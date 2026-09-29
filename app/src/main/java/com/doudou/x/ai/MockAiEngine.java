package com.doudou.x.ai;

import android.os.Handler;
import android.os.Looper;

import com.doudou.x.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 模拟 AI 流式输出引擎（本地生成，无网络）。
 */
public class MockAiEngine implements AiEngine {

    private static final long THINKING_DELAY_MS = 700;
    private static final long TOKEN_MIN_DELAY_MS = 30;
    private static final long TOKEN_MAX_DELAY_MS = 90;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random random = new Random();

    private boolean cancelled = false;
    private Runnable pendingRunnable;

    @Override
    public void streamReply(List<ChatMessage> history, final StreamCallback callback) {
        cancel();

        final String question = lastUserQuestion(history);
        final String fullAnswer = buildAnswer(question);
        final List<String> tokens = splitToTokens(fullAnswer);
        final StringBuilder accumulated = new StringBuilder();

        cancelled = false;
        final int[] index = {0};

        Runnable startRunnable = new Runnable() {
            @Override
            public void run() {
                if (cancelled) {
                    return;
                }
                callback.onStart();
                scheduleNextToken(tokens, index, accumulated, callback);
            }
        };
        pendingRunnable = startRunnable;
        handler.postDelayed(startRunnable, THINKING_DELAY_MS);
    }

    @Override
    public void cancel() {
        cancelled = true;
        if (pendingRunnable != null) {
            handler.removeCallbacks(pendingRunnable);
            pendingRunnable = null;
        }
    }

    private void scheduleNextToken(final List<String> tokens,
                                   final int[] index,
                                   final StringBuilder accumulated,
                                   final StreamCallback callback) {
        if (cancelled) {
            return;
        }
        if (index[0] >= tokens.size()) {
            callback.onComplete(accumulated.toString(), null);
            return;
        }
        long delay = TOKEN_MIN_DELAY_MS
                + (long) (random.nextFloat() * (TOKEN_MAX_DELAY_MS - TOKEN_MIN_DELAY_MS));
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                if (cancelled) {
                    return;
                }
                accumulated.append(tokens.get(index[0]));
                index[0]++;
                callback.onToken(accumulated.toString());
                scheduleNextToken(tokens, index, accumulated, callback);
            }
        };
        pendingRunnable = runnable;
        handler.postDelayed(runnable, delay);
    }

    private String lastUserQuestion(List<ChatMessage> history) {
        if (history == null) {
            return "";
        }
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getRole() == ChatMessage.ROLE_USER) {
                return msg.getContent();
            }
        }
        return "";
    }

    /** 把回答切成 1~3 个字符的小块，模拟 token 粒度。 */
    private List<String> splitToTokens(String text) {
        List<String> tokens = new ArrayList<>();
        int i = 0;
        while (i < text.length()) {
            int size = 1 + random.nextInt(3);
            size = Math.min(size, text.length() - i);
            tokens.add(text.substring(i, i + size));
            i += size;
        }
        return tokens;
    }

    // ---------------------------------------------------------------------
    // 模拟的回答内容
    // ---------------------------------------------------------------------

    private String buildAnswer(String question) {
        String q = question == null ? "" : question.trim();

        if (containsAny(q, "你好", "您好", "hi", "hello", "嗨")) {
            return "你好呀！我是兜兜X，你的随身 AI 伙伴。"
                    + "无论是写代码、改文案，还是随便聊聊，我都很乐意帮忙。"
                    + "今天有什么想聊的吗？";
        }
        if (containsAny(q, "你是谁", "名字", "介绍")) {
            return "我是兜兜X，一个 AI 对话助手。"
                    + "我目前是本地模拟回复；在「设置 → AI 接口设置」里"
                    + "配置 OpenAI 兼容接口后，就能用真实大模型和我聊天了。";
        }
        if (containsAny(q, "天气")) {
            return "我暂时还无法获取实时天气数据。"
                    + "不过演示一下：北京今天晴，18~26℃，微风，适合出门走走。"
                    + "（此为模拟数据，请以实际天气为准）";
        }
        if (containsAny(q, "android", "安卓", "java", "代码", "编程")) {
            return "聊到我熟悉的领域了！关于 Android 开发，一般建议：\n"
                    + "1. 优先保证主线程只做 UI 相关工作；\n"
                    + "2. 列表场景使用 RecyclerView 并复用 ViewHolder；\n"
                    + "3. 耗时任务交给线程池，注意页面销毁时取消回调；\n"
                    + "4. 适配 targetSdk 35 时注意边缘到边缘（edge-to-edge）布局。"
                    + "如果你有具体问题，可以继续追问～";
        }
        if (containsAny(q, "笑话", "开心", "无聊")) {
            return "给你讲一个：\n"
                    + "程序员最讨厌的四件事：写注释、写文档、"
                    + "别人不写注释、别人不写文档。"
                    + "希望这个冷笑话能让你笑一笑 😄";
        }
        if (containsAny(q, "谢谢", "感谢")) {
            return "不客气！能帮上忙就好。还有其他问题随时问我～";
        }

        return "收到你的问题：「" + q + "」。\n"
                + "我目前是本地模拟回复，还没有接入真正的大模型。"
                + "不过这条消息是一个字一个字流式输出的，"
                + "用来演示真实 AI 对话的打字机效果。"
                + "在「设置 → AI 接口设置」中填入 OpenAI 兼容接口后，"
                + "这里就会显示真实的回答内容。";
    }

    private boolean containsAny(String text, String... keywords) {
        String lower = text.toLowerCase();
        for (String keyword : keywords) {
            if (lower.contains(keyword.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}
