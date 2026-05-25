// actuators/servo_control.h
#ifndef SERVO_CONTROL_H
#define SERVO_CONTROL_H

#include "config.h"
#include "sensor_state.h"

void setupServos();
int calcTiltAngle(int adcValue);
void sweepTilt();
void resetTiltSweep();
void startTiltSweep();
void stopTiltSweep(int resetAngle);
void updateServosAuto(int priorityIdx);
void updateServosManual(int pan, int tilt);

#endif // SERVO_CONTROL_H
