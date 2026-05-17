package thick2.nhom1.smartfiremonitoring;

import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;


import com.google.firebase.auth.FirebaseAuth;
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        // Bật offline persistence
        FirebaseHelper.enableOfflineMode();

        // Đăng nhập ẩn danh Firebase
        FirebaseAuth.getInstance()
                .signInAnonymously()
                .addOnSuccessListener(result -> {

                    Log.d("FIREBASE_AUTH", "Login success");

                    Toast.makeText(
                            this,
                            "Firebase Connected",
                            Toast.LENGTH_SHORT
                    ).show();

                    // TODO:
                    // bắt đầu đọc dữ liệu Firebase ở đây

                })
                .addOnFailureListener(e -> {

                    Log.e(
                            "FIREBASE_AUTH",
                            e.getMessage()
                    );

                    Toast.makeText(
                            this,
                            "Login Failed",
                            Toast.LENGTH_SHORT
                    ).show();
                });
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });
    }
}