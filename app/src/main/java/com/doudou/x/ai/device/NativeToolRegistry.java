package com.doudou.x.ai.device;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 原生工具集合的静态聚合点。
 *
 * <p>原生工具与 MCP 工具的区别：数量固定、编译期可知、没有网络握手，
 * 所以不需要 provider 注册与路由表，直接在这里列全即可。
 * 写操作的开关由上层 {@code ToolRegistry} 统一判断；
 * 系统权限这类「工具集是否可用」的检查在这里统一做。
 */
public final class NativeToolRegistry {

    private static final String LOG_TAG = "DoudouX-Native";

    private static final List<NativeToolSet> SETS = new ArrayList<>();

    static {
        SETS.add(new CalendarTool());
        SETS.add(new DeviceTool());
        SETS.add(new WebSearchTool());
    }

    private NativeToolRegistry() {
    }

    /** 汇总所有原生工具的 function 声明；不可用的工具集直接跳过。 */
    public static JSONArray definitions(boolean includeWrite) {
        JSONArray all = new JSONArray();
        for (NativeToolSet set : SETS) {
            if (!set.isAvailable()) {
                String message = set.unavailableMessage();
                Log.w(LOG_TAG, "原生工具集不可用，已跳过：" + set.getClass().getSimpleName()
                        + (message == null ? "" : "，" + message));
                continue;
            }
            JSONArray part = set.definitions(includeWrite);
            if (part == null) {
                continue;
            }
            for (int i = 0; i < part.length(); i++) {
                all.put(part.opt(i));
            }
        }
        return all;
    }

    /**
     * 按工具名分发执行。
     *
     * @return 结果文本；返回 null 表示这个名字不属于任何原生工具
     */
    public static String execute(String name, JSONObject args) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        // 写工具在执行前再确认一次系统权限，防止旧会话或缓存声明带来的越权调用
        for (NativeToolSet set : SETS) {
            if (set.isWriteTool(name) && !set.isAvailable()) {
                String message = set.unavailableMessage();
                return message == null ? "工具 " + name + " 当前不可用" : message;
            }
        }
        for (NativeToolSet set : SETS) {
            String result = set.execute(name, args);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    /** 判断一个工具是否为写操作，供上层在执行前按开关拦截。 */
    public static boolean isWriteTool(String name) {
        for (NativeToolSet set : SETS) {
            if (set.isWriteTool(name)) {
                return true;
            }
        }
        return false;
    }
}
