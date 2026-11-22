package com.doudou.x.ai.agent;

import android.util.Log;

import com.doudou.x.ai.StreamLogger;
import com.doudou.x.ai.ToolExecutor;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Function Calling 的一轮「工具执行 + 上下文回填」。
 *
 * <p>这是 Agent 循环里与网络无关的那一半：模型要求调用工具后，
 * 本地执行、把结果按 OpenAI 协议回填成 assistant(tool_calls) + tool 消息、
 * 拼出下一轮要发出去的历史。抽出来的好处是它变成了纯函数式的编排单元——
 * 不碰 socket、不碰回调、不起线程，因此可以被直接测。
 */
public final class ToolLoop {

    /** 最多自动接续的轮数，防止模型无限调用工具。 */
    public static final int MAX_ROUNDS = 3;

    private static final String LOG_TAG = StreamLogger.LOG_TAG;

    /** 本轮的处理结果。 */
    public enum Decision {
        /** 已执行工具，可以带着 {@link Outcome#nextHistory} 再请求一次。 */
        CONTINUE,
        /** 轮次已用尽，不再执行。 */
        LIMIT
    }

    /** 一轮执行后的产物。 */
    public static final class Outcome {
        public final Decision decision;
        /** 含历史轮次的全部工具调用，用于界面一次性展示。 */
        public final List<ToolCall> allCalls;
        /** 下一轮请求要用的历史；LIMIT 时为 null。 */
        public final List<ChatMessage> nextHistory;
        /** 本轮实际执行的工具名，用于 trace。 */
        public final List<String> executedNames;

        Outcome(Decision decision, List<ToolCall> allCalls,
                List<ChatMessage> nextHistory, List<String> executedNames) {
            this.decision = decision;
            this.allCalls = allCalls;
            this.nextHistory = nextHistory;
            this.executedNames = executedNames;
        }
    }

    private ToolLoop() {
    }

    /**
     * 执行本轮工具并组装下一轮历史。
     *
     * @param history      本轮请求用的历史
     * @param roundCalls   模型本轮要求调用的工具
     * @param carried      之前轮次已执行的调用
     * @param executor     工具执行器，调用方需保证非 null
     * @param roundsLeft   本次请求还剩多少轮可接续
     */
    public static Outcome run(List<ChatMessage> history, List<ToolCall> roundCalls,
                              List<ToolCall> carried, ToolExecutor executor, int roundsLeft) {
        if (roundsLeft <= 0) {
            Log.w(LOG_TAG, "工具调用轮次已达上限（" + MAX_ROUNDS + " 轮），停止调用");
            return new Outcome(Decision.LIMIT,
                    carried == null ? Collections.<ToolCall>emptyList() : carried,
                    null, Collections.<String>emptyList());
        }

        List<String> executedNames = new ArrayList<>();
        for (ToolCall call : roundCalls) {
            executedNames.add(call.getName());
            call.setResult(executor.execute(call.getName(), call.getArguments()));
            Log.d(LOG_TAG, "工具调用：" + call.getName()
                    + "，参数：" + call.getArguments()
                    + "，结果：" + call.getResult());
        }

        List<ToolCall> allCalls = new ArrayList<>();
        if (carried != null) {
            allCalls.addAll(carried);
        }
        allCalls.addAll(roundCalls);

        // 按 OpenAI 协议回填：assistant 带上 tool_calls，随后每个结果一条 tool 消息
        List<ChatMessage> nextHistory = new ArrayList<>(history);
        ChatMessage assistantCall = new ChatMessage(ChatMessage.ROLE_AI, "");
        assistantCall.setToolCalls(roundCalls);
        nextHistory.add(assistantCall);
        for (ToolCall call : roundCalls) {
            ChatMessage toolMessage = new ChatMessage(ChatMessage.ROLE_TOOL, call.getResult());
            toolMessage.setToolCallId(call.getId());
            nextHistory.add(toolMessage);
        }
        return new Outcome(Decision.CONTINUE, allCalls, nextHistory, executedNames);
    }
}
