package thick2.nhom1.smartfiremonitoring;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import android.app.Service;
import android.os.IBinder;
import androidx.annotation.Nullable;

public class FireAlarmService extends Service {

    private static final String TAG = "FireAlarmService";
    private static final String CHANNEL_ID = "fire_alarm_channel_v2";

    private DatabaseReference rootRef;
    private ValueEventListener fireListener;
    private FirebaseAuth.AuthStateListener authStateListener;
    private boolean isListening = false;
    private boolean fireAlertShown = false;
    private boolean overlayCloseScheduled = false;
    private boolean overlayReopenScheduled = false;
    
    // Các trường phục vụ báo động liên tục
    private final Handler repeatingHandler = new Handler(Looper.getMainLooper());
    private final Handler overlayCloseHandler = new Handler(Looper.getMainLooper());
    private final Handler overlayReopenHandler = new Handler(Looper.getMainLooper());
    private Runnable repeatingRunnable;
    private Runnable overlayCloseRunnable;
    private Runnable overlayReopenRunnable;
    private boolean isFireDetected = false;
    private boolean currentAlertEnabled = true;
    private boolean currentSnoozedActive = false;
    private BroadcastReceiver overlayDismissReceiver;
    private String currentDirection = "không xác định";
    private int currentMq2Value = -1;
    private String currentPattern = "--";
    private double currentTemp = 0.0;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "FireAlarmService Created - Đang chạy ngầm lắng nghe Firebase");
        
        // Kích hoạt Foreground Service để giữ kết nối Firebase RTDB ngay cả khi đóng App
        startForegroundServiceNotification();

        // Trỏ vào gốc để lắng nghe cả system/, alert/ và sensors/
        rootRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system");
        
        // Lắng nghe trạng thái Auth để bắt đầu lắng nghe DB an toàn sau khi đăng nhập thành công
        setupAuthListener();
        registerOverlayDismissReceiver();
    }

    private void listenForFire() {
        Log.d(TAG, "listenForFire() - Đăng ký lắng nghe sự kiện cháy!");
        fireListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Log.d(TAG, "onDataChange() - Nhận thay đổi dữ liệu từ Firebase!");
                if (!snapshot.exists()) {
                    Log.d(TAG, "onDataChange() - Snapshot không tồn tại!");
                    return;
                }

                // 1. Đọc trạng thái cháy tổng quát từ Firebase
                Boolean fire = snapshot.child("system/fire_detected").getValue(Boolean.class);
                isFireDetected = (fire != null && fire);
                Log.d(TAG, "onDataChange() - system/fire_detected = " + isFireDetected);

                // 2. Đọc thêm thông tin hướng lửa và nhiệt độ để cập nhật nội dung thông báo liên tục
                String direction = snapshot.child("sensors/flame/direction").getValue(String.class);
                Double temp = snapshot.child("sensors/dht11/temperature").getValue(Double.class);
                Integer mq2Value = snapshot.child("sensors/mq2/value").getValue(Integer.class);
                if (direction != null) currentDirection = direction;
                if (temp != null) currentTemp = temp;
                if (mq2Value != null) currentMq2Value = mq2Value;

                currentPattern = buildFlamePattern(snapshot);

                // 3. Kiểm tra xem cấu hình có đang bật cảnh báo không
                Boolean alertEnabled = snapshot.child("alert/enabled").getValue(Boolean.class);
                boolean isAlertEnabled = (alertEnabled == null || alertEnabled);
                currentAlertEnabled = isAlertEnabled;
                Log.d(TAG, "onDataChange() - alert/enabled = " + isAlertEnabled);

                // 4. Kiểm tra tính năng Tạm tắt (Snooze)
                Boolean snoozed = snapshot.child("alert/snoozed").getValue(Boolean.class);
                Long snoozeUntil = snapshot.child("alert/snooze_until").getValue(Long.class);
                boolean isSnoozedActive = false;
                if (Boolean.TRUE.equals(snoozed) && snoozeUntil != null) {
                    long now = System.currentTimeMillis() / 1000;
                    if (now < snoozeUntil) {
                        isSnoozedActive = true;
                    }
                }
                currentSnoozedActive = isSnoozedActive;
                Log.d(TAG, "onDataChange() - alert/snoozed = " + snoozed + ", isSnoozedActive = " + isSnoozedActive);

                // Quyết định phát chuông báo liên tục hoặc tắt
                if (isFireDetected && isAlertEnabled && !isSnoozedActive) {
                    Log.d(TAG, "onDataChange() - Phát hiện cháy và cảnh báo được phép -> Bắt đầu réo chuông liên tục!");
                    cancelOverlayClose();
                    cancelOverlayReopen();
                    if (!fireAlertShown) {
                        showFireAlertOverlay();
                        fireAlertShown = true;
                    }
                    startRepeatingNotification();
                } else if (isFireDetected) {
                    Log.d(TAG, "onDataChange() - Có cháy nhưng cảnh báo bị tắt/snooze -> Đóng overlay ngay!");
                    cancelOverlayClose();
                    cancelOverlayReopen();
                    fireAlertShown = false;
                    closeFireAlertOverlay();
                    stopRepeatingNotification();
                    cancelFireNotification();
                } else {
                    Log.d(TAG, "onDataChange() - Hết cháy -> Chờ 5 giây rồi đóng overlay!");
                    cancelOverlayReopen();
                    stopRepeatingNotification();
                    cancelFireNotification();
                    scheduleOverlayClose();
                }

                // Luôn dọn dẹp cờ notification_sent của ESP32 nếu nó được bật lên
                Boolean notificationSent = snapshot.child("alert/notification_sent").getValue(Boolean.class);
                if (notificationSent != null && notificationSent) {
                    Log.d(TAG, "onDataChange() - Reset cờ notification_sent của ESP32 về false.");
                    resetNotificationFlag();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Lỗi đọc Firebase (Service): " + error.getMessage());
            }
        };
        
        rootRef.addValueEventListener(fireListener);
    }

    /**
     * Bắt đầu lặp thông báo réo chuông liên tục mỗi 1.5 giây
     */
    private void startRepeatingNotification() {
        if (repeatingRunnable != null) return; // Đang chạy rồi, không tạo thêm trùng lặp

        repeatingRunnable = new Runnable() {
            @Override
            public void run() {
                if (isFireDetected) {
                    String tempStr = (currentTemp > 0) ? String.valueOf(currentTemp) : "--";
                    String body = "Phát hiện cháy hướng " + currentDirection + ". Nhiệt độ: " + tempStr + "°C";
                    
                    Log.d(TAG, "Đang đẩy thông báo lặp lại: " + body);
                    sendNotification("🔥 CẢNH BÁO CHÁY KHẨN CẤP!", body);

                    // Lặp lại sau mỗi 1.5 giây cho khẩn cấp cực độ
                    repeatingHandler.postDelayed(this, 1500);
                } else {
                    stopRepeatingNotification();
                }
            }
        };
        repeatingHandler.post(repeatingRunnable);
    }

    /**
     * Dừng lặp thông báo lập tức
     */
    private void stopRepeatingNotification() {
        if (repeatingRunnable != null) {
            repeatingHandler.removeCallbacks(repeatingRunnable);
            repeatingRunnable = null;
            Log.d(TAG, "Đã dừng lặp thông báo báo động.");
        }
    }

    /**
     * Hủy notification cảnh báo cháy đang hiển thị để âm thanh heads-up không còn kéo dài sau khi hết cháy.
     */
    private void cancelFireNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(1);
        }
    }

    /**
     * Hàm phụ trợ reset lại cờ notification_sent trên Firebase về false
     */
    private void resetNotificationFlag() {
        if (rootRef != null) {
            rootRef.child("alert/notification_sent").setValue(false);
        }
    }

    private void showFireAlertOverlay() {
        Intent intent = new Intent(this, FireAlertActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(FireAlertActivity.EXTRA_TEMPERATURE, currentTemp);
        intent.putExtra(FireAlertActivity.EXTRA_MQ2_VALUE, currentMq2Value);
        intent.putExtra(FireAlertActivity.EXTRA_DIRECTION, currentDirection);
        intent.putExtra(FireAlertActivity.EXTRA_PATTERN, currentPattern);
        startActivity(intent);
    }

    private void closeFireAlertOverlay() {
        sendBroadcast(new Intent(FireAlertActivity.ACTION_CLOSE));
    }

    private void registerOverlayDismissReceiver() {
        overlayDismissReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!FireAlertActivity.ACTION_USER_DISMISS.equals(intent.getAction())) {
                    return;
                }

                Log.d(TAG, "onReceive() - Người dùng vừa tắt overlay thủ công.");
                if (isFireDetected && currentAlertEnabled && !currentSnoozedActive) {
                    fireAlertShown = false;
                    scheduleOverlayReopen();
                }
            }
        };

        IntentFilter filter = new IntentFilter(FireAlertActivity.ACTION_USER_DISMISS);
        ContextCompat.registerReceiver(this, overlayDismissReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void scheduleOverlayClose() {
        if (overlayCloseScheduled) {
            return;
        }
        overlayCloseScheduled = true;
        overlayCloseRunnable = () -> {
            overlayCloseScheduled = false;
            if (!isFireDetected) {
                fireAlertShown = false;
                closeFireAlertOverlay();
            }
        };
        overlayCloseHandler.postDelayed(overlayCloseRunnable, 5000L);
    }

    private void cancelOverlayClose() {
        if (overlayCloseRunnable != null) {
            overlayCloseHandler.removeCallbacks(overlayCloseRunnable);
            overlayCloseRunnable = null;
        }
        overlayCloseScheduled = false;
    }

    private String buildFlamePattern(@NonNull DataSnapshot snapshot) {
        StringBuilder pattern = new StringBuilder(5);
        for (int i = 1; i <= 5; i++) {
            Integer value = snapshot.child("sensors/flame/eye_" + i).getValue(Integer.class);
            if (value == null) {
                Long valueLong = snapshot.child("sensors/flame/eye_" + i).getValue(Long.class);
                value = valueLong != null ? valueLong.intValue() : 0;
            }
            pattern.append(value != null && value == 1 ? '1' : '0');
        }
        return pattern.toString();
    }

    private void scheduleOverlayReopen() {
        if (overlayReopenScheduled) {
            return;
        }
        overlayReopenScheduled = true;
        overlayReopenRunnable = () -> {
            overlayReopenScheduled = false;
            if (isFireDetected && currentAlertEnabled && !currentSnoozedActive && !fireAlertShown) {
                showFireAlertOverlay();
                fireAlertShown = true;
            }
        };
        overlayReopenHandler.postDelayed(overlayReopenRunnable, 800L);
    }

    private void cancelOverlayReopen() {
        if (overlayReopenRunnable != null) {
            overlayReopenHandler.removeCallbacks(overlayReopenRunnable);
            overlayReopenRunnable = null;
        }
        overlayReopenScheduled = false;
    }

    private void setupAuthListener() {
        authStateListener = firebaseAuth -> {
            if (firebaseAuth.getCurrentUser() != null) {
                Log.d(TAG, "FirebaseAuth đã sẵn sàng -> Đăng ký lắng nghe sự kiện cháy.");
                if (!isListening) {
                    listenForFire();
                    isListening = true;
                }
            } else {
                Log.d(TAG, "FirebaseAuth chưa sẵn sàng hoặc đã đăng xuất.");
                if (isListening && fireListener != null) {
                    rootRef.removeEventListener(fireListener);
                    isListening = false;
                }
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Dừng lặp thông báo nếu đang chạy ngầm
        cancelOverlayClose();
        cancelOverlayReopen();
        closeFireAlertOverlay();
        stopRepeatingNotification();
        // Hủy đăng ký AuthStateListener
        if (authStateListener != null) {
            FirebaseAuth.getInstance().removeAuthStateListener(authStateListener);
        }
        if (overlayDismissReceiver != null) {
            try {
                unregisterReceiver(overlayDismissReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            overlayDismissReceiver = null;
        }
        // Xóa listener để tránh leak memory nếu Service bị ngắt
        if (rootRef != null && fireListener != null && isListening) {
            rootRef.removeEventListener(fireListener);
            isListening = false;
        }
    }

    /**
     * Tạo và đẩy Notification lên thanh thông báo của điện thoại
     */
    private void sendNotification(String title, String body) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
        if (defaultSoundUri == null) {
            defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
        }

        // Tạo Notification Channel (Bắt buộc cho Android 8.0 trở lên)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Cảnh báo cháy",
                    NotificationManager.IMPORTANCE_HIGH // Mức độ quan trọng cao nhất
            );
            channel.setDescription("Kênh thông báo khẩn cấp khi phát hiện cháy");
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(true);
            // Mẫu rung mạnh và dài (Nghỉ 0ms, Rung 1000ms, Nghỉ 500ms, Rung 1000ms...)
            channel.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
            channel.setSound(defaultSoundUri, audioAttributes);
            notificationManager.createNotificationChannel(channel);
        }

        // Tạo Intent để khi bấm vào Notification sẽ mở lại màn hình chính của App
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        Intent fireIntent = new Intent(this, FireAlertActivity.class);
        fireIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullScreenIntent = PendingIntent.getActivity(this, 1, fireIntent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher) // Dùng tạm icon app
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setSound(defaultSoundUri)
                .setPriority(NotificationCompat.PRIORITY_HIGH) // Hiển thị dạng Pop-up ngay trên màn hình (Heads-up)
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000}) 
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(fullScreenIntent, true);

        // ID = 1 cố định để các cảnh báo mới ghi đè lên cảnh báo cũ, không làm ngập lụt thanh thông báo
        notificationManager.notify(1, notificationBuilder.build());
    }

    private static final String FOREGROUND_CHANNEL_ID = "fire_monitor_foreground_channel";
    private static final int FOREGROUND_NOTIFICATION_ID = 999;

    /**
     * Khởi chạy thông báo chạy ngầm bắt buộc để duy trì Foreground Service
     */
    private void startForegroundServiceNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "Giám sát hệ thống cháy",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Dịch vụ chạy ngầm để liên tục giám sát trạng thái cháy");
            notificationManager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Hệ thống giám sát cháy")
                .setContentText("Đang chạy ngầm để sẵn sàng cảnh báo...")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        // Bắt đầu Foreground Service với Type phù hợp Android 14+ (targetSdk 34+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, builder.build(), 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, builder.build());
        }
    }
}
