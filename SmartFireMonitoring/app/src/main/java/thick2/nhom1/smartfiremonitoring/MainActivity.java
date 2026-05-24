package thick2.nhom1.smartfiremonitoring;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;


import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    // Activity chính: giữ bottom navigation và điều phối các fragment của app
    BottomNavigationView bottomNav;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        
        // Cấp quyền thông báo cho Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            }
        }
        
        // Bật offline persistence trước khi bất kỳ FirebaseDatabase instance nào được chạm tới
        FirebaseHelper.enableOfflineMode();

        bottomNav = findViewById(R.id.bottomNav);

        // Khởi động service sớm để luôn sẵn sàng nghe trạng thái cháy.
        // Service sẽ tự chờ FirebaseAuth xong rồi mới đăng ký listener.
        Intent serviceIntent = new Intent(this, FireAlarmService.class);
        ContextCompat.startForegroundService(this, serviceIntent);

        FirebaseAuth.getInstance()
                .signInAnonymously()
                .addOnSuccessListener(result -> {
                    Log.d("FIREBASE_AUTH", "Login success");

                    Toast.makeText(this, "Firebase Connected", Toast.LENGTH_SHORT).show();

                    loadFragment(new DashboardFragment());
                    bottomNav.setOnItemSelectedListener(item -> {
                        if (item.getItemId() == R.id.nav_dashboard) {
                            loadFragment(new DashboardFragment());
                        } else if (item.getItemId() == R.id.nav_history) {
                            loadFragment(new HistoryFragment());
                        } else if (item.getItemId() == R.id.nav_threshold) {
                            loadFragment(new ThresholdFragment());
                        } else if (item.getItemId() == R.id.nav_alert) {
                            loadFragment(new NotificationFragment());
                        } else if (item.getItemId() == R.id.nav_control) {
                            loadFragment(new ControlFragment());
                        }
                        return true;
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e("FIREBASE_AUTH", e.getMessage());
                    Toast.makeText(this, "Login Failed", Toast.LENGTH_SHORT).show();
                });

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
    public void loadFragment(Fragment fragment) {
        // Thay nội dung trong container bằng fragment được chọn ở bottom navigation
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.container, fragment)
                .commit();
    }
}
