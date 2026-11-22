package com.doudou.x.ai.observability;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一次 LLM 调用的可观测记录。
 *
 * <p>字段设计原则：只记「能用来回答 eval 问题」的量——这次给模型塞了多少上下文、
 * 各层占多少、模型决定调什么工具、为什么结束、花了多久、有没有错。
 * 不记消息正文和 API Key，避免把隐私与密钥写进本地文件。
 *
 * <p>落盘格式是 JSONL（一行一条），将来接中间后端时可以直接把这层替换成 HTTP 上报，
 * 上层调用方不需要改。
 */
public final class TraceEvent {

    private static final String LOG_TAG = "DoudouX-Trace";
    private static final String TRACE_DIR = "traces";
    /** 单文件上限 2MB，防止长期运行把存储空间吃满。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;

    public final long timestamp;
    public final String model;
    public final String endpoint;
    public final int roundsLeft;
    /** 上下文各层估算 token；与 ContextPlan.Stats 一一对应。 */
    public final int systemTokens;
    public final int toolTokens;
    public final int memoryTokens;
    public final int summaryTokens;
    public final int historyTokens;
    public final int droppedCount;
    public final int truncatedCount;
    public final boolean overflow;
    /** 本次模型发起的工具名列表，可能为空。 */
    public final List<String> toolNames;
    public final String finishReason;
    public final long latencyMs;
    public final boolean success;
    public final String error;

    public TraceEvent(long timestamp, String model, String endpoint, int roundsLeft,
                      int systemTokens, int toolTokens, int memoryTokens, int summaryTokens,
                      int historyTokens, int droppedCount, int truncatedCount, boolean overflow,
                      List<String> toolNames, String finishReason, long latencyMs,
                      boolean success, String error) {
        this.timestamp = timestamp;
        this.model = model == null ? "" : model;
        this.endpoint = endpoint == null ? "" : endpoint;
        this.roundsLeft = roundsLeft;
        this.systemTokens = systemTokens;
        this.toolTokens = toolTokens;
        this.memoryTokens = memoryTokens;
        this.summaryTokens = summaryTokens;
        this.historyTokens = historyTokens;
        this.droppedCount = droppedCount;
        this.truncatedCount = truncatedCount;
        this.overflow = overflow;
        this.toolNames = toolNames == null ? new ArrayList<String>() : toolNames;
        this.finishReason = finishReason == null ? "" : finishReason;
        this.latencyMs = latencyMs;
        this.success = success;
        this.error = error == null ? "" : error;
    }

    /** 转成 JSONL 的一行。key 均非空，构造失败时返回空对象也不会中断写盘。 */
    public String toJsonLine() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ts", timestamp);
            obj.put("model", model);
            obj.put("endpoint", endpoint);
            obj.put("round", roundsLeft);
            obj.put("sys", systemTokens);
            obj.put("tool", toolTokens);
            obj.put("mem", memoryTokens);
            obj.put("sum", summaryTokens);
            obj.put("hist", historyTokens);
            obj.put("dropped", droppedCount);
            obj.put("truncated", truncatedCount);
            obj.put("overflow", overflow);
            JSONArray calls = new JSONArray();
            for (String name : toolNames) {
                calls.put(name);
            }
            obj.put("calls", calls);
            obj.put("finish", finishReason);
            obj.put("ms", latencyMs);
            obj.put("ok", success);
            if (!error.isEmpty()) {
                obj.put("err", error);
            }
            return obj.toString();
        } catch (Exception ignored) {
            // 字段都是基础类型，不会发生
            return "{}";
        }
    }

    /** 上下文各层 token 之和，与 ContextPlan.Stats.totalTokens() 口径一致。 */
    public int totalContextTokens() {
        return systemTokens + toolTokens + memoryTokens + summaryTokens + historyTokens;
    }

    /**
     * 写入应用私有目录的当日 JSONL 文件（本地离线兜底）。
     *
     * <p>新架构下 trace 由网关在代理链路中统一记录，端侧只保留本地这一份副本
     * 供离线导出；写盘失败只打中文日志、不抛异常，可观测性不能打断对话。
     */
    public boolean writeTo(Context context) {
        if (context == null) {
            return false;
        }
        File dir = new File(context.getFilesDir(), TRACE_DIR);
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(LOG_TAG, "创建 trace 目录失败：" + dir.getAbsolutePath());
                return false;
            }
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
                    .format(new java.util.Date(timestamp));
            File file = new File(dir, "trace-" + day + ".jsonl");
            if (file.length() >= MAX_FILE_BYTES) {
                Log.w(LOG_TAG, "trace 文件已达上限，跳过本次写入：" + file.getName());
                return false;
            }
            BufferedWriter writer = new BufferedWriter(new FileWriter(file, true));
            try {
                writer.write(toJsonLine());
                writer.newLine();
            } finally {
                writer.close();
            }
            return true;
        } catch (Exception e) {
            Log.w(LOG_TAG, "trace 写盘失败（不影响对话）：" + e.getMessage());
            return false;
        }
    }
}
