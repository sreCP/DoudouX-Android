package com.doudou.x.ui.settings;

import android.Manifest;
import android.content.Intent;
import android.net.Uri;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.doudou.x.R;
import com.doudou.x.ai.observability.TraceReporter;
import com.doudou.x.eval.EvalReport;
import com.doudou.x.eval.EvalRunner;
import com.doudou.x.eval.JsonlExporter;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.data.ConversationStore;
import com.doudou.x.data.OpsConfigStore;
import com.doudou.x.data.SessionManager;
import com.doudou.x.data.TavilyConfigStore;
import com.doudou.x.data.UiSettingsStore;
import com.doudou.x.model.ApiProfile;
import com.doudou.x.ui.login.LoginActivity;

/**
 * 设置页：账号信息、AI 接口、代码块渲染、清空历史、版本、退出登录。
 */
public class SettingsActivity extends AppCompatActivity {

    private static final int REQ_CALENDAR_PERMISSION = 2001;

    private TextView tvApiStatus;
    private SwitchCompat switchCodeWrap;
    private TextView tvCodeModeSubtitle;
    private SwitchCompat switchAutoTitle;
    private TextView tvAutoTitleSubtitle;
    private SwitchCompat switchNativeWrite;
    private TextView tvNativeWriteSubtitle;
    private EditText etTavilyApiKey;
    private EditText etOpsBaseUrl;
    private TextView btnTestOps;
    private TextView btnOpenDashboard;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBack);
        TextView tvAccount = findViewById(R.id.tvSettingsAccount);
        LinearLayout btnApiSettings = findViewById(R.id.btnApiSettings);
        tvApiStatus = findViewById(R.id.tvApiStatus);
        TextView btnCalendarPermission = findViewById(R.id.btnCalendarPermission);
        LinearLayout btnRunEval = findViewById(R.id.btnRunEval);
        TextView btnClearHistory = findViewById(R.id.btnClearHistory);
        TextView tvVersion = findViewById(R.id.tvVersion);
        TextView btnLogout = findViewById(R.id.btnLogout);

        final SessionManager session = SessionManager.getInstance(this);
        tvAccount.setText(session.getMaskedPhone());
        tvVersion.setText(getString(R.string.settings_version) + "  " + getVersionName());

        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        btnApiSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ApiSettingsActivity.class));
            }
        });

        switchCodeWrap = findViewById(R.id.switchCodeWrap);
        tvCodeModeSubtitle = findViewById(R.id.tvCodeModeSubtitle);
        final UiSettingsStore uiSettings = UiSettingsStore.getInstance(this);
        switchCodeWrap.setChecked(uiSettings.isCodeWrapEnabled());
        refreshCodeModeSubtitle();
        switchCodeWrap.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        uiSettings.setCodeWrapEnabled(isChecked);
                        refreshCodeModeSubtitle();
                    }
                });

        switchAutoTitle = findViewById(R.id.switchAutoTitle);
        tvAutoTitleSubtitle = findViewById(R.id.tvAutoTitleSubtitle);
        switchAutoTitle.setChecked(uiSettings.isAutoTitleEnabled());
        refreshAutoTitleSubtitle();
        switchAutoTitle.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        uiSettings.setAutoTitleEnabled(isChecked);
                        refreshAutoTitleSubtitle();
                    }
                });

        switchNativeWrite = findViewById(R.id.switchNativeWrite);
        tvNativeWriteSubtitle = findViewById(R.id.tvNativeWriteSubtitle);
        switchNativeWrite.setChecked(uiSettings.isNativeWriteEnabled());
        refreshNativeWriteSubtitle();
        switchNativeWrite.setOnCheckedChangeListener(
                new android.widget.CompoundButton.OnCheckedChangeListener() {
                    @Override
                    public void onCheckedChanged(android.widget.CompoundButton buttonView,
                                                 boolean isChecked) {
                        uiSettings.setNativeWriteEnabled(isChecked);
                        refreshNativeWriteSubtitle();
                    }
                });

        btnCalendarPermission.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (calendarPermissionGranted()) {
                    Toast.makeText(SettingsActivity.this,
                            R.string.settings_calendar_permission_granted,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                ActivityCompat.requestPermissions(SettingsActivity.this,
                        new String[]{Manifest.permission.READ_CALENDAR,
                                Manifest.permission.WRITE_CALENDAR},
                        REQ_CALENDAR_PERMISSION);
            }
        });

        etTavilyApiKey = findViewById(R.id.etTavilyApiKey);
        final TavilyConfigStore tavilyConfig = TavilyConfigStore.getInstance(this);
        etTavilyApiKey.setText(tavilyConfig.getApiKey());
        etTavilyApiKey.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                tavilyConfig.setApiKey(s.toString());
            }
        });

        etOpsBaseUrl = findViewById(R.id.etOpsBaseUrl);
        btnTestOps = findViewById(R.id.btnTestOps);
        btnOpenDashboard = findViewById(R.id.btnOpenDashboard);
        final OpsConfigStore opsConfig = OpsConfigStore.getInstance(this);
        etOpsBaseUrl.setText(opsConfig.getBaseUrl());
        etOpsBaseUrl.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                opsConfig.setBaseUrl(s.toString());
            }
        });
        btnTestOps.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                testOpsConnection();
            }
        });
        btnOpenDashboard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String url = OpsConfigStore.getInstance(SettingsActivity.this).normalizedBaseUrl();
                if (url.isEmpty()) {
                    Toast.makeText(SettingsActivity.this,
                            R.string.settings_ops_subtitle, Toast.LENGTH_LONG).show();
                    return;
                }
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url + "/")));
                } catch (Exception e) {
                    Toast.makeText(SettingsActivity.this,
                            "无法打开浏览器：" + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnRunEval.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showEvalDialog();
            }
        });

        btnClearHistory.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setMessage(R.string.settings_clear_history)
                        .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                            ConversationStore.getInstance(SettingsActivity.this).clear();
                            Toast.makeText(SettingsActivity.this,
                                    R.string.settings_clear_history_done,
                                    Toast.LENGTH_SHORT).show();
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });

        btnLogout.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(R.string.logout_confirm_title)
                        .setMessage(R.string.logout_confirm_message)
                        .setPositiveButton(R.string.action_confirm, (dialog, which) -> {
                            session.logout();
                            Intent intent = new Intent(SettingsActivity.this, LoginActivity.class);
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                    | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                            startActivity(intent);
                            finish();
                        })
                        .setNegativeButton(R.string.action_cancel, null)
                        .show();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshApiStatus();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_CALENDAR_PERMISSION) {
            return;
        }
        Toast.makeText(this,
                calendarPermissionGranted()
                        ? R.string.settings_calendar_permission_granted
                        : R.string.settings_calendar_permission_denied,
                Toast.LENGTH_SHORT).show();
    }

    private boolean calendarPermissionGranted() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 跑一遍确定性自检，结果用可滚动对话框展示。 */
    private void showEvalDialog() {
        EvalReport report = EvalRunner.runAll();
        android.util.Log.i("DoudouX-Eval", report.toText());

        ScrollView scroll = new ScrollView(this);
        TextView text = new TextView(this);
        text.setText(report.toText());
        text.setTextSize(13f);
        text.setTextColor(getResources().getColor(R.color.text_primary));
        text.setPadding(8, 8, 8, 8);
        scroll.addView(text);

        new AlertDialog.Builder(this)
                .setTitle(R.string.eval_result_title)
                .setView(scroll)
                .setPositiveButton(R.string.action_confirm, null)
                .setNeutralButton(R.string.eval_export_trace,
                        (dialog, which) -> exportTrace())
                .show();

        // 自检本身也落一条 trace，方便后续与真实调用一起统计
        JsonlExporter.write(this, new com.doudou.x.ai.observability.TraceEvent(
                System.currentTimeMillis(), "eval", "local", 0,
                0, 0, 0, 0, 0, 0, 0,
                false, new java.util.ArrayList<String>(), "eval",
                report.durationMs, report.allPassed(),
                report.allPassed() ? "" : "失败 " + report.failedCount() + " 条"));
    }

    /** 在后台线程探测后端，结果回到主线程用 Toast 展示。 */
    private void testOpsConnection() {
        final OpsConfigStore config = OpsConfigStore.getInstance(this);
        if (!config.isConfigured()) {
            Toast.makeText(this, R.string.settings_ops_subtitle, Toast.LENGTH_LONG).show();
            return;
        }
        btnTestOps.setText(R.string.settings_ops_test_running);
        btnTestOps.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String result = TraceReporter.testConnection(SettingsActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        btnTestOps.setText(R.string.settings_ops_test);
                        btnTestOps.setEnabled(true);
                        Toast.makeText(SettingsActivity.this, result, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }, "doudou-server-test").start();
    }

    /** 检查 trace 是否已经落盘，给出明确反馈而不是静默无响应。 */
    private void exportTrace() {
        int lines = JsonlExporter.readAll(this).size();
        Toast.makeText(this,
                lines == 0 ? R.string.eval_trace_empty : R.string.eval_export_done,
                Toast.LENGTH_SHORT).show();
    }

    /** 显示写操作开关说明。 */
    private void refreshNativeWriteSubtitle() {
        tvNativeWriteSubtitle.setText(switchNativeWrite.isChecked()
                ? R.string.settings_native_write_subtitle_on
                : R.string.settings_native_write_subtitle_off);
    }

    /** 显示当前代码块渲染方式说明。 */
    private void refreshCodeModeSubtitle() {
        tvCodeModeSubtitle.setText(switchCodeWrap.isChecked()
                ? R.string.settings_code_mode_subtitle_on
                : R.string.settings_code_mode_subtitle_off);
    }

    /** 显示自动生成标题开关的说明。 */
    private void refreshAutoTitleSubtitle() {
        tvAutoTitleSubtitle.setText(switchAutoTitle.isChecked()
                ? R.string.settings_auto_title_subtitle_on
                : R.string.settings_auto_title_subtitle_off);
    }

    /** 显示当前 AI 接口启用状态。 */
    private void refreshApiStatus() {
        ApiConfigStore config = ApiConfigStore.getInstance(this);
        if (config.isReady()) {
            ApiProfile profile = config.getActiveProfile();
            tvApiStatus.setText(getString(R.string.settings_api_status_real)
                    + (profile == null ? config.getModel()
                    : profile.getName() + " · " + profile.getModel()));
        } else if (config.isEnabled()) {
            tvApiStatus.setText(R.string.api_config_incomplete);
        } else {
            tvApiStatus.setText(R.string.settings_api_status_mock);
        }
    }

    private String getVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "v" + info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "v1.0";
        }
    }
}
