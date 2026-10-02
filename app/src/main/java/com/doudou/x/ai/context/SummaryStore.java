package com.doudou.x.ai.context;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话摘要的本地存储。
 *
 * <p>一并记录「已压缩到第几条」，下一次只压增量部分，这是滚动压缩能省 token 的关键。
 * 摘要按会话 key 存，跨轮、跨启动都能命中同一份。
 */
public final class SummaryStore {

    private static final String PREF_NAME = "doudou_context_summary";
    private static final String KEY_TEXT = "text";
    private static final String KEY_COUNT = "count";
    private static final String KEY_TIME = "time";
    /** 最多保留的会话数，超出丢最旧的，避免随时间无限增长。 */
    private static final int MAX_ENTRIES = 50;

    private final SharedPreferences prefs;

    public SummaryStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 一条会话的压缩进度。 */
    public static final class Summary {
        /** 摘要正文，没有时为空串。 */
        public final String text;
        /** 已被压进摘要的历史条数，下一次从这里继续。 */
        public final int compressedCount;

        Summary(String text, int compressedCount) {
            this.text = text == null ? "" : text;
            this.compressedCount = Math.max(0, compressedCount);
        }

        public static Summary empty() {
            return new Summary("", 0);
        }

        public boolean hasSummary() {
            return !text.isEmpty();
        }
    }

    public Summary load(String sessionKey) {
        String json = prefs.getString(sessionKey, null);
        if (json == null) {
            return Summary.empty();
        }
        try {
            JSONObject obj = new JSONObject(json);
            return new Summary(obj.optString(KEY_TEXT, ""), obj.optInt(KEY_COUNT, 0));
        } catch (Exception e) {
            return Summary.empty();
        }
    }

    /** 摘要只会往前滚：新摘要覆盖旧的，压缩进度一并推进。 */
    public void save(String sessionKey, String text, int compressedCount) {
        JSONObject obj = new JSONObject();
        try {
            obj.put(KEY_TEXT, text);
            obj.put(KEY_COUNT, compressedCount);
            obj.put(KEY_TIME, System.currentTimeMillis());
        } catch (Exception e) {
            return;
        }
        prefs.edit().putString(sessionKey, obj.toString()).apply();
        trimIfNeeded();
    }

    /** 会话被删掉时清理，避免残留。 */
    public void remove(String sessionKey) {
        prefs.edit().remove(sessionKey).apply();
    }

    /** 超出上限时按更新时间丢掉最旧的一批。 */
    private void trimIfNeeded() {
        Map<String, ?> all = prefs.getAll();
        if (all.size() <= MAX_ENTRIES) {
            return;
        }
        final Map<String, Long> times = new HashMap<>();
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, ?> entry : all.entrySet()) {
            keys.add(entry.getKey());
            times.put(entry.getKey(), parseTime(entry.getValue()));
        }
        Collections.sort(keys, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                return Long.compare(times.get(a), times.get(b));
            }
        });
        SharedPreferences.Editor editor = prefs.edit();
        int removeCount = keys.size() - MAX_ENTRIES / 2;
        for (int i = 0; i < removeCount && i < keys.size(); i++) {
            editor.remove(keys.get(i));
        }
        editor.apply();
    }

    private static long parseTime(Object value) {
        if (!(value instanceof String)) {
            return 0L;
        }
        try {
            return new JSONObject((String) value).optLong(KEY_TIME, 0L);
        } catch (Exception e) {
            return 0L;
        }
    }
}
