package thick2.nhom1.smartfiremonitoring;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
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

public class DashboardFragment extends Fragment {
    /**
     * Fragment Trang chủ:
     * - Lắng nghe toàn bộ dữ liệu từ Firebase
     * - Hiển thị cảm biến, trạng thái thiết bị, cảnh báo cháy
     * - Tự bật/tắt banner khi mất kết nối mạng
     */
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

    private View bannerConnection;
    private View bannerAlert;
    private final View[] flameEyes = new View[5];
    private CardView mq2Card;
    private BroadcastReceiver connectivityReceiver;

    public DashboardFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate layout và ánh xạ tất cả view cần hiển thị trên màn hình dashboard
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
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        registerConnectivityReceiver();
        updateConnectivityBanner();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterConnectivityReceiver();
    }

    private void listenFirebase() {
        // Đăng ký listener tổng để mỗi lần Firebase thay đổi thì UI tự cập nhật theo dữ liệu mới/cached
        databaseRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Đọc dữ liệu cảm biến nhiệt độ và độ ẩm từ Firebase.
                // Nếu dữ liệu bị thiếu thì hiển thị "--" để tránh crash.
                Double temp = snapshot.child("sensors/dht11/temperature").getValue(Double.class);
                Double hum = snapshot.child("sensors/dht11/humidity").getValue(Double.class);

                tvTemp.setText("🌡 Nhiệt độ: " + valueOrPlaceholder(temp, "--") + "°C");
                tvHumidity.setText("💧 Độ ẩm: " + valueOrPlaceholder(hum, "--") + "%");

                // Đọc mức khí gas từ MQ-2.
                // level có thể là safe / warning / danger / unknown.
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

                // Đọc 5 mắt lửa.
                // Nếu giá trị = 1 thì đổi sang đỏ, ngược lại để xanh.
                for (int i = 1; i <= 5; i++) {
                    Integer val = snapshot.child("sensors/flame/eye_" + i).getValue(Integer.class);
                    if (val != null && val == 1) {
                        flameEyes[i - 1].setBackgroundResource(R.drawable.eye_status_red);
                    } else {
                        flameEyes[i - 1].setBackgroundResource(R.drawable.eye_status_green);
                    }
                }

                // Hướng cháy chỉ hiển thị dữ liệu hướng, không dùng để kết luận online/offline.
                String direction = snapshot.child("sensors/flame/direction").getValue(String.class);
                tvDirection.setText("Hướng: " + (direction != null ? direction : "--"));

                // Bơm và còi đang được lưu dưới dạng Boolean trong Firebase.
                // true = ON, false = OFF.
                Boolean pump = snapshot.child("actuators/pump").getValue(Boolean.class);
                Boolean buzzer = snapshot.child("actuators/buzzer").getValue(Boolean.class);

                tvPump.setText("Bơm: " + boolToStatus(pump));
                tvBuzzer.setText("Còi: " + boolToStatus(buzzer));

                // Góc servo là số nguyên, nên đọc kiểu Integer.
                Integer servoX = snapshot.child("actuators/servo/axis_x").getValue(Integer.class);
                Integer servoY = snapshot.child("actuators/servo/axis_y").getValue(Integer.class);

                tvServoX.setText("Servo X: " + valueOrPlaceholder(servoX, "--") + "°");
                tvServoY.setText("Servo Y: " + valueOrPlaceholder(servoY, "--") + "°");

                // Cờ báo cháy dùng để hiển thị/ẩn banner cảnh báo cháy.
                Boolean fire = snapshot.child("system/fire_detected").getValue(Boolean.class);
                bannerAlert.setVisibility(Boolean.TRUE.equals(fire) ? View.VISIBLE : View.GONE);

                // Trạng thái online/offline được quyết định dựa trên last_seen.
                // Lưu ý: last_seen phải cùng đơn vị với now bên dưới.
                // Nếu Firebase lưu last_seen theo giây thì dùng System.currentTimeMillis()/1000.
                // Nếu Firebase lưu theo mili-giây thì đổi now sang System.currentTimeMillis().
                Long lastSeen = snapshot.child("system/last_seen").getValue(Long.class);
                if (lastSeen != null) {
                    long now = System.currentTimeMillis() / 1000;
                    boolean isOnline = now - lastSeen <= 15;
                    tvStatus.setText(isOnline ? " Thiết bị: Online" : " Thiết bị: Offline");
                    tvStatus.setTextColor(isOnline ? Color.parseColor("#4CAF50") : Color.parseColor("#F44336"));
                } else {
                    // Nếu chưa có last_seen thì chưa thể kết luận thiết bị online.
                    tvStatus.setText(" Thiết bị: --");
                    tvStatus.setTextColor(Color.GRAY);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(getContext(), "Mất kết nối Firebase", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateConnectivityBanner() {
        if (bannerConnection == null || getContext() == null) {
            return;
        }

        // Dùng ConnectivityManager để biết điện thoại đang online hay offline
        ConnectivityManager cm = requireContext().getSystemService(ConnectivityManager.class);
        NetworkInfo info = cm != null ? cm.getActiveNetworkInfo() : null;
        boolean isOnline = info != null && info.isConnected();

        bannerConnection.setVisibility(isOnline ? View.GONE : View.VISIBLE);
    }

    private void registerConnectivityReceiver() {
        if (connectivityReceiver != null || getContext() == null) {
            return;
        }

        // Lắng nghe thay đổi mạng để banner phản hồi ngay khi Wi-Fi/4G mất hoặc có lại
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
            // Receiver đã được unregister ở lifecycle khác.
        }
        connectivityReceiver = null;
    }

    private String boolToStatus(Boolean value) {
        if (value == null) {
            return "--";
        }
        // Chuyển Boolean trong Firebase thành trạng thái dễ đọc trên giao diện.
        return value ? "ON" : "OFF";
    }

    private String valueOrPlaceholder(Object value, String placeholder) {
        // Trả về giá trị dạng chuỗi hoặc placeholder nếu dữ liệu chưa có.
        return value != null ? String.valueOf(value) : placeholder;
    }
}
