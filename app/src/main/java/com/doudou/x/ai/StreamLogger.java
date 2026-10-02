package com.doudou.x.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 流式日志：把 SSE / NDJSON 原始行整理成人能读的块。
 *
 * <p>每类信息都用一对符号包住头尾，扫符号就知道是什么：
 * <pre>
 * ★ 会话开始/结束   ♥ 思考块   ♦ 回答块
 * ✦ 工具调用        ■ 结束事件  ▲ 告警
 * ▓ 原始报文（原文）
 * </pre>
 *
 * <p>压缩办法：协议壳（id / created / object…）只在会话头打一次，
 * 连续同类增量合并成块，思考与回答分开成块。原文默认不打印，
 * 要核对报文时把 {@link #rawDump} 置 true。
 *
 * <p>输出经 {@link Sink} 中转，便于在 JVM 上换成内存后端跑验证。
 */
public final class StreamLogger {

    // ------------------------------------------------------------------
    // 输出后端
    // ------------------------------------------------------------------

    /** 日志输出后端（logcat / 文件 / 内存缓冲）。 */
    public interface Sink {
        /**
         * @param level {@link Level} 的序号
         * @param tag   固定宽度的标签，便于各列对齐
         * @param text  一条完整记录（可含换行，表示一个多行块）
         */
        void log(int level, String tag, String text);
    }

    /** 日志级别：与 android.util.Log 取值一致，避免两套常量混淆。 */
    public static final class Level {
        public static final int VERBOSE = 2;
        public static final int DEBUG = 3;
        public static final int INFO = 4;
        public static final int WARN = 5;
        public static final int ERROR = 6;

        private Level() {
        }
    }

    /** 默认后端：logcat。用反射调用，好让本类能在 JVM 上直接跑。 */
    private static final Sink LOGCAT_SINK = new Sink() {
        @Override
        public void log(int level, String tag, String text) {
            try {
                Class<?> log = Class.forName("android.util.Log");
                log.getMethod("println", int.class, String.class, String.class)
                        .invoke(null, level, tag, text);
            } catch (Exception e) {
                // 宿主没有 android.util.Log（JVM 验证脚本）；日志失败不能影响对话
            }
        }
    };

    private static volatile Sink sink = LOGCAT_SINK;

    /** 替换输出后端；传 null 恢复 logcat。 */
    public static void setSink(Sink newSink) {
        sink = newSink == null ? LOGCAT_SINK : newSink;
    }

    public static Sink getSink() {
        return sink;
    }

    /** logcat 过滤用的 tag。 */
    public static final String LOG_TAG = "DoudouX";
    /** 打印用 tag：带尾空格凑 8 字符，logcat 里各列对齐。 */
    private static final String PRINT_TAG = "DoudouX ";

    // ------------------------------------------------------------------
    // 符号标记
    // ------------------------------------------------------------------

    /** 会话开始与结束。 */
    private static final String M_SESSION = "★";
    /** 思考过程块。 */
    private static final String M_THINK = "♥";
    /** 正式回答块。 */
    private static final String M_ANSWER = "♦";
    /** 工具调用分片。 */
    private static final String M_TOOL = "✦";
    /** 结束事件（finish_reason）。 */
    private static final String M_END = "■";
    /** 告警：字段变化、解析失败、未识别字段。 */
    private static final String M_WARN = "▲";
    /** 原始报文。 */
    private static final String M_RAW = "▓";

    /** 用符号把标题包起来，头尾各三个，扫一眼就能定位。 */
    private static String mark(String symbol, String title) {
        return symbol + symbol + symbol + " " + title + " " + symbol + symbol + symbol;
    }

    // ------------------------------------------------------------------
    // 开关与阈值
    // ------------------------------------------------------------------

    /**
     * 原始报文是否输出，默认关闭（日志只剩整理好的块，最干净）。
     * 置 true 后原文在会话结束时一次性输出，不会插在内容块中间把思路割断。
     */
    public static volatile boolean rawDump = false;

    /** true：原文攒到收尾一起打；false：按批边收边打，适合超长流式。 */
    public static volatile boolean RAW_TAIL_MODE = true;

    /** 原文缓冲的字符上限，攒满强制输出一批，避免超长思考撑住内存。 */
    private static final int RAW_BUFFER_LIMIT = 512 * 1024;

    /** 原文用 DEBUG 打印：Verbose 常被厂商默认过滤，反而看不到。 */
    private static final int RAW_LEVEL = Level.DEBUG;

    /** 原文攒够这么多事件打一批。 */
    private static final int BATCH_EVENTS = 30;
    /** 或距本批开始超过这么久打一批（低速率下也有输出）。 */
    private static final long BATCH_MS = 500L;
    /** 单条记录的字符上限，超出按行边界拆成多条，日志尾部不会被截掉。 */
    private static final int CHUNK_CHARS = 3000;

    /** 连续同类增量攒到这么多字符就打印一块。 */
    private static final int BLOCK_CHARS = 120;
    /** 预览/告警的最大长度。 */
    private static final int PREVIEW_CHARS = 120;

    // ------------------------------------------------------------------
    // 状态
    // ------------------------------------------------------------------

    private static final int KIND_NONE = 0;
    private static final int KIND_REASONING = 1;
    private static final int KIND_ANSWER = 2;

    private final String endpoint;
    private final String model;

    private final long startMs = System.currentTimeMillis();
    private int eventCount;
    private int parseFailCount;

    private final StringBuilder rawBatch = new StringBuilder();
    private int rawBatchEvents;
    private long rawBatchStartMs;

    private boolean headerPrinted;
    /** 已告警过的字段，避免告警本身变成新的刷屏源。 */
    private final Set<String> warned = new HashSet<>();
    /** 首事件的元信息取值，作为"这些字段没变"的判断基线。 */
    private final Map<String, String> metaFirst = new HashMap<>();

    private final StringBuilder block = new StringBuilder();
    private int blockKind = KIND_NONE;
    private long blockStartMs;
    private int blockIndex;

    /** 累计字数，比"打了多少条日志"有意义得多。 */
    private int answerChars;
    private int reasoningChars;

    private boolean closed;

    public StreamLogger(String endpoint, String model) {
        this.endpoint = endpoint == null ? "" : endpoint;
        this.model = model == null ? "" : model;
    }

    // ------------------------------------------------------------------
    // 原始报文：按批打印，保留原文
    // ------------------------------------------------------------------

    /**
     * 累积一行原始报文（SSE 的 {@code data: …} 整行，或 NDJSON 整行）。
     * 只在边收边打模式下生效；尾随模式下引擎已留了一份原文，收尾时输出。
     */
    public void appendRaw(String line) {
        if (!rawDump || RAW_TAIL_MODE || line == null || line.trim().isEmpty()) {
            return;
        }
        if (rawBatchEvents == 0) {
            rawBatchStartMs = System.currentTimeMillis();
        }
        rawBatch.append(line).append('\n');
        rawBatchEvents++;
        if (rawBatch.length() >= RAW_BUFFER_LIMIT) {
            flushRaw("缓冲区达上限");
        } else if (rawBatchEvents >= BATCH_EVENTS) {
            flushRaw("攒满 " + BATCH_EVENTS + " 条");
        } else if (System.currentTimeMillis() - rawBatchStartMs >= BATCH_MS) {
            flushRaw("超时");
        }
    }

    /** 把攒下的原文打出来并清空缓冲。 */
    private void flushRaw(String reason) {
        if (!rawDump || rawBatchEvents == 0) {
            return;
        }
        long cost = System.currentTimeMillis() - rawBatchStartMs;
        String head = mark(M_RAW, "原始报文 " + rawBatchEvents + " 条 · " + reason
                + " · 本批 " + cost + "ms · 累计 " + eventCount + " 条");
        String body = rawBatch.toString();
        rawBatch.setLength(0);
        rawBatchEvents = 0;
        printChunked(head, body, mark(M_RAW, "原始报文结束"));
    }

    /**
     * 按上限切分打印，<b>只在行边界切</b>。
     * 按字符硬切会把一条事件断成两半，续接那条以半截 JSON 开头，没法核对。
     */
    private void printChunked(String head, String body, String tail) {
        if (body.length() <= CHUNK_CHARS) {
            sink.log(RAW_LEVEL, PRINT_TAG, "\n" + head + "\n\n" + body + endLine(body, tail));
            return;
        }
        List<String> parts = splitAtLineBreak(body, CHUNK_CHARS);
        for (int i = 0; i < parts.size(); i++) {
            String part = parts.get(i);
            StringBuilder out = new StringBuilder();
            if (i == 0) {
                out.append('\n').append(head).append("\n\n");
            } else {
                out.append("…（原文续，本段 ").append(part.length()).append(" 字符）\n");
            }
            out.append(part);
            if (i == parts.size() - 1) {
                out.append(endLine(part, tail));
            }
            sink.log(RAW_LEVEL, PRINT_TAG, out.toString());
        }
    }

    /** 结尾标记单独占一行；正文已带换行就不再补空行。 */
    private static String endLine(String body, String tail) {
        if (tail == null || tail.isEmpty()) {
            return "";
        }
        return (body.endsWith("\n") ? "" : "\n") + tail;
    }

    /** 按行边界切成不超过 limit 的段；只有单行本身超长才在行内切。 */
    static List<String> splitAtLineBreak(String text, int limit) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            boolean hasNewline = end >= 0;
            if (!hasNewline) {
                end = text.length();
            }
            String line = text.substring(start, end + (hasNewline ? 1 : 0));
            start = end + 1;
            if (line.length() > limit) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < line.length(); i += limit) {
                    parts.add(line.substring(i, Math.min(i + limit, line.length())));
                }
                continue;
            }
            if (current.length() + line.length() > limit) {
                parts.add(current.toString());
                current.setLength(0);
            }
            current.append(line);
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts;
    }

    // ------------------------------------------------------------------
    // 增量：合并成可读块
    // ------------------------------------------------------------------

    /**
     * 登记一条已解析的事件。本方法内部自己解析一次 payload，
     * 用于提取元信息、识别未处理字段，调用方不必重复解析。
     */
    public void onEvent(String payload, boolean sse, String answerDelta, String reasonDelta,
                        String finishReason, int toolCallDeltas) {
        eventCount++;
        JSONObject obj = parse(payload);
        printHeaderOnce(obj, sse);
        checkMetaStable(obj);
        warnUnknownFields(obj);

        // 结束原因与工具调用都要先收尾当前块，避免和下一段增量混在一起
        if (finishReason != null || toolCallDeltas > 0) {
            flushBlock();
        }
        if (finishReason != null) {
            sink.log(Level.INFO, PRINT_TAG,
                    mark(M_END, "结束事件 · finish_reason=" + finishReason));
        }
        if (toolCallDeltas > 0) {
            sink.log(Level.INFO, PRINT_TAG,
                    mark(M_TOOL, "工具调用分片 +" + toolCallDeltas + "（本事件）"));
        }
        if (answerDelta != null && !answerDelta.isEmpty()) {
            appendBlock(KIND_ANSWER, answerDelta);
        }
        if (reasonDelta != null && !reasonDelta.isEmpty()) {
            appendBlock(KIND_REASONING, reasonDelta);
        }
    }

    /** 登记一次解析失败（原文仍在原始报文里，这里只做可检索的告警）。 */
    public void onParseFailure(String line) {
        parseFailCount++;
        sink.log(Level.WARN, PRINT_TAG, mark(M_WARN, "第 " + eventCount
                + " 条事件解析失败，已按原文打印：" + preview(line)));
    }

    private void appendBlock(int kind, String delta) {
        if (blockKind != kind) {
            flushBlock();
            blockKind = kind;
            blockStartMs = System.currentTimeMillis();
        }
        block.append(delta);
        if (block.length() >= BLOCK_CHARS) {
            flushBlock();
        }
    }

    /**
     * 打印并清空当前增量块。
     * 块内保留原始换行，看到的是一段连续文本而不是几百条碎片。
     */
    public void flushBlock() {
        if (blockKind == KIND_NONE || block.length() == 0) {
            blockKind = KIND_NONE;
            block.setLength(0);
            return;
        }
        blockIndex++;
        String text = block.toString();
        block.setLength(0);
        boolean answer = blockKind == KIND_ANSWER;
        blockKind = KIND_NONE;
        long cost = System.currentTimeMillis() - blockStartMs;

        String label = answer ? "回答" : "思考";
        String symbol = answer ? M_ANSWER : M_THINK;
        String head = mark(symbol, label + " 块#" + blockIndex
                + " · " + text.length() + "字"
                + " · " + cost + "ms"
                + " · 累计" + (answer ? answerChars : reasoningChars) + "字");
        printChunked(head, text, mark(symbol, label + " 块#" + blockIndex + " 结束"));
    }

    // ------------------------------------------------------------------
    // 会话头尾
    // ------------------------------------------------------------------

    /**
     * 首事件里提取一次的元信息字段。这些字段并非无条件丢弃：
     * 每个事件都会重新比对，取值一变就告警（见 {@link #checkMetaStable}）。
     */
    private static final String[] META_KEYS = {
            "id", "created", "object", "system_fingerprint", "model",
    };

    /** 首事件的元信息只打印一次，这是"变简单"的主要来源。 */
    private void printHeaderOnce(JSONObject firstEvent, boolean sse) {
        if (headerPrinted) {
            return;
        }
        headerPrinted = true;
        StringBuilder out = new StringBuilder();
        out.append('\n').append(mark(M_SESSION, "流式开始 · "
                + (sse ? "SSE" : "NDJSON")
                + " · " + (model.isEmpty() ? "(模型未指定)" : model)));
        out.append('\n').append("  地址：").append(endpoint);
        if (firstEvent != null) {
            for (String key : META_KEYS) {
                String value = optMetaValue(firstEvent, key);
                if (value == null) {
                    continue;
                }
                metaFirst.put(key, value);
                // model 上面已按配置打印，这里只记基线不重复
                if (!"model".equals(key)) {
                    appendMeta(out, key, value);
                }
            }
        }
        sink.log(Level.INFO, PRINT_TAG, out.toString());
    }

    /**
     * 检查元信息是否与首事件一致。
     * 只对"本事件确实带了这个字段"的情况比对：字段时有时无是正常行为，
     * 但同一字段两次取值不同就是必须让人看见的信息（换 id、换模型）。
     */
    private void checkMetaStable(JSONObject obj) {
        if (obj == null || metaFirst.isEmpty()) {
            return;
        }
        for (String key : META_KEYS) {
            String value = optMetaValue(obj, key);
            if (value == null) {
                continue;
            }
            String first = metaFirst.get(key);
            if (first == null) {
                metaFirst.put(key, value);
                continue;
            }
            if (!first.equals(value) && warned.add("meta:" + key)) {
                sink.log(Level.WARN, PRINT_TAG, mark(M_WARN,
                        "字段 " + key + " 在流中途变化：" + first + " → " + value
                                + "（原文保留在原始响应里）"));
            }
        }
    }

    /** 取元信息字段的字符串值；字段缺失返回 null（与"值为 null"区分开）。 */
    private static String optMetaValue(JSONObject obj, String key) {
        if (!obj.has(key)) {
            return null;
        }
        Object raw = obj.opt(key);
        if (raw == null) {
            return "null";
        }
        return String.valueOf(raw);
    }

    private void appendMeta(StringBuilder out, String key, String value) {
        if (value != null && !value.isEmpty() && !"null".equals(value)) {
            out.append('\n').append("  ").append(key).append("：").append(value);
        }
    }

    /**
     * 会话结束汇总。输出顺序固定：剩余内容块 → 汇总 → 原始报文（尾随模式）。
     *
     * @param finishReason 服务端给出的结束原因，可为 null
     * @param usage        服务端返回的 usage 原文，可为 null
     */
    public void close(String finishReason, String usage) {
        close(finishReason, usage, null);
    }

    /** @param rawBody 完整原始响应，尾随模式下在汇总之后整体输出。 */
    public void close(String finishReason, String usage, String rawBody) {
        if (closed) {
            return;
        }
        closed = true;
        flushBlock(); // 先把剩余内容块打完，保证思考/回答连贯

        long cost = System.currentTimeMillis() - startMs;
        StringBuilder out = new StringBuilder();
        out.append('\n').append(mark(M_SESSION, "流式结束 · " + eventCount + " 事件"
                + " · " + cost + "ms"
                + " · 回答 " + answerChars + " 字"
                + " · 思考 " + reasoningChars + " 字"));
        if (finishReason != null) {
            out.append('\n').append("  finish_reason=").append(finishReason);
        }
        if (parseFailCount > 0) {
            out.append('\n').append("  解析失败 ").append(parseFailCount).append(" 条");
        }
        if (usage != null && !usage.isEmpty()) {
            out.append('\n').append("  usage：").append(usage);
        }
        sink.log(Level.INFO, PRINT_TAG, out.toString());

        // 原始报文放最后：核对时往下翻，不干扰流式期间的阅读
        if (!rawDump) {
            return;
        }
        if (RAW_TAIL_MODE) {
            if (rawBody != null && !rawBody.isEmpty()) {
                printChunked(mark(M_RAW, "完整原始响应 · " + eventCount + " 事件"
                                + " · " + rawBody.length() + " 字符"),
                        rawBody, mark(M_RAW, "原始响应结束"));
            }
        } else {
            flushRaw("收尾");
        }
    }

    /** 登记累计回答字数（引擎拼接时调用，保证汇总数字与界面一致）。 */
    public void countAnswer(int chars) {
        answerChars += chars;
    }

    /** 登记累计思考字数。 */
    public void countReasoning(int chars) {
        reasoningChars += chars;
    }

    // ------------------------------------------------------------------
    // 告警：绝不静默丢弃未识别信息
    // ------------------------------------------------------------------

    /** 对"本类没有专门处理"的字段告警；每种字段每次会话只提醒一次。 */
    private void warnUnknownFields(JSONObject obj) {
        JSONObject delta = firstDelta(obj);
        if (delta == null) {
            return;
        }
        warnOnce(delta, "audio", "语音增量");
        warnOnce(delta, "function_call", "旧版 function_call 字段");
    }

    private void warnOnce(JSONObject delta, String key, String label) {
        if (!delta.has(key) || !warned.add(key)) {
            return;
        }
        sink.log(Level.WARN, PRINT_TAG, mark(M_WARN, "未识别字段 " + key + "（" + label
                + "），已保留在原文中：" + preview(delta.optString(key, ""))));
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static JSONObject firstDelta(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        JSONArray choices = obj.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }
        JSONObject choice = choices.optJSONObject(0);
        return choice == null ? null : choice.optJSONObject("delta");
    }

    /** 宽松解析：允许传整行、带 {@code data:} 前缀、或纯 JSON。 */
    private static JSONObject parse(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("data:")) {
            trimmed = trimmed.substring(5).trim();
        }
        if (!trimmed.startsWith("{")) {
            return null;
        }
        try {
            return new JSONObject(trimmed);
        } catch (Exception e) {
            return null;
        }
    }

    private static String preview(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() > PREVIEW_CHARS
                ? oneLine.substring(0, PREVIEW_CHARS) + "…"
                : oneLine;
    }
}
