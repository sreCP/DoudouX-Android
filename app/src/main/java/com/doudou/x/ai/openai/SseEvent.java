package com.doudou.x.ai.openai;

import org.json.JSONArray;

/**
 * 一条流式事件解析后的全部可用信息。
 *
 * <p>把原来分散的「取增量 / 取结束原因 / 取工具分片」合成一次解析：
 * 既能为日志提供一条事件的完整元信息，也省掉同一个 JSONObject 被反复构造的开销。
 */
public final class SseEvent {

    /** 正式回答增量，无增量为空串。 */
    public String answer = "";

    /** 思考过程增量，无增量为空串。 */
    public String reasoning = "";

    /** 服务端结束原因（stop / length / tool_calls），非 null 表示本条带该字段。 */
    public String finishReason;

    /** 本条事件携带的 tool_calls 分片数量。 */
    public int toolCallDeltas;

    /** 本条事件的 tool_calls 原始数组，供合并分片使用。 */
    public JSONArray toolCallArray;

    /** 是否携带了可用内容（正文或思考）。 */
    public boolean hasContent() {
        return !answer.isEmpty() || !reasoning.isEmpty();
    }
}
