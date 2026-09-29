package com.doudou.x.ui.settings;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.doudou.x.R;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.data.ConversationStore;
import com.doudou.x.data.SessionManager;
import com.doudou.x.ui.login.LoginActivity;

/**
 * 设置页：账号信息、AI 接口、清空历史、版本、退出登录。
 */
public class SettingsActivity extends AppCompatActivity {

    private TextView tvApiStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        ImageButton btnBack = findViewById(R.id.btnBack);
        TextView tvAccount = findViewById(R.id.tvSettingsAccount);
        LinearLayout btnApiSettings = findViewById(R.id.btnApiSettings);
        tvApiStatus = findViewById(R.id.tvApiStatus);
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

    /** 显示当前 AI 接口启用状态。 */
    private void refreshApiStatus() {
        ApiConfigStore config = ApiConfigStore.getInstance(this);
        if (config.isReady()) {
            tvApiStatus.setText(getString(R.string.settings_api_status_real) + config.getModel());
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
