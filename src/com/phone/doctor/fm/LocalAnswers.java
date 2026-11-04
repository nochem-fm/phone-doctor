package com.phone.doctor.fm;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.Settings;

import java.util.Locale;

/**
 * Work that needs no AI and no quota:
 * - instant answers to simple factual questions (RAM, uptime, storage, battery, Android version);
 * - a quick scan of common trouble spots, attached to the first message of a chat, so the agent
 *   starts with facts instead of spending requests on finding them.
 */
final class LocalAnswers {
    private static String gb(long b) { return String.format(Locale.US, "%.1f GB", b / 1073741824.0); }

    private static String norm(String s) { return s.toLowerCase(Locale.US).replace("?", "").replace("׳", "'").trim(); }

    private static boolean any(String t, String... w) {
        for (String x : w) if (t.contains(x)) return true;
        return false;
    }

    /** Hebrew answer, or null when the question needs the agent. */
    static String answer(Context c, String text) {
        if (text == null) return null;
        String t = norm(text);
        if (t.length() > 60) return null;                       // longer messages are real problems
        if (any(t, "לא ", "אין", "מלא", "בעיה", "תקן", "תתקן", "למה", "איך", "עזור", "מתרוקנת", "נגמרת", "איטי", "קורס", "נתקע", "תפנה", "לפנות")) return null;
        // only real questions ("כמה ...?", "מה ...") are answered locally; "באחסון הראשי" is a reply, not a question
        if (!(text.contains("?") || any(t, "כמה", "מה ", "מהי", "מהו", "איזה", "איזו", "תגיד", "בדוק", "תבדוק"))) return null;
        String a = null;
        if (any(t, "ram", "זיכרון", "זכרון")) a = ram(c);
        else if (any(t, "דלוק", "uptime", "זמן פעולה", "הודלק", "אתחול האחרון", "אתחול אחרון")) a = uptime();
        else if (any(t, "אחסון", "מקום פנוי", "נפח")) a = storage();
        else if (any(t, "סוללה", "בטריה", "battery")) a = battery(c);
        else if (any(t, "גרסת אנדרואיד", "גירסת אנדרואיד", "איזה אנדרואיד", "גרסת מערכת", "android version")) a = version();
        return a == null ? null : a + "\n\n_נענה מקומית, בלי שימוש במכסה._";
    }

    static String ram(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        long used = mi.totalMem - mi.availMem;
        int pct = (int) (used * 100 / Math.max(1, mi.totalMem));
        return "**זיכרון RAM:** בשימוש " + gb(used) + " מתוך " + gb(mi.totalMem) + " (" + pct + "%). פנוי " + gb(mi.availMem) + "."
            + (mi.lowMemory ? "\nהמערכת מדווחת על זיכרון נמוך כרגע." : "");
    }

    static String uptime() {
        long s = SystemClock.elapsedRealtime() / 1000;
        long d = s / 86400, h = (s % 86400) / 3600, m = (s % 3600) / 60;
        return "**המכשיר דלוק** " + (d > 0 ? d + " ימים, " : "") + h + " שעות ו-" + m + " דקות מאז האתחול האחרון.";
    }

    static String storage() {
        StatFs s = new StatFs(Environment.getDataDirectory().getPath());
        long free = (long) s.getAvailableBlocks() * s.getBlockSize(), total = (long) s.getBlockCount() * s.getBlockSize();
        return "**אחסון פנימי:** פנוי " + gb(free) + " מתוך " + gb(total) + " (" + (free * 100 / Math.max(1, total)) + "% פנוי).";
    }

    static String battery(Context c) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return null;
        int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int health = b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0);
        int plug = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        String h = health == BatteryManager.BATTERY_HEALTH_GOOD ? "תקינה" : health == BatteryManager.BATTERY_HEALTH_OVERHEAT ? "התחממות יתר"
            : health == BatteryManager.BATTERY_HEALTH_DEAD ? "פגומה" : health == BatteryManager.BATTERY_HEALTH_COLD ? "קרה מדי" : "לא ידוע";
        return "**סוללה:** " + lvl + "%" + (plug != 0 ? ", בטעינה" : ", לא בטעינה") + ". טמפרטורה " + (temp / 10.0) + "°C. מצב: " + h + ".";
    }

    static String version() {
        return "**מערכת:** אנדרואיד " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + "), " + Build.MANUFACTURER + " " + Build.MODEL
            + (Build.VERSION.SDK_INT >= 23 ? ". עדכון אבטחה: " + Build.VERSION.SECURITY_PATCH : "") + ".";
    }

    // ---------------------------------------------------------------- quick scan for the agent (English, compact)

    private static String pkgState(PackageManager pm, String p) {
        try {
            PackageInfo pi = pm.getPackageInfo(p, 0);
            ApplicationInfo ai = pi.applicationInfo;
            return pi.versionName + (ai.enabled ? "" : " DISABLED");
        } catch (Exception e) {
            return "not installed";
        }
    }

    /** The WebView the system really uses (it is not always com.google.android.webview). */
    static String webview(Context c) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                PackageInfo w = Compat.webviewPackage();
                if (w != null) return w.packageName + " " + w.versionName + (w.applicationInfo.enabled ? "" : " DISABLED");
            }
        } catch (Throwable ignored) { }
        PackageManager pm = c.getPackageManager();
        for (String p : new String[]{"com.google.android.webview", "com.android.webview", "com.android.chrome"}) {
            String st = pkgState(pm, p);
            if (!st.equals("not installed")) return p + " " + st;
        }
        return "unknown";
    }

    static String quickScan(Context c, boolean root) {
        StringBuilder sb = new StringBuilder("[AUTOMATIC QUICK SCAN, collected locally before this message. Starting facts only: it does NOT replace the checks the request needs]\n");
        try {
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            long free = (long) s.getAvailableBlocks() * s.getBlockSize(), total = (long) s.getBlockCount() * s.getBlockSize();
            sb.append("storage /data: ").append(gb(free)).append(" free of ").append(gb(total)).append(free * 100 / Math.max(1, total) < 3 ? " (over 97% used)" : " (fine: under 97% used is not a shortage)").append('\n');
        } catch (Exception ignored) { }
        try {
            ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            sb.append("RAM: ").append(gb(mi.availMem)).append(" available of ").append(gb(mi.totalMem)).append(mi.lowMemory ? " (LOW)" : "").append('\n');
        } catch (Exception ignored) { }
        sb.append("uptime: ").append(SystemClock.elapsedRealtime() / 3600000).append(" h\n");
        try {
            String bat = battery(c);
            if (bat != null) sb.append("battery: ").append(bat.replace("**", "")).append('\n');
        } catch (Exception ignored) { }
        try {
            android.content.ContentResolver r = c.getContentResolver();
            sb.append("auto time: ").append(Settings.Global.getInt(r, Settings.Global.AUTO_TIME, 1) == 1 ? "on" : "OFF")
                .append(", airplane: ").append(Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) == 1 ? "ON" : "off");
            if (Build.VERSION.SDK_INT >= 21) {
                android.os.PowerManager pw = (android.os.PowerManager) c.getSystemService(Context.POWER_SERVICE);
                sb.append(", battery saver: ").append(pw.isPowerSaveMode() ? "ON" : "off");
            }
            sb.append('\n');
        } catch (Exception ignored) { }
        PackageManager pm = c.getPackageManager();
        sb.append("Google Play Services: ").append(pkgState(pm, "com.google.android.gms"))
            .append("; Play Store: ").append(pkgState(pm, "com.android.vending"))
            .append("; active WebView: ").append(webview(c))
            .append("; Chrome: ").append(pkgState(pm, "com.android.chrome")).append('\n');
        try {
            int dis = 0;
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) if (!ai.enabled) dis++;
            sb.append("disabled packages: ").append(dis).append('\n');
        } catch (Exception ignored) { }
        try {   // apps that crashed in the last 24 h (needs root or the DUMP permission; skipped otherwise)
            String out = Shell.run("dumpsys dropbox --print data_app_crash 2>/dev/null | grep '^Process: ' | sort | uniq -c | sort -rn | head -5", root, 20).out.trim();
            if (!out.isEmpty() && !out.toLowerCase().contains("permission")) sb.append("app crashes kept in the system crash history (count, process):\n").append(out).append('\n');
        } catch (Exception ignored) { }
        return sb.toString();
    }
}
