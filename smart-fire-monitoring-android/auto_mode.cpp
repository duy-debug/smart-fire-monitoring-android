// logic/auto_mode.cpp

#include "auto_mode.h"

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "buzzer_control.h"
#include "pump_control.h"
#include "servo_control.h"
#include "firebase_manager.h"

void handleAutoMode()
{
    // Tối ưu #7: Sensor fusion — kết hợp lửa + nhiệt + khí gas
    // Kích hoạt khi: có lửa HOẶC (nhiệt cao + khí gas nguy hiểm)
    bool shouldActivate = anyFlameDetected || sensorFusionAlert;

    // alert/enabled=false hoặc snoozed=true → tắt toàn bộ (còi + bơm + servo + notification)
    if (!alertEnabled || alertSnoozed)
    {
        shouldActivate = false;
    }

    // ── CÓ NGUY CƠ CHÁY ──────────────────────────────────────
    if (shouldActivate)
    {
        if (!fireDetected)
        {
            fireDetected = true;
            systemMode = MODE_AUTO;
            fireTriggerTime = millis();
            waitingForServo = true;
            sensorDataDirty = true;

            Serial.println("\n[AUTO] PHÁT HIỆN CHÁY! Đang kích hoạt...");
            if (sensorFusionAlert && !anyFlameDetected)
                Serial.println("[AUTO] Kích hoạt bởi SENSOR FUSION (nhiệt + khí gas).");

            setBuzzer(true);

            // Khởi tạo sweep tilt
            lastTiltStep = millis();
            currentTilt = constrain(currentTilt, TILT_SWEEP_MIN, TILT_SWEEP_MAX);
            tiltStep = (currentTilt <= TILT_SWEEP_MIN) ? TILT_STEP_DEGREES : -TILT_STEP_DEGREES;

            // Nếu có lửa → điều servo theo hướng lửa
            // Nếu chỉ sensor fusion → giữ servo trung tâm
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

        // Bật bơm sau 500ms — chờ servo ổn định
        if (waitingForServo && millis() - fireTriggerTime >= 500)
        {
            waitingForServo = false;
            setPump(true);
            Serial.println("[AUTO] Servo ổn định → Bơm BẬT.");

            if (isFirebaseReady())
            {
                Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/pump", true);
                Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/auto_pump_active", true);
            }
        }

        // Cập nhật hướng servo liên tục nếu lửa di chuyển
        // Trục X: bám theo hướng lửa | Trục Y: quét nâng hạ liên tục
        if (!waitingForServo)
        {
            if (anyFlameDetected && flamePriorityIdx >= 0)
            {
                // Cập nhật Pan theo hướng lửa mới
                int newPan = PAN_ANGLES[flamePriorityIdx];
                if (newPan != currentPan)
                {
                    currentPan = newPan;
                    servoPan.write(currentPan);
                    Serial.printf("[Servo AUTO] Pan=%d° (mắt #%d)\n", currentPan, flamePriorityIdx + 1);
                }
            }
            // Trục Y luôn quét nâng hạ khi đang cháy
            sweepTilt();
        }
    }

    // ── NGUY CƠ ĐÃ HẾT ────────────────────────────────────────
    else
    {
        if (fireDetected)
        {
            fireDetected = false;
            waitingForServo = false;
            sensorDataDirty = true;

            Serial.println("[AUTO] Nguy cơ đã hết — Reset hệ thống.\n");

            setPump(false);
            delay(200); // Chờ dòng ổn định
            setBuzzer(false);
            delay(200);
            // Reset servo về trung tâm
            currentPan = 90;
            servoPan.write(currentPan);
            delay(200);
            resetTiltSweep();
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