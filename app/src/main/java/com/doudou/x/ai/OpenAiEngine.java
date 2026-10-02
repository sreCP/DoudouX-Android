package com.doudou.x.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseArray;

import com.doudou.x.ai.context.ContextManager;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

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
 * <p>
 * 请求：POST {baseUrl}/chat/completions
 * {"model": "...", "stream": true, "messages": [{"role":"user","content":"..."}]}
 * 响应：SSE 流，逐行解析 "data: {...}"，
 * 取 choices[0].delta.content 增量拼成完整文本；
 * "data: [DONE]" 结束。整个原始响应体会累计下来，
 * 随 onComplete / onError 回传，供「查看原始返回」使用。
 */
public class OpenAiEngine implements AiEngine {

    /**
     * 排查专用：Logcat 过滤该 tag 即可看到完整请求与整理后的流式日志。
     * 与 {@link StreamLogger} 共用同一个 tag，一次过滤能看到全部内容。
     */
    public static final String LOG_TAG = StreamLogger.LOG_TAG;

    private static final int CONNECT_TIMEOUT_MS = 30000;
    /**
     * 流式读取超时：0 表示不限制。
     * <p>
     * 思考型模型（reasoning）在正式回答前可能长时间只吐思考内容、甚至静默很久，
     * 之前固定 60s 会在思考还没结束时就被判成超时，这里改成不限制；
     * 不想等了用界面上的「停止」按钮主动断开即可。
     */
    private static final int READ_TIMEOUT_MS = 0;
    /**
     * Function Calling 最多自动接续的轮数，防止模型无限调用工具。
     */
    private static final int MAX_TOOL_ROUNDS = 3;
    /**
     * 原始响应最多保留的字符数，避免超长思考把内存撑爆。
     */
    private static final int MAX_RAW_CHARS = 1024 * 1024;

    private final ApiConfigStore config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ToolExecutor toolExecutor;
    /** 上下文预算与滚动压缩的入口，本身是单例。 */
    private final ContextManager contextManager = ContextManager.get();

    private volatile boolean cancelled = false;
    private volatile HttpURLConnection activeConnection;

    /**
     * 最近一次请求的接口地址与请求体，供「测试连接」弹窗展示。
     */
    private volatile String lastEndpoint = "";
    private volatile String lastRequestBody = "";

    public OpenAiEngine(ApiConfigStore config) {
        this.config = config;
    }

    /**
     * 设置工具执行器。传 null 表示这套引擎不参与工具调用（例如生成标题的请求）。
     */
    public void setToolExecutor(ToolExecutor executor) {
        this.toolExecutor = executor;
    }

    public String getLastEndpoint() {
        return lastEndpoint;
    }

    public String getLastRequestBody() {
        return lastRequestBody;
    }

    /**
     * 用一句 "hi" 打一次真实请求，用于验证配置是否正确。
     */
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
                doRequest(history, callback, MAX_TOOL_ROUNDS, new ArrayList<ToolCall>());
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

    /**
     * @param roundsLeft       剩余可自动接续的工具轮数
     * @param carriedToolCalls 之前轮次已执行的工具调用，用于最终一次性展示
     */
    private void doRequest(List<ChatMessage> history, StreamCallback callback,
                           int roundsLeft, List<ToolCall> carriedToolCalls) {
        final StringBuilder raw = new StringBuilder();
        final StringBuilder answer = new StringBuilder();
        // 思考型模型（reasoning / reasoning_content）的思考过程，单独累积
        final StringBuilder reasoning = new StringBuilder();
        final String endpoint =
                config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        // 本次流式会话的日志：整理成可读块 + 按批保留原始事件
        final StreamLogger logger = new StreamLogger(endpoint, config.getModel());
        String finishReason = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            boolean switchOn = config.isFunctionCallingEnabled();
            boolean toolsEnabled = switchOn && toolExecutor != null && roundsLeft > 0;
            // 排查用：请求体里没看到 tools 时，看这行就知道是被哪个条件挡住的
            Log.d(LOG_TAG, "工具调用状态：开关=" + switchOn
                    + "，执行器已注入=" + (toolExecutor != null)
                    + "，剩余轮次=" + roundsLeft
                    + "，本次下发 tools=" + toolsEnabled);
            String bodyJson = buildRequestBody(history, toolsEnabled);
            lastEndpoint = endpoint;
            lastRequestBody = bodyJson;
            Log.d(LOG_TAG, "请求方式：POST，请求地址：" + endpoint);
            Log.d(LOG_TAG, "鉴权头：Bearer " + maskKey(config.getApiKey()));
            Log.d(LOG_TAG, "请求体：" + bodyJson);

            byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            int code = conn.getResponseCode();
            Log.d(LOG_TAG, "响应状态码：" + code + "，状态信息：" + conn.getResponseMessage());
            InputStream stream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                Log.e(LOG_TAG, "响应状态码：" + code + "，响应体为空");
                notifyError(callback, "HTTP " + code + "，无响应体", raw.toString());
                return;
            }

            postStart(callback);
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            boolean done = false;
            // 工具调用分片累积：index → 调用
            final SparseArray<ToolCallBuilder> toolBuilders = new SparseArray<>();
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    return;
                }
                boolean blank = line.trim().isEmpty();
                appendRaw(raw, line);
                // 原始事件交给日志类按批整理；blank 行没有信息量，日志里不出现
                if (!blank) {
                    logger.appendRaw(line);
                }
                if (!line.startsWith("data:")) {
                    // 兼容 Ollama 原生接口（/api/chat、/api/generate）直接返回的 NDJSON
                    String jsonLine = line.trim();
                    if (jsonLine.startsWith("{")) {
                        String[] nativeDelta = parseNativeChunk(jsonLine);
                        String nativeAnswer = nativeDelta == null ? "" : nativeDelta[0];
                        String nativeReasoning = nativeDelta == null ? "" : nativeDelta[1];
                        // 与 SSE 分支一致：先累计字数，再登记事件（可能触发打印）
                        logger.countAnswer(nativeAnswer.length());
                        logger.countReasoning(nativeReasoning.length());
                        logger.onEvent(jsonLine, false, nativeAnswer, nativeReasoning, null, 0);
                        boolean changed = false;
                        if (!nativeAnswer.isEmpty()) {
                            answer.append(nativeAnswer);
                            changed = true;
                        }
                        if (!nativeReasoning.isEmpty()) {
                            reasoning.append(nativeReasoning);
                            changed = true;
                        }
                        if (changed) {
                            notifyToken(callback, answer.toString(), reasoning.toString());
                        }
                        if (isNativeDone(jsonLine)) {
                            done = true;
                            break;
                        }
                    }
                    continue; // 跳过 event:、注释、空行
                }
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    done = true;
                    break;
                }
                // 一条事件只解析一次：增量、结束原因、工具分片都从这里取
                ParsedEvent event = parseEvent(payload);
                if (event == null) {
                    logger.onEvent(payload, true, "", "", null, 0);
                    logger.onParseFailure(payload);
                    continue;
                }
                if (event.finishReason != null) {
                    finishReason = event.finishReason;
                }
                // 先累计字数再登记事件：onEvent 可能因为满块而立即打印，
                // 那时计数必须已经包含本条增量，否则块的"累计N字"会滞后一块
                logger.countAnswer(event.answer.length());
                logger.countReasoning(event.reasoning.length());
                logger.onEvent(payload, true, event.answer, event.reasoning,
                        event.finishReason, event.toolCallDeltas);
                mergeToolCallDeltas(event, toolBuilders);
                boolean changed = false;
                if (!event.answer.isEmpty()) {
                    answer.append(event.answer);
                    changed = true;
                }
                if (!event.reasoning.isEmpty()) {
                    reasoning.append(event.reasoning);
                    changed = true;
                }
                if (changed) {
                    notifyToken(callback, answer.toString(), reasoning.toString());
                }
            }
            reader.close();

            if (code < 200 || code >= 300) {
                Log.e(LOG_TAG, "请求失败，响应状态码：" + code + "，响应体：" + raw);
                // 直接把服务端的 message 带出来，例如「model does not exist」
                String serverMessage = extractServerMessage(raw.toString());
                notifyError(callback, "HTTP " + code
                        + (serverMessage == null ? "" : " · " + serverMessage), raw.toString());
            } else if (answer.length() == 0 && reasoning.length() == 0
                    && toolBuilders.size() == 0) {
                notifyError(callback, done ? "响应内容为空" : "流意外中断", raw.toString());
            } else {
                // 模型发起了工具调用：本地执行后把结果回传，再自动发起下一轮
                List<ToolCall> roundCalls = buildToolCalls(toolBuilders);
                if (!roundCalls.isEmpty() && toolExecutor != null && roundsLeft <= 0) {
                    // 轮次用尽仍要调工具：不再循环，明确提示，避免返回一条空消息
                    Log.w(LOG_TAG, "工具调用轮次已达上限（" + MAX_TOOL_ROUNDS + "轮），停止调用");
                    notifyError(callback, "工具调用轮次已达上限（最多 "
                            + MAX_TOOL_ROUNDS + " 轮），已停止", raw.toString());
                } else if (!roundCalls.isEmpty() && toolExecutor != null) {
                    for (ToolCall call : roundCalls) {
                        call.setResult(toolExecutor.execute(call.getName(), call.getArguments()));
                        Log.d(LOG_TAG, "工具调用：" + call.getName()
                                + "，参数：" + call.getArguments()
                                + "，结果：" + call.getResult());
                    }
                    List<ToolCall> allCalls = new ArrayList<>(carriedToolCalls);
                    allCalls.addAll(roundCalls);
                    notifyToolCalls(callback, allCalls);

                    List<ChatMessage> nextHistory = new ArrayList<>(history);
                    ChatMessage assistantCall = new ChatMessage(ChatMessage.ROLE_AI, "");
                    assistantCall.setToolCalls(roundCalls);
                    nextHistory.add(assistantCall);
                    for (ToolCall call : roundCalls) {
                        ChatMessage toolMessage =
                                new ChatMessage(ChatMessage.ROLE_TOOL, call.getResult());
                        toolMessage.setToolCallId(call.getId());
                        nextHistory.add(toolMessage);
                    }
                    doRequest(nextHistory, callback, roundsLeft - 1, allCalls);
                    return;
                }
                if ("length".equals(finishReason)) {
                    // 常见原因：Ollama 的 num_predict 默认只有 1024，
                    // 思考过程先把预算吃光，正式回答一个字都没出来
                    Log.w(LOG_TAG, "输出被截断：服务端返回 finish_reason=length。"
                            + "可在接口设置里把「最大输出 Token」填大（如 8192），"
                            + "或关掉模型思考");
                }
                // 正式回答与思考过程分开回传，由界面决定怎么展示
                notifyComplete(callback, answer.toString(), reasoning.toString(), raw.toString());
            }
        } catch (Exception e) {
            if (!cancelled) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.e(LOG_TAG, "请求异常：" + message, e);
                notifyError(callback, "请求失败：" + message, raw.length() > 0 ? raw.toString() : null);
            }
        } finally {
            activeConnection = null;
            // 任何退出路径（正常结束 / 取消 / 异常 / 工具续轮）都要收尾，
            // 否则最后一批内容块和结束汇总会丢；close 幂等，重复调用无副作用
            logger.close(finishReason, extractUsage(raw.toString()), raw.toString());
        }
    }

    /**
     * 一条事件解析后的全部可用信息。
     *
     * <p>把原来分散的 parseDelta / parseFinishReason / collectToolCallDeltas
     * 合并成一次解析：既能为日志提供一条事件的完整元信息，也省掉同一个
     * JSONObject 被反复构造的开销。
     */
    private static final class ParsedEvent {
        /** 正式回答增量，无增量为空串。 */
        String answer = "";
        /** 思考过程增量，无增量为空串。 */
        String reasoning = "";
        /** 服务端结束原因（stop / length / tool_calls），非 null 表示本条带该字段。 */
        String finishReason;
        /** 本条事件携带的 tool_calls 分片数量。 */
        int toolCallDeltas;
        /** 本条事件的 tool_calls 原始数组，供合并分片使用。 */
        JSONArray toolCallArray;
    }

    /** 解析一条 SSE 事件正文；非 JSON 或没有 choices 时返回 null。 */
    private ParsedEvent parseEvent(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(payload);
        } catch (Exception e) {
            return null;
        }
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject choice = choices.optJSONObject(0);
        if (choice == null) {
            return null;
        }
        ParsedEvent event = new ParsedEvent();
        String reason = choice.optString("finish_reason", null);
        if (reason != null && !reason.isEmpty() && !"null".equals(reason)) {
            event.finishReason = reason;
        }
        JSONObject delta = choice.optJSONObject("delta");
        if (delta != null) {
            event.answer = optText(delta, "content");
            // 兼容思考型模型：reasoning（Ollama / Qwen3）与 reasoning_content（DeepSeek）
            event.reasoning = optText(delta, "reasoning");
            if (event.reasoning.isEmpty()) {
                event.reasoning = optText(delta, "reasoning_content");
            }
            JSONArray calls = delta.optJSONArray("tool_calls");
            if (calls != null) {
                event.toolCallDeltas = calls.length();
                event.toolCallArray = calls;
            }
        }
        return event;
    }

    /** 取字符串字段，把 JSON null 与字段缺失统一成空串。 */
    private static String optText(JSONObject obj, String key) {
        String value = obj.optString(key, null);
        return value == null || "null".equals(value) ? "" : value;
    }

    /**
     * 从原始响应里提取最后一个 usage 对象，供日志汇总展示。
     *
     * <p>流式响应里 usage 通常只在最后一条事件出现（部分服务端需要
     * {@code stream_options.include_usage}），所以从后往前找第一个即最近的那个。
     *
     * @return usage 的 JSON 文本；没有则返回 null，不占日志
     */
    private String extractUsage(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        String[] lines = rawBody.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String payload = lines[i].trim();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            if (!payload.startsWith("{") || payload.indexOf("usage") < 0) {
                continue;
            }
            try {
                JSONObject usage = new JSONObject(payload).optJSONObject("usage");
                if (usage != null && usage.length() > 0) {
                    return usage.toString();
                }
            } catch (Exception ignored) {
                // 这一行不是合法 JSON，继续往前找
            }
        }
        return null;
    }

    /** 累积一行原始数据。思考过程可能非常长，这里设一个上限，
     * 超出后只做标记，避免「查看原始返回」把内存吃光。
     */
    private void appendRaw(StringBuilder raw, String line) {
        if (raw.length() >= MAX_RAW_CHARS) {
            return;
        }
        raw.append(line).append('\n');
        if (raw.length() >= MAX_RAW_CHARS) {
            raw.append("…（原始响应过长，已截断）\n");
        }
    }

    /**
     * 尝试从错误响应里提取 message，便于气泡直接显示原因。
     */
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

    /**
     * 只显示 key 首尾，避免完整密钥进日志。
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 12) {
            return "***";
        }
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }

    /**
     * 构造 OpenAI 兼容请求体（默认带完整多轮上下文）。
     */
    private String buildRequestBody(List<ChatMessage> history, boolean withTools) {
        try {
            // 服务端不保存会话：完整历史由客户端每次回传；
            // 关闭该开关时只发送当前这一句，用于省流或单轮问答场景
            List<ChatMessage> toSend = history;
            if (!config.isSendFullHistory() && history != null) {
                ChatMessage lastUser = null;
                for (int i = history.size() - 1; i >= 0; i--) {
                    if (history.get(i).getRole() == ChatMessage.ROLE_USER) {
                        lastUser = history.get(i);
                        break;
                    }
                }
                if (lastUser != null) {
                    toSend = new ArrayList<>();
                    toSend.add(lastUser);
                }
            }

            String systemPrompt = config.getSystemPrompt();
            // 分层预算、历史裁剪与滚动摘要都在 ai.context 模块内，这里只取组装结果
            JSONArray messages = contextManager.buildMessages(toSend, systemPrompt, withTools);
            JSONObject body = new JSONObject();
            body.put("model", config.getModel());
            body.put("stream", true);
            body.put("messages", messages);
            // 模型参数：未设置的不下发，交给服务端默认值
            float temperature = config.getTemperature();
            if (temperature >= 0f) {
                body.put("temperature", temperature);
            }
            float topP = config.getTopP();
            if (topP >= 0f) {
                body.put("top_p", topP);
            }
            int maxTokens = config.getMaxTokens();
            if (maxTokens > 0) {
                body.put("max_tokens", maxTokens);
            }
            // 关闭模型思考：用 reasoning_effort=none（think=false 对部分服务端不生效）
            if (config.isDisableThinking()) {
                body.put("reasoning_effort", "none");
            }
            // Function Calling：声明可用工具，交给模型决定要不要调用
            if (withTools) {
                body.put("tools", ToolRegistry.toolsJson());
                body.put("tool_choice", "auto");
            }
            return body.toString();
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败：" + e.getMessage(), e);
        }
    }

    /**
     * 把一条已解析事件的 tool_calls 分片并进累积器。
     *
     * <p>delta.tool_calls 是数组，每个元素带 index，id / name / arguments
     * 都是流式分片拼出来的，所以这里只做追加，不做覆盖。
     */
    private void mergeToolCallDeltas(ParsedEvent event, SparseArray<ToolCallBuilder> builders) {
        if (event == null || event.toolCallDeltas == 0) {
            return;
        }
        for (int i = 0; i < event.toolCallDeltas; i++) {
            JSONObject call = event.toolCallArray.optJSONObject(i);
            if (call == null) {
                continue;
            }
            int index = call.optInt("index", i);
            ToolCallBuilder builder = builders.get(index);
            if (builder == null) {
                builder = new ToolCallBuilder();
                builders.put(index, builder);
            }
            String id = call.optString("id", null);
            if (id != null && !id.isEmpty() && !"null".equals(id)) {
                builder.id = id;
            }
            JSONObject function = call.optJSONObject("function");
            if (function == null) {
                continue;
            }
            String name = function.optString("name", null);
            if (name != null && !name.isEmpty() && !"null".equals(name)) {
                builder.name.append(name);
            }
            String arguments = function.optString("arguments", null);
            if (arguments != null && !arguments.isEmpty() && !"null".equals(arguments)) {
                builder.arguments.append(arguments);
            }
        }
    }

    /**
     * 把分片累积结果整理成工具调用列表。
     */
    private List<ToolCall> buildToolCalls(SparseArray<ToolCallBuilder> builders) {
        List<ToolCall> calls = new ArrayList<>();
        for (int i = 0; i < builders.size(); i++) {
            ToolCallBuilder builder = builders.valueAt(i);
            if (builder == null || builder.name.length() == 0) {
                continue;
            }
            ToolCall call = new ToolCall();
            String id = builder.id;
            call.setId(id == null || id.isEmpty() ? "call_" + i : id);
            call.setName(builder.name.toString());
            call.setArguments(builder.arguments.toString());
            calls.add(call);
        }
        return calls;
    }

    /**
     * tool_calls 分片累积器。
     */
    private static class ToolCallBuilder {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 解析 Ollama 原生接口一行 NDJSON（/api/chat 的 message.content、
     * /api/generate 的 response），返回 [回答, 思考过程]；不是这类数据返回 null。
     */
    private String[] parseNativeChunk(String line) {
        try {
            JSONObject obj = new JSONObject(line);
            String content = null;
            String thinking = null;
            JSONObject message = obj.optJSONObject("message");
            if (message != null) {
                content = message.optString("content", null);
                thinking = message.optString("thinking", null);
            }
            if (content == null) {
                content = obj.optString("response", null); // /api/generate
            }
            if (thinking == null) {
                thinking = obj.optString("thinking", null);
            }
            if (content == null && thinking == null) {
                return null;
            }
            return new String[]{
                    content == null ? "" : content,
                    thinking == null ? "" : thinking};
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Ollama 原生接口用 done=true 标记结束。
     */
    private boolean isNativeDone(String line) {
        try {
            return new JSONObject(line).optBoolean("done", false);
        } catch (Exception e) {
            return false;
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

    private void notifyToken(final StreamCallback callback, final String fullText,
                             final String thinkingText) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onToken(fullText, thinkingText);
                }
            }
        });
    }

    private void notifyComplete(final StreamCallback callback, final String fullText,
                                final String thinkingText, final String raw) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onComplete(fullText, thinkingText, raw);
                }
            }
        });
    }

    private void notifyToolCalls(final StreamCallback callback, final List<ToolCall> calls) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    callback.onToolCall(calls);
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
