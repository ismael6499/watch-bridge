package com.nitsutech.watchbridge.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.nitsutech.watchbridge.R;
import com.nitsutech.watchbridge.data.AppRuleEntity;

import java.util.ArrayList;
import java.util.List;

public class AppRulesAdapter extends RecyclerView.Adapter<AppRulesAdapter.VH> {

    public interface Listener {
        void onToggleEnabled(AppRuleEntity rule, boolean enabled);
        void onEditTimers(AppRuleEntity rule);
    }

    private final List<AppRuleEntity> items = new ArrayList<>();
    private final Listener listener;

    public AppRulesAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<AppRuleEntity> list) {
        items.clear();
        if (list != null) items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_rule, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AppRuleEntity item = items.get(position);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(item.appName);
        h.tvPkg.setText(item.packageName);

        String directSummary = ctx.getString(
                R.string.app_rule_direct_summary,
                item.directEnabled ? ctx.getString(R.string.state_on) : ctx.getString(R.string.state_off),
                item.directRepeatEnabled ? item.directRepeatIntervalSec + "s" : ctx.getString(R.string.once_label),
                item.directCooldownSec
        );
        String groupSummary = ctx.getString(
                R.string.app_rule_group_summary,
                item.groupEnabled ? ctx.getString(R.string.state_on) : ctx.getString(R.string.state_off),
                item.groupRepeatEnabled ? item.groupRepeatIntervalSec + "s" : ctx.getString(R.string.once_label),
                item.groupCooldownSec
        );
        String silentSummary = "";
        if (item.silentWatchMode == 1) {
            silentSummary = "\n" + ctx.getString(R.string.badge_silent_24h);
        } else if (item.silentWatchMode == 2) {
            String st = String.format(java.util.Locale.US, "%02d:%02d", item.silentStartHour, item.silentStartMin);
            String en = String.format(java.util.Locale.US, "%02d:%02d", item.silentEndHour, item.silentEndMin);
            silentSummary = "\n" + ctx.getString(R.string.badge_silent_sched_fmt, st, en);
        }
        h.tvSummary.setText(directSummary + "\n" + groupSummary + silentSummary);

        h.swEnabled.setOnCheckedChangeListener(null);
        h.swEnabled.setChecked(item.enabled);
        h.swEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggleEnabled(item, isChecked));
        h.btnEdit.setOnClickListener(v -> listener.onEditTimers(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView tvName;
        final TextView tvPkg;
        final TextView tvSummary;
        final SwitchMaterial swEnabled;
        final Button btnEdit;

        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_app_name);
            tvPkg = itemView.findViewById(R.id.tv_app_pkg);
            tvSummary = itemView.findViewById(R.id.tv_app_summary);
            swEnabled = itemView.findViewById(R.id.sw_app_enabled);
            btnEdit = itemView.findViewById(R.id.btn_edit_app_timers);
        }
    }
}
