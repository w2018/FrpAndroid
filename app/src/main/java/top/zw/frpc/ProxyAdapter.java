package top.zw.frpc;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class ProxyAdapter extends RecyclerView.Adapter<ProxyAdapter.ViewHolder> {
    private final List<ProxyItem> items;
    private final OnDeleteListener deleteListener;
    private final OnEditListener editListener;
    private long lastClickTime = 0;
    private static final long DOUBLE_CLICK_TIME = 400;
    private boolean isRunning = false;

    public interface OnDeleteListener {
        void onDelete(int position);
    }

    public interface OnEditListener {
        void onEdit(int position);
    }

    public void setRunning(boolean running) {
        this.isRunning = running;
        notifyDataSetChanged();
    }

    public ProxyAdapter(List<ProxyItem> items, OnDeleteListener deleteListener, OnEditListener editListener) {
        this.items = items;
        this.deleteListener = deleteListener;
        this.editListener = editListener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_proxy, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ProxyItem item = items.get(position);
        holder.tvName.setText(item.getName());
        holder.tvDetail.setText(item.toDetail());

        // Status badge
        holder.tvBadge.setText(item.getStatusText());
        GradientDrawable bg = (GradientDrawable) holder.tvBadge.getBackground();
        bg.setColor(item.getStatusColor());
        bg.invalidateSelf();

        // Hide delete button and disable edit when running
        holder.btnDelete.setVisibility(isRunning ? View.GONE : View.VISIBLE);
        holder.btnDelete.setOnClickListener(v -> {
            if (!isRunning && deleteListener != null) deleteListener.onDelete(position);
        });

        // Disable edit when running
        holder.itemView.setOnClickListener(v -> {
            if (isRunning) return;
            long now = System.currentTimeMillis();
            if (now - lastClickTime < DOUBLE_CLICK_TIME) {
                if (editListener != null) editListener.onEdit(position);
                lastClickTime = 0;
            } else {
                lastClickTime = now;
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvBadge, tvName, tvDetail;
        ImageButton btnDelete;

        ViewHolder(View v) {
            super(v);
            tvBadge = v.findViewById(R.id.tv_status_badge);
            tvName = v.findViewById(R.id.tv_name);
            tvDetail = v.findViewById(R.id.tv_detail);
            btnDelete = v.findViewById(R.id.btn_delete);
        }
    }
}
