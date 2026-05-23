package thick2.nhom1.smartfiremonitoring;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class FireEvent {
    public long timestamp;
    public String time_readable;
    public double temperature;
    public double humidity;
    public int mq2_value;
    public String mq2_level;
    public String flame_direction;
    public String flame_pattern;
    public String action_taken;
    public long resolved_at;

    public FireEvent() {
    }

    public long getTimestampSafe() {
        return timestamp;
    }

    public double getTemperature() {
        return temperature;
    }

    public int getMq2Value() {
        return mq2_value;
    }

    public String getMq2Level() {
        return safeText(mq2_level, "--");
    }

    public String getFlameDirection() {
        return safeText(flame_direction, "--");
    }

    public String getFlamePattern() {
        return safeText(flame_pattern, "-----");
    }

    public String getActionTaken() {
        return safeText(action_taken, "--");
    }

    public String getChartTimeLabel() {
        if (time_readable != null && !time_readable.trim().isEmpty()) {
            try {
                SimpleDateFormat source = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                Date date = source.parse(time_readable);
                if (date != null) {
                    return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(date);
                }
            } catch (ParseException ignored) {
            }
        }

        if (timestamp > 0) {
            return new SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                    .format(new Date(timestamp * 1000L));
        }
        return "--";
    }

    public String getDisplayTime() {
        if (time_readable != null && !time_readable.trim().isEmpty()) {
            return time_readable;
        }
        return getChartTimeLabel();
    }

    public String getProcessingDuration() {
        if (resolved_at <= 0 || timestamp <= 0) {
            return "Đang xử lý";
        }

        long diff = Math.max(0, resolved_at - timestamp);
        if (diff < 60) {
            return diff + "s";
        }

        long minutes = diff / 60;
        long seconds = diff % 60;
        if (minutes < 60) {
            return minutes + "m " + seconds + "s";
        }

        long hours = minutes / 60;
        long remainMinutes = minutes % 60;
        return hours + "h " + remainMinutes + "m";
    }

    private String safeText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
