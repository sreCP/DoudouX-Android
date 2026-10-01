package com.doudou.x.ai;

import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import java.util.List;

/**
 * AI 对话引擎统一抽象：模拟引擎与真实 API 引擎实现同一接口，
 * 上层（MainActivity）按配置切换，无需关心实现细节。
 */
public interface AiEngine {

    interface StreamCallback {
        /** 第一个 token 到来前调用（"思考中"阶段结束）。 */
        void onStart();

        /**
         * 流式增量。
         *
         * @param fullText     到目前为止累计的正式回答
         * @param thinkingText 到目前为止累计的思考过程（可能为 null / 空）
         */
        void onToken(String fullText, String thinkingText);

        /**
         * 正常结束。
         *
         * @param fullText     完整回答文本
         * @param thinkingText 完整思考过程（可能为 null / 空）
         * @param rawResponse  API 原始返回字符串（模拟引擎为 null）
         */
        void onComplete(String fullText, String thinkingText, String rawResponse);

        /**
         * 模型发起了工具调用，并且已经在本地执行完成。
         * 引擎会自动带上结果发起下一轮请求，这里只用于界面展示。
         *
         * @param calls 本轮累计的工具调用（已回填 result）
         */
        void onToolCall(List<ToolCall> calls);

        /**
         * 请求失败。
         *
         * @param errorMessage 错误描述
         * @param rawResponse  已收到的原始数据（可能为 null）
         */
        void onError(String errorMessage, String rawResponse);
    }

    /**
     * 流式回答。回调始终发生在主线程。
     *
     * @param history 当前对话的完整消息列表（含最后一条用户提问；
     *                内容为空的消息应被忽略）
     */
    void streamReply(List<ChatMessage> history, StreamCallback callback);

    /** 停止当前输出（页面销毁或开启新对话时调用）。 */
    void cancel();
}
