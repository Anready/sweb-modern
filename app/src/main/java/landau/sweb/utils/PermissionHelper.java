package landau.sweb.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionHelper {

    public static boolean hasOrRequestPermission(Context context, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                // Запрос разрешения на доступ к файловой системе
                ActivityCompat.requestPermissions(
                        (Activity) context,
                        new String[]{Manifest.permission.MANAGE_EXTERNAL_STORAGE},
                        requestCode
                );
                return false;
            }
        } else {
            // Для Android 10 и ниже
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return true;
            } else {
                // Запрос разрешения на запись в внешнее хранилище
                ActivityCompat.requestPermissions(
                        (Activity) context,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        requestCode
                );
                return false;
            }
        }
    }
}

