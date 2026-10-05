package com.doudou.x.ai.memory;

import android.content.Context;
import android.util.Log;

import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 记忆系统门面：对外只有三件事——初始化、召回一段可注入的文本、按需后台抽取。
 *
 * <p>三层记忆的分工：
 * <ul>
 *   <li><b>情景记忆</b>：某次对话聊了什么、结论是什么。忘得最快（半衰期 3 天）；</li>
 *   <li><b>语义记忆</b>：关于用户的稳定事实。长期有效（半衰期 30 天）；</li>
 *   <li><b>程序记忆</b>：用户要求的做事方式与输出格式。几乎不忘（半衰期 365 天），
 *       且<b>不参与查询匹配、总是带进上下文</b>。</li>
 * </ul>
 *
 * <p>与上下文模块共用同一套节拍：召回是同步的（每次请求都要用），
 * 抽取是后台异步的（延迟一轮生效，失败静默跳过）。
 */
public final class MemoryManager {

    private static final String LOG_TAG = OpenAiEngine.LOG_TAG;

    private static final MemoryManager INSTANCE = new MemoryManager();

    /** 单次召回上限（含固定带上的程序记忆）。 */
    private static final int RECALL_LIMIT = 5;
    /** 生成"已记住什么"摘要时最多列几条，避免抽取提示词过长。 */
    private static final int KNOWN_SUMMARY_LIMIT = 30;

    private ApiConfigStore config;
    private MemoryStore store;
    private MemoryExtractor extractor;
    private boolean initialized;

    /** 正在抽取的会话，避免同一会话重复发起。 */
    private final Set<String> extracting = new HashSet<>();

    private MemoryManager() {
    }

    public static MemoryManager get() {
        return INSTANCE;
    }

    /**
     * 初始化一次（主页创建引擎之后调用）。
     * 抽取用独立的引擎实例，不与主对话、生成标题的请求互相取消。
     */
    public void init(Context context) {
        if (initialized) {
            return;
        }
        config = ApiConfigStore.getInstance(context);
        store = new MemoryStore(context);
        extractor = new MemoryExtractor(new OpenAiEngine(config));
        initialized = true;
    }

    /**
     * 召回与当前提问相关的记忆，返回可直接拼进上下文的文本块。
     *
     * <p>命中会 {@link MemoryItem#touch()} 并写回，
     * 这样常用的记忆不会被遗忘机制淘汰掉。
     *
     * @param query 当前用户提问
     * @return 格式化好的文本；没有可用记忆时返回空串
     */
    public String recallBlock(String query) {
        if (!initialized || store == null) {
            return "";
        }
        List<MemoryItem> all = store.loadAll();
        List<MemoryItem> items = MemoryRetriever.recall(all, query, RECALL_LIMIT);
        if (items.isEmpty()) {
            return "";
        }
        for (MemoryItem item : items) {
            item.touch();
        }
        store.saveAll(all);
        Log.d(LOG_TAG, "记忆召回：" + items.size() + " 条（库内共 " + all.size() + " 条）");
        return format(items);
    }

    /**
     * 需要抽取就后台抽一次；结果下一次请求生效，本轮不受影响。
     *
     * @param history     完整对话历史
     * @param sessionKey  会话标识，与上下文模块用的是同一套 key
     */
    public void maybeExtractAsync(final List<ChatMessage> history, final String sessionKey) {
        if (!initialized || extractor == null || store == null
                || config == null || !config.isReady()) {
            return; // 未配置真实接口（走本地模拟）时不抽取
        }
        if (history == null || history.isEmpty()) {
            return;
        }
        final int from = store.getExtractedCount(sessionKey);
        if (history.size() - from < MemoryExtractor.MIN_NEW_MESSAGES) {
            return; // 增量不够，不值得为它发一次请求
        }
        synchronized (extracting) {
            if (!extracting.add(sessionKey)) {
                return; // 该会话已在抽取中
            }
        }
        final int end = history.size();
        extractor.extractAsync(history, from, summarizeKnown(), store, sessionKey, end,
                new Runnable() {
                    @Override
                    public void run() {
                        synchronized (extracting) {
                            extracting.remove(sessionKey);
                        }
                    }
                });
    }

    /** 库内全部记忆（供设置页展示或清除）。 */
    public List<MemoryItem> all() {
        return store == null ? new java.util.ArrayList<MemoryItem>() : store.loadAll();
    }

    public void clear() {
        if (store != null) {
            store.clear();
        }
    }

    /**
     * 把已记住的内容列成摘要，作为抽取提示词的输入之一——
     * 告诉模型"这些已经有了"，它才不会反复抽出同一条。
     */
    private String summarizeKnown() {
        List<MemoryItem> all = store.loadAll();
        if (all.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (MemoryItem item : all) {
            if (count >= KNOWN_SUMMARY_LIMIT) {
                break;
            }
            sb.append("· ");
            if (!item.getKey().isEmpty()) {
                sb.append(item.getKey()).append("：");
            }
            sb.append(item.getContent()).append('\n');
            count++;
        }
        return sb.toString();
    }

    /** 渲染成模型好读的形式：按类型标注，并声明不要复述。 */
    private static String format(List<MemoryItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下是关于这位用户的长期记忆，回答时自然参考即可，不要复述这些内容：\n");
        for (MemoryItem item : items) {
            sb.append("· [").append(item.getType().getLabel()).append("] ");
            if (!item.getKey().isEmpty()) {
                sb.append(item.getKey()).append("：");
            }
            sb.append(item.getContent()).append('\n');
        }
        return sb.toString();
    }
}
