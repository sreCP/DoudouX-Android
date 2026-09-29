package com.doudou.x.ui.login;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.doudou.x.R;
import com.doudou.x.data.SessionManager;
import com.doudou.x.ui.chat.MainActivity;

/**
 * 登录页：手机号 + 验证码（本地模拟流程）。
 */
public class LoginActivity extends AppCompatActivity {

    private static final int CODE_COUNTDOWN_SECONDS = 60;
    private static final long MOCK_LOGIN_DELAY_MS = 1000;

    private EditText etPhone;
    private EditText etCode;
    private TextView tvGetCode;
    private TextView btnLogin;
    private ProgressBar progressLogin;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private int countdownLeft = 0;
    private boolean loggingIn = false;

    private final Runnable countdownRunnable = new Runnable() {
        @Override
        public void run() {
            if (countdownLeft <= 0) {
                tvGetCode.setEnabled(true);
                tvGetCode.setText(R.string.login_get_code);
                return;
            }
            tvGetCode.setText(countdownLeft + "s");
            countdownLeft--;
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        etPhone = findViewById(R.id.etPhone);
        etCode = findViewById(R.id.etCode);
        tvGetCode = findViewById(R.id.tvGetCode);
        btnLogin = findViewById(R.id.btnLogin);
        progressLogin = findViewById(R.id.progressLogin);

        tvGetCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onGetCodeClicked();
            }
        });
        btnLogin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onLoginClicked();
            }
        });
    }

    private void onGetCodeClicked() {
        String phone = etPhone.getText().toString().trim();
        if (!isValidPhone(phone)) {
            toast(getString(R.string.login_error_phone));
            return;
        }
        // 模拟发送验证码并进入倒计时
        countdownLeft = CODE_COUNTDOWN_SECONDS;
        tvGetCode.setEnabled(false);
        handler.post(countdownRunnable);
        toast(getString(R.string.login_code_sent));
    }

    private void onLoginClicked() {
        if (loggingIn) {
            return;
        }
        final String phone = etPhone.getText().toString().trim();
        String code = etCode.getText().toString().trim();
        if (!isValidPhone(phone)) {
            toast(getString(R.string.login_error_phone));
            return;
        }
        if (code.isEmpty()) {
            toast(getString(R.string.login_error_code));
            return;
        }

        // 模拟网络请求
        loggingIn = true;
        progressLogin.setVisibility(View.VISIBLE);
        btnLogin.setEnabled(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                SessionManager.getInstance(LoginActivity.this).saveLogin(phone);
                goMain();
            }
        }, MOCK_LOGIN_DELAY_MS);
    }

    private void goMain() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    private boolean isValidPhone(String phone) {
        return phone.length() == 11 && phone.startsWith("1");
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
