package com.doudou.x.eval;

import android.content.Context;
import android.util.Log;

import com.doudou.x.ai.observability.TraceEvent;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 {@link TraceEvent} 写成应用私有目录里的 JSONL 文件，按天分片。
 *
 * <p>这是「中间后端」的最小替身：现在写本地文件，将来换成 HTTP 上报时
 * 只需替换 {@link #write} 一个方法。写盘失败一律吞掉并打中文日志，
 * 因为可观测性不能反过来打断对话。
 */
public final class JsonlExporter {

    private static final String LOG_TAG = "DoudouX-Eval";

    private JsonlExporter() {
    }

    /** 写入一条 trace；实际落盘逻辑在 TraceEvent 内，便于核心层直接调用。 */
    public static boolean write(Context context, TraceEvent event) {
        return event != null && event.writeTo(context);
    }

    /** 读取全部 trace 文件里的行，供导出或后续 eval 消费。 */
    public static List<String> readAll(Context context) {
        List<String> lines = new ArrayList<>();
        if (context == null) {
            return lines;
        }
        File dir = new File(context.getFilesDir(), "traces");
        File[] files = dir.listFiles();
        if (files == null) {
            return lines;
        }
        for (File file : files) {
            if (!file.getName().endsWith(".jsonl")) {
                continue;
            }
            java.io.BufferedReader reader = null;
            try {
                reader = new java.io.BufferedReader(new java.io.FileReader(file));
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lines.add(line);
                    }
                }
            } catch (Exception e) {
                Log.w(LOG_TAG, "读取 trace 失败：" + file.getName() + "，" + e.getMessage());
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Exception ignored) {
                        // 忽略
                    }
                }
            }
        }
        return lines;
    }

}
