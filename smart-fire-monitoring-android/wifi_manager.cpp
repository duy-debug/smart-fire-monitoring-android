// network/wifi_manager.cpp

#include "wifi_manager.h"

#include <WiFi.h>
#include <Arduino.h>

#include "credentials.h"

void startWiFi()
{
    Serial.printf("[WiFi] Đang kết nối tới \"%s\"...\n", WIFI_SSID);
    WiFi.mode(WIFI_STA);
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    lastWiFiRetry = millis();
}

// ─────────────────────────────────────────────────────────────
//  QUẢN LÝ WIFI — HOÀN TOÀN NON-BLOCKING
//  Hệ thống hoạt động 2 chế độ:
//    1. CÓ WiFi: đọc cảm biến + ghi Firebase + nhận lệnh
//    2. KHÔNG WiFi: đọc cảm biến + chữa cháy local (auto mode)
//  WiFi retry mỗi 10s, KHÔNG block loop()
// ─────────────────────────────────────────────────────────────
void manageWiFi()
{
    bool prev = wifiConnected;
    wifiConnected = (WiFi.status() == WL_CONNECTED);

    // ── ĐÃ KẾT NỐI ──────────────────────────────────────────
    if (wifiConnected)
    {
        wifiConnecting = false;
        if (!prev)
        {
            wifiStableSince = millis();
            Serial.printf("[WiFi] ✓ Đã kết nối. IP: %s\n",
                          WiFi.localIP().toString().c_str());
            // Reset stream khi WiFi reconnect — stream cũ đã hỏng
            if (streamStarted)
            {
                streamStarted = false;
                Serial.println("[Stream] Reset — sẽ khởi tạo lại.");
            }
        }
        return;
    }

    // ── MẤT KẾT NỐI ─────────────────────────────────────────
    wifiStableSince = 0;

    // Reset trạng thái Firebase khi mất WiFi
    if (prev && !wifiConnected)
    {
        fbReady = false;
        Serial.println("[WiFi] ✗ Mất kết nối — chuyển sang chế độ LOCAL.");
    }

    // Đang chờ kết nối — in tiến trình
    if (wifiConnecting)
    {
        unsigned long elapsed = (millis() - wifiConnectStart) / 1000;
        // In mỗi 3s để biết hệ thống đang làm gì
        if (elapsed > 0 && elapsed % 3 == 0 &&
            millis() - wifiConnectStart > (elapsed * 1000 - 100))
        {
            Serial.printf("[WiFi] Đang chờ kết nối... %lus\n", elapsed);
        }
        return; // Không block, trả về loop() ngay
    }

    // Đã đủ thời gian retry — thử kết nối lại
    if (millis() - lastWiFiRetry >= WIFI_RETRY_INTERVAL_MS)
    {
        lastWiFiRetry = millis();
        wifiConnecting = true;
        wifiConnectStart = millis();
        Serial.printf("[WiFi] Thử kết nối lại (retry mỗi %ds)...\n",
                      WIFI_RETRY_INTERVAL_MS / 1000);
        WiFi.disconnect();
        WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
    }
}