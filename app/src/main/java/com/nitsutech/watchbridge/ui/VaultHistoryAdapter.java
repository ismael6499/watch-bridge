package com.nitsutech.watchbridge.ui;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.ChatMessageEntity;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class VaultHistoryAdapter extends RecyclerView.Adapter<VaultHistoryAdapter.VH> {

    public interface Listener {
        void onDeleteItem(ChatMessageEntity item);
    }

    private final List<ChatMessageEntity> items = new ArrayList<>();
    private final Listener listener;

    public VaultHistoryAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<ChatMessageEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_vault_message, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        ChatMessageEntity m = items.get(position);
        Context ctx = h.itemView.getContext();

        String timeStr = DateFormat.format("HH:mm:ss", new Date(m.timestamp)).toString();
        StringBuilder badge = new StringBuilder();
        badge.append(m.appLabel).append(" • ");
        if (m.isGroup) {
            badge.append("[").append(m.conversationTitle).append("] ");
        }
        badge.append(m.senderName).append(" • ").append(timeStr);
        if (m.isDeletedBySender) {
            badge.append(" • 🗑️ ").append(ctx.getString(R.string.deleted_tag));
        } else if (m.isAfterHoursMuted) {
            badge.append(" • 🌙 ").append(ctx.getString(R.string.after_hours_tag));
        } else if (m.wasCoalesced) {
            badge.append(" • ❄️ ").append(ctx.getString(R.string.coalesced_tag));
        }

        h.tvHeader.setText(badge.toString());
        h.tvBody.setText(m.messageText);
        h.btnDelete.setOnClickListener(v -> listener.onDeleteItem(m));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvHeader;
        final TextView tvBody;
        final ImageButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvHeader = itemView.findViewById(R.id.tv_vault_header);
            tvBody = itemView.findViewById(R.id.tv_vault_body);
            btnDelete = itemView.findViewById(R.id.btn_delete_vault_item);
        }
    }
}
