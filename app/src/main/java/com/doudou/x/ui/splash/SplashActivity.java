package com.doudou.x.ui.splash;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.doudou.x.R;
import com.doudou.x.data.SessionManager;
import com.doudou.x.ui.chat.MainActivity;
import com.doudou.x.ui.login.LoginActivity;

/**
 * 闪屏页：展示品牌信息，"加载"完成后按登录态路由。
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DURATION_MS = 900;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable routeRunnable = new Runnable() {
        @Override
        public void run() {
            routeNext();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);
        // 模拟启动加载（真实场景可在此做初始化、预拉取配置等）
        handler.postDelayed(routeRunnable, SPLASH_DURATION_MS);
    }

    private void routeNext() {
        boolean loggedIn = SessionManager.getInstance(this).isLoggedIn();
        Class<?> target = loggedIn ? MainActivity.class : LoginActivity.class;
        startActivity(new Intent(this, target));
        finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(routeRunnable);
        super.onDestroy();
    }
}
