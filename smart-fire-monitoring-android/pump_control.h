// actuators/pump_control.h
#ifndef PUMP_CONTROL_H
#define PUMP_CONTROL_H

#include "config.h"
#include "sensor_state.h"

void setupRelay();
void setPump(bool state);

#endif // PUMP_CONTROL_H