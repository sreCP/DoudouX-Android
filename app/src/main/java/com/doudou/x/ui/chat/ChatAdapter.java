package com.doudou.x.ui.chat;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.doudou.x.R;
import com.doudou.x.model.ChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 聊天消息列表适配器（用户 / AI 两种气泡）。
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    /** 局部刷新 payload：仅更新文本，避免整条 View 重建造成闪烁。 */
    public static final Object PAYLOAD_TEXT = new Object();

    /** 长按 AI 消息（用于查看原始返回）。 */
    public interface OnAiMessageLongClickListener {
        void onAiMessageLongClick(ChatMessage message);
    }

    private static final String TYPING_CURSOR = " ▍";

    private final List<ChatMessage> messages = new ArrayList<>();
    private OnAiMessageLongClickListener longClickListener;

    public void setOnAiMessageLongClickListener(OnAiMessageLongClickListener listener) {
        this.longClickListener = listener;
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
            holder.bindText(messages.get(position));
            return;
        }
        super.onBindViewHolder(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        final ChatMessage message = messages.get(position);
        holder.bindText(message);
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

    static class MessageViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvMessage;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvMessage = itemView.findViewById(R.id.tvMessage);
        }

        void bindText(ChatMessage message) {
            String text = message.getContent();
            if (message.isStreaming()) {
                text = text + TYPING_CURSOR;
            }
            tvMessage.setText(text);
        }
    }
}
