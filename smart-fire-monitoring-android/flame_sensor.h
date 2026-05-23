// sensors/flame_sensor.h
#ifndef FLAME_SENSOR_H
#define FLAME_SENSOR_H

#include "config.h"
#include "sensor_state.h"

void setupFlamePins();
void readFlameSensors();
void checkSensorFusion();

#endif // FLAME_SENSOR_H