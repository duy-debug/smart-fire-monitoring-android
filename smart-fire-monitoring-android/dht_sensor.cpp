// sensors/dht_sensor.cpp

#include "dht_sensor.h"

#include <Arduino.h>

void setupDHT()
{
    dht.begin();
}

void readDHT11()
{
    if (millis() - lastDHTRead < 2000)
        return;
    lastDHTRead = millis();

    float t = dht.readTemperature();
    float h = dht.readHumidity();

    if (isnan(t) || isnan(h))
    {
        dhtStatus = DHT_ERROR;
        Serial.println("[DHT11] Lỗi đọc — giữ nguyên giá trị cũ.");
        return;
    }
    temperature = t;
    humidity = h;
    dhtStatus = DHT_OK;
    Serial.printf("[DHT11] %.1f°C | %.1f%%\n", t, h);
}