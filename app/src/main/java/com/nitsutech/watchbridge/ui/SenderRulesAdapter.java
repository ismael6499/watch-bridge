package com.nitsutech.watchbridge.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.AppDatabase;
import com.nitsutech.watchbridge.data.SenderRuleEntity;

import java.util.ArrayList;
import java.util.List;

public class SenderRulesAdapter extends RecyclerView.Adapter<SenderRulesAdapter.VH> {

    public interface Listener {
        void onEditRule(SenderRuleEntity rule);
        void onDeleteRule(SenderRuleEntity rule);
    }

    private final List<SenderRuleEntity> items = new ArrayList<>();
    private final Listener listener;

    public SenderRulesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<SenderRuleEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_sender_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        SenderRuleEntity r = items.get(position);
        Context ctx = h.itemView.getContext();
        String title = (r.isVip ? "⭐ " : "") + r.senderOrChatName;
        h.tvTitle.setText(title);

        String appScope = "*".equals(r.packageFilter)
                ? ctx.getString(R.string.scope_all_apps)
                : (AppDatabase.PKG_TEAMS.equals(r.packageFilter) ? "Teams" : (AppDatabase.PKG_WHATSAPP.equals(r.packageFilter) ? "WhatsApp" : r.packageFilter));

        String chatType = r.chatTypeFilter == 1
                ? ctx.getString(R.string.chat_type_direct)
                : (r.chatTypeFilter == 2 ? ctx.getString(R.string.chat_type_group) : ctx.getString(R.string.chat_type_both));

        String action = r.actionMode == 0
                ? ctx.getString(R.string.rule_action_repeat_fmt, r.repeatIntervalSec)
                : (r.actionMode == 1 ? ctx.getString(R.string.rule_action_once) : ctx.getString(R.string.rule_action_mute));

        h.tvDetails.setText(appScope + " • " + chatType + " • " + action + " • " + ctx.getString(R.string.cooling_fmt, r.cooldownSec));
        h.itemView.setOnClickListener(v -> listener.onEditRule(r));
        h.btnDelete.setOnClickListener(v -> listener.onDeleteRule(r));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvDetails;
        final ImageButton btnDelete;

        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_sender_rule_name);
            tvDetails = itemView.findViewById(R.id.tv_sender_rule_details);
            btnDelete = itemView.findViewById(R.id.btn_delete_sender_rule);
        }
    }
}
