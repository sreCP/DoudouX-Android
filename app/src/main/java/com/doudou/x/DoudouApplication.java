package com.doudou.x;

import android.app.Application;

/**
 * 兜兜X 应用入口。
 */
public class DoudouApplication extends Application {

    private static DoudouApplication instance;

    public static DoudouApplication getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }
}
