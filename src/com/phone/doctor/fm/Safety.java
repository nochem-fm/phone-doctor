package com.phone.doctor.fm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** Local safety net. Independent of what the model claims. */
final class Safety {
    /** Catastrophic commands: never run, even with user approval. */
    static final Pattern BLOCK = Pattern.compile(
        "(?i)(\\brm\\s+(-\\S+\\s+)*/(system|data|vendor|product|boot|dev|sdcard|storage)?/?(\\s|$|\\*|;)"
        + "|\\bmkfs|\\bdd\\s[^;|&]*of=/dev/|>\\s*/dev/block|\\bwipe\\s+(data|all)|--wipe_data"
        + "|\\bfastboot\\b|flash_image|\\bflashing\\b|reboot\\s+(bootloader|recovery|fastboot|edl)"
        + "|\\bmasterclear\\b|factory_?reset|:\\(\\)\\s*\\{|chmod\\s+-R\\s+\\S+\\s+/(\\s|$))");

    /** Needs explicit approval even in autonomous mode. */
    static final Pattern SENSITIVE = Pattern.compile(
        "(?i)(\\bpm\\s+(uninstall|clear|disable|hide|suspend)|\\bcmd\\s+package\\s+uninstall|\\brm\\s|\\bmv\\s|\\bdd\\s|\\bchmod\\b|\\bchown\\b"
        + "|\\bsetprop\\b|\\bresetprop\\b|\\biptables\\b|\\bmount\\b|\\breboot\\b|\\bsvc\\s+power|\\bsu\\b"
        + "|settings\\s+put\\s+\\w+\\s+(adb_enabled|install_non_market_apps|airplane_mode_on)|\\bsetenforce\\b|\\bmagisk\\b)");

    static boolean sensitive(String cmd) {
        return SENSITIVE.matcher(cmd).find();
    }

    static boolean blocked(String cmd) {
        return BLOCK.matcher(cmd).find();
    }

    static final Set<String> RO = new HashSet<String>(Arrays.asList(
        "getprop", "cat", "ls", "df", "du", "ps", "uname", "uptime", "free", "id", "stat", "grep", "egrep",
        "head", "tail", "wc", "sort", "uniq", "cut", "netstat", "ss", "getenforce", "lsmod", "dmesg",
        "readlink", "which", "whoami", "echo", "printf", "test", "lsof", "nproc", "vmstat", "pidof", "tr",
        "basename", "dirname", "true", "sleep", "ls", "stat", "md5sum", "sha1sum", "sha256sum"));

    static final Set<String> MUTATING_WORDS = new HashSet<String>(Arrays.asList(
        "set", "reset", "unplug", "suspend", "whitelist", "force-idle", "enable", "disable", "step",
        "unforce", "tempwhitelist", "add", "del", "delete", "flush", "change", "replace", "up", "down"));

    /** True only if every segment of the command is a known read-only operation. */
    /** The command with the inside of '...' and "..." blanked, so a | or > inside a grep pattern is not taken as a pipe/redirect. */
    static String unquoted(String cmd) {
        StringBuilder sb = new StringBuilder(cmd.length());
        char q = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (q == 0) {
                if (c == '\'' || c == '"') { q = c; sb.append(c); } else sb.append(c);
            } else {
                if (c == q) { q = 0; sb.append(c); } else sb.append(c == '\n' ? '\n' : '_');
            }
        }
        return sb.toString();
    }

    /** Splits on newline ; && || | outside quotes. */
    static List<String> segments(String cmd) {
        List<String> out = new ArrayList<String>();
        String u = unquoted(cmd);
        int start = 0;
        for (int i = 0; i < u.length(); i++) {
            char c = u.charAt(i);
            int len = 0;
            if (c == '\n' || c == ';') len = 1;
            else if (c == '|') len = (i + 1 < u.length() && u.charAt(i + 1) == '|') ? 2 : 1;
            else if (c == '&' && i + 1 < u.length() && u.charAt(i + 1) == '&') len = 2;
            if (len > 0) { out.add(cmd.substring(start, i)); start = i + len; i += len - 1; }
        }
        out.add(cmd.substring(start));
        return out;
    }

    /** True only if every segment of the command is a known read-only operation. */
    static boolean readOnly(String cmd) {
        String s = cmd.replace("2>&1", "").replace("2>/dev/null", "").replace(">/dev/null", "");
        String u = unquoted(s);
        if (u.contains(">") || u.contains("`") || u.contains("$(") || u.contains("<(")) return false;
        for (String seg : segments(s)) {
            if (unquoted(seg).contains("&")) return false;
            if (!segOk(seg.trim())) return false;
        }
        return true;
    }

    private static boolean has(String[] t, String x) {
        for (String v : t) if (v.equals(x)) return true;
        return false;
    }

    private static boolean anyMutating(String[] t) {
        for (int i = 1; i < t.length; i++) if (MUTATING_WORDS.contains(t[i])) return true;
        return false;
    }

    private static boolean segOk(String seg) {
        if (seg.isEmpty()) return true;
        String[] t = seg.split("\\s+");
        String c = t[0];
        String a1 = t.length > 1 ? t[1] : "";
        switch (c) {
            case "dumpsys": return t.length <= 4 && !anyMutating(t);
            case "pm": return a1.equals("list") || a1.equals("path") || a1.equals("dump") || a1.startsWith("get-");
            case "settings": return a1.equals("get") || a1.equals("list");
            case "cmd": return a1.equals("package") && t.length > 2 && (t[2].equals("list") || t[2].equals("resolve-activity"));
            case "am": return a1.equals("stack") || a1.equals("get-current-user");
            case "appops": return a1.equals("get");
            case "service": return a1.equals("list") || a1.equals("check");
            case "content": return a1.equals("query");
            case "wm": return t.length == 2 && (a1.equals("size") || a1.equals("density"));
            case "mount": return t.length == 1;
            case "top": return has(t, "-n");
            case "logcat": return has(t, "-d") && !has(t, "-c") && !has(t, "-G");
            case "ping": return has(t, "-c");
            case "find": return !(has(t, "-delete") || has(t, "-exec") || has(t, "-ok") || has(t, "-fprint"));
            case "ip": return !anyMutating(t);
            case "ifconfig": return t.length == 1 || (t.length == 2 && !anyMutating(t));
            case "date": return t.length == 1 || a1.startsWith("+");
            case "sort": return !seg.contains(" -o");
            default: return RO.contains(c);
        }
    }
}
