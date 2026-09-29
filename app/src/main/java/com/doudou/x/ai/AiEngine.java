package com.doudou.x.ai;

import com.doudou.x.model.ChatMessage;

import java.util.List;

/**
 * AI 对话引擎统一抽象：模拟引擎与真实 API 引擎实现同一接口，
 * 上层（MainActivity）按配置切换，无需关心实现细节。
 */
public interface AiEngine {

    interface StreamCallback {
        /** 第一个 token 到来前调用（"思考中"阶段结束）。 */
        void onStart();

        /** @param fullText 到目前为止累计的完整文本 */
        void onToken(String fullText);

        /**
         * 正常结束。
         *
         * @param fullText    完整回答文本
         * @param rawResponse API 原始返回字符串（模拟引擎为 null）
         */
        void onComplete(String fullText, String rawResponse);

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
