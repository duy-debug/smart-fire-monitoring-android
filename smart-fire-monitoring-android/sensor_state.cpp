// sensors/sensor_state.cpp

#include "sensor_state.h"

FirebaseData fbData;
FirebaseData fbStreamData;
FirebaseConfig fbConfig;
FirebaseAuth fbAuth;

DHT dht(DHT_PIN, DHT_TYPE);
Servo servoPan;
Servo servoTilt;

float temperature = 0.0f;
float humidity = 0.0f;
DHTStatus dhtStatus = DHT_OK;

int mq2Value = 0;
MQ2Level mq2Level = MQ2_SAFE;
int mq2Safe = MQ2_SAFE_DEFAULT;
int mq2Warning = MQ2_WARNING_DEFAULT;
float tempSafe = TEMP_SAFE_DEFAULT;
float tempWarning = TEMP_WARNING_DEFAULT;

bool flameDetected[5] = {false};
int flameRaw[5] = {4095, 4095, 4095, 4095, 4095};
bool anyFlameDetected = false;
int flamePriorityIdx = -1;
FlameDir flameDirection = DIR_NONE;

int confirmOnCount = 0;
int confirmOffCount = 0;
unsigned long lastConfirmRead = 0;

bool fireDetected = false;
SystemMode systemMode = MODE_AUTO;
bool pumpActive = false;
bool buzzerActive = false;
int currentPan = 90;
int currentTilt = TILT_HOME_ANGLE;
bool waitingForServo = false;
unsigned long fireTriggerTime = 0;
char currentLogKey[64] = "";
bool alertEnabled = true;
bool alertSnoozed = false;
bool sensorFusionAlert = false;

bool wifiConnected = false;
bool fbReady = false;
bool ntpSynced = false;
bool firebaseInitStarted = false;
bool firebaseDefaultsInitialized = false;
bool ntpInitStarted = false;
bool streamStarted = false;
unsigned long wifiStableSince = 0;
bool wifiConnecting = false;
unsigned long wifiConnectStart = 0;

bool sensorDataDirty = true;

float prevTemperature = -999;
float prevHumidity = -999;
int prevMq2Value = -1;
MQ2Level prevMq2Level = MQ2_SAFE;
bool prevFlameDetected[5] = {false};
bool prevAnyFlame = false;
bool prevPumpActive = false;
bool prevBuzzerActive = false;
int prevPan = -1;
int prevTilt = -1;
bool prevFireDetected = false;

unsigned long lastDHTRead = 0;
unsigned long lastMQ2Read = 0;
unsigned long lastFlameRead = 0;
unsigned long lastFBWrite = 0;
unsigned long lastFBCmdRead = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastWiFiRetry = 0;

int tiltStep = TILT_STEP_DEGREES;
unsigned long lastTiltStep = 0;
