package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 联网搜索（Tavily）配置。
 *
 * <p>只把 API Key 存在应用私有 SharedPreferences 里，不写进源码、不打印。
 * 未配置时，web_search 工具不会出现在 tools[] 里。
 */
public class TavilyConfigStore {

    private static final String PREF_NAME = "doudou_tavily_config";
    private static final String KEY_API_KEY = "api_key";

    private static TavilyConfigStore instance;

    private final SharedPreferences prefs;

    private TavilyConfigStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized TavilyConfigStore getInstance(Context context) {
        if (instance == null) {
            instance = new TavilyConfigStore(context);
        }
        return instance;
    }

    public String getApiKey() {
        String key = prefs.getString(KEY_API_KEY, "");
        return key == null ? "" : key.trim();
    }

    public void setApiKey(String key) {
        prefs.edit().putString(KEY_API_KEY, key == null ? "" : key.trim()).apply();
    }

    public boolean isConfigured() {
        return !getApiKey().isEmpty();
    }
}
