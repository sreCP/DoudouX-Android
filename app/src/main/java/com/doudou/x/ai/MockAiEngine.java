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
            callback.onComplete(accumulated.toString(), null, null);
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
                callback.onToken(accumulated.toString(), null);
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
        if (containsAny(q, "标题", "列表", "加粗", "格式", "md语法")) {
            return "## 一、分级标题\n\n"
                    + "这是一段普通正文，里面可以 **加粗强调**，比如 **兜兜X** 支持 Markdown 渲染。\n\n"
                    + "### 1.1 三级标题\n\n"
                    + "下面是一组无序列表：\n\n"
                    + "- **加粗** 的列表项\n"
                    + "- 普通列表项\n"
                    + "  - 缩进一级的子项，同样支持 **加粗**\n"
                    + "- 再来一项，内容稍微长一点，用来看看换行之后的对齐效果\n\n"
                    + "#### 四级标题\n"
                    + "##### 五级标题\n"
                    + "###### 六级标题\n\n"
                    + "标题、列表、加粗可以和代码块、表格混排，流式输出过程中也会逐步渲染出来。";
        }
        if (containsAny(q, "表格", "table", "markdown", "对比")) {
            return "Markdown 表格渲染示例（表头加粗、行间分隔、数据行底色交替）：\n\n"
                    + "| 渲染模式 | 超长行表现 | 适用场景 |\n"
                    + "| --- | :---: | ---: |\n"
                    + "| 单行横滚 | 不换行，整体横向滚动，每一行都能看完整 | 代码、日志、SQL |\n"
                    + "| 自动换行 | 按屏幕宽度折行，表格压缩到一屏内 | 说明性长文本 |\n\n"
                    + "再来一个带列对齐的宽表（左 / 居中 / 右对齐）：\n\n"
                    + "| 指标 | 说明 | 数值 | 备注 |\n"
                    + "| --- | --- | ---: | :---: |\n"
                    + "| 首屏渲染 | 从冷启动到列表首帧绘制完成 | 320 ms | 已达标 |\n"
                    + "| 流式首字 | 请求发出到收到第一个 token | 680 ms | 依赖网络 |\n"
                    + "| 内存占用 | 会话页面常驻增量 | 48 MB | 需持续观察 |\n\n"
                    + "在「设置」里切换渲染模式，可以看到表格同样会跟随切换。";
        }
        if (containsAny(q, "android", "安卓", "java", "代码", "编程")) {
            return "聊到我熟悉的领域了！关于 Android 开发，一般建议：\n"
                    + "1. 优先保证主线程只做 UI 相关工作；\n"
                    + "2. 列表场景使用 RecyclerView 并复用 ViewHolder；\n"
                    + "3. 耗时任务交给线程池，注意页面销毁时取消回调；\n"
                    + "4. 适配 targetSdk 35 时注意边缘到边缘（edge-to-edge）布局。\n\n"
                    + "下面是一段流式回调的示例代码（最后一行故意很长，用来演示超长行的两种渲染方式）：\n\n"
                    + "```java\n"
                    + "public void streamReply(String question, StreamCallback callback) {\n"
                    + "    executor.execute(() -> doRequest(question, callback));\n"
                    + "}\n"
                    + "\n"
                    + "private void doRequest(String question, StreamCallback callback) {\n"
                    + "    StringBuilder answer = new StringBuilder();\n"
                    + "    // 逐 token 回调，UI 侧增量刷新即可形成打字机效果\n"
                    + "    for (String token : tokens) { answer.append(token); callback.onToken(answer.toString()); }\n"
                    + "}\n"
                    + "String veryLongLine = \"这一行非常非常长，用来演示单行横向滚动与自动换行两种渲染模式的区别，可以拖着看完整内容，也可以打开设置里的换行开关看黑灰相间的行间标记效果，横屏时能看到更完整的一行\";\n"
                    + "```\n\n"
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
