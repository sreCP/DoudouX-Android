package com.doudou.x.ai.memory;

/**
 * 记忆的三种类型。
 *
 * <p>分层的依据是「这条记忆多久会失效」——不同类型的信息保质期差几个数量级，
 * 混在一起存就没法设计合理的遗忘策略。
 */
public enum MemoryType {

    /**
     * 情景记忆：某次对话聊了什么、结论是什么。
     * 只对后续相似话题有用，忘得最快。
     */
    EPISODIC("episodic", "情景", 3 * 24 * 3600_000L, 1.0f),

    /**
     * 语义记忆：关于用户的稳定事实（身份、偏好、约束）。
     * 变化慢，长期有效。
     */
    SEMANTIC("semantic", "语义", 30 * 24 * 3600_000L, 1.2f),

    /**
     * 程序记忆：用户要求的工作方式与输出格式（例如"先给结论""代码要可运行"）。
     * 一旦形成几乎不会变，所以半衰期设得极长。
     */
    PROCEDURAL("procedural", "程序", 365 * 24 * 3600_000L, 1.5f);

    private final String json;
    private final String label;
    /** 半衰期：过了这么久，记忆的时效性得分衰减到一半。 */
    private final long halfLifeMs;
    /** 召回权重：越稳定的记忆越该被优先带进上下文。 */
    private final float recallWeight;

    MemoryType(String json, String label, long halfLifeMs, float recallWeight) {
        this.json = json;
        this.label = label;
        this.halfLifeMs = halfLifeMs;
        this.recallWeight = recallWeight;
    }

    public String getJson() {
        return json;
    }

    public String getLabel() {
        return label;
    }

    public long getHalfLifeMs() {
        return halfLifeMs;
    }

    public float getRecallWeight() {
        return recallWeight;
    }

    /** 按 JSON 里的类型名解析；无法识别时按语义记忆处理。 */
    public static MemoryType fromJson(String value) {
        if (value == null) {
            return SEMANTIC;
        }
        String v = value.trim().toLowerCase();
        if (EPISODIC.json.equals(v)) {
            return EPISODIC;
        }
        if (PROCEDURAL.json.equals(v)) {
            return PROCEDURAL;
        }
        return SEMANTIC;
    }
}
