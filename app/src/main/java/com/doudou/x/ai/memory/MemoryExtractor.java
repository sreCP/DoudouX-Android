package com.doudou.x.ai.memory;

import android.util.Log;

import com.doudou.x.ai.AiEngine;
import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 记忆抽取：从一段对话里挑出值得长期记住的信息。
 *
 * <p>抽取而不是"全存"是关键——把每轮对话都塞进记忆库，
 * 检索质量会被大量无意义的寒暄稀释掉，几百条之后就基本不可用。
 * 所以提示词里明确要求：只写明确出现的信息、推测的一律不写、最多 5 条。
 *
 * <p>与滚动压缩共用同一套节拍：<b>后台跑、只处理增量、失败静默跳过</b>，
 * 结果下一次请求才生效。
 */
public final class MemoryExtractor {

    private static final String LOG_TAG = OpenAiEngine.LOG_TAG;

    /** 增量不足这个条数不值得抽一次。 */
    public static final int MIN_NEW_MESSAGES = 6;
    /** 单次最多写入多少条记忆。 */
    private static final int MAX_ITEMS_PER_ROUND = 5;
    /** 单条消息参与抽取时最多取的字符数。 */
    private static final int PER_MESSAGE_CHARS = 300;

    /**
     * 抽取提示词。三个类型的划分让模型有明确的归类依据，
     * "没有值得记的就输出 []" 这一句很重要——否则模型会硬凑出无价值的条目。
     */
    private static final String EXTRACT_PROMPT =
            "请从下面这段对话中提取值得长期记住的信息，供以后的对话参考。\n"
                    + "\n【已记住的内容】（这些不要重复；如果新信息与之矛盾，以新的为准）\n%s\n"
                    + "\n【本次对话】\n%s\n"
                    + "\n只输出一个 JSON 数组，不要解释、不要用 markdown 代码块围栏。每条格式：\n"
                    + "{\"type\":\"semantic|procedural|episodic\","
                    + "\"key\":\"主题或主语\","
                    + "\"content\":\"一句话事实\","
                    + "\"confidence\":0.9}\n"
                    + "\ntype 的取值：\n"
                    + "· semantic：关于用户的稳定事实（身份、偏好、约束、明确说过的信息）\n"
                    + "· procedural：用户要求的做事方式或输出格式（例如「先给结论」「代码要可运行」）\n"
                    + "· episodic：本次对话的主题与最终结论，一句话概括\n"
                    + "confidence 是你对这条信息的把握程度，0.1~1。\n"
                    + "\n要求：\n"
                    + "1. 只写对话里明确出现的信息，推测的一律不写\n"
                    + "2. 已有记忆里出现过的不要重复\n"
                    + "3. 最多 " + MAX_ITEMS_PER_ROUND + " 条；没有值得记的就输出 []";

    private final AiEngine engine;

    public MemoryExtractor(AiEngine engine) {
        this.engine = engine;
    }

    /**
     * 后台抽取一次。
     *
     * @param onDone 无论成功失败都会回调一次，用于清掉「抽取中」标记
     */
    public void extractAsync(final List<ChatMessage> history, final int from,
                            final String knownSummary, final MemoryStore store,
                            final String sessionKey, final int end,
                            final Runnable onDone) {
        String chunk = render(history, from);
        String prompt = String.format(EXTRACT_PROMPT,
                knownSummary == null || knownSummary.isEmpty() ? "（无）" : knownSummary,
                chunk);
        List<ChatMessage> request = new ArrayList<>();
        request.add(new ChatMessage(ChatMessage.ROLE_USER, prompt));
        Log.d(LOG_TAG, "记忆抽取：处理第 " + from + " 到 " + end + " 条");
        engine.streamReply(request, new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onToken(String fullText, String thinkingText) {
            }

            @Override
            public void onToolCall(List<ToolCall> calls) {
                // 抽取请求不带工具，忽略
            }

            @Override
            public void onComplete(String fullText, String thinkingText, String rawResponse) {
                List<MemoryItem> items = parse(fullText);
                int added = 0;
                for (MemoryItem item : items) {
                    if (store.upsert(item)) {
                        added++;
                    }
                }
                store.setExtractedCount(sessionKey, end);
                Log.d(LOG_TAG, "记忆抽取：完成，新增 " + added + " 条，覆盖 "
                        + (items.size() - added) + " 条");
                onDone.run();
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                // 抽取失败不影响对话，下一次达到增量门槛时会再试
                Log.w(LOG_TAG, "记忆抽取：失败（" + errorMessage + "），本轮跳过");
                onDone.run();
            }
        });
    }

    /**
     * 解析模型输出的 JSON 数组。
     *
     * <p>容错是这里的重点：模型经常在 JSON 外面包一层 markdown 代码块，
     * 或在数组前后加几句废话，偶尔还会吐出非法 JSON。
     * 处理方式是从第一个 {@code [} 截到最后一个 {@code ]}，逐条解析，
     * 单条坏掉只丢那一条。
     */
    static List<MemoryItem> parse(String raw) {
        List<MemoryItem> items = new ArrayList<>();
        if (raw == null) {
            return items;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline >= 0) {
                text = text.substring(firstNewline + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int start = text.indexOf('[');
        int end = text.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return items;
        }
        try {
            JSONArray arr = new JSONArray(text.substring(start, end + 1));
            for (int i = 0; i < arr.length() && items.size() < MAX_ITEMS_PER_ROUND; i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String content = obj.optString("content", "").trim();
                if (content.isEmpty() || "null".equals(content)) {
                    continue;
                }
                String key = obj.optString("key", "").trim();
                MemoryType type = MemoryType.fromJson(obj.optString("type", null));
                float confidence = (float) obj.optDouble("confidence", 0.8f);
                items.add(new MemoryItem(MemoryStore.newId(), type, key, content, confidence));
            }
        } catch (Exception e) {
            // 整体解析失败就当这次没抽到，下次再试
        }
        return items;
    }

    /** 把 [from, 末尾) 的对话渲染成纯文本；思考过程不参与抽取。 */
    static String render(List<ChatMessage> history, int from) {
        StringBuilder sb = new StringBuilder();
        if (history == null) {
            return "";
        }
        for (int i = Math.max(0, from); i < history.size(); i++) {
            ChatMessage msg = history.get(i);
            String content = msg.getContent();
            if (content == null || content.isEmpty() || msg.isError()) {
                continue;
            }
            sb.append(msg.getRole() == ChatMessage.ROLE_USER ? "用户：" : "助手：");
            sb.append(content.length() > PER_MESSAGE_CHARS
                    ? content.substring(0, PER_MESSAGE_CHARS) + "…" : content);
            sb.append('\n');
        }
        return sb.toString();
    }
}
