package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.doudou.x.model.Conversation;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 历史对话本地持久化（SharedPreferences + JSON）。
 */
public class ConversationStore {

    private static final String PREF_NAME = "doudou_conversations";
    private static final String KEY_LIST = "conversation_list";

    private static ConversationStore instance;
    private final SharedPreferences prefs;

    private ConversationStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized ConversationStore getInstance(Context context) {
        if (instance == null) {
            instance = new ConversationStore(context);
        }
        return instance;
    }

    /** 读取全部会话，按更新时间倒序。 */
    public List<Conversation> loadAll() {
        List<Conversation> list = new ArrayList<>();
        String json = prefs.getString(KEY_LIST, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    Conversation c = Conversation.fromJson(obj);
                    if (!c.getMessages().isEmpty()) {
                        list.add(c);
                    }
                }
            }
        } catch (JSONException ignored) {
            // 数据损坏时返回空列表
        }
        Collections.sort(list, new Comparator<Conversation>() {
            @Override
            public int compare(Conversation a, Conversation b) {
                return Long.compare(b.getUpdateTime(), a.getUpdateTime());
            }
        });
        return list;
    }

    /** 新增或更新一条会话。 */
    public void upsert(Conversation conversation) {
        List<Conversation> list = loadAll();
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getId().equals(conversation.getId())) {
                list.set(i, conversation);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(conversation);
        }
        saveAll(list);
    }

    public void delete(String conversationId) {
        List<Conversation> list = loadAll();
        List<Conversation> kept = new ArrayList<>();
        for (Conversation c : list) {
            if (!c.getId().equals(conversationId)) {
                kept.add(c);
            }
        }
        saveAll(kept);
    }

    public void clear() {
        prefs.edit().remove(KEY_LIST).apply();
    }

    private void saveAll(List<Conversation> list) {
        JSONArray arr = new JSONArray();
        for (Conversation c : list) {
            try {
                arr.put(c.toJson());
            } catch (JSONException ignored) {
                // 跳过序列化失败的会话
            }
        }
        prefs.edit().putString(KEY_LIST, arr.toString()).apply();
    }
}
