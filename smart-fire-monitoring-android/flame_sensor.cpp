// sensors/flame_sensor.cpp

#include "flame_sensor.h"

#include <Arduino.h>

void setupFlamePins()
{
    for (int i = 0; i < 5; i++)
    {
        // Active-HIGH: mặc định LOW = không lửa
        pinMode(FLAME_DO_PINS[i], INPUT_PULLDOWN);
    }
}

void readFlameSensors()
{
    if (millis() - lastFlameRead < 100)
        return;
    lastFlameRead = millis();

    // Bỏ qua 5 giây đầu sau boot — cảm biến cần ổn định
    if (millis() < 5000)
        return;

    bool rawAny = false;
    int minRaw = 0; // Tìm AO cao nhất (lửa mạnh nhất)
    int minIdx = -1;

    for (int i = 0; i < 5; i++)
    {
        // Đọc DO: module này HIGH = có lửa, LOW = không lửa (active-HIGH)
        bool doState = digitalRead(FLAME_DO_PINS[i]);
        flameDetected[i] = (doState == HIGH);

        flameRaw[i] = FLAME_AO_ENABLED[i] ? analogRead(FLAME_AO_PINS[i]) : 0;
    }

    // Đếm số mắt DO báo lửa
    int fireCount = 0;
    int singleIdx = -1;
    for (int i = 0; i < 5; i++)
    {
        if (flameDetected[i])
        {
            fireCount++;
            singleIdx = i;
        }
    }

    // Xác định hướng lửa:
    //   1 mắt → dùng DO trực tiếp
    //   >1 mắt → xét AO (chỉ mắt có DO=0) để chọn mắt có tín hiệu mạnh nhất
    if (fireCount == 1)
    {
        rawAny = true;
        minIdx = singleIdx;
        minRaw = flameRaw[singleIdx];
    }
    else if (fireCount > 1)
    {
        rawAny = true;
        // Tìm mắt có AO cao nhất trong số mắt DO=0 VÀ AO enabled
        for (int i = 0; i < 5; i++)
        {
            if (flameDetected[i] && FLAME_AO_ENABLED[i])
            {
                if (flameRaw[i] > minRaw)
                {
                    minRaw = flameRaw[i];
                    minIdx = i;
                }
            }
        }
        // Nếu không có mắt AO nào enabled trong số DO=0, lấy mắt DO đầu tiên
        if (minIdx < 0)
        {
            for (int i = 0; i < 5; i++)
            {
                if (flameDetected[i])
                {
                    minIdx = i;
                    break;
                }
            }
        }
    }

    // Debug: in trạng thái 5 mắt mỗi 2 giây
    static unsigned long lastFlameDebug = 0;
    if (millis() - lastFlameDebug >= 2000)
    {
        lastFlameDebug = millis();
        Serial.printf("[Flame] DO: %d%d%d%d%d | AO: %d %d %d %d %d\n",
                      flameDetected[0], flameDetected[1], flameDetected[2],
                      flameDetected[3], flameDetected[4],
                      flameRaw[0], flameRaw[1], flameRaw[2],
                      flameRaw[3], flameRaw[4]);
    }

    unsigned long now = millis();

    if (rawAny)
    {
        confirmOffCount = 0;
        if (now - lastConfirmRead >= CONFIRM_INTERVAL_MS)
        {
            confirmOnCount++;
            lastConfirmRead = now;
            if (confirmOnCount > CONFIRM_ON_THRESHOLD * 2)
                confirmOnCount = CONFIRM_ON_THRESHOLD;
        }
    }
    else
    {
        confirmOnCount = 0;
        if (now - lastConfirmRead >= CONFIRM_INTERVAL_MS)
        {
            confirmOffCount++;
            lastConfirmRead = now;
            if (confirmOffCount > CONFIRM_OFF_THRESHOLD * 2)
                confirmOffCount = CONFIRM_OFF_THRESHOLD;
        }
    }

    if (confirmOnCount >= CONFIRM_ON_THRESHOLD && !anyFlameDetected)
    {
        anyFlameDetected = true;
        flamePriorityIdx = minIdx;
        flameDirection = (minIdx >= 0) ? (FlameDir)minIdx : DIR_NONE;
        Serial.printf("[Flame] ✓ CÓ lửa! Hướng: %s (mắt #%d, ADC=%d)\n",
                      FLAME_DIR_STR[flameDirection], minIdx + 1, minRaw);
    }
    else if (confirmOffCount >= CONFIRM_OFF_THRESHOLD && anyFlameDetected)
    {
        anyFlameDetected = false;
        flamePriorityIdx = -1;
        flameDirection = DIR_NONE;
        Serial.println("[Flame] ✓ Lửa đã TẮT.");
    }
    else if (anyFlameDetected && minIdx >= 0)
    {
        flamePriorityIdx = minIdx;
        flameDirection = (FlameDir)minIdx;
    }
}

/*
 * Tối ưu #7: Sensor Fusion
 * Kết hợp nhiệt độ + khí gas để phát hiện nguy cơ cháy
 * ngay cả khi cảm biến lửa chưa kích hoạt.
 * Điều kiện: nhiệt độ >= tempWarning VÀ MQ2 >= mq2Warning
 */
void checkSensorFusion()
{
    bool prevFusion = sensorFusionAlert;
    sensorFusionAlert = (temperature >= tempWarning && mq2Level == MQ2_DANGER);

    if (sensorFusionAlert && !prevFusion)
    {
        Serial.println("[Fusion] CẢNH BÁO: Nhiệt độ CAO + Khí gas NGUY HIỂM!");
    }
    else if (!sensorFusionAlert && prevFusion)
    {
        Serial.println("[Fusion] Mức nguy hiểm đã giảm.");
    }
}