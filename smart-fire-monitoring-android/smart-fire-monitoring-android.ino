// smart-fire-monitoring-android.ino
/*
 * ============================================================
 *  HỆ THỐNG CHỮA CHÁY THÔNG MINH — ESP32
 *  Nhóm 1 · Lập Trình Thiết Bị Di Động · 65CNTT2
 * ============================================================
 *  Phần cứng:
 *    - DHT11         → GPIO4  (nhiệt độ / độ ẩm): powered with 3.3V
 *    - MQ-2 AO       → GPIO32 (nồng độ khí — ADC1)
 *    - MQ-2 DO       → GPIO15 (ngưỡng số)
 *    - Flame #1,3,5 AO → GPIO33,34,35 (ADC1 — an toàn khi Wi-Fi bật)
 *    - Flame #2,4 AO → GPIO36,39 (nếu board không có chân này thì raw = 0)
 *    - Flame #1–5 DO → GPIO13,25,14,27,26
 *    - Servo Pan     → GPIO18 (PWM — quay trái/phải)
 *    - Servo Tilt    → GPIO5  (PWM — ngẩng lên/xuống)
 *    - Relay 5V IN   → GPIO19 (HIGH = đóng relay = bật bơm)
 *    - Relay 5V IN   → GPIO2  (HIGH = đóng relay = bật còi)
 *
 *  Thư viện cần cài (Library Manager):
 *    - DHT sensor library (Adafruit)
 *    - Adafruit Unified Sensor
 *    - ESP32Servo
 *    - Firebase ESP32 Client (Mobizt)
 *
 *  Tối ưu v2.2.0:
 *    - Thay String động bằng enum + const char* (giảm phân mảnh heap)
 *    - Batch read Firebase (getJSON thay vì nhiều get riêng lẻ)
 *    - Ghi Firebase 2s/lần khi bình thường, 500ms khi cháy
 *    - Baud rate 115200
 *    - Hardware Watchdog Timer 10s
 *    - Sensor fusion: nhiệt + khí gas kết hợp kích hoạt cảnh báo
 * ============================================================
 */

#include <WiFi.h>
#include <WebServer.h>
#include <ElegantOTA.h>
WebServer server(80);
bool otaUrlPrinted = false;

#include <esp_task_wdt.h>
#include "soc/soc.h"
#include "soc/rtc_cntl_reg.h"

#include "config.h"
#include "credentials.h"
#include "sensor_state.h"
#include "wifi_manager.h"
#include "firebase_manager.h"
#include "ntp_manager.h"
#include "dht_sensor.h"
#include "mq2_sensor.h"
#include "flame_sensor.h"
#include "servo_control.h"
#include "pump_control.h"
#include "buzzer_control.h"
#include "auto_mode.h"

void setup()
{
    // Tối ưu #5: Baud rate 115200 thay vì 9600
    Serial.begin(115200);

    // Tắt brownout detector — tránh reset khi servo/bơm gây sụt áp tạm thời
    WRITE_PERI_REG(RTC_CNTL_BROWN_OUT_REG, 0);

    Serial.println();
    Serial.println("══════════════════════════════════════════════════");
    Serial.printf("  Hệ thống Chữa Cháy Thông Minh — ESP32 v%s\n", FIRMWARE_VERSION);
    Serial.println("══════════════════════════════════════════════════");

    // Tối ưu #6: Hardware Watchdog Timer (ESP32 Arduino Core 3.x)
    // Dùng trigger_panic = false để chỉ log warning, KHÔNG reset ESP32
    // Vì Firebase SSL handshake lần đầu có thể mất 15-25s
    const esp_task_wdt_config_t wdt_config = {
        .timeout_ms = WDT_TIMEOUT_S * 1000,
        .idle_core_mask = 0,
        .trigger_panic = false};
    esp_task_wdt_reconfigure(&wdt_config);
    esp_task_wdt_add(NULL);
    Serial.printf("[WDT] Watchdog Timer — timeout %ds (warning only)\n", WDT_TIMEOUT_S);

    // Khởi động phần cứng
    setupDHT();
    setupFlamePins();
    setupRelay();
    setupBuzzer();
    setupServos();

    // Kết nối Wi-Fi (non-blocking)
    startWiFi();

    // Khởi động server OTA
    ElegantOTA.begin(&server);
    server.begin();
    Serial.println("[OTA] Đã khởi tạo. URL sẽ được in khi Wi-Fi có IP hợp lệ.");

    Serial.println("[Setup] Hoàn tất. Bắt đầu vòng lặp chính.\n");
}

void loop()
{
    // OTA loop
    server.handleClient();
    ElegantOTA.loop();

    // Reset Watchdog mỗi vòng lặp
    esp_task_wdt_reset();

    // ── 1. Quản lý kết nối mạng (non-blocking) ───────────────
    manageWiFi();

    if (wifiConnected)
    {
        if (!otaUrlPrinted)
        {
            Serial.printf("[OTA] Sẵn sàng. Truy cập: http://%s/update\n",
                          WiFi.localIP().toString().c_str());
            otaUrlPrinted = true;
        }
    }
    else
    {
        otaUrlPrinted = false;
    }

    // ── 2. Đọc cảm biến — LUÔN CHẠY dù có WiFi hay không ────
    readFlameSensors(); // 100  ms — ưu tiên cao nhất
    readMQ2();          // 500  ms
    readDHT11();        // 2000 ms

    // Tối ưu #7: Kiểm tra sensor fusion
    checkSensorFusion();

    // ── 3. Logic điều khiển — LUÔN CHẠY (local) ──────────────
    handleAutoMode(); // Manual chỉ áp dụng khi an toàn; có cháy thì ép AUTO để cảnh báo/chữa cháy.

    // ── 4. Giao tiếp Firebase — CHỈ KHI CÓ WIFI ─────────────
    handleFirebaseCycle();
}
