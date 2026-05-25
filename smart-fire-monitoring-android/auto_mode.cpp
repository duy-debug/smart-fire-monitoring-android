// logic/auto_mode.cpp

#include "auto_mode.h"

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "buzzer_control.h"
#include "pump_control.h"
#include "servo_control.h"
#include "firebase_manager.h"

static bool anyFlameEyeActive()
{
    for (int i = 0; i < 5; i++)
    {
        if (flameDetected[i])
            return true;
    }

    return false;
}

void handleAutoMode()
{
    bool flameEyeActive = anyFlameEyeActive();
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
        else if (waitingForServo && !flameEyeActive)
        {
            stopTiltSweep(90);
        }

        if (!waitingForServo)
        {
            if (flameEyeActive && anyFlameDetected && flamePriorityIdx >= 0)
            {
                updateServosAuto(flamePriorityIdx);
            }
            else
            {
                stopTiltSweep(90);
            }
        }
    }
    else
    {
        if (fireDetected)
        {
            fireDetected = false;
            waitingForServo = false;
            sensorDataDirty = true;

            Serial.println("[AUTO] Fire cleared. Resetting system.");

            setPump(false);
            delay(200);
            setBuzzer(false);
            delay(200);
            updateServosManual(90, 90);
            resolveLogEvent();

            if (isFirebaseReady())
            {
                esp_task_wdt_reset();
                FirebaseJson resetJson;
                resetJson.set("system/fire_detected", false);
                resetJson.set("actuators/pump", false);
                resetJson.set("actuators/buzzer", false);
                resetJson.set("actuators/auto_pump_active", false);
                resetJson.set("actuators/servo/axis_x", 90);
                resetJson.set("actuators/servo/axis_y", 90);
                Firebase.RTDB.updateNode(&fbData, FB_ROOT, &resetJson);
            }
        }
    }
}
