package com.doudou.x.ai.context;

import org.json.JSONArray;

import java.util.List;

/**
 * 一次上下文组装的结果：最终下发的消息列表 + 分层的 token 统计。
 */
public final class ContextPlan {

    /** 已含系统提示词与摘要，顺序即下发顺序。 */
    public final List<ContextMessage> messages;
    public final Stats stats;

    ContextPlan(List<ContextMessage> messages, Stats stats) {
        this.messages = messages;
        this.stats = stats;
    }

    /** 转成请求体里的 messages 数组。 */
    public JSONArray toJsonArray() {
        JSONArray array = new JSONArray();
        for (ContextMessage message : messages) {
            array.put(message.toJson());
        }
        return array;
    }

    /** 组装过程的统计，同时用于判断是否需要触发压缩。 */
    public static final class Stats {
        public int systemTokens;
        public int toolTokens;
        public int summaryTokens;
        /** 长期记忆块（ai.memory 召回）。 */
        public int memoryTokens;
        public int historyTokens;
        /** 因预算不足被整条丢弃的历史条数。 */
        public int droppedCount;
        /** 被内容截断的条数。 */
        public int truncatedCount;
        /** 受保护的最近几轮自身就超预算，没能完全装下。 */
        public boolean overflow;

        public int totalTokens() {
            return systemTokens + toolTokens + summaryTokens
                    + memoryTokens + historyTokens;
        }
    }
}
