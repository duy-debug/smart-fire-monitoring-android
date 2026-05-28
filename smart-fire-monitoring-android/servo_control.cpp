// actuators/servo_control.cpp

#include "servo_control.h"

#include <Arduino.h>

#include "flame_sensor.h"

struct TiltSchedulerState
{
    bool enabled;
    int minAngle;
    int maxAngle;
    int currentTilt;
    int direction;
    unsigned long lastStep;
    bool writePending;
};

static TiltSchedulerState tiltState = {
    false,
    TILT_SWEEP_MIN,
    TILT_SWEEP_MAX,
    TILT_HOME_ANGLE,
    TILT_STEP_DEGREES,
    0,
    true};

static TaskHandle_t tiltTaskHandle = nullptr;
static portMUX_TYPE tiltStateMux = portMUX_INITIALIZER_UNLOCKED;
static SemaphoreHandle_t servoWriteMutex = nullptr;
static int lastAutoPanIdx = -1;
static int targetPan = 90;
static unsigned long lastPanStep = 0;

static void writePanServo(int angle)
{
    if (servoWriteMutex != nullptr)
        xSemaphoreTake(servoWriteMutex, portMAX_DELAY);

    servoPan.write(angle);

    if (servoWriteMutex != nullptr)
        xSemaphoreGive(servoWriteMutex);
}

static void writeTiltServo(int angle)
{
    if (servoWriteMutex != nullptr)
        xSemaphoreTake(servoWriteMutex, portMAX_DELAY);

    servoTilt.write(angle);

    if (servoWriteMutex != nullptr)
        xSemaphoreGive(servoWriteMutex);
}

static void setPanImmediate(int angle)
{
    currentPan = constrain(angle, 0, 180);
    targetPan = currentPan;
    writePanServo(currentPan);
    sensorDataDirty = true;
}

static void stepPanTowardTarget()
{
    if (currentPan == targetPan)
        return;

    unsigned long now = millis();
    if (now - lastPanStep < PAN_STEP_INTERVAL_MS)
        return;

    lastPanStep = now;

    int delta = targetPan - currentPan;
    int step = constrain(delta, -PAN_STEP_DEGREES, PAN_STEP_DEGREES);
    currentPan += step;
    writePanServo(currentPan);
    sensorDataDirty = true;
}

static void refreshAutoPan(int priorityIdx, bool forceWrite)
{
    (void)forceWrite;

    if (priorityIdx < 0 || priorityIdx > 4)
        return;

    int newPan = PAN_ANGLES[priorityIdx];
    bool directionChanged = (priorityIdx != lastAutoPanIdx);
    bool targetChanged = (newPan != targetPan);

    if (!targetChanged && !directionChanged)
        return;

    targetPan = newPan;
    lastAutoPanIdx = priorityIdx;
    sensorDataDirty = true;

    if (targetChanged || directionChanged)
        Serial.printf("[Servo AUTO] Pan target=%d (eye #%d)\n", targetPan, priorityIdx + 1);
}

static void refreshAutoPanFromFlame(bool forceWrite)
{
    int priorityIdx = pollFlameSensorsImmediate(false);

    if (priorityIdx >= 0)
        refreshAutoPan(priorityIdx, forceWrite);
}

static void tiltSchedulerTask(void *parameter)
{
    (void)parameter;

    int lastWrittenTilt = -1;
    TickType_t delayTicks = pdMS_TO_TICKS(5);
    if (delayTicks < 1)
        delayTicks = 1;

    for (;;)
    {
        int angleToWrite = lastWrittenTilt;
        bool shouldWrite = false;
        bool tiltEnabled = false;
        unsigned long now = millis();

        portENTER_CRITICAL(&tiltStateMux);
        tiltEnabled = tiltState.enabled;

        if (tiltState.enabled && now - tiltState.lastStep >= TILT_STEP_INTERVAL_MS)
        {
            tiltState.lastStep = now;
            tiltState.currentTilt += tiltState.direction;

            if (tiltState.currentTilt >= tiltState.maxAngle)
            {
                tiltState.currentTilt = tiltState.maxAngle;
                tiltState.direction = -TILT_STEP_DEGREES;
            }
            else if (tiltState.currentTilt <= tiltState.minAngle)
            {
                tiltState.currentTilt = tiltState.minAngle;
                tiltState.direction = TILT_STEP_DEGREES;
            }

            currentTilt = tiltState.currentTilt;
            tiltStep = tiltState.direction;
            lastTiltStep = tiltState.lastStep;
            angleToWrite = tiltState.currentTilt;
            shouldWrite = true;
        }
        else if (!tiltState.enabled && tiltState.writePending)
        {
            currentTilt = tiltState.currentTilt;
            tiltStep = tiltState.direction;
            lastTiltStep = tiltState.lastStep;
            angleToWrite = tiltState.currentTilt;
            tiltState.writePending = false;
            shouldWrite = true;
        }

        portEXIT_CRITICAL(&tiltStateMux);

        if (tiltEnabled)
            refreshAutoPanFromFlame(true);

        stepPanTowardTarget();

        if (shouldWrite && angleToWrite != lastWrittenTilt)
        {
            writeTiltServo(angleToWrite);
            lastWrittenTilt = angleToWrite;

            refreshAutoPanFromFlame(true);
        }

        vTaskDelay(delayTicks);
    }
}

static void setTiltManualPosition(int angle)
{
    angle = constrain(angle, 0, 180);

    portENTER_CRITICAL(&tiltStateMux);
    tiltState.enabled = false;
    tiltState.currentTilt = angle;
    tiltState.lastStep = millis();
    tiltState.writePending = true;
    currentTilt = angle;
    lastTiltStep = tiltState.lastStep;
    portEXIT_CRITICAL(&tiltStateMux);
}

void setupServos()
{
    servoPan.attach(SERVO_PAN_PIN, 500, 2400);
    servoTilt.attach(SERVO_TILT_PIN, 500, 2400);

    if (servoWriteMutex == nullptr)
        servoWriteMutex = xSemaphoreCreateMutex();

    writePanServo(90);

    currentPan = 90;
    targetPan = 90;
    currentTilt = TILT_HOME_ANGLE;
    lastTiltStep = millis();
    tiltStep = TILT_STEP_DEGREES;

    portENTER_CRITICAL(&tiltStateMux);
    tiltState.enabled = false;
    tiltState.minAngle = TILT_SWEEP_MIN;
    tiltState.maxAngle = TILT_SWEEP_MAX;
    tiltState.currentTilt = TILT_HOME_ANGLE;
    tiltState.direction = TILT_STEP_DEGREES;
    tiltState.lastStep = lastTiltStep;
    tiltState.writePending = true;
    portEXIT_CRITICAL(&tiltStateMux);

    if (tiltTaskHandle == nullptr)
    {
        xTaskCreatePinnedToCore(
            tiltSchedulerTask,
            "tilt_scheduler",
            2048,
            nullptr,
            3,
            &tiltTaskHandle,
            1);
    }

    Serial.printf("[Servo] Started. Pan=90, Tilt=%d, tilt scheduler active.\n", TILT_HOME_ANGLE);
}

int calcTiltAngle(int adcValue)
{
    adcValue = constrain(adcValue, 0, 4095);
    return map(adcValue, 0, 4095, TILT_MIN, TILT_MAX);
}

void sweepTilt()
{
    startTiltSweep();
}

void resetTiltSweep()
{
    stopTiltSweep(TILT_HOME_ANGLE);
}

void startTiltSweep()
{
    unsigned long now = millis();

    portENTER_CRITICAL(&tiltStateMux);
    tiltState.minAngle = TILT_SWEEP_MIN;
    tiltState.maxAngle = TILT_SWEEP_MAX;

    if (!tiltState.enabled)
    {
        tiltState.enabled = true;
        tiltState.currentTilt = constrain(currentTilt, tiltState.minAngle, tiltState.maxAngle);
        tiltState.direction = (tiltState.currentTilt <= tiltState.minAngle) ? TILT_STEP_DEGREES : tiltState.direction;
        tiltState.lastStep = now;
        tiltState.writePending = false;
        currentTilt = tiltState.currentTilt;
        tiltStep = tiltState.direction;
        lastTiltStep = now;
    }

    portEXIT_CRITICAL(&tiltStateMux);
}

void stopTiltSweep(int resetAngle)
{
    setTiltManualPosition(resetAngle);
}

void updateServosAuto(int priorityIdx)
{
    refreshAutoPan(priorityIdx, true);
    startTiltSweep();
}

void updateServosManual(int pan, int tilt)
{
    lastAutoPanIdx = -1;
    setPanImmediate(pan);
    setTiltManualPosition(tilt);
    Serial.printf("[Servo MANUAL] Pan=%d | Tilt=%d\n", currentPan, currentTilt);
}
