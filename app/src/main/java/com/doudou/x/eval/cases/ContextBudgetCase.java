package com.doudou.x.eval.cases;

import com.doudou.x.ai.context.ContextAssembler;
import com.doudou.x.ai.context.ContextBudget;
import com.doudou.x.ai.context.ContextMessage;
import com.doudou.x.ai.context.ContextPlan;
import com.doudou.x.eval.EvalCase;
import com.doudou.x.eval.EvalResult;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import java.util.ArrayList;
import java.util.List;

/** 上下文预算用例：预算扣减、最近轮保护、工具调用与结果同进同退。 */
public final class ContextBudgetCase implements EvalCase {

    @Override
    public String id() {
        return "context.budget";
    }

    @Override
    public String description() {
        return "分层预算不超窗、最近 3 轮保留、工具调用与结果不被拆散";
    }

    @Override
    public List<EvalResult> run() {
        List<EvalResult> out = new ArrayList<>();

        ContextBudget budget = ContextBudget.defaults();
        int history = budget.historyBudget(0, 0, 0, 0);
        EvalResult.add(out, id() + ".满额历史预算",
                history == ContextBudget.DEFAULT_TOTAL - 1024,
                "history=" + history);

        int squeezed = budget.historyBudget(100000, 100000, 100000, 100000);
        EvalResult.add(out, id() + ".各层上限保护",
                squeezed > 0 && squeezed <= history,
                "squeezed=" + squeezed + "，full=" + history);

        ContextBudget small = ContextBudget.forWindow(2048);
        EvalResult.add(out, id() + ".小窗口缩放",
                small.getTotalTokens() == 2048 && small.getKeepRecentTurns() == 3,
                "total=" + small.getTotalTokens());

        // 历史里含一次工具调用，预算调小后应丢弃旧组，但工具调用与其结果必须同进同退
        List<ChatMessage> messages = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            messages.add(new ChatMessage(ChatMessage.ROLE_USER,
                    "这是第 " + i + " 条很长的历史消息，用来把上下文预算挤满。" + repeat("填充", 40)));
        }
        ToolCall call = new ToolCall();
        call.setId("call_pair");
        call.setName("get_time");
        call.setArguments("{}");
        ChatMessage assistantCall = new ChatMessage(ChatMessage.ROLE_AI, "");
        assistantCall.setToolCalls(singleton(call));
        messages.add(assistantCall);
        ChatMessage toolResult = new ChatMessage(ChatMessage.ROLE_TOOL, "2026-11-19 20:00");
        toolResult.setToolCallId("call_pair");
        messages.add(toolResult);
        messages.add(new ChatMessage(ChatMessage.ROLE_USER, "最后一句提问"));

        ContextPlan plan = new ContextAssembler(small).assemble(
                messages, "你是助手", null, 0, null, null);

        boolean lastKept = false;
        int toolSeen = 0;
        int callSeen = 0;
        for (ContextMessage item : plan.messages) {
            if ("最后一句提问".equals(item.getContent())) {
                lastKept = true;
            }
            if (ContextMessage.ROLE_TOOL.equals(item.getRole())) {
                toolSeen++;
            }
            if (item.getSource() != null && item.getSource().getToolCalls() != null
                    && !item.getSource().getToolCalls().isEmpty()) {
                callSeen++;
            }
        }
        EvalResult.add(out, id() + ".最近提问保留", lastKept, "");
        EvalResult.add(out, id() + ".旧历史被裁剪", plan.stats.droppedCount > 0,
                "dropped=" + plan.stats.droppedCount);
        EvalResult.add(out, id() + ".工具调用与结果同进同退", toolSeen == callSeen,
                "tool=" + toolSeen + "，call=" + callSeen);

        int assembled = plan.stats.totalTokens();
        EvalResult.add(out, id() + ".组装统计非负", assembled >= 0 && !plan.messages.isEmpty(),
                "total≈" + assembled);
        return out;
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static <T> List<T> singleton(T item) {
        List<T> list = new ArrayList<>();
        list.add(item);
        return list;
    }
}
