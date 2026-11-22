package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Agent Ops 后端配置。
 *
 * <p>只保存后端 Base URL，不存任何密钥。地址为空表示不上报，
 * trace 仍按原方式写本地 JSONL；这样后端没起来也不会影响日常使用。
 */
public class OpsConfigStore {

    private static final String PREF_NAME = "doudou_ops_config";
    private static final String KEY_BASE_URL = "base_url";

    private static OpsConfigStore instance;

    private final SharedPreferences prefs;

    private OpsConfigStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized OpsConfigStore getInstance(Context context) {
        if (instance == null) {
            instance = new OpsConfigStore(context);
        }
        return instance;
    }

    /** 用户填写的原始地址（已 trim）。 */
    public String getBaseUrl() {
        String value = prefs.getString(KEY_BASE_URL, "");
        return value == null ? "" : value.trim();
    }

    public void setBaseUrl(String url) {
        prefs.edit().putString(KEY_BASE_URL, url == null ? "" : url.trim()).apply();
    }

    public boolean isConfigured() {
        return !normalizedBaseUrl().isEmpty();
    }

    /**
     * 规范化后的 Base URL：没写协议补 http://，去掉尾部斜杠。
     * Back end 需要在局域网里跑，所以默认按 http 处理。
     */
    public String normalizedBaseUrl() {
        String url = getBaseUrl();
        if (url.isEmpty()) {
            return "";
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /** trace 上报地址。 */
    public String uploadUrl() {
        String base = normalizedBaseUrl();
        return base.isEmpty() ? "" : base + "/api/traces";
    }

    /** 健康检查地址。 */
    public String healthUrl() {
        String base = normalizedBaseUrl();
        return base.isEmpty() ? "" : base + "/api/health";
    }

    /** 统计汇总地址，供设置页查看后端是否收到数据。 */
    public String statsUrl() {
        String base = normalizedBaseUrl();
        return base.isEmpty() ? "" : base + "/api/stats";
    }
}
