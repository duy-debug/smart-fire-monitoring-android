// sensors/mq2_sensor.cpp

#include "mq2_sensor.h"

#include <Arduino.h>

void readMQ2()
{
    if (millis() - lastMQ2Read < 500)
        return;
    lastMQ2Read = millis();

    mq2Value = analogRead(MQ2_AO_PIN);

    MQ2Level prev = mq2Level;
    if (mq2Value < mq2Safe)
        mq2Level = MQ2_SAFE;
    else if (mq2Value < mq2Warning)
        mq2Level = MQ2_WARNING;
    else
        mq2Level = MQ2_DANGER;

    if (mq2Level != prev)
    {
        Serial.printf("[MQ-2] %d → %s\n", mq2Value, MQ2_LEVEL_STR[mq2Level]);
    }
}