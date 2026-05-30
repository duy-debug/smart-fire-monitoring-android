// config.h
#ifndef CONFIG_H
#define CONFIG_H

// ─────────────────────────────────────────────────────────────
//  NODE GỐC FIREBASE
// ─────────────────────────────────────────────────────────────
#define FB_ROOT "/fire-alarm-system"

// ─────────────────────────────────────────────────────────────
//  WATCHDOG TIMER
// ─────────────────────────────────────────────────────────────
#define WDT_TIMEOUT_S 30

// ─────────────────────────────────────────────────────────────
//  CHÂN GPIO
// ─────────────────────────────────────────────────────────────
#define DHT_PIN 4
#define DHT_TYPE DHT11

#define MQ2_AO_PIN 32
#define MQ2_DO_PIN 15

const int FLAME_AO_PINS[5] = {33, 36, 34, 39, 35};
const int FLAME_DO_PINS[5] = {13, 25, 14, 27, 26};
const bool FLAME_AO_ENABLED[5] = {true, false, true, false, true};

// ─────────────────────────────────────────────────────────────
//  CẤU HÌNH LOGIC CẢM BIẾN LỬA
//  DO: HIGHT = có lửa (active-HIGH)
//  AO: giá trị CAO = lửa mạnh (dùng để xác định hướng khi >1 mắt báo)
// ─────────────────────────────────────────────────────────────

#define SERVO_PAN_PIN 18
#define SERVO_TILT_PIN 5

#define RELAY_PIN 19
#define BUZZER_PIN 2

// ─────────────────────────────────────────────────────────────
//  ENUM THAY THẾ STRING ĐỘNG (Tối ưu #1: tránh phân mảnh heap)
// ─────────────────────────────────────────────────────────────
enum MQ2Level
{
    MQ2_SAFE,
    MQ2_WARNING,
    MQ2_DANGER
};
inline const char *MQ2_LEVEL_STR[] = {"safe", "warning", "danger"};

enum FlameDir
{
    DIR_LEFT,
    DIR_CENTER_LEFT,
    DIR_CENTER,
    DIR_CENTER_RIGHT,
    DIR_RIGHT,
    DIR_NONE
};
inline const char *FLAME_DIR_STR[] = {"left", "center-left", "center", "center-right", "right", "none"};

enum SystemMode
{
    MODE_AUTO,
    MODE_MANUAL
};
inline const char *SYSTEM_MODE_STR[] = {"auto", "manual"};

enum DHTStatus
{
    DHT_OK,
    DHT_ERROR
};
inline const char *DHT_STATUS_STR[] = {"ok", "error"};

// ─────────────────────────────────────────────────────────────
//  ÁNH XẠ GÓC SERVO (theo cơ cấu thực tế)
// ─────────────────────────────────────────────────────────────
const int PAN_ANGLES[5] = {50, 70, 90, 120, 140};

#define PAN_STEP_DEGREES 2
#define PAN_STEP_INTERVAL_MS 12

#define TILT_HOME_ANGLE 45
#define TILT_MIN 30
#define TILT_MAX 150
#define ADC_NEAR 0
#define ADC_FAR 2048

// ─────────────────────────────────────────────────────────────
//  NGƯỠNG MẶC ĐỊNH (cập nhật động từ Firebase)
// ─────────────────────────────────────────────────────────────
#define MQ2_SAFE_DEFAULT 800
#define MQ2_WARNING_DEFAULT 1500
#define TEMP_SAFE_DEFAULT 40.0f
#define TEMP_WARNING_DEFAULT 60.0f

// ─────────────────────────────────────────────────────────────
//  DEBOUNCE CẢM BIẾN LỬA
// ─────────────────────────────────────────────────────────────
#define CONFIRM_ON_THRESHOLD 1
#define CONFIRM_OFF_THRESHOLD 3
#define CONFIRM_INTERVAL_MS 100
#define FLAME_READ_INTERVAL_MS 20

// ─────────────────────────────────────────────────────────────
//  KẾT NỐI / FIREBASE
// ─────────────────────────────────────────────────────────────
#define WIFI_STABLE_GRACE_MS 5000
#define WIFI_RETRY_INTERVAL_MS 10000
#define FB_WRITE_INTERVAL_NORMAL 2000
#define FB_WRITE_INTERVAL_FIRE 500
#define FB_CMD_READ_INTERVAL_MS 5000
#define FUSION_HOLD_AFTER_FLAME_CLEAR_MS 45000

// ─────────────────────────────────────────────────────────────
//  PHIÊN BẢN
// ─────────────────────────────────────────────────────────────
#define FIRMWARE_VERSION "2.2.0"

// ─────────────────────────────────────────────────────────────
//  TILT SWEEP
// ─────────────────────────────────────────────────────────────
#define TILT_SWEEP_MIN 30
#define TILT_SWEEP_MAX 90
#define TILT_STEP_DEGREES 1
#define TILT_STEP_INTERVAL_MS 20

#endif // CONFIG_H
