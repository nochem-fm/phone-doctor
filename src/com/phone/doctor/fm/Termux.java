package com.phone.doctor.fm;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs commands inside Termux (a full Linux userland: bash, apt/pkg, python, curl, git, ffmpeg...).
 *  1. Official way: the RUN_COMMAND intent (needs the com.termux.permission.RUN_COMMAND permission and
 *     "allow-external-apps = true" in ~/.termux/termux.properties). The result comes back in a PendingIntent.
 *  2. With root: run Termux's bash as the Termux user with Termux's environment (no setting needed).
 */
final class Termux {
    static final String PKG = "com.termux";
    static final String PERM = "com.termux.permission.RUN_COMMAND";
    static final String PREFIX = "/data/data/com.termux/files/usr", HOME = "/data/data/com.termux/files/home";

    static final class Result {
        String stdout = "", stderr = "", error = "";
        int exit = -1;
        boolean timedOut;
    }

    static boolean installed(Context c) {
        try { c.getPackageManager().getPackageInfo(PKG, 0); return true; } catch (Exception e) { return false; }
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Short state for the agent's capability list and the settings screen. */
    static String state(Context c, boolean root) {
        if (!installed(c)) return "not installed";
        if (permitted(c) && Core.prefs().getBoolean("termux_ok", false)) return "connected";
        if (root) return "available through root";
        return permitted(c) ? "installed; allow-external-apps may be off" : "installed but not connected";
    }

    // ------------------------------------------------------------------ the RUN_COMMAND intent

    private static final Map<Integer, Bundle> RESULTS = new HashMap<Integer, Bundle>();
    private static final Map<Integer, CountDownLatch> WAIT = new HashMap<Integer, CountDownLatch>();
    private static int seq = (int) (System.currentTimeMillis() % 100000);

    static Result viaIntent(Context c, String cmd, int timeoutSec) throws Exception {
        int id;
        CountDownLatch latch = new CountDownLatch(1);
        synchronized (RESULTS) { id = ++seq; WAIT.put(id, latch); }
        Intent back = new Intent(c, Done.class).putExtra("id", id);
        PendingIntent pi = PendingIntent.getBroadcast(c, id, back, Compat.mutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName(PKG, "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", PREFIX + "/bin/bash");
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", cmd});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        i.putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Phone Doctor");
        i.putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pi);
        if (Build.VERSION.SDK_INT >= 26) Compat.startForegroundService(c, i); else c.startService(i);
        boolean done = latch.await(timeoutSec, TimeUnit.SECONDS);
        Bundle b;
        synchronized (RESULTS) { WAIT.remove(id); b = RESULTS.remove(id); }
        Result r = new Result();
        if (!done || b == null) { r.timedOut = true; return r; }
        r.stdout = b.getString("stdout", "");
        r.stderr = b.getString("stderr", "");
        r.exit = b.getInt("exitCode", -1);
        int err = b.getInt("err", -1);
        String em = b.getString("errmsg", "");
        if (err > 0 || (em != null && !em.isEmpty() && r.exit != 0)) r.error = "Termux error " + err + ": " + em;
        return r;
    }

    /** Termux sends the result here (the "result" bundle: stdout, stderr, exitCode, err, errmsg). */
    public static final class Done extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            int id = i.getIntExtra("id", -1);
            Bundle b = i.getBundleExtra("result");
            synchronized (RESULTS) {
                CountDownLatch l = WAIT.get(id);
                if (l == null) return;
                RESULTS.put(id, b == null ? new Bundle() : b);
                l.countDown();
            }
        }
    }

    // ------------------------------------------------------------------ root fallback

    static String uid() {
        String u = Shell.run("stat -c %u /data/data/com.termux 2>/dev/null", true, 10).out.trim();
        return u.matches("\\d+") ? u : null;
    }

    /** The command runs as the Termux user, with Termux's environment, through Termux's own bash. */
    static Result viaRoot(String cmd, int timeoutSec) {
        Result r = new Result();
        String u = uid();
        if (u == null) { r.error = "Termux data folder not found (open Termux once to finish its setup)"; return r; }
        String script = "export PREFIX=" + PREFIX + " HOME=" + HOME + " TMPDIR=" + PREFIX + "/tmp LANG=en_US.UTF-8 TERM=xterm-256color\n"
            + "export PATH=" + PREFIX + "/bin:$PATH LD_PRELOAD=" + PREFIX + "/lib/libtermux-exec.so\n"
            + "cd \"$HOME\"\n" + cmd + "\n";
        String b64 = android.util.Base64.encodeToString(script.getBytes(java.nio.charset.Charset.forName("UTF-8")), android.util.Base64.NO_WRAP);
        Shell.Result s = Shell.run("echo " + b64 + " | base64 -d | su " + u + " -c " + PREFIX + "/bin/bash 2>&1", true, timeoutSec);
        r.stdout = s.out;
        r.exit = s.exit;
        r.timedOut = s.exit == -2;
        return r;
    }

    /** Uses the intent when it is set up, otherwise root; null error text means it ran. */
    static Result run(Context c, String cmd, int timeoutSec, boolean root) throws Exception {
        if (permitted(c)) {
            Result r = viaIntent(c, cmd, timeoutSec);
            if (!r.timedOut && r.error.isEmpty()) { Core.prefs().edit().putBoolean("termux_ok", true).apply(); return r; }
            if (!root) {
                if (r.timedOut && !Core.prefs().getBoolean("termux_ok", false))
                    r.error = "No answer from Termux. Usually 'allow-external-apps = true' is missing in ~/.termux/termux.properties, or Termux was never opened.";
                return r;
            }
            if (r.timedOut && Core.prefs().getBoolean("termux_ok", false)) return r;   // a real long command, not a setup problem
        }
        if (root) return viaRoot(cmd, timeoutSec);
        Result r = new Result();
        r.error = installed(c) ? "Termux is not connected to Phone Doctor (Settings > Termux)." : "Termux is not installed.";
        return r;
    }

    /** With root: turns on allow-external-apps for the user (as the Termux user, so file ownership stays right). */
    static String enableExternalApps() {
        String u = uid();
        if (u == null) return "פתח את Termux פעם אחת כדי שיסיים את ההתקנה, ונסה שוב.";
        String cmd = "su " + u + " -c 'mkdir -p " + HOME + "/.termux; f=" + HOME + "/.termux/termux.properties; "
            + "grep -q \"^allow-external-apps *= *true\" $f 2>/dev/null || echo \"allow-external-apps = true\" >> $f'; am force-stop com.termux";
        Shell.Result r = Shell.run(cmd, true, 20);
        return r.exit == 0 ? "" : r.out;
    }
}
