package com.doudou.x.ai.openai;

import com.doudou.x.ai.ToolRegistry;
import com.doudou.x.ai.context.ContextManager;
import com.doudou.x.ai.context.ContextPlan;
import com.doudou.x.data.ApiConfigStore;
import com.doudou.x.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容请求体的构造层。
 *
 * <p>只关心「发出去的 JSON 长什么样」，不关心连接、不关心解析、不关心工具执行。
 * 两种形态：
 * <ul>
 *   <li><b>完整请求</b>：经 {@link ContextManager} 做分层预算、记忆注入、滚动摘要，
 *       按配置下发采样参数，可选下发 tools 与端侧上下文统计；</li>
 *   <li><b>精简请求</b>：只发原始消息，不带 system prompt / 上下文工程 / 工具，
 *       并强制关闭 thinking。用于生成标题这类「一句话就够」的短任务。</li>
 * </ul>
 */
public final class RequestBodyBuilder {

    /** 精简请求的输出上限：一句话摘要足够，避免模型长篇发挥。 */
    private static final int LITE_MAX_TOKENS = 64;

    /** 精简请求用较低温度，让短摘要更稳定、少发散。 */
    private static final float LITE_TEMPERATURE = 0.3f;

    private final ApiConfigStore config;
    private final ContextManager contextManager;

    public RequestBodyBuilder(ApiConfigStore config, ContextManager contextManager) {
        this.config = config;
        this.contextManager = contextManager;
    }

    /**
     * 构造请求体。
     *
     * @param history   当前对话的完整消息列表
     * @param withTools 是否下发 tools 声明
     * @param lite      是否走精简分支
     */
    public String build(List<ChatMessage> history, boolean withTools, boolean lite) {
        try {
            if (lite) {
                return buildLite(history);
            }
            return buildFull(history, withTools);
        } catch (Exception e) {
            throw new RuntimeException("请求体序列化失败：" + e.getMessage(), e);
        }
    }

    /** 精简请求：只要原始消息，不碰 system prompt / 上下文工程 / 工具，硬关 thinking。 */
    private String buildLite(List<ChatMessage> history) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", config.getModel());
        body.put("stream", true);
        body.put("messages", rawMessages(history));
        // Ollama 的 OpenAI 兼容层不认 think=false，只认 reasoning_effort=none；
        // 两个都发，前者给支持的原生端点，后者保证 Ollama 真正不思考。
        body.put("reasoning_effort", "none");
        body.put("think", false);
        body.put("max_tokens", LITE_MAX_TOKENS);
        body.put("temperature", LITE_TEMPERATURE);
        return body.toString();
    }

    /** 完整请求：走上下文工程 + 采样参数 + 可选的 tools 与端侧统计。 */
    private String buildFull(List<ChatMessage> history, boolean withTools) throws Exception {
        // 服务端不保存会话：完整历史由客户端每次回传；
        // 关闭该开关时只发送当前这一句，用于省流或单轮问答场景
        List<ChatMessage> toSend = history;
        if (!config.isSendFullHistory() && history != null) {
            ChatMessage lastUser = lastUserMessage(history);
            if (lastUser != null) {
                toSend = new ArrayList<>();
                toSend.add(lastUser);
            }
        }

        String systemPrompt = config.getSystemPrompt();
        // 分层预算、历史裁剪与滚动摘要都在 ai.context 模块内，这里只取组装结果
        JSONArray messages = contextManager.buildMessages(toSend, systemPrompt, withTools);

        JSONObject body = new JSONObject();
        body.put("model", config.getModel());
        body.put("stream", true);
        body.put("messages", messages);
        // 模型参数：未设置的不下发，交给服务端默认值
        float temperature = config.getTemperature();
        if (temperature >= 0f) {
            body.put("temperature", temperature);
        }
        float topP = config.getTopP();
        if (topP >= 0f) {
            body.put("top_p", topP);
        }
        int maxTokens = config.getMaxTokens();
        if (maxTokens > 0) {
            body.put("max_tokens", maxTokens);
        }
        // 关闭模型思考：用 reasoning_effort=none（think=false 对部分服务端不生效）
        if (config.isDisableThinking()) {
            body.put("reasoning_effort", "none");
        }
        // Function Calling：声明可用工具，交给模型决定要不要调用
        if (withTools) {
            body.put("tools", ToolRegistry.toolsJson());
            body.put("tool_choice", "auto");
        }
        // 把端侧上下文分层统计随请求带上，网关会剔除该字段并写进 trace。
        // 这样 trace 由网关统一记录，也能拿到只有端侧才知道的预算/裁剪数据。
        JSONObject ctx = contextStatsJson(ContextManager.lastStats());
        if (ctx != null) {
            body.put("x_doudou_ctx", ctx);
        }
        return body.toString();
    }

    /** 从后往前找最后一条用户消息。 */
    private static ChatMessage lastUserMessage(List<ChatMessage> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage message = history.get(i);
            if (message != null && message.getRole() == ChatMessage.ROLE_USER) {
                return message;
            }
        }
        return null;
    }

    /** 端侧分层 token 统计 → JSON；没有统计时返回 null。 */
    private static JSONObject contextStatsJson(ContextPlan.Stats stats) throws Exception {
        if (stats == null) {
            return null;
        }
        JSONObject ctx = new JSONObject();
        ctx.put("sys", stats.systemTokens);
        ctx.put("tool", stats.toolTokens);
        ctx.put("mem", stats.memoryTokens);
        ctx.put("sum", stats.summaryTokens);
        ctx.put("hist", stats.historyTokens);
        ctx.put("dropped", stats.droppedCount);
        ctx.put("truncated", stats.truncatedCount);
        ctx.put("overflow", stats.overflow);
        return ctx;
    }

    /** 把消息原样转成 OpenAI 格式，不做任何上下文加工（精简请求专用）。 */
    private static JSONArray rawMessages(List<ChatMessage> messages) throws Exception {
        JSONArray array = new JSONArray();
        if (messages == null) {
            return array;
        }
        for (ChatMessage message : messages) {
            if (message == null) {
                continue;
            }
            JSONObject obj = new JSONObject();
            obj.put("role", roleName(message.getRole()));
            obj.put("content", message.getContent() == null ? "" : message.getContent());
            array.put(obj);
        }
        return array;
    }

    private static String roleName(int role) {
        if (role == ChatMessage.ROLE_USER) {
            return "user";
        }
        if (role == ChatMessage.ROLE_TOOL) {
            return "tool";
        }
        return "assistant";
    }
}
