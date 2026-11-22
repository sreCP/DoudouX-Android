package com.doudou.x.ai.observability;

import android.content.Context;
import android.util.Log;

import com.doudou.x.data.OpsConfigStore;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 把 {@link TraceEvent} 上报到 Agent Ops 后端。
 *
 * <p>刻意只做「发一条 JSON」这件事：地址来自 {@link OpsConfigStore}，
 * 失败由调用方 {@link TraceEvent#writeTo(Context)} 决定是否退回本地写盘。
 * 两个动作分开，是为了让「后端不可用」时依然有 trace 留下。
 *
 * <p>注意：本类是同步网络调用，必须在后台线程调用；超时设得短，
 * 因为可观测性是旁路，不能让对话等它。
 */
public final class TraceReporter {

    private static final String LOG_TAG = "DoudouX-Ops";
    private static final int CONNECT_TIMEOUT_MS = 3000;
    private static final int READ_TIMEOUT_MS = 5000;

    private TraceReporter() {
    }

    /**
     * 上报一条 trace，返回是否成功（HTTP 2xx）。
     *
     * <p>未配置后端地址时返回 false，调用方会照常写本地文件。
     */
    public static boolean report(Context context, TraceEvent event) {
        if (context == null || event == null) {
            return false;
        }
        OpsConfigStore config = OpsConfigStore.getInstance(context);
        String url = config.uploadUrl();
        if (url.isEmpty()) {
            return false;
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");

            DataOutputStream os = new DataOutputStream(conn.getOutputStream());
            try {
                os.write(event.toJsonLine().getBytes("UTF-8"));
                os.flush();
            } finally {
                os.close();
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                Log.d(LOG_TAG, "trace 上报成功，model=" + event.model + "，HTTP " + code);
                return true;
            }
            Log.w(LOG_TAG, "trace 上报失败，HTTP " + code + "：" + clip(read(conn.getErrorStream()), 120));
            return false;
        } catch (Exception e) {
            Log.w(LOG_TAG, "trace 上报失败（将退回本地）：" + e.getMessage());
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /**
     * 探测后端是否可达，返回值是可直接展示给用户的中文说明。
     * 供设置页「测试后端连接」按钮在后台线程调用。
     */
    public static String testConnection(Context context) {
        if (context == null) {
            return "上下文为空，无法测试";
        }
        OpsConfigStore config = OpsConfigStore.getInstance(context);
        String url = config.healthUrl();
        if (url.isEmpty()) {
            return "尚未填写后端地址";
        }

        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                return "后端返回 HTTP " + code;
            }
            String body = read(conn.getInputStream()).trim();
            // 只认 /api/health 的契约字段，不比对服务名：
            // 服务端改过名（doudou-ops → doudou-server），写死名字会让旧断言误判
            if (!body.contains("\"ok\":true")) {
                return "地址可达，但响应不是本服务：" + clip(body, 80);
            }
            return "连接正常：" + config.normalizedBaseUrl();
        } catch (Exception e) {
            String message = e.getMessage();
            return "连接失败：" + (message == null ? e.toString() : clip(message, 120));
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String read(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
        } catch (Exception ignored) {
            // 返回已读到的部分
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Exception ignored) {
                    // 忽略
                }
            }
        }
        return sb.toString();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
