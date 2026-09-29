package com.doudou.x.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一段对话（包含多条消息）。
 */
public class Conversation {

    private static final String KEY_ID = "id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_UPDATE_TIME = "updateTime";
    private static final String KEY_MESSAGES = "messages";

    private final String id;
    private String title;
    private long updateTime;
    private final List<ChatMessage> messages = new ArrayList<>();

    public Conversation() {
        this.id = UUID.randomUUID().toString();
        this.title = "";
        this.updateTime = System.currentTimeMillis();
    }

    private Conversation(String id, String title, long updateTime) {
        this.id = id;
        this.title = title;
        this.updateTime = updateTime;
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        updateTime = System.currentTimeMillis();
    }

    /** 用第一条用户消息生成标题（超长截断）。 */
    public void deriveTitleIfNeeded() {
        if (title != null && !title.isEmpty()) {
            return;
        }
        for (ChatMessage msg : messages) {
            if (msg.getRole() == ChatMessage.ROLE_USER) {
                String text = msg.getContent().trim();
                title = text.length() > 16 ? text.substring(0, 16) + "…" : text;
                return;
            }
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_ID, id);
        obj.put(KEY_TITLE, title);
        obj.put(KEY_UPDATE_TIME, updateTime);
        JSONArray arr = new JSONArray();
        for (ChatMessage msg : messages) {
            arr.put(msg.toJson());
        }
        obj.put(KEY_MESSAGES, arr);
        return obj;
    }

    public static Conversation fromJson(JSONObject obj) {
        Conversation c = new Conversation(
                obj.optString(KEY_ID, UUID.randomUUID().toString()),
                obj.optString(KEY_TITLE, ""),
                obj.optLong(KEY_UPDATE_TIME, System.currentTimeMillis()));
        JSONArray arr = obj.optJSONArray(KEY_MESSAGES);
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject msgObj = arr.optJSONObject(i);
                if (msgObj != null) {
                    c.messages.add(ChatMessage.fromJson(msgObj));
                }
            }
        }
        return c;
    }
}
