package com.doudou.x.ai.openai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 流式请求的传输层：建连、写请求体、取响应流、被取消时断开。
 *
 * <p>刻意不引入三方网络库，保留手写 {@link HttpURLConnection} 的做法，
 * 但把「连接怎么建、怎么断」从编排逻辑里隔离出来：换实现（OkHttp / 加拦截器 /
 * 走代理）只改这一个类，引擎侧无感知。
 */
public final class ChatHttpTransport {

    private static final int CONNECT_TIMEOUT_MS = 30000;

    /**
     * 流式读取超时：0 表示不限制。
     *
     * <p>思考型模型在正式回答前可能长时间只吐思考内容、甚至静默很久，
     * 固定超时会在思考还没结束时就被判成超时；不想等了由界面「停止」按钮主动断开。
     */
    private static final int READ_TIMEOUT_MS = 0;

    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String HEADER_ACCEPT = "Accept";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String MIME_JSON = "application/json";
    private static final String MIME_EVENT_STREAM = "text/event-stream";

    /** 当前连接；取消时从这里断开，让阻塞在 readLine 的线程立刻返回。 */
    private volatile HttpURLConnection active;

    /**
     * 发起一次 POST 并返回响应。
     *
     * @param bodyJson 请求体 JSON 文本，内部按 UTF-8 编码写出
     */
    public Response post(String endpoint, String apiKey, String bodyJson) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        active = conn;
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setDoOutput(true);
        conn.setRequestProperty(HEADER_CONTENT_TYPE, MIME_JSON);
        conn.setRequestProperty(HEADER_ACCEPT, MIME_EVENT_STREAM);
        conn.setRequestProperty(HEADER_AUTHORIZATION, "Bearer " + apiKey);

        OutputStream os = conn.getOutputStream();
        os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? conn.getInputStream() : conn.getErrorStream();
        return new Response(code, conn.getResponseMessage(), stream);
    }

    /** 主动取消：直接断开连接。可重复调用。 */
    public void abort() {
        HttpURLConnection conn = active;
        if (conn != null) {
            conn.disconnect();
            active = null;
        }
    }

    /** 正常收尾：只清引用，连接由响应流的 close 负责。 */
    public void release() {
        active = null;
    }

    /** 一次 HTTP 响应。stream 为 null 表示服务端没给响应体。 */
    public static final class Response {
        public final int code;
        public final String message;
        public final InputStream stream;

        Response(int code, String message, InputStream stream) {
            this.code = code;
            this.message = message;
            this.stream = stream;
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }
}
