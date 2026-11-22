package com.doudou.x.util;

import org.json.JSONObject;

/**
 * JSON 取值容错工具。
 *
 * <p>Android 的 {@code org.json} 有两个坑：{@code optString} 在字段是 JSON null 时
 * 返回字面量 {@code "null"} 而不是默认值；流式响应里 {@code data:} 前缀与多行拼接
 * 也常让调用方重复写同一段剥离逻辑。这里统一收口。
 */
public final class JsonUtils {

    /** 与 {@code data:} 前缀一起出现的字面量 null。 */
    private static final String LITERAL_NULL = "null";

    private JsonUtils() {
    }

    /** 取字符串字段，把 JSON null 与字段缺失统一成空串。 */
    public static String optText(JSONObject obj, String key) {
        if (obj == null) {
            return "";
        }
        String value = obj.optString(key, null);
        return isLiteralNull(value) ? "" : value;
    }

    /** 同上，但不存在时返回默认值。 */
    public static String optText(JSONObject obj, String key, String fallback) {
        String value = optText(obj, key);
        return value.isEmpty() ? fallback : value;
    }

    /** 判掉 null 与字符串 "null"（org.json 的坑）。 */
    public static boolean isLiteralNull(String value) {
        return value == null || LITERAL_NULL.equals(value);
    }

    /**
     * 从原始流式响应里提取最后一个 usage 对象，供日志汇总展示。
     *
     * <p>流式响应里 usage 通常只在最后一条事件出现（部分服务端需要
     * {@code stream_options.include_usage}），所以从后往前找第一个即为最近的那个。
     *
     * @return usage 的 JSON 文本；没有则返回 null，不占日志
     */
    public static String lastUsage(String rawBody) {
        if (Texts.isBlank(rawBody)) {
            return null;
        }
        String[] lines = rawBody.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String payload = stripDataPrefix(lines[i].trim());
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

    /**
     * 从错误响应里提取 message，便于气泡直接显示原因。
     *
     * <p>兼容两种形状：顶层 {@code {"message":"..."}} 与
     * OpenAI 的 {@code {"error":{"message":"..."}}}。
     *
     * @return 服务端给出的错误描述；解析不出来返回 null
     */
    public static String serverMessage(String rawBody) {
        if (Texts.isBlank(rawBody)) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(stripDataPrefix(rawBody.trim()));
            String message = obj.optString("message", null);
            if (isLiteralNull(message)) {
                JSONObject error = obj.optJSONObject("error");
                message = error == null ? null : error.optString("message", null);
            }
            return isLiteralNull(message) ? null : message;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 去掉 SSE 的 {@code data:} 前缀（没有前缀则原样返回）。 */
    private static String stripDataPrefix(String text) {
        if (text != null && text.startsWith("data:")) {
            return text.substring(5).trim();
        }
        return text == null ? "" : text;
    }
}
