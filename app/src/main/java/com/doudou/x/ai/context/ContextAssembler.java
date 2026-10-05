package com.doudou.x.ai.context;

import com.doudou.x.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装器：按分层预算裁剪对话历史。
 *
 * <p>裁剪顺序（先砍谁）：更老的历史 → 摘要 → 最后才动受保护的最近几轮。
 * 系统提示词与工具声明只做上限保护，不参与裁剪。
 *
 * <p>工具调用必须和它的结果同进同退：只留下 assistant 的 tool_calls 而丢掉
 * role=tool 的结果，多数服务端会直接返回 400，所以裁剪以「组」为单位。
 */
public final class ContextAssembler {

    private final ContextBudget budget;

    public ContextAssembler(ContextBudget budget) {
        this.budget = budget;
    }

    /**
     * 组装一次请求的上下文。
     *
     * @param history      对话历史，按时间正序
     * @param systemPrompt 系统提示词，为空则不下发
     * @param toolsJson    tools[] 的 JSON 文本，不带工具时为 null
     * @param toolCount    工具条数
     * @param summary      该会话已有的历史摘要，没有则传空
     * @param memory       长期记忆块（ai.memory 召回），没有则传空
     */
    public ContextPlan assemble(List<ChatMessage> history, String systemPrompt,
                                String toolsJson, int toolCount,
                                String summary, String memory) {
        ContextPlan.Stats stats = new ContextPlan.Stats();
        List<ContextMessage> out = new ArrayList<>();

        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            ContextMessage item = ContextMessage.system(systemPrompt.trim());
            stats.systemTokens = item.getTokens();
            out.add(item);
        }
        stats.toolTokens = TokenEstimator.estimateTools(toolsJson, toolCount);

        List<ContextMessage> summaryItems = new ArrayList<>();
        if (summary != null && !summary.trim().isEmpty()) {
            ContextMessage item = ContextMessage.summary(summary.trim());
            stats.summaryTokens = item.getTokens();
            summaryItems.add(item);
        }

        List<ContextMessage> memoryItems = new ArrayList<>();
        if (memory != null && !memory.trim().isEmpty()) {
            ContextMessage item = ContextMessage.memory(memory.trim());
            stats.memoryTokens = item.getTokens();
            memoryItems.add(item);
        }

        List<List<ContextMessage>> groups = group(history);
        int protectFrom = protectFrom(groups, budget.getKeepRecentTurns());
        int available = budget.historyBudget(
                stats.systemTokens, stats.toolTokens,
                stats.summaryTokens, stats.memoryTokens);

        // 从最新往回装：装不下就停，剩下更老的整组丢弃
        int used = 0;
        int firstKept = groups.size();
        for (int i = groups.size() - 1; i >= 0; i--) {
            int groupTokens = groupTokens(groups.get(i));
            if (i >= protectFrom) {
                used += groupTokens; // 受保护窗口：超预算也要带上
                firstKept = i;
                continue;
            }
            if (used + groupTokens > available) {
                break;
            }
            used += groupTokens;
            firstKept = i;
        }

        // 受保护窗口自身就可能超预算：从最早的一条开始截断，直到装得下
        int overflow = used - available;
        for (int i = firstKept; i < groups.size() && overflow > 0; i++) {
            List<ContextMessage> group = groups.get(i);
            for (int j = 0; j < group.size() && overflow > 0; j++) {
                ContextMessage item = group.get(j);
                if (item.getKind() != ContextMessage.KIND_HISTORY) {
                    continue;
                }
                int before = item.getTokens();
                ContextMessage cut = item.truncateTo(before - overflow);
                group.set(j, cut);
                overflow -= (before - cut.getTokens());
                stats.truncatedCount++;
            }
        }
        stats.overflow = overflow > 0;

        for (int i = 0; i < firstKept; i++) {
            stats.droppedCount += groups.get(i).size();
        }
        // 下发顺序：系统提示词 → 长期记忆 → 本次摘要 → 历史。
        // 先给全局背景（人设、关于用户的长期事实），再给本次会话背景，最后才是最近对话
        out.addAll(memoryItems);
        out.addAll(summaryItems);
        for (int i = firstKept; i < groups.size(); i++) {
            for (ContextMessage item : groups.get(i)) {
                out.add(item);
                stats.historyTokens += item.getTokens();
            }
        }
        return new ContextPlan(out, stats);
    }

    /**
     * 把历史切成「组」：一条 assistant 的工具调用连同它后续的所有工具结果算一组，
     * 其余每条自成一组。工具结果挂在调用所在的组里，裁剪时不会分离。
     */
    private static List<List<ContextMessage>> group(List<ChatMessage> history) {
        List<List<ContextMessage>> groups = new ArrayList<>();
        if (history == null) {
            return groups;
        }
        for (ChatMessage msg : history) {
            ContextMessage item = ContextMessage.fromChat(msg);
            if (item == null) {
                continue;
            }
            if (msg.getRole() == ChatMessage.ROLE_TOOL && !groups.isEmpty()) {
                groups.get(groups.size() - 1).add(item);
                continue;
            }
            List<ContextMessage> group = new ArrayList<>();
            group.add(item);
            groups.add(group);
        }
        return groups;
    }

    /** 受保护窗口的起始组下标：最后 keepTurns 条 user 消息所在的组都要留下。 */
    private static int protectFrom(List<List<ContextMessage>> groups, int keepTurns) {
        int found = 0;
        int from = groups.size();
        for (int i = groups.size() - 1; i >= 0; i--) {
            for (ContextMessage item : groups.get(i)) {
                if (ContextMessage.ROLE_USER.equals(item.getRole())) {
                    from = i;
                    found++;
                    break;
                }
            }
            if (found >= keepTurns) {
                break;
            }
        }
        return from;
    }

    private static int groupTokens(List<ContextMessage> group) {
        int total = 0;
        for (ContextMessage item : group) {
            total += item.getTokens();
        }
        return total;
    }

}
