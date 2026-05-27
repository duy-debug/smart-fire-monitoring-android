package thick2.nhom1.smartfiremonitoring;

import android.animation.ValueAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class FireAlertActivity extends AppCompatActivity {

    public static final String ACTION_CLOSE = "thick2.nhom1.smartfiremonitoring.ACTION_CLOSE_FIRE_ALERT";
    public static final String ACTION_USER_DISMISS = "thick2.nhom1.smartfiremonitoring.ACTION_USER_DISMISS_FIRE_ALERT";
    public static final String EXTRA_TEMPERATURE = "extra_temperature";
    public static final String EXTRA_MQ2_VALUE = "extra_mq2_value";
    public static final String EXTRA_DIRECTION = "extra_direction";
    public static final String EXTRA_PATTERN = "extra_pattern";

    private static final int FIRE_RED = 0xFFEF4444;
    private static final int PURE_WHITE = 0xFFFFFFFF;

    private BroadcastReceiver closeReceiver;
    private View pulseCircle;
    private TextView titleText;
    private ValueAnimator flashAnimator;
    private GradientDrawable pulseCircleDrawable;
    private MediaPlayer alarmPlayer;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fire_alert);

        if (getWindow() != null) {
            getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            getWindow().setDimAmount(0.70f);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DIM_BEHIND
                    | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }

        pulseCircle = findViewById(R.id.fireAlertPulseCircle);
        pulseCircleDrawable = (GradientDrawable) pulseCircle.getBackground().mutate();
        titleText = findViewById(R.id.tvFireAlertTitle);
        TextView tvTime = findViewById(R.id.tvFireAlertTimeValue);
        TextView btnClose = findViewById(R.id.btnFireAlertClose);

        tvTime.setText("Phát hiện lúc: " + formatNow());
        btnClose.setOnClickListener(v -> dismissByUser());

        startAlarmSound();
        startFlashAnimation();
        registerCloseReceiver();
    }

    private void startAlarmSound() {
        stopAlarmSound();
        alarmPlayer = MediaPlayer.create(this, R.raw.tieng_coi_bao_chay);
        if (alarmPlayer == null) {
            return;
        }

        alarmPlayer.setLooping(true);
        alarmPlayer.setVolume(1f, 1f);
        alarmPlayer.start();
    }

    private void stopAlarmSound() {
        if (alarmPlayer == null) {
            return;
        }

        try {
            if (alarmPlayer.isPlaying()) {
                alarmPlayer.stop();
            }
        } catch (IllegalStateException ignored) {
        }
        alarmPlayer.release();
        alarmPlayer = null;
    }

    private void startFlashAnimation() {
        stopFlashAnimation();

        flashAnimator = ValueAnimator.ofFloat(0f, 1f);
        flashAnimator.setDuration(650L);
        flashAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flashAnimator.setRepeatMode(ValueAnimator.REVERSE);
        flashAnimator.addUpdateListener(animator -> {
            float fraction = (float) animator.getAnimatedValue();
            boolean brightPhase = fraction < 0.5f;
            int color = brightPhase ? FIRE_RED : PURE_WHITE;
            pulseCircleDrawable.setColor(color);
            pulseCircle.setScaleX(brightPhase ? 1.0f : 1.08f);
            pulseCircle.setScaleY(brightPhase ? 1.0f : 1.08f);
            pulseCircle.setAlpha(brightPhase ? 1.0f : 0.72f);
            titleText.setTextColor(brightPhase ? PURE_WHITE : FIRE_RED);
        });
        flashAnimator.start();
    }

    private void stopFlashAnimation() {
        if (flashAnimator != null) {
            flashAnimator.cancel();
            flashAnimator.removeAllUpdateListeners();
            flashAnimator = null;
        }
    }

    private void dismissByUser() {
        sendBroadcast(new Intent(ACTION_USER_DISMISS));
        finish();
    }

    private String formatNow() {
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(new java.util.Date());
    }

    private void registerCloseReceiver() {
        closeReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                finish();
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_CLOSE);
        ContextCompat.registerReceiver(this, closeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopAlarmSound();
        stopFlashAnimation();
        if (closeReceiver != null) {
            try {
                unregisterReceiver(closeReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            closeReceiver = null;
        }
    }

    @Override
    public void onBackPressed() {
        dismissByUser();
    }
}
