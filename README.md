# Smart Fire Monitoring Android

Hệ thống chữa cháy tự động kết hợp giám sát thông minh bằng ứng dụng Android.

## Tổng quan

Dự án gồm 2 phần:
- **ESP32 Firmware** (`smart-fire-monitoring-android/`): Đọc cảm biến, điều khiển chữa cháy tự động, ghi dữ liệu lên Firebase
- **Android App**: Giám sát realtime, điều khiển từ xa, lịch sử sự kiện

## Kiến trúc

```
[Cảm biến] → [ESP32] → [Firebase RTDB] → [Android App]
                ↓                              ↓
         [Servo/Bơm/Còi]              [Điều khiển manual]
```

## Phần cứng: ESP32

| Thành phần | Trạng thái |
|-----------|-----------|
| DHT11 (nhiệt độ/độ ẩm) | Hoàn thành |
| MQ-2 (khí gas/khói) | Hoàn thành |
| Cảm biến lửa 5 mắt | Hoàn thành |
| Servo X (Pan) — bám hướng lửa | Hoàn thành |
| Servo Y (Tilt) — quét nâng hạ 100°↔150° | Hoàn thành |
| Relay bơm | Hoàn thành |
| Relay còi | Hoàn thành |
| Firebase ghi/đọc | Hoàn thành |
| Firebase Stream (nhận lệnh realtime) | Hoàn thành |
| Sensor Fusion (nhiệt + khí gas) | Hoàn thành |
| Watchdog Timer | Hoàn thành |
| WiFi auto-reconnect (non-blocking) | Hoàn thành |

## Ứng dụng Android (Chưa triển khai)

| Màn hình | Chức năng |
|----------|-----------|
| Dashboard | Hiển thị realtime: nhiệt độ, độ ẩm, MQ-2, trạng thái 5 mắt lửa, bơm, còi, servo |
| Lịch sử | Danh sách + biểu đồ sự kiện cháy từ logs/ |
| Cài đặt ngưỡng | Chỉnh mq2_safe, mq2_warning, temp_safe, temp_warning |
| Cảnh báo | Bật/tắt cảnh báo, snooze |
| Điều khiển thủ công | SeekBar servo X/Y, nút bơm/còi (khóa khi cháy) |

## Công nghệ

- **ESP32**: Arduino IDE, Firebase ESP Client (Mobizt), ESP32Servo, DHT library
- **Android**: Java, Android Studio, Firebase Realtime Database SDK, Firebase Auth, MPAndroidChart
- **Cloud**: Firebase Realtime Database, Firebase Anonymous Auth, FCM

## Cấu trúc Firebase

```
fire-alarm-system/
├── sensors/        (ESP32 ghi → App đọc)
├── actuators/      (ESP32 ghi → App đọc)
├── system/         (ESP32 ghi → App đọc)
├── alert/          (App ghi ↔ ESP32 đọc)
├── thresholds/     (App ghi → ESP32 đọc)
├── control/        (App ghi → ESP32 đọc qua Stream)
└── logs/           (ESP32 ghi → App đọc)
```

