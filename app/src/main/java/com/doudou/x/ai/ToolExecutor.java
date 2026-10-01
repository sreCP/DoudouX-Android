package com.doudou.x.ai;

/**
 * 工具执行器：引擎拿到模型发起的调用后，交给它执行并拿回结果文本。
 */
public interface ToolExecutor {

    /**
     * @param name          工具名
     * @param argumentsJson 模型给出的参数（JSON 字符串，可能为空或不合法）
     * @return 执行结果文本，会作为 role=tool 消息回传给模型
     */
    String execute(String name, String argumentsJson);
}
