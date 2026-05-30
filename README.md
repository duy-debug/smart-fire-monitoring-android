# Smart Fire Monitoring

Hệ thống giám sát và cảnh báo cháy theo thời gian thực, kết hợp ESP32, Firebase Realtime Database và ứng dụng Android Java.

## Tổng quan

- **ESP32 Firmware**: đọc cảm biến, phát hiện cháy, điều khiển servo, bơm, còi và đồng bộ dữ liệu lên Firebase.
- **Android App**: hiển thị realtime, điều khiển từ xa, theo dõi lịch sử và cảnh báo cháy.

## Ảnh giao diện

### Màn hình chính

<table>
  <tr>
    <td align="center"><img src="image/home.png" alt="Home" width="190"></td>
    <td align="center"><img src="image/canhbao.png" alt="Cảnh báo" width="190"></td>
    <td align="center"><img src="image/dieukhien.png" alt="Điều khiển" width="190"></td>
  </tr>
</table>

### Lịch sử và ngưỡng

<table>
  <tr>
    <td align="center"><img src="image/history-list.png" alt="History List" width="190"></td>
    <td align="center"><img src="image/history-chart.png" alt="History Chart" width="190"></td>
    <td align="center"><img src="image/nguong.png" alt="Ngưỡng" width="190"></td>
  </tr>
</table>

### Cảnh báo và mô hình lắp ráp

<table>
  <tr>
    <td align="center"><img src="image/warning.png" alt="Warning" width="190"></td>
    <td align="center"><img src="image/notify.png" alt="Notification" width="190"></td>
  </tr>
</table>

<table>
  <tr>
    <td align="center"><img src="image/demo1.png" alt="Demo 1" width="260"></td>
    <td align="center"><img src="image/demo2.png" alt="Demo 2" width="260"></td>
  </tr>
  <tr>
    <td align="center"><img src="image/demo3.png" alt="Demo 3" width="260"></td>
    <td align="center"><img src="image/demo4.png" alt="Demo 4" width="260"></td>
  </tr>
</table>

## Tính năng chính

### Android App

- Dashboard realtime: nhiệt độ, độ ẩm, MQ-2, cảm biến lửa, còi, bơm, servo
- Notification và dialog cảnh báo cháy toàn màn hình
- Điều khiển thủ công servo X/Y, bơm, còi
- Thiết lập ngưỡng và quản lý cảnh báo/snooze
- Lịch sử sự kiện cháy dạng danh sách và biểu đồ

### ESP32 Firmware

- Đọc DHT11, MQ-2 và 5 cảm biến lửa
- Điều khiển servo pan/tilt, bơm và còi
- Ghi dữ liệu lên Firebase Realtime Database
- Nhận lệnh realtime từ Firebase
- Tự chuyển sang chế độ an toàn khi có cháy

## Firebase structure

```text
fire-alarm-system/
├── sensors/
├── actuators/
├── system/
├── alert/
├── thresholds/
├── control/
└── logs/
```

## Tải APK

<div align="center">

### [TẢI PHIÊN BẢN MỚI NHẤT (APK)](https://github.com/duy-debug/smart-fire-monitoring-android/releases/latest)

<a href="https://github.com/duy-debug/smart-fire-monitoring-android/releases/latest">
  <img src="https://img.shields.io/badge/Download-APK-brightgreen?style=for-the-badge&logo=android&logoColor=white" alt="Download APK">
</a>

</div>

### Cách cài đặt:

1. Truy cập trang Release bằng nút **DOWNLOAD APK** ở trên.
2. Trong mục **Assets**, chọn file `smart-fire-monitoring-android.apk`.
3. Tải file APK về điện thoại Android.
4. Mở file APK và chọn **Cài đặt**.
5. Nếu điện thoại yêu cầu, hãy bật **Cho phép cài đặt ứng dụng không rõ nguồn gốc**.

> Lưu ý: Ứng dụng được phát hành qua GitHub Releases. Android có thể hiển thị cảnh báo vì đây là APK cài ngoài Google Play.

### Cách build APK để phát hành

1. Mở project bằng Android Studio.
2. Chọn `Build` -> `Generate Signed App Bundle or APK...`.
3. Chọn `APK`.
4. Tạo hoặc chọn `keystore` để ký ứng dụng.
5. Chọn biến thể `release` và bấm `Finish`.
6. Sau khi build xong, file APK sẽ nằm trong:
   - `SmartFireMonitoring/app/build/outputs/apk/release/`

### Ghi chú khi chia sẻ cho người dùng

- File APK release được workflow GitHub Actions tự tạo và đính kèm vào `Releases`.
- Khi tạo tag version như `v1.0.0`, workflow sẽ build và upload file `smart-fire-monitoring-android.apk`.
- Nếu chỉ dùng để test nhanh nội bộ, bản `debug` vẫn có thể build từ Android Studio.

## Chạy project

### Android App

1. Mở project bằng Android Studio.
2. Kiểm tra file `google-services.json` đã nằm trong `app/`.
3. Sync Gradle.
4. Build và chạy lên thiết bị Android hoặc emulator.

### ESP32

1. Mở firmware trong Arduino IDE.
2. Cập nhật WiFi, Firebase URL và Firebase Auth.
3. Upload code lên board ESP32.

## Công nghệ sử dụng

- **ESP32**: Arduino IDE, Firebase ESP Client, ESP32Servo, DHT library
- **Android**: Java, Android Studio, Firebase Realtime Database, Firebase Auth, MPAndroidChart
- **Cloud**: Firebase Realtime Database, Firebase Anonymous Auth

## Trạng thái project

- ESP32 Firmware: hoàn thành
- Android App: đang hoàn thiện và tối ưu UI/UX

