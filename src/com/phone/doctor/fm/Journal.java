package com.phone.doctor.fm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Undo journal. Every change the agent makes is analysed BEFORE it runs:
 *   level 0 - harmless / transient, nothing to record
 *   level 1 - reversible: the previous state is read first and an undo command is stored
 *   level 2 - destructive but backup-able: a copy is saved first, undo restores it
 *   level 3 - cannot be undone or backed up: the user is warned and must confirm explicitly
 */
final class Journal {
    static final class Op {
        final String kind;
        final List<String> a = new ArrayList<String>();

        Op(String kind, String... args) {
            this.kind = kind;
            for (String s : args) a.add(s);
        }
    }

    static final class Plan {
        final List<String> undo = new ArrayList<String>();   // already in execution order
        final List<String> notes = new ArrayList<String>();
        final List<String> failed = new ArrayList<String>();
        String dir = "";
        int level;
    }

    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    private static final Pattern REDIR = Pattern.compile("(?:^|[^>0-9&])>>?\\s*(/[^\\s;|&<>]+)");
    private static final long MAX_KB = 300000;
    private static final int MAX_ENTRIES = 150;

    // ================================================================ analysis

    private static List<String> tokens(String seg) {
        List<String> t = new ArrayList<String>();
        for (String x : seg.trim().split("\\s+")) {
            x = x.replaceAll("^['\"]+|['\"]+$", "");
            if (!x.isEmpty()) t.add(x);
        }
        return t;
    }

    private static String join(List<String> l, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) { if (i > 0) sb.append(sep); sb.append(l.get(i)); }
        return sb.toString();
    }

    private static int pkgIdx(List<String> t, int from) {
        for (int i = from; i < t.size(); i++)
            if (!t.get(i).startsWith("-") && PKG.matcher(t.get(i)).matches()) return i;
        return -1;
    }

    private static List<String> nonFlags(List<String> t, int from) {
        List<String> r = new ArrayList<String>();
        for (int i = from; i < t.size(); i++) if (!t.get(i).startsWith("-")) r.add(t.get(i));
        return r;
    }

    private static boolean hasFlag(List<String> t, String f) {
        for (String x : t) if (x.equals(f)) return true;
        return false;
    }

    static List<Op> parse(String cmd) {
        List<Op> ops = new ArrayList<Op>();
        Matcher m = REDIR.matcher(cmd);
        while (m.find()) {
            String path = m.group(1);
            if (path.startsWith("/dev/")) continue;
            ops.add(new Op(path.startsWith("/sys/") || path.startsWith("/proc/") ? "SYSFS" : "FILEEDIT", path));
        }
        for (String raw : cmd.split("\\n|;|&&|\\|\\||\\|")) {
            String seg = raw.replaceAll("\\d*>>?\\s*\\S+", " ").trim();
            if (seg.isEmpty() || Safety.readOnly(seg)) continue;
            segOps(tokens(seg), ops);
        }
        return ops;
    }

    private static void segOps(List<String> t, List<Op> out) {
        if (t.isEmpty()) return;
        String c = t.get(0);
        if (c.equals("cmd") && t.size() > 1) {
            String s = t.get(1);
            if (s.equals("appops")) { t = new ArrayList<String>(t.subList(1, t.size())); c = "appops"; }
            else if (s.equals("package")) { t = new ArrayList<String>(t.subList(1, t.size())); t.set(0, "pm"); c = "pm"; }
            else if (s.equals("statusbar")) { out.add(new Op("TRANSIENT")); return; }
        }
        String s1 = t.size() > 1 ? t.get(1) : "";
        String whole = join(t, " ");
        switch (c) {
            case "pm": {
                int pi = pkgIdx(t, 2);
                String pkg = pi >= 0 ? t.get(pi) : "";
                if ((s1.equals("grant") || s1.equals("revoke")) && pi >= 0 && pi + 1 < t.size())
                    out.add(new Op(s1.equals("grant") ? "GRANT" : "REVOKE", pkg, t.get(pi + 1)));
                else if (s1.equals("uninstall") && !pkg.isEmpty()) out.add(new Op("UNINSTALL", pkg, hasFlag(t, "-k") ? "k" : ""));
                else if (s1.equals("clear") && !pkg.isEmpty()) out.add(new Op("CLEAR", pkg));
                else if (s1.equals("enable") && !pkg.isEmpty()) out.add(new Op("ENABLE", pkg));
                else if ((s1.equals("disable") || s1.equals("disable-user") || s1.equals("disable-until-used")) && !pkg.isEmpty()) out.add(new Op("DISABLE", pkg));
                else if (s1.equals("hide") && !pkg.isEmpty()) out.add(new Op("HIDE", pkg));
                else if (s1.equals("unhide") && !pkg.isEmpty()) out.add(new Op("UNHIDE", pkg));
                else if (s1.equals("suspend") && !pkg.isEmpty()) out.add(new Op("SUSPEND", pkg));
                else if (s1.equals("unsuspend") && !pkg.isEmpty()) out.add(new Op("UNSUSPEND", pkg));
                else if (s1.equals("install") || s1.startsWith("install-") || s1.equals("compile") || s1.equals("trim-caches")) out.add(new Op("TRANSIENT"));
                else out.add(new Op("UNKNOWN", whole));
                return;
            }
            case "appops": {
                int pi = pkgIdx(t, 2);
                if (s1.equals("set") && pi >= 0 && pi + 2 < t.size()) out.add(new Op("APPOPS", t.get(pi), t.get(pi + 1), t.get(pi + 2)));
                else out.add(new Op("UNKNOWN", whole));
                return;
            }
            case "dumpsys":
                if (s1.equals("deviceidle") && t.size() > 3 && t.get(2).equals("whitelist") && t.get(3).length() > 1) {
                    String x = t.get(3);
                    if (x.startsWith("+")) { out.add(new Op("WL_ADD", x.substring(1))); return; }
                    if (x.startsWith("-")) { out.add(new Op("WL_REM", x.substring(1))); return; }
                }
                if (s1.equals("battery")) { out.add(new Op("BATTERY")); return; }
                out.add(new Op("UNKNOWN", whole));
                return;
            case "am":
                if (s1.equals("set-standby-bucket") && t.size() > 3) { out.add(new Op("BUCKET", t.get(2), t.get(3))); return; }
                if (s1.equals("force-stop") || s1.equals("kill") || s1.equals("kill-all") || s1.startsWith("start") || s1.equals("broadcast")
                    || s1.equals("instrument") || s1.equals("stopservice")) { out.add(new Op("TRANSIENT")); return; }
                out.add(new Op("UNKNOWN", whole));
                return;
            case "settings":
                if (s1.equals("put") && t.size() > 4) out.add(new Op("SPUT", t.get(2), t.get(3)));
                else if (s1.equals("delete") && t.size() > 3) out.add(new Op("SDEL", t.get(2), t.get(3)));
                else out.add(new Op("UNKNOWN", whole));
                return;
            case "setprop":
            case "resetprop":
                if (t.size() > 1) out.add(new Op("SETPROP", c, nonFlags(t, 1).isEmpty() ? s1 : nonFlags(t, 1).get(0)));
                else out.add(new Op("UNKNOWN", whole));
                return;
            case "setenforce":
                out.add(new Op("SETENFORCE"));
                return;
            case "svc":
                if ((s1.equals("wifi") || s1.equals("data") || s1.equals("bluetooth") || s1.equals("nfc")) && t.size() > 2
                    && (t.get(2).equals("enable") || t.get(2).equals("disable"))) out.add(new Op("SVC", s1, t.get(2)));
                else out.add(new Op("IRREV", whole));
                return;
            case "mount": {
                String mp = "", mode = "";
                for (String x : t) {
                    if (x.contains("remount")) mode = x.contains("rw") ? "rw" : "ro";
                    if (x.startsWith("/")) mp = x;
                }
                if (!mode.isEmpty() && !mp.isEmpty()) out.add(new Op("REMOUNT", mp, mode)); else out.add(new Op("UNKNOWN", whole));
                return;
            }
            case "wm":
                if (s1.equals("size") || s1.equals("density")) out.add(new Op("WM", s1)); else out.add(new Op("UNKNOWN", whole));
                return;
            case "iptables":
            case "ip6tables":
                out.add(new Op("IPT", c));
                return;
            case "rm": {
                List<String> p = nonFlags(t, 1);
                if (p.isEmpty()) { out.add(new Op("UNKNOWN", whole)); return; }
                Op o = new Op("RM");
                o.a.addAll(p);
                out.add(o);
                return;
            }
            case "mv":
            case "cp": {
                List<String> p = nonFlags(t, 1);
                if (p.size() < 2) { out.add(new Op("UNKNOWN", whole)); return; }
                Op o = new Op(c.equals("mv") ? "MV" : "CP");
                o.a.addAll(p);
                out.add(o);
                return;
            }
            case "chmod":
            case "chown":
            case "chgrp": {
                List<String> p = nonFlags(t, 2);
                if (p.isEmpty() || hasFlag(t, "-R") || hasFlag(t, "-r")) { out.add(new Op("UNKNOWN", whole)); return; }
                Op o = new Op("PERMS");
                o.a.addAll(p);
                out.add(o);
                return;
            }
            case "sed": {
                boolean inplace = false;
                for (String x : t) if (x.startsWith("-i")) inplace = true;
                List<String> p = nonFlags(t, 1);
                if (inplace && p.size() > 1) out.add(new Op("FILEEDIT", p.get(p.size() - 1))); else out.add(new Op("TRANSIENT"));
                return;
            }
            case "tee":
                for (String x : nonFlags(t, 1)) out.add(new Op("FILEEDIT", x));
                return;
            case "monkey": case "input": case "killall": case "kill": case "pkill": case "sleep": case "screencap":
            case "ping": case "logcat": case "touch": case "mkdir": case "restorecon":
                out.add(new Op("TRANSIENT"));
                return;
            case "reboot": case "poweroff": case "shutdown": case "halt": case "dd": case "su": case "magisk": case "fastboot":
                out.add(new Op("IRREV", whole));
                return;
            default:
                out.add(new Op("UNKNOWN", whole));
        }
    }

    private static int levelOf(String kind) {
        switch (kind) {
            case "TRANSIENT": return 0;
            case "GRANT": case "REVOKE": case "APPOPS": case "WL_ADD": case "WL_REM": case "BUCKET": case "SPUT": case "SDEL":
            case "ENABLE": case "DISABLE": case "HIDE": case "UNHIDE": case "SUSPEND": case "UNSUSPEND": case "SVC":
            case "SETPROP": case "SETENFORCE": case "REMOUNT": case "WM": case "BATTERY": case "SYSFS":
                return 1;
            case "UNINSTALL": case "CLEAR": case "RM": case "MV": case "CP": case "FILEEDIT": case "PERMS": case "IPT":
                return 2;
            default:
                return 3;
        }
    }

    /** Level of the whole command; a model-supplied undo command lifts the unknown parts from 3 to 1. */
    static int level(String cmd, boolean hasModelUndo) {
        int max = 0;
        for (Op o : parse(cmd)) max = Math.max(max, levelOf(o.kind));
        return (max == 3 && hasModelUndo) ? 1 : max;
    }

    /** Short Hebrew line for the approval dialog. */
    static String note(String cmd, boolean hasModelUndo) {
        int l = level(cmd, hasModelUndo);
        switch (l) {
            case 0: return "";
            case 1: return hasModelUndo && level(cmd, false) == 3
                ? "↩ ניתן לביטול לפי הצעת ה-AI (לא מאומת). יירשם ביומן."
                : "↩ ניתן לביטול: יירשם ביומן השינויים עם כפתור \"בטל\".";
            case 2: return "💾 הפעולה מוחקת או משנה: יישמר גיבוי לפני הביצוע, ואפשר יהיה לשחזר.";
            default: return "⚠ לא ניתן לבטל ולא ניתן לגבות פעולה זו. היא תתבצע רק באישורך.";
        }
    }

    // ================================================================ preparing (probes + backups)

    private static String q(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String sh(String c, boolean root) {
        return Shell.run(c, root, 20).out.trim();
    }

    private static String parentOf(String p) {
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    private static String baseOf(String p) {
        return p.substring(p.lastIndexOf('/') + 1);
    }

    private static long sizeKb(String path, boolean root) {
        String o = sh("du -sk " + q(path) + " 2>/dev/null", root);
        try { return Long.parseLong(o.split("\\s+")[0]); } catch (Exception e) { return 0; }
    }

    private static int counter;

    /** Backs up one path; undo restores it (or removes it when it did not exist before). */
    private static boolean backupPath(String path, boolean root, Plan p) {
        if (!path.startsWith("/")) { p.failed.add("נתיב יחסי (" + path + ")"); return false; }
        if (!sh("[ -e " + q(path) + " ] && echo y", root).contains("y")) {
            p.undo.add("rm -rf " + q(path));
            return true;
        }
        long kb = sizeKb(path, root);
        if (kb > MAX_KB) { p.failed.add(path + " גדול מדי לגיבוי (" + (kb / 1024) + " MB)"); return false; }
        String d = p.dir + "/f" + (counter++);
        Shell.Result r = Shell.run("mkdir -p " + q(d) + " && (cp -a " + q(path) + " " + q(d) + "/ 2>/dev/null || cp -r " + q(path) + " " + q(d) + "/) && chmod -R 777 " + q(d), root, 180);
        if (r.exit != 0) { p.failed.add("העתקת הגיבוי נכשלה (" + path + ")"); return false; }
        p.undo.add("cp -a " + q(d + "/" + baseOf(path)) + " " + q(parentOf(path)) + "/ 2>/dev/null || cp -r " + q(d + "/" + baseOf(path)) + " " + q(parentOf(path)) + "/");
        p.notes.add("גיבוי: " + path);
        return true;
    }

    private static boolean isDir(String path, boolean root) {
        return sh("[ -d " + q(path) + " ] && echo y", root).contains("y");
    }

    static Plan prepare(String cmd, boolean root, String modelUndo) {
        Plan p = new Plan();
        List<Op> ops = parse(cmd);
        boolean needDir = false, unknown = false;
        for (Op o : ops) {
            int l = levelOf(o.kind);
            if (l == 2) needDir = true;
            if (l == 3) unknown = true;
            p.level = Math.max(p.level, l);
        }
        if (needDir) {
            p.dir = new File(Core.app.getFilesDir(), "backups/" + System.currentTimeMillis()).getAbsolutePath();
            new File(p.dir).mkdirs();
            Shell.run("chmod 777 " + q(p.dir), root, 10);
        }
        for (Op o : ops) {
            try { prepareOp(o, root, p); } catch (Exception e) { p.failed.add("שגיאה בהכנת הביטול: " + o.kind); }
        }
        Collections.reverse(p.undo);   // undo in reverse order of the changes
        if (unknown && modelUndo != null && !modelUndo.isEmpty()) p.undo.add(modelUndo);
        if (unknown && (modelUndo == null || modelUndo.isEmpty())) p.notes.add("חלק מהפעולה בלתי הפיך");
        if (!unknown) p.level = Math.min(p.level, 2);
        else if (modelUndo != null && !modelUndo.isEmpty()) p.level = 1;
        return p;
    }

    private static void prepareOp(Op o, boolean root, Plan p) {
        List<String> a = o.a;
        switch (o.kind) {
            case "GRANT": case "REVOKE": {
                String pkg = a.get(0), perm = a.get(1);
                boolean granted = sh("dumpsys package " + pkg + " | grep -F " + q(perm + ": granted="), root).contains("granted=true");
                if (o.kind.equals("GRANT") && !granted) p.undo.add("pm revoke " + pkg + " " + perm);
                if (o.kind.equals("REVOKE") && granted) p.undo.add("pm grant " + pkg + " " + perm);
                return;
            }
            case "APPOPS": {
                String pkg = a.get(0), op = a.get(1), mode = a.get(2);
                String out = sh("appops get " + pkg + " " + op + " 2>&1 || cmd appops get " + pkg + " " + op + " 2>&1", root);
                Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(op) + "\\s*:\\s*([a-z_]+)").matcher(out);
                String prev = m.find() ? m.group(1).toLowerCase() : "default";
                if (!prev.equals(mode.toLowerCase())) p.undo.add("appops set " + pkg + " " + op + " " + prev);
                return;
            }
            case "WL_ADD": case "WL_REM": {
                String pkg = a.get(0);
                int n = 0;
                try { n = Integer.parseInt(sh("dumpsys deviceidle whitelist | grep -c " + q(pkg), root).split("\\s+")[0]); } catch (Exception ignored) { }
                if (o.kind.equals("WL_ADD") && n == 0) p.undo.add("dumpsys deviceidle whitelist -" + pkg);
                if (o.kind.equals("WL_REM") && n > 0) p.undo.add("dumpsys deviceidle whitelist +" + pkg);
                return;
            }
            case "BUCKET": {
                String prev = sh("am get-standby-bucket " + a.get(0), root);
                if (prev.matches("[A-Za-z0-9_]+")) p.undo.add("am set-standby-bucket " + a.get(0) + " " + prev);
                return;
            }
            case "SPUT": case "SDEL": {
                String prev = sh("settings get " + a.get(0) + " " + a.get(1), root);
                if (prev.isEmpty() || prev.equals("null")) {
                    if (o.kind.equals("SPUT")) p.undo.add("settings delete " + a.get(0) + " " + a.get(1));
                } else p.undo.add("settings put " + a.get(0) + " " + a.get(1) + " " + q(prev));
                return;
            }
            case "ENABLE": {
                int n = 0;
                try { n = Integer.parseInt(sh("pm list packages -d | grep -c " + q("package:" + a.get(0) + "$"), root).split("\\s+")[0]); } catch (Exception ignored) { }
                if (n > 0) p.undo.add("pm disable-user --user 0 " + a.get(0));
                return;
            }
            case "DISABLE": p.undo.add("pm enable " + a.get(0)); return;
            case "HIDE": p.undo.add("pm unhide " + a.get(0)); return;
            case "UNHIDE": p.undo.add("pm hide " + a.get(0)); return;
            case "SUSPEND": p.undo.add("pm unsuspend " + a.get(0)); return;
            case "UNSUSPEND": p.undo.add("pm suspend " + a.get(0)); return;
            case "SVC": p.undo.add("svc " + a.get(0) + " " + (a.get(1).equals("enable") ? "disable" : "enable")); p.notes.add("ביטול הופך את המצב, גם אם היה כך קודם"); return;
            case "SETPROP": {
                String prev = sh("getprop " + a.get(1), root);
                p.undo.add(a.get(0) + " " + a.get(1) + " " + q(prev));
                return;
            }
            case "SETENFORCE": {
                String prev = sh("getenforce", root);
                if (prev.equalsIgnoreCase("Enforcing")) p.undo.add("setenforce 1");
                else if (prev.equalsIgnoreCase("Permissive")) p.undo.add("setenforce 0");
                return;
            }
            case "REMOUNT": p.undo.add("mount -o remount," + (a.get(1).equals("rw") ? "ro" : "rw") + " " + a.get(0)); return;
            case "WM": p.undo.add("wm " + a.get(0) + " reset"); return;
            case "BATTERY": p.undo.add("dumpsys battery reset"); return;
            case "SYSFS": {
                String prev = sh("cat " + q(a.get(0)), root).split("\\n")[0].trim();
                if (prev.isEmpty()) p.failed.add("לא ניתן לקרוא את הערך הנוכחי של " + a.get(0));
                else p.undo.add("echo " + q(prev) + " > " + q(a.get(0)));
                return;
            }
            case "IPT": {
                String f = p.dir + "/" + a.get(0) + ".rules";
                Shell.run(a.get(0) + "-save > " + q(f) + " 2>/dev/null", root, 20);
                if (sh("[ -s " + q(f) + " ] && echo y", root).contains("y")) {
                    p.undo.add(a.get(0) + "-restore < " + q(f));
                    p.notes.add("גיבוי חוקי " + a.get(0));
                } else p.failed.add("שמירת חוקי " + a.get(0) + " נכשלה");
                return;
            }
            case "UNINSTALL": {
                String pkg = a.get(0);
                String out = sh("pm path " + pkg, root);
                List<String> files = new ArrayList<String>();
                for (String ln : out.split("\\n")) if (ln.startsWith("package:")) files.add(ln.substring(8).trim());
                if (files.isEmpty()) { p.failed.add("לא נמצאו קובצי APK של " + pkg); return; }
                String d = p.dir + "/apk_" + pkg;
                StringBuilder cp = new StringBuilder("mkdir -p " + q(d));
                StringBuilder inst = new StringBuilder();
                for (int i = 0; i < files.size(); i++) {
                    cp.append(" && cp ").append(q(files.get(i))).append(' ').append(q(d + "/" + i + ".apk"));
                    inst.append(' ').append(q(d + "/" + i + ".apk"));
                }
                cp.append(" && chmod -R 777 ").append(q(d));
                if (Shell.run(cp.toString(), root, 120).exit != 0) { p.failed.add("העתקת קובצי ה-APK נכשלה"); return; }
                p.undo.add(files.size() == 1 ? "pm install -r" + inst : "pm install-multiple -r" + inst);
                p.notes.add("גיבוי APK של " + pkg + (a.get(1).equals("k") ? "" : ". נתוני האפליקציה לא ישוחזרו"));
                return;
            }
            case "CLEAR": {
                String pkg = a.get(0), dir = "/data/data/" + pkg;
                if (!sh("[ -d " + q(dir) + " ] && echo y", root).contains("y")) { p.failed.add("אין גישה לתיקיית הנתונים של " + pkg + " (נדרש root)"); return; }
                long kb = sizeKb(dir, root);
                if (kb > MAX_KB) { p.failed.add("נתוני " + pkg + " גדולים מדי לגיבוי (" + (kb / 1024) + " MB)"); return; }
                String uid = sh("stat -c %u " + q(dir) + " 2>/dev/null", root);
                if (!uid.matches("\\d+")) { p.failed.add("לא ניתן לקבוע את בעל התיקייה של " + pkg); return; }
                String d = p.dir + "/data_" + pkg;
                Shell.Result r = Shell.run("mkdir -p " + q(d) + " && (cp -a " + q(dir) + "/. " + q(d) + "/ 2>/dev/null || cp -r " + q(dir) + "/. " + q(d) + "/) && chmod -R 777 " + q(d), root, 240);
                if (r.exit != 0) { p.failed.add("גיבוי נתוני " + pkg + " נכשל"); return; }
                p.undo.add("am force-stop " + pkg + "; cp -a " + q(d) + "/. " + q(dir) + "/; chown -R " + uid + ":" + uid + " " + q(dir) + "; restorecon -R " + q(dir) + " 2>/dev/null");
                p.notes.add("גיבוי נתוני " + pkg);
                return;
            }
            case "RM": {
                for (String path : a) {
                    if (path.contains("*") || path.contains("?")) {
                        for (String e : sh("ls -d " + path + " 2>/dev/null", root).split("\\n")) if (!e.trim().isEmpty()) backupPath(e.trim(), root, p);
                    } else if (sh("[ -e " + q(path) + " ] && echo y", root).contains("y")) backupPath(path, root, p);
                }
                return;
            }
            case "MV": case "CP": {
                String dst = a.get(a.size() - 1);
                List<String> srcs = a.subList(0, a.size() - 1);
                boolean dstDir = isDir(dst, root);
                boolean mv = o.kind.equals("MV");
                if (srcs.size() == 1 && !dstDir) {
                    if (sh("[ -e " + q(dst) + " ] && echo y", root).contains("y")) backupPath(dst, root, p);
                    else if (!mv) p.undo.add("rm -rf " + q(dst));
                    if (mv) p.undo.add("mv " + q(dst) + " " + q(srcs.get(0)));
                } else {
                    for (String s : srcs) {
                        String target = dst + "/" + baseOf(s);
                        if (sh("[ -e " + q(target) + " ] && echo y", root).contains("y")) backupPath(target, root, p);
                        else if (!mv) p.undo.add("rm -rf " + q(target));
                        if (mv) p.undo.add("mv " + q(target) + " " + q(s));
                    }
                }
                return;
            }
            case "FILEEDIT": backupPath(a.get(0), root, p); return;
            case "PERMS": {
                for (String path : a) {
                    String s = sh("stat -c '%a %u %g' " + q(path) + " 2>/dev/null", root);
                    Matcher m = Pattern.compile("^(\\d+) (\\d+) (\\d+)$").matcher(s);
                    if (!m.find()) { p.failed.add("לא ניתן לקרוא הרשאות של " + path); continue; }
                    p.undo.add("chmod " + m.group(1) + " " + q(path) + "; chown " + m.group(2) + ":" + m.group(3) + " " + q(path));
                }
                return;
            }
            default:
        }
    }

    static void discard(Plan p) {
        if (p != null && !p.dir.isEmpty()) Shell.run("rm -rf " + q(p.dir), true, 30);
    }

    // ================================================================ persistence

    private static JSONArray cache;

    private static File file() {
        return new File(Core.app.getFilesDir(), "journal.json");
    }

    private static synchronized JSONArray load() {
        if (cache != null) return cache;
        try {
            File f = file();
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                cache = new JSONArray(bo.toString("UTF-8"));
            }
        } catch (Exception ignored) { }
        if (cache == null) cache = new JSONArray();
        return cache;
    }

    private static synchronized void save() {
        try {
            FileOutputStream o = new FileOutputStream(file());
            o.write(load().toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    private static synchronized long put(JSONObject e) {
        JSONArray a = load();
        a.put(e);
        while (a.length() > MAX_ENTRIES) {
            JSONObject old = a.optJSONObject(0);
            if (old != null && !old.optString("dir").isEmpty()) Shell.run("rm -rf " + q(old.optString("dir")), old.optBoolean("root"), 30);
            JSONArray n = new JSONArray();
            for (int i = 1; i < a.length(); i++) n.put(a.opt(i));
            cache = n;
            a = n;
        }
        save();
        return e.optLong("id");
    }

    static long add(String purpose, String cmd, boolean root, Plan p, int exit) {
        try {
            JSONObject e = new JSONObject().put("id", System.currentTimeMillis()).put("kind", "sh").put("purpose", purpose)
                .put("cmd", cmd).put("root", root).put("level", p.level).put("state", "applied")
                .put("dir", p.dir).put("exit", exit).put("undo", new JSONArray(p.undo)).put("notes", join(p.notes, "\n"));
            return put(e);
        } catch (Exception ex) {
            return 0;
        }
    }

    static long addAdmin(String purpose, String shown, String aAct, String pkg, String perm, int aState) {
        try {
            JSONObject e = new JSONObject().put("id", System.currentTimeMillis()).put("kind", "admin").put("purpose", purpose)
                .put("cmd", shown).put("root", false).put("level", 1).put("state", "applied").put("dir", "")
                .put("aAct", aAct == null ? "" : aAct).put("aPkg", pkg).put("aPerm", perm).put("aState", aState)
                .put("undo", new JSONArray().put("admin")).put("notes", "");
            return put(e);
        } catch (Exception ex) {
            return 0;
        }
    }

    static synchronized JSONObject get(long id) {
        JSONArray a = load();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && e.optLong("id") == id) return e;
        }
        return null;
    }

    static synchronized List<JSONObject> list() {
        JSONArray a = load();
        List<JSONObject> r = new ArrayList<JSONObject>();
        for (int i = a.length() - 1; i >= 0; i--) { JSONObject e = a.optJSONObject(i); if (e != null) r.add(e); }
        return r;
    }

    static int activeCount() {
        int n = 0;
        for (JSONObject e : list()) if (canUndo(e)) n++;
        return n;
    }

    static boolean canUndo(JSONObject e) {
        JSONArray u = e.optJSONArray("undo");
        return e.optString("state").equals("applied") && u != null && u.length() > 0;
    }

    /** Runs the undo commands. Blocking. */
    static String undo(long id) {
        JSONObject e = get(id);
        if (e == null) return "הרשומה לא נמצאה";
        if (e.optString("state").equals("undone")) return "כבר בוטל";
        boolean ok = true;
        StringBuilder err = new StringBuilder();
        try {
            if (e.optString("kind").equals("admin")) {
                ok = AdminOps.undo(Core.app, e);
                if (!ok) err.append("פעולת מנהל המכשיר נכשלה");
            } else {
                JSONArray u = e.optJSONArray("undo");
                boolean root = e.optBoolean("root");
                for (int i = 0; u != null && i < u.length(); i++) {
                    Shell.Result r = Shell.run(u.optString(i) + " 2>&1", root, 180);
                    if (r.exit != 0) { ok = false; err.append(r.out.length() > 160 ? r.out.substring(0, 160) : r.out).append('\n'); }
                }
            }
        } catch (Exception ex) {
            ok = false;
            err.append(ex.getMessage());
        }
        synchronized (Journal.class) {
            try { e.put("state", ok ? "undone" : "applied").put("undoError", ok ? "" : err.toString()); } catch (Exception ignored) { }
            save();
        }
        return ok ? "הביטול בוצע" : "הביטול נכשל חלקית:\n" + err.toString().trim();
    }

    static synchronized void clearAll() {
        JSONArray a = load();
        for (int i = 0; i < a.length(); i++) {
            JSONObject e = a.optJSONObject(i);
            if (e != null && !e.optString("dir").isEmpty()) Shell.run("rm -rf " + q(e.optString("dir")), e.optBoolean("root"), 30);
        }
        cache = new JSONArray();
        save();
    }
}
