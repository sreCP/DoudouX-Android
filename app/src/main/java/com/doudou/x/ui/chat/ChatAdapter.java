package com.doudou.x.ui.chat;

import android.content.Context;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.doudou.x.R;
import com.doudou.x.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息列表适配器（用户 / AI 两种气泡）。
 * AI 消息支持 Markdown：代码块会渲染成带行号的暗色代码视图。
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    /** 局部刷新 payload：仅更新文本，避免整条 View 重建造成闪烁。 */
    public static final Object PAYLOAD_TEXT = new Object();

    /** 流式输出期间思考过程最多显示的行数（避免气泡无限增高）。 */
    private static final int THINKING_STREAM_MAX_LINES = 6;
    /** 流式输出期间思考过程最多保留的字符数（避免超长文本反复测量拖垮主线程）。 */
    private static final int THINKING_STREAM_MAX_CHARS = 400;

    /** 长按 AI 消息（用于查看原始返回）。 */
    public interface OnAiMessageLongClickListener {
        void onAiMessageLongClick(ChatMessage message);
    }

    /** 点击代码块复制按钮。 */
    public interface OnCodeCopyListener {
        void onCopyCode(String code);
    }

    private static final String TYPING_CURSOR = " ▍";

    private final List<ChatMessage> messages = new ArrayList<>();
    private OnAiMessageLongClickListener longClickListener;
    private OnCodeCopyListener codeCopyListener;
    private int codeRenderMode = CodeBlockRenderer.MODE_SINGLE_LINE;

    public void setOnAiMessageLongClickListener(OnAiMessageLongClickListener listener) {
        this.longClickListener = listener;
    }

    public void setOnCodeCopyListener(OnCodeCopyListener listener) {
        this.codeCopyListener = listener;
    }

    public void setCodeRenderMode(int mode) {
        this.codeRenderMode = mode;
    }

    public void setMessages(List<ChatMessage> newMessages) {
        messages.clear();
        if (newMessages != null) {
            messages.addAll(newMessages);
        }
        notifyDataSetChanged();
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /** 流式更新最后一条 AI 消息的内容（正文 + 思考过程）。 */
    public void updateLastMessage(String text, String thinking, boolean streaming) {
        if (messages.isEmpty()) {
            return;
        }
        int lastIndex = messages.size() - 1;
        ChatMessage last = messages.get(lastIndex);
        last.setContent(text);
        last.setThinking(thinking);
        last.setStreaming(streaming);
        notifyItemChanged(lastIndex, PAYLOAD_TEXT);
    }

    /** 只更新最后一条消息的数据，不触发刷新（配合流式增量更新使用）。 */
    public void setLastMessageData(String text, String thinking) {
        if (messages.isEmpty()) {
            return;
        }
        ChatMessage last = messages.get(messages.size() - 1);
        last.setContent(text);
        last.setThinking(thinking);
    }

    /** 最后一条消息，供调用方判断增量更新是否作用于同一条。 */
    public ChatMessage getLastMessage() {
        return messages.isEmpty() ? null : messages.get(messages.size() - 1);
    }

    public int getMessageCount() {
        return messages.size();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getRole();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == ChatMessage.ROLE_USER
                ? R.layout.item_message_user
                : R.layout.item_message_ai;
        View view = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position,
                                 @NonNull List<Object> payloads) {
        if (!payloads.isEmpty() && payloads.contains(PAYLOAD_TEXT)) {
            holder.bind(messages.get(position), codeRenderMode, longClickListener, codeCopyListener);
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        final ChatMessage message = messages.get(position);
        holder.bind(message, codeRenderMode, longClickListener, codeCopyListener);
        if (message.getRole() == ChatMessage.ROLE_AI && longClickListener != null) {
            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    longClickListener.onAiMessageLongClick(message);
                    return true;
                }
            });
        } else {
            holder.itemView.setOnLongClickListener(null);
        }
    }

    /** 分级标题：## 一级标题 按级别递减字号，全部加粗。 */
    private static View buildHeadingView(Context context, MarkdownParser.Block block) {
        TextView textView = new TextView(context);
        textView.setText(MarkdownInline.apply(block.text));
        textView.setTextSize(headingTextSize(block.level));
        textView.setTypeface(Typeface.DEFAULT_BOLD);
        textView.setTextColor(context.getResources().getColor(R.color.text_primary));
        textView.setLineSpacing(0f, 1.35f);
        textView.setTextIsSelectable(true);
        int extraTop = block.level <= 2 ? dp(context, 4) : dp(context, 2);
        textView.setPadding(0, extraTop, 0, 0);
        return textView;
    }

    private static float headingTextSize(int level) {
        switch (level) {
            case 1:
                return 21f;
            case 2:
                return 19f;
            case 3:
                return 17f;
            case 4:
                return 16f;
            default:
                return 15f;
        }
    }

    /** 无序列表：圆点符号 + 内容，支持按缩进分级的空心圆点。 */
    private static View buildListView(Context context, MarkdownParser.Block block) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        if (block.items == null) {
            return root;
        }
        for (MarkdownParser.ListItem item : block.items) {
            root.addView(buildListItemView(context, item));
        }
        return root;
    }

    private static View buildListItemView(Context context, MarkdownParser.ListItem item) {
        boolean nested = item.indent > 0;
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.setPadding(item.indent * dp(context, 16), 0, 0, 0);

        TextView bullet = new TextView(context);
        bullet.setText(nested ? "◦" : "•");
        bullet.setTextSize(15);
        bullet.setTextColor(nested ? 0xFFA9B1C0 : 0xFF8A93A6);
        bullet.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 16),
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(bullet);

        TextView content = new TextView(context);
        content.setText(MarkdownInline.apply(item.text));
        content.setTextSize(15);
        content.setTextColor(context.getResources().getColor(R.color.text_primary));
        content.setLineSpacing(0f, 1.4f);
        content.setTextIsSelectable(true);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(content);
        return row;
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** 非静态内部类：思考块展开/收起时需要回调 Adapter 刷新当前项。 */
    /** 错误信息：⚠️ 前缀 + 警示色，提示这只是本地报错，不会进入上下文。 */
    private static View buildErrorView(Context context, String text) {
        TextView textView = new TextView(context);
        textView.setText("⚠️ " + (text == null ? "" : text));
        textView.setTextSize(14);
        textView.setTextColor(context.getResources().getColor(R.color.danger));
        textView.setLineSpacing(0f, 1.35f);
        textView.setTextIsSelectable(true);
        return textView;
    }

    public class MessageViewHolder extends RecyclerView.ViewHolder {

        private final Context context;
        private final TextView tvMessage;
        private final LinearLayout messageBody;

        /** 流式快速通道：只维护两个 TextView，避免每个 token 重建整条消息。 */
        private TextView streamThinkingBody;
        private TextView streamContent;
        private boolean streamingLayoutBound;
        /** 当前绑定的消息，用于判断增量更新是否仍然有效。 */
        private ChatMessage boundMessage;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            context = itemView.getContext();
            tvMessage = itemView.findViewById(R.id.tvMessage);
            messageBody = itemView.findViewById(R.id.messageBody);
        }

        void bind(ChatMessage message, int renderMode,
                  final OnAiMessageLongClickListener longClickListener,
                  final OnCodeCopyListener copyListener) {
            if (message.getRole() == ChatMessage.ROLE_USER) {
                tvMessage.setText(message.getContent());
                boundMessage = message;
                streamingLayoutBound = false;
                return;
            }
            boundMessage = message;

            // AI 消息：按 Markdown 结构渲染（文本 + 标题 + 列表 + 代码块 + 表格）
            messageBody.removeAllViews();
            streamThinkingBody = null;
            streamContent = null;

            // 流式输出中走轻量布局：一个思考 TextView + 一个正文 TextView，
            // 后续只做 setText 增量更新；渲染完成后才做完整 Markdown 渲染
            if (message.isStreaming() && !message.isError()) {
                bindStreamingLayout(message);
                return;
            }
            streamingLayoutBound = false;

            if (message.isError()) {
                // 错误信息用醒目的样式单独展示，且不会作为上下文回传给接口
                messageBody.addView(buildErrorView(context, message.getContent()));
                return;
            }

            // 思考过程（字体更小、颜色更浅、可折叠）
            if (!TextUtils.isEmpty(message.getThinking())) {
                messageBody.addView(buildThinkingView(message));
            }

            List<MarkdownParser.Block> blocks = MarkdownParser.parse(message.getContent());
            int gap = (int) (context.getResources().getDisplayMetrics().density * 8 + 0.5f);
            for (int i = 0; i < blocks.size(); i++) {
                MarkdownParser.Block block = blocks.get(i);
                boolean isLast = i == blocks.size() - 1;
                View blockView;
                if (block.type == MarkdownParser.TYPE_CODE) {
                    blockView = CodeBlockRenderer.render(
                            context, block.lang, block.text, renderMode,
                            new CodeBlockRenderer.OnCopyListener() {
                                @Override
                                public void onCopy(String code) {
                                    if (copyListener != null) {
                                        copyListener.onCopyCode(code);
                                    }
                                }
                            });
                } else if (block.type == MarkdownParser.TYPE_TABLE) {
                    blockView = TableBlockRenderer.render(context, block.table, renderMode);
                } else if (block.type == MarkdownParser.TYPE_HEADING) {
                    blockView = buildHeadingView(context, block);
                } else if (block.type == MarkdownParser.TYPE_LIST) {
                    blockView = buildListView(context, block);
                } else {
                    TextView textView = new TextView(context);
                    String text = block.text;
                    if (isLast && message.isStreaming()) {
                        text = text + TYPING_CURSOR;
                    }
                    textView.setText(MarkdownInline.apply(text));
                    textView.setTextSize(15);
                    textView.setTextColor(context.getResources().getColor(R.color.text_primary));
                    textView.setLineSpacing(0f, 1.4f);
                    textView.setTextIsSelectable(true);
                    blockView = textView;
                }
                if (i > 0) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.topMargin = gap;
                    blockView.setLayoutParams(lp);
                }
                messageBody.addView(blockView);
            }
            if (message.isStreaming()
                    && (blocks.isEmpty()
                    || blocks.get(blocks.size() - 1).type != MarkdownParser.TYPE_TEXT)) {
                // 流式输出停在代码块里时，补一个游标提示仍在生成
                TextView cursor = new TextView(context);
                cursor.setText(TYPING_CURSOR.trim());
                cursor.setTextSize(15);
                cursor.setTextColor(context.getResources().getColor(R.color.text_primary));
                messageBody.addView(cursor);
            }
        }

        /** 流式轻量布局：不解析 Markdown，只建两个 TextView。 */
        private void bindStreamingLayout(ChatMessage message) {
            if (!TextUtils.isEmpty(message.getThinking())) {
                messageBody.addView(buildThinkingView(message));
            }
            TextView content = new TextView(context);
            content.setTextSize(15);
            content.setTextColor(context.getResources().getColor(R.color.text_primary));
            content.setLineSpacing(0f, 1.4f);
            // 流式过程中不做文本选择，避免与频繁 setText 抢主线程
            content.setTextIsSelectable(false);
            content.setText(message.getContent() + TYPING_CURSOR);
            messageBody.addView(content);
            streamContent = content;
            streamingLayoutBound = true;
        }

        /**
         * 增量更新流式内容：只 setText，不重建任何 View。
         *
         * @return true 表示已就地更新；false 表示需要走一次完整绑定
         */
        boolean updateStreamingContent(ChatMessage message, String text, String thinking) {
            if (!streamingLayoutBound || boundMessage != message) {
                return false;
            }
            if (!TextUtils.isEmpty(thinking) && streamThinkingBody == null) {
                return false; // 思考块是后来才出现的，需要重建结构
            }
            if (streamThinkingBody != null) {
                streamThinkingBody.setText(thinkingTail(thinking));
            }
            if (streamContent != null) {
                streamContent.setText(text + TYPING_CURSOR);
            }
            return true;
        }

        /** 思考过程块：可点击折叠，正文小字号 + 浅色 + 浅灰底。 */
        private View buildThinkingView(final ChatMessage message) {
            float density = context.getResources().getDisplayMetrics().density;
            LinearLayout root = new LinearLayout(context);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            // 还没有正式回答时默认展开，避免看起来「没有输出」
            final boolean expanded = message.isThinkingExpanded()
                    || TextUtils.isEmpty(message.getContent());

            TextView header = new TextView(context);
            header.setText(expanded
                    ? context.getString(R.string.chat_thinking_expanded)
                    : context.getString(R.string.chat_thinking_collapsed));
            header.setTextSize(12);
            header.setTextColor(context.getResources().getColor(R.color.text_secondary));
            header.setPadding(0, (int) (2 * density + 0.5f), 0, (int) (4 * density + 0.5f));
            header.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    message.setThinkingExpanded(!expanded);
                    int position = getBindingAdapterPosition();
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position, PAYLOAD_TEXT);
                    }
                }
            });
            root.addView(header);

            if (expanded) {
                TextView body = new TextView(context);
                body.setText(message.isStreaming()
                        ? thinkingTail(message.getThinking())
                        : message.getThinking());
                body.setTextSize(12);
                body.setTextColor(context.getResources().getColor(R.color.text_hint));
                body.setLineSpacing(0f, 1.35f);
                if (message.isStreaming()) {
                    // 流式输出时限定行数与字符数：高度不再无限增长，气泡不会被顶出屏幕，
                    // 并且始终显示最新的内容（相当于自动跟随）
                    body.setMaxLines(THINKING_STREAM_MAX_LINES);
                    streamThinkingBody = body;
                }
                int padH = (int) (8 * density + 0.5f);
                int padV = (int) (6 * density + 0.5f);
                body.setPadding(padH, padV, padH, padV);
                body.setBackgroundResource(R.drawable.bg_thinking_block);
                // 流式输出中不做文本选择，避免与频繁 setText 抢主线程
                body.setTextIsSelectable(!message.isStreaming());
                root.addView(body);
            }
            return root;
        }
    }

    /** 流式期间只保留思考过程末尾若干字符，避免超长文本反复测量。 */
    private static String thinkingTail(String thinking) {
        if (thinking == null) {
            return "";
        }
        if (thinking.length() <= THINKING_STREAM_MAX_CHARS) {
            return thinking;
        }
        return "…" + thinking.substring(thinking.length() - THINKING_STREAM_MAX_CHARS);
    }
}
