package thick2.nhom1.smartfiremonitoring;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Fragment Quản lý thông báo và cảnh báo (Snooze).
 * Đọc/Ghi dữ liệu vào node "alert/" trên Firebase.
 */
public class NotificationFragment extends Fragment {
    /**
     * Fragment Quản lý cảnh báo:
     * - Bật/tắt thông báo
     * - Thiết lập snooze tạm thời
     * - Hiển thị thời gian cảnh báo gần nhất
     */

    // Tham chiếu đến nhánh "alert" trên Firebase
    private DatabaseReference alertRef;
    private ValueEventListener alertListener;

    // View Components
    private SwitchMaterial switchAlertEnabled;
    private TextInputEditText etSnoozeDuration;
    private MaterialButton btnSnooze;
    private MaterialButton btnResetSnooze;
    private TextView tvCountdown;
    private TextView tvLastTriggered;

    // Bộ đếm thời gian Snooze (chạy ngầm trên UI thread để update giao diện)
    private Handler countdownHandler = new Handler(Looper.getMainLooper());
    private Runnable countdownRunnable;
    private long currentSnoozeUntil = 0;
    
    // Cờ báo hiệu đang load switch từ DB, để không bị đè sự kiện OnCheckedChange của User
    private boolean isUpdatingSwitch = false;

    public NotificationFragment() {
        // Required empty constructor
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        // Inflate layout và ánh xạ toàn bộ view của màn hình cảnh báo
        View view = inflater.inflate(R.layout.fragment_notification, container, false);

        // Khởi tạo Database Reference
        alertRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system/alert");

        // Ánh xạ View
        switchAlertEnabled = view.findViewById(R.id.switchAlertEnabled);
        etSnoozeDuration = view.findViewById(R.id.etSnoozeDuration);
        btnSnooze = view.findViewById(R.id.btnSnooze);
        btnResetSnooze = view.findViewById(R.id.btnResetSnooze);
        tvCountdown = view.findViewById(R.id.tvCountdown);
        tvLastTriggered = view.findViewById(R.id.tvLastTriggered);

        setupListeners();
        listenAlertData();

        return view;
    }

    /**
     * Lắng nghe sự kiện từ giao diện người dùng
     */
    private void setupListeners() {
        // Listener cho switch, nút snooze và nút reset snooze
        // 1. Khi bật/tắt Switch cảnh báo
        switchAlertEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingSwitch) return; // Nếu là code tự set thì bỏ qua
            
            // Ghi trạng thái trực tiếp lên Firebase
            alertRef.child("enabled").setValue(isChecked)
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        });

        // 2. Khi nhấn nút "Tạm Tắt Cảnh Báo"
        btnSnooze.setOnClickListener(v -> {
            String durationStr = etSnoozeDuration.getText().toString().trim();
            if (TextUtils.isEmpty(durationStr)) {
                Toast.makeText(getContext(), "Vui lòng nhập số phút", Toast.LENGTH_SHORT).show();
                return;
            }

            int minutes;
            try {
                minutes = Integer.parseInt(durationStr);
                if (minutes <= 0) {
                    Toast.makeText(getContext(), "Thời gian phải lớn hơn 0", Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(getContext(), "Thời gian không hợp lệ", Toast.LENGTH_SHORT).show();
                return;
            }

            // Tính toán thời điểm kết thúc snooze (tính bằng giây Unix Timestamp)
            long nowSeconds = System.currentTimeMillis() / 1000;
            long snoozeUntil = nowSeconds + (minutes * 60L);

            // Ghi 3 giá trị cần thiết lên Firebase
            Map<String, Object> updates = new HashMap<>();
            updates.put("snoozed", true);
            updates.put("snooze_until", snoozeUntil);
            updates.put("snooze_duration_min", minutes);

            alertRef.updateChildren(updates)
                    .addOnSuccessListener(unused -> Toast.makeText(getContext(), "Đã tạm tắt cảnh báo " + minutes + " phút", Toast.LENGTH_SHORT).show())
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        });

        // 3. Khi nhấn nút Reset Snooze: xóa toàn bộ trạng thái tạm tắt cảnh báo
        btnResetSnooze.setOnClickListener(v -> {
            Map<String, Object> updates = new HashMap<>();
            updates.put("snoozed", false);
            updates.put("snooze_until", 0);
            updates.put("snooze_duration_min", 0);

            alertRef.updateChildren(updates)
                    .addOnSuccessListener(unused -> {
                        stopCountdown();
                        etSnoozeDuration.setText("10");
                        Toast.makeText(getContext(), "Đã reset tạm tắt cảnh báo", Toast.LENGTH_SHORT).show();
                    })
                    .addOnFailureListener(e -> Toast.makeText(getContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());
        });
    }

    /**
     * Lắng nghe cấu hình/thời gian từ Firebase theo Realtime
     */
    private void listenAlertData() {
        // Lắng nghe dữ liệu alert từ Firebase để đồng bộ giao diện theo thời gian thực
        alertListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) return;

                // --- 1. Trạng thái Enabled chung của hệ thống ---
                Boolean enabled = snapshot.child("enabled").getValue(Boolean.class);
                isUpdatingSwitch = true;
                switchAlertEnabled.setChecked(enabled != null ? enabled : true);
                isUpdatingSwitch = false;

                // --- 2. Trạng thái Tạm tắt (Snooze) ---
                Boolean snoozed = snapshot.child("snoozed").getValue(Boolean.class);
                Long snoozeUntil = snapshot.child("snooze_until").getValue(Long.class);
                
                // Nếu đang Snooze và thời gian cài đặt hợp lệ -> Kích hoạt bộ đếm ngược
                if (Boolean.TRUE.equals(snoozed) && snoozeUntil != null && snoozeUntil > 0) {
                    currentSnoozeUntil = snoozeUntil;
                    startCountdown();
                } else {
                    currentSnoozeUntil = 0;
                    stopCountdown(); // Nếu không Snooze hoặc đã hết hạn thì tắt UI đếm ngược
                }

                // --- 3. Lần cháy gần nhất (Lịch sử) ---
                Long lastTriggered = snapshot.child("last_triggered").getValue(Long.class);
                if (lastTriggered != null && lastTriggered > 0) {
                    // Chú ý: Firebase lưu timestamp theo Giây, class Date của Java yêu cầu Mili-giây
                    Date date = new Date(lastTriggered * 1000);
                    SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                    tvLastTriggered.setText(sdf.format(date));
                } else {
                    tvLastTriggered.setText("Chưa có dữ liệu");
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(getContext(), "Lỗi đọc dữ liệu: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };
        alertRef.addValueEventListener(alertListener);
    }

    /**
     * Kích hoạt Handler đếm ngược thời gian Snooze trên màn hình
     */
    private void startCountdown() {
        // Bắt đầu đếm ngược số phút snooze còn lại trên UI
        stopCountdown(); // Dừng bộ đếm cũ nếu đang chạy
        tvCountdown.setVisibility(View.VISIBLE); // Hiện dòng chữ đếm ngược

        countdownRunnable = new Runnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis() / 1000;
                long remaining = currentSnoozeUntil - now;

                if (remaining > 0) {
                    // Nếu vẫn còn thời gian, phân tích ra Phút và Giây
                    long minutes = remaining / 60;
                    long seconds = remaining % 60;
                    tvCountdown.setText(String.format(Locale.getDefault(), "⏱️ Đang tạm tắt: còn %d:%02d", minutes, seconds));
                    
                    // Lặp lại hàm run() này sau 1000ms (1 giây)
                    countdownHandler.postDelayed(this, 1000);
                } else {
                    // Khi hết giờ Snooze
                    tvCountdown.setText("⏱️ Đang tạm tắt: còn 0:00");
                    
                    // Theo logic, ESP32 sẽ quét và tự set snoozed = false khi hết giờ.
                    // Nhưng để an toàn (và phòng trường hợp ESP32 mất mạng), App cũng hỗ trợ set lại Firebase:
                    alertRef.child("snoozed").setValue(false);
                    alertRef.child("snooze_until").setValue(0);
                    
                    stopCountdown();
                }
            }
        };
        countdownHandler.post(countdownRunnable);
    }

    /**
     * Dừng vòng lặp đếm ngược và ẩn dòng chữ
     */
    private void stopCountdown() {
        // Dừng runnable đếm ngược và ẩn dòng hiển thị thời gian
        if (countdownRunnable != null) {
            countdownHandler.removeCallbacks(countdownRunnable);
            countdownRunnable = null;
        }
        tvCountdown.setVisibility(View.GONE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Gỡ listener và dừng handler khi rời màn hình để tránh rò rỉ bộ nhớ
        // Hủy Handler đếm ngược và Listener khi rời màn hình để giải phóng bộ nhớ
        stopCountdown();
        if (alertRef != null && alertListener != null) {
            alertRef.removeEventListener(alertListener);
        }
    }
}
