package thick2.nhom1.smartfiremonitoring;

import com.google.firebase.database.FirebaseDatabase;

public class FirebaseHelper {

    private static boolean isPersistenceEnabled = false;

    public static void enableOfflineMode() {

        if (!isPersistenceEnabled) {

            FirebaseDatabase.getInstance()
                    .setPersistenceEnabled(true);
            // Offline cache
            // App mất mạng vẫn giữ dữ liệu
            // Realtime ổn định hơn
            isPersistenceEnabled = true;
        }
    }
}