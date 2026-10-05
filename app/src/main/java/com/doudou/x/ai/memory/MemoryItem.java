package com.doudou.x.ai.memory;

import org.json.JSONObject;

/**
 * 一条记忆。
 *
 * <p>{@code key} 是这条记忆的「主语 / 主题」，冲突消解就靠它：
 * 同一类型下 key 相同的新记忆覆盖旧的，而不是堆两条互相矛盾的内容。
 */
public final class MemoryItem {

    private static final String JSON_ID = "id";
    private static final String JSON_TYPE = "type";
    private static final String JSON_KEY = "key";
    private static final String JSON_CONTENT = "content";
    private static final String JSON_CREATED = "createdAt";
    private static final String JSON_UPDATED = "updatedAt";
    private static final String JSON_ACCESSED = "accessedAt";
    private static final String JSON_HITS = "hits";
    private static final String JSON_CONFIDENCE = "confidence";

    private final String id;
    private final MemoryType type;
    private final String key;
    private String content;
    private final long createdAt;
    private long updatedAt;
    private long accessedAt;
    private int hits;
    /** 置信度 0~1：模型抽取时的把握程度，参与遗忘排序。 */
    private float confidence;

    public MemoryItem(String id, MemoryType type, String key, String content,
                      float confidence) {
        this.id = id;
        this.type = type == null ? MemoryType.SEMANTIC : type;
        this.key = key == null ? "" : key.trim();
        this.content = content == null ? "" : content.trim();
        this.confidence = clamp(confidence);
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.updatedAt = now;
        this.accessedAt = now;
    }

    /** 反序列化专用：完整恢复时间戳与命中数。 */
    private MemoryItem(String id, MemoryType type, String key, String content,
                       float confidence, long createdAt, long updatedAt,
                       long accessedAt, int hits) {
        this.id = id;
        this.type = type == null ? MemoryType.SEMANTIC : type;
        this.key = key == null ? "" : key.trim();
        this.content = content == null ? "" : content.trim();
        this.confidence = clamp(confidence);
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.accessedAt = accessedAt;
        this.hits = hits;
    }

    /** 被召回一次：提升命中数与最近访问时间，这两项决定它会不会被遗忘。 */
    public void touch() {
        hits++;
        accessedAt = System.currentTimeMillis();
    }

    /**
     * 用新内容覆盖（保留创建时间与命中数）。
     * 用于冲突消解：同一 key 出现矛盾信息时，以新的为准。
     */
    public void overwrite(String newContent, float newConfidence) {
        this.content = newContent == null ? "" : newContent.trim();
        this.confidence = clamp(newConfidence);
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 遗忘评分：命中越多、越近访问、越可信、类型越稳定，分越高。
     *
     * <p>时间衰减用指数形式：过了半衰期得分减半，
     * 而不是「超过 N 天直接删除」——后者会让记忆库出现断崖。
     */
    public float score(long now) {
        long age = Math.max(0L, now - accessedAt);
        double decay = Math.pow(0.5, age / (double) type.getHalfLifeMs());
        return (1f + hits * 0.5f) * (float) decay * confidence * type.getRecallWeight();
    }

    private static float clamp(float value) {
        if (value < 0.1f) {
            return 0.1f;
        }
        return value > 1f ? 1f : value;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(JSON_ID, id);
            obj.put(JSON_TYPE, type.getJson());
            obj.put(JSON_KEY, key);
            obj.put(JSON_CONTENT, content);
            obj.put(JSON_CREATED, createdAt);
            obj.put(JSON_UPDATED, updatedAt);
            obj.put(JSON_ACCESSED, accessedAt);
            obj.put(JSON_HITS, hits);
            obj.put(JSON_CONFIDENCE, (double) confidence);
        } catch (Exception ignored) {
            // key 均非空，不会发生
        }
        return obj;
    }

    public static MemoryItem fromJson(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String id = obj.optString(JSON_ID, "");
        String content = obj.optString(JSON_CONTENT, "");
        if (id.isEmpty() || content.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        long created = obj.optLong(JSON_CREATED, now);
        return new MemoryItem(
                id,
                MemoryType.fromJson(obj.optString(JSON_TYPE, null)),
                obj.optString(JSON_KEY, ""),
                content,
                (float) obj.optDouble(JSON_CONFIDENCE, 0.8f),
                created,
                obj.optLong(JSON_UPDATED, created),
                obj.optLong(JSON_ACCESSED, created),
                obj.optInt(JSON_HITS, 0));
    }

    public String getId() {
        return id;
    }

    public MemoryType getType() {
        return type;
    }

    public String getKey() {
        return key;
    }

    public String getContent() {
        return content;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public int getHits() {
        return hits;
    }

    public float getConfidence() {
        return confidence;
    }
}
