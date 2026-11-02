package com.phone.doctor.fm;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** APK analysis (compatibility, signature, permissions, red flags) and controlled installation. */
final class ApkTools {
    private static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    /** Well-known apps that fakes like to imitate: label (lower case) -> real package. */
    private static final Map<String, String> KNOWN = new HashMap<String, String>();
    static {
        String[][] k = {{"whatsapp", "com.whatsapp"}, {"telegram", "org.telegram.messenger"}, {"waze", "com.waze"},
            {"google play store", "com.android.vending"}, {"chrome", "com.android.chrome"}, {"gmail", "com.google.android.gm"},
            {"youtube", "com.google.android.youtube"}, {"google", "com.google.android.googlequicksearchbox"}, {"maps", "com.google.android.apps.maps"},
            {"facebook", "com.facebook.katana"}, {"instagram", "com.instagram.android"}, {"moovit", "com.tranzmate"},
            {"netfree", "com.netfree"}, {"bit", "com.bnhp.payments.paymentsapp"}};
        for (String[] x : k) KNOWN.put(x[0], x[1]);
    }

    private static final String[][] RISKY = {
        {"android.permission.SEND_SMS", "שליחת SMS"}, {"android.permission.READ_SMS", "קריאת SMS"}, {"android.permission.RECEIVE_SMS", "קבלת SMS"},
        {"android.permission.READ_CALL_LOG", "יומן שיחות"}, {"android.permission.CALL_PHONE", "חיוג"}, {"android.permission.READ_CONTACTS", "אנשי קשר"},
        {"android.permission.RECORD_AUDIO", "מיקרופון"}, {"android.permission.CAMERA", "מצלמה"}, {"android.permission.ACCESS_FINE_LOCATION", "מיקום מדויק"},
        {"android.permission.ACCESS_BACKGROUND_LOCATION", "מיקום ברקע"}, {"android.permission.SYSTEM_ALERT_WINDOW", "חלונות מעל אפליקציות"},
        {"android.permission.REQUEST_INSTALL_PACKAGES", "התקנת אפליקציות"}, {"android.permission.READ_EXTERNAL_STORAGE", "קבצים"},
        {"android.permission.MANAGE_EXTERNAL_STORAGE", "כל הקבצים"}, {"android.permission.QUERY_ALL_PACKAGES", "רשימת כל האפליקציות"},
        {"android.permission.BIND_ACCESSIBILITY_SERVICE", "שירות נגישות"}, {"android.permission.BIND_DEVICE_ADMIN", "מנהל מכשיר"},
        {"android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", "קריאת התראות"}, {"android.permission.PACKAGE_USAGE_STATS", "נתוני שימוש"}};

    static String sha256(InputStream in) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) md.update(b, 0, n);
        in.close();
        return hex(md.digest());
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static Signature[] sigs(PackageInfo pi) {
        if (pi == null) return null;
        if (Build.VERSION.SDK_INT >= 28) {
            Signature[] s = Compat.signers(pi);
            if (s != null && s.length > 0) return s;
        }
        return pi.signatures;
    }

    private static int sigFlags() {
        return Build.VERSION.SDK_INT >= 28 ? Compat.SIGNING_FLAG : PackageManager.GET_SIGNATURES;
    }

    private static String certLine(Signature s) {
        try {
            X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(s.toByteArray()));
            String dn = c.getSubjectDN().getName();
            String sha = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray()));
            return "signer " + dn + " | cert SHA-256 " + sha + (dn.contains("Android Debug") ? " | DEBUG KEY (test build, not an official release)" : "");
        } catch (Exception e) {
            return "signer: unreadable (" + e.getMessage() + ")";
        }
    }

    private static Set<String> sigSet(Signature[] s) {
        Set<String> r = new HashSet<String>();
        if (s != null) for (Signature x : s) r.add(x.toCharsString());
        return r;
    }

    private static List<String> abisIn(File f) {
        List<String> r = new ArrayList<String>();
        try {
            ZipFile z = new ZipFile(f);
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                String n = e.nextElement().getName();
                if (n.startsWith("lib/") && n.endsWith(".so")) {
                    String abi = n.split("/")[1];
                    if (!r.contains(abi)) r.add(abi);
                }
            }
            z.close();
        } catch (Exception ignored) { }
        return r;
    }

    private static boolean hasDex(File f) {
        try { ZipFile z = new ZipFile(f); boolean d = z.getEntry("classes.dex") != null; z.close(); return d; } catch (Exception e) { return false; }
    }

    static List<String> deviceAbis() {
        if (Build.VERSION.SDK_INT >= 21) return Arrays.asList(Compat.abis());
        List<String> l = new ArrayList<String>();
        l.add(Build.CPU_ABI);
        if (Build.CPU_ABI2 != null && !Build.CPU_ABI2.isEmpty()) l.add(Build.CPU_ABI2);
        return l;
    }

    /** Full report on an APK file that is not (necessarily) installed. */
    static String info(Context c, String path, boolean rootOk) {
        File f = new File(path);
        File work = f;
        if (!f.canRead()) {
            if (!rootOk) return "Cannot read " + path + " (no permission).";
            work = new File(c.getCacheDir(), "inspect.apk");
            Shell.run("cp " + q(path) + " " + q(work.getPath()) + " && chmod 644 " + q(work.getPath()), true, 60);
            if (!work.canRead()) return "Cannot read " + path + " even with root.";
        }
        String low = path.toLowerCase(Locale.US);
        if (low.endsWith(".apks") || low.endsWith(".xapk") || low.endsWith(".apkm")) return bundleInfo(c, work);
        StringBuilder sb = new StringBuilder();
        PackageManager pm = c.getPackageManager();
        int flags = PackageManager.GET_PERMISSIONS | PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES | PackageManager.GET_RECEIVERS | sigFlags();
        PackageInfo pi = null;
        try { pi = pm.getPackageArchiveInfo(work.getPath(), flags); } catch (Exception ignored) { }
        if (pi == null) {
            try { pi = pm.getPackageArchiveInfo(work.getPath(), PackageManager.GET_PERMISSIONS); } catch (Exception ignored) { }
        }
        try { sb.append("file SHA-256: ").append(sha256(new FileInputStream(work))).append(" (check at https://www.virustotal.com/gui/file/<sha256>)\n"); } catch (Exception ignored) { }
        if (pi == null) return sb + "NOT A VALID APK: Android cannot parse it (damaged, incomplete download, or not an APK). " + FileTools.checkFile(work);
        ApplicationInfo ai = pi.applicationInfo;
        ai.sourceDir = work.getPath();
        ai.publicSourceDir = work.getPath();
        String label = String.valueOf(ai.loadLabel(pm));
        sb.append("app: ").append(label).append(" | package ").append(pi.packageName).append(" | version ").append(pi.versionName).append(" (code ").append(pi.versionCode).append(")\n");
        int minSdk = Build.VERSION.SDK_INT >= 24 ? Compat.minSdk(ai) : 0;
        sb.append("minSdk ").append(minSdk > 0 ? String.valueOf(minSdk) : "?").append(", targetSdk ").append(ai.targetSdkVersion)
            .append(" | this phone: SDK ").append(Build.VERSION.SDK_INT).append('\n');
        List<String> abis = abisIn(work), dev = deviceAbis();
        sb.append("native code ABIs: ").append(abis.isEmpty() ? "none (runs on any CPU)" : abis.toString()).append(" | phone supports ").append(dev).append('\n');
        boolean split = !hasDex(work);
        if (split) sb.append("NO classes.dex: this looks like a SPLIT part or a resource-only APK; it cannot be installed alone (it needs the base APK, e.g. from an .apks/.xapk bundle).\n");

        // verdict
        List<String> problems = new ArrayList<String>();
        if (minSdk > Build.VERSION.SDK_INT) problems.add("requires Android SDK " + minSdk + " but the phone has " + Build.VERSION.SDK_INT + " (INSTALL_FAILED_OLDER_SDK)");
        if (!abis.isEmpty()) {
            boolean ok = false;
            for (String a : abis) if (dev.contains(a)) ok = true;
            if (!ok) problems.add("its native code is only for " + abis + ", the phone CPU supports " + dev + " (INSTALL_FAILED_NO_MATCHING_ABIS)");
        }
        if (Build.VERSION.SDK_INT >= 34 && ai.targetSdkVersion < 23) problems.add("targetSdk " + ai.targetSdkVersion + " is blocked on Android 14+ (INSTALL_FAILED_DEPRECATED_SDK_VERSION; root install can bypass with --bypass-low-target-sdk-block)");
        if ((ai.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0) problems.add("marked testOnly: normal install refuses it (needs pm install -t)");

        PackageInfo inst = null;
        try { inst = pm.getPackageInfo(pi.packageName, PackageManager.GET_PERMISSIONS | sigFlags()); } catch (Exception ignored) { }
        Signature[] fs = sigs(pi);
        if (fs != null) for (Signature s : fs) sb.append(certLine(s)).append('\n');
        else sb.append("signature: not readable on this Android version for this APK\n");
        if (inst != null) {
            sb.append("INSTALLED: version ").append(inst.versionName).append(" (code ").append(inst.versionCode).append(")\n");
            if (pi.versionCode < inst.versionCode) problems.add("older than the installed version (downgrade: INSTALL_FAILED_VERSION_DOWNGRADE; needs uninstall or root with -d)");
            Set<String> a = sigSet(fs), b = sigSet(sigs(inst));
            if (!a.isEmpty() && !b.isEmpty() && !a.equals(b))
                problems.add("SIGNED BY A DIFFERENT KEY than the installed app (INSTALL_FAILED_UPDATE_INCOMPATIBLE): cannot update over it; uninstalling first deletes its data. A different key can also mean a modified or fake copy");
            Set<String> newPerms = new HashSet<String>();
            if (pi.requestedPermissions != null) newPerms.addAll(Arrays.asList(pi.requestedPermissions));
            if (inst.requestedPermissions != null) newPerms.removeAll(Arrays.asList(inst.requestedPermissions));
            if (!newPerms.isEmpty()) sb.append("permissions NEW compared to the installed version: ").append(newPerms).append('\n');
        }
        sb.append(problems.isEmpty() ? "VERDICT: compatible with this phone" + (split ? " (but only as part of a bundle)" : "") + ".\n"
            : "VERDICT: WILL NOT INSTALL / PROBLEM:\n - " + android.text.TextUtils.join("\n - ", problems) + "\n");

        // red flags
        List<String> flagsFound = new ArrayList<String>();
        Set<String> req = new HashSet<String>();
        if (pi.requestedPermissions != null) req.addAll(Arrays.asList(pi.requestedPermissions));
        StringBuilder risky = new StringBuilder();
        for (String[] r : RISKY) if (req.contains(r[0])) risky.append(r[1]).append(", ");
        if (pi.services != null) for (ServiceInfo s : pi.services) {
            if ("android.permission.BIND_ACCESSIBILITY_SERVICE".equals(s.permission)) flagsFound.add("contains an ACCESSIBILITY service (can read and control the screen)");
            if ("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE".equals(s.permission)) flagsFound.add("can read all notifications");
        }
        if (pi.receivers != null) for (ActivityInfo r : pi.receivers)
            if ("android.permission.BIND_DEVICE_ADMIN".equals(r.permission)) flagsFound.add("contains a DEVICE ADMIN receiver (can lock/wipe, hard to uninstall)");
        String real = KNOWN.get(label.toLowerCase(Locale.US).trim());
        if (real != null && !real.equals(pi.packageName)) flagsFound.add("IMPERSONATION: named '" + label + "' but the real app is " + real);
        if (inst == null) {
            for (PackageInfo other : pm.getInstalledPackages(0)) {
                if (other.packageName.equals(pi.packageName)) continue;
                if (String.valueOf(other.applicationInfo.loadLabel(pm)).equalsIgnoreCase(label)) { flagsFound.add("an installed app with the same name has a different package (" + other.packageName + ")"); break; }
            }
        }
        sb.append("sensitive permissions requested: ").append(risky.length() == 0 ? "none" : risky.substring(0, risky.length() - 2)).append('\n');
        sb.append(flagsFound.isEmpty() ? "red flags: none found\n" : "RED FLAGS:\n - " + android.text.TextUtils.join("\n - ", flagsFound) + "\n");
        int n = pi.requestedPermissions == null ? 0 : pi.requestedPermissions.length;
        sb.append("components: ").append(pi.activities == null ? 0 : pi.activities.length).append(" activities, ")
            .append(pi.services == null ? 0 : pi.services.length).append(" services, ").append(pi.receivers == null ? 0 : pi.receivers.length)
            .append(" receivers, ").append(n).append(" permissions\n");
        if (work != f) work.delete();
        return sb.toString();
    }

    /** .apks / .xapk / .apkm: a zip with several APKs (and maybe OBB files). */
    private static String bundleInfo(Context c, File f) {
        StringBuilder sb = new StringBuilder("BUNDLE " + f.getName() + ":\n");
        List<String> apks = new ArrayList<String>(), obb = new ArrayList<String>();
        try {
            ZipFile z = new ZipFile(f);
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (en.getName().endsWith(".apk")) apks.add(en.getName() + " (" + (en.getSize() >> 10) + " KB)");
                else if (en.getName().endsWith(".obb")) obb.add(en.getName());
                if (en.getName().equals("manifest.json") || en.getName().equals("info.json")) {
                    InputStream in = z.getInputStream(en);
                    byte[] b = new byte[(int) Math.min(4000, Math.max(0, en.getSize()))];
                    int r = in.read(b);
                    in.close();
                    if (r > 0) sb.append("bundle manifest: ").append(new String(b, 0, r, "UTF-8").replaceAll("\\s+", " ")).append('\n');
                }
            }
            z.close();
        } catch (Exception e) {
            return sb + "DAMAGED bundle: " + e.getMessage();
        }
        sb.append("APK parts: ").append(apks).append('\n');
        if (!obb.isEmpty()) sb.append("OBB data files: ").append(obb).append('\n');
        List<String> dev = deviceAbis();
        sb.append("phone CPU ABIs: ").append(dev).append(", SDK ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("Install it with apk_install (root installs all parts together; without root the system installer cannot install bundles).");
        return sb.toString();
    }

    // ---------------------------------------------------------------- install

    static String explain(String out) {
        String[][] m = {
            {"INSTALL_FAILED_UPDATE_INCOMPATIBLE", "the APK is signed with a different key than the installed app; uninstall it first (deletes its data; back it up)"},
            {"INSTALL_FAILED_OLDER_SDK", "the app needs a newer Android version"},
            {"INSTALL_FAILED_NO_MATCHING_ABIS", "the app is built for a different CPU type"},
            {"INSTALL_FAILED_VERSION_DOWNGRADE", "an older version than the installed one; needs -d (root) or uninstall"},
            {"INSTALL_FAILED_INSUFFICIENT_STORAGE", "not enough storage"},
            {"INSTALL_FAILED_MISSING_SPLIT", "a part of a split app is missing; install the whole bundle"},
            {"INSTALL_PARSE_FAILED_NO_CERTIFICATES", "the APK is not signed or the signature is broken (modified or damaged file)"},
            {"INSTALL_PARSE_FAILED_NOT_APK", "not an APK"},
            {"INSTALL_FAILED_INVALID_APK", "damaged or incomplete APK"},
            {"INSTALL_FAILED_TEST_ONLY", "test build; needs -t"},
            {"INSTALL_FAILED_DEPRECATED_SDK_VERSION", "the app targets a very old Android; Android 14 blocks it (root can bypass)"},
            {"INSTALL_FAILED_DUPLICATE_PERMISSION", "another installed app defines the same permission (often an old modified copy)"},
            {"INSTALL_FAILED_CONFLICTING_PROVIDER", "another installed app uses the same provider name"},
            {"INSTALL_FAILED_USER_RESTRICTED", "installing apps is blocked on this phone (filter/parental control/device policy)"},
            {"INSTALL_FAILED_VERIFICATION_FAILURE", "blocked by verification (Play Protect or a filter)"},
            {"INSTALL_FAILED_ALREADY_EXISTS", "already installed; use -r to replace"},
            {"INSTALL_FAILED_INSUFFICIENT_SIGNATURE", "signature problem"}};
        for (String[] x : m) if (out.contains(x[0])) return x[0] + ": " + x[1];
        return null;
    }

    /**
     * Runs a streaming install. Returns the final report, or null when streaming itself is not supported on this
     * phone (then the caller falls back to copying into /data/local/tmp).
     */
    private static String streamRun(String script, String opts) {
        String out = Shell.run(script, true, 600).out.trim();
        if (out.contains("Success")) return "OK installed (streamed, no copy)." + (out.contains("OBB_COPIED") ? " OBB data copied." : "");
        if (opts.contains("--bypass") && (out.contains("Unknown option") || out.contains("unrecognized option"))) {
            out = Shell.run(script.replace(" --bypass-low-target-sdk-block", ""), true, 600).out.trim();
            if (out.contains("Success")) return "OK installed (streamed, no copy).";
        }
        String ex = explain(out);
        if (ex != null) return "FAILED: " + ex + "\nraw: " + (out.length() > 800 ? out.substring(0, 800) : out);
        return null;   // NO_SESSION / WRITE_FAILED / -S not supported / unreadable stdin: try the copy method
    }

    /** Installs .apk / .apks / .xapk. With root: pm install(-multiple); without root: opens the system installer. */
    static String install(Context c, String path, boolean rootOk, boolean allowDowngrade) {
        File f = new File(path);
        String low = path.toLowerCase(Locale.US);
        boolean bundle = low.endsWith(".apks") || low.endsWith(".xapk") || low.endsWith(".apkm");
        if (!rootOk) {
            if (bundle) return "FAILED: without root the system installer cannot install a bundle (.apks/.xapk). Suggest an installer app such as SAI, or root.";
            try {
                File copy = new File(c.getCacheDir(), "install.apk");
                FileInputStream in = new FileInputStream(f);
                java.io.FileOutputStream o = new java.io.FileOutputStream(copy);
                byte[] b = new byte[65536];
                int n;
                while ((n = in.read(b)) > 0) o.write(b, 0, n);
                in.close();
                o.close();
                Uri u = Build.VERSION.SDK_INT >= 24 ? Uri.parse("content://" + c.getPackageName() + ".files/install.apk") : Uri.fromFile(copy);
                if (Build.VERSION.SDK_INT < 24) copy.setReadable(true, false);
                Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                c.startActivity(i);
                return "OPENED the system installer: the user must tap Install (and maybe allow 'install unknown apps' for Phone Doctor). Ask the user afterwards whether it succeeded, then verify with inspect_app.";
            } catch (Exception e) {
                return "FAILED to open the installer: " + e.getMessage();
            }
        }
        String t = "/data/local/tmp/pd_install";
        StringBuilder sh = new StringBuilder("rm -rf " + t + "; mkdir -p " + t + "; ");
        String opts = "-r" + (allowDowngrade ? " -d" : "") + (Build.VERSION.SDK_INT >= 34 ? " --bypass-low-target-sdk-block" : "");
        if (bundle) {
            // unzip in Java (toybox/busybox unzip is not on every phone), then hand the parts to pm
            File ex = new File(c.getCacheDir(), "bundle");
            Shell.run("rm -rf " + q(ex.getPath()), false, 60);
            ex.mkdirs();
            int parts = 0;
            boolean obb = false;
            try {
                ZipFile z = new ZipFile(f);
                for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                    ZipEntry en = e.nextElement();
                    String n = en.getName();
                    if (en.isDirectory() || n.contains("..")) continue;
                    boolean isApk = n.endsWith(".apk"), isObb = n.endsWith(".obb");
                    if (!isApk && !isObb) continue;
                    File out = isApk ? new File(ex, (parts++) + "_" + new File(n).getName()) : new File(ex, "obb/" + n.replaceFirst("^.*Android/obb/", ""));
                    out.getParentFile().mkdirs();
                    InputStream in = z.getInputStream(en);
                    java.io.FileOutputStream o = new java.io.FileOutputStream(out);
                    byte[] b = new byte[65536];
                    int r;
                    while ((r = in.read(b)) > 0) o.write(b, 0, r);
                    in.close();
                    o.close();
                    if (isObb) obb = true;
                }
                z.close();
            } catch (Exception e) {
                return "FAILED: cannot open the bundle: " + e.getMessage();
            }
            if (parts == 0) return "FAILED: no APK files inside the bundle";
            // 1st choice: stream every part into one install session (no copy to /data/local/tmp)
            File[] ps = ex.listFiles();
            StringBuilder st = new StringBuilder("S=$(pm install-create " + opts + " 2>&1 | sed -n 's/.*\\[\\([0-9]*\\)\\].*/\\1/p'); [ -n \"$S\" ] || { echo NO_SESSION; exit 1; }; ");
            if (ps != null) for (File pf : ps) if (pf.getName().endsWith(".apk"))
                st.append("pm install-write -S ").append(pf.length()).append(" $S ").append(q(pf.getName())).append(" - < ").append(q(pf.getPath())).append(" >/dev/null 2>&1 || { echo WRITE_FAILED; pm install-abandon $S; exit 1; }; ");
            if (obb) st.append("mkdir -p /sdcard/Android/obb && cp -r ").append(q(ex.getPath() + "/obb")).append("/* /sdcard/Android/obb/ && echo OBB_COPIED; ");
            st.append("pm install-commit $S 2>&1");
            if (Build.VERSION.SDK_INT >= 21) {
                String so = streamRun(st.toString(), opts);
                if (so != null) { Shell.run("rm -rf " + q(ex.getPath()), true, 60); return so; }
            }
            sh.append("cp ").append(q(ex.getPath())).append("/*.apk ").append(t).append("/ && chmod 644 ").append(t).append("/*.apk; ");
            if (obb) sh.append("mkdir -p /sdcard/Android/obb && cp -r ").append(q(ex.getPath() + "/obb")).append("/* /sdcard/Android/obb/ && echo OBB_COPIED; ");
            sh.append("pm install-multiple ").append(opts).append(" ").append(t).append("/*.apk 2>&1; rm -rf ").append(q(ex.getPath())).append("; ");
        } else {
            // 1st choice: stream the file straight into pm (no copy); root opens it, pm reads it from stdin
            if (Build.VERSION.SDK_INT >= 21) {
                long size = f.length();
                String sz = size > 0 ? String.valueOf(size) : "$(stat -c %s " + q(path) + " 2>/dev/null || wc -c < " + q(path) + ")";
                String so = streamRun("pm install " + opts + " -S " + sz + " < " + q(path) + " 2>&1", opts);
                if (so != null) return so;
            }
            sh.append("cp ").append(q(path)).append(" ").append(t).append("/a.apk && chmod 644 ").append(t).append("/a.apk && pm install ").append(opts).append(" ").append(t).append("/a.apk 2>&1; ");
        }
        sh.append("rm -rf ").append(t);
        String out = Shell.run(sh.toString(), true, 600).out.trim();
        if (out.contains("Success")) return "OK installed." + (out.contains("OBB_COPIED") ? " OBB data copied." : "");
        String ex = explain(out);
        if (out.contains("unrecognized option") || out.contains("Unknown option")) {
            String o2 = Shell.run(sh.toString().replace(" --bypass-low-target-sdk-block", ""), true, 600).out.trim();
            if (o2.contains("Success")) return "OK installed.";
            out = o2;
            ex = explain(out);
        }
        return "FAILED: " + (ex != null ? ex : "") + "\nraw: " + (out.length() > 800 ? out.substring(0, 800) : out);
    }
}
