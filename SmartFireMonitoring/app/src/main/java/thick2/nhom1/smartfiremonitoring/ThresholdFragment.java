package thick2.nhom1.smartfiremonitoring;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

/**
 * Fragment quản lý việc Cài đặt Ngưỡng Cảnh Báo (Thresholds).
 * Cho phép người dùng chỉnh sửa các mức an toàn/cảnh báo cho cảm biến MQ-2 và Nhiệt độ (DHT11),
 * sau đó đẩy các giá trị này lên Firebase để ESP32 cập nhật lại cấu hình hệ thống.
 */
public class ThresholdFragment extends Fragment {
    /**
     * Fragment Cài đặt ngưỡng:
     * - Cho phép người dùng chỉnh ngưỡng MQ-2 và nhiệt độ
     * - Đẩy cấu hình mới lên Firebase
     * - Đồng bộ giá trị hiện tại từ ESP32 về ô nhập liệu
     */

    // Tham chiếu đến nhánh "thresholds" trên Firebase Realtime Database
    private DatabaseReference thresholdsRef;
    
    // Listener để lắng nghe sự thay đổi dữ liệu theo thời gian thực (realtime)
    private ValueEventListener thresholdListener;

    // Các biến đại diện cho các ô nhập liệu (EditText)
    private TextInputEditText etMq2Safe, etMq2Warning, etTempSafe, etTempWarning;
    
    // Nút Lưu thay đổi
    private MaterialButton btnSaveThresholds;
    
    // Bảng hiển thị tóm tắt ngưỡng hiện tại đang chạy trên hệ thống
    private TextView tvCurrentThresholds;

    // Cờ báo hiệu App vừa mới được mở màn hình này lên (để lấy dữ liệu cũ đắp vào EditText một lần duy nhất)
    private boolean isInitialLoad = true;

    public ThresholdFragment() {
        // Constructor rỗng bắt buộc của Fragment
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // Inflate layout và ánh xạ các ô nhập/ngòi lưu cấu hình ngưỡng
        // Liên kết (inflate) với file giao diện XML
        View view = inflater.inflate(R.layout.fragment_threshold, container, false);

        // Khởi tạo Database Reference trỏ đến đúng bảng thresholds/ trên Firebase
        thresholdsRef = FirebaseDatabase.getInstance().getReference("fire-alarm-system/thresholds");

        // Ánh xạ (bind) các View từ file XML vào các biến trong Java
        etMq2Safe = view.findViewById(R.id.etMq2Safe);
        etMq2Warning = view.findViewById(R.id.etMq2Warning);
        etTempSafe = view.findViewById(R.id.etTempSafe);
        etTempWarning = view.findViewById(R.id.etTempWarning);
        btnSaveThresholds = view.findViewById(R.id.btnSaveThresholds);
        tvCurrentThresholds = view.findViewById(R.id.tvCurrentThresholds);

        // Khởi chạy các hàm lắng nghe sự kiện
        setupSaveButton();
        listenThresholds();

        return view;
    }

    /**
     * Hàm lắng nghe dữ liệu từ Firebase.
     * Hàm này sẽ tự động chạy mỗi khi dữ liệu trên bảng thresholds/ có bất kỳ thay đổi nào.
     */
    private void listenThresholds() {
        // Lắng nghe realtime để UI luôn hiển thị ngưỡng đang chạy trên hệ thống
        thresholdListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                // Nếu không có dữ liệu trên bảng, bỏ qua
                if (!snapshot.exists()) return;

                // Lấy từng giá trị từ Firebase (ép về đúng kiểu Integer hoặc Double)
                Integer mq2Safe = snapshot.child("mq2_safe").getValue(Integer.class);
                Integer mq2Warning = snapshot.child("mq2_warning").getValue(Integer.class);
                Double tempSafe = snapshot.child("temp_safe").getValue(Double.class);
                Double tempWarning = snapshot.child("temp_warning").getValue(Double.class);

                // --- 1. CẬP NHẬT GIAO DIỆN HIỂN THỊ REALTIME ---
                // Format chuỗi hiển thị, nếu biến bị null thì thay bằng dấu "--"
                String currentText = String.format("MQ2: %s/%s\nTemp: %s/%s",
                        mq2Safe != null ? mq2Safe : "--",
                        mq2Warning != null ? mq2Warning : "--",
                        tempSafe != null ? tempSafe : "--",
                        tempWarning != null ? tempWarning : "--");
                        
                // Hiển thị vào khung thông tin bên dưới nút LƯU
                tvCurrentThresholds.setText(currentText);

                // --- 2. ĐIỀN DATA VÀO Ô NHẬP LIỆU (CHỈ 1 LẦN DUY NHẤT LÚC LOAD FORM) ---
                // Chỉ điền vào lúc đầu để giúp người dùng thấy giá trị cũ. 
                // Không điền liên tục mỗi khi data đổi, vì sẽ làm mất chữ người dùng đang gõ dở.
                if (isInitialLoad) {
                    if (mq2Safe != null) etMq2Safe.setText(String.valueOf(mq2Safe));
                    if (mq2Warning != null) etMq2Warning.setText(String.valueOf(mq2Warning));
                    if (tempSafe != null) etTempSafe.setText(String.valueOf(tempSafe));
                    if (tempWarning != null) etTempWarning.setText(String.valueOf(tempWarning));
                    isInitialLoad = false;
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                // Xử lý khi có lỗi (chẳng hạn mất mạng hoặc không có quyền đọc Firebase)
                Toast.makeText(getContext(), "Lỗi đọc dữ liệu: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        };
        // Gắn listener vào nhánh Firebase
        thresholdsRef.addValueEventListener(thresholdListener);
    }

    /**
     * Hàm cấu hình sự kiện bấm cho nút "LƯU NGƯỠNG".
     */
    private void setupSaveButton() {
        // Xử lý sự kiện bấm nút Lưu ngưỡng
        btnSaveThresholds.setOnClickListener(v -> {
            // Lấy chuỗi text mà người dùng nhập vào (trim() để xóa khoảng trắng thừa)
            String strMq2Safe = etMq2Safe.getText().toString().trim();
            String strMq2Warning = etMq2Warning.getText().toString().trim();
            String strTempSafe = etTempSafe.getText().toString().trim();
            String strTempWarning = etTempWarning.getText().toString().trim();

            // Kiểm tra xem người dùng có bỏ trống ô nào không
            if (TextUtils.isEmpty(strMq2Safe) || TextUtils.isEmpty(strMq2Warning) ||
                    TextUtils.isEmpty(strTempSafe) || TextUtils.isEmpty(strTempWarning)) {
                Toast.makeText(getContext(), "Vui lòng nhập đầy đủ các trường", Toast.LENGTH_SHORT).show();
                return; // Dừng xử lý nếu bị rỗng
            }

            try {
                // Ép kiểu chuỗi sang kiểu Số nguyên/Số thực
                int mq2Safe = Integer.parseInt(strMq2Safe);
                int mq2Warning = Integer.parseInt(strMq2Warning);
                float tempSafe = Float.parseFloat(strTempSafe);
                float tempWarning = Float.parseFloat(strTempWarning);

                // --- VALIDATE LOGIC HỆ THỐNG ---
                // Yêu cầu bắt buộc: Mức Cảnh báo phải LỚN HƠN mức An toàn.
                if (mq2Safe >= mq2Warning) {
                    etMq2Warning.setError("Phải lớn hơn mức An toàn");
                    return;
                }
                if (tempSafe >= tempWarning) {
                    etTempWarning.setError("Phải lớn hơn mức An toàn");
                    return;
                }

                // --- CHUẨN BỊ GÓI DỮ LIỆU ĐẨY LÊN FIREBASE ---
                // Dùng HashMap (cấu trúc key-value) để cập nhật đồng loạt nhiều trường 1 lúc
                Map<String, Object> updates = new HashMap<>();
                updates.put("mq2_safe", mq2Safe);
                updates.put("mq2_warning", mq2Warning);
                updates.put("temp_safe", tempSafe);
                updates.put("temp_warning", tempWarning);
                
                // Cờ QUAN TRỌNG: Đánh dấu updated = true để Firmware ESP32 biết có thay đổi mới và tải về
                updates.put("updated", true); 

                // Gửi lệnh cập nhật lên Firebase bằng updateChildren
                thresholdsRef.updateChildren(updates)
                        .addOnSuccessListener(unused -> Toast.makeText(getContext(), "Đã lưu ngưỡng mới", Toast.LENGTH_SHORT).show())
                        .addOnFailureListener(e -> Toast.makeText(getContext(), "Lỗi: " + e.getMessage(), Toast.LENGTH_SHORT).show());

            } catch (NumberFormatException e) {
                // Bắt lỗi nếu người dùng nhập ký tự chữ cái vào ô số
                Toast.makeText(getContext(), "Dữ liệu nhập không hợp lệ", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Gỡ listener Firebase khi fragment bị huỷ để tránh leak
        // Hủy việc lắng nghe Firebase khi Fragment bị đóng để giải phóng bộ nhớ (tránh memory leak)
        if (thresholdsRef != null && thresholdListener != null) {
            thresholdsRef.removeEventListener(thresholdListener);
        }
    }
}
