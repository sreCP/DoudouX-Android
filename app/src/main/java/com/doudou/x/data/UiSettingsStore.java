package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 界面显示偏好（代码块渲染方式等）。
 */
public class UiSettingsStore {

    private static final String PREF_NAME = "doudou_ui_settings";
    private static final String KEY_CODE_WRAP = "code_wrap_mode";
    private static final String KEY_AUTO_TITLE = "auto_title_enabled";

    private static UiSettingsStore instance;
    private final SharedPreferences prefs;

    private UiSettingsStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized UiSettingsStore getInstance(Context context) {
        if (instance == null) {
            instance = new UiSettingsStore(context);
        }
        return instance;
    }

    /** true = 超长行自动换行（黑灰相间），false = 单行横向滚动。 */
    public boolean isCodeWrapEnabled() {
        return prefs.getBoolean(KEY_CODE_WRAP, false);
    }

    public void setCodeWrapEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CODE_WRAP, enabled).apply();
    }

    /** true = 首次对话结束后调用模型生成不超过 20 字的摘要作为标题。 */
    public boolean isAutoTitleEnabled() {
        return prefs.getBoolean(KEY_AUTO_TITLE, false);
    }

    public void setAutoTitleEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_TITLE, enabled).apply();
    }
}
