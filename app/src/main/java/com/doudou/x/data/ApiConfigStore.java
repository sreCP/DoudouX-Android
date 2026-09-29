package com.doudou.x.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.doudou.x.model.ApiProfile;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * AI 接口配置（OpenAI 兼容格式）。
 *
 * 支持保存多套配置（ApiProfile），其中一套为「当前使用」的配置；
 * 开启总开关且当前配置三项齐全时才走真实接口，否则使用本地模拟回复。
 */
public class ApiConfigStore {

    private static final String PREF_NAME = "doudou_api_config";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE_ID = "active_id";

    /** 旧版本只有单套配置时的键，用于升级迁移。 */
    private static final String LEGACY_BASE_URL = "base_url";
    private static final String LEGACY_API_KEY = "api_key";
    private static final String LEGACY_MODEL = "model";

    public static final String DEFAULT_BASE_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_MODEL = "gpt-4o-mini";
    public static final String DEFAULT_PROFILE_NAME = "默认配置";

    private static ApiConfigStore instance;

    private final SharedPreferences prefs;
    private final List<ApiProfile> profiles = new ArrayList<>();

    private ApiConfigStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        load();
    }

    public static synchronized ApiConfigStore getInstance(Context context) {
        if (instance == null) {
            instance = new ApiConfigStore(context);
        }
        return instance;
    }

    // ------------------------------------------------------------------
    // 读取
    // ------------------------------------------------------------------

    /** 全部配置（副本，修改后需调用 saveProfile 才会持久化）。 */
    public List<ApiProfile> getProfiles() {
        List<ApiProfile> copy = new ArrayList<>();
        synchronized (profiles) {
            for (ApiProfile profile : profiles) {
                copy.add(profile.copy());
            }
        }
        return copy;
    }

    public int getProfileCount() {
        synchronized (profiles) {
            return profiles.size();
        }
    }

    public String getActiveProfileId() {
        String id = prefs.getString(KEY_ACTIVE_ID, null);
        if (findProfile(id) == null) {
            synchronized (profiles) {
                if (!profiles.isEmpty()) {
                    id = profiles.get(0).getId();
                }
            }
        }
        return id;
    }

    /** 当前正在使用的那套配置。 */
    public ApiProfile getActiveProfile() {
        return findProfile(getActiveProfileId());
    }

    public ApiProfile findProfile(String id) {
        if (id == null) {
            return null;
        }
        synchronized (profiles) {
            for (ApiProfile profile : profiles) {
                if (id.equals(profile.getId())) {
                    return profile.copy();
                }
            }
        }
        return null;
    }

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }

    /** 兼容旧调用：当前配置的 Base URL。 */
    public String getBaseUrl() {
        ApiProfile profile = getActiveProfile();
        String value = profile == null ? null : profile.getBaseUrl();
        return TextUtils.isEmpty(value) ? DEFAULT_BASE_URL : value;
    }

    /** 兼容旧调用：当前配置的 API Key。 */
    public String getApiKey() {
        ApiProfile profile = getActiveProfile();
        return profile == null ? "" : profile.getApiKey();
    }

    /** 兼容旧调用：当前配置的模型名。 */
    public String getModel() {
        ApiProfile profile = getActiveProfile();
        String value = profile == null ? null : profile.getModel();
        return TextUtils.isEmpty(value) ? DEFAULT_MODEL : value;
    }

    /** 开关打开且当前配置三项齐全时，才走真实接口。 */
    public boolean isReady() {
        return isEnabled() && isProfileReady(getActiveProfile());
    }

    public static boolean isProfileReady(ApiProfile profile) {
        return profile != null
                && !TextUtils.isEmpty(profile.getBaseUrl())
                && !TextUtils.isEmpty(profile.getApiKey())
                && !TextUtils.isEmpty(profile.getModel());
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** 新增或更新一套配置。 */
    public ApiProfile saveProfile(ApiProfile profile) {
        if (profile == null) {
            return null;
        }
        if (TextUtils.isEmpty(profile.getId())) {
            profile.setId(newId());
        }
        synchronized (profiles) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(profile.getId())) {
                    profiles.set(i, profile.copy());
                    persist();
                    return profile.copy();
                }
            }
            profiles.add(profile.copy());
        }
        persist();
        return profile.copy();
    }

    /** 快捷保存当前配置的三项内容（保留名称）。 */
    public void saveCurrentValues(String baseUrl, String apiKey, String model) {
        ApiProfile profile = getActiveProfile();
        if (profile == null) {
            profile = new ApiProfile(newId(), DEFAULT_PROFILE_NAME,
                    baseUrl, apiKey, model);
            ApiProfile created = saveProfile(profile);
            setActiveProfileId(created.getId());
            return;
        }
        profile.setBaseUrl(baseUrl);
        profile.setApiKey(apiKey);
        profile.setModel(model);
        saveProfile(profile);
    }

    public ApiProfile createProfile(String name, String baseUrl, String apiKey, String model) {
        ApiProfile profile = new ApiProfile(newId(), name, baseUrl, apiKey, model);
        return saveProfile(profile);
    }

    public void deleteProfile(String id) {
        synchronized (profiles) {
            for (int i = 0; i < profiles.size(); i++) {
                if (profiles.get(i).getId().equals(id)) {
                    profiles.remove(i);
                    break;
                }
            }
            if (profiles.isEmpty()) {
                ApiProfile fresh = new ApiProfile(newId(), DEFAULT_PROFILE_NAME,
                        DEFAULT_BASE_URL, "", DEFAULT_MODEL);
                profiles.add(fresh);
                prefs.edit().putString(KEY_ACTIVE_ID, fresh.getId()).apply();
            }
        }
        String activeId = prefs.getString(KEY_ACTIVE_ID, null);
        if (findProfile(activeId) == null) {
            synchronized (profiles) {
                prefs.edit().putString(KEY_ACTIVE_ID, profiles.get(0).getId()).apply();
            }
        }
        persist();
    }

    public void setActiveProfileId(String id) {
        if (findProfile(id) == null) {
            return;
        }
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply();
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private void load() {
        synchronized (profiles) {
            profiles.clear();
            String json = prefs.getString(KEY_PROFILES, null);
            if (json == null) {
                // 旧版本升级：把单套配置迁移成一套档案
                ApiProfile legacy = new ApiProfile(newId(), DEFAULT_PROFILE_NAME,
                        prefs.getString(LEGACY_BASE_URL, DEFAULT_BASE_URL),
                        prefs.getString(LEGACY_API_KEY, ""),
                        prefs.getString(LEGACY_MODEL, DEFAULT_MODEL));
                profiles.add(legacy);
            } else {
                try {
                    JSONArray array = new JSONArray(json);
                    for (int i = 0; i < array.length(); i++) {
                        ApiProfile profile = ApiProfile.fromJson(array.getJSONObject(i));
                        if (profile != null) {
                            profiles.add(profile);
                        }
                    }
                } catch (Exception ignored) {
                    // 数据损坏则回退到默认配置
                }
            }
            if (profiles.isEmpty()) {
                profiles.add(new ApiProfile(newId(), DEFAULT_PROFILE_NAME,
                        DEFAULT_BASE_URL, "", DEFAULT_MODEL));
            }
        }
        String activeId = prefs.getString(KEY_ACTIVE_ID, null);
        if (findProfile(activeId) == null) {
            synchronized (profiles) {
                prefs.edit().putString(KEY_ACTIVE_ID, profiles.get(0).getId()).apply();
            }
        }
        persist();
    }

    private void persist() {
        JSONArray array = new JSONArray();
        synchronized (profiles) {
            for (ApiProfile profile : profiles) {
                array.put(profile.toJson());
            }
        }
        prefs.edit().putString(KEY_PROFILES, array.toString()).apply();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }
}
