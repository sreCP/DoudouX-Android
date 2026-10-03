package com.doudou.x.ai;

/**
 * ========== 本文件在干什么 ==========
 *
 * 这是整个项目最核心的一个类：真正发起网络请求、解析 SSE 流式响应、
 * 并且在模型要求调用工具时把「执行工具 → 回填结果 → 再发一次请求」这个循环跑起来。
 *
 * 一句话概括职责：
 *   把「一串聊天消息」交给远端大模型，把模型吐回来的增量文本一段一段回调给界面，
 *   期间如果模型说「我要调用工具」，就本地执行工具再把结果喂回去，直到模型给出最终回答。
 *
 * ========== 支持哪几种服务端 ==========
 *
 * 1) OpenAI 兼容接口（默认路径，/v1/chat/completions）
 *    响应是标准 SSE：每行 "data: {...}"，以 "data: [DONE]" 结束。
 *    代表：OpenAI 官方、各类中转、vLLM / One-API 等网关。
 *
 * 2) Ollama 原生接口（/api/chat、/api/generate）
 *    响应是 NDJSON：一行一个完整 JSON 对象，没有 "data:" 前缀，用 done=true 表示结束。
 *    （注意：Ollama 现在也提供 OpenAI 兼容端口，走的是分支 1；
 *      这里保留原生解析是为了用户直接填 /api/chat 时也能用。）
 *
 * 两种格式在本类的 while 循环里分别处理，互不干扰。
 *
 * ========== 线程模型（很重要） ==========
 *
 *   [调用方线程 / 主线程]  streamReply(...)
 *             │
 *             └─ executor（单线程线程池）──► doRequest(...)
 *                                              │  这里全程都在子线程：
 *                                              │  组包、发请求、读流、解析、执行工具
 *                                              │
 *                                              └─ 需要通知界面时 → mainHandler.post(...)
 *                                                                    └─ 切回主线程跑回调
 *
 * 所以：本类里除了几个 notifyXxx / postXxx 方法内部，其余代码都运行在子线程，
 * 绝对不能在这里直接操作 View。
 *
 * ========== 一次请求可能跑好几轮 ==========
 *
 * doRequest 是递归的。模型如果返回 tool_calls：
 *   本轮结束 → 本地执行工具 → 把 assistant(带 tool_calls) 和 role=tool 消息追加进历史
 *            → 用 roundsLeft-1 再调一次 doRequest
 * 最多递归 MAX_TOOL_ROUNDS(3) 轮，防止模型陷入死循环一直调工具。
 */
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

    /**
     * 建立 TCP / TLS 连接的超时。
     * <p>
     * 30 秒足够判断「地址填错了 / 网络不通 / 端口没开」。
     * 注意这个超时只覆盖「连上之前」，连上之后的读取由 READ_TIMEOUT_MS 控制。
     */
    private static final int CONNECT_TIMEOUT_MS = 30000;
    /**
     * 流式读取超时：0 表示不限制。
     * <p>
     * 思考型模型（reasoning）在正式回答前可能长时间只吐思考内容、甚至静默很久，
     * 之前固定 60s 会在思考还没结束时就被判成超时，这里改成不限制；
     * 不想等了用界面上的「停止」按钮主动断开即可。
     * <p>
     * 【不要改回固定值】曾经因为这个值导致长思考被误判超时，详见交接文档 5.7。
     */
    private static final int READ_TIMEOUT_MS = 0;
    /**
     * Function Calling 最多自动接续的轮数，防止模型无限调用工具。
     * <p>
     * 计数方式是「剩余轮数」：初始传入 MAX_TOOL_ROUNDS，每续一轮减 1，
     * 减到 0 时如果模型还要调工具，就明确报错而不是继续循环。
     */
    private static final int MAX_TOOL_ROUNDS = 3;
    /**
     * 原始响应最多保留的字符数，避免超长思考把内存撑爆。
     * <p>
     * 1MB 是字符数不是字节数。原始响应用来给「长按查看原始返回」展示，
     * 超长思考过程（几万 token）很容易突破几十 MB，所以必须设上限。
     */
    private static final int MAX_RAW_CHARS = 1024 * 1024;

    /** 接口配置：baseUrl / apiKey / model / 各项开关与模型参数。 */
    private final ApiConfigStore config;
    /** 主线程 Handler：所有回调都必须通过它切回主线程再执行。 */
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * 单线程池：保证同一时刻只有一个请求在跑，也保证 doRequest 的递归调用串行执行，
     * 不会因为并发把 activeConnection 或 cancelled 状态搞乱。
     */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /**
     * 工具执行器。为 null 表示这套引擎禁用 Function Calling。
     * <p>
     * 主对话的引擎会注入；生成标题用的 titleEngine 不注入（小模型容易乱调用工具）。
     */
    private ToolExecutor toolExecutor;
    /** 上下文预算与滚动压缩的入口，本身是单例。 */
    private final ContextManager contextManager = ContextManager.get();

    /**
     * 取消标记。volatile 是因为写它的是主线程（用户点「停止」），
     * 读它的是 executor 子线程，必须保证跨线程可见。
     */
    private volatile boolean cancelled = false;
    /** 当前正在进行的连接，取消时需要拿它 disconnect()。同样跨线程访问，用 volatile。 */
    private volatile HttpURLConnection activeConnection;

    /**
     * 最近一次请求的接口地址与请求体，供「测试连接」弹窗展示。
     * <p>
     * 这两个字段是给 UI 读的，子线程写、主线程读，所以用 volatile。
     */
    private volatile String lastEndpoint = "";
    private volatile String lastRequestBody = "";

    /**
     * @param config 接口配置。注意传入的是单例对象，用户在设置页改配置后，
     *               本类下一次发请求时读到的就是新值，不需要重建引擎。
     */
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
     * <p>
     * 设置页的「测试连接」走这里：能拿到回复就说明 baseUrl / key / model 三者对得上。
     */
    public void testConnection(StreamCallback callback) {
        List<ChatMessage> test = new ArrayList<>();
        test.add(new ChatMessage(ChatMessage.ROLE_USER, "hi"));
        streamReply(test, callback);
    }

    /**
     * 对外的流式问答入口。
     *
     * @param history  完整对话历史（服务端不保存上下文，每次都由客户端回传）
     * @param callback 回调，全部在主线程触发
     */
    @Override
    public void streamReply(final List<ChatMessage> history, final StreamCallback callback) {
        // 先取消上一次未完成的请求：本类只有一个连接槽，新请求必须挤掉旧的
        cancel();
        // 再复位标记，否则上一次的取消状态会让本次请求刚发出就被判为已取消
        cancelled = false;
        executor.execute(new Runnable() {
            @Override
            public void run() {
                // 第一次进入时剩余轮数是满的，已执行的工具调用列表是空的
                doRequest(history, callback, MAX_TOOL_ROUNDS, new ArrayList<ToolCall>());
            }
        });
    }

    /**
     * 取消当前请求。
     * <p>
     * 两件事都要做：置标记（让循环里主动退出）+ 断连接（让阻塞在 readLine 的线程立刻抛异常醒来）。
     * 只做其中一个都不够——只置标记会卡在读流上，只断连接会在下一轮递归里重新开始请求。
     */
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
     * 真正的一次 HTTP 往返 + 流式解析 + 可能的工具续轮。
     * <p>
     * 这个方法跑在 executor 子线程上，且可能是递归调用（工具续轮）的其中一层。
     *
     * @param history           本轮要发送的完整历史（续轮时会带上工具结果）
     * @param callback          回调，始终是最外层那一个，不会变
     * @param roundsLeft        剩余可自动接续的工具轮数
     * @param carriedToolCalls  之前轮次已执行的工具调用，用于最终一次性展示
     */
    private void doRequest(List<ChatMessage> history, StreamCallback callback,
                           int roundsLeft, List<ToolCall> carriedToolCalls) {
        // ---- 三个累积器：整个流的解析结果都往这三个 StringBuilder 里堆 ----
        /** 原始响应全文（含所有 data 行），用于「查看原始返回」与错误排查，有 1MB 上限。 */
        final StringBuilder raw = new StringBuilder();
        /** 正式回答全文。每次回调都传全文而不是增量，界面直接 setText 即可。 */
        final StringBuilder answer = new StringBuilder();
        // 思考型模型（reasoning / reasoning_content）的思考过程，单独累积
        final StringBuilder reasoning = new StringBuilder();

        // 拼 endpoint：把 baseUrl 末尾多余的斜杠去掉，再接上 /chat/completions。
        // 用户填 "http://host/v1/" 或 "http://host/v1" 都能拼对。
        final String endpoint =
                config.getBaseUrl().replaceAll("/+$", "") + "/chat/completions";
        // 本次流式会话的日志：整理成可读块 + 按批保留原始事件
        final StreamLogger logger = new StreamLogger(endpoint, config.getModel());
        /** 服务端返回的结束原因：stop(正常) / length(被截断) / tool_calls(要调工具)。 */
        String finishReason = null;
        try {
            // ============ 1. 建立连接 ============
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            // 记下来，cancel() 时要靠它断开
            activeConnection = conn;
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true); // 需要写请求体
            conn.setRequestProperty("Content-Type", "application/json");
            // 告诉服务端我要 SSE；部分网关据此决定分块传输
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Authorization", "Bearer " + config.getApiKey());

            // ============ 2. 判断是否下发 tools ============
            // 三道闸门，任一不满足就不带 tools（详见交接文档第 4 节）
            boolean switchOn = config.isFunctionCallingEnabled();          // 设置页开关，默认 false
            boolean toolsEnabled = switchOn && toolExecutor != null && roundsLeft > 0;
            // 排查用：请求体里没看到 tools 时，看这行就知道是被哪个条件挡住的
            Log.d(LOG_TAG, "工具调用状态：开关=" + switchOn
                    + "，执行器已注入=" + (toolExecutor != null)
                    + "，剩余轮次=" + roundsLeft
                    + "，本次下发 tools=" + toolsEnabled);

            // ============ 3. 组装并发送请求体 ============
            String bodyJson = buildRequestBody(history, toolsEnabled);
            // 记录起来给「测试连接」弹窗看
            lastEndpoint = endpoint;
            lastRequestBody = bodyJson;
            Log.d(LOG_TAG, "请求方式：POST，请求地址：" + endpoint);
            // 密钥不能明文进日志，只显示首尾
            Log.d(LOG_TAG, "鉴权头：Bearer " + maskKey(config.getApiKey()));
            Log.d(LOG_TAG, "请求体：" + bodyJson);

            byte[] body = bodyJson.getBytes(StandardCharsets.UTF_8);
            OutputStream os = conn.getOutputStream();
            os.write(body);
            os.flush();
            os.close();

            // ============ 4. 拿状态码，选对输入流 ============
            // 注意：getResponseCode() 一旦调用，连接就真的发出去了，之后不能再改请求头
            int code = conn.getResponseCode();
            Log.d(LOG_TAG, "响应状态码：" + code + "，状态信息：" + conn.getResponseMessage());
            // 2xx 走 getInputStream，非 2xx 必须走 getErrorStream 才能读到服务端错误信息
            InputStream stream = code >= 200 && code < 300
                    ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                Log.e(LOG_TAG, "响应状态码：" + code + "，响应体为空");
                notifyError(callback, "HTTP " + code + "，无响应体", raw.toString());
                return;
            }

            // ============ 5. 通知界面「开始输出了」 ============
            postStart(callback);

            // ============ 6. 逐行读流并解析 ============
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            String line;
            /** 是否收到了明确的结束信号（[DONE] 或 Ollama 的 done=true）。 */
            boolean done = false;
            // 工具调用分片累积：index → 调用
            // 用 SparseArray 而不是 HashMap，key 是 int 的场景下更省内存
            final SparseArray<ToolCallBuilder> toolBuilders = new SparseArray<>();
            while ((line = reader.readLine()) != null) {
                // 用户点了停止：直接静默返回，不回调任何东西
                if (cancelled) {
                    return;
                }
                boolean blank = line.trim().isEmpty();
                appendRaw(raw, line);
                // 原始事件交给日志类按批整理；blank 行没有信息量，日志里不出现
                if (!blank) {
                    logger.appendRaw(line);
                }

                // ---- 分支 A：不是 "data:" 开头，尝试按 Ollama 原生 NDJSON 解析 ----
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
                        // 只有真的有新内容才回调，避免每读一行（哪怕只是心跳注释）都刷一次界面
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

                // ---- 分支 B：标准 SSE，payload 是 "data:" 后面的 JSON ----
                String payload = line.substring(5).trim();
                if ("[DONE]".equals(payload)) {
                    done = true;
                    break;
                }
                // 一条事件只解析一次：增量、结束原因、工具分片都从这里取
                ParsedEvent event = parseEvent(payload);
                if (event == null) {
                    // 解析不出 choices 的事件（例如只有 usage 的收尾包）：
                    // 登记进日志但不算失败，继续等后面的行
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
                // 工具调用是分片流式下发的，这里把分片并进累积器
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

            // ============ 7. 收尾判定：四种结局 ============
            if (code < 200 || code >= 300) {
                // 结局一：HTTP 层失败。把服务端的 message 抠出来直接显示，
                // 例如「model does not exist」「Incorrect API key provided」
                Log.e(LOG_TAG, "请求失败，响应状态码：" + code + "，响应体：" + raw);
                // 直接把服务端的 message 带出来，例如「model does not exist」
                String serverMessage = extractServerMessage(raw.toString());
                notifyError(callback, "HTTP " + code
                        + (serverMessage == null ? "" : " · " + serverMessage), raw.toString());
            } else if (answer.length() == 0 && reasoning.length() == 0
                    && toolBuilders.size() == 0) {
                // 结局二：HTTP 是 200，但一个字都没收到。
                // 区分两种：收到 [DONE] 说明服务端自己认为结束了（内容为空）；
                // 没收到 [DONE] 说明连接中途断了（流意外中断）。
                notifyError(callback, done ? "响应内容为空" : "流意外中断", raw.toString());
            } else {
                // 结局三 / 四：有内容。先看是不是要调工具。
                // 模型发起了工具调用：本地执行后把结果回传，再自动发起下一轮
                List<ToolCall> roundCalls = buildToolCalls(toolBuilders);
                if (!roundCalls.isEmpty() && toolExecutor != null && roundsLeft <= 0) {
                    // 轮次用尽仍要调工具：不再循环，明确提示，避免返回一条空消息
                    Log.w(LOG_TAG, "工具调用轮次已达上限（" + MAX_TOOL_ROUNDS + "轮），停止调用");
                    notifyError(callback, "工具调用轮次已达上限（最多 "
                            + MAX_TOOL_ROUNDS + " 轮），已停止", raw.toString());
                } else if (!roundCalls.isEmpty() && toolExecutor != null) {
                    // ---- 结局三：本轮要调工具，本地执行 ----
                    for (ToolCall call : roundCalls) {
                        // 工具全部是只读的（当前只有 get_current_time），可以放心同步执行
                        call.setResult(toolExecutor.execute(call.getName(), call.getArguments()));
                        Log.d(LOG_TAG, "工具调用：" + call.getName()
                                + "，参数：" + call.getArguments()
                                + "，结果：" + call.getResult());
                    }
                    // 累计展示：把之前轮次的调用也带上，界面一次性画全
                    List<ToolCall> allCalls = new ArrayList<>(carriedToolCalls);
                    allCalls.addAll(roundCalls);
                    notifyToolCalls(callback, allCalls);

                    // ---- 组装下一轮的历史，遵循 OpenAI 的消息协议 ----
                    // assistant 消息带 tool_calls（内容为空串）
                    List<ChatMessage> nextHistory = new ArrayList<>(history);
                    ChatMessage assistantCall = new ChatMessage(ChatMessage.ROLE_AI, "");
                    assistantCall.setToolCalls(roundCalls);
                    nextHistory.add(assistantCall);
                    // 每个工具调用后面跟一条 role=tool 的结果消息，用 tool_call_id 关联
                    for (ToolCall call : roundCalls) {
                        ChatMessage toolMessage =
                                new ChatMessage(ChatMessage.ROLE_TOOL, call.getResult());
                        toolMessage.setToolCallId(call.getId());
                        nextHistory.add(toolMessage);
                    }
                    // 递归下一轮。注意 return：本轮不再走 notifyComplete，
                    // 最终回答由最后一次没有工具调用的那一轮发出。
                    doRequest(nextHistory, callback, roundsLeft - 1, allCalls);
                    return;
                }
                // ---- 结局四：没有工具调用，本轮就是最终回答 ----
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
            // 主动取消时 disconnect() 会让读流抛异常，这是预期内的，不回调错误
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

    /**
     * 解析一条 SSE 事件正文（"data:" 后面的那段 JSON）。
     *
     * @param payload 事件正文
     * @return 解析结果；非 JSON、没有 choices、或 choices[0] 不是对象时返回 null
     *         （返回 null 只表示「这条不是内容事件」，不代表出错，调用方会 continue）
     */
    private ParsedEvent parseEvent(String payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        JSONObject obj;
        try {
            obj = new JSONObject(payload);
        } catch (Exception e) {
            // 流被截断时最后一行常常是半个 JSON，静默忽略即可
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
        // finish_reason 只在最后一条内容事件里出现；中间那些事件里它是 JSON null。
        // org.json 的 optString 遇到 JSON null 会返回字符串 "null"，所以要额外判断。
        String reason = choice.optString("finish_reason", null);
        if (reason != null && !reason.isEmpty() && !"null".equals(reason)) {
            event.finishReason = reason;
        }
        // 流式响应的内容在 delta 里；非流式才会放在 message 里，这里只处理流式
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

    /**
     * 取字符串字段，把 JSON null 与字段缺失统一成空串。
     * <p>
     * org.json 的坑：optString(key, null) 在字段值是 JSON null 时会返回字符串 "null"
     * 而不是我们传的默认值，所以这里必须手动兜一层。
     */
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
        // 倒着扫：usage 一般在最后，倒着找能一次命中
        for (int i = lines.length - 1; i >= 0; i--) {
            String payload = lines[i].trim();
            if (payload.startsWith("data:")) {
                payload = payload.substring(5).trim();
            }
            // 先用字符串包含做一次廉价过滤，避免每行都构造 JSONObject（很贵）
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
        // 刚好越过上限这一次补一句截断提示，之后本方法第一行就 return 了
        if (raw.length() >= MAX_RAW_CHARS) {
            raw.append("…（原始响应过长，已截断）\n");
        }
    }

    /**
     * 尝试从错误响应里提取 message，便于气泡直接显示原因。
     * <p>
     * 两种常见结构都兼容：
     * <pre>
     * {"message": "..."}                      多数网关
     * {"error": {"message": "..."}}           OpenAI 官方
     * </pre>
     *
     * @return 错误信息；取不到返回 null
     */
    private String extractServerMessage(String rawBody) {
        if (rawBody == null || rawBody.isEmpty()) {
            return null;
        }
        String text = rawBody.trim();
        // 少数服务端把错误也用 SSE 包了一层 "data: {...}"
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
            // 错误响应可能是 HTML（比如网关的 502 页面），解析不了就算了
            return null;
        }
    }

    /**
     * 只显示 key 首尾，避免完整密钥进日志。
     * <p>
     * 短于 12 位的直接全遮，否则「前 6 后 4」还能保留一点辨识度方便核对配置。
     */
    private String maskKey(String key) {
        if (key == null || key.length() <= 12) {
            return "***";
        }
        return key.substring(0, 6) + "***" + key.substring(key.length() - 4);
    }

    /**
     * 构造 OpenAI 兼容请求体（默认带完整多轮上下文）。
     * <p>
     * 组装顺序：messages → model/stream → 可选模型参数 → tools。
     * 其中 messages 里已经包含 system prompt，由 {@link ContextManager} 统一处理。
     *
     * @param history    完整历史
     * @param withTools  是否下发工具声明（由调用方按三道闸门算好传进来）
     * @return 请求体 JSON 字符串
     */
    private String buildRequestBody(List<ChatMessage> history, boolean withTools) {
        try {
            // 服务端不保存会话：完整历史由客户端每次回传；
            // 关闭该开关时只发送当前这一句，用于省流或单轮问答场景
            List<ChatMessage> toSend = history;
            if (!config.isSendFullHistory() && history != null) {
                // 从后往前找最近一条用户消息
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
                // auto = 模型自己决定；不写 tool_choice 时多数服务端默认也是 auto
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
     * <p>
     * 为什么必须按 index 归并：模型可能并行发起多个工具调用，
     * 分片是交错下发的（第 0 个的 name、第 1 个的 name、第 0 个的 arguments…），
     * 只有 index 能把它们各自归到正确的槽位。
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
            // index 缺省时退化为数组下标，保证至少不会丢
            int index = call.optInt("index", i);
            ToolCallBuilder builder = builders.get(index);
            if (builder == null) {
                builder = new ToolCallBuilder();
                builders.put(index, builder);
            }
            // id 只在第一个分片出现，后面都是空串，所以「非空才覆盖」
            String id = call.optString("id", null);
            if (id != null && !id.isEmpty() && !"null".equals(id)) {
                builder.id = id;
            }
            JSONObject function = call.optJSONObject("function");
            if (function == null) {
                continue;
            }
            // name 理论上一次给全，但保险起见也按分片追加
            String name = function.optString("name", null);
            if (name != null && !name.isEmpty() && !"null".equals(name)) {
                builder.name.append(name);
            }
            // arguments 是真正的流式重点：JSON 被切成十几个碎片逐个下发
            String arguments = function.optString("arguments", null);
            if (arguments != null && !arguments.isEmpty() && !"null".equals(arguments)) {
                builder.arguments.append(arguments);
            }
        }
    }

    /**
     * 把分片累积结果整理成工具调用列表。
     * <p>
     * 过滤规则：没有 name 的调用直接丢弃——只有 arguments 碎片而没有函数名，
     * 说明这个调用没拼完整，拿去执行必然失败。
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
            // 服务端没给 id 时造一个，因为回传 role=tool 消息必须带 tool_call_id
            call.setId(id == null || id.isEmpty() ? "call_" + i : id);
            call.setName(builder.name.toString());
            call.setArguments(builder.arguments.toString());
            calls.add(call);
        }
        return calls;
    }

    /**
     * tool_calls 分片累积器。
     * <p>
     * id 用普通 String（整体覆盖），name / arguments 用 StringBuilder（逐片追加）。
     */
    private static class ToolCallBuilder {
        String id;
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();
    }

    /**
     * 解析 Ollama 原生接口一行 NDJSON（/api/chat 的 message.content、
     * /api/generate 的 response），返回 [回答, 思考过程]；不是这类数据返回 null。
     * <p>
     * 字段查找顺序：message.content → response（/api/generate 格式）；
     * thinking 同理先看 message 里再看顶层。
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
            // 两个都没有说明这行不是内容行（可能是 done 收尾包）
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
     * Ollama 原生接口用 done=true 标记结束（等价于 SSE 的 [DONE]）。
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
    //
    // 下面五个方法一模一样的套路：mainHandler.post 切回主线程 + 检查 !cancelled。
    // 每个方法都带 cancelled 检查，是因为 post 是异步的——排队期间用户可能已经点了停止，
    // 这时回调就绝对不能再触发，否则界面会显示一条本应被丢弃的内容。
    // ------------------------------------------------------------------

    /** 通知界面：连接已建立，马上要开始输出了（可以显示"正在输入"之类）。 */
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

    /**
     * 通知界面：有新的增量内容。
     *
     * @param fullText      到目前为止的【完整】回答，不是增量。
     *                      传全文是为了让界面直接 setText，省掉自己拼接的麻烦。
     * @param thinkingText  到目前为止的完整思考过程，可能为 null
     */
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

    /**
     * 通知界面：这一轮彻底结束了，没有更多内容。
     *
     * @param raw 原始响应全文，供长按查看
     */
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

    /**
     * 通知界面：模型调用了工具，且工具已经在本机执行完毕。
     * <p>
     * 传的是【累计】列表（含之前轮次的调用），界面一次性把工具卡片画全。
     */
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

    /**
     * 通知界面：出错了。
     *
     * @param errorMessage 可直接展示给用户的中文错误描述
     * @param raw          原始响应（可能为 null，例如连接都没建立成功时）
     */
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
