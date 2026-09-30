package com.doudou.x.ui.chat;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.doudou.x.R;
import com.doudou.x.model.Conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 侧边栏历史对话列表适配器。
 * 点击条目进入对话，右侧两个按钮分别用于重命名与删除（不再使用长按）。
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    public interface OnConversationActionListener {
        void onClick(Conversation conversation);

        void onRename(Conversation conversation);

        void onDelete(Conversation conversation);
    }

    private final List<Conversation> conversations = new ArrayList<>();
    private final OnConversationActionListener listener;
    private String selectedId;

    public HistoryAdapter(OnConversationActionListener listener) {
        this.listener = listener;
    }

    public void setConversations(List<Conversation> list) {
        conversations.clear();
        if (list != null) {
            conversations.addAll(list);
        }
        notifyDataSetChanged();
    }

    public void setSelectedId(String id) {
        selectedId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public HistoryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_conversation, parent, false);
        return new HistoryViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HistoryViewHolder holder, int position) {
        final Conversation conversation = conversations.get(position);
        String title = conversation.getTitle();
        if (TextUtils.isEmpty(title)) {
            title = holder.itemView.getContext().getString(R.string.chat_title_default);
        }
        holder.tvTitle.setText(title);
        holder.itemView.setSelected(conversation.getId().equals(selectedId));

        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onClick(conversation);
                }
            }
        });
        holder.btnRename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onRename(conversation);
                }
            }
        });
        holder.btnDelete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDelete(conversation);
                }
            }
        });
        // 明确取消长按响应
        holder.itemView.setOnLongClickListener(null);
        holder.itemView.setLongClickable(false);
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {

        final TextView tvTitle;
        final ImageButton btnRename;
        final ImageButton btnDelete;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvConversationTitle);
            btnRename = itemView.findViewById(R.id.btnRenameConversation);
            btnDelete = itemView.findViewById(R.id.btnDeleteConversation);
        }
    }
}
