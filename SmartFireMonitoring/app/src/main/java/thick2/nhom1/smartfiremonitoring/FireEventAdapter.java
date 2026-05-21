package thick2.nhom1.smartfiremonitoring;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class FireEventAdapter extends RecyclerView.Adapter<FireEventAdapter.FireEventViewHolder> {

    private final List<FireEvent> items = new ArrayList<>();

    public void submitList(List<FireEvent> newItems) {
        // Nhận danh sách mới từ fragment và làm mới RecyclerView
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FireEventViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_fire_event, parent, false);
        return new FireEventViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FireEventViewHolder holder, int position) {
        // Đổ dữ liệu của từng sự kiện cháy vào card hiển thị
        FireEvent event = items.get(position);

        holder.tvTime.setText("🔥 " + event.getDisplayTime());
        holder.tvTemperature.setText(String.format(Locale.getDefault(),
                "Nhiệt: %.1f°C | MQ2: %d", event.getTemperature(), event.getMq2Value()));
        holder.tvDirection.setText("Hướng: " + event.getFlameDirection());
        holder.tvPattern.setText("Pattern: " + event.getFlamePattern());
        holder.tvAction.setText("Xử lý: " + event.getActionTaken());
        holder.tvDuration.setText("Thời gian: " + event.getProcessingDuration());
        String level = event.getMq2Level().toLowerCase(Locale.getDefault());
        holder.tvLevel.setText(level.toUpperCase(Locale.getDefault()));

        int levelColor;
        int levelTextColor;
        switch (level) {
            case "danger":
                levelColor = Color.parseColor("#FEE2E2");
                levelTextColor = Color.parseColor("#B91C1C");
                holder.cardRoot.setStrokeColor(Color.parseColor("#EF4444"));
                break;
            case "warning":
                levelColor = Color.parseColor("#FEF3C7");
                levelTextColor = Color.parseColor("#B45309");
                holder.cardRoot.setStrokeColor(Color.parseColor("#F59E0B"));
                break;
            default:
                levelColor = Color.parseColor("#DCFCE7");
                levelTextColor = Color.parseColor("#166534");
                holder.cardRoot.setStrokeColor(Color.parseColor("#10B981"));
                break;
        }
        holder.tvLevel.setBackgroundTintList(ColorStateList.valueOf(levelColor));
        holder.tvLevel.setTextColor(levelTextColor);

        if ("manual".equalsIgnoreCase(event.getActionTaken())) {
            holder.tvAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#E9D5FF")));
            holder.tvAction.setTextColor(Color.parseColor("#6B21A8"));
        } else {
            holder.tvAction.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#DBEAFE")));
            holder.tvAction.setTextColor(Color.parseColor("#1D4ED8"));
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class FireEventViewHolder extends RecyclerView.ViewHolder {
        MaterialCardView cardRoot;
        TextView tvTime;
        TextView tvLevel;
        TextView tvTemperature;
        TextView tvDirection;
        TextView tvPattern;
        TextView tvAction;
        TextView tvDuration;

        FireEventViewHolder(@NonNull View itemView) {
            super(itemView);
            cardRoot = itemView.findViewById(R.id.cardEventRoot);
            tvTime = itemView.findViewById(R.id.tvEventTime);
            tvLevel = itemView.findViewById(R.id.tvEventLevel);
            tvTemperature = itemView.findViewById(R.id.tvEventTemperature);
            tvDirection = itemView.findViewById(R.id.tvEventDirection);
            tvPattern = itemView.findViewById(R.id.tvEventPattern);
            tvAction = itemView.findViewById(R.id.tvEventAction);
            tvDuration = itemView.findViewById(R.id.tvEventDuration);
        }
    }
}
