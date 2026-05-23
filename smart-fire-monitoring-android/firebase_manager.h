// network/firebase_manager.h
#ifndef FIREBASE_MANAGER_H
#define FIREBASE_MANAGER_H

#include "config.h"
#include "sensor_state.h"

void initFirebase();
bool isFirebaseReady();
void initFirebaseDefaults();
void writeFirebaseData();
void sendHeartbeat();
void logFireEvent(const char *action);
void resolveLogEvent();
void triggerNotification();
void handleFirebaseCommands();
void syncThresholds();
void checkSnooze();
void streamCallback(FirebaseStream data);
void streamTimeoutCallback(bool timeout);
void startStream();
void handleFirebaseCycle();

#endif // FIREBASE_MANAGER_H