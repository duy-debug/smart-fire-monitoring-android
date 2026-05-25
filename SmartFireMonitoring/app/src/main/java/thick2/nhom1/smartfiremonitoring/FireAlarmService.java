package thick2.nhom1.smartfiremonitoring;

import android.app.ActivityManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.List;

public class FireAlarmService extends Service {

    private static final String TAG = "FireAlarmService";
    private static final String FIRE_CHANNEL_SOUND_ID = "fire_alarm_channel_v2_sound";
    private static final String FIRE_CHANNEL_SILENT_ID = "fire_alarm_channel_v2_silent";
    private static final String FOREGROUND_CHANNEL_ID = "fire_monitor_foreground_channel";
    private static final int FIRE_NOTIFICATION_ID = 1;
    private static final int FOREGROUND_NOTIFICATION_ID = 999;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Handler repeatingHandler = new Handler(Looper.getMainLooper());
    private final Handler overlayCloseHandler = new Handler(Looper.getMainLooper());
    private final Handler overlayReopenHandler = new Handler(Looper.getMainLooper());

    private DatabaseReference rootRef;
    private ValueEventListener fireListener;
    private FirebaseAuth.AuthStateListener authStateListener;
    private BroadcastReceiver overlayDismissReceiver;

    private Runnable repeatingRunnable;
    private Runnable overlayCloseRunnable;
    private Runnable overlayReopenRunnable;

    private boolean isListening = false;
    private boolean fireAlertShown = false;
    private boolean overlayCloseScheduled = false;
    private boolean overlayReopenScheduled = false;
    private boolean isFireDetected = false;
    private boolean currentAlertEnabled = true;
    private boolean currentSnoozedActive = false;

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
        Log.d(TAG, "Service created");
        ensureNotificationChannels();
        startForegroundServiceNotification();

        rootRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system");
        setupAuthListener();
        registerOverlayDismissReceiver();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelOverlayClose();
        cancelOverlayReopen();
        stopRepeatingNotification();
        closeFireAlertOverlay();

        if (authStateListener != null) {
            FirebaseAuth.getInstance().removeAuthStateListener(authStateListener);
            authStateListener = null;
        }

        if (overlayDismissReceiver != null) {
            try {
                unregisterReceiver(overlayDismissReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            overlayDismissReceiver = null;
        }

        if (rootRef != null && fireListener != null && isListening) {
            rootRef.removeEventListener(fireListener);
            isListening = false;
        }
    }

    private void setupAuthListener() {
        authStateListener = firebaseAuth -> {
            if (firebaseAuth.getCurrentUser() != null) {
                if (!isListening) {
                    listenForFire();
                    isListening = true;
                }
            } else if (isListening && rootRef != null && fireListener != null) {
                rootRef.removeEventListener(fireListener);
                isListening = false;
            }
        };
        FirebaseAuth.getInstance().addAuthStateListener(authStateListener);
    }

    private void listenForFire() {
        fireListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    return;
                }

                Boolean fire = snapshot.child("system/fire_detected").getValue(Boolean.class);
                isFireDetected = fire != null && fire;

                String direction = snapshot.child("sensors/flame/direction").getValue(String.class);
                Double temp = snapshot.child("sensors/dht11/temperature").getValue(Double.class);
                Integer mq2Value = snapshot.child("sensors/mq2/value").getValue(Integer.class);
                if (direction != null) currentDirection = direction;
                if (temp != null) currentTemp = temp;
                if (mq2Value != null) currentMq2Value = mq2Value;
                currentPattern = buildFlamePattern(snapshot);

                Boolean alertEnabled = snapshot.child("alert/enabled").getValue(Boolean.class);
                currentAlertEnabled = alertEnabled == null || alertEnabled;

                Boolean snoozed = snapshot.child("alert/snoozed").getValue(Boolean.class);
                Long snoozeUntil = snapshot.child("alert/snooze_until").getValue(Long.class);
                boolean snoozeActive = false;
                if (Boolean.TRUE.equals(snoozed) && snoozeUntil != null) {
                    long now = System.currentTimeMillis() / 1000L;
                    snoozeActive = now < snoozeUntil;
                }
                currentSnoozedActive = snoozeActive;

                boolean appInForeground = isAppInForeground();
                if (isFireDetected && currentAlertEnabled && !currentSnoozedActive) {
                    cancelOverlayClose();
                    cancelOverlayReopen();

                    if (appInForeground) {
                        if (!fireAlertShown) {
                            showFireAlertOverlay();
                            fireAlertShown = true;
                        }
                    } else {
                        if (fireAlertShown) {
                            closeFireAlertOverlay();
                        }
                        fireAlertShown = false;
                    }

                    startRepeatingNotification(appInForeground);
                } else if (isFireDetected) {
                    cancelOverlayClose();
                    cancelOverlayReopen();
                    fireAlertShown = false;
                    closeFireAlertOverlay();
                    stopRepeatingNotification();
                    cancelFireNotification();
                } else {
                    cancelOverlayReopen();
                    stopRepeatingNotification();
                    cancelFireNotification();
                    scheduleOverlayClose();
                }

                Boolean notificationSent = snapshot.child("alert/notification_sent").getValue(Boolean.class);
                if (Boolean.TRUE.equals(notificationSent)) {
                    resetNotificationFlag();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Firebase listen cancelled: " + error.getMessage());
            }
        };

        rootRef.addValueEventListener(fireListener);
    }

    private void startRepeatingNotification(boolean allowFullScreen) {
        if (repeatingRunnable != null) {
            return;
        }

        repeatingRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isFireDetected) {
                    stopRepeatingNotification();
                    return;
                }

                String tempStr = (currentTemp > 0) ? String.valueOf(currentTemp) : "--";
                String body = "Phát hiện cháy hướng " + currentDirection + ". Nhiệt độ: " + tempStr + "°C";
                sendNotification("CẢNH BÁO CHÁY KHẨN CẤP!", body, allowFullScreen);
                repeatingHandler.postDelayed(this, 1500L);
            }
        };

        repeatingHandler.post(repeatingRunnable);
    }

    private void stopRepeatingNotification() {
        if (repeatingRunnable != null) {
            repeatingHandler.removeCallbacks(repeatingRunnable);
            repeatingRunnable = null;
        }
    }

    private void cancelFireNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancel(FIRE_NOTIFICATION_ID);
        }
    }

    private void resetNotificationFlag() {
        if (rootRef != null) {
            rootRef.child("alert/notification_sent").setValue(false);
        }
    }

    private void showFireAlertOverlay() {
        if (!isAppInForeground()) {
            return;
        }

        mainHandler.post(() -> {
            Intent intent = new Intent(this, FireAlertActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(FireAlertActivity.EXTRA_TEMPERATURE, currentTemp);
            intent.putExtra(FireAlertActivity.EXTRA_MQ2_VALUE, currentMq2Value);
            intent.putExtra(FireAlertActivity.EXTRA_DIRECTION, currentDirection);
            intent.putExtra(FireAlertActivity.EXTRA_PATTERN, currentPattern);
            startActivity(intent);
        });
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

    private void scheduleOverlayReopen() {
        if (overlayReopenScheduled) {
            return;
        }

        overlayReopenScheduled = true;
        overlayReopenRunnable = () -> {
            overlayReopenScheduled = false;
            if (isFireDetected && currentAlertEnabled && !currentSnoozedActive && !fireAlertShown && isAppInForeground()) {
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

    private void sendNotification(String title, String body, boolean allowFullScreen) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        Uri fireSoundUri = getFireSoundUri();
        String channelId = allowFullScreen ? FIRE_CHANNEL_SILENT_ID : FIRE_CHANNEL_SOUND_ID;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioAttributes audioAttributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();

            NotificationChannel channel = new NotificationChannel(
                    channelId,
                    allowFullScreen ? "Cảnh báo cháy (im lặng)" : "Cảnh báo cháy",
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription("Kênh thông báo khẩn cấp khi phát hiện cháy");
            channel.enableLights(true);
            channel.setLightColor(Color.RED);
            channel.enableVibration(true);
            channel.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
            if (!allowFullScreen) {
                channel.setSound(fireSoundUri, audioAttributes);
            } else {
                channel.setSound(null, null);
            }
            notificationManager.createNotificationChannel(channel);
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVibrate(new long[]{0, 1000, 500, 1000, 500, 1000})
                .setContentIntent(contentIntent);

        if (!allowFullScreen) {
            builder.setSound(fireSoundUri);
        }

        if (allowFullScreen) {
            Intent alertIntent = new Intent(this, FireAlertActivity.class);
            alertIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent fullScreenIntent = PendingIntent.getActivity(
                    this,
                    1,
                    alertIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.setFullScreenIntent(fullScreenIntent, true);
        }

        notificationManager.notify(FIRE_NOTIFICATION_ID, builder.build());
    }

    private void startForegroundServiceNotification() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    FOREGROUND_CHANNEL_ID,
                    "Giám sát hệ thống cháy",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Dịch vụ nền để liên tục giám sát trạng thái cháy");
            notificationManager.createNotificationChannel(channel);
        }

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Hệ thống giám sát cháy")
                .setContentText("Đang chạy ngầm để sẵn sàng cảnh báo...")
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                    FOREGROUND_NOTIFICATION_ID,
                    builder.build(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            );
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, builder.build());
        }
    }

    private void ensureNotificationChannels() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }

        Uri fireSoundUri = getFireSoundUri();
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        NotificationChannel soundChannel = new NotificationChannel(
                FIRE_CHANNEL_SOUND_ID,
                "Cảnh báo cháy",
                NotificationManager.IMPORTANCE_HIGH
        );
        soundChannel.setDescription("Kênh thông báo khẩn cấp khi phát hiện cháy");
        soundChannel.enableLights(true);
        soundChannel.setLightColor(Color.RED);
        soundChannel.enableVibration(true);
        soundChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
        soundChannel.setSound(fireSoundUri, audioAttributes);
        notificationManager.createNotificationChannel(soundChannel);

        NotificationChannel silentChannel = new NotificationChannel(
                FIRE_CHANNEL_SILENT_ID,
                "Cảnh báo cháy (im lặng)",
                NotificationManager.IMPORTANCE_HIGH
        );
        silentChannel.setDescription("Kênh hiển thị dialog khi ứng dụng đang mở");
        silentChannel.enableLights(true);
        silentChannel.setLightColor(Color.RED);
        silentChannel.enableVibration(true);
        silentChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000, 500, 1000});
        silentChannel.setSound(null, null);
        notificationManager.createNotificationChannel(silentChannel);

        NotificationChannel foregroundChannel = new NotificationChannel(
                FOREGROUND_CHANNEL_ID,
                "Giám sát hệ thống cháy",
                NotificationManager.IMPORTANCE_LOW
        );
        foregroundChannel.setDescription("Dịch vụ nền để liên tục giám sát trạng thái cháy");
        notificationManager.createNotificationChannel(foregroundChannel);
    }

    private Uri getFireSoundUri() {
        return Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.tieng_coi_bao_chay);
    }

    private boolean isAppInForeground() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return false;
        }

        List<ActivityManager.RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return false;
        }

        String packageName = getPackageName();
        for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
            if (processInfo != null
                    && packageName.equals(processInfo.processName)
                    && processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                return true;
            }
        }
        return false;
    }
}
