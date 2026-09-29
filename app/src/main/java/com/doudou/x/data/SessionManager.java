package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

/**
 * 登录态管理（本地 SharedPreferences 模拟）。
 */
public class SessionManager {

    private static final String PREF_NAME = "doudou_session";
    private static final String KEY_LOGGED_IN = "logged_in";
    private static final String KEY_PHONE = "phone";

    private static SessionManager instance;
    private final SharedPreferences prefs;

    private SessionManager(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    public boolean isLoggedIn() {
        return prefs.getBoolean(KEY_LOGGED_IN, false);
    }

    public void saveLogin(String phone) {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, true)
                .putString(KEY_PHONE, phone)
                .apply();
    }

    public void logout() {
        prefs.edit()
                .putBoolean(KEY_LOGGED_IN, false)
                .apply();
    }

    public String getPhone() {
        return prefs.getString(KEY_PHONE, "");
    }

    /** 138****0000 形式脱敏展示。 */
    public String getMaskedPhone() {
        String phone = getPhone();
        if (TextUtils.isEmpty(phone) || phone.length() < 7) {
            return TextUtils.isEmpty(phone) ? "未登录" : phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }
}
