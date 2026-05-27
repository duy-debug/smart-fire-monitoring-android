// logic/auto_mode.cpp

#include "auto_mode.h"

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "buzzer_control.h"
#include "pump_control.h"
#include "servo_control.h"
#include "firebase_manager.h"

static unsigned long flameClearedFusionHoldStart = 0;

static void clearFireStateImmediate()
{
    // 1) Turn off alert logic immediately.
    setBuzzer(false);
    setPump(false);
    flameClearedFusionHoldStart = 0;
    fireDetected = false;
    waitingForServo = false;
    sensorDataDirty = true;
    Serial.println("[AUTO] Alert logic cleared immediately (buzzer/pump OFF).");

    // 2) Push reset state to Firebase immediately.
    if (isFirebaseReady())
    {
        esp_task_wdt_reset();
        FirebaseJson resetJson;
        resetJson.set("system/fire_detected", false);
        resetJson.set("actuators/pump", false);
        resetJson.set("actuators/buzzer", false);
        resetJson.set("actuators/auto_pump_active", false);
        Firebase.RTDB.updateNode(&fbData, FB_ROOT, &resetJson);
    }

    // 3) Return servos home asynchronously.
    stopTiltSweep(90);
    updateServosManual(90, 90);

    resolveLogEvent();
}

void handleAutoMode()
{
    // Nếu đang chữa cháy và mắt lửa đã hết thì reset ngay,
    // tránh giữ còi/pump do độ trễ cập nhật của cảm biến fusion.
    if (fireDetected && !anyFlameDetected)
    {
        setBuzzer(false);
        if (!sensorFusionAlert)
        {
            flameClearedFusionHoldStart = 0;
            Serial.println("[AUTO] All clear. Full reset.");
            clearFireStateImmediate();
            return;
        }
        else
        {
            unsigned long now = millis();
            if (flameClearedFusionHoldStart == 0)
            {
                flameClearedFusionHoldStart = now;
                Serial.println("[AUTO] Flame cleared, fusion still active. Buzzer OFF, keep pump/servo.");
            }
            else if (now - flameClearedFusionHoldStart >= FUSION_HOLD_AFTER_FLAME_CLEAR_MS)
            {
                Serial.println("[AUTO] Fusion hold timeout after flame clear. Forcing full reset.");
                flameClearedFusionHoldStart = 0;
                clearFireStateImmediate();
                return;
            }
        }
    }
    else
    {
        flameClearedFusionHoldStart = 0;
    }

    bool shouldActivate = anyFlameDetected || sensorFusionAlert;

    if (!alertEnabled || alertSnoozed)
    {
        shouldActivate = false;
    }

    if (shouldActivate)
    {
        if (!fireDetected)
        {
            fireDetected = true;
            systemMode = MODE_AUTO;
            fireTriggerTime = millis();
            waitingForServo = true;
            sensorDataDirty = true;

            Serial.println("\n[AUTO] Fire detected. Activating.");
            if (sensorFusionAlert && !anyFlameDetected)
                Serial.println("[AUTO] Triggered by sensor fusion.");

            setBuzzer(true);

            if (anyFlameDetected && flamePriorityIdx >= 0)
                updateServosAuto(flamePriorityIdx);
            else
                updateServosManual(90, 90);

            if (isFirebaseReady())
            {
                esp_task_wdt_reset();
                FirebaseJson fireJson;
                fireJson.set("system/fire_detected", true);
                fireJson.set("actuators/buzzer", true);
                fireJson.set("system/mode", "auto");
                Firebase.RTDB.updateNode(&fbData, FB_ROOT, &fireJson);

                triggerNotification();
                logFireEvent("auto");
            }
        }

        if (waitingForServo && millis() - fireTriggerTime >= 500)
        {
            waitingForServo = false;
            setPump(true);
            Serial.println("[AUTO] Servo settled. Pump ON.");

            if (isFirebaseReady())
            {
                Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/pump", true);
                Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/auto_pump_active", true);
            }
        }

        if (anyFlameDetected && flamePriorityIdx >= 0)
        {
            updateServosAuto(flamePriorityIdx);
        }
        else
        {
            stopTiltSweep(90);
        }
    }
    else if (fireDetected)
    {
        Serial.println("[AUTO] Fire cleared. Resetting system immediately.");
        clearFireStateImmediate();
    }
}
