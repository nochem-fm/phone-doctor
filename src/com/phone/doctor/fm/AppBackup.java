package com.phone.doctor.fm;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.StatFs;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Backup and restore of a whole app: its APK file(s) and, with root, its private data.
 * Backups live in the app's own storage (files/appbackups/<package>_<time>/).
 */
final class AppBackup {
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    static File root(Context c) { return new File(c.getFilesDir(), "appbackups"); }

    /** Returns a report; the first word is OK or FAILED. */
    static String backup(Context c, String pkg, boolean rootOk, String tag) {
        if (!pkg.matches("[A-Za-z0-9_.]+")) return "FAILED: invalid package";
        PackageInfo pi;
        try { pi = c.getPackageManager().getPackageInfo(pkg, 0); } catch (Exception e) { return "FAILED: " + pkg + " is not installed"; }
        String apkPaths = Shell.run("pm path " + pkg, rootOk, 20).out;
        List<String> apks = new ArrayList<String>();
        for (String ln : apkPaths.split("\n")) if (ln.startsWith("package:")) apks.add(ln.substring(8).trim());
        if (apks.isEmpty()) {
            apks.add(pi.applicationInfo.sourceDir);
            if (android.os.Build.VERSION.SDK_INT >= 21 && pi.applicationInfo.splitSourceDirs != null)
                apks.addAll(Arrays.asList(pi.applicationInfo.splitSourceDirs));
        }
        String dataDir = "/data/data/" + pkg;
        long needKb = 0;
        for (String a : apks) needKb += new File(a).length() / 1024;
        if (rootOk) {
            try { needKb += Long.parseLong(Shell.run("du -sk " + q(dataDir) + " 2>/dev/null", true, 60).out.trim().split("\\s+")[0]); } catch (Exception ignored) { }
        }
        StatFs fs = new StatFs(c.getFilesDir().getPath());
        long freeKb = (long) fs.getAvailableBlocks() * fs.getBlockSize() / 1024;
        if (needKb * 12 / 10 + 200 * 1024 > freeKb) return "FAILED: not enough free space (need ~" + (needKb / 1024) + " MB, free " + (freeKb / 1024) + " MB)";

        String id = pkg + "_" + System.currentTimeMillis();
        File d = new File(root(c), id);
        d.mkdirs();
        int n = 0;
        StringBuilder sh = new StringBuilder();
        for (String a : apks) sh.append("cp ").append(q(a)).append(' ').append(q(d.getPath() + "/" + (n++) + ".apk")).append(" && ");
        sh.append("true");
        boolean apkOk = Shell.run(sh.toString(), rootOk, 180).exit == 0;
        if (!apkOk && !rootOk) {   // without root, copy through Java (the APK files are world-readable)
            apkOk = true;
            n = 0;
            for (String a : apks) { try { copy(new File(a), new File(d, (n++) + ".apk")); } catch (Exception e) { apkOk = false; } }
        }
        boolean data = false;
        if (rootOk) {
            Shell.Result r = Shell.run("mkdir -p " + q(d + "/data") + " && cp -a " + q(dataDir) + "/. " + q(d + "/data") + "/ 2>/dev/null; "
                + "rm -rf " + q(d + "/data/cache") + " " + q(d + "/data/code_cache") + " " + q(d + "/data/lib") + "; echo done", true, 600);
            data = r.out.contains("done");
        }
        try {
            JSONObject meta = new JSONObject().put("pkg", pkg).put("version", pi.versionName).put("versionCode", pi.versionCode)
                .put("time", System.currentTimeMillis()).put("apks", apks.size()).put("data", data).put("tag", tag == null ? "" : tag);
            FileOutputStream o = new FileOutputStream(new File(d, "meta.json"));
            o.write(meta.toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
        if (!apkOk) { Shell.run("rm -rf " + q(d.getPath()), rootOk, 60); return "FAILED: could not copy the APK files"; }
        return "OK backup_id=" + id + " version " + pi.versionName + ", APK files: " + apks.size()
            + (data ? ", app data included" : rootOk ? ", app data could NOT be copied" : ", app data NOT included (needs root)");
    }

    private static void copy(File a, File b) throws Exception {
        FileInputStream in = new FileInputStream(a);
        FileOutputStream o = new FileOutputStream(b);
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        o.close();
    }

    static JSONObject meta(File d) {
        try {
            FileInputStream in = new FileInputStream(new File(d, "meta.json"));
            byte[] b = new byte[(int) new File(d, "meta.json").length()];
            int r = in.read(b);
            in.close();
            return new JSONObject(new String(b, 0, Math.max(0, r), "UTF-8"));
        } catch (Exception e) {
            return null;
        }
    }

    static String list(Context c, String pkg) {
        File[] l = root(c).listFiles();
        if (l == null || l.length == 0) return "No app backups.";
        List<File> fs = new ArrayList<File>(Arrays.asList(l));
        Collections.sort(fs);
        StringBuilder sb = new StringBuilder();
        for (File d : fs) {
            JSONObject m = meta(d);
            if (m == null || (pkg != null && !pkg.isEmpty() && !pkg.equals(m.optString("pkg")))) continue;
            sb.append(d.getName()).append(": ").append(m.optString("pkg")).append(" v").append(m.optString("version"))
                .append(m.optBoolean("data") ? " +data" : " apk only").append(m.optString("tag").isEmpty() ? "" : " (" + m.optString("tag") + ")")
                .append(", ").append(android.text.format.DateFormat.format("dd/MM/yyyy HH:mm", m.optLong("time"))).append('\n');
        }
        return sb.length() == 0 ? "No backups for " + pkg : sb.toString();
    }

    /** Restores APK and (with root) data. A fresh backup of the current state is taken first. */
    static String restore(Context c, String id, boolean rootOk) {
        File d = new File(root(c), id);
        JSONObject m = meta(d);
        if (m == null || !id.matches("[A-Za-z0-9_.]+")) return "FAILED: backup not found";
        if (!rootOk) return "FAILED: restore needs root (installing over an app and writing its data). Without root the user can install the APK manually.";
        String pkg = m.optString("pkg");
        String pre = "";
        try {
            c.getPackageManager().getPackageInfo(pkg, 0);
            pre = backup(c, pkg, true, "before restore");
        } catch (Exception ignored) { }
        File[] apks = d.listFiles();
        List<String> files = new ArrayList<String>();
        if (apks != null) for (File f : apks) if (f.getName().endsWith(".apk")) files.add(f.getPath());
        Collections.sort(files);
        StringBuilder sh = new StringBuilder("T=/data/local/tmp/pd_restore; rm -rf $T; mkdir -p $T; ");
        StringBuilder inst = new StringBuilder();
        for (int i = 0; i < files.size(); i++) { sh.append("cp ").append(q(files.get(i))).append(" $T/").append(i).append(".apk; "); inst.append(" $T/").append(i).append(".apk"); }
        sh.append("chmod 644 $T/*.apk; ");
        sh.append(files.size() > 1 ? "pm install-multiple -r -d" + inst : "pm install -r -d" + inst).append(" 2>&1; rm -rf $T");
        String io = Shell.run(sh.toString(), true, 300).out.trim();
        boolean installed = io.contains("Success");
        String dataRes = "";
        if (m.optBoolean("data") && new File(d, "data").exists()) {
            String dd = "/data/data/" + pkg;
            String script = "am force-stop " + pkg + "; U=$(stat -c %u " + q(dd) + "); "
                + "[ -n \"$U\" ] || exit 3; "
                + "find " + q(dd) + " -mindepth 1 -maxdepth 1 ! -name lib -exec rm -rf {} + 2>/dev/null || (cd " + q(dd) + " && ls -A | grep -v '^lib$' | while read f; do rm -rf \"$f\"; done); "
                + "cp -a " + q(d + "/data") + "/. " + q(dd) + "/ && chown -R $U:$U " + q(dd) + " && (restorecon -R " + q(dd) + " 2>/dev/null; true) && echo restored";
            Shell.Result r = Shell.run(script, true, 600);
            dataRes = r.out.contains("restored") ? ", data restored" : ", DATA RESTORE FAILED: " + r.out.trim();
        }
        return (installed ? "OK installed v" + m.optString("version") : "INSTALL RESULT: " + io) + dataRes
            + (pre.startsWith("OK") ? ". The state before the restore was saved as " + pre.substring(3).split(" ")[0] : "");
    }

    static long totalKb(Context c) {
        try { return Long.parseLong(Shell.run("du -sk " + q(root(c).getPath()) + " 2>/dev/null", false, 30).out.trim().split("\\s+")[0]); } catch (Exception e) { return 0; }
    }

    static void deleteAll(Context c, boolean rootOk) {
        Shell.run("rm -rf " + q(root(c).getPath()), rootOk, 120);
    }
}
