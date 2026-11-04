package com.phone.doctor.fm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.StatusBarManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;

import java.util.function.Consumer;

/** Everything that needs a newer Android class lives here, so older devices (down to 4.4) never load it. */
final class Compat {
    /** Android 6+: the alarm also fires in Doze (inexact, no special permission needed). */
    static void alarmIdle(android.app.AlarmManager am, long when, PendingIntent p) {
        am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, when, p);
    }

    static String channelOf(Notification n) { return n.getChannelId(); }

    static boolean allFiles() { return android.os.Environment.isExternalStorageManager(); }

    static final int SIGNING_FLAG = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;

    static android.content.pm.Signature[] signers(PackageInfo pi) {
        android.content.pm.SigningInfo si = pi.signingInfo;
        if (si == null) return null;
        return si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
    }

    static int minSdk(android.content.pm.ApplicationInfo ai) { return ai.minSdkVersion; }

    static String[] abis() { return Build.SUPPORTED_ABIS; }

    /** Page count with the system PDF renderer; a password-protected PDF throws SecurityException. */
    static String pdfPages(java.io.File f) {
        try {
            android.os.ParcelFileDescriptor fd = android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
            android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(fd);
            int n = r.getPageCount();
            r.close();
            return n + " pages";
        } catch (SecurityException e) {
            return "PASSWORD-PROTECTED pdf";
        } catch (Exception e) {
            return "the PDF renderer cannot open it: " + e.getMessage();
        }
    }

    /** Can a hardware/software decoder on this phone play this codec at this size? */
    static String decoderCan(String mime, int w, int h) {
        try {
            android.media.MediaCodecList l = new android.media.MediaCodecList(android.media.MediaCodecList.ALL_CODECS);
            boolean any = false;
            for (android.media.MediaCodecInfo ci : l.getCodecInfos()) {
                if (ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) {
                    if (!t.equalsIgnoreCase(mime)) continue;
                    any = true;
                    android.media.MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(t).getVideoCapabilities();
                    if (vc == null || vc.isSizeSupported(w, h)) return "decoder " + ci.getName() + " supports " + w + "x" + h;
                }
            }
            return any ? "NO DECODER ON THIS PHONE SUPPORTS " + w + "x" + h + " for " + mime + " (too large for the hardware)" : "NO DECODER FOR " + mime + " ON THIS PHONE";
        } catch (Exception e) {
            return "decoder check failed: " + e.getMessage();
        }
    }

    /** A PendingIntent the system may fill in (install/uninstall results). */
    static int mutable() { return Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0; }

    /** TLS keying-material exporter (adb wireless pairing). */
    static byte[] exportKeyingMaterial(javax.net.ssl.SSLSocket s, String label, int len) throws javax.net.ssl.SSLException {
        return android.net.ssl.SSLSockets.exportKeyingMaterial(s, label, null, len);
    }

    static PackageInfo webviewPackage() { return android.webkit.WebView.getCurrentWebViewPackage(); }

    static int immutable() {
        return Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
    }

    static long versionCode(PackageInfo pi) {
        return Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
    }

    static void startFg(Context c, Intent i) {
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
    }

    static void channels(Context c) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(AgentService.CH, "Phone Doctor עובד", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(AgentService.CH_ALERT, "Phone Doctor התראות", NotificationManager.IMPORTANCE_HIGH));
    }

    static Notification.Builder builder(Context c, String channel) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(c, channel) : new Notification.Builder(c);
    }

    // ---- digital assistant role (Android 10+)

    static boolean roleAvailable(Context c) {
        RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
        return rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT);
    }

    static boolean roleHeld(Context c) {
        return ((RoleManager) c.getSystemService(Context.ROLE_SERVICE)).isRoleHeld(RoleManager.ROLE_ASSISTANT);
    }

    static Intent roleIntent(Context c) {
        try {
            RoleManager rm = (RoleManager) c.getSystemService(Context.ROLE_SERVICE);
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return rm.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT);
        } catch (Exception ignored) { }
        return null;
    }

    // ---- ask the system to add the quick-settings tile (Android 13+)

    static boolean addTile(final MainActivity a) {
        try {
            ((StatusBarManager) a.getSystemService(Context.STATUS_BAR_SERVICE)).requestAddTileService(
                new ComponentName(a, QsTileService.class), "צלם ושאל",
                Icon.createWithResource(a, android.R.drawable.ic_menu_camera), a.getMainExecutor(),
                new Consumer<Integer>() {
                    public void accept(final Integer r) {
                        a.runOnUiThread(new Runnable() {
                            public void run() {
                                a.toast(r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ? "האריח נוסף להגדרות המהירות"
                                    : r == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED ? "האריח כבר קיים" : "האריח לא נוסף");
                            }
                        });
                    }
                });
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---- keyboard (Android 11+)

    static void showIme30(EditText e) {
        WindowInsetsController c = e.getWindowInsetsController();
        if (c != null) c.show(WindowInsets.Type.ime());
    }
}
