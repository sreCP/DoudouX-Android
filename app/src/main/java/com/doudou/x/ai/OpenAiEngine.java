package com.doudou.x.ai;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.doudou.x.DoudouApplication;
import com.doudou.x.ai.agent.ToolLoop;
import com.doudou.x.ai.context.ContextManager;
import com.doudou.x.ai.context.ContextPlan;
import com.doudou.x.ai.observability.TraceEvent;
import com.doudou.x.ai.openai.ChatHttpTransport;
import com.doudou.x.ai.openai.RequestBodyBuilder;
import com.doudou.x.ai.openai.SseEvent;
import com.doudou.x.ai.openai.SseEventParser;
import com.doudou.x.ai.openai.ToolCallAccumulator;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;
import com.doudou.x.util.CappedTextBuffer;
import com.doudou.x.util.JsonUtils;
import com.doudou.x.util.Texts;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * OpenAI 兼容格式的真实 API 引擎——<b>只做编排</b>。
 *
 * <p>请求：POST {baseUrl}/chat/completions；
 * 响应：SSE 流，逐行解析后取 choices[0].delta 增量拼成完整文本，
 * 整个原始响应体会累计下来，随 onComplete / onError 回传供「查看原始返回」使用。
 *
 * <p>职责边界（拆出去的协作方）：
 * <ul>
 *   <li>{@link RequestBodyBuilder}：请求体长什么样（含精简请求模式）；</li>
 *   <li>{@link ChatHttpTransport}：连接怎么建、怎么断；</li>
 *   <li>{@link SseEventParser} / {@link ToolCallAccumulator}：一行报文怎么解、分片怎么拼；</li>
 *   <li>{@link ToolLoop}：模型要调工具时，执行与上下文回填；</li>
 *   <li>{@link CappedTextBuffer} / {@link JsonUtils} / {@link Texts}：原文累积、JSON 取值、文本处理。</li>
 * </ul>
 *
 * <p>留在这里的是它无法外包的部分：线程模型、流式主循环的先后顺序、
 * 各阶段该回调哪个 UI 事件、以及一次调用的可观测收尾。
 */
public class OpenAiEngine implements AiEngine {

    /**
     * 排查专用：Logcat 过滤该 tag 即可看到完整请求与整理后的流式日志。
     * 与 {@link StreamLogger} 共用同一个 tag，一次过滤能看到全部内容。
     */
    private static final String LOG_TAG = StreamLogger.LOG_TAG;

    /**
     * 原始响应最多保留的字符数，避免超长思考把内存撑爆。
     */
    private static final int MAX_RAW_CHARS = 1024 * 1024;

    private static final String RAW_TRUNCATED_MARK = "…（原始响应过长，已截断）";

    private final ApiConfigStore config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** 上下文预算与滚动压缩的入口，本身是单例。 */
    private final ContextManager contextManager = ContextManager.get();
    private final RequestBodyBuilder requestBodyBuilder;
    private final ChatHttpTransport transport = new ChatHttpTransport();

    private ToolExecutor toolExecutor;

    private volatile boolean cancelled = false;
    /**
     * 精简请求模式：跳过系统提示词、上下文预算/记忆注入与工具声明，
     * 并强制关闭 thinking。用于生成标题这类「只要一句话、不需要上下文」的短任务。
     */
    private volatile boolean liteRequest = false;

    /**
     * 最近一次请求的接口地址与请求体，供「测试连接」弹窗展示。
     */
    private volatile String lastEndpoint = "";
    private volatile String lastRequestBody = "";

    public OpenAiEngine(ApiConfigStore config) {
        this.config = config;
        this.requestBodyBuilder = new RequestBodyBuilder(config, contextManager);
    }

    /**
     * 设置工具执行器。传 null 表示这套引擎不参与工具调用（例如生成标题的请求）。
     */
    public void setToolExecutor(ToolExecutor executor) {
        this.toolExecutor = executor;
    }

    /**
     * 开启精简请求模式（默认关闭）。开启后：
     * <ul>
     *   <li>不注入配置里的 system prompt，避免主对话人设干扰短任务；</li>
     *   <li>不走 ContextManager 的预算裁剪 / 记忆召回 / 滚动摘要；</li>
     *   <li>不下发 tools，也不会进入工具续轮；</li>
     *   <li>强制 {@code reasoning_effort=none} 关闭 thinking（对 Ollama 实测有效），
     *       并把 max_tokens 收到 64。</li>
     * </ul>
     */
    public void setLiteRequest(boolean lite) {
        this.liteRequest = lite;
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
                doRequest(history, callback, ToolLoop.MAX_ROUNDS, new ArrayList<ToolCall>());
            }
        });
    }

    @Override
    public void cancel() {
        cancelled = true;
        transport.abort();
    }

    /**
     * 一次完整的流式请求。
     *
     * <p>顺序很重要：先定 tools 是否下发 → 再构造请求体（构造过程会写上下文统计）→
     * 发请求 → 逐行解析并按需回调 → 结束后决定「继续工具轮」还是「收尾」。
     *
     * @param roundsLeft       剩余可自动接续的工具轮数
     * @param carriedToolCalls 之前轮次已执行的工具调用，用于最终一次性展示
     */
    private void doRequest(List<ChatMessage> history, StreamCallback callback,
                           int roundsLeft, List<ToolCall> carriedToolCalls) {
        final StringBuilder answer = new StringBuilder();
        // 思考型模型（reasoning / reasoning_content）的思考过程，单独累积
        final StringBuilder reasoning = new StringBuilder();
        final CappedTextBuffer raw = new CappedTextBuffer(MAX_RAW_CHARS, RAW_TRUNCATED_MARK);
        final String endpoint = resolveEndpoint();
        // 本次流式会话的日志：整理成可读块 + 按批保留原始事件
        final StreamLogger logger = new StreamLogger(endpoint, config.getModel());
        String finishReason = null;
        // trace 埋点：每次 doRequest 一条，工具续轮会各自记一条
        final long traceStartMs = System.currentTimeMillis();
        final List<String> traceToolNames = new ArrayList<>();
        // finally 里要读到可能被改写的局部量，用单元素数组包一层
        final String[] traceError = new String[1];
        final ContextPlan.Stats[] traceStats = new ContextPlan.Stats[1];
        final boolean[] traceCompleted = new boolean[1];
        try {
            boolean switchOn = config.isFunctionCallingEnabled();
            boolean toolsEnabled = switchOn && toolExecutor != null && roundsLeft > 0;
            // 排查用：请求体里没看到 tools 时，看这行就知道是被哪个条件挡住的
            Log.d(LOG_TAG, "工具调用状态：开关=" + switchOn
                    + "，执行器已注入=" + (toolExecutor != null)
                    + "，剩余轮次=" + roundsLeft
                    + "，本次下发 tools=" + toolsEnabled);
            String bodyJson = requestBodyBuilder.build(history, toolsEnabled, liteRequest);
            // 精简请求没走 ContextManager，lastStats() 会是上一次主对话的旧值，置空避免误导
            traceStats[0] = liteRequest ? null : ContextManager.lastStats();
            lastEndpoint = endpoint;
            lastRequestBody = bodyJson;
            Log.d(LOG_TAG, "请求方式：POST，请求地址：" + endpoint);
            Log.d(LOG_TAG, "鉴权头：Bearer " + Texts.maskSecret(config.getApiKey()));
            Log.d(LOG_TAG, "请求体：" + bodyJson);

            ChatHttpTransport.Response response =
                    transport.post(endpoint, config.getApiKey(), bodyJson);
            int code = response.code;
            Log.d(LOG_TAG, "响应状态码：" + code + "，状态信息：" + response.message);
            InputStream stream = response.stream;
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
            // 工具调用分片累积：按 index 拼回完整调用
            final ToolCallAccumulator accumulator = new ToolCallAccumulator();
            while ((line = reader.readLine()) != null) {
                if (cancelled) {
                    traceError[0] = "用户取消";
                    return;
                }
                boolean blank = line.trim().isEmpty();
                raw.appendLine(line);
                // 原始事件交给日志类按批整理；blank 行没有信息量，日志里不出现
                if (!blank) {
                    logger.appendRaw(line);
                }
                if (!SseEventParser.isDataLine(line)) {
                    if (consumeNativeLine(line, logger, answer, reasoning, callback)) {
                        done = true;
                        break;
                    }
                    continue; // 跳过 event:、注释、空行
                }
                String payload = SseEventParser.payloadOf(line);
                if (SseEventParser.isDone(payload)) {
                    done = true;
                    break;
                }
                // 一条事件只解析一次：增量、结束原因、工具分片都从这里取
                SseEvent event = SseEventParser.parse(payload);
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
                accumulator.merge(event);
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
                String serverMessage = JsonUtils.serverMessage(raw.toString());
                notifyError(callback, "HTTP " + code
                        + (serverMessage == null ? "" : " · " + serverMessage), raw.toString());
                return;
            }
            if (answer.length() == 0 && reasoning.length() == 0 && accumulator.size() == 0) {
                notifyError(callback, done ? "响应内容为空" : "流意外中断", raw.toString());
                return;
            }
            if (continueToolLoop(history, accumulator, carriedToolCalls, roundsLeft,
                    callback, raw, traceToolNames)) {
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
            traceCompleted[0] = true;
            notifyComplete(callback, answer.toString(), reasoning.toString(), raw.toString());
        } catch (Exception e) {
            if (!cancelled) {
                String message = e.getMessage() != null ? e.getMessage() : e.toString();
                Log.e(LOG_TAG, "请求异常：" + message, e);
                notifyError(callback, "请求失败：" + message,
                        raw.length() > 0 ? raw.toString() : null);
            }
        } finally {
            transport.release();
            // 任何退出路径（正常结束 / 取消 / 异常 / 工具续轮）都要收尾，
            // 否则最后一批内容块和结束汇总会丢；close 幂等，重复调用无副作用
            logger.close(finishReason, JsonUtils.lastUsage(raw.toString()), raw.toString());
            writeTrace(endpoint, roundsLeft, traceStartMs, traceStats[0],
                    traceToolNames, finishReason, traceCompleted[0], traceError[0]);
        }
    }

    /**
     * 消费一行 Ollama 原生 NDJSON。
     *
     * @return true 表示这是结束行，主循环应退出
     */
    private boolean consumeNativeLine(String line, StreamLogger logger,
                                      StringBuilder answer, StringBuilder reasoning,
                                      StreamCallback callback) {
        String jsonLine = line.trim();
        if (!jsonLine.startsWith("{")) {
            return false;
        }
        SseEventParser.NativeChunk chunk = SseEventParser.parseNative(jsonLine);
        String nativeAnswer = chunk == null ? "" : chunk.answer;
        String nativeReasoning = chunk == null ? "" : chunk.reasoning;
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
        return SseEventParser.isNativeDone(jsonLine);
    }

    /**
     * 模型发起了工具调用时，执行并接续下一轮。
     *
     * @return true 表示本轮已处理完（要么续轮、要么轮次耗尽并已回调错误），
     *         调用方不应再走正常收尾
     */
    private boolean continueToolLoop(List<ChatMessage> history, ToolCallAccumulator accumulator,
                                     List<ToolCall> carriedToolCalls, int roundsLeft,
                                     StreamCallback callback, CappedTextBuffer raw,
                                     List<String> traceToolNames) {
        List<ToolCall> roundCalls = accumulator.build();
        if (roundCalls.isEmpty() || toolExecutor == null) {
            return false;
        }
        ToolLoop.Outcome outcome =
                ToolLoop.run(history, roundCalls, carriedToolCalls, toolExecutor, roundsLeft);
        if (outcome.decision == ToolLoop.Decision.LIMIT) {
            // 轮次用尽仍要调工具：不再循环，明确提示，避免返回一条空消息。
            // 这里必须直接结束，否则会继续回调 onComplete，把错误气泡覆盖成空内容。
            notifyError(callback, "工具调用轮次已达上限（最多 "
                    + ToolLoop.MAX_ROUNDS + " 轮），已停止", raw.toString());
            return true;
        }
        // trace 只记本轮执行过的工具名；续轮会各自记一条 trace，不在这里累加历史轮次
        traceToolNames.addAll(outcome.executedNames);
        notifyToolCalls(callback, outcome.allCalls);
        doRequest(outcome.nextHistory, callback, roundsLeft - 1, outcome.allCalls);
        return true;
    }

    /** 接口地址：baseUrl 去掉尾部斜杠后拼 /chat/completions。 */
    private String resolveEndpoint() {
        return config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
    }

    /**
     * 写一条调用 trace。整段包在 try/catch 里：可观测失败不能影响对话结果。
     */
    private void writeTrace(String endpoint, int roundsLeft, long startMs,
                            ContextPlan.Stats stats, List<String> toolNames,
                            String finishReason, boolean completed, String error) {
        try {
            DoudouApplication app = DoudouApplication.getInstance();
            if (app == null) {
                return;
            }
            boolean success = completed || (!toolNames.isEmpty() && error == null);
            String err = error;
            if (!success && err == null) {
                // 工具续轮与取消之外的失败分支统一标注，便于 eval 按 reason 过滤
                err = finishReason == null ? "未完成" : "finish=" + finishReason;
            }
            TraceEvent event = new TraceEvent(
                    System.currentTimeMillis(), config.getModel(), endpoint, roundsLeft,
                    stats == null ? 0 : stats.systemTokens,
                    stats == null ? 0 : stats.toolTokens,
                    stats == null ? 0 : stats.memoryTokens,
                    stats == null ? 0 : stats.summaryTokens,
                    stats == null ? 0 : stats.historyTokens,
                    stats == null ? 0 : stats.droppedCount,
                    stats == null ? 0 : stats.truncatedCount,
                    stats != null && stats.overflow,
                    toolNames, finishReason, System.currentTimeMillis() - startMs,
                    success, err);
            event.writeTo(app.getApplicationContext());
        } catch (Throwable ignored) {
            // trace 是旁路，任何异常都吞掉
        }
    }

    // ------------------------------------------------------------------
    // 主线程回调
    // ------------------------------------------------------------------

    /** 统一切回主线程；用户已取消就不再打扰界面。 */
    private void postIfActive(final Runnable action) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!cancelled) {
                    action.run();
                }
            }
        });
    }

    private void postStart(final StreamCallback callback) {
        postIfActive(new Runnable() {
            @Override
            public void run() {
                callback.onStart();
            }
        });
    }

    private void notifyToken(final StreamCallback callback, final String fullText,
                             final String thinkingText) {
        postIfActive(new Runnable() {
            @Override
            public void run() {
                callback.onToken(fullText, thinkingText);
            }
        });
    }

    private void notifyComplete(final StreamCallback callback, final String fullText,
                                final String thinkingText, final String raw) {
        postIfActive(new Runnable() {
            @Override
            public void run() {
                callback.onComplete(fullText, thinkingText, raw);
            }
        });
    }

    private void notifyToolCalls(final StreamCallback callback, final List<ToolCall> calls) {
        postIfActive(new Runnable() {
            @Override
            public void run() {
                callback.onToolCall(calls);
            }
        });
    }

    private void notifyError(final StreamCallback callback,
                            final String errorMessage, final String raw) {
        postIfActive(new Runnable() {
            @Override
            public void run() {
                callback.onError(errorMessage, raw);
            }
        });
    }
}
