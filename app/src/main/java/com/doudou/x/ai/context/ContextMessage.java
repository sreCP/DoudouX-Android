package com.doudou.x.ai.context;

import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * 组装后的一个上下文条目，已经是面向请求体的扁平结构。
 *
 * <p>保留原始 {@link ChatMessage} 是为了序列化工具调用时能取到 id / name / arguments；
 * 系统提示词与摘要没有来源消息，source 为 null。
 */
public final class ContextMessage {

    /** 系统提示词，永远第一条。 */
    public static final int KIND_SYSTEM = 0;
    /** 历史摘要，同样下发成 system，但与系统提示词分开计量。 */
    public static final int KIND_SUMMARY = 1;
    /** 正常对话历史。 */
    public static final int KIND_HISTORY = 2;

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_TOOL = "tool";

    /** 内容被截断后追加的标记。 */
    private static final String TRUNCATE_MARK = "…（已截断）";

    private final String role;
    private final String content;
    private final int kind;
    private final ChatMessage source;
    private final int tokens;
    private final boolean truncated;

    private ContextMessage(String role, String content, int kind,
                           ChatMessage source, boolean truncated) {
        this.role = role;
        this.content = content == null ? "" : content;
        this.kind = kind;
        this.source = source;
        this.truncated = truncated;
        this.tokens = countTokens(this.role, this.content, source);
    }

    public static ContextMessage system(String text) {
        return new ContextMessage(ROLE_SYSTEM, text, KIND_SYSTEM, null, false);
    }

    public static ContextMessage summary(String text) {
        return new ContextMessage(ROLE_SYSTEM, text, KIND_SUMMARY, null, false);
    }

    /**
     * 把一条会话消息转成上下文条目。
     *
     * @return null 表示这条应当跳过（错误信息、流式占位中的空消息）
     */
    public static ContextMessage fromChat(ChatMessage msg) {
        if (msg == null || msg.isError()) {
            return null; // 错误信息只是本地提示，不能作为上下文回传
        }
        if (msg.getRole() == ChatMessage.ROLE_TOOL) {
            return new ContextMessage(ROLE_TOOL, msg.getContent(), KIND_HISTORY, msg, false);
        }
        List<ToolCall> calls = msg.getToolCalls();
        if (calls != null && !calls.isEmpty()) {
            return new ContextMessage(ROLE_ASSISTANT, msg.getContent(), KIND_HISTORY, msg, false);
        }
        String content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        String role = msg.getRole() == ChatMessage.ROLE_USER ? ROLE_USER : ROLE_ASSISTANT;
        return new ContextMessage(role, content, KIND_HISTORY, msg, false);
    }

    /**
     * 按剩余预算截断内容，保留开头部分。
     *
     * <p>中英混排下「字符数 / token」不是常数，所以先按比例缩一次，
     * 再逐步回退直到确实装得下。
     */
    public ContextMessage truncateTo(int budgetTokens) {
        if (budgetTokens <= 0 || tokens <= budgetTokens) {
            return this;
        }
        int length = (int) (content.length() * ((double) budgetTokens / tokens));
        for (int i = 0; i < 4; i++) {
            int end = Math.max(0, Math.min(length, content.length()));
            String cut = content.substring(0, end) + TRUNCATE_MARK;
            if (TokenEstimator.estimate(cut) <= budgetTokens) {
                return new ContextMessage(role, cut, kind, source, true);
            }
            length = length * 9 / 10;
        }
        return new ContextMessage(role, TRUNCATE_MARK, kind, source, true);
    }

    /** 序列化成 OpenAI 兼容的一条消息。 */
    public JSONObject toJson() {
        JSONObject item = new JSONObject();
        try {
            item.put("role", role);
            if (ROLE_TOOL.equals(role)) {
                item.put("tool_call_id", source == null ? "" : source.getToolCallId());
                item.put("content", content);
                return item;
            }
            List<ToolCall> calls = source == null ? null : source.getToolCalls();
            if (calls != null && !calls.isEmpty()) {
                if (!content.isEmpty()) {
                    item.put("content", content);
                }
                JSONArray array = new JSONArray();
                for (ToolCall call : calls) {
                    JSONObject obj = new JSONObject();
                    obj.put("id", call.getId());
                    obj.put("type", "function");
                    JSONObject function = new JSONObject();
                    function.put("name", call.getName());
                    function.put("arguments",
                            call.getArguments() == null ? "" : call.getArguments());
                    obj.put("function", function);
                    array.put(obj);
                }
                item.put("tool_calls", array);
                return item;
            }
            item.put("content", content);
        } catch (Exception e) {
            // 序列化失败不应中断整轮请求：退化成只带 role 的消息
        }
        return item;
    }

    /** 统计一条消息的 token：正文 + 结构开销 + 工具调用的壳与参数。 */
    private static int countTokens(String role, String content, ChatMessage source) {
        int tokens = TokenEstimator.estimate(content) + TokenEstimator.PER_MESSAGE;
        if (source == null) {
            return tokens;
        }
        if (source.getRole() == ChatMessage.ROLE_TOOL) {
            return tokens + TokenEstimator.TOOL_MESSAGE;
        }
        List<ToolCall> calls = source.getToolCalls();
        if (calls != null) {
            for (ToolCall call : calls) {
                tokens += TokenEstimator.PER_TOOL_CALL
                        + TokenEstimator.estimate(call.getName())
                        + TokenEstimator.estimate(call.getArguments());
            }
        }
        return tokens;
    }

    public String getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    public int getKind() {
        return kind;
    }

    public ChatMessage getSource() {
        return source;
    }

    public int getTokens() {
        return tokens;
    }

    public boolean isTruncated() {
        return truncated;
    }
}
