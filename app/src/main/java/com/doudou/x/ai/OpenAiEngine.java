package com.doudou.x.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容格式的真实 API 引擎。
 *
 * 请求：POST {baseUrl}/chat/completions
 *   {"model": "...", "stream": true, "messages": [{"role":"user","content":"..."}]}
 * 响应：SSE 流，逐行解析 "data: {...}"，
 *   取 choices[0].delta.content 增量拼成完整文本；
 *   "data: [DONE]" 结束。整个原始响应体会累计下来，
 *   随 onComplete / onError 回传，供「查看原始返回」使用。
 */
public class OpenAiEngine implements AiEngine {

    /** 排查专用：Logcat 过滤该 tag 即可看到完整请求与原始响应。 */
    public static final String LOG_TAG = "DoudouX";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 60000;

    private final ApiConfigStore config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile boolean cancelled = false;
    private volatile HttpURLConnection activeConnection;

    /** 最近一次请求的接口地址与请求体，供「测试连接」弹窗展示。 */
    private volatile String lastEndpoint = "";
    private volatile String lastRequestBody = "";

    public OpenAiEngine(ApiConfigStore config) {
        this.config = config;
    }

    public String getLastEndpoint() {
        return lastEndpoint;
    }

    public String getLastRequestBody() {
        return lastRequestBody;
    }

    /** 用一句 "hi" 打一次真实请求，用于验证配置是否正确。 */
    public void testConnection(StreamCallback callback) {
        List<ChatMessage> test = new ArrayList<>();
        test.add(new ChatMessage(ChatMessage.ROLE_USER, "hi"));
        streamReply(test, callback);
    }

    @Override
    public void streamReply(final List<ChatMessage> history, final StreamCallback callback) {
        cancel();
        cancelled = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                doRequest(history, callback);
            }
        });
    }

    @Override
    public void cancel() {
        cancelled = true;
        HttpURLConnection conn = activeConnection;
        if (conn != null) {
            conn.disconnect();
            activeConnection = null;
        }
    }

    private void doRequest(List<ChatMessage> history, StreamCallback callback) {
        final StringBuilder raw = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        try {
            String endpoint = config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            String bodyJson = buildRequestBody(history);
            lastEndpoint = endpoint;
            lastRequestBody = bodyJson;
            // 排查用日志：Logcat 过滤 DoudouX
            Log.d(LOG_TAG, "POST " + endpoint);
            Log.d(LOG_TAG, "Authorization: Bearer " + maskKey(config.getApiKey()));
            Log.d(LOG_TAG, "RequestBody: " + bodyJson);

            byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            Log.d(LOG_TAG, "HTTP " + code + " " + conn.getResponseMessage());
            InputStream stream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                Log.e(LOG_TAG, "HTTP " + code + "，无响应体");
                notifyError(callback, "HTTP " + code + "，无响应体", raw.toString());
                return;
            }

            postStart(callback);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            boolean done = false;
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    return;
                }
                raw.append(line).append('\n');
                Log.v(LOG_TAG, "sse| " + line);
                if (!line.startsWith("data:")) {
                    continue; // 跳过 event:、注释、空行
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    done = true;
                    break;
                }
                String delta = parseDeltaContent(payload);
                if (delta != null && !delta.isEmpty()) {
                    answer.append(delta);
                    notifyToken(callback, answer.toString());
                }
            }
            reader.close();

            if (code < 200 || code >= 300) {
                Log.e(LOG_TAG, "请求失败 HTTP " + code + " 响应体: " + raw);
                // 直接把服务端的 message 带出来，例如「model does not exist」
                String serverMessage = extractServerMessage(raw.toString());
                notifyError(callback, "HTTP " + code
                        + (serverMessage == null ? "" : " · " + serverMessage), raw.toString());
            } else if (answer.length() == 0) {
                notifyError(callback, done ? "响应内容为空" : "流意外中断", raw.toString());
            } else {
                notifyComplete(callback, answer.toString(), raw.toString());
            }
        } catch (Exception e) {
            if (!cancelled) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.e(LOG_TAG, "请求异常: " + message, e);
                notifyError(callback, "请求失败：" + message, raw.length() > 0 ? raw.toString() : null);
            }
        } finally {
            activeConnection = null;
        }
    }

    /** 尝试从错误响应里提取 message，便于气泡直接显示原因。 */
    private String extractServerMessage(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        String text = rawBody.trim();
        if (text.startsWith("data:")) {
            text = text.substring(5).trim();
        }
        try {
            JSONObject obj = new JSONObject(text);
            String message = obj.optString("message", null);
            if (message == null) {
                JSONObject error = obj.optJSONObject("error");
                if (error != null) {
                    message = error.optString("message", null);
                }
            }
            return message;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 只显示 key 首尾，避免完整密钥进日志。 */
    private String maskKey(String key) {
        if (key == null || key.length() <= 12) {
            return "***";
        }
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }

    /** 构造 OpenAI 兼容请求体（完整多轮上下文）。 */
    private String buildRequestBody(List<ChatMessage> history) {
        try {
            JSONArray messages = new JSONArray();
            if (history != null) {
                for (ChatMessage msg : history) {
                    String content = msg.getContent();
                    if (content == null || content.isEmpty()) {
                        continue; // 跳过占位中的空 AI 消息
                    }
                    JSONObject item = new JSONObject();
                    item.put("role", msg.getRole() == ChatMessage.ROLE_USER ? "user" : "assistant");
                    item.put("content", content);
                    messages.put(item);
                }
            }
            JSONObject body = new JSONObject();
            body.put("model", config.getModel());
            body.put("stream", true);
            body.put("messages", messages);
            return body.toString();
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败：" + e.getMessage(), e);
        }
    }

    /** 解析一行 SSE data：取 choices[0].delta.content。 */
    private String parseDeltaContent(String payload) {
        try {
            JSONObject obj = new JSONObject(payload);
            JSONArray choices = obj.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return null;
            }
            JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
            if (delta == null) {
                return null;
            }
            return delta.optString("content", null);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 主线程回调
    // ------------------------------------------------------------------

    private void postStart(final StreamCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onStart();
                }
            }
        });
    }

    private void notifyToken(final StreamCallback callback, final String fullText) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onToken(fullText);
                }
            }
        });
    }

    private void notifyComplete(final StreamCallback callback,
                                final String fullText, final String raw) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onComplete(fullText, raw);
                }
            }
        });
    }

    private void notifyError(final StreamCallback callback,
                             final String errorMessage, final String raw) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onError(errorMessage, raw);
                }
            }
        });
    }
}
