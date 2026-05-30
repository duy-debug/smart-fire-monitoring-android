// sensors/flame_sensor.cpp

#include "flame_sensor.h"

#include <Arduino.h>

void setupFlamePins()
{
    for (int i = 0; i < 5; i++)
    {
        // Active-HIGH: default LOW = no flame.
        pinMode(FLAME_DO_PINS[i], INPUT_PULLDOWN);
    }
}

int getActiveFlamePriorityIdx()
{
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

    if (fireCount == 0)
        return -1;

    if (fireCount == 1)
        return singleIdx;

    int bestIdx = -1;
    int bestRaw = -1;

    for (int i = 0; i < 5; i++)
    {
        if (!flameDetected[i])
            continue;

        if (flameRaw[i] > bestRaw)
        {
            bestRaw = flameRaw[i];
            bestIdx = i;
        }
    }

    return bestIdx;
}

int pollFlameSensorsImmediate(bool logChanges)
{
    bool wasDetected = anyFlameDetected;
    int previousIdx = flamePriorityIdx;

    for (int i = 0; i < 5; i++)
    {
        bool doState = digitalRead(FLAME_DO_PINS[i]);
        flameDetected[i] = (doState == HIGH);
        flameRaw[i] = FLAME_AO_ENABLED[i] ? analogRead(FLAME_AO_PINS[i]) : 0;
    }

    int priorityIdx = getActiveFlamePriorityIdx();
    unsigned long now = millis();

    if (priorityIdx >= 0)
    {
        anyFlameDetected = true;
        confirmOnCount = CONFIRM_ON_THRESHOLD;
        confirmOffCount = 0;
        lastConfirmRead = now;
        flamePriorityIdx = priorityIdx;
        flameDirection = (FlameDir)priorityIdx;
    }
    else
    {
        anyFlameDetected = false;
        confirmOnCount = 0;
        confirmOffCount = CONFIRM_OFF_THRESHOLD;
        lastConfirmRead = now;
        flamePriorityIdx = -1;
        flameDirection = DIR_NONE;
    }

    if (logChanges && priorityIdx >= 0 && (!wasDetected || previousIdx != priorityIdx))
    {
        Serial.printf("[Flame] Direction -> %s (eye #%d, ADC=%d)\n",
                      FLAME_DIR_STR[flameDirection], priorityIdx + 1,
                      flameRaw[priorityIdx]);
    }
    else if (logChanges && wasDetected && priorityIdx < 0)
    {
        Serial.println("[Flame] Fire cleared.");
    }

    return flamePriorityIdx;
}

void readFlameSensors()
{
    if (millis() - lastFlameRead < FLAME_READ_INTERVAL_MS)
        return;
    lastFlameRead = millis();

    // Skip the first 5 seconds after boot so sensors can stabilize.
    if (millis() < 5000)
        return;

    pollFlameSensorsImmediate(true);

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
}

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
