/*
 * ============================================================
 *  HỆ THỐNG CHỮA CHÁY THÔNG MINH — ESP32
 *  Nhóm 1 · Lập Trình Thiết Bị Di Động · 65CNTT2
 * ============================================================
 *  Phần cứng:
 *    - DHT11         → GPIO4  (nhiệt độ / độ ẩm): powered with 3.3V
 *    - MQ-2 AO       → GPIO32 (nồng độ khí — ADC1): + resistor 10kΩ từ 3.3V → chân AO để tạo điện áp tham chiếu, an toàn khi Wi-Fi hoạt động
 *    - MQ-2 DO       → GPIO15 (ngưỡng số)
 *    - Flame #1,3,5 AO → GPIO33,34,35 (ADC1 — an toàn khi Wi-Fi bật)
 *    - Flame #2,4 AO → GPIO36,39 (nếu board không có chân này thì raw = 0 lên Firebase)
 *    - Flame #1–5 DO → GPIO13,25,14,27,26
 *    - Servo Pan     → GPIO18 (PWM — quay trái/phải)
 *    - Servo Tilt    → GPIO5  (PWM — ngẩng lên/xuống)
 *    - Relay 5V IN   → GPIO19 (HIGH = đóng relay = bật bơm)
 *    - Relay 5V IN   → GPIO2  (HIGH = đóng relay = bật còi)
 *
 *  Thư viện cần cài (Library Manager):
 *    - DHT sensor library (Adafruit)
 *    - Adafruit Unified Sensor
 *    - ESP32Servo
 *    - Firebase ESP32 Client (Mobizt)
 * ============================================================
 */

#include <WiFi.h>
#include <time.h>
#include <DHT.h>
#include <ESP32Servo.h>
#include <Firebase_ESP_Client.h>

// Helper để dùng RTDB và các tính năng khác của Firebase-ESP-Client
#include "addons/TokenHelper.h" // Callback xử lý token
#include "addons/RTDBHelper.h"  // Helper in dữ liệu RTDB (tuỳ chọn)

#include "credentials.h"

// ─────────────────────────────────────────────────────────────
//  NODE GỐC FIREBASE
// ─────────────────────────────────────────────────────────────
#define FB_ROOT "/fire-alarm-system"

// ─────────────────────────────────────────────────────────────
//  CHÂN GPIO
// ─────────────────────────────────────────────────────────────
#define DHT_PIN 4
#define DHT_TYPE DHT11

#define MQ2_AO_PIN 32 // ADC1 — an toàn khi Wi-Fi bật
#define MQ2_DO_PIN 15

// AO: cường độ hồng ngoại (ADC1), DO: nhị phân (active-LOW)
// Mắt #2 và #4 dùng chân dự phòng 36/39; nếu board không có header thì code sẽ ghi raw = 0.
const int FLAME_AO_PINS[5] = {33, 36, 34, 39, 35};
const int FLAME_DO_PINS[5] = {13, 25, 14, 27, 26};
const bool FLAME_AO_ENABLED[5] = {true, false, true, false, true};

#define SERVO_PAN_PIN 18 // Trục X — quay trái/phải
#define SERVO_TILT_PIN 5 // Trục Y — ngẩng/cúi

#define RELAY_PIN 19 // Relay bơm: active-HIGH
#define BUZZER_PIN 2 // Buzzer qua transistor NPN BC547

// ─────────────────────────────────────────────────────────────
//  ÁNH XẠ GÓC SERVO
// ─────────────────────────────────────────────────────────────
// Góc Pan theo từng mắt: #1(trái xa)=0° ... #5(phải xa)=180°
const int PAN_ANGLES[5] = {0, 45, 90, 135, 180};

// Tilt nội suy từ ADC:
//   ADC thấp (lửa gần) → Tilt nhỏ (hạ vòi phun vào gốc lửa)
//   ADC cao  (lửa xa)  → Tilt lớn (ngẩng vòi, nước bay xa)
#define TILT_MIN 30
#define TILT_MAX 90
#define ADC_NEAR 0
#define ADC_FAR 2048

const String DIRECTIONS[5] = {
    "left", "center-left", "center", "center-right", "right"};

// ─────────────────────────────────────────────────────────────
//  NGƯỠNG MẶC ĐỊNH (cập nhật động từ Firebase)
// ─────────────────────────────────────────────────────────────
int mq2Safe = 800;
int mq2Warning = 1500;
float tempSafe = 40.0f;
float tempWarning = 60.0f;

// ─────────────────────────────────────────────────────────────
//  ĐỐI TƯỢNG FIREBASE-ESP-CLIENT
//  FirebaseAuth  → lưu thông tin đăng nhập (anonymous UID, token)
//  FirebaseConfig → cấu hình project (API key, database URL)
//  FirebaseData   → đối tượng truyền nhận dữ liệu RTDB
// ─────────────────────────────────────────────────────────────
FirebaseData fbData;
FirebaseConfig fbConfig;
FirebaseAuth fbAuth;

// ─────────────────────────────────────────────────────────────
//  ĐỐI TƯỢNG PHẦN CỨNG
// ─────────────────────────────────────────────────────────────
DHT dht(DHT_PIN, DHT_TYPE);
Servo servoPan;
Servo servoTilt;

// ─────────────────────────────────────────────────────────────
//  BIẾN TRẠNG THÁI CẢM BIẾN
// ─────────────────────────────────────────────────────────────
float temperature = 0.0f;
float humidity = 0.0f;
String dhtStatus = "ok";

int mq2Value = 0;
String mq2Level = "safe";

bool flameDetected[5] = {false};
int flameRaw[5] = {4095, 4095, 4095, 4095, 4095};
bool anyFlameDetected = false;
int flamePriorityIdx = -1;
String flameDirection = "none";

// ─────────────────────────────────────────────────────────────
//  DEBOUNCE CẢM BIẾN LỬA
//  Xác nhận CÓ/KHÔNG lửa sau >= CONFIRM_THRESHOLD lần đọc
//  liên tiếp, mỗi lần cách CONFIRM_INTERVAL_MS ms
// ─────────────────────────────────────────────────────────────
#define CONFIRM_THRESHOLD 3
#define CONFIRM_INTERVAL_MS 50

int confirmOnCount = 0;
int confirmOffCount = 0;
unsigned long lastConfirmRead = 0;

// ─────────────────────────────────────────────────────────────
//  BIẾN TRẠNG THÁI HỆ THỐNG
// ─────────────────────────────────────────────────────────────
bool fireDetected = false;
String systemMode = "auto"; // "auto" | "manual"
bool pumpActive = false;
bool buzzerActive = false;
int currentPan = 90;
int currentTilt = 60;
bool waitingForServo = false;
unsigned long fireTriggerTime = 0;
String currentLogKey = ""; // Key log hiện tại để ghi resolved_at

// ─────────────────────────────────────────────────────────────
//  BIẾN TRẠNG THÁI KẾT NỐI
// ─────────────────────────────────────────────────────────────
bool wifiConnected = false;
bool fbReady = false; // true khi Firebase đã sign-in thành công
bool ntpSynced = false;
bool firebaseInitStarted = false;
bool firebaseDefaultsInitialized = false;
bool ntpInitStarted = false;
unsigned long wifiStableSince = 0;

const unsigned long WIFI_STABLE_GRACE_MS = 3000;

// ─────────────────────────────────────────────────────────────
//  TIMER NON-BLOCKING
// ─────────────────────────────────────────────────────────────
unsigned long lastDHTRead = 0;
unsigned long lastMQ2Read = 0;
unsigned long lastFlameRead = 0;
unsigned long lastFBWrite = 0;
unsigned long lastFBCmdRead = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastWiFiRetry = 0;
unsigned long wifiRetryInterval = 1000; // Exponential backoff

// ─────────────────────────────────────────────────────────────
//  PHIÊN BẢN
// ─────────────────────────────────────────────────────────────
#define FIRMWARE_VERSION "2.1.0"

// ════════════════════════════════════════════════════════════
//  PHẦN 1 — KẾT NỐI MẠNG, FIREBASE AUTH, THỜI GIAN
// ════════════════════════════════════════════════════════════

/*
 * Bắt đầu kết nối Wi-Fi — non-blocking.
 * Chỉ gọi WiFi.begin() rồi trả về ngay.
 * Trạng thái kết nối kiểm tra trong manageWiFi().
 */
void startWiFi()
{
  Serial.printf("[WiFi] Đang kết nối tới \"%s\"...\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

/*
 * Quản lý Wi-Fi định kỳ trong loop().
 * Thử kết nối lại theo exponential backoff khi mất mạng:
 * 1s → 2s → 4s → 8s → 16s → 30s (tối đa).
 */
void manageWiFi()
{
  bool prev = wifiConnected;
  wifiConnected = (WiFi.status() == WL_CONNECTED);

  if (wifiConnected)
  {
    wifiRetryInterval = 1000; // Reset backoff
    if (!prev)
    {
      wifiStableSince = millis();
      Serial.printf("[WiFi] Đã kết nối. IP: %s\n",
                    WiFi.localIP().toString().c_str());
    }
    return;
  }

  wifiStableSince = 0;

  if (millis() - lastWiFiRetry >= wifiRetryInterval)
  {
    lastWiFiRetry = millis();
    wifiRetryInterval = min(wifiRetryInterval * 2UL, 30000UL);
    Serial.printf("[WiFi] Thử kết nối lại... (backoff %lu ms)\n",
                  wifiRetryInterval);
    WiFi.disconnect();
    WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
  }
}

/*
 * Khởi tạo Firebase với Anonymous Authentication.
 *
 * Luồng hoạt động của Firebase-ESP-Client:
 *   1. Cấu hình API key và database URL
 *   2. Không set fbAuth.user → thư viện tự nhận đây là anonymous sign-in
 *   3. Firebase.begin() khởi động; thư viện tự gọi REST API
 *      POST /v1/accounts:signUp?key={apiKey} → nhận idToken + refreshToken
 *   4. Token tự động làm mới trước khi hết hạn (mỗi ~1 giờ)
 *   5. tokenStatusCallback() được gọi khi token ready/error
 *
 * Chỉ gọi một lần sau khi Wi-Fi đã kết nối.
 */
void initFirebase()
{
  if (firebaseInitStarted)
    return;

  firebaseInitStarted = true;

  // API Key (Web API Key) — KHÔNG phải Database Secret cũ
  fbConfig.api_key = FIREBASE_API_KEY;

  // URL Realtime Database
  fbConfig.database_url = FIREBASE_DATABASE_URL;

  // Callback theo dõi trạng thái token (từ addons/TokenHelper.h)
  // In ra Serial khi token đang tạo, ready, hoặc lỗi
  fbConfig.token_status_callback = tokenStatusCallback;

  // Thời gian thử lại khi tạo token thất bại (ms)
  fbConfig.max_token_generation_retry = 5;

  /*
   * Anonymous sign-in:
   *   Thư viện này cần signUp với chuỗi rỗng để tạo user ẩn danh.
   *   Sau đó mới gọi Firebase.begin() với auth/config đã được tạo.
   */
  if (!Firebase.signUp(&fbConfig, &fbAuth, "", ""))
  {
    Serial.printf("[Firebase] signUp lỗi: %s\n",
                  fbConfig.signer.signupError.message.c_str());
  }
  else
  {
    Serial.println("[Firebase] Anonymous user đã được tạo.");
  }

  Firebase.begin(&fbConfig, &fbAuth);
  Firebase.reconnectWiFi(true);

  // Buffer size để tránh overflow khi đọc/ghi JSON lớn
  fbData.setBSSLBufferSize(4096, 1024);
  fbData.setResponseSize(4096);

  Serial.println("[Firebase] Đã bắt đầu khởi tạo (anonymous sign-in)...");
  Serial.println("[Firebase] Chờ token ready — xem log bên dưới.");
}

/*
 * Tạo sẵn các node debug trên Firebase một lần khi token đã sẵn sàng.
 * Mục tiêu là để kiểm tra realtime database trước khi app Android được nối vào.
 */
void initFirebaseDefaults()
{
  if (firebaseDefaultsInitialized)
    return;
  if (!isFirebaseReady())
    return;

  FirebaseJson json;
  json.set("thresholds/updated", false);
  json.set("control/pump_on", false);
  json.set("control/buzzer_on", false);
  json.set("control/servo/axis_x", 90);
  json.set("control/servo/axis_y", 90);

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

/*
 * Kiểm tra Firebase đã sẵn sàng chưa (token đã được cấp).
 * Firebase.ready() trả về true khi:
 *   - Wi-Fi đã kết nối
 *   - Anonymous sign-in thành công
 *   - idToken hợp lệ (chưa hết hạn hoặc đã làm mới)
 *
 * Gọi trong loop() thay vì kiểm tra fbReady tĩnh.
 */
bool isFirebaseReady()
{
  return wifiConnected && wifiStableSince > 0 &&
         (millis() - wifiStableSince >= WIFI_STABLE_GRACE_MS) &&
         Firebase.ready();
}

/*
 * Đồng bộ thời gian NTP — gọi sau khi có Wi-Fi.
 * Múi giờ Việt Nam: UTC+7 = 25200 giây offset, không có DST.
 */
void initNTP()
{
  configTime(7 * 3600, 0, "pool.ntp.org", "time.nist.gov");
  Serial.println("[NTP] Đang đồng bộ thời gian...");
}

/*
 * Kiểm tra NTP đã đồng bộ chưa.
 * Điều kiện: năm >= 2024 (tránh nhầm với epoch mặc định 1970).
 */
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

/* Lấy Unix timestamp UTC. Trả về 0 nếu NTP chưa sẵn sàng. */
time_t getTimestamp()
{
  if (!ntpSynced)
    return 0;
  time_t now;
  time(&now);
  return now;
}

/* Chuỗi thời gian "yyyy-MM-dd HH:mm:ss" để ghi vào logs/. */
String getTimeReadable()
{
  if (!ntpSynced)
    return "NTP not synced";
  struct tm ti;
  getLocalTime(&ti);
  char buf[20];
  strftime(buf, sizeof(buf), "%Y-%m-%d %H:%M:%S", &ti);
  return String(buf);
}

// ════════════════════════════════════════════════════════════
//  PHẦN 2 — KHỞI ĐỘNG PHẦN CỨNG
// ════════════════════════════════════════════════════════════

void setupServos()
{
  // Dải xung chuẩn MG90S: 500µs = 0°, 2400µs = 180°
  servoPan.attach(SERVO_PAN_PIN, 500, 2400);
  servoTilt.attach(SERVO_TILT_PIN, 500, 2400);
  servoPan.write(90);
  servoTilt.write(60);
  currentPan = 90;
  currentTilt = 60;
  Serial.println("[Servo] Khởi động về trung tâm (Pan=90°, Tilt=60°).");
}

void setupRelay()
{
  pinMode(RELAY_PIN, OUTPUT);
  // Fail-safe: LOW = relay TẮT = bơm TẮT khi khởi động
  digitalWrite(RELAY_PIN, LOW);
  Serial.println("[Relay] Khởi động — bơm TẮT.");
}

void setupBuzzer()
{
  pinMode(BUZZER_PIN, OUTPUT);
  // LOW = transistor BC547 tắt = còi TẮT (tránh kêu khi reset)
  digitalWrite(BUZZER_PIN, LOW);
  Serial.println("[Buzzer] Khởi động — còi TẮT.");
}

void setupFlamePins()
{
  for (int i = 0; i < 5; i++)
  {
    // INPUT_PULLUP: giữ mức HIGH ổn định khi không có lửa
    // Tránh đọc nhiễu khi dây tín hiệu DO hở
    pinMode(FLAME_DO_PINS[i], INPUT_PULLUP);
  }
}

// ════════════════════════════════════════════════════════════
//  PHẦN 3 — ĐỌC CẢM BIẾN
// ════════════════════════════════════════════════════════════

/* Đọc DHT11 mỗi 2000ms. NaN → ghi "error", giữ giá trị cũ. */
void readDHT11()
{
  if (millis() - lastDHTRead < 2000)
    return;
  lastDHTRead = millis();

  float t = dht.readTemperature();
  float h = dht.readHumidity();

  if (isnan(t) || isnan(h))
  {
    dhtStatus = "error";
    Serial.println("[DHT11] Lỗi đọc — giữ nguyên giá trị cũ.");
    return;
  }
  temperature = t;
  humidity = h;
  dhtStatus = "ok";
  Serial.printf("[DHT11] %.1f°C | %.1f%%\n", t, h);
}

/* Đọc MQ-2 mỗi 500ms. Phân mức theo ngưỡng từ Firebase. */
void readMQ2()
{
  if (millis() - lastMQ2Read < 500)
    return;
  lastMQ2Read = millis();

  mq2Value = analogRead(MQ2_AO_PIN); // 0–4095 (12-bit ADC1)

  String prev = mq2Level;
  if (mq2Value < mq2Safe)
    mq2Level = "safe";
  else if (mq2Value < mq2Warning)
    mq2Level = "warning";
  else
    mq2Level = "danger";

  if (mq2Level != prev)
  {
    Serial.printf("[MQ-2] %d → %s\n", mq2Value, mq2Level.c_str());
  }
}

/*
 * Đọc cụm 5 cảm biến lửa mỗi 100ms với debounce 2 chiều.
 *
 * Debounce CÓ lửa:
 *   Raw báo lửa → confirmOnCount tăng mỗi CONFIRM_INTERVAL_MS
 *   confirmOnCount >= CONFIRM_THRESHOLD → xác nhận anyFlameDetected = true
 *
 * Debounce TẮT lửa:
 *   Raw không lửa → confirmOffCount tăng
 *   confirmOffCount >= CONFIRM_THRESHOLD → xác nhận anyFlameDetected = false
 *
 * Hướng ưu tiên: mắt có ADC thấp nhất trong số mắt đang báo lửa
 * (ADC thấp = IR mạnh = lửa gần về phía đó).
 */
void readFlameSensors()
{
  if (millis() - lastFlameRead < 100)
    return;
  lastFlameRead = millis();

  bool rawAny = false;
  int minRaw = 4095;
  int minIdx = -1;

  for (int i = 0; i < 5; i++)
  {
    flameDetected[i] = (digitalRead(FLAME_DO_PINS[i]) == LOW); // active-LOW
    flameRaw[i] = FLAME_AO_ENABLED[i] ? analogRead(FLAME_AO_PINS[i]) : 0;

    if (flameDetected[i])
    {
      rawAny = true;
      if (flameRaw[i] < minRaw)
      {
        minRaw = flameRaw[i];
        minIdx = i;
      }
    }
  }

  unsigned long now = millis();

  if (rawAny)
  {
    confirmOffCount = 0;
    if (now - lastConfirmRead >= CONFIRM_INTERVAL_MS)
    {
      confirmOnCount++;
      lastConfirmRead = now;
      if (confirmOnCount > CONFIRM_THRESHOLD * 2)
        confirmOnCount = CONFIRM_THRESHOLD;
    }
  }
  else
  {
    confirmOnCount = 0;
    confirmOffCount++;
    if (confirmOffCount > CONFIRM_THRESHOLD * 2)
      confirmOffCount = CONFIRM_THRESHOLD;
  }

  // Chuyển trạng thái sau khi đủ ngưỡng xác nhận
  if (confirmOnCount >= CONFIRM_THRESHOLD && !anyFlameDetected)
  {
    anyFlameDetected = true;
    flamePriorityIdx = minIdx;
    flameDirection = (minIdx >= 0) ? DIRECTIONS[minIdx] : "none";
    Serial.printf("[Flame] ✓ CÓ lửa! Hướng: %s (mắt #%d, ADC=%d)\n",
                  flameDirection.c_str(), minIdx + 1, minRaw);
  }
  else if (confirmOffCount >= CONFIRM_THRESHOLD && anyFlameDetected)
  {
    anyFlameDetected = false;
    flamePriorityIdx = -1;
    flameDirection = "none";
    Serial.println("[Flame] ✓ Lửa đã TẮT.");
  }
  else if (anyFlameDetected && minIdx >= 0)
  {
    // Cập nhật hướng liên tục nếu lửa di chuyển
    flamePriorityIdx = minIdx;
    flameDirection = DIRECTIONS[minIdx];
  }
}

// ════════════════════════════════════════════════════════════
//  PHẦN 4 — ĐIỀU KHIỂN CHẤP HÀNH
// ════════════════════════════════════════════════════════════

/* Nội suy góc Tilt từ ADC: ADC thấp → Tilt nhỏ (lửa gần). */
int calcTiltAngle(int adcValue)
{
  adcValue = constrain(adcValue, ADC_NEAR, ADC_FAR);
  return map(adcValue, ADC_NEAR, ADC_FAR, TILT_MIN, TILT_MAX);
}

/* Servo chế độ AUTO — tra bảng Pan, nội suy Tilt. */
void updateServosAuto(int priorityIdx)
{
  if (priorityIdx < 0 || priorityIdx > 4)
    return;

  int newPan = PAN_ANGLES[priorityIdx];
  int newTilt = calcTiltAngle(flameRaw[priorityIdx]);

  if (newPan == currentPan && newTilt == currentTilt)
    return;

  currentPan = newPan;
  currentTilt = newTilt;
  servoPan.write(currentPan);
  servoTilt.write(currentTilt);
  Serial.printf("[Servo AUTO] Pan=%d° | Tilt=%d°\n", currentPan, currentTilt);
}

/* Servo chế độ MANUAL — giới hạn 0°–180° bảo vệ servo. */
void updateServosManual(int pan, int tilt)
{
  currentPan = constrain(pan, 0, 180);
  currentTilt = constrain(tilt, 0, 180);
  servoPan.write(currentPan);
  servoTilt.write(currentTilt);
  Serial.printf("[Servo MANUAL] Pan=%d° | Tilt=%d°\n", currentPan, currentTilt);
}

/*
 * Bật/tắt bơm qua relay.
 *   true  → GPIO19=HIGH → relay BẬT → bơm BẬT
 *   false → GPIO19=LOW  → relay TẮT → bơm TẮT (fail-safe)
 */
void setPump(bool state)
{
  if (pumpActive == state)
    return;
  pumpActive = state;
  digitalWrite(RELAY_PIN, state ? HIGH : LOW);
  Serial.printf("[Bơm] %s\n", state ? "BẬT" : "TẮT");
}

/*
 * Bật/tắt còi qua transistor BC547.
 *   true  → GPIO2=HIGH → transistor dẫn → còi BẬT
 *   false → GPIO2=LOW  → transistor tắt → còi TẮT
 */
void setBuzzer(bool state)
{
  if (buzzerActive == state)
    return;
  buzzerActive = state;
  digitalWrite(BUZZER_PIN, state ? HIGH : LOW);
  Serial.printf("[Còi] %s\n", state ? "BẬT" : "TẮT");
}

// ════════════════════════════════════════════════════════════
//  PHẦN 5 — FIREBASE: GHI DỮ LIỆU BATCH
// ════════════════════════════════════════════════════════════

/*
 * Ghi toàn bộ dữ liệu sensor + trạng thái bằng MỘT lần
 * Firebase.updateNode() (JSON batch update).
 *
 * API Firebase-ESP-Client:
 *   Firebase.RTDB.updateNode(&fbData, path, &json)
 *   → Thực hiện PATCH request, chỉ ghi đè các key được chỉ định,
 *     không xoá các key khác trong cùng node.
 */
void writeFirebaseData()
{
  if (millis() - lastFBWrite < 500)
    return;
  lastFBWrite = millis();
  if (!isFirebaseReady())
    return;

  FirebaseJson json;

  // Sensors: DHT11
  json.set("sensors/dht11/temperature", temperature);
  json.set("sensors/dht11/humidity", humidity);
  json.set("sensors/dht11/status", dhtStatus);

  // Sensors: MQ-2
  json.set("sensors/mq2/value", mq2Value);
  json.set("sensors/mq2/level", mq2Level);
  json.set("sensors/mq2/status", "ok");

  // Sensors: 5 mắt cảm biến lửa
  for (int i = 0; i < 5; i++)
  {
    json.set("sensors/flame/eye_" + String(i + 1),
             flameDetected[i] ? 1 : 0);
    json.set("sensors/flame/eye_" + String(i + 1) + "_raw",
             flameRaw[i]);
  }
  json.set("sensors/flame/any_detected", anyFlameDetected);
  json.set("sensors/flame/direction", flameDirection);
  json.set("sensors/flame/status", "ok");

  // Actuators
  json.set("actuators/servo/axis_x", currentPan);
  json.set("actuators/servo/axis_y", currentTilt);
  json.set("actuators/pump", pumpActive);
  json.set("actuators/buzzer", buzzerActive);
  json.set("actuators/auto_pump_active", pumpActive && fireDetected);

  // System
  json.set("system/fire_detected", fireDetected);
  json.set("system/mode", systemMode);
  json.set("system/wifi_connected", wifiConnected);
  json.set("system/firmware_version", FIRMWARE_VERSION);

  // Ghi batch — một request duy nhất
  if (!Firebase.RTDB.updateNode(&fbData, FB_ROOT, &json))
  {
    Serial.printf("[Firebase] Lỗi ghi batch: %s\n",
                  fbData.errorReason().c_str());
  }
}

/*
 * Ghi heartbeat last_seen bằng Unix timestamp UTC từ NTP.
 * App Android: now - last_seen > 15 giây → hiển thị "Offline".
 *
 * Không dùng millis() vì millis() chỉ đếm từ lúc khởi động,
 * không thể so sánh với đồng hồ thực của điện thoại.
 */
void sendHeartbeat()
{
  if (millis() - lastHeartbeat < 5000)
    return;
  lastHeartbeat = millis();
  if (!isFirebaseReady())
    return;
  if (!checkNTPSynced())
    return;

  time_t now = getTimestamp();
  Firebase.RTDB.setInt(&fbData, FB_ROOT "/system/last_seen", (int)now);
}

/*
 * Ghi log sự kiện cháy vào logs/{push_key}/ bằng pushJSON.
 * Firebase tự tạo push key duy nhất (timestamp-based, không trùng).
 * Lưu key để ghi resolved_at khi lửa tắt.
 */
void logFireEvent(const String &action)
{
  if (!isFirebaseReady())
    return;

  FirebaseJson log;
  log.set("timestamp", (int)getTimestamp());
  log.set("time_readable", getTimeReadable());
  log.set("temperature", temperature);
  log.set("humidity", humidity);
  log.set("mq2_value", mq2Value);
  log.set("mq2_level", mq2Level);
  log.set("flame_direction", flameDirection);

  // Chuỗi pattern 5 ký tự, ví dụ "00100"
  String pattern = "";
  for (int i = 0; i < 5; i++)
    pattern += flameDetected[i] ? "1" : "0";

  log.set("flame_pattern", pattern);
  log.set("servo_x_at_event", currentPan);
  log.set("servo_y_at_event", currentTilt);
  log.set("action_taken", action);
  log.set("pump_activated", true);
  log.set("buzzer_activated", true);
  log.set("alert_was_snoozed", false);
  log.set("resolved_at", 0); // 0 = sự kiện chưa kết thúc

  if (Firebase.RTDB.pushJSON(&fbData, FB_ROOT "/logs", &log))
  {
    currentLogKey = fbData.pushName();
    Serial.printf("[Firebase] Log OK. Key: %s\n", currentLogKey.c_str());
  }
  else
  {
    Serial.printf("[Firebase] Lỗi ghi log: %s\n",
                  fbData.errorReason().c_str());
  }
}

/* Cập nhật resolved_at khi sự kiện cháy kết thúc. */
void resolveLogEvent()
{
  if (!isFirebaseReady())
    return;
  if (currentLogKey.isEmpty())
    return;

  String path = String(FB_ROOT) + "/logs/" + currentLogKey + "/resolved_at";
  Firebase.RTDB.setInt(&fbData, path.c_str(), (int)getTimestamp());
  currentLogKey = "";
}

/* Kích hoạt FCM: set notification_sent = true để App gửi push. */
void triggerNotification()
{
  if (!isFirebaseReady())
    return;

  FirebaseJson alertJson;
  alertJson.set("alert/notification_sent", true);
  alertJson.set("alert/last_triggered", (int)getTimestamp());
  Firebase.RTDB.updateNode(&fbData, FB_ROOT, &alertJson);
}

// ════════════════════════════════════════════════════════════
//  PHẦN 6 — FIREBASE: ĐỌC LỆNH ĐIỀU KHIỂN
// ════════════════════════════════════════════════════════════

/* Đồng bộ ngưỡng khi App set thresholds/updated = true. */
void syncThresholds()
{
  if (!isFirebaseReady())
    return;

  if (!Firebase.RTDB.getBool(&fbData, FB_ROOT "/thresholds/updated"))
    return;
  if (!fbData.boolData())
    return;

  Serial.println("[Threshold] Cập nhật ngưỡng từ App.");

  if (Firebase.RTDB.getInt(&fbData, FB_ROOT "/thresholds/mq2_safe"))
    mq2Safe = fbData.intData();

  if (Firebase.RTDB.getInt(&fbData, FB_ROOT "/thresholds/mq2_warning"))
    mq2Warning = fbData.intData();

  if (Firebase.RTDB.getFloat(&fbData, FB_ROOT "/thresholds/temp_safe"))
    tempSafe = fbData.floatData();

  if (Firebase.RTDB.getFloat(&fbData, FB_ROOT "/thresholds/temp_warning"))
    tempWarning = fbData.floatData();

  Firebase.RTDB.setBool(&fbData, FB_ROOT "/thresholds/updated", false);

  Serial.printf("[Threshold] Mới — MQ2: %d/%d | Temp: %.1f/%.1f\n",
                mq2Safe, mq2Warning, tempSafe, tempWarning);
}

/* Kiểm tra và reset snooze cảnh báo khi hết thời hạn. */
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
 * Đọc và thực thi lệnh Firebase mỗi 500ms.
 *
 * Ưu tiên an toàn:
 *   Khi fireDetected = true, mọi lệnh manual đều bị bỏ qua.
 *   Hệ thống ép về "auto" và ghi lại Firebase để App đồng bộ.
 */
void handleFirebaseCommands()
{
  if (millis() - lastFBCmdRead < 500)
    return;
  lastFBCmdRead = millis();
  if (!isFirebaseReady())
    return;

  // 1. Đồng bộ ngưỡng và snooze
  syncThresholds();
  checkSnooze();

  // 2. Đọc chế độ hoạt động
  if (Firebase.RTDB.getString(&fbData, FB_ROOT "/system/mode"))
  {
    systemMode = fbData.stringData();
  }

  // 3. Ưu tiên an toàn: khi cháy → luôn auto
  if (fireDetected && systemMode != "auto")
  {
    systemMode = "auto";
    Firebase.RTDB.setString(&fbData, FB_ROOT "/system/mode", "auto");
    Serial.println("[Safety] Đang cháy — ép AUTO, bỏ qua lệnh manual.");
    return;
  }

  // 4. Xử lý lệnh thủ công (chỉ khi mode = "manual")
  if (systemMode != "manual")
    return;

  int panCmd = currentPan;
  int tiltCmd = currentTilt;
  bool pumpCmd = false;
  bool buzzerCmd = false;

  if (Firebase.RTDB.getInt(&fbData, FB_ROOT "/control/servo/axis_x"))
    panCmd = fbData.intData();

  if (Firebase.RTDB.getInt(&fbData, FB_ROOT "/control/servo/axis_y"))
    tiltCmd = fbData.intData();

  if (Firebase.RTDB.getBool(&fbData, FB_ROOT "/control/pump_on"))
    pumpCmd = fbData.boolData();

  if (Firebase.RTDB.getBool(&fbData, FB_ROOT "/control/buzzer_on"))
    buzzerCmd = fbData.boolData();

  updateServosManual(panCmd, tiltCmd);
  setPump(pumpCmd);
  setBuzzer(buzzerCmd);
}

// ════════════════════════════════════════════════════════════
//  PHẦN 7 — LOGIC AUTO MODE (STATE MACHINE)
// ════════════════════════════════════════════════════════════

/*
 * State machine auto mode:
 *
 *  [BÌNH THƯỜNG] ──anyFlameDetected=true──► [PHÁT HIỆN CHÁY]
 *                                              │ Bật còi, điều servo
 *                                              │ Chờ 500ms
 *                                              ↓
 *                                          [BƠM ĐANG CHẠY]
 *                                              │ Cập nhật hướng liên tục
 *                                              │ anyFlameDetected=false
 *                                              ↓
 *                                            [RESET]
 *                                              │ Tắt bơm, còi
 *                                              │ Servo về trung tâm
 *                                              └──────────────────► [BÌNH THƯỜNG]
 */
void handleAutoMode()
{

  // ── CÓ LỬA ────────────────────────────────────────────────
  if (anyFlameDetected)
  {

    if (!fireDetected)
    {
      // === Kích hoạt lần đầu ===
      fireDetected = true;
      fireTriggerTime = millis();
      waitingForServo = true;

      Serial.println("\n[AUTO] PHÁT HIỆN CHÁY! Đang kích hoạt...");

      setBuzzer(true);
      updateServosAuto(flamePriorityIdx);

      if (isFirebaseReady())
      {
        FirebaseJson fireJson;
        fireJson.set("system/fire_detected", true);
        fireJson.set("actuators/buzzer", true);
        fireJson.set("system/mode", "auto");
        Firebase.RTDB.updateNode(&fbData, FB_ROOT, &fireJson);

        triggerNotification();
        logFireEvent("auto");
      }
    }

    // === Bật bơm sau 500ms — chờ servo ổn định ===
    if (waitingForServo && millis() - fireTriggerTime >= 500)
    {
      waitingForServo = false;
      setPump(true);
      Serial.println("[AUTO] Servo ổn định → Bơm BẬT.");

      if (isFirebaseReady())
      {
        Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/pump", true);
        Firebase.RTDB.setBool(&fbData, FB_ROOT "/actuators/auto_pump_active", true);
      }
    }

    // === Cập nhật hướng servo liên tục nếu lửa di chuyển ===
    if (!waitingForServo && flamePriorityIdx >= 0)
    {
      updateServosAuto(flamePriorityIdx);
    }
  }

  // ── LỬA ĐÃ TẮT ────────────────────────────────────────────
  else
  {
    if (fireDetected)
    {
      fireDetected = false;
      waitingForServo = false;

      Serial.println("[AUTO] Lửa đã tắt — Reset hệ thống.\n");

      setPump(false);
      setBuzzer(false);
      updateServosManual(90, 60);
      resolveLogEvent();

      if (isFirebaseReady())
      {
        FirebaseJson resetJson;
        resetJson.set("system/fire_detected", false);
        resetJson.set("actuators/pump", false);
        resetJson.set("actuators/buzzer", false);
        resetJson.set("actuators/auto_pump_active", false);
        resetJson.set("actuators/servo/axis_x", 90);
        resetJson.set("actuators/servo/axis_y", 60);
        Firebase.RTDB.updateNode(&fbData, FB_ROOT, &resetJson);
      }
    }
  }
}

// ════════════════════════════════════════════════════════════
//  SETUP & LOOP
// ════════════════════════════════════════════════════════════

void setup()
{
  Serial.begin(9600);
  delay(500);
  Serial.println();
  Serial.println("══════════════════════════════════════════════════");
  Serial.printf("  Hệ thống Chữa Cháy Thông Minh — ESP32 v%s\n",
                FIRMWARE_VERSION);
  Serial.println("══════════════════════════════════════════════════");

  // Khởi động phần cứng
  dht.begin();
  setupFlamePins();
  setupRelay();
  setupBuzzer();
  setupServos();

  // Kết nối Wi-Fi (non-blocking)
  startWiFi();
  lastWiFiRetry = millis();

  Serial.println("[Setup] Hoàn tất. Bắt đầu vòng lặp chính.\n");
}

void loop()
{
  // ── 1. Quản lý kết nối mạng ──────────────────────────────
  manageWiFi();

  if (wifiConnected)
  {
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

    if (!ntpSynced)
    {
      if (!ntpInitStarted)
      {
        initNTP();
        ntpInitStarted = true;
      }
      checkNTPSynced();
    }
  }

  // ── 2. Đọc cảm biến (non-blocking) ───────────────────────
  readFlameSensors(); // 100  ms — ưu tiên cao nhất
  readMQ2();          // 500  ms
  readDHT11();        // 2000 ms

  // ── 3. Logic điều khiển ──────────────────────────────────
  if (systemMode == "auto")
  {
    handleAutoMode();
  }
  // Chế độ manual xử lý trong handleFirebaseCommands()

  // ── 4. Giao tiếp Firebase ────────────────────────────────
  writeFirebaseData();      // Ghi batch sensor data
  handleFirebaseCommands(); // Đọc lệnh từ App Android
  sendHeartbeat();          // Cập nhật last_seen (timestamp NTP)
}
