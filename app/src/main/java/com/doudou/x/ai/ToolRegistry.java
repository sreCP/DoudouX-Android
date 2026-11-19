package com.doudou.x.ai;

import android.content.Context;
import android.util.Log;

import com.doudou.x.DoudouApplication;
import com.doudou.x.ai.device.NativeToolRegistry;
import com.doudou.x.data.UiSettingsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地工具注册表：定义 OpenAI 兼容格式的 tools 声明，并负责执行。
 *
 * <p>内置两类工具：
 * <ul>
 *   <li>只读工具：get_current_time、设备/网络状态、web_search（需配置 Tavily Key）；</li>
 *   <li>写工具：日历新增日程 add_calendar_event（默认不下发，需在设置页打开开关并授予日历权限）。</li>
 * </ul>
 *
 * <p>保持两个静态入口签名不变，引擎与界面侧无需改动。
 */
public final class ToolRegistry {

    private static final String LOG_TAG = "DoudouX-Tool";

    public static final String GET_CURRENT_TIME = "get_current_time";

    private static final String DEFAULT_FORMAT = "yyyy-MM-dd HH:mm:ss EEEE";
    private static final String TIMESTAMP = "timestamp";

    private ToolRegistry() {
    }

    /** 是否允许下发并执行会改动本机数据的原生工具，默认关闭。 */
    private static boolean writeToolsEnabled() {
        Context context = appContext();
        return context != null && UiSettingsStore.getInstance(context).isNativeWriteEnabled();
    }

    private static Context appContext() {
        DoudouApplication app = DoudouApplication.getInstance();
        return app == null ? null : app.getApplicationContext();
    }

    /** 请求体里的 tools[] 声明：内置时间工具 + 原生工具（按写开关与系统权限过滤）。 */
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

        JSONArray nativeTools = NativeToolRegistry.definitions(writeToolsEnabled());
        for (int i = 0; i < nativeTools.length(); i++) {
            tools.put(nativeTools.opt(i));
        }
        Log.d(LOG_TAG, "下发工具数：" + tools.length() + "，写操作=" + writeToolsEnabled());
        return tools;
    }

    /** 执行工具，返回给模型的结果文本（永不抛异常）。 */
    public static String execute(String name, String argumentsJson) {
        if (name == null || name.isEmpty()) {
            return "工具名为空";
        }
        JSONObject args = new JSONObject();
        try {
            if (argumentsJson != null && !argumentsJson.trim().isEmpty()) {
                args = new JSONObject(argumentsJson.trim());
            }
        } catch (Exception ignored) {
            // 参数不合法时按空对象处理，由工具自己报缺少必填项
        }

        if (NativeToolRegistry.isWriteTool(name) && !writeToolsEnabled()) {
            Log.w(LOG_TAG, "写工具被开关拦截：" + name);
            return "工具 " + name + " 会改动本机数据，当前未在设置中开启「允许修改本机数据」，已阻止执行";
        }
        String nativeResult = NativeToolRegistry.execute(name, args);
        if (nativeResult != null) {
            return nativeResult;
        }

        if (!GET_CURRENT_TIME.equals(name)) {
            return "不支持的工具：" + name;
        }
        String format = DEFAULT_FORMAT;
        try {
            String requested = args.optString("format", null);
            if (requested != null && !requested.trim().isEmpty()
                    && !"null".equals(requested.trim())) {
                format = requested.trim();
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
