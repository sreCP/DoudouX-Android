package com.doudou.x.ai.context;

import android.content.Context;
import android.util.Log;

import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.ai.ToolRegistry;
import com.doudou.x.ai.memory.MemoryManager;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;

import org.json.JSONArray;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 上下文模块门面：对引擎只暴露 {@link #buildMessages} 一个入口。
 *
 * <p>分工：
 * <ul>
 *   <li>每次请求同步做一次分层裁剪，保证不超预算；</li>
 *   <li>发现历史偏长就后台触发一次压缩，结果写库，下一次请求才用上；</li>
 *   <li>压缩失败不影响本轮，最坏情况退化成裁剪。</li>
 * </ul>
 */
public final class ContextManager {

    private static final String LOG_TAG = OpenAiEngine.LOG_TAG;

    private static final ContextManager INSTANCE = new ContextManager();

    private ContextBudget budget = ContextBudget.defaults();
    private ApiConfigStore config;
    private SummaryStore store;
    private RollingCompressor compressor;
    private boolean initialized;

    /** 正在压缩的会话，避免同一会话重复发起。 */
    private final Set<String> compacting = new HashSet<>();

    private ContextManager() {
    }

    public static ContextManager get() {
        return INSTANCE;
    }

    /**
     * 初始化一次（主页创建引擎之后调用）。
     * 压缩用独立的引擎实例，不与主对话、生成标题的请求互相取消。
     */
    public void init(Context context) {
        if (initialized) {
            return;
        }
        config = ApiConfigStore.getInstance(context);
        store = new SummaryStore(context);
        compressor = new RollingCompressor(new OpenAiEngine(config), store);
        initialized = true;
    }

    /** 覆盖默认预算，例如服务端窗口更大时。 */
    public void setBudget(ContextBudget budget) {
        if (budget != null) {
            this.budget = budget;
        }
    }

    /**
     * 组装本次请求的消息数组，这是引擎侧唯一需要调用的方法。
     *
     * @param history      本次要回传的对话历史
     * @param systemPrompt 系统提示词，为空则不下发
     * @param withTools    是否带工具声明
     */
    public JSONArray buildMessages(List<ChatMessage> history, String systemPrompt,
                                   boolean withTools) {
        String sessionKey = sessionKey(history);
        SummaryStore.Summary summary =
                store == null ? SummaryStore.Summary.empty() : store.load(sessionKey);
        JSONArray tools = withTools ? ToolRegistry.toolsJson() : null;
        // 长期记忆：按当前提问召回，与摘要分开占一层预算
        String memory = MemoryManager.get().recallBlock(lastUserText(history));
        ContextPlan plan = new ContextAssembler(budget).assemble(
                history,
                systemPrompt,
                tools == null ? null : tools.toString(),
                tools == null ? 0 : tools.length(),
                summary.text,
                memory);
        Log.d(LOG_TAG, "上下文组装：合计≈" + plan.stats.totalTokens() + " token，系统="
                + plan.stats.systemTokens + "，工具=" + plan.stats.toolTokens
                + "，记忆=" + plan.stats.memoryTokens
                + "，摘要=" + plan.stats.summaryTokens + "，历史=" + plan.stats.historyTokens
                + "，丢弃=" + plan.stats.droppedCount + "，截断=" + plan.stats.truncatedCount
                + (plan.stats.overflow ? "，保护窗口仍超预算" : ""));
        maybeCompact(history, sessionKey, summary);
        // 记忆抽取：同样后台跑、只处理增量，结果下一次请求才用上
        MemoryManager.get().maybeExtractAsync(history, sessionKey);
        return plan.toJsonArray();
    }

    /** 当前这一轮的提问，作为记忆召回的查询词。 */
    private static String lastUserText(List<ChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getRole() == ChatMessage.ROLE_USER) {
                return msg.getContent() == null ? "" : msg.getContent();
            }
        }
        return "";
    }

    /** 需要压缩就后台压一次；结果下一次请求生效，本轮不受影响。 */
    private void maybeCompact(final List<ChatMessage> history, final String sessionKey,
                              final SummaryStore.Summary summary) {
        if (!initialized || compressor == null || config == null || !config.isReady()) {
            return; // 未配置真实接口（走本地模拟）时不压缩
        }
        if (!compressor.shouldCompact(history, summary, budget)) {
            return;
        }
        synchronized (compacting) {
            if (!compacting.add(sessionKey)) {
                return; // 该会话已在压缩中
            }
        }
        compressor.compactAsync(history, sessionKey, summary, budget, new Runnable() {
            @Override
            public void run() {
                synchronized (compacting) {
                    compacting.remove(sessionKey);
                }
            }
        });
    }

    /**
     * 会话标识：首条消息的时间戳 + 内容长度。
     * 同一会话里首条消息恒定，跨轮、跨启动都能命中同一份摘要。
     */
    private static String sessionKey(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return "empty";
        }
        ChatMessage first = history.get(0);
        String content = first.getContent();
        return first.getTimestamp() + "_" + (content == null ? 0 : content.length());
    }
}
