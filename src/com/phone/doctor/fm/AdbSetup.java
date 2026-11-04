package com.phone.doctor.fm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gives Phone Doctor the ADB-only permissions (and tries Device Owner) by itself, through wireless debugging:
 * finds the pairing / connect ports with mDNS, reads the pairing code from the screen (accessibility) or
 * takes it from a notification reply, pairs, connects to its own adbd and runs the commands as the shell user.
 * Android 11+ and Wi-Fi only (wireless debugging does not exist otherwise).
 */
final class AdbSetup {
    static final int NOTE = 4400;
    static volatile AdbSetup cur;

    final Context c;
    volatile String code, host;
    volatile int pairPort, connPort;
    volatile boolean done, paired;
    private NsdManager nsd;
    private final java.util.List<NsdManager.DiscoveryListener> listeners = new java.util.ArrayList<NsdManager.DiscoveryListener>();
    private final ArrayDeque<NsdServiceInfo> toResolve = new ArrayDeque<NsdServiceInfo>();
    private boolean resolving, clickedPair;
    private final Set<String> mine = new HashSet<String>();

    private AdbSetup(Context c) { this.c = c.getApplicationContext(); }

    static boolean supported() { return Build.VERSION.SDK_INT >= 30; }

    static boolean onWifi(Context c) {
        try {
            ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        } catch (Exception e) { return false; }
    }

    static boolean devOptionsOn(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1;
    }

    static boolean canWriteSecure(Context c) {
        return c.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Starts the flow. When we already paired once and may write secure settings, it runs with no user steps at all. */
    static void start(Context ctx) {
        if (cur != null && !cur.done) { cur.status("ההגדרה כבר רצה", "ממתין לקוד הצימוד או לחיבור"); return; }
        final AdbSetup a = new AdbSetup(ctx);
        cur = a;
        new Thread(new Runnable() { public void run() { a.run(); } }).start();
    }

    // ------------------------------------------------------------------ main flow (background thread)

    private void run() {
        boolean auto = Core.prefs().getBoolean("adb_paired", false) && canWriteSecure(c);
        try {
            localIps();
            if (auto) {   // paired before: switch wireless debugging on ourselves and go
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
                paired = true;
                status("מתחבר לניפוי באגים אלחוטי…", "");
            } else {
                status("ממתין לקוד הצימוד",
                    "בהגדרות: הפעל 'ניפוי באגים אלחוטי', היכנס אליו ולחץ 'התאמת מכשיר באמצעות קוד התאמה'. "
                        + (ScreenService.inst != null ? "הקוד ייקרא מהמסך אוטומטית, או הקלד אותו כאן." : "הקלד כאן את הקוד בן 6 הספרות."));
            }
            discover("_adb-tls-pairing._tcp");
            discover("_adb-tls-connect._tcp");
            long end = System.currentTimeMillis() + 6 * 60000L;
            int pairFails = 0;
            while (!done && System.currentTimeMillis() < end) {
                if (!paired) {
                    watchScreen();
                    if (code != null && pairPort > 0) {
                        String cd = code;
                        status("מצמד…", "");
                        try {
                            new AdbWifi(c).pair(host(), pairPort, cd);
                            paired = true;
                            Core.prefs().edit().putBoolean("adb_paired", true).apply();
                            status("הצימוד הצליח, מתחבר…", "אפשר לסגור את חלון הקוד.");
                        } catch (Exception e) {
                            code = null;
                            if (++pairFails >= 4) { finish(false, "הצימוד נכשל: " + e.getMessage()); return; }
                            status("הצימוד נכשל, נסה שוב", e.getMessage() + ". פתח שוב את 'התאמת מכשיר באמצעות קוד התאמה' והזן את הקוד החדש.");
                        }
                    }
                } else {
                    if (connPort == 0) watchScreen();
                    if (connPort > 0) { grant(); return; }
                }
                Thread.sleep(700);
            }
            if (!done) finish(false, paired ? "לא נמצא פורט החיבור של ניפוי הבאגים האלחוטי. ודא שהוא מופעל ונסה שוב."
                : "לא התקבל קוד צימוד בזמן. נסה שוב.");
        } catch (Exception e) {
            finish(false, "שגיאה: " + e);
        } finally {
            stopDiscovery();
        }
    }

    private String host() { return host != null ? host : "127.0.0.1"; }

    private void grant() {
        String p = c.getPackageName();
        status("נותן הרשאות…", "");
        StringBuilder res = new StringBuilder();
        try {
            AdbWifi a = new AdbWifi(c);
            String out = a.shell(host(), connPort,
                "for x in WRITE_SECURE_SETTINGS DUMP READ_LOGS; do pm grant " + p + " android.permission.$x 2>&1 && echo \"OK $x\"; done");
            res.append(out.contains("OK WRITE_SECURE_SETTINGS") ? "✓ " : "✗ ").append("שינוי הגדרות מערכת (WRITE_SECURE_SETTINGS)\n");
            res.append(out.contains("OK DUMP") ? "✓ " : "✗ ").append("קריאת נתוני מערכת (DUMP)\n");
            res.append(out.contains("OK READ_LOGS") ? "✓ " : "✗ ").append("קריאת לוגים (READ_LOGS)\n");
            DevicePolicyManager dpm = (DevicePolicyManager) c.getSystemService(Context.DEVICE_POLICY_SERVICE);
            if (dpm.isDeviceOwnerApp(p)) {
                res.append("✓ מנהל מכשיר (Device Owner): כבר מוגדר\n");
            } else {
                String d = a.shell(host(), connPort, "dpm set-device-owner " + p + "/.AdminReceiver 2>&1");
                String low = d.toLowerCase();
                if (low.contains("success")) res.append("✓ מנהל מכשיר (Device Owner)\n");
                else if (low.contains("account")) res.append("✗ מנהל מכשיר (Device Owner): יש חשבונות במכשיר (למשל חשבון Google). "
                    + "אנדרואיד מאפשר להגדיר Device Owner רק כשאין אף חשבון. אפשר להסיר זמנית את החשבונות (הגדרות > חשבונות), להריץ שוב, ולהחזיר אותם אחר כך.\n");
                else if (low.contains("already") || low.contains("users")) res.append("✗ מנהל מכשיר (Device Owner): כבר יש מנהל מכשיר אחר או כמה משתמשים במכשיר.\n");
                else res.append("✗ מנהל מכשיר (Device Owner): ").append(d.trim().length() > 200 ? d.trim().substring(0, 200) : d.trim()).append('\n');
            }
            finish(true, res.toString().trim());
        } catch (Exception e) {
            if (String.valueOf(e.getMessage()).contains("לצמד שוב")) Core.prefs().edit().putBoolean("adb_paired", false).apply();
            finish(false, "החיבור לניפוי הבאגים האלחוטי נכשל: " + e.getMessage());
        } finally {
            // wireless debugging left on is an open door on this Wi-Fi: switch it off again
            if (canWriteSecure(c)) {
                try { Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 0); } catch (Exception ignored) { }
            }
        }
    }

    // ------------------------------------------------------------------ the pairing code and ports, from the screen

    private static final Pattern CODE = Pattern.compile("\"(\\d{6})\"");
    private static final Pattern IPPORT = Pattern.compile("(\\d{1,3}(?:\\.\\d{1,3}){3}):(\\d{3,5})");

    private void watchScreen() {
        ScreenService s = ScreenService.inst;
        if (s == null) return;
        String d;
        try { d = s.dump(); } catch (Exception e) { return; }
        if (d == null) return;
        Matcher cm = CODE.matcher(d), ip = IPPORT.matcher(d);
        boolean dialog = cm.find();
        if (!paired && dialog && code == null) {
            code = cm.group(1);
            status("נמצא קוד צימוד", "");
        }
        if (ip.find()) {
            String h = ip.group(1);
            int port = Integer.parseInt(ip.group(2));
            if (mine.isEmpty() || mine.contains(h)) {
                if (dialog) { if (pairPort == 0) { pairPort = port; host = h; } }
                else if (paired && port != pairPort && connPort == 0) { connPort = port; host = h; }
            }
        }
        if (!paired && !dialog && !clickedPair) {   // on the Wireless debugging page: open the pairing dialog for the user
            String low = d.toLowerCase();
            String hit = low.contains("pair device with pairing code") ? "pairing code" : d.contains("קוד התאמה") ? "קוד התאמה" : d.contains("קוד צימוד") ? "קוד צימוד" : null;
            if (hit != null) { clickedPair = true; try { s.act("click_text", 0, 0, 0, 0, hit); } catch (Exception ignored) { } }
        }
    }

    // ------------------------------------------------------------------ mDNS

    private void localIps() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(ni.getInetAddresses()))
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) mine.add(a.getHostAddress());
        } catch (Exception ignored) { }
    }

    private void discover(final String type) {
        try {
            if (nsd == null) nsd = (NsdManager) c.getSystemService(Context.NSD_SERVICE);
            NsdManager.DiscoveryListener l = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) { }
                public void onStopDiscoveryFailed(String t, int e) { }
                public void onDiscoveryStarted(String t) { }
                public void onDiscoveryStopped(String t) { }
                public void onServiceLost(NsdServiceInfo i) { }
                public void onServiceFound(NsdServiceInfo i) {
                    synchronized (toResolve) { toResolve.add(i); }
                    resolveNext();
                }
            };
            listeners.add(l);
            nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, l);
        } catch (Exception ignored) { }
    }

    private void resolveNext() {
        final NsdServiceInfo i;
        synchronized (toResolve) {
            if (resolving || toResolve.isEmpty()) return;
            resolving = true;
            i = toResolve.poll();
        }
        try {
            nsd.resolveService(i, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo s, int e) { done(); }
                public void onServiceResolved(NsdServiceInfo s) {
                    String h = s.getHost() == null ? null : s.getHost().getHostAddress();
                    if (h != null && (mine.isEmpty() || mine.contains(h))) {
                        String t = s.getServiceType();
                        if (t.contains("pairing")) { if (pairPort == 0) { pairPort = s.getPort(); host = h; } }
                        else if (t.contains("connect")) { connPort = s.getPort(); host = h; }
                    }
                    done();
                }
                private void done() {
                    synchronized (toResolve) { resolving = false; }
                    resolveNext();
                }
            });
        } catch (Exception e) {
            synchronized (toResolve) { resolving = false; }
        }
    }

    private void stopDiscovery() {
        for (NsdManager.DiscoveryListener l : listeners) { try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) { } }
        listeners.clear();
    }

    // ------------------------------------------------------------------ notification with a reply box for the code

    void status(String title, String text) {
        try {
            AgentService.channels(c);
            Notification.Builder b = Compat.builder(c, AgentService.CH_ALERT).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("ניפוי באגים אלחוטי: " + title).setOnlyAlertOnce(true).setOngoing(!done);
            if (!text.isEmpty()) b.setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text));
            if (!paired && !done) {
                RemoteInput ri = new RemoteInput.Builder("code").setLabel("קוד הצימוד (6 ספרות)").build();
                PendingIntent pi = PendingIntent.getBroadcast(c, NOTE, new Intent(c, CodeReceiver.class),
                    Compat.mutable() | PendingIntent.FLAG_UPDATE_CURRENT);
                b.addAction(new Notification.Action.Builder(null, "הזן קוד", pi).addRemoteInput(ri).build());
            }
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTE, b.build());
        } catch (Exception ignored) { }
    }

    private void finish(boolean ok, String msg) {
        done = true;
        cur = null;
        Core.prefs().edit().putString("adb_result", msg).apply();
        status(ok ? "הסתיים" : "לא הצליח", msg);
        try {
            c.startActivity(new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("adbResult", (ok ? "" : "לא הצליח. ") + msg));
        } catch (Exception ignored) { }
    }

    /** The code typed into the notification. */
    public static final class CodeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent i) {
            Bundle r = RemoteInput.getResultsFromIntent(i);
            CharSequence v = r == null ? null : r.getCharSequence("code");
            AdbSetup a = cur;
            if (a == null || v == null) return;
            String d = v.toString().replaceAll("\\D", "");
            if (d.length() != 6) { a.status("הקוד צריך 6 ספרות", "הקלד שוב את הקוד מהחלון 'התאמת מכשיר'."); return; }
            a.code = d;
            a.status("מצמד…", "");
        }
    }
}
