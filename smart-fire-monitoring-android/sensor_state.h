// sensors/sensor_state.h
#ifndef SENSOR_STATE_H
#define SENSOR_STATE_H

#include <Arduino.h>
#define FIREBASE_DISABLE_FIRESTORE
#define FIREBASE_DISABLE_FCM
#define FIREBASE_DISABLE_GCS
#define FIREBASE_DISABLE_FB_FUNCTIONS
#define DISABLE_SD
#include <Firebase_ESP_Client.h>
#include <DHT.h>
#include <ESP32Servo.h>

#include "config.h"

extern FirebaseData fbData;
extern FirebaseData fbStreamData;
extern FirebaseConfig fbConfig;
extern FirebaseAuth fbAuth;

extern DHT dht;
extern Servo servoPan;
extern Servo servoTilt;

extern float temperature;
extern float humidity;
extern DHTStatus dhtStatus;

extern int mq2Value;
extern MQ2Level mq2Level;
extern int mq2Safe;
extern int mq2Warning;
extern float tempSafe;
extern float tempWarning;

extern bool flameDetected[5];
extern int flameRaw[5];
extern bool anyFlameDetected;
extern int flamePriorityIdx;
extern FlameDir flameDirection;

extern int confirmOnCount;
extern int confirmOffCount;
extern unsigned long lastConfirmRead;

extern bool fireDetected;
extern SystemMode systemMode;
extern bool pumpActive;
extern bool buzzerActive;
extern int currentPan;
extern int currentTilt;
extern bool waitingForServo;
extern unsigned long fireTriggerTime;
extern char currentLogKey[64];
extern bool alertEnabled;
extern bool alertSnoozed;
extern bool sensorFusionAlert;

extern bool wifiConnected;
extern bool fbReady;
extern bool ntpSynced;
extern bool firebaseInitStarted;
extern bool firebaseDefaultsInitialized;
extern bool ntpInitStarted;
extern bool streamStarted;
extern unsigned long wifiStableSince;
extern bool wifiConnecting;
extern unsigned long wifiConnectStart;

extern bool sensorDataDirty;

extern float prevTemperature;
extern float prevHumidity;
extern int prevMq2Value;
extern MQ2Level prevMq2Level;
extern bool prevFlameDetected[5];
extern bool prevAnyFlame;
extern bool prevPumpActive;
extern bool prevBuzzerActive;
extern int prevPan;
extern int prevTilt;
extern bool prevFireDetected;

extern unsigned long lastDHTRead;
extern unsigned long lastMQ2Read;
extern unsigned long lastFlameRead;
extern unsigned long lastFBWrite;
extern unsigned long lastFBCmdRead;
extern unsigned long lastHeartbeat;
extern unsigned long lastWiFiRetry;

extern int tiltStep;
extern unsigned long lastTiltStep;

#endif // SENSOR_STATE_H
