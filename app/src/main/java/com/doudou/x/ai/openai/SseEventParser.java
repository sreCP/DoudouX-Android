package com.doudou.x.ai.openai;

import com.doudou.x.util.JsonUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 流式协议解析层：把一行报文变成 {@link SseEvent}。
 *
 * <p>纯函数实现，不持有状态、不碰网络、不依赖 Android，因此可以被评测
 * 直接调用（eval 里的 sse.parse 用例测的就是这份线上代码），
 * 也可以放到 JVM 上单跑。
 *
 * <p>同时兼容两种协议：
 * <ul>
 *   <li>OpenAI 兼容 SSE：{@code data: {...}}，结束标记 {@code data: [DONE]}；</li>
 *   <li>Ollama 原生 NDJSON：一行一个 JSON，靠 {@code done=true} 结束。</li>
 * </ul>
 */
public final class SseEventParser {

    private static final String DATA_PREFIX = "data:";
    private static final String DONE_PAYLOAD = "[DONE]";

    private SseEventParser() {
    }

    /** 解析一条 SSE 事件正文；非 JSON 或没有 choices 时返回 null。 */
    public static SseEvent parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(payload);
        } catch (Exception e) {
            return null;
        }
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return null;
        }
        SseEvent event = new SseEvent();
        String reason = choice.optString("finish_reason", null);
        if (!JsonUtils.isLiteralNull(reason) && !reason.isEmpty()) {
            event.finishReason = reason;
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            event.answer = JsonUtils.optText(delta, "content");
            // 兼容思考型模型：reasoning（Ollama / Qwen3）与 reasoning_content（DeepSeek）
            event.reasoning = JsonUtils.optText(delta, "reasoning");
            if (event.reasoning.isEmpty()) {
                event.reasoning = JsonUtils.optText(delta, "reasoning_content");
            }
            JSONArray calls = delta.optJSONArray("tool_calls");
            if (calls != null) {
                event.toolCallDeltas = calls.length();
                event.toolCallArray = calls;
            }
        }
        return event;
    }

    /** 是否是一条 SSE 的 data 行（{@code event:} / 注释 / 空行都不算）。 */
    public static boolean isDataLine(String line) {
        return line != null && line.startsWith(DATA_PREFIX);
    }

    /** 去掉 {@code data:} 前缀并 trim。 */
    public static String payloadOf(String line) {
        if (!isDataLine(line)) {
            return line == null ? "" : line.trim();
        }
        return line.substring(DATA_PREFIX.length()).trim();
    }

    /** 是否为流结束标记 {@code [DONE]}。 */
    public static boolean isDone(String payload) {
        return DONE_PAYLOAD.equals(payload);
    }

    /**
     * 解析 Ollama 原生接口的一行 NDJSON
     * （{@code /api/chat} 的 message.content、{@code /api/generate} 的 response）。
     *
     * @return 本行的增量；不是这类数据返回 null
     */
    public static NativeChunk parseNative(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            String content = null;
            String thinking = null;
            JSONObject message = obj.optJSONObject("message");
            if (message != null) {
                content = message.optString("content", null);
                thinking = message.optString("thinking", null);
            }
            if (content == null) {
                content = obj.optString("response", null); // /api/generate
            }
            if (thinking == null) {
                thinking = obj.optString("thinking", null);
            }
            if (content == null && thinking == null) {
                return null;
            }
            return new NativeChunk(content == null ? "" : content,
                    thinking == null ? "" : thinking);
        } catch (Exception e) {
            return null;
        }
    }

    /** Ollama 原生接口用 {@code done=true} 标记结束。 */
    public static boolean isNativeDone(String line) {
        try {
            return new JSONObject(line).optBoolean("done", false);
        } catch (Exception e) {
            return false;
        }
    }

    /** 一行原生 NDJSON 解析出来的增量。 */
    public static final class NativeChunk {
        public final String answer;
        public final String reasoning;

        NativeChunk(String answer, String reasoning) {
            this.answer = answer;
            this.reasoning = reasoning;
        }
    }
}
