package com.doudou.x.ui.chat;

import android.content.Context;
import android.graphics.Typeface;
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

    /** 流式更新最后一条 AI 消息的内容。 */
    public void updateLastMessage(String text, boolean streaming) {
        if (messages.isEmpty()) {
            return;
        }
        int lastIndex = messages.size() - 1;
        ChatMessage last = messages.get(lastIndex);
        last.setContent(text);
        last.setStreaming(streaming);
        notifyItemChanged(lastIndex, PAYLOAD_TEXT);
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

    static class MessageViewHolder extends RecyclerView.ViewHolder {

        private final Context context;
        private final TextView tvMessage;
        private final LinearLayout messageBody;

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
                return;
            }

            // AI 消息：按 Markdown 结构渲染（文本 + 标题 + 列表 + 代码块 + 表格）
            messageBody.removeAllViews();
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
    }
}
