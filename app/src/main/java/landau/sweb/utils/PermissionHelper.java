package landau.sweb.utils;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class PermissionHelper {

    public static boolean hasOrRequestPermission(Context context, int requestCode) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return false;
            } else {
                ActivityCompat.requestPermissions(
                        (Activity) context,
                        new String[]{Manifest.permission.MANAGE_EXTERNAL_STORAGE},
                        requestCode
                );
                return true;
            }
        } else {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                return false;
            } else {
                ActivityCompat.requestPermissions(
                        (Activity) context,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        requestCode
                );
                return true;
            }
        }
    }
}

