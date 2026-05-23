// time/ntp_manager.h
#ifndef NTP_MANAGER_H
#define NTP_MANAGER_H

#include "config.h"
#include "sensor_state.h"

void initNTP();
bool checkNTPSynced();
time_t getTimestamp();
const char *getTimeReadable();

#endif // NTP_MANAGER_H