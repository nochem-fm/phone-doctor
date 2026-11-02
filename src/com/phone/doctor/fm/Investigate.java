package com.phone.doctor.fm;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;

import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence collectors, one per kind of complaint. Each gathers everything relevant in one call, with
 * dates, so the agent can correlate by time instead of guessing from a single grep.
 */
final class Investigate {
    private static final SimpleDateFormat DT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    private static final SimpleDateFormat SHORT = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US);

    static String run(Context c, String topic, String pkg, String from, String to, int days, boolean root) {
        try {
            switch (topic) {
                case "timeline": return timeline(c, from, to, root);
                case "app_changes": return appChanges(c, days <= 0 ? 30 : days);
                case "background": return background(c, pkg, root);
                case "battery_drain": return batteryDrain(root);
                case "network": return network(c, root);
                case "performance": return performance(pkg, root);
                case "bluetooth_audio": return btAudio(root);
                case "privacy": return privacy(root);
                case "storage_growth": return storageGrowth(c, days <= 0 ? 7 : days, root);
                default: return "unknown topic " + topic;
            }
        } catch (Throwable t) {
            return "ERROR: " + t;
        }
    }

    private static String sh(String cmd, boolean root, int t) { return Shell.run(cmd, root, t).out; }

    // ---------------------------------------------------------------- 1. what happened between two times

    static long parseTime(String s, long def) {
        if (s == null || s.trim().isEmpty()) return def;
        s = s.trim();
        String[] fmts = {"yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm", "yyyy-MM-dd", "dd/MM/yyyy HH:mm", "dd/MM HH:mm"};
        for (String f : fmts) {
            try {
                SimpleDateFormat sdf = new SimpleDateFormat(f, Locale.US);
                sdf.setLenient(false);
                Date d = sdf.parse(s);
                if (f.equals("dd/MM HH:mm")) {   // no year: this year
                    java.util.Calendar now = java.util.Calendar.getInstance(), cal = java.util.Calendar.getInstance();
                    cal.setTime(d);
                    cal.set(java.util.Calendar.YEAR, now.get(java.util.Calendar.YEAR));
                    return cal.getTimeInMillis();
                }
                return d.getTime();
            } catch (Exception ignored) { }
        }
        Matcher m = Pattern.compile("^-?(\\d+)\\s*([hdm])$").matcher(s);   // "-6h", "2d", "30m" = that long ago
        if (m.find()) {
            long n = Long.parseLong(m.group(1));
            long unit = m.group(2).equals("d") ? 86400000L : m.group(2).equals("h") ? 3600000L : 60000L;
            return System.currentTimeMillis() - n * unit;
        }
        return def;
    }

    private static final class Ev {
        final long t;
        final String what;
        Ev(long t, String what) { this.t = t; this.what = what; }
    }

    private static String timeline(Context c, String from, String to, boolean root) {
        long now = System.currentTimeMillis();
        long t1 = parseTime(to, now), t0 = parseTime(from, t1 - 24 * 3600000L);
        if (t0 >= t1) t0 = t1 - 3600000L;
        List<Ev> ev = new ArrayList<Ev>();
        StringBuilder notes = new StringBuilder();
        PackageManager pm = c.getPackageManager();

        // apps in front, screen, unlock, shutdown/startup (usage access)
        if (Build.VERSION.SDK_INT >= 22 && MainActivityHelper.usageOk(c)) {
            try {
                android.app.usage.UsageStatsManager um = (android.app.usage.UsageStatsManager) c.getSystemService("usagestats");
                android.app.usage.UsageEvents ue = um.queryEvents(t0, t1);
                android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
                String lastFg = "";
                while (ue.hasNextEvent()) {
                    ue.getNextEvent(e);
                    int type = e.getEventType();
                    String p = e.getPackageName();
                    switch (type) {
                        case 1: if (!p.equals(lastFg)) { ev.add(new Ev(e.getTimeStamp(), "opened " + label(pm, p))); lastFg = p; } break;
                        case 15: ev.add(new Ev(e.getTimeStamp(), "screen ON")); break;
                        case 16: ev.add(new Ev(e.getTimeStamp(), "screen OFF")); lastFg = ""; break;
                        case 18: ev.add(new Ev(e.getTimeStamp(), "unlocked")); break;
                        case 26: ev.add(new Ev(e.getTimeStamp(), "DEVICE SHUTDOWN")); break;
                        case 27: ev.add(new Ev(e.getTimeStamp(), "DEVICE STARTUP")); break;
                        default:
                    }
                }
            } catch (Exception e) { notes.append("usage events: ").append(e.getMessage()).append('\n'); }
        } else notes.append("app/screen events missing: usage access is not granted\n");

        // crashes, ANRs, reboots, system crashes (system dropbox)
        String list = Shell.runFull("dumpsys dropbox 2>/dev/null", root, 40).out;
        Matcher m = Pattern.compile("(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) (\\S+)").matcher(list);
        Map<String, Boolean> tagsInWindow = new HashMap<String, Boolean>();
        int dropCount = 0;
        while (m.find()) {
            long t;
            try { t = DT.parse(m.group(1)).getTime(); } catch (Exception e) { continue; }
            if (t < t0 || t > t1) continue;
            String tag = m.group(2);
            if (tag.contains("crash") || tag.contains("anr") || tag.contains("watchdog") || tag.startsWith("SYSTEM_") || tag.contains("kernel") || tag.contains("wtf")) {
                tagsInWindow.put(tag, true);
                dropCount++;
            }
        }
        if (dropCount == 0 && list.trim().isEmpty()) notes.append("crash/boot history unreadable (needs root or the DUMP permission)\n");
        for (String tag : tagsInWindow.keySet()) {   // details: which process
            String pr = Shell.runFull("dumpsys dropbox --print " + tag + " 2>/dev/null", root, 60).out;
            String[] blocks = pr.split("(?m)^={20,}");
            for (String b : blocks) {
                Matcher h = Pattern.compile("(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}) " + Pattern.quote(tag)).matcher(b);
                if (!h.find()) continue;
                long t;
                try { t = DT.parse(h.group(1)).getTime(); } catch (Exception e) { continue; }
                if (t < t0 || t > t1) continue;
                Matcher p = Pattern.compile("(?m)^(?:Process|Subject): (.+)$").matcher(b);
                String who = p.find() ? p.group(1).trim() : "";
                Matcher ex = Pattern.compile("(?m)^(\\S*(?:Exception|Error)[^\\n]{0,120})").matcher(b);
                ev.add(new Ev(t, tag + (who.isEmpty() ? "" : " " + who) + (ex.find() ? " | " + ex.group(1) : "")));
            }
        }

        // installs and updates
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            if (pi.lastUpdateTime >= t0 && pi.lastUpdateTime <= t1)
                ev.add(new Ev(pi.lastUpdateTime, (pi.firstInstallTime == pi.lastUpdateTime ? "INSTALLED " : "UPDATED ") + label(pm, pi.packageName) + " v" + pi.versionName));
        }

        Collections.sort(ev, new Comparator<Ev>() { public int compare(Ev a, Ev b) { return Long.compare(a.t, b.t); } });
        StringBuilder sb = new StringBuilder("TIMELINE " + SHORT.format(new Date(t0)) + " -> " + SHORT.format(new Date(t1)) + " (" + ev.size() + " events)\n");
        if (notes.length() > 0) sb.append(notes);
        int start = Math.max(0, ev.size() - 250);
        if (start > 0) sb.append("...").append(start).append(" earlier events left out; narrow the window\n");
        for (int i = start; i < ev.size(); i++) sb.append(SHORT.format(new Date(ev.get(i).t))).append("  ").append(ev.get(i).what).append('\n');
        return sb.toString();
    }

    private static String label(PackageManager pm, String p) {
        try { return pm.getApplicationLabel(pm.getApplicationInfo(p, 0)) + " (" + p + ")"; } catch (Exception e) { return p; }
    }

    // ---------------------------------------------------------------- 2. installs and updates

    private static String appChanges(Context c, int days) {
        PackageManager pm = c.getPackageManager();
        long since = System.currentTimeMillis() - days * 86400000L;
        List<PackageInfo> l = new ArrayList<PackageInfo>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) if (pi.lastUpdateTime >= since) l.add(pi);
        Collections.sort(l, new Comparator<PackageInfo>() { public int compare(PackageInfo a, PackageInfo b) { return Long.compare(b.lastUpdateTime, a.lastUpdateTime); } });
        StringBuilder sb = new StringBuilder("Installed or updated in the last " + days + " days: " + l.size() + "\n");
        for (PackageInfo pi : l) {
            boolean sys = (pi.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String inst = null;
            try { inst = pm.getInstallerPackageName(pi.packageName); } catch (Exception ignored) { }
            sb.append(SHORT.format(new Date(pi.lastUpdateTime))).append("  ").append(pi.firstInstallTime == pi.lastUpdateTime ? "installed " : "updated ")
                .append(label(pm, pi.packageName)).append(" v").append(pi.versionName).append(sys ? " [system app]" : "")
                .append(inst == null ? " [installer: manual/unknown]" : inst.equals("com.android.vending") ? " [Play Store]" : " [installer: " + inst + "]").append('\n');
        }
        sb.append("System build date: ").append(SHORT.format(new Date(Build.TIME)));
        if (Build.VERSION.SDK_INT >= 23) sb.append(", security patch ").append(Build.VERSION.SECURITY_PATCH);
        return sb.toString();
    }

    // ---------------------------------------------------------------- 3. app killed in background / notifications not arriving

    private static String background(Context c, String pkg, boolean root) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_.]+")) return "background needs a package name";
        String q = pkg.replace(".", "\\.");
        StringBuilder sh = new StringBuilder();
        sh.append("echo '== APP STATE'; dumpsys package ").append(pkg).append(" 2>/dev/null | grep -E 'versionName|stopped=|enabled=|lastUpdateTime' | head -6\n");
        sh.append("echo \"standby bucket: $(am get-standby-bucket ").append(pkg).append(" 2>/dev/null)\"\n");
        sh.append("echo '== BACKGROUND RESTRICTIONS'; (cmd appops get ").append(pkg).append(" RUN_ANY_IN_BACKGROUND 2>/dev/null; cmd appops get ").append(pkg).append(" RUN_IN_BACKGROUND 2>/dev/null; appops get ").append(pkg).append(" RUN_IN_BACKGROUND 2>/dev/null) | head -4\n");
        sh.append("echo \"battery optimization exempt: $(dumpsys deviceidle whitelist 2>/dev/null | grep -c ").append(pkg).append(")\"\n");
        sh.append("echo '== WHY IT WAS CLOSED (exit info, Android 11+)'; dumpsys activity exit-info ").append(pkg).append(" 2>/dev/null | grep -E 'timestamp=|reason=|description=|importance=' | head -40\n");
        sh.append("echo '== KILLED BY THE SYSTEM (events log)'; logcat -b events -d 2>/dev/null | grep -E 'am_kill|am_proc_died|am_low_memory' | grep -E '").append(q).append("|am_low_memory' | tail -20\n");
        sh.append("echo '== NOTIFICATION SETTINGS'; dumpsys notification 2>/dev/null | grep -E -i 'mZenMode|zen_mode|ZenModeConfig: allow' | head -3; dumpsys notification 2>/dev/null | grep -E \"").append(q).append("\" | grep -i -E 'importance|banned|blocked|channel|AppSettings' | head -25\n");
        sh.append("echo '== GOOGLE PUSH (FCM) CONNECTION'; dumpsys activity service com.google.android.gms/.gcm.GcmService 2>/dev/null | grep -i -E 'connected|lastConnect|heartbeat|endpoint' | head -8\n");
        return Shell.run(sh.toString(), root, 60).out + "\n" + NotifyService.report(c, pkg, 72);
    }

    // ---------------------------------------------------------------- 4. battery drain

    private static String batteryDrain(boolean root) {
        String s = "echo '== SINCE LAST CHARGE'; dumpsys batterystats --charged 2>/dev/null | grep -E -i 'Time on battery|Screen off discharge|Screen on discharge|Discharge|Capacity:|Computed drain|actual drain' | head -12\n"
            + "echo '== POWER USE PER APP'; dumpsys batterystats --charged 2>/dev/null | grep -A25 -E 'Estimated power use|Estimated power use \\(mAh\\)' | head -30\n"
            + "echo '== WAKELOCKS (what kept the phone awake)'; dumpsys batterystats --charged 2>/dev/null | grep -E 'Wake lock|Kernel Wake lock|Wakeup reason' | sort -t: -k2 | head -25\n"
            + "echo '== ALARMS THAT WAKE THE PHONE'; dumpsys alarm 2>/dev/null | grep -A14 -E 'Top Alarms' | head -16\n"
            + "echo '== DOZE'; dumpsys deviceidle 2>/dev/null | grep -E 'mState|mLightState|mDeepEnabled|mLightEnabled' | head -6\n"
            + "echo '== BATTERY NOW'; dumpsys battery 2>/dev/null | grep -E 'level|temperature|health|status|plugged' | head -8\n";
        return Shell.run(s, root, 60).out;
    }

    // ---------------------------------------------------------------- 5. network

    private static String probe(String url) {
        try {
            HttpURLConnection c = Net.open(url);
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(false);
            int code = c.getResponseCode();
            String loc = c.getHeaderField("Location");
            String body = "";
            try {
                java.io.InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
                if (in != null) {
                    byte[] b = new byte[600];
                    int n = in.read(b);
                    in.close();
                    if (n > 0) body = new String(b, 0, n, "UTF-8").replaceAll("\\s+", " ");
                }
            } catch (Exception ignored) { }
            c.disconnect();
            String low = body.toLowerCase(Locale.ROOT);
            String filt = low.contains("netfree") || low.contains("נטפרי") ? " [NETFREE BLOCK PAGE]" : low.contains("netspark") || low.contains("רימון") || low.contains("etrog") ? " [RIMON/ETROG BLOCK PAGE]" : "";
            return url + " -> HTTP " + code + (loc != null ? " redirect to " + loc : "") + filt + (body.isEmpty() ? "" : " | " + (body.length() > 160 ? body.substring(0, 160) : body));
        } catch (javax.net.ssl.SSLException e) {
            return url + " -> TLS ERROR (" + e.getMessage() + "): a filter or proxy with an untrusted certificate";
        } catch (java.net.UnknownHostException e) {
            return url + " -> DNS FAILED (name not resolved)";
        } catch (Exception e) {
            return url + " -> FAILED: " + e;
        }
    }

    private static String network(Context c, boolean root) {
        String s = "echo '== CONNECTION NOW'; dumpsys connectivity 2>/dev/null | grep -E 'Active default network|NetworkAgentInfo.*(WIFI|CELLULAR|VPN)' | head -4\n"
            + "echo \"private DNS: $(settings get global private_dns_mode 2>/dev/null) $(settings get global private_dns_specifier 2>/dev/null); http proxy: $(settings get global http_proxy 2>/dev/null)\"\n"
            + "echo '== VPN'; dumpsys connectivity 2>/dev/null | grep -i -E 'VPN' | head -4\n"
            + "echo '== WI-FI DISCONNECTS / FAILURES (recent)'; dumpsys wifi 2>/dev/null | grep -i -E 'disconnect|reason=|AUTH_FAILURE|ASSOC_REJECT|DHCP|CONNECTION_FAILURE|Lost' | tail -25\n"
            + "echo '== WI-FI SIGNAL'; dumpsys wifi 2>/dev/null | grep -E 'mWifiInfo|RSSI:|rssi=' | head -3\n"
            + "echo '== MOBILE'; dumpsys telephony.registry 2>/dev/null | grep -E 'mServiceState|mSignalStrength|mDataConnectionState' | head -4\n"
            + "echo '== VALIDATION'; dumpsys connectivity 2>/dev/null | grep -i -E 'validated|captive|partial' | head -6\n";
        StringBuilder sb = new StringBuilder(Shell.run(s, root, 60).out);
        sb.append("\n== LIVE TESTS (from this app)\n");
        sb.append(probe("https://www.google.com/generate_204")).append('\n');
        sb.append(probe("https://generativelanguage.googleapis.com/")).append('\n');
        sb.append(probe("https://www.wikipedia.org/")).append('\n');
        sb.append("(204 from generate_204 = internet works; a block page or TLS error = the filter/proxy intercepts)");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 6. slowness and heat

    private static String performance(String pkg, boolean root) {
        String s = "echo '== CPU LOAD'; cat /proc/loadavg; (top -b -n 1 -m 10 2>/dev/null || top -n 1 2>/dev/null) | head -16\n"
            + "echo '== CPU SPEED NOW / MAX (throttling)'; for c in /sys/devices/system/cpu/cpu[0-9]*; do echo \"$(basename $c) $(cat $c/cpufreq/scaling_cur_freq 2>/dev/null)/$(cat $c/cpufreq/cpuinfo_max_freq 2>/dev/null)\"; done 2>/dev/null | head -10\n"
            + "echo '== THERMAL'; dumpsys thermalservice 2>/dev/null | grep -E -i 'Thermal Status|Temperature\\{|mValue|throttl' | head -20\n"
            + "echo '== MEMORY PRESSURE'; cat /proc/pressure/memory 2>/dev/null; cat /proc/pressure/cpu 2>/dev/null; grep -E 'MemAvailable|SwapFree|SwapTotal' /proc/meminfo\n"
            + "echo '== APPS CLOSED FOR MEMORY (low-memory kills per day)'; logcat -b events -d 2>/dev/null | grep -E 'am_kill|lowmemorykiller|lmk' | awk '{print $1}' | sort | uniq -c | tail -7\n"
            + "echo '== EXIT REASONS (Android 11+)'; dumpsys activity exit-info 2>/dev/null | grep -o -E 'reason=[A-Z_]+' | sort | uniq -c | sort -rn | head -8\n"
            + (pkg != null && pkg.matches("[A-Za-z0-9_.]+") ? "echo '== FRAME DROPS IN " + pkg + "'; dumpsys gfxinfo " + pkg + " 2>/dev/null | grep -E 'Total frames|Janky frames|percentile|Slow' | head -10\n" : "");
        return Shell.run(s, root, 60).out;
    }

    // ---------------------------------------------------------------- 7. Bluetooth and audio

    private static String btAudio(boolean root) {
        String s = "echo '== BLUETOOTH'; dumpsys bluetooth_manager 2>/dev/null | grep -E -i 'enabled|state:|Bonded devices|name:|address:|Connected|A2DP|HFP|codec|disconnect' | head -50\n"
            + "echo '== AUDIO ROUTING'; dumpsys audio 2>/dev/null | grep -E -i 'mode|Devices|route|SCO|A2DP|isBluetooth|stream_music|STREAM_VOICE' | head -30\n"
            + "echo '== RECENT AUDIO EVENTS'; dumpsys audio 2>/dev/null | grep -A20 -i 'Audio event log' | head -24\n";
        return Shell.run(s, root, 60).out;
    }

    // ---------------------------------------------------------------- 8. who used the camera / microphone / location

    private static long ago(String d) {
        long ms = 0;
        Matcher m = Pattern.compile("(\\d+)(ms|d|h|m|s)").matcher(d);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            String u = m.group(2);
            ms += u.equals("d") ? n * 86400000L : u.equals("h") ? n * 3600000L : u.equals("m") ? n * 60000L : u.equals("s") ? n * 1000L : n;
        }
        return ms;
    }

    private static String privacy(boolean root) {
        String out = Shell.runFull("dumpsys appops 2>/dev/null", root, 60).out;
        if (out.trim().isEmpty()) return "appops history unreadable (needs root or the DUMP permission)";
        String[] ops = {"CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CONTACTS", "READ_SMS", "READ_CALL_LOG"};
        List<String[]> hits = new ArrayList<String[]>();
        String pkg = null, op = null;
        for (String line : out.split("\n")) {
            String t = line.trim();
            Matcher pm = Pattern.compile("^Package (\\S+?):?$").matcher(t);
            if (pm.find()) { pkg = pm.group(1); op = null; continue; }
            for (String o : ops) if (t.startsWith(o + " (") || t.startsWith(o + ":")) op = o;
            if (pkg == null || op == null) continue;
            if (t.contains("Reject")) continue;
            Matcher a = Pattern.compile("time=\\+?([0-9dhms]+) ago").matcher(t);        // older format
            Matcher b = Pattern.compile("\\(-([0-9dhms]+)\\)").matcher(t);               // Android 11+ "Access: ... (-1d2h3m)"
            String d = a.find() ? a.group(1) : (t.startsWith("Access") && b.find()) ? b.group(1) : null;
            if (d == null) continue;
            hits.add(new String[]{String.valueOf(ago(d)), pkg, op, d});
        }
        Collections.sort(hits, new Comparator<String[]>() { public int compare(String[] x, String[] y) { return Long.compare(Long.parseLong(x[0]), Long.parseLong(y[0])); } });
        StringBuilder sb = new StringBuilder("Most recent use of camera / microphone / location / contacts / SMS / call log (newest first):\n");
        Map<String, Boolean> seen = new HashMap<String, Boolean>();
        int n = 0;
        for (String[] h : hits) {
            String k = h[1] + "|" + h[2];
            if (seen.containsKey(k)) continue;
            seen.put(k, true);
            sb.append(h[3]).append(" ago  ").append(h[2]).append("  ").append(h[1]).append('\n');
            if (++n >= 60) break;
        }
        if (n == 0) sb.append("(no recorded use found)");
        return sb.toString();
    }

    // ---------------------------------------------------------------- 9. what filled the storage lately

    private static void recent(File d, long since, List<File> out, long end, int depth) {
        if (depth > 12 || System.currentTimeMillis() > end) return;
        File[] l = d.listFiles();
        if (l == null) return;
        for (File x : l) {
            if (x.isDirectory()) recent(x, since, out, end, depth + 1);
            else if (x.lastModified() >= since && x.length() > 1024 * 1024) out.add(x);
        }
    }

    private static String mb(long b) { return String.format(Locale.US, "%.1f MB", b / 1048576.0); }

    private static String storageGrowth(Context c, int days, boolean root) {
        StringBuilder sb = new StringBuilder();
        // apps: sizes now vs the last snapshot this tool saved
        String ds = Shell.runFull("dumpsys diskstats 2>/dev/null", root, 60).out;
        Matcher pn = Pattern.compile("Package Names: \\[(.*?)\\]").matcher(ds);
        Matcher as = Pattern.compile("App Sizes: \\[(.*?)\\]").matcher(ds);
        Matcher dd = Pattern.compile("App Data Sizes: \\[(.*?)\\]").matcher(ds);
        Matcher cs = Pattern.compile("Cache Sizes: \\[(.*?)\\]").matcher(ds);
        SharedPreferences p = Core.prefs();
        if (pn.find() && as.find() && dd.find() && cs.find()) {
            String[] names = pn.group(1).replace("\"", "").split(",\\s*");
            String[] a = as.group(1).split(",\\s*"), dt = dd.group(1).split(",\\s*"), ca = cs.group(1).split(",\\s*");
            JSONObject prev;
            try { prev = new JSONObject(p.getString("sizes_snap", "{}")); } catch (Exception e) { prev = new JSONObject(); }
            long prevAt = p.getLong("sizes_snap_at", 0);
            JSONObject now = new JSONObject();
            List<Object[]> rows = new ArrayList<Object[]>();
            for (int i = 0; i < names.length && i < a.length && i < dt.length && i < ca.length; i++) {
                try {
                    long total = Long.parseLong(a[i].trim()) + Long.parseLong(dt[i].trim()) + Long.parseLong(ca[i].trim());
                    now.put(names[i], total);
                    long before = prev.optLong(names[i], -1);
                    rows.add(new Object[]{names[i], total, before < 0 ? 0L : total - before, Long.parseLong(ca[i].trim())});
                } catch (Exception ignored) { }
            }
            Collections.sort(rows, new Comparator<Object[]>() { public int compare(Object[] x, Object[] y) { return Long.compare((Long) y[1], (Long) x[1]); } });
            sb.append("== BIGGEST APPS (app+data+cache)").append(prevAt > 0 ? ", change since " + SHORT.format(new Date(prevAt)) : ", first snapshot saved now").append(":\n");
            for (int i = 0; i < Math.min(15, rows.size()); i++) {
                Object[] r = rows.get(i);
                long g = (Long) r[2];
                sb.append("  ").append(mb((Long) r[1])).append("  ").append(r[0]).append("  cache ").append(mb((Long) r[3]))
                    .append(prevAt > 0 && g != 0 ? "  (" + (g > 0 ? "+" : "") + mb(g) + ")" : "").append('\n');
            }
            if (prevAt > 0) {
                Collections.sort(rows, new Comparator<Object[]>() { public int compare(Object[] x, Object[] y) { return Long.compare((Long) y[2], (Long) x[2]); } });
                sb.append("== GREW THE MOST since the last snapshot:\n");
                for (int i = 0; i < Math.min(8, rows.size()); i++) if ((Long) rows.get(i)[2] > 5 * 1048576L) sb.append("  +").append(mb((Long) rows.get(i)[2])).append("  ").append(rows.get(i)[0]).append('\n');
            }
            p.edit().putString("sizes_snap", now.toString()).putLong("sizes_snap_at", System.currentTimeMillis()).apply();
        } else sb.append("app sizes not available (needs root or DUMP on this Android)\n");
        // files created/changed lately
        long since = System.currentTimeMillis() - days * 86400000L;
        List<File> fs = new ArrayList<File>();
        recent(Environment.getExternalStorageDirectory(), since, fs, System.currentTimeMillis() + 20000, 0);
        Collections.sort(fs, new Comparator<File>() { public int compare(File x, File y) { return Long.compare(y.length(), x.length()); } });
        Map<String, Long> perDir = new HashMap<String, Long>();
        long sum = 0;
        for (File f : fs) {
            sum += f.length();
            String top = f.getPath().replace(Environment.getExternalStorageDirectory().getPath() + "/", "");
            int i = top.indexOf('/');
            top = i > 0 ? top.substring(0, i) : "(root)";
            perDir.put(top, (perDir.containsKey(top) ? perDir.get(top) : 0L) + f.length());
        }
        sb.append("== FILES OVER 1 MB CREATED/CHANGED IN THE LAST ").append(days).append(" DAYS: ").append(fs.size()).append(", ").append(mb(sum)).append('\n');
        List<Map.Entry<String, Long>> dirs = new ArrayList<Map.Entry<String, Long>>(perDir.entrySet());
        Collections.sort(dirs, new Comparator<Map.Entry<String, Long>>() { public int compare(Map.Entry<String, Long> x, Map.Entry<String, Long> y) { return Long.compare(y.getValue(), x.getValue()); } });
        for (int i = 0; i < Math.min(8, dirs.size()); i++) sb.append("  ").append(mb(dirs.get(i).getValue())).append("  ").append(dirs.get(i).getKey()).append("/\n");
        for (int i = 0; i < Math.min(15, fs.size()); i++)
            sb.append("  ").append(mb(fs.get(i).length())).append("  ").append(SHORT.format(new Date(fs.get(i).lastModified()))).append("  ").append(fs.get(i).getPath()).append('\n');
        return sb.toString();
    }
}
