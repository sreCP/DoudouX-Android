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

    private final int role;
    private String content;
    private final long timestamp;
    /** 是否正在流式输出中（仅 AI 消息使用，不持久化）。 */
    private boolean streaming;
    /** API 原始返回字符串（仅真实接口的 AI 消息有值）。 */
    private String rawResponse;

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

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_ROLE, role);
        obj.put(KEY_CONTENT, content);
        obj.put(KEY_TIMESTAMP, timestamp);
        if (rawResponse != null) {
            obj.put(KEY_RAW, rawResponse);
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
        return msg;
    }
}
