package com.doudou.x.ui.chat;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.doudou.x.R;
import com.doudou.x.model.Conversation;

import java.util.ArrayList;
import java.util.List;

/**
 * 侧边栏历史对话列表适配器。
 */
public class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder> {

    public interface OnConversationClickListener {
        void onClick(Conversation conversation);

        void onLongClick(Conversation conversation);
    }

    private final List<Conversation> conversations = new ArrayList<>();
    private final OnConversationClickListener listener;
    private String selectedId;

    public HistoryAdapter(OnConversationClickListener listener) {
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
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener != null) {
                    listener.onLongClick(conversation);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    static class HistoryViewHolder extends RecyclerView.ViewHolder {

        final TextView tvTitle;

        HistoryViewHolder(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tvConversationTitle);
        }
    }
}
