package com.doudou.x.ai;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地工具注册表：定义 OpenAI 兼容格式的 tools 声明，并负责执行。
 *
 * 目前只内置一个只读工具：get_current_time（获取当前时间）。
 * 只做只读能力是为了避免小模型乱调用造成不可逆后果。
 */
public final class ToolRegistry {

    public static final String GET_CURRENT_TIME = "get_current_time";

    private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss EEEE";
    private static final String TIMESTAMP = "timestamp";

    private ToolRegistry() {
    }

    /** 请求体里的 tools[] 声明。 */
    public static JSONArray toolsJson() {
        JSONArray tools = new JSONArray();
        try {
            JSONObject parameters = new JSONObject();
            parameters.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONObject format = new JSONObject();
            format.put("type", "string");
            format.put("description", "时间格式，Java SimpleDateFormat 语法；"
                    + "默认 yyyy-MM-dd HH:mm:ss EEEE，填 timestamp 返回毫秒时间戳");
            properties.put("format", format);
            parameters.put("properties", properties);
            parameters.put("required", new JSONArray());

            JSONObject function = new JSONObject();
            function.put("name", GET_CURRENT_TIME);
            function.put("description", "获取当前的日期和时间。"
                    + "当用户问「现在几点」「今天几号」「今天星期几」等与时间相关的问题时调用。");
            function.put("parameters", parameters);

            JSONObject tool = new JSONObject();
            tool.put("type", "function");
            tool.put("function", function);
            tools.put(tool);
        } catch (Exception ignored) {
            // JSON key 均非空，不会发生
        }
        return tools;
    }

    /** 执行工具，返回给模型的结果文本（永不抛异常）。 */
    public static String execute(String name, String argumentsJson) {
        if (!GET_CURRENT_TIME.equals(name)) {
            return "不支持的工具：" + name;
        }
        String format = DEFAULT_FORMAT;
        try {
            if (argumentsJson != null && !argumentsJson.trim().isEmpty()) {
                JSONObject args = new JSONObject(argumentsJson.trim());
                String requested = args.optString("format", null);
                if (requested != null && !requested.trim().isEmpty()
                        && !"null".equals(requested.trim())) {
                    format = requested.trim();
                }
            }
        } catch (Exception ignored) {
            // 参数不合法就用默认格式
        }
        try {
            if (TIMESTAMP.equalsIgnoreCase(format)) {
                return String.valueOf(System.currentTimeMillis());
            }
            return new SimpleDateFormat(format, Locale.CHINA).format(new Date());
        } catch (Exception e) {
            return new SimpleDateFormat(DEFAULT_FORMAT, Locale.CHINA).format(new Date());
        }
    }
}
