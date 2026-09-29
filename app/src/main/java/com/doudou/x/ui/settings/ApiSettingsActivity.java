package com.doudou.x.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;

import com.doudou.x.R;
import com.doudou.x.ai.AiEngine;
import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ApiProfile;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 接口设置页（OpenAI 兼容格式）。
 *
 * 支持保存多套配置：顶部下拉切换「当前使用」的配置，并可新建 / 重命名 / 删除；
 * 表单编辑的 Base URL、API Key、Model 都属于当前选中的那套配置。
 */
public class ApiSettingsActivity extends AppCompatActivity {

    private SwitchCompat switchEnable;
    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private TextView btnTest;
    private Spinner spinnerProfile;
    private TextView btnNewProfile;
    private TextView btnRenameProfile;
    private TextView btnDeleteProfile;

    private ApiConfigStore config;
    private OpenAiEngine engine;

    private final List<ApiProfile> profiles = new ArrayList<>();
    private ArrayAdapter<String> profileAdapter;
    private String currentId;
    /** 记录下拉当前位置，用于忽略 setSelection 触发的回弹回调。 */
    private int spinnerPosition = -1;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_api_settings);

        config = ApiConfigStore.getInstance(this);

        ImageButton btnBack = findViewById(R.id.btnBack);
        switchEnable = findViewById(R.id.switchEnableApi);
        etBaseUrl = findViewById(R.id.etBaseUrl);
        etApiKey = findViewById(R.id.etApiKey);
        etModel = findViewById(R.id.etModel);
        TextView btnSave = findViewById(R.id.btnSaveApi);
        btnTest = findViewById(R.id.btnTestApi);
        spinnerProfile = findViewById(R.id.spinnerProfile);
        btnNewProfile = findViewById(R.id.btnNewProfile);
        btnRenameProfile = findViewById(R.id.btnRenameProfile);
        btnDeleteProfile = findViewById(R.id.btnDeleteProfile);
        engine = new OpenAiEngine(config);

        // 回填总开关
        switchEnable.setChecked(config.isEnabled());

        initProfileSpinner();

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        btnSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
            }
        });
        btnTest.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testConnection();
            }
        });
        btnNewProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showNameDialog(getString(R.string.api_profile_new), "", false);
            }
        });
        btnRenameProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ApiProfile profile = currentProfile();
                if (profile == null) {
                    return;
                }
                showNameDialog(getString(R.string.api_profile_rename), profile.getName(), true);
            }
        });
        btnDeleteProfile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete();
            }
        });
    }

    // ------------------------------------------------------------------
    // 多套配置
    // ------------------------------------------------------------------

    private void initProfileSpinner() {
        profileAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, new ArrayList<String>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerProfile.setAdapter(profileAdapter);
        spinnerProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == spinnerPosition) {
                    return; // setSelection 的回弹
                }
                if (position < 0 || position >= profiles.size()) {
                    return;
                }
                spinnerPosition = position;
                switchToProfile(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        refreshProfiles();
    }

    /** 重新读取配置列表，并回填到下拉与表单。 */
    private void refreshProfiles() {
        profiles.clear();
        profiles.addAll(config.getProfiles());
        if (profiles.isEmpty()) {
            return;
        }

        List<String> names = new ArrayList<>();
        int activeIndex = 0;
        String activeId = config.getActiveProfileId();
        for (int i = 0; i < profiles.size(); i++) {
            ApiProfile profile = profiles.get(i);
            names.add(profile.getName());
            if (profile.getId().equals(activeId)) {
                activeIndex = i;
            }
        }
        profileAdapter.clear();
        profileAdapter.addAll(names);
        profileAdapter.notifyDataSetChanged();

        // 定位到当前配置并回填表单
        spinnerPosition = activeIndex;
        spinnerProfile.setSelection(activeIndex, false);
        currentId = profiles.get(activeIndex).getId();
        bindForm(profiles.get(activeIndex));
    }

    private void switchToProfile(int position) {
        // 先把当前表单内容落到原配置里，避免切换时丢失编辑
        flushCurrentEdits();
        ApiProfile target = profiles.get(position);
        config.setActiveProfileId(target.getId());
        currentId = target.getId();
        bindForm(target);
        toast(getString(R.string.api_profile_switched, target.getName()));
    }

    private void bindForm(ApiProfile profile) {
        etBaseUrl.setText(profile.getBaseUrl());
        etApiKey.setText(profile.getApiKey());
        etModel.setText(profile.getModel());
    }

    private ApiProfile currentProfile() {
        return config.findProfile(currentId);
    }

    /** 把表单内容写回当前配置（不校验、不关闭页面）。 */
    private void flushCurrentEdits() {
        config.setEnabled(switchEnable.isChecked());
        ApiProfile profile = currentProfile();
        if (profile == null) {
            return;
        }
        profile.setBaseUrl(etBaseUrl.getText().toString().trim());
        profile.setApiKey(etApiKey.getText().toString().trim());
        profile.setModel(etModel.getText().toString().trim());
        config.saveProfile(profile);
    }

    /** 新建 / 重命名配置时输入名称。 */
    private void showNameDialog(final String title, String preset, final boolean rename) {
        final EditText input = new EditText(this);
        input.setText(preset == null ? "" : preset);
        input.setHint(R.string.api_profile_name_hint);
        input.setSingleLine(true);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        if (!TextUtils.isEmpty(preset)) {
            input.setSelection(preset.length());
        }

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(input)
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast(getString(R.string.api_profile_name_empty));
                        return;
                    }
                    if (rename) {
                        ApiProfile profile = currentProfile();
                        if (profile == null) {
                            return;
                        }
                        profile.setName(name);
                        config.saveProfile(profile);
                        refreshProfiles();
                    } else {
                        // 新建：先落盘当前编辑，再新建一套并切换过去
                        flushCurrentEdits();
                        ApiProfile created = config.createProfile(name,
                                ApiConfigStore.DEFAULT_BASE_URL, "",
                                ApiConfigStore.DEFAULT_MODEL);
                        config.setActiveProfileId(created.getId());
                        refreshProfiles();
                        toast(getString(R.string.api_profile_created, name));
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmDelete() {
        final ApiProfile profile = currentProfile();
        if (profile == null) {
            return;
        }
        if (config.getProfileCount() <= 1) {
            toast(getString(R.string.api_profile_keep_one));
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.api_profile_delete_title)
                .setMessage(getString(R.string.api_profile_delete_message, profile.getName()))
                .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                    config.deleteProfile(profile.getId());
                    refreshProfiles();
                    toast(getString(R.string.api_profile_deleted, profile.getName()));
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 用当前表单内容打一次真实请求，把接口地址、请求体、HTTP 结果、
     * 原始返回全部展示出来，方便定位 400 之类的错误。
     */
    private void testConnection() {
        boolean enabled = switchEnable.isChecked();
        String baseUrl = etBaseUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();

        if (baseUrl.isEmpty()) {
            toast(getString(R.string.api_error_base_url));
            return;
        }
        if (apiKey.isEmpty()) {
            toast(getString(R.string.api_error_key));
            return;
        }
        if (model.isEmpty()) {
            toast(getString(R.string.api_error_model));
            return;
        }
        // 先用当前表单内容覆盖配置再测试（含未保存的修改），但不改动开关状态
        flushCurrentEdits();
        config.setEnabled(enabled);

        btnTest.setEnabled(false);
        btnTest.setText(R.string.api_testing);
        engine.testConnection(new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onToken(String fullText) {
            }

            @Override
            public void onComplete(String fullText, String rawResponse) {
                showTestResult(true, null, rawResponse);
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                showTestResult(false, errorMessage, rawResponse);
            }
        });
    }

    private void showTestResult(boolean success, String errorMessage, String rawResponse) {
        StringBuilder sb = new StringBuilder();
        sb.append("结果：").append(success ? "成功 ✅" : "失败 ❌").append('\n');
        if (!success && errorMessage != null) {
            sb.append("错误：").append(errorMessage).append('\n');
        }
        sb.append("\n【接口地址】\n").append(engine.getLastEndpoint()).append("\n\n");
        sb.append("【请求体】\n").append(engine.getLastRequestBody()).append("\n\n");
        sb.append("【原始返回】\n").append(rawResponse == null
                ? "（无）" : rawResponse);
        showTextDialog(getString(R.string.api_test_result_title), sb.toString());

        btnTest.setEnabled(true);
        btnTest.setText(R.string.api_test);
    }

    /** 通用长文本弹窗（等宽字体、可滚动、可复制）。 */
    private void showTextDialog(String title, final String content) {
        int pad = dp(16);
        TextView textView = new TextView(this);
        textView.setText(content);
        textView.setTextSize(12);
        textView.setTypeface(Typeface.MONOSPACE);
        textView.setTextIsSelectable(true);
        textView.setPadding(pad, pad, pad, pad);
        textView.setTextColor(0xFF1F2430);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(textView);

        new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(scrollView)
                .setPositiveButton(R.string.action_copy, (dialog, which) -> {
                    ClipboardManager cm = (ClipboardManager)
                            getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("api_debug", content));
                    Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.action_close, null)
                .show();
    }

    private void save() {
        boolean enabled = switchEnable.isChecked();
        String baseUrl = etBaseUrl.getText().toString().trim();
        String apiKey = etApiKey.getText().toString().trim();
        String model = etModel.getText().toString().trim();

        if (enabled) {
            if (baseUrl.isEmpty()) {
                toast(getString(R.string.api_error_base_url));
                return;
            }
            if (apiKey.isEmpty()) {
                toast(getString(R.string.api_error_key));
                return;
            }
            if (model.isEmpty()) {
                toast(getString(R.string.api_error_model));
                return;
            }
        }
        if (baseUrl.isEmpty()) {
            baseUrl = ApiConfigStore.DEFAULT_BASE_URL;
        }
        if (model.isEmpty()) {
            model = ApiConfigStore.DEFAULT_MODEL;
        }

        config.setEnabled(enabled);
        ApiProfile profile = currentProfile();
        if (profile == null) {
            profile = config.createProfile(getString(R.string.api_profile_default_name),
                    baseUrl, apiKey, model);
            config.setActiveProfileId(profile.getId());
        } else {
            profile.setBaseUrl(baseUrl);
            profile.setApiKey(apiKey);
            profile.setModel(model);
            config.saveProfile(profile);
        }
        toast(getString(R.string.api_saved));
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onDestroy() {
        engine.cancel();
        super.onDestroy();
    }
}
