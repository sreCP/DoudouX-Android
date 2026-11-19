package com.doudou.x.ai.device;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.util.Log;

import com.doudou.x.DoudouApplication;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 设备状态原生工具：读取型号、系统版本、电量与联网状态。
 *
 * <p>全部为只读，不需要任何运行时权限，也不会改动用户数据。
 */
public final class DeviceTool implements NativeToolSet {

    private static final String LOG_TAG = "DoudouX-Native";

    public static final String GET_DEVICE_INFO = "get_device_info";
    public static final String GET_NETWORK_STATUS = "get_network_status";

    @Override
    public JSONArray definitions(boolean includeWrite) {
        JSONArray array = new JSONArray();
        array.put(function(GET_DEVICE_INFO,
                "获取本机设备信息：品牌、型号、系统版本、SDK 版本、电量百分比与充电状态。"
                        + "不涉及任何个人隐私数据，也不需要权限。",
                new JSONObject()));
        array.put(function(GET_NETWORK_STATUS,
                "获取本机当前联网状态：是否联网、网络类型（WLAN/移动数据/以太网）。",
                new JSONObject()));
        return array;
    }

    @Override
    public String execute(String name, JSONObject args) {
        if (GET_DEVICE_INFO.equals(name)) {
            return deviceInfo();
        }
        if (GET_NETWORK_STATUS.equals(name)) {
            return networkStatus();
        }
        return null;
    }

    @Override
    public boolean isWriteTool(String name) {
        return false;
    }

    private String deviceInfo() {
        StringBuilder sb = new StringBuilder("设备信息：\n");
        sb.append("· 品牌：").append(Build.BRAND).append('\n');
        sb.append("· 型号：").append(Build.MODEL).append('\n');
        sb.append("· 系统版本：").append(Build.VERSION.RELEASE)
                .append("（SDK ").append(Build.VERSION.SDK_INT).append("）\n");
        Context context = appContext();
        if (context != null) {
            try {
                Intent battery = context.registerReceiver(null,
                        new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
                    if (level >= 0 && scale > 0) {
                        int percent = Math.round(level * 100f / scale);
                        sb.append("· 电量：").append(percent).append('%').append('\n');
                    }
                    sb.append("· 充电中：").append(plugged != 0 ? "是" : "否").append('\n');
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "读取电量失败：" + e.getMessage());
            }
        }
        return sb.toString();
    }

    private String networkStatus() {
        Context context = appContext();
        if (context == null) {
            return "应用上下文未初始化";
        }
        ConnectivityManager manager =
                (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return "无法读取网络状态";
        }
        try {
            Network network = manager.getActiveNetwork();
            if (network == null) {
                return "当前未联网";
            }
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null) {
                return "已连接网络，但无法读取网络类型";
            }
            StringBuilder sb = new StringBuilder("当前网络：已连接，类型=");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                sb.append("WLAN");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                sb.append("移动数据");
            } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                sb.append("以太网");
            } else {
                sb.append("其他");
            }
            return sb.toString();
        } catch (Exception e) {
            Log.w(LOG_TAG, "读取网络状态失败：" + e.getMessage());
            return "读取网络状态失败：" + e.getMessage();
        }
    }

    private static Context appContext() {
        DoudouApplication app = DoudouApplication.getInstance();
        return app == null ? null : app.getApplicationContext();
    }

    private static JSONObject function(String name, String description, JSONObject parameters) {
        JSONObject function = new JSONObject();
        JSONObject tool = new JSONObject();
        try {
            if (parameters.length() == 0) {
                parameters.put("type", "object");
                parameters.put("properties", new JSONObject());
            }
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", parameters);
            tool.put("type", "function");
            tool.put("function", function);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
        return tool;
    }
}
