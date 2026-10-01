package com.doudou.x.model;

import org.json.JSONObject;

/**
 * 一套 AI 接口配置（OpenAI 兼容格式）。
 * 一个账号下可以保存多套，随时切换使用哪一套。
 *
 * 模型参数默认值说明：
 * · temperature / topP 为负数 → 不下发该字段，使用服务端默认；
 * · maxTokens 为 0 → 不限制输出长度（不下发该字段）。
 */
public class ApiProfile {

    /** 表示「不下发该参数，使用服务端默认」。 */
    public static final float VALUE_UNSET = -1f;
    public static final int MAX_TOKENS_UNLIMITED = 0;

    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_BASE_URL = "baseUrl";
    private static final String JSON_API_KEY = "apiKey";
    private static final String JSON_MODEL = "model";
    private static final String JSON_SYSTEM_PROMPT = "systemPrompt";
    private static final String JSON_TEMPERATURE = "temperature";
    private static final String JSON_TOP_P = "topP";
    private static final String JSON_MAX_TOKENS = "maxTokens";
    private static final String JSON_SEND_FULL_HISTORY = "sendFullHistory";
    private static final String JSON_DISABLE_THINKING = "disableThinking";

    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String model;
    private String systemPrompt;
    private float temperature;
    private float topP;
    private int maxTokens;
    /** 是否每次把完整对话历史发给服务端（服务端本身不保存会话）。 */
    private boolean sendFullHistory;
    /** 是否关闭模型思考（请求体下发 think=false）。 */
    private boolean disableThinking;

    public ApiProfile(String id, String name, String baseUrl, String apiKey, String model) {
        this(id, name, baseUrl, apiKey, model, "", VALUE_UNSET, VALUE_UNSET,
                MAX_TOKENS_UNLIMITED);
    }

    public ApiProfile(String id, String name, String baseUrl, String apiKey, String model,
                      String systemPrompt, float temperature, float topP, int maxTokens) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
        this.temperature = temperature;
        this.topP = topP;
        this.maxTokens = maxTokens;
        this.sendFullHistory = true;
        this.disableThinking = false;
    }

    /** 是否每次发送完整对话历史，默认开启。 */
    public boolean isSendFullHistory() {
        return sendFullHistory;
    }

    public void setSendFullHistory(boolean sendFullHistory) {
        this.sendFullHistory = sendFullHistory;
    }

    /** 是否关闭模型思考（下发 reasoning_effort=none），默认不关闭。 */
    public boolean isDisableThinking() {
        return disableThinking;
    }

    public void setDisableThinking(boolean disableThinking) {
        this.disableThinking = disableThinking;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model;
    }

    /** 系统提示词，为空表示不下发 system 消息。 */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
    }

    /** 温度，负数表示使用服务端默认。 */
    public float getTemperature() {
        return temperature;
    }

    public void setTemperature(float temperature) {
        this.temperature = temperature;
    }

    /** 核采样，负数表示使用服务端默认。 */
    public float getTopP() {
        return topP;
    }

    public void setTopP(float topP) {
        this.topP = topP;
    }

    /** 单次回复最大 token，0 表示不限制。 */
    public int getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public ApiProfile copy() {
        ApiProfile copy = new ApiProfile(id, name, baseUrl, apiKey, model,
                systemPrompt, temperature, topP, maxTokens);
        copy.setSendFullHistory(sendFullHistory);
        copy.setDisableThinking(disableThinking);
        return copy;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(JSON_ID, id);
            obj.put(JSON_NAME, name);
            obj.put(JSON_BASE_URL, baseUrl);
            obj.put(JSON_API_KEY, apiKey);
            obj.put(JSON_MODEL, model);
            obj.put(JSON_SYSTEM_PROMPT, systemPrompt);
            obj.put(JSON_TEMPERATURE, temperature);
            obj.put(JSON_TOP_P, topP);
            obj.put(JSON_MAX_TOKENS, maxTokens);
            obj.put(JSON_SEND_FULL_HISTORY, sendFullHistory);
            obj.put(JSON_DISABLE_THINKING, disableThinking);
        } catch (Exception ignored) {
            // 不会发生（key 均非空）
        }
        return obj;
    }

    public static ApiProfile fromJson(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String id = obj.optString(JSON_ID, null);
        if (id == null || id.isEmpty()) {
            return null;
        }
        ApiProfile profile = new ApiProfile(
                id,
                obj.optString(JSON_NAME, ""),
                obj.optString(JSON_BASE_URL, ""),
                obj.optString(JSON_API_KEY, ""),
                obj.optString(JSON_MODEL, ""),
                obj.optString(JSON_SYSTEM_PROMPT, ""),
                (float) obj.optDouble(JSON_TEMPERATURE, VALUE_UNSET),
                (float) obj.optDouble(JSON_TOP_P, VALUE_UNSET),
                obj.optInt(JSON_MAX_TOKENS, MAX_TOKENS_UNLIMITED));
        profile.setSendFullHistory(obj.optBoolean(JSON_SEND_FULL_HISTORY, true));
        profile.setDisableThinking(obj.optBoolean(JSON_DISABLE_THINKING, false));
        return profile;
    }
}
