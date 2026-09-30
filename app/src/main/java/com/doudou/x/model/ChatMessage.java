package com.doudou.x.model;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * 单条聊天消息。
 */
public class ChatMessage {

    public static final int ROLE_USER = 0;
    public static final int ROLE_AI = 1;

    private static final String KEY_ROLE = "role";
    private static final String KEY_CONTENT = "content";
    private static final String KEY_TIMESTAMP = "timestamp";
    private static final String KEY_RAW = "raw";
    private static final String KEY_THINKING = "thinking";
    private static final String KEY_ERROR = "error";

    private final int role;
    private String content;
    private final long timestamp;
    /** 是否正在流式输出中（仅 AI 消息使用，不持久化）。 */
    private boolean streaming;
    /** API 原始返回字符串（仅真实接口的 AI 消息有值）。 */
    private String rawResponse;
    /** 模型思考过程（reasoning / thinking），与正式回答分开存储，不参与上下文回传。 */
    private String thinking;
    /** 是否为错误信息：错误内容不发进下一次请求，避免污染上下文。 */
    private boolean error;
    /** 思考块是否展开（仅 UI 状态，不持久化）。 */
    private boolean thinkingExpanded;

    public ChatMessage(int role, String content) {
        this(role, content, System.currentTimeMillis());
    }

    public ChatMessage(int role, String content, long timestamp) {
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
    }

    public int getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public boolean isStreaming() {
        return streaming;
    }

    public void setStreaming(boolean streaming) {
        this.streaming = streaming;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public void setRawResponse(String rawResponse) {
        this.rawResponse = rawResponse;
    }

    public String getThinking() {
        return thinking;
    }

    public void setThinking(String thinking) {
        this.thinking = thinking;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }

    public boolean isThinkingExpanded() {
        return thinkingExpanded;
    }

    public void setThinkingExpanded(boolean thinkingExpanded) {
        this.thinkingExpanded = thinkingExpanded;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_ROLE, role);
        obj.put(KEY_CONTENT, content);
        obj.put(KEY_TIMESTAMP, timestamp);
        if (rawResponse != null) {
            obj.put(KEY_RAW, rawResponse);
        }
        if (thinking != null && !thinking.isEmpty()) {
            obj.put(KEY_THINKING, thinking);
        }
        if (error) {
            obj.put(KEY_ERROR, true);
        }
        return obj;
    }

    public static ChatMessage fromJson(JSONObject obj) {
        ChatMessage msg = new ChatMessage(
                obj.optInt(KEY_ROLE, ROLE_AI),
                obj.optString(KEY_CONTENT, ""),
                obj.optLong(KEY_TIMESTAMP, System.currentTimeMillis()));
        msg.setStreaming(false);
        String raw = obj.optString(KEY_RAW, null);
        msg.setRawResponse(raw);
        msg.setThinking(obj.optString(KEY_THINKING, ""));
        msg.setError(obj.optBoolean(KEY_ERROR, false));
        return msg;
    }
}
