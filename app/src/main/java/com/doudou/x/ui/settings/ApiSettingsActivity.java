package com.doudou.x.ui.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ScrollView;
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

/**
 * AI 接口设置页（OpenAI 兼容格式）：开关、Base URL、API Key、Model。
 */
public class ApiSettingsActivity extends AppCompatActivity {

    private SwitchCompat switchEnable;
    private EditText etBaseUrl;
    private EditText etApiKey;
    private EditText etModel;
    private TextView btnTest;

    private ApiConfigStore config;
    private OpenAiEngine engine;

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
        engine = new OpenAiEngine(config);

        // 回填已保存配置
        switchEnable.setChecked(config.isEnabled());
        etBaseUrl.setText(config.getBaseUrl());
        etApiKey.setText(config.getApiKey());
        etModel.setText(config.getModel());

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
        config.save(enabled, baseUrl, apiKey, model);

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
        int pad = (int) (16 * getResources().getDisplayMetrics().density + 0.5f);
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

        config.save(enabled, baseUrl, apiKey, model);
        toast(getString(R.string.api_saved));
        finish();
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        engine.cancel();
        super.onDestroy();
    }
}
