// time/ntp_manager.cpp

#include "ntp_manager.h"

#include <time.h>
#include <Arduino.h>

void initNTP()
{
    configTime(7 * 3600, 0, "pool.ntp.org", "time.nist.gov");
    Serial.println("[NTP] Đang đồng bộ thời gian...");
}

bool checkNTPSynced()
{
    if (ntpSynced)
        return true;
    struct tm ti;
    if (!getLocalTime(&ti))
        return false;
    if (ti.tm_year + 1900 < 2024)
        return false;
    ntpSynced = true;
    Serial.println("[NTP] Đồng bộ thành công.");
    return true;
}

time_t getTimestamp()
{
    if (!ntpSynced)
        return 0;
    time_t now;
    time(&now);
    return now;
}

const char *getTimeReadable()
{
    if (!ntpSynced)
        return "NTP not synced";
    static char timeReadable[20];
    struct tm ti;
    getLocalTime(&ti);
    strftime(timeReadable, sizeof(timeReadable), "%Y-%m-%d %H:%M:%S", &ti);
    return timeReadable;
}