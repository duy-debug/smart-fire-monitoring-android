/*
 * credentials-example.h — File mẫu thông tin kết nối
 *
 * HƯỚNG DẪN SỬ DỤNG:
 *   1. Copy file này thành "credentials.h" (cùng thư mục)
 *   2. Điền thông tin WiFi và Firebase của bạn
 *   3. KHÔNG commit file credentials.h lên GitHub!
 *
 * HƯỚNG DẪN LẤY THÔNG TIN:
 *
 * [1] FIREBASE_API_KEY (Web API Key):
 *     Firebase Console → Project Settings (bánh răng)
 *     → tab General → Web API key
 *
 * [2] FIREBASE_DATABASE_URL:
 *     Firebase Console → Realtime Database → tab Data
 *     → URL dạng: https://your-project-rtdb.firebaseio.com
 *     (GIỮ https://, KHÔNG có "/" cuối)
 *
 * [3] Bật Anonymous Authentication:
 *     Firebase Console → Authentication → Sign-in method
 *     → Anonymous → Enable → Save
 *
 * [4] Security Rules (Realtime Database → Rules):
 *     {
 *       "rules": {
 *         ".read":  "auth != null",
 *         ".write": "auth != null"
 *       }
 *     }
 */

#ifndef CREDENTIALS_H
#define CREDENTIALS_H

// ═══════════════════════════════════════════════════
//  WIFI — Chỉ hỗ trợ 2.4 GHz (ESP32 không hỗ trợ 5 GHz)
// ═══════════════════════════════════════════════════

#define WIFI_SSID "TEN_WIFI_CUA_BAN"
#define WIFI_PASSWORD "MAT_KHAU_WIFI"

// ═══════════════════════════════════════════════════
//  FIREBASE
// ═══════════════════════════════════════════════════

// Web API Key — Firebase Console → Project Settings → General
#define FIREBASE_API_KEY "AIzaSyXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX"

// Realtime Database URL — Firebase Console → Realtime Database → Data
#define FIREBASE_DATABASE_URL "https://your-project-id-default-rtdb.firebaseio.com"

#endif // CREDENTIALS_H
