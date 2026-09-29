package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * AI 接口配置（OpenAI 兼容格式）。
 */
public class ApiConfigStore {

    private static final String PREF_NAME = "doudou_api_config";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_MODEL = "model";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private static ApiConfigStore instance;
    private final SharedPreferences prefs;

    private ApiConfigStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized ApiConfigStore getInstance(Context context) {
        if (instance == null) {
            instance = new ApiConfigStore(context);
        }
        return instance;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    public String getBaseUrl() {
        return prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL);
    }

    public String getApiKey() {
        return prefs.getString(KEY_API_KEY, "");
    }

    public String getModel() {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL);
    }

    public void save(boolean enabled, String baseUrl, String apiKey, String model) {
        prefs.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putString(KEY_BASE_URL, baseUrl)
                .putString(KEY_API_KEY, apiKey)
                .putString(KEY_MODEL, model)
                .apply();
    }

    /** 开关打开且三项配置齐全时，才走真实接口。 */
    public boolean isReady() {
        return isEnabled()
                && !TextUtils.isEmpty(getBaseUrl())
                && !TextUtils.isEmpty(getApiKey())
                && !TextUtils.isEmpty(getModel());
    }
}
