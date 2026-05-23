package thick2.nhom1.smartfiremonitoring;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Description;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Fragment Lịch sử:
 * - Đọc logs cháy từ Firebase
 * - Hiển thị bằng RecyclerView
 * - Chuyển qua lại giữa danh sách và biểu đồ
 */
public class HistoryFragment extends Fragment {

    private DatabaseReference rootRef;
    private DatabaseReference logsRef;
    private DatabaseReference thresholdsRef;
    private Query logsQuery;

    private ValueEventListener logsListener;
    private ValueEventListener thresholdsListener;

    private RecyclerView recyclerHistory;
    private LineChart chartHistory;
    private TextView tvHistoryEmpty;
    private TextView tvHistoryCount;
    private MaterialButtonToggleGroup toggleHistoryView;
    private MaterialButton btnHistoryList;
    private MaterialButton btnHistoryChart;

    private FireEventAdapter adapter;
    private final ArrayList<FireEvent> historyEvents = new ArrayList<>();

    private boolean showingChart = false;
    private float tempWarning = 50f;
    private int mq2Warning = 1500;

    public HistoryFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate layout và khởi tạo toàn bộ thành phần hiển thị lịch sử
        View view = inflater.inflate(R.layout.fragment_history, container, false);

        rootRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system");
        logsRef = rootRef.child("logs");
        thresholdsRef = rootRef.child("thresholds");

        bindViews(view);
        setupRecyclerView();
        setupChart();
        setupToggleGroup();
        listenThresholds();
        listenLogs();
        showListView();

        return view;
    }

    private void bindViews(View view) {
        // Ánh xạ các view trong XML sang biến Java
        recyclerHistory = view.findViewById(R.id.recyclerHistory);
        chartHistory = view.findViewById(R.id.chartHistory);
        tvHistoryEmpty = view.findViewById(R.id.tvHistoryEmpty);
        tvHistoryCount = view.findViewById(R.id.tvHistoryCount);
        toggleHistoryView = view.findViewById(R.id.toggleHistoryView);
        btnHistoryList = view.findViewById(R.id.btnHistoryList);
        btnHistoryChart = view.findViewById(R.id.btnHistoryChart);
    }

    private void setupRecyclerView() {
        // RecyclerView dùng để hiển thị danh sách sự kiện cháy mới nhất
        adapter = new FireEventAdapter();
        recyclerHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerHistory.setAdapter(adapter);
        recyclerHistory.setHasFixedSize(true);
    }

    private void setupChart() {
        // Cấu hình biểu đồ đường cho nhiệt độ và MQ-2
        chartHistory.setNoDataText("Chưa có sự kiện cháy");
        chartHistory.setDrawGridBackground(false);
        chartHistory.setDragEnabled(true);
        chartHistory.setScaleEnabled(true);
        chartHistory.setPinchZoom(true);
        chartHistory.getAxisRight().setEnabled(true);

        Description description = new Description();
        description.setText("");
        chartHistory.setDescription(description);

        chartHistory.getLegend().setEnabled(true);

        XAxis xAxis = chartHistory.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setGranularity(1f);
        xAxis.setDrawGridLines(false);
        xAxis.setTextColor(Color.parseColor("#64748B"));
        xAxis.setTextSize(10f);
        xAxis.setLabelRotationAngle(-35f);

        YAxis leftAxis = chartHistory.getAxisLeft();
        leftAxis.setTextColor(Color.parseColor("#EF4444"));
        leftAxis.setAxisMinimum(0f);

        YAxis rightAxis = chartHistory.getAxisRight();
        rightAxis.setTextColor(Color.parseColor("#10B981"));
        rightAxis.setAxisMinimum(0f);
        rightAxis.setDrawGridLines(false);
    }

    private void setupToggleGroup() {
        // Bắt sự kiện chuyển tab giữa danh sách và biểu đồ
        toggleHistoryView.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) {
                return;
            }

            if (checkedId == R.id.btnHistoryChart) {
                showChartView();
            } else {
                showListView();
            }
        });
    }

    private void listenLogs() {
        // Lấy tối đa 50 log mới nhất từ Firebase
        logsQuery = logsRef.orderByChild("timestamp").limitToLast(50);
        logsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                ArrayList<FireEvent> ascendingEvents = new ArrayList<>();
                for (DataSnapshot child : snapshot.getChildren()) {
                    FireEvent event = parseFireEvent(child);
                    if (event != null) {
                        ascendingEvents.add(event);
                    }
                }

                Collections.sort(ascendingEvents, Comparator.comparingLong(FireEvent::getTimestampSafe));

                historyEvents.clear();
                historyEvents.addAll(ascendingEvents);

                ArrayList<FireEvent> newestFirst = new ArrayList<>(ascendingEvents);
                Collections.reverse(newestFirst);
                adapter.submitList(newestFirst);

                tvHistoryCount.setText(newestFirst.size() + " sự kiện");
                tvHistoryEmpty.setVisibility(newestFirst.isEmpty() ? View.VISIBLE : View.GONE);

                renderChart(ascendingEvents);
                updateContentVisibility();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                tvHistoryEmpty.setVisibility(View.VISIBLE);
                tvHistoryEmpty.setText("Không thể tải lịch sử từ Firebase");
            }
        };
        logsQuery.addValueEventListener(logsListener);
    }

    private FireEvent parseFireEvent(DataSnapshot child) {
        if (child == null || !child.exists()) {
            return null;
        }

        FireEvent event = new FireEvent();

        Long timestamp = child.child("timestamp").getValue(Long.class);
        if (timestamp == null) {
            Integer timestampInt = child.child("timestamp").getValue(Integer.class);
            event.timestamp = timestampInt != null ? timestampInt.longValue() : 0L;
        } else {
            event.timestamp = timestamp;
        }

        event.time_readable = child.child("time_readable").getValue(String.class);

        Double temp = child.child("temperature").getValue(Double.class);
        if (temp == null) {
            Float tempFloat = child.child("temperature").getValue(Float.class);
            event.temperature = tempFloat != null ? tempFloat.doubleValue() : 0d;
        } else {
            event.temperature = temp;
        }

        Double humidity = child.child("humidity").getValue(Double.class);
        if (humidity == null) {
            Float humidityFloat = child.child("humidity").getValue(Float.class);
            event.humidity = humidityFloat != null ? humidityFloat.doubleValue() : 0d;
        } else {
            event.humidity = humidity;
        }

        Integer mq2Value = child.child("mq2_value").getValue(Integer.class);
        if (mq2Value == null) {
            Long mq2Long = child.child("mq2_value").getValue(Long.class);
            event.mq2_value = mq2Long != null ? mq2Long.intValue() : 0;
        } else {
            event.mq2_value = mq2Value;
        }

        event.mq2_level = child.child("mq2_level").getValue(String.class);
        event.flame_direction = child.child("flame_direction").getValue(String.class);
        event.flame_pattern = child.child("flame_pattern").getValue(String.class);
        event.action_taken = child.child("action_taken").getValue(String.class);

        Long resolvedAt = child.child("resolved_at").getValue(Long.class);
        if (resolvedAt == null) {
            Integer resolvedAtInt = child.child("resolved_at").getValue(Integer.class);
            event.resolved_at = resolvedAtInt != null ? resolvedAtInt.longValue() : 0L;
        } else {
            event.resolved_at = resolvedAt;
        }

        return event;
    }

    private void listenThresholds() {
        // Lấy ngưỡng cảnh báo hiện tại để kẻ line giới hạn trên biểu đồ
        thresholdsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
        Double temp = snapshot.child("temp_warning").getValue(Double.class);
        Integer mq2 = snapshot.child("mq2_warning").getValue(Integer.class);

        if (temp != null) {
            tempWarning = temp.floatValue();
        }
                if (mq2 != null) {
                    mq2Warning = mq2;
                }

                renderChart(new ArrayList<>(historyEvents));
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        thresholdsRef.addValueEventListener(thresholdsListener);
    }

    private void renderChart(List<FireEvent> ascendingEvents) {
        // Vẽ dữ liệu sự kiện cháy lên biểu đồ nhiệt độ và MQ-2
        if (ascendingEvents == null || ascendingEvents.isEmpty()) {
            chartHistory.clear();
            chartHistory.invalidate();
            return;
        }

        ArrayList<Entry> tempEntries = new ArrayList<>();
        ArrayList<Entry> mq2Entries = new ArrayList<>();
        ArrayList<String> labels = new ArrayList<>();

        for (int i = 0; i < ascendingEvents.size(); i++) {
            FireEvent event = ascendingEvents.get(i);
            tempEntries.add(new Entry(i, (float) event.getTemperature()));
            mq2Entries.add(new Entry(i, event.getMq2Value()));
            labels.add(event.getChartTimeLabel());
        }

        LineDataSet tempSet = new LineDataSet(tempEntries, "Nhiệt độ (°C)");
        tempSet.setColor(Color.parseColor("#EF4444"));
        tempSet.setCircleColor(Color.parseColor("#EF4444"));
        tempSet.setCircleRadius(3.5f);
        tempSet.setLineWidth(2.2f);
        tempSet.setDrawValues(false);
        tempSet.setAxisDependency(YAxis.AxisDependency.LEFT);
        tempSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        LineDataSet mq2Set = new LineDataSet(mq2Entries, "MQ-2");
        mq2Set.setColor(Color.parseColor("#10B981"));
        mq2Set.setCircleColor(Color.parseColor("#10B981"));
        mq2Set.setCircleRadius(3.5f);
        mq2Set.setLineWidth(2.2f);
        mq2Set.setDrawValues(false);
        mq2Set.setAxisDependency(YAxis.AxisDependency.RIGHT);
        mq2Set.setMode(LineDataSet.Mode.CUBIC_BEZIER);

        LineData data = new LineData(tempSet, mq2Set);
        chartHistory.setData(data);

        XAxis xAxis = chartHistory.getXAxis();
        xAxis.setValueFormatter(new IndexAxisValueFormatter(labels));
        xAxis.setLabelCount(Math.min(6, labels.size()), false);

        YAxis leftAxis = chartHistory.getAxisLeft();
        leftAxis.removeAllLimitLines();
        LimitLine tempLimit = new LimitLine(tempWarning, "temp_warning");
        tempLimit.enableDashedLine(10f, 8f, 0f);
        tempLimit.setLineColor(Color.parseColor("#F87171"));
        tempLimit.setTextColor(Color.parseColor("#F87171"));
        leftAxis.addLimitLine(tempLimit);

        YAxis rightAxis = chartHistory.getAxisRight();
        rightAxis.removeAllLimitLines();
        LimitLine mq2Limit = new LimitLine(mq2Warning, "mq2_warning");
        mq2Limit.enableDashedLine(10f, 8f, 0f);
        mq2Limit.setLineColor(Color.parseColor("#34D399"));
        mq2Limit.setTextColor(Color.parseColor("#34D399"));
        rightAxis.addLimitLine(mq2Limit);

        chartHistory.notifyDataSetChanged();
        chartHistory.invalidate();
    }

    private void showListView() {
        // Chế độ hiển thị danh sách sự kiện
        showingChart = false;
        recyclerHistory.setVisibility(View.VISIBLE);
        chartHistory.setVisibility(View.GONE);
        tvHistoryEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }

    private void showChartView() {
        // Chế độ hiển thị biểu đồ
        showingChart = true;
        recyclerHistory.setVisibility(View.GONE);
        chartHistory.setVisibility(View.VISIBLE);
        tvHistoryEmpty.setVisibility(View.GONE);
        renderChart(new ArrayList<>(historyEvents));
    }

    private void updateContentVisibility() {
        // Đồng bộ hiển thị giữa danh sách, biểu đồ và trạng thái rỗng
        if (showingChart) {
            chartHistory.setVisibility(View.VISIBLE);
            recyclerHistory.setVisibility(View.GONE);
            tvHistoryEmpty.setVisibility(View.GONE);
        } else {
            chartHistory.setVisibility(View.GONE);
            recyclerHistory.setVisibility(View.VISIBLE);
            tvHistoryEmpty.setVisibility(adapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Gỡ listener Firebase khi fragment bị huỷ để tránh leak bộ nhớ
        if (logsQuery != null && logsListener != null) {
            logsQuery.removeEventListener(logsListener);
        }
        if (thresholdsRef != null && thresholdsListener != null) {
            thresholdsRef.removeEventListener(thresholdsListener);
        }
    }
}
