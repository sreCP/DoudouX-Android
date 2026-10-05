package com.doudou.x.ai.memory;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 记忆的本地存储。
 *
 * <p>三件事值得单独说明：
 * <ul>
 *   <li><b>冲突消解</b>：同类型 + 同 key 的新记忆覆盖旧的，而不是并存。
 *       否则记忆库里会同时存在"用户是 Android 工程师"和"用户是后端工程师"，
 *       被一起召回时模型反而更困惑；</li>
 *   <li><b>遗忘</b>：容量或条目超限时按 {@link MemoryItem#score} 淘汰最低的，
 *       让不常用的记忆自然消失，而不是让库无限膨胀；</li>
 *   <li><b>抽取进度</b>：按会话记录已抽取到第几条，下次只处理增量。</li>
 * </ul>
 */
public final class MemoryStore {

    private static final String PREF_NAME = "doudou_memory";
    private static final String KEY_ITEMS = "items";
    /** 记忆条数上限，超出按遗忘分淘汰。 */
    private static final int MAX_ITEMS = 200;
    /** 抽取进度键前缀：每个会话一个。 */
    private static final String KEY_PROGRESS = "progress_";

    private final SharedPreferences prefs;

    public MemoryStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 读取全部记忆（按写入顺序，不排序）。 */
    public List<MemoryItem> loadAll() {
        List<MemoryItem> items = new ArrayList<>();
        String json = prefs.getString(KEY_ITEMS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                MemoryItem item = MemoryItem.fromJson(arr.optJSONObject(i));
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception ignored) {
            // 数据损坏时返回空列表，记忆丢了不影响对话
        }
        return items;
    }

    /**
     * 写入一条记忆。
     *
     * @return true 表示新增，false 表示覆盖了同 key 的旧记忆
     */
    public boolean upsert(MemoryItem incoming) {
        if (incoming == null) {
            return false;
        }
        List<MemoryItem> items = loadAll();
        boolean replaced = false;
        for (int i = 0; i < items.size(); i++) {
            MemoryItem old = items.get(i);
            if (isSame(old, incoming)) {
                // 冲突消解：保留原条目的创建时间与命中数，只换内容
                old.overwrite(incoming.getContent(), incoming.getConfidence());
                items.set(i, old);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            items.add(incoming);
        }
        saveAll(items);
        return !replaced;
    }

    /** 同一类型且 key 相同即视为同一条记忆的不同版本。 */
    private static boolean isSame(MemoryItem a, MemoryItem b) {
        if (a == null || b == null || a.getType() != b.getType()) {
            return false;
        }
        String ka = a.getKey();
        String kb = b.getKey();
        if (ka.isEmpty() || kb.isEmpty()) {
            return false; // 没有 key 的条目无法判定同一性，只能新增
        }
        return ka.equals(kb);
    }

    /** 批量写回，并顺带做一次遗忘淘汰。 */
    public void saveAll(List<MemoryItem> items) {
        if (items == null) {
            return;
        }
        List<MemoryItem> kept = trim(items);
        JSONArray arr = new JSONArray();
        for (MemoryItem item : kept) {
            arr.put(item.toJson());
        }
        prefs.edit().putString(KEY_ITEMS, arr.toString()).apply();
    }

    /** 超出上限时按遗忘分从低到高淘汰；分数相同时保留更新的。 */
    private static List<MemoryItem> trim(List<MemoryItem> items) {
        if (items.size() <= MAX_ITEMS) {
            return items;
        }
        final long now = System.currentTimeMillis();
        List<MemoryItem> sorted = new ArrayList<>(items);
        java.util.Collections.sort(sorted, new java.util.Comparator<MemoryItem>() {
            @Override
            public int compare(MemoryItem a, MemoryItem b) {
                return Float.compare(a.score(now), b.score(now));
            }
        });
        int removeCount = sorted.size() - MAX_ITEMS;
        List<MemoryItem> victims = sorted.subList(0, removeCount);
        List<MemoryItem> kept = new ArrayList<>(items);
        for (MemoryItem victim : victims) {
            kept.remove(victim);
        }
        return kept;
    }

    public void clear() {
        prefs.edit().remove(KEY_ITEMS).apply();
        // 抽取进度一并清掉，否则清空后不会再抽取
        java.util.Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        for (String key : all.keySet()) {
            if (key.startsWith(KEY_PROGRESS)) {
                editor.remove(key);
            }
        }
        editor.apply();
    }

    /** 该会话已抽取到第几条。 */
    public int getExtractedCount(String sessionKey) {
        return prefs.getInt(KEY_PROGRESS + sessionKey, 0);
    }

    /** 记录该会话的抽取进度。 */
    public void setExtractedCount(String sessionKey, int count) {
        prefs.edit().putInt(KEY_PROGRESS + sessionKey, count).apply();
    }

    /** 会话被删掉时清理它的抽取进度。 */
    public void removeProgress(String sessionKey) {
        prefs.edit().remove(KEY_PROGRESS + sessionKey).apply();
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
