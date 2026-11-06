package com.phone.doctor.fm;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.Build;

/** Permission checks the agent needs too (without an Activity). */
final class MainActivityHelper {
    static boolean usageOk(Context c) {
        if (Build.VERSION.SDK_INT < 21) return true;
        try {
            AppOpsManager a = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
            return a.checkOpNoThrow("android:get_usage_stats", android.os.Process.myUid(), c.getPackageName()) == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean filesOk() {
        if (Build.VERSION.SDK_INT < 30 || Compat.allFiles()) return true;
        // targetSdk 28 keeps legacy storage: the storage permission alone opens all of /sdcard
        android.content.Context c = Core.app;
        return c != null && c.getApplicationInfo().targetSdkVersion < 29
            && c.checkSelfPermission("android.permission.WRITE_EXTERNAL_STORAGE") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }
}
