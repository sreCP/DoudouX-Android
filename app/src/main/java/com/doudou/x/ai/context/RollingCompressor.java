package com.doudou.x.ai.context;

import android.util.Log;

import com.doudou.x.ai.AiEngine;
import com.doudou.x.ai.OpenAiEngine;
import com.doudou.x.model.ChatMessage;
import com.doudou.x.model.ToolCall;

import java.util.ArrayList;
import java.util.List;

/**
 * 滚动压缩：把更早的对话增量压成一份结构化摘要，供后续轮次使用。
 *
 * <p>三个要点：
 * <ul>
 *   <li>追加式：旧摘要连同新增内容一起交给模型，产出的新摘要整体覆盖旧的，
 *       摘要只会往前滚，不会回头重写已经压过的部分；</li>
 *   <li>只压增量：记住已压到第几条，下次从那里继续，同一段对话不会被压第二次；</li>
 *   <li>不阻塞：压缩在后台跑，结果下一次请求才生效，失败就静默跳过，最坏退化成裁剪。</li>
 * </ul>
 */
public final class RollingCompressor {

    private static final String LOG_TAG = OpenAiEngine.LOG_TAG;

    /** 增量不足这个条数就不值得压一次。 */
    public static final int MIN_NEW_MESSAGES = 4;
    /** 单条消息参与摘要时最多取的字符数，防止一条超长消息独占提示词。 */
    private static final int PER_MESSAGE_CHARS = 400;

    /**
     * 摘要提示词。固定要求输出四段：用户目标、已确认事实、失败过的方案、待办。
     * 这四项正是长对话里最容易丢、丢了又最影响后续判断的信息。
     */
    private static final String SUMMARY_PROMPT =
            "请把下面这段较早的对话压缩成一份紧凑摘要，供后续对话继续参考。\n"
                    + "\n【已有摘要】\n%s\n"
                    + "\n【新增对话】\n%s\n"
                    + "\n只输出摘要正文，不要解释、不要寒暄。必须包含这四部分：\n"
                    + "1. 用户目标：\n"
                    + "2. 已确认事实：\n"
                    + "3. 已尝试但失败的方案：\n"
                    + "4. 待办与未完成事项：\n"
                    + "总长度不超过 400 字，没有的部分写「无」。";

    private final AiEngine engine;
    private final SummaryStore store;

    public RollingCompressor(AiEngine engine, SummaryStore store) {
        this.engine = engine;
        this.store = store;
    }

    /** 是否值得再压一次：预留的最近几轮之外还有足够增量。 */
    public boolean shouldCompact(List<ChatMessage> history, SummaryStore.Summary summary,
                                 ContextBudget budget) {
        if (history == null || engine == null || summary == null) {
            return false;
        }
        int end = compactEnd(history, budget);
        return end - summary.compressedCount >= MIN_NEW_MESSAGES;
    }

    /**
     * 后台压缩一次。
     *
     * @param onDone 无论成功失败都会回调一次，用于清掉「压缩中」标记
     */
    public void compactAsync(final List<ChatMessage> history, final String sessionKey,
                             final SummaryStore.Summary summary, final ContextBudget budget,
                             final Runnable onDone) {
        final int end = compactEnd(history, budget);
        String chunk = render(history, summary.compressedCount, end);
        String prompt = String.format(SUMMARY_PROMPT,
                summary.hasSummary() ? summary.text : "（无）", chunk);
        List<ChatMessage> request = new ArrayList<>();
        request.add(new ChatMessage(ChatMessage.ROLE_USER, prompt));
        Log.d(LOG_TAG, "上下文压缩：开始压缩第 " + summary.compressedCount + " 到 " + end + " 条");
        engine.streamReply(request, new AiEngine.StreamCallback() {
            @Override
            public void onStart() {
            }

            @Override
            public void onToken(String fullText, String thinkingText) {
            }

            @Override
            public void onToolCall(List<ToolCall> calls) {
                // 压缩请求不带工具，忽略
            }

            @Override
            public void onComplete(String fullText, String thinkingText, String rawResponse) {
                String text = clean(fullText);
                if (text.isEmpty()) {
                    Log.w(LOG_TAG, "上下文压缩：模型返回空摘要，保留原摘要");
                } else {
                    store.save(sessionKey, text, end);
                    Log.d(LOG_TAG, "上下文压缩：完成，已压到第 " + end + " 条，摘要 " + text.length() + " 字");
                }
                onDone.run();
            }

            @Override
            public void onError(String errorMessage, String rawResponse) {
                // 压缩失败不影响对话本身，下一次超预算时会再试
                Log.w(LOG_TAG, "上下文压缩：失败（" + errorMessage + "），本轮继续按裁剪发送");
                onDone.run();
            }
        });
    }

    /** 压缩区间的结束下标（不含）：最近 compactKeepTurns 条 user 消息之前的部分。 */
    private static int compactEnd(List<ChatMessage> history, ContextBudget budget) {
        int keep = budget.getCompactKeepTurns();
        int found = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i).getRole() == ChatMessage.ROLE_USER) {
                found++;
                if (found >= keep) {
                    return i;
                }
            }
        }
        return 0;
    }

    /** 把 [from, to) 渲染成纯文本。思考过程不进摘要，太占篇幅且对后续无价值。 */
    private static String render(List<ChatMessage> history, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, from); i < to && i < history.size(); i++) {
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

    /** 清洗摘要文本：去首尾空白与「摘要：」这类前缀。 */
    private static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();
        String[] prefixes = {"以下是摘要：", "对话摘要：", "摘要："};
        for (String prefix : prefixes) {
            if (text.startsWith(prefix)) {
                text = text.substring(prefix.length()).trim();
                break;
            }
        }
        return text;
    }
}
