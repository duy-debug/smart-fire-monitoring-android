// sensors/flame_sensor.cpp

#include "flame_sensor.h"

#include <Arduino.h>

void setupFlamePins()
{
    for (int i = 0; i < 5; i++)
    {
        // Active-HIGH: default LOW = no flame
        pinMode(FLAME_DO_PINS[i], INPUT_PULLDOWN);
    }
}

void readFlameSensors()
{
    if (millis() - lastFlameRead < FLAME_READ_INTERVAL_MS)
        return;
    lastFlameRead = millis();

    // Skip the first 5 seconds after boot so sensors can stabilize.
    if (millis() < 5000)
        return;

    bool rawAny = false;
    int minRaw = -1;
    int minIdx = -1;

    for (int i = 0; i < 5; i++)
    {
        bool doState = digitalRead(FLAME_DO_PINS[i]);
        flameDetected[i] = (doState == HIGH);

        flameRaw[i] = FLAME_AO_ENABLED[i] ? analogRead(FLAME_AO_PINS[i]) : 0;
    }

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

    if (fireCount == 1)
    {
        rawAny = true;
        minIdx = singleIdx;
        minRaw = flameRaw[singleIdx];
    }
    else if (fireCount > 1)
    {
        rawAny = true;
        int activeIdxSum = 0;
        bool hasAoCandidate = false;

        for (int i = 0; i < 5; i++)
        {
            if (!flameDetected[i])
                continue;

            activeIdxSum += i;

            if (FLAME_AO_ENABLED[i])
            {
                if (!hasAoCandidate || flameRaw[i] > minRaw)
                {
                    hasAoCandidate = true;
                    minRaw = flameRaw[i];
                    minIdx = i;
                }
            }
        }

        if (!hasAoCandidate)
        {
            // Khi nhiều mắt lửa cùng ON nhưng không có AO khả dụng,
            // lấy trung bình vị trí để pan bám hướng lửa mượt hơn.
            int avgIdx = (activeIdxSum + (fireCount / 2)) / fireCount;
            minIdx = constrain(avgIdx, 0, 4);
            minRaw = flameRaw[minIdx];
        }
    }

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

    if (rawAny && !anyFlameDetected)
    {
        anyFlameDetected = true;
        confirmOnCount = CONFIRM_ON_THRESHOLD;
        confirmOffCount = 0;
        lastConfirmRead = now;
        flamePriorityIdx = minIdx;
        flameDirection = (minIdx >= 0) ? (FlameDir)minIdx : DIR_NONE;
        Serial.printf("[Flame] \xE2\x9C\x93 Fire detected! Direction: %s (eye #%d, ADC=%d)\n",
                      FLAME_DIR_STR[flameDirection], minIdx + 1, minRaw);
    }
    else if (!rawAny && anyFlameDetected)
    {
        anyFlameDetected = false;
        confirmOnCount = 0;
        confirmOffCount = CONFIRM_OFF_THRESHOLD;
        lastConfirmRead = now;
        flamePriorityIdx = -1;
        flameDirection = DIR_NONE;
        Serial.println("[Flame] \xE2\x9C\x93 Fire cleared.");
    }
    else if (anyFlameDetected)
    {
        if (minIdx >= 0)
        {
            flamePriorityIdx = minIdx;
            flameDirection = (FlameDir)minIdx;
            Serial.printf("[Flame] Direction updated -> %s (eye #%d)\n",
                          FLAME_DIR_STR[flameDirection], minIdx + 1);
        }
    }
}

/*
 * Sensor fusion: temperature + gas can trigger fire alert
 * even before flame sensors are active.
 * Condition: temperature >= tempWarning and MQ2 >= mq2Warning
 */
void checkSensorFusion()
{
    bool prevFusion = sensorFusionAlert;
    sensorFusionAlert = (temperature >= tempWarning && mq2Level == MQ2_DANGER);

    if (sensorFusionAlert && !prevFusion)
    {
        Serial.println("[Fusion] WARNING: High temperature + dangerous gas!");
    }
    else if (!sensorFusionAlert && prevFusion)
    {
        Serial.println("[Fusion] Danger level dropped.");
    }
}
