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
        return Build.VERSION.SDK_INT < 30 || Compat.allFiles();
    }
}
