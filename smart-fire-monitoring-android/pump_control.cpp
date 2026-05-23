// actuators/pump_control.cpp

#include "pump_control.h"

#include <Arduino.h>

void setupRelay()
{
    pinMode(RELAY_PIN, OUTPUT);
    digitalWrite(RELAY_PIN, LOW);
    Serial.println("[Relay] Khởi động — bơm TẮT.");
}

void setPump(bool state)
{
    if (pumpActive == state)
        return;
    pumpActive = state;
    digitalWrite(RELAY_PIN, state ? HIGH : LOW);
    Serial.printf("[Bơm] %s\n", state ? "BẬT" : "TẮT");
}