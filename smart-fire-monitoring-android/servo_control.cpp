// actuators/servo_control.cpp

#include "servo_control.h"

#include <Arduino.h>

void setupServos()
{
    servoPan.attach(SERVO_PAN_PIN, 500, 2400);
    servoTilt.attach(SERVO_TILT_PIN, 500, 2400);
    servoPan.write(90);
    servoTilt.write(TILT_SWEEP_MIN);
    currentPan = 90;
    currentTilt = TILT_SWEEP_MIN;
    lastTiltStep = millis();
    tiltStep = TILT_STEP_DEGREES;
    Serial.println("[Servo] Khởi động về trung tâm (Pan=90°, Tilt=30°).");
}

int calcTiltAngle(int adcValue)
{
    // Không dùng AO cho trục Y nữa — trục Y sẽ quét nâng hạ tự động
    // Hàm này giữ lại cho tương thích nhưng không được gọi trong auto mode
    adcValue = constrain(adcValue, 0, 4095);
    return map(adcValue, 0, 4095, TILT_MIN, TILT_MAX);
}

// ─────────────────────────────────────────────────────────────
//  TRỤC Y QUÉT NÂNG HẠ TỰ ĐỘNG (90° ↔ 30°)
//  Đi từng bước nhỏ 1° để servo mượt hơn khi loop bị Firebase làm chậm.
// ─────────────────────────────────────────────────────────────
void sweepTilt()
{
    unsigned long now = millis();
    if (now - lastTiltStep < TILT_STEP_INTERVAL_MS)
        return;

    lastTiltStep = now;
    currentTilt += tiltStep;

    if (currentTilt >= TILT_SWEEP_MAX)
    {
        currentTilt = TILT_SWEEP_MAX;
        tiltStep = -TILT_STEP_DEGREES;
    }
    else if (currentTilt <= TILT_SWEEP_MIN)
    {
        currentTilt = TILT_SWEEP_MIN;
        tiltStep = TILT_STEP_DEGREES;
    }

    servoTilt.write(currentTilt);
}

void resetTiltSweep()
{
    tiltStep = -TILT_STEP_DEGREES;
    lastTiltStep = millis();
    currentTilt = 90;
    servoTilt.write(currentTilt);
}

void updateServosAuto(int priorityIdx)
{
    if (priorityIdx < 0 || priorityIdx > 4)
        return;

    int newPan = PAN_ANGLES[priorityIdx];

    if (newPan != currentPan)
    {
        currentPan = newPan;
        servoPan.write(currentPan);
        Serial.printf("[Servo AUTO] Pan=%d° (mắt #%d)\n", currentPan, priorityIdx + 1);
    }

    // Trục Y: quét nâng hạ tự động 90°↔30°
    sweepTilt();
}

void updateServosManual(int pan, int tilt)
{
    currentPan = constrain(pan, 0, 180);
    currentTilt = constrain(tilt, 0, 180);
    servoPan.write(currentPan);
    servoTilt.write(currentTilt);
    Serial.printf("[Servo MANUAL] Pan=%d° | Tilt=%d°\n", currentPan, currentTilt);
}