package thick2.nhom1.smartfiremonitoring;

import android.graphics.Color;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class ControlFragment extends Fragment {
    private static final int SERVO_Y_MIN = 30;
    private static final int SERVO_Y_MAX = 90;
    private static final int SERVO_Y_RANGE = SERVO_Y_MAX - SERVO_Y_MIN;
    private static final int SERVO_Y_AUTO_DEFAULT = 45;

    /**
     * Fragment Điều khiển:
     * - Chuyển AUTO/MANUAL
     * - Gửi lệnh servo/bơm/còi lên Firebase
     * - Khóa toàn bộ điều khiển khi hệ thống phát hiện cháy
     */
    private DatabaseReference controlRef;
    private DatabaseReference systemRef;
    private DatabaseReference actuatorsRef;
    private ValueEventListener controlPumpListener;
    private ValueEventListener controlBuzzerListener;
    private ValueEventListener modeListener;
    private ValueEventListener fireListener;

    private ValueEventListener actuatorsPumpListener;
    private ValueEventListener actuatorsBuzzerListener;
    private ValueEventListener actuatorsServoXListener;
    private ValueEventListener actuatorsServoYListener;
    private ValueEventListener actuatorsAutoPumpListener;

    private SwitchMaterial switchMode;
    private TextView tvServoXValue;
    private TextView tvServoYValue;
    private SeekBar seekBarServoX;
    private SeekBar seekBarServoY;
    private MaterialButton btnPump;
    private MaterialButton btnBuzzer;
    private View fireLockBanner;
    private TextView tvAutoLock;

    private boolean isUpdatingUi = false;
    private boolean pumpOn = false;
    private boolean buzzerOn = false;
    private boolean fireDetected = false;
    // Trạng thái phần cứng thực tế (từ node actuators/) nếu có
    private boolean pumpActualAvailable = false;
    private boolean pumpActual = false;
    private boolean buzzerActualAvailable = false;
    private boolean buzzerActual = false;
    private String pendingMode = null;
    private long pendingModeAt = 0L;
    private static final long MODE_CONFIRM_WINDOW_MS = 1500L;

    public ControlFragment() {
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate layout điều khiển và chuẩn bị các node Firebase cần dùng
        View view = inflater.inflate(R.layout.fragment_control, container, false);

        // Dùng cùng đường dẫn gốc với firmware ESP32
        // ESP32 định nghĩa FB_ROOT là "/fire-alarm-system"
        DatabaseReference rootRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system");
        controlRef = rootRef.child("control");
        systemRef = rootRef.child("system");
        actuatorsRef = rootRef.child("actuators");

        bindViews(view);
        setDefaultUiState();

        setupModeSwitch();
        setupSeekBars();
        setupActionButtons();
        listenControlState();
        listenActuatorsState();
        listenFireDetected();
        loadInitialModeState();
        return view;
    }

    private void bindViews(View view) {
        // Ánh xạ các control từ XML sang Java để xử lý sự kiện
        switchMode = view.findViewById(R.id.switchMode);
        tvServoXValue = view.findViewById(R.id.tvServoXValue);
        tvServoYValue = view.findViewById(R.id.tvServoYValue);
        seekBarServoX = view.findViewById(R.id.seekBarServoX);
        seekBarServoY = view.findViewById(R.id.seekBarServoY);
        btnPump = view.findViewById(R.id.btnPump);
        btnBuzzer = view.findViewById(R.id.btnBuzzer);
        fireLockBanner = view.findViewById(R.id.fireLockBanner);
        tvAutoLock = view.findViewById(R.id.tvAutoLock);
    }

    private void setDefaultUiState() {
        // Khởi tạo UI mặc định, không ghi đè mode lên Firebase
        isUpdatingUi = true;
        switchMode.setChecked(false);
        isUpdatingUi = false;

        seekBarServoX.setProgress(90);
        seekBarServoY.setMax(SERVO_Y_RANGE);
        seekBarServoY.setProgress(servoYToProgress(SERVO_Y_AUTO_DEFAULT));
        tvServoXValue.setText("Servo X (Pan): 90°");
        tvServoYValue.setText("Servo Y (Tilt): 45°");
    }

    private void loadInitialModeState() {
        systemRef.child("mode").get().addOnSuccessListener(snapshot -> {
            if (fireDetected) {
                return;
            }

            String mode = snapshot.getValue(String.class);
            boolean manualMode = "manual".equalsIgnoreCase(mode);
            isUpdatingUi = true;
            switchMode.setChecked(manualMode);
            isUpdatingUi = false;
            syncUiFromCurrentMode(manualMode);
        }).addOnFailureListener(e -> {
            if (!fireDetected) {
                syncUiFromCurrentMode(false);
            }
        });
    }

    private void setupModeSwitch() {
        // Khi người dùng chuyển chế độ, ghi ngay mode mới lên Firebase
        switchMode.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isUpdatingUi || fireDetected) {
                return;
            }

            pendingMode = isChecked ? "manual" : "auto";
            pendingModeAt = System.currentTimeMillis();
            writeMode(isChecked ? "manual" : "auto");
            syncUiFromCurrentMode(isChecked);
        });
    }

    private void setupSeekBars() {
        // Hai thanh kéo dùng để điều khiển góc servo trục X/Y trong chế độ manual
        seekBarServoX.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvServoXValue.setText("Servo X (Pan): " + progress + "°");

                if (!fromUser || isUpdatingUi || fireDetected || !switchMode.isChecked()) {
                    return;
                }

                controlRef.child("servo").child("axis_x").setValue(progress)
                        .addOnSuccessListener(unused -> Toast.makeText(getContext(),
                                "Servo X = " + progress + "°",
                                Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e -> Toast.makeText(getContext(),
                                "Không ghi được Servo X: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        seekBarServoY.setMax(SERVO_Y_RANGE);
        seekBarServoY.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int angle = progressToServoY(progress);
                tvServoYValue.setText("Servo Y (Tilt): " + angle + "°");

                if (!fromUser || isUpdatingUi || fireDetected || !switchMode.isChecked()) {
                    return;
                }

                controlRef.child("servo").child("axis_y").setValue(angle)
                        .addOnSuccessListener(unused -> Toast.makeText(getContext(),
                                "Servo Y = " + angle + "°",
                                Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e -> Toast.makeText(getContext(),
                                "Không ghi được Servo Y: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void setupActionButtons() {
        // Hai nút này điều khiển bơm và còi, chỉ hoạt động khi đang ở manual
        btnPump.setOnClickListener(v -> {
            if (fireDetected || !switchMode.isChecked()) {
                return;
            }

            pumpOn = !pumpOn;
            controlRef.child("pump_on").setValue(pumpOn)
                    .addOnSuccessListener(unused -> updatePumpButton())
                    .addOnFailureListener(e -> Toast.makeText(getContext(),
                            "Không ghi được Bơm: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
            updatePumpButton();
        });

        btnBuzzer.setOnClickListener(v -> {
            if (fireDetected || !switchMode.isChecked()) {
                return;
            }

            buzzerOn = !buzzerOn;
            controlRef.child("buzzer_on").setValue(buzzerOn)
                    .addOnSuccessListener(unused -> updateBuzzerButton())
                    .addOnFailureListener(e -> Toast.makeText(getContext(),
                            "Không ghi được Còi: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
            updateBuzzerButton();
        });
    }

    /**
     * Lắng nghe trạng thái các lệnh điều khiển thủ công từ node control/
     * Bao gồm: trạng thái bơm, còi, và chế độ hoạt động (auto/manual)
     * Cập nhật UI khi có thay đổi từ Firebase hoặc từ các fragment khác.
     */
    private void listenControlState() {
        // Đồng bộ trạng thái lệnh điều khiển từ Firebase về UI
        controlPumpListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean value = snapshot.getValue(Boolean.class);
                pumpOn = Boolean.TRUE.equals(value);
                updatePumpButton();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        controlRef.child("pump_on").addValueEventListener(controlPumpListener);

        controlBuzzerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean value = snapshot.getValue(Boolean.class);
                buzzerOn = Boolean.TRUE.equals(value);
                updateBuzzerButton();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        controlRef.child("buzzer_on").addValueEventListener(controlBuzzerListener);

        modeListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                String mode = snapshot.getValue(String.class);
                if (fireDetected) {
                    return;
                }

                if (shouldIgnoreRemoteMode(mode)) {
                    return;
                }

                boolean manualMode = "manual".equalsIgnoreCase(mode);
                isUpdatingUi = true;
                switchMode.setChecked(manualMode);
                isUpdatingUi = false;
                pendingMode = null;
                syncUiFromCurrentMode(manualMode);
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        systemRef.child("mode").addValueEventListener(modeListener);
    }

    /**
     * Lắng nghe trạng thái thực tế của các chấp hành từ node actuators/
     * Dùng để hiển thị trạng thái phần cứng thực tế thay vì chỉ lệnh đã gửi.
     * Bao gồm: bơm, còi, góc servo trục X/Y, và chỉ báo tự động kích hoạt bơm.
     */
    private void listenActuatorsState() {
        // Lắng nghe trạng thái thực tế của phần cứng để UI phản ánh đúng trạng thái ESP32
        // Trạng thái thực tế của bơm
        actuatorsPumpListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean value = snapshot.getValue(Boolean.class);
                pumpActualAvailable = (value != null);
                pumpActual = Boolean.TRUE.equals(value);
                updatePumpButton();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        actuatorsRef.child("pump").addValueEventListener(actuatorsPumpListener);

        // Trạng thái thực tế của còi
        actuatorsBuzzerListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean value = snapshot.getValue(Boolean.class);
                buzzerActualAvailable = (value != null);
                buzzerActual = Boolean.TRUE.equals(value);
                updateBuzzerButton();
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        actuatorsRef.child("buzzer").addValueEventListener(actuatorsBuzzerListener);

        // Vị trí thực tế của servo
        actuatorsServoXListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer v = snapshot.getValue(Integer.class);
                if (v != null) {
                    if (!isUpdatingUi) {
                        seekBarServoX.setProgress(v);
                        tvServoXValue.setText("Servo X (Pan): " + v + "°");
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        actuatorsRef.child("servo").child("axis_x").addValueEventListener(actuatorsServoXListener);

        actuatorsServoYListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Integer v = snapshot.getValue(Integer.class);
                if (v != null) {
                    if (!isUpdatingUi) {
                        if (switchMode.isChecked()) {
                            seekBarServoY.setProgress(servoYToProgress(v));
                            tvServoYValue.setText("Servo Y (Tilt): " + clampServoY(v) + "°");
                        } else {
                            seekBarServoY.setProgress(servoYToProgress(SERVO_Y_AUTO_DEFAULT));
                            tvServoYValue.setText("Servo Y (Tilt): " + SERVO_Y_AUTO_DEFAULT + "°");
                        }
                    }
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        actuatorsRef.child("servo").child("axis_y").addValueEventListener(actuatorsServoYListener);

        // Chỉ báo bơm tự động đang hoạt động (tùy chọn hiển thị trên App)
        actuatorsAutoPumpListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // App có thể dùng trường này để hiển thị nguồn kích hoạt bơm (tự động hay thủ công)
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
            }
        };
        actuatorsRef.child("auto_pump_active").addValueEventListener(actuatorsAutoPumpListener);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Gỡ listener khi rời fragment để tránh rò rỉ bộ nhớ
        try {
            if (controlRef != null) {
                if (controlPumpListener != null) controlRef.child("pump_on").removeEventListener(controlPumpListener);
                if (controlBuzzerListener != null) controlRef.child("buzzer_on").removeEventListener(controlBuzzerListener);
            }
            if (systemRef != null) {
                if (modeListener != null) systemRef.child("mode").removeEventListener(modeListener);
                if (fireListener != null) systemRef.child("fire_detected").removeEventListener(fireListener);
            }
            if (actuatorsRef != null) {
                if (actuatorsPumpListener != null) actuatorsRef.child("pump").removeEventListener(actuatorsPumpListener);
                if (actuatorsBuzzerListener != null) actuatorsRef.child("buzzer").removeEventListener(actuatorsBuzzerListener);
                if (actuatorsServoXListener != null) actuatorsRef.child("servo").child("axis_x").removeEventListener(actuatorsServoXListener);
                if (actuatorsServoYListener != null) actuatorsRef.child("servo").child("axis_y").removeEventListener(actuatorsServoYListener);
                if (actuatorsAutoPumpListener != null) actuatorsRef.child("auto_pump_active").removeEventListener(actuatorsAutoPumpListener);
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Lắng nghe cảnh báo phát hiện cháy từ node system/fire_detected
     * Khi phát hiện cháy:
     *   - Khóa tất cả điều khiển thủ công
     *   - Hiển thị banner cảnh báo
     *   - Buộc chế độ AUTO
     * Khi nguy cơ kết thúc, mở khóa điều khiển lại.
     */
    private void listenFireDetected() {
        // Nếu hệ thống báo cháy thì khóa toàn bộ manual control và buộc về AUTO
        fireListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                Boolean fire = snapshot.getValue(Boolean.class);
                fireDetected = Boolean.TRUE.equals(fire);

                if (fireDetected) {
                    setModeToAutoBySystem();
                    setControlsEnabled(false);
                    fireLockBanner.setVisibility(View.VISIBLE);
                    tvAutoLock.setText("Hệ thống đang xử lý cháy");
                    tvAutoLock.setTextColor(Color.parseColor("#9A3412"));
                } else {
                    fireLockBanner.setVisibility(View.GONE);
                    syncUiFromCurrentMode(switchMode.isChecked());
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Toast.makeText(getContext(), "Không thể đọc fire_detected", Toast.LENGTH_SHORT).show();
            }
        };
        systemRef.child("fire_detected").addValueEventListener(fireListener);
    }

    /**
     * Ghi chế độ hoạt động vào Firebase (auto/manual)
     * Khi chuyển sang AUTO và không đang cháy, đặt lại tất cả lệnh điều khiển về mặc định an toàn.
     * @param mode "auto" hoặc "manual"
     */
    private void writeMode(String mode) {
        // Ghi mode hiện tại lên Firebase, đồng thời reset lệnh manual khi quay về AUTO
        systemRef.child("mode").setValue(mode)
            .addOnSuccessListener(unused -> {
                Toast.makeText(getContext(),
                    "Đã chuyển sang " + ("auto".equalsIgnoreCase(mode) ? "AUTO" : "MANUAL"),
                    Toast.LENGTH_SHORT).show();

                // Nếu chuyển sang AUTO và hệ thống không đang cháy,
                // đặt lại lệnh `control` về mặc định an toàn để ESP32 đọc được.
        if ("auto".equalsIgnoreCase(mode) && !fireDetected) {
                controlRef.child("buzzer_on").setValue(false);
                controlRef.child("pump_on").setValue(false);
                controlRef.child("servo").child("axis_x").setValue(90);
                controlRef.child("servo").child("axis_y").setValue(SERVO_Y_AUTO_DEFAULT);
                }
            })
                .addOnFailureListener(e -> Toast.makeText(getContext(),
                        "Không ghi được chế độ: " + e.getMessage(),
                        Toast.LENGTH_SHORT).show());
    }

    private void setModeToAutoBySystem() {
        isUpdatingUi = true;
        switchMode.setChecked(false);
        isUpdatingUi = false;
        pendingMode = null;
        writeMode("auto");
    }

    /**
     * Đồng bộ giao diện theo chế độ hiện tại (auto/manual)
     * Cập nhật trạng thái khóa/mở của các điều khiển, hiển thị trạng thái chế độ.
     * @param manualMode true = chế độ MANUAL; false = chế độ AUTO
     */
    private void syncUiFromCurrentMode(boolean manualMode) {
        // Đồng bộ trạng thái enable/disable của UI theo chế độ hiện tại
        if (fireDetected) {
            setControlsEnabled(false);
            fireLockBanner.setVisibility(View.VISIBLE);
            tvAutoLock.setText("Hệ thống đang xử lý cháy");
            tvAutoLock.setTextColor(Color.parseColor("#9A3412"));
            return;
        }

        setControlsEnabled(manualMode);
        tvServoXValue.setText("Servo X (Pan): " + seekBarServoX.getProgress() + "°");
        if (manualMode) {
            tvServoYValue.setText("Servo Y (Tilt): " + progressToServoY(seekBarServoY.getProgress()) + "°");
        } else {
            seekBarServoY.setProgress(servoYToProgress(SERVO_Y_AUTO_DEFAULT));
            tvServoYValue.setText("Servo Y (Tilt): " + SERVO_Y_AUTO_DEFAULT + "°");
        }

        fireLockBanner.setVisibility(View.GONE);
        if (manualMode) {
            tvAutoLock.setText("Đang ở chế độ MANUAL");
        } else {
            tvAutoLock.setText("Đang ở chế độ AUTO");
        }
        tvAutoLock.setTextColor(Color.parseColor("#1D4ED8"));

        updatePumpButton();
        updateBuzzerButton();
    }

    private boolean shouldIgnoreRemoteMode(String mode) {
        if (pendingMode == null || mode == null) {
            return false;
        }

        if (System.currentTimeMillis() - pendingModeAt > MODE_CONFIRM_WINDOW_MS) {
            pendingMode = null;
            return false;
        }

        return !pendingMode.equalsIgnoreCase(mode);
    }

    private void setControlsEnabled(boolean enabled) {
        // Bật/tắt toàn bộ control cùng lúc để tránh thao tác sai trạng thái
        seekBarServoX.setEnabled(enabled);
        seekBarServoY.setEnabled(enabled);
        btnPump.setEnabled(enabled);
        btnBuzzer.setEnabled(enabled);
    }

    private int progressToServoY(int progress) {
        return clampServoY(SERVO_Y_MIN + progress);
    }

    private int servoYToProgress(int angle) {
        return clampServoY(angle) - SERVO_Y_MIN;
    }

    private int clampServoY(int angle) {
        if (angle < SERVO_Y_MIN) {
            return SERVO_Y_MIN;
        }
        if (angle > SERVO_Y_MAX) {
            return SERVO_Y_MAX;
        }
        return angle;
    }

    /**
     * Cập nhật giao diện nút bơm dựa trên trạng thái thực tế (ưu tiên)
     * hoặc lệnh cuối cùng được gửi (fallback).
     * Hiển thị: "BOM BẬT" (xanh) hoặc "BOM TẮT" (nhạt)
     */
    private void updatePumpButton() {
        // Cập nhật text/màu của nút bơm dựa trên trạng thái thực tế hoặc trạng thái lệnh
        boolean displayPump = pumpActualAvailable ? pumpActual : pumpOn;
        btnPump.setText(displayPump ? "💧 BƠM BẬT" : "💧 BƠM TẮT");
        btnPump.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            displayPump ? Color.parseColor("#BAE6FD") : Color.parseColor("#E0F2FE")));
    }

    /**
     * Cập nhật giao diện nút còi dựa trên trạng thái thực tế (ưu tiên)
     * hoặc lệnh cuối cùng được gửi (fallback).
     * Hiển thị: "COI BẬT" (tím) hoặc "COI TẮT" (nhạt)
     */
    private void updateBuzzerButton() {
        // Cập nhật text/màu của nút còi theo logic giống nút bơm
        boolean displayBuzzer = buzzerActualAvailable ? buzzerActual : buzzerOn;
        btnBuzzer.setText(displayBuzzer ? "🔔 CÒI BẬT" : "🔔 CÒI TẮT");
        btnBuzzer.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
            displayBuzzer ? Color.parseColor("#E9D5FF") : Color.parseColor("#F3E8FF")));
    }
}
