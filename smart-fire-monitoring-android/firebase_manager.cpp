// network/firebase_manager.cpp

#include "firebase_manager.h"

#include <Arduino.h>
#include <esp_task_wdt.h>

#include "addons/TokenHelper.h"
#include "addons/RTDBHelper.h"

#include "credentials.h"
#include "ntp_manager.h"
#include "pump_control.h"
#include "buzzer_control.h"
#include "servo_control.h"

#include <cstring>

// ─────────────────────────────────────────────────────────────
//  JSON BATCH OBJECT (Tối ưu #1: tránh allocate/free mỗi lần ghi)
// ─────────────────────────────────────────────────────────────
static FirebaseJson batchJson;

void initFirebase()
{
    if (firebaseInitStarted)
        return;

    firebaseInitStarted = true;

    fbConfig.api_key = FIREBASE_API_KEY;
    fbConfig.database_url = FIREBASE_DATABASE_URL;
    fbConfig.token_status_callback = tokenStatusCallback;
    fbConfig.max_token_generation_retry = 5;

    esp_task_wdt_reset(); // Feed WDT trước SSL handshake

    if (!Firebase.signUp(&fbConfig, &fbAuth, "", ""))
    {
        Serial.printf("[Firebase] signUp lỗi: %s\n",
                      fbConfig.signer.signupError.message.c_str());
    }
    else
    {
        Serial.println("[Firebase] Anonymous user đã được tạo.");
    }

    esp_task_wdt_reset(); // Feed WDT sau signUp

    Firebase.begin(&fbConfig, &fbAuth);
    Firebase.reconnectWiFi(true);

    // Set timeout cho các request Firebase (tránh block vô hạn khi mất mạng)
    fbData.setBSSLBufferSize(4096, 1024);
    fbData.setResponseSize(4096);

    // Timeout 15s cho mỗi request — nếu quá sẽ trả lỗi thay vì block
    fbConfig.timeout.serverResponse = 15 * 1000;
    fbConfig.timeout.socketConnection = 10 * 1000;
    fbConfig.timeout.sslHandshake = 15 * 1000;

    Serial.println("[Firebase] Đã bắt đầu khởi tạo (anonymous sign-in)...");
}

bool isFirebaseReady()
{
    return wifiConnected && wifiStableSince > 0 &&
           (millis() - wifiStableSince >= WIFI_STABLE_GRACE_MS) &&
           Firebase.ready();
}

void initFirebaseDefaults()
{
    if (firebaseDefaultsInitialized)
        return;
    if (!isFirebaseReady())
        return;

    FirebaseJson json;
    json.set("control/pump_on", false);
    json.set("control/buzzer_on", false);
    json.set("control/servo/axis_x", 90);
    json.set("control/servo/axis_y", TILT_HOME_ANGLE);
    json.set("system/last_seen", 0);

    esp_task_wdt_reset();

    if (Firebase.RTDB.updateNode(&fbData, FB_ROOT, &json))
    {
        firebaseDefaultsInitialized = true;
        Serial.println("[Firebase] Đã khởi tạo node debug mặc định.");
    }
    else
    {
        Serial.printf("[Firebase] Lỗi khởi tạo node debug: %s\n",
                      fbData.errorReason().c_str());
    }
}

void streamCallback(FirebaseStream data)
{
    Serial.printf("[Stream] Path: %s | Type: %s\n",
                  data.dataPath().c_str(), data.dataType().c_str());

    // Chỉ xử lý khi mode = manual VÀ không đang cháy
    if (systemMode != MODE_MANUAL || fireDetected)
        return;

    if (data.dataTypeEnum() == fb_esp_rtdb_data_type_json)
    {
        // Nhận toàn bộ node control/ (lần đầu stream kết nối)
        FirebaseJson &json = data.jsonObject();
        FirebaseJsonData jsonData;

        if (json.get(jsonData, "pump_on") && jsonData.type == "boolean")
            setPump(jsonData.boolValue);

        if (json.get(jsonData, "buzzer_on") && jsonData.type == "boolean")
            setBuzzer(jsonData.boolValue);

        int panCmd = currentPan, tiltCmd = currentTilt;
        if (json.get(jsonData, "servo/axis_x") && jsonData.type == "int")
            panCmd = jsonData.intValue;
        if (json.get(jsonData, "servo/axis_y") && jsonData.type == "int")
            tiltCmd = jsonData.intValue;
        updateServosManual(panCmd, tiltCmd);
    }
    else
    {
        // Nhận thay đổi từng field riêng lẻ
        if (data.dataPath() == "/pump_on")
            setPump(data.boolData());
        else if (data.dataPath() == "/buzzer_on")
            setBuzzer(data.boolData());
        else if (data.dataPath() == "/servo/axis_x")
            updateServosManual(data.intData(), currentTilt);
        else if (data.dataPath() == "/servo/axis_y")
            updateServosManual(currentPan, data.intData());
    }
}

void streamTimeoutCallback(bool timeout)
{
    if (timeout)
        Serial.println("[Stream] Timeout — tự động kết nối lại.");
}

void startStream()
{
    if (streamStarted)
        return;
    if (!isFirebaseReady())
        return;

    // Dừng stream cũ nếu có (giải phóng SSL session)
    Firebase.RTDB.endStream(&fbStreamData);
    fbStreamData.clear();

    fbStreamData.setBSSLBufferSize(2048, 512);

    esp_task_wdt_reset();

    if (Firebase.RTDB.beginStream(&fbStreamData, FB_ROOT "/control"))
    {
        Firebase.RTDB.setStreamCallback(&fbStreamData, streamCallback, streamTimeoutCallback);
        streamStarted = true;
        lastFBCmdRead = millis();
        Serial.println("[Stream] Đã bắt đầu lắng nghe node control/.");
    }
    else
    {
        Serial.printf("[Stream] Lỗi: %s\n", fbStreamData.errorReason().c_str());
    }
}

void writeFirebaseData()
{
    unsigned long interval = fireDetected ? FB_WRITE_INTERVAL_FIRE : FB_WRITE_INTERVAL_NORMAL;

    if (millis() - lastFBWrite < interval)
        return;
    lastFBWrite = millis();
    if (!isFirebaseReady())
        return;

    // Kiểm tra dirty flag
    if (temperature != prevTemperature || humidity != prevHumidity)
        sensorDataDirty = true;
    if (mq2Value != prevMq2Value || mq2Level != prevMq2Level)
        sensorDataDirty = true;
    if (anyFlameDetected != prevAnyFlame)
        sensorDataDirty = true;
    if (pumpActive != prevPumpActive || buzzerActive != prevBuzzerActive)
        sensorDataDirty = true;
    if (currentPan != prevPan || currentTilt != prevTilt)
        sensorDataDirty = true;
    if (fireDetected != prevFireDetected)
        sensorDataDirty = true;

    for (int i = 0; i < 5; i++)
    {
        if (flameDetected[i] != prevFlameDetected[i])
        {
            sensorDataDirty = true;
            break;
        }
    }

    if (!sensorDataDirty)
        return;

    batchJson.clear();

    // Sensors: DHT11
    batchJson.set("sensors/dht11/temperature", temperature);
    batchJson.set("sensors/dht11/humidity", humidity);
    batchJson.set("sensors/dht11/status", DHT_STATUS_STR[dhtStatus]);

    // Sensors: MQ-2
    batchJson.set("sensors/mq2/value", mq2Value);
    batchJson.set("sensors/mq2/level", MQ2_LEVEL_STR[mq2Level]);
    batchJson.set("sensors/mq2/status", "ok");

    // Sensors: 5 mắt cảm biến lửa
    for (int i = 0; i < 5; i++)
    {
        char key[32];
        snprintf(key, sizeof(key), "sensors/flame/eye_%d", i + 1);
        batchJson.set(key, flameDetected[i] ? 1 : 0);
        snprintf(key, sizeof(key), "sensors/flame/eye_%d_raw", i + 1);
        batchJson.set(key, flameRaw[i]);
    }
    batchJson.set("sensors/flame/any_detected", anyFlameDetected);
    batchJson.set("sensors/flame/direction", FLAME_DIR_STR[flameDirection]);
    batchJson.set("sensors/flame/status", "ok");

    // Actuators
    batchJson.set("actuators/servo/axis_x", currentPan);
    batchJson.set("actuators/servo/axis_y", currentTilt);
    batchJson.set("actuators/pump", pumpActive);
    batchJson.set("actuators/buzzer", buzzerActive);
    batchJson.set("actuators/auto_pump_active", pumpActive && fireDetected);

    // System
    batchJson.set("system/fire_detected", fireDetected);
    batchJson.set("system/mode", SYSTEM_MODE_STR[systemMode]);
    batchJson.set("system/firmware_version", FIRMWARE_VERSION);
    batchJson.set("system/sensor_fusion_alert", sensorFusionAlert);

    esp_task_wdt_reset(); // Feed WDT trước batch write

    if (!Firebase.RTDB.updateNode(&fbData, FB_ROOT, &batchJson))
    {
        Serial.printf("[Firebase] Lỗi ghi batch: %s\n",
                      fbData.errorReason().c_str());
    }
    else
    {
        sensorDataDirty = false;
        prevTemperature = temperature;
        prevHumidity = humidity;
        prevMq2Value = mq2Value;
        prevMq2Level = mq2Level;
        prevAnyFlame = anyFlameDetected;
        prevPumpActive = pumpActive;
        prevBuzzerActive = buzzerActive;
        prevPan = currentPan;
        prevTilt = currentTilt;
        prevFireDetected = fireDetected;
        for (int i = 0; i < 5; i++)
            prevFlameDetected[i] = flameDetected[i];
    }
}

void sendHeartbeat()
{
    if (millis() - lastHeartbeat < 5000)
        return;
    if (!isFirebaseReady())
        return;
    if (!checkNTPSynced())
    {
        lastHeartbeat = millis();
        Serial.println("[Heartbeat] Bỏ qua last_seen vì NTP chưa đồng bộ.");
        return;
    }

    time_t now = getTimestamp();
    if (Firebase.RTDB.setInt(&fbData, FB_ROOT "/system/last_seen", (int)now))
    {
        lastHeartbeat = millis();
    }
    else
    {
        Serial.printf("[Heartbeat] Lỗi ghi last_seen: %s\n",
                      fbData.errorReason().c_str());
    }
}

void logFireEvent(const char *action)
{
    if (!isFirebaseReady())
        return;

    FirebaseJson log;
    log.set("timestamp", (int)getTimestamp());
    log.set("time_readable", getTimeReadable());
    log.set("temperature", temperature);
    log.set("humidity", humidity);
    log.set("mq2_value", mq2Value);
    log.set("mq2_level", MQ2_LEVEL_STR[mq2Level]);
    log.set("flame_direction", FLAME_DIR_STR[flameDirection]);

    char pattern[6] = "00000";
    for (int i = 0; i < 5; i++)
        if (flameDetected[i])
            pattern[i] = '1';
    pattern[5] = '\0';

    log.set("flame_pattern", pattern);
    log.set("servo_x_at_event", currentPan);
    log.set("servo_y_at_event", currentTilt);
    log.set("action_taken", action);
    log.set("pump_activated", true);
    log.set("buzzer_activated", true);
    log.set("alert_was_snoozed", false);
    log.set("sensor_fusion_triggered", sensorFusionAlert);
    log.set("resolved_at", 0);

    if (Firebase.RTDB.pushJSON(&fbData, FB_ROOT "/logs", &log))
    {
        strncpy(currentLogKey, fbData.pushName().c_str(), sizeof(currentLogKey) - 1);
        currentLogKey[sizeof(currentLogKey) - 1] = '\0';
        Serial.printf("[Firebase] Log OK. Key: %s\n", currentLogKey);
    }
    else
    {
        Serial.printf("[Firebase] Lỗi ghi log: %s\n",
                      fbData.errorReason().c_str());
    }
}

void resolveLogEvent()
{
    if (!isFirebaseReady())
        return;
    if (currentLogKey[0] == '\0')
        return;

    char path[128];
    snprintf(path, sizeof(path), "%s/logs/%s/resolved_at", FB_ROOT, currentLogKey);
    Firebase.RTDB.setInt(&fbData, path, (int)getTimestamp());
    currentLogKey[0] = '\0';
}

void triggerNotification()
{
    if (!isFirebaseReady())
    {
        Serial.println("[Firebase] Báo cháy: Lỗi, Firebase chưa sẵn sàng để gửi notification.");
        return;
    }

    FirebaseJson alertJson;
    alertJson.set("notification_sent", true);
    alertJson.set("last_triggered", (int)getTimestamp());

    if (Firebase.RTDB.updateNode(&fbData, FB_ROOT "/alert", &alertJson))
    {
        Serial.println("[Firebase] Đã kích hoạt báo cháy (alert/notification_sent = true) thành công!");
    }
    else
    {
        Serial.printf("[Firebase] Lỗi kích hoạt báo cháy: %s\n", fbData.errorReason().c_str());
    }
}

void syncThresholds()
{
    if (!isFirebaseReady())
        return;

    if (!Firebase.RTDB.getBool(&fbData, FB_ROOT "/thresholds/updated"))
        return;
    if (!fbData.boolData())
        return;

    Serial.println("[Threshold] Cập nhật ngưỡng từ App.");

    esp_task_wdt_reset();

    // Tối ưu #2: Đọc toàn bộ node thresholds/ bằng 1 request
    if (Firebase.RTDB.getJSON(&fbData, FB_ROOT "/thresholds"))
    {
        FirebaseJson &json = fbData.jsonObject();
        FirebaseJsonData jsonData;

        if (json.get(jsonData, "mq2_safe") && jsonData.type == "int")
            mq2Safe = jsonData.intValue;
        if (json.get(jsonData, "mq2_warning") && jsonData.type == "int")
            mq2Warning = jsonData.intValue;
        if (json.get(jsonData, "temp_safe") && jsonData.type == "float")
            tempSafe = jsonData.floatValue;
        if (json.get(jsonData, "temp_warning") && jsonData.type == "float")
            tempWarning = jsonData.floatValue;
    }

    Firebase.RTDB.setBool(&fbData, FB_ROOT "/thresholds/updated", false);

    Serial.printf("[Threshold] Mới — MQ2: %d/%d | Temp: %.1f/%.1f\n",
                  mq2Safe, mq2Warning, tempSafe, tempWarning);
}

void checkSnooze()
{
    if (!isFirebaseReady() || !ntpSynced)
        return;

    if (!Firebase.RTDB.getBool(&fbData, FB_ROOT "/alert/snoozed"))
        return;
    if (!fbData.boolData())
        return;

    if (!Firebase.RTDB.getInt(&fbData, FB_ROOT "/alert/snooze_until"))
        return;
    int snoozeUntil = fbData.intData();

    if (snoozeUntil > 0 && (int)getTimestamp() >= snoozeUntil)
    {
        Firebase.RTDB.setBool(&fbData, FB_ROOT "/alert/snoozed", false);
        Firebase.RTDB.setInt(&fbData, FB_ROOT "/alert/snooze_until", 0);
        Serial.println("[Alert] Snooze hết hạn — cảnh báo bật lại.");
    }
}

/*
 * Tối ưu #2: handleFirebaseCommands() giờ chỉ xử lý:
 *   - Đồng bộ ngưỡng (khi App set updated=true)
 *   - Kiểm tra snooze
 *   - Đọc mode (1 request thay vì 6-8 request cũ)
 *
 * Lệnh control/ (pump, buzzer, servo) được xử lý qua Stream callback
 * → ESP32 nhận lệnh ngay lập tức khi App ghi, không bị trễ 500ms
 */
void handleFirebaseCommands()
{
    if (millis() - lastFBCmdRead < FB_CMD_READ_INTERVAL_MS)
        return;
    lastFBCmdRead = millis();
    if (!isFirebaseReady())
        return;

    esp_task_wdt_reset();

    // 1. Đồng bộ ngưỡng và snooze
    syncThresholds();
    checkSnooze();

    // Đọc cờ enabled và snoozed của hệ thống cảnh báo từ Firebase
    if (Firebase.RTDB.getBool(&fbData, FB_ROOT "/alert/enabled"))
    {
        alertEnabled = fbData.boolData();
    }
    else
    {
        alertEnabled = true; // Mặc định bật nếu chưa có cấu hình
    }

    if (Firebase.RTDB.getBool(&fbData, FB_ROOT "/alert/snoozed"))
    {
        alertSnoozed = fbData.boolData();
    }
    else
    {
        alertSnoozed = false;
    }

    esp_task_wdt_reset();

    // 2. Đọc chế độ hoạt động
    if (Firebase.RTDB.getString(&fbData, FB_ROOT "/system/mode"))
    {
        if (fbData.stringData() == "manual")
            systemMode = MODE_MANUAL;
        else
            systemMode = MODE_AUTO;
    }

    // 3. Ưu tiên an toàn: khi cháy → luôn auto
    if (fireDetected && systemMode != MODE_AUTO)
    {
        systemMode = MODE_AUTO;
        Firebase.RTDB.setString(&fbData, FB_ROOT "/system/mode", "auto");
        Serial.println("[Safety] Đang cháy — ép AUTO, bỏ qua lệnh manual.");
    }
}

void handleFirebaseCycle()
{
    const unsigned long FB_SLOW_CALL_WARN_MS = 200;

    if (!wifiConnected)
        return;

    // Khởi tạo Firebase lần đầu có mạng
    if (!firebaseInitStarted &&
        wifiStableSince > 0 &&
        (millis() - wifiStableSince >= WIFI_STABLE_GRACE_MS))
    {
        initFirebase();
    }

    if (!fbReady && Firebase.ready())
    {
        fbReady = true;
        Serial.println("[Firebase] Token đã sẵn sàng.");
    }

    if (fbReady && !firebaseDefaultsInitialized)
    {
        initFirebaseDefaults();
    }

    // Khởi động Stream cho node control/
    if (fbReady && !streamStarted)
    {
        startStream();
    }

    if (!ntpSynced)
    {
        if (!ntpInitStarted)
        {
            initNTP();
            ntpInitStarted = true;
        }
        checkNTPSynced();
    }

    // Đọc mode/ngưỡng trước để state ghi lên Firebase không bị lùi 1 nhịp
    unsigned long t0 = millis();
    handleFirebaseCommands(); // Đồng bộ ngưỡng + mode (1s/lần)
    unsigned long dt = millis() - t0;
    if (dt >= FB_SLOW_CALL_WARN_MS)
        Serial.printf("[Firebase][Perf] handleFirebaseCommands slow: %lu ms\n", dt);

    t0 = millis();
    writeFirebaseData(); // Batch (2s bình thường / 500ms khi cháy)
    dt = millis() - t0;
    if (dt >= FB_SLOW_CALL_WARN_MS)
        Serial.printf("[Firebase][Perf] writeFirebaseData slow: %lu ms\n", dt);

    t0 = millis();
    sendHeartbeat(); // Cập nhật last_seen (5s/lần)
    dt = millis() - t0;
    if (dt >= FB_SLOW_CALL_WARN_MS)
        Serial.printf("[Firebase][Perf] sendHeartbeat slow: %lu ms\n", dt);
}
