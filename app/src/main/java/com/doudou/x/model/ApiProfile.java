package com.doudou.x.model;

import org.json.JSONObject;

/**
 * 一套 AI 接口配置（OpenAI 兼容格式）。
 * 一个账号下可以保存多套，随时切换使用哪一套。
 */
public class ApiProfile {

    private static final String JSON_ID = "id";
    private static final String JSON_NAME = "name";
    private static final String JSON_BASE_URL = "baseUrl";
    private static final String JSON_API_KEY = "apiKey";
    private static final String JSON_MODEL = "model";

    private String id;
    private String name;
    private String baseUrl;
    private String apiKey;
    private String model;

    public ApiProfile(String id, String name, String baseUrl, String apiKey, String model) {
        this.id = id;
        this.name = name;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model == null ? "" : model;
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

    public ApiProfile copy() {
        return new ApiProfile(id, name, baseUrl, apiKey, model);
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put(JSON_ID, id);
            obj.put(JSON_NAME, name);
            obj.put(JSON_BASE_URL, baseUrl);
            obj.put(JSON_API_KEY, apiKey);
            obj.put(JSON_MODEL, model);
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
        return new ApiProfile(
                id,
                obj.optString(JSON_NAME, ""),
                obj.optString(JSON_BASE_URL, ""),
                obj.optString(JSON_API_KEY, ""),
                obj.optString(JSON_MODEL, ""));
    }
}
