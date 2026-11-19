package com.doudou.x.ai.device;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 一组原生工具：负责提供声明，并按工具名执行。
 *
 * <p>与 MCP 的 provider 不同，这里不引入注册/路由抽象——原生工具数量固定、
 * 全部在编译期可知，保持静态结构即可。每个实现可以暴露多个工具名。
 */
public interface NativeToolSet {

    /** 本组要下发的 function 声明数组；includeWrite=false 时不含写操作工具。 */
    JSONArray definitions(boolean includeWrite);

    /**
     * 执行一个工具。
     *
     * @return 结果文本；返回 null 表示这个名字不属于本组
     */
    String execute(String name, JSONObject args);

    /** 该工具是否为写操作（用于开关过滤与执行前拦截）。 */
    boolean isWriteTool(String name);

    /** 该工具集在当前系统状态下是否可用（例如是否已授予必要权限），默认可用。 */
    default boolean isAvailable() {
        return true;
    }

    /** 不可用时的中文提示，用于日志与执行前拦截；默认无。 */
    default String unavailableMessage() {
        return null;
    }
}
