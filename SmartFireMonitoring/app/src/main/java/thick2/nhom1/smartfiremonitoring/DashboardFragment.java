package thick2.nhom1.smartfiremonitoring;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

/**
 * Fragment Trang chủ:
 * - Hiển thị dữ liệu cảm biến realtime
 * - Theo dõi trạng thái kết nối internet của điện thoại
 * - Theo dõi ESP32 online/offline dựa trên last_seen
 */
public class DashboardFragment extends Fragment {

    private static final long ESP32_OFFLINE_THRESHOLD_SECONDS = 10L;
    private static final long ESP32_CHECK_INTERVAL_MS = 5000L;

    private DatabaseReference databaseRef;

    private TextView tvTemp;
    private TextView tvHumidity;
    private TextView tvMq2Value;
    private TextView tvMq2Level;
    private TextView tvDirection;
    private TextView tvPump;
    private TextView tvBuzzer;
    private TextView tvServoX;
    private TextView tvServoY;
    private TextView tvStatus;
    private TextView tvFirmwareVersion;
    private View statusDot;

    private View bannerConnection;
    private View bannerAlert;
    private final View[] flameEyes = new View[5];
    private CardView mq2Card;

    private BroadcastReceiver connectivityReceiver;
    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private Runnable statusRunnable;

    private long lastSeenTimestamp = 0L;
    private String firmwareVersion = "--";

    public DashboardFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate layout dashboard và ánh xạ các view hiển thị dữ liệu realtime
        View view = inflater.inflate(R.layout.fragment_dashboard, container, false);

        databaseRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system");

        tvTemp = view.findViewById(R.id.tvTemp);
        tvHumidity = view.findViewById(R.id.tvHumidity);
        tvMq2Value = view.findViewById(R.id.tvMq2Value);
        tvMq2Level = view.findViewById(R.id.tvMq2Level);
        tvDirection = view.findViewById(R.id.tvDirection);
        tvPump = view.findViewById(R.id.tvPump);
        tvBuzzer = view.findViewById(R.id.tvBuzzer);
        tvServoX = view.findViewById(R.id.tvServoX);
        tvServoY = view.findViewById(R.id.tvServoY);
        tvStatus = view.findViewById(R.id.tvStatus);
        tvFirmwareVersion = view.findViewById(R.id.tvFirmwareVersion);
        statusDot = view.findViewById(R.id.viewStatusDot);

        bannerConnection = view.findViewById(R.id.bannerConnection);
        bannerAlert = view.findViewById(R.id.bannerAlert);
        mq2Card = view.findViewById(R.id.mq2Card);

        flameEyes[0] = view.findViewById(R.id.eye1);
        flameEyes[1] = view.findViewById(R.id.eye2);
        flameEyes[2] = view.findViewById(R.id.eye3);
        flameEyes[3] = view.findViewById(R.id.eye4);
        flameEyes[4] = view.findViewById(R.id.eye5);

        listenFirebase();
        updateConnectivityBanner();
        startEsp32StatusMonitor();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerConnectivityReceiver();
        startEsp32StatusMonitor();
        updateConnectivityBanner();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityReceiver();
        stopEsp32StatusMonitor();
    }

    private void listenFirebase() {
        // Nghe toàn bộ node gốc để UI tự cập nhật khi Firebase có dữ liệu mới hoặc dữ liệu cache
        databaseRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Đọc nhiệt độ và độ ẩm từ DHT11
                Double temp = snapshot.child("sensors/dht11/temperature").getValue(Double.class);
                Double hum = snapshot.child("sensors/dht11/humidity").getValue(Double.class);
                tvTemp.setText("🌡 Nhiệt độ: " + valueOrPlaceholder(temp, "--") + "°C");
                tvHumidity.setText("💧 Độ ẩm: " + valueOrPlaceholder(hum, "--") + "%");

                // Đọc MQ-2
                Integer mq2Value = snapshot.child("sensors/mq2/value").getValue(Integer.class);
                String level = snapshot.child("sensors/mq2/level").getValue(String.class);
                if (level == null) {
                    level = "unknown";
                }
                tvMq2Value.setText("💨 MQ-2: " + valueOrPlaceholder(mq2Value, "--"));
                tvMq2Level.setText("Mức: " + level.toUpperCase());

                if ("safe".equals(level)) {
                    mq2Card.setCardBackgroundColor(Color.parseColor("#4CAF50"));
                } else if ("warning".equals(level)) {
                    mq2Card.setCardBackgroundColor(Color.parseColor("#FFC107"));
                } else if ("danger".equals(level)) {
                    mq2Card.setCardBackgroundColor(Color.parseColor("#F44336"));
                }

                // Đọc 5 mắt lửa
                for (int i = 1; i <= 5; i++) {
                    Integer val = snapshot.child("sensors/flame/eye_" + i).getValue(Integer.class);
                    if (val != null && val == 1) {
                        flameEyes[i - 1].setBackgroundResource(R.drawable.eye_status_red);
                    } else {
                        flameEyes[i - 1].setBackgroundResource(R.drawable.eye_status_green);
                    }
                }

                // Hướng cháy
                String direction = snapshot.child("sensors/flame/direction").getValue(String.class);
                tvDirection.setText("Hướng: " + (direction != null ? direction : "--"));

                // Bơm và còi
                Boolean pump = snapshot.child("actuators/pump").getValue(Boolean.class);
                Boolean buzzer = snapshot.child("actuators/buzzer").getValue(Boolean.class);
                tvPump.setText("Bơm: " + boolToStatus(pump));
                tvBuzzer.setText("Còi: " + boolToStatus(buzzer));

                // Góc servo
                Integer servoX = snapshot.child("actuators/servo/axis_x").getValue(Integer.class);
                Integer servoY = snapshot.child("actuators/servo/axis_y").getValue(Integer.class);
                tvServoX.setText("Servo X: " + valueOrPlaceholder(servoX, "--") + "°");
                tvServoY.setText("Servo Y: " + valueOrPlaceholder(servoY, "--") + "°");

                // Cảnh báo cháy
                Boolean fire = snapshot.child("system/fire_detected").getValue(Boolean.class);
                bannerAlert.setVisibility(Boolean.TRUE.equals(fire) ? View.VISIBLE : View.GONE);

                // Firmware version
                String firmware = snapshot.child("system/firmware_version").getValue(String.class);
                firmwareVersion = firmware != null ? firmware : "--";
                tvFirmwareVersion.setText("FW: " + firmwareVersion);

                // last_seen của ESP32: nguồn chính để xác định online/offline
                Long lastSeen = snapshot.child("system/last_seen").getValue(Long.class);
                if (lastSeen != null) {
                    lastSeenTimestamp = lastSeen;
                }

                updateEsp32StatusUi();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(getContext(), "Mất kết nối Firebase", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void startEsp32StatusMonitor() {
        // Theo dõi ESP32 mỗi 5 giây để báo Online/Offline chính xác hơn
        if (statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
        }

        statusRunnable = new Runnable() {
            @Override
            public void run() {
                updateEsp32StatusUi();
                statusHandler.postDelayed(this, ESP32_CHECK_INTERVAL_MS);
            }
        };
        statusHandler.post(statusRunnable);
    }

    private void stopEsp32StatusMonitor() {
        if (statusRunnable != null) {
            statusHandler.removeCallbacks(statusRunnable);
            statusRunnable = null;
        }
    }

    private void updateEsp32StatusUi() {
        if (tvStatus == null || statusDot == null) {
            return;
        }

        if (lastSeenTimestamp <= 0L) {
            // Nếu chưa có last_seen hợp lệ thì coi như offline để tránh hiển thị xanh giả
            tvStatus.setText(" Thiết bị: Offline");
            tvStatus.setTextColor(Color.parseColor("#F44336"));
            statusDot.setBackgroundResource(R.drawable.eye_status_red);
            return;
        }

        long now = System.currentTimeMillis() / 1000;
        long diff = now - lastSeenTimestamp;
        boolean online = diff <= ESP32_OFFLINE_THRESHOLD_SECONDS;

        if (online) {
            tvStatus.setText(" Thiết bị: Online");
            tvStatus.setTextColor(Color.parseColor("#4CAF50"));
            statusDot.setBackgroundResource(R.drawable.eye_status_green);
        } else {
            tvStatus.setText(" Thiết bị: Offline");
            tvStatus.setTextColor(Color.parseColor("#F44336"));
            statusDot.setBackgroundResource(R.drawable.eye_status_red);
        }
    }

    private void updateConnectivityBanner() {
        if (bannerConnection == null || getContext() == null) {
            return;
        }

        // Kiểm tra mạng điện thoại để biết app đang online hay đang dùng dữ liệu cache
        ConnectivityManager cm = requireContext().getSystemService(ConnectivityManager.class);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        boolean isOnline = info != null && info.isConnected();

        bannerConnection.setVisibility(isOnline ? View.GONE : View.VISIBLE);
    }

    private void registerConnectivityReceiver() {
        if (connectivityReceiver != null || getContext() == null) {
            return;
        }

        // Lắng nghe thay đổi mạng để banner tự ẩn/hiện ngay khi điện thoại mất hoặc có internet
        connectivityReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateConnectivityBanner();
            }
        };

        IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
        requireContext().registerReceiver(connectivityReceiver, filter);
    }

    private void unregisterConnectivityReceiver() {
        if (connectivityReceiver == null || getContext() == null) {
            return;
        }

        try {
            requireContext().unregisterReceiver(connectivityReceiver);
        } catch (IllegalArgumentException ignored) {
        }
        connectivityReceiver = null;
    }

    private String boolToStatus(Boolean value) {
        if (value == null) {
            return "--";
        }
        return value ? "ON" : "OFF";
    }

    private String valueOrPlaceholder(Object value, String placeholder) {
        return value != null ? String.valueOf(value) : placeholder;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopEsp32StatusMonitor();
        unregisterConnectivityReceiver();
    }
}
