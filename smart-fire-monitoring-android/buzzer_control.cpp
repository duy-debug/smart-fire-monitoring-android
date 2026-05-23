// actuators/buzzer_control.cpp

#include "buzzer_control.h"

#include <Arduino.h>

void setupBuzzer()
{
    pinMode(BUZZER_PIN, OUTPUT);
    digitalWrite(BUZZER_PIN, LOW);
    Serial.println("[Buzzer] Khởi động — còi TẮT.");
}

void setBuzzer(bool state)
{
    if (buzzerActive == state)
        return;
    buzzerActive = state;
    digitalWrite(BUZZER_PIN, state ? HIGH : LOW);
    Serial.printf("[Còi] %s\n", state ? "BẬT" : "TẮT");
}