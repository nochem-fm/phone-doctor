package com.phone.doctor.fm;

import android.app.Notification;
import android.content.Context;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Notification history, so the agent can tell "the notification never arrived" from "it arrived but was
 * not shown". Only metadata is kept (time, app, channel, flags), never the text of the notification.
 */
public class NotifyService extends NotificationListenerService {
    private static final int MAX_LINES = 3000;

    static File file(Context c) { return new File(c.getFilesDir(), "notify_log.txt"); }

    static boolean enabled(Context c) {
        String v = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        return v != null && v.contains(c.getPackageName() + "/");
    }

    static String component(Context c) { return c.getPackageName() + "/" + NotifyService.class.getName(); }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            Notification n = sbn.getNotification();
            String ch = Build.VERSION.SDK_INT >= 26 ? Compat.channelOf(n) : "";
            boolean ongoing = (n.flags & Notification.FLAG_ONGOING_EVENT) != 0;
            String line = System.currentTimeMillis() + "|" + sbn.getPackageName() + "|" + (ch == null ? "" : ch) + "|" + (ongoing ? "ongoing" : "")
                + "|" + (n.category == null ? "" : n.category) + "\n";
            synchronized (NotifyService.class) {
                File f = file(this);
                FileWriter w = new FileWriter(f, true);
                w.write(line);
                w.close();
                if (f.length() > 400000) trim(f);
            }
        } catch (Throwable ignored) { }
    }

    private static void trim(File f) throws Exception {
        List<String> l = read(f);
        FileWriter w = new FileWriter(f, false);
        for (int i = Math.max(0, l.size() - MAX_LINES); i < l.size(); i++) w.write(l.get(i) + "\n");
        w.close();
    }

    private static List<String> read(File f) throws Exception {
        List<String> l = new ArrayList<String>();
        if (!f.exists()) return l;
        BufferedReader r = new BufferedReader(new FileReader(f));
        String s;
        while ((s = r.readLine()) != null) l.add(s);
        r.close();
        return l;
    }

    /** Report for the agent: notifications per app in the last N hours, or the timeline of one app. */
    static String report(Context c, String pkg, int hours) {
        if (!enabled(c)) return "NOT AVAILABLE: notification access is off. Ask the user to enable 'notification access' for Phone Doctor in Settings (Phone Doctor > זיכרון ומעקב / גישה להתראות), or with root it can be enabled automatically. History starts only after it is enabled.";
        List<String> l;
        synchronized (NotifyService.class) {
            try { l = read(file(c)); } catch (Exception e) { return "ERROR reading history: " + e; }
        }
        long since = System.currentTimeMillis() - hours * 3600000L;
        SimpleDateFormat fmt = new SimpleDateFormat("dd/MM HH:mm", Locale.US);
        if (l.isEmpty()) return "History is empty (access was enabled recently, or nothing was posted since).";
        long first = Long.parseLong(l.get(0).split("\\|")[0]);
        StringBuilder sb = new StringBuilder("History available since " + fmt.format(new Date(first)) + ".\n");
        if (pkg != null && !pkg.isEmpty()) {
            List<String> mine = new ArrayList<String>();
            for (String s : l) { String[] p = s.split("\\|", -1); if (p.length > 1 && p[1].equals(pkg) && Long.parseLong(p[0]) >= since) mine.add(s); }
            sb.append(pkg).append(": ").append(mine.size()).append(" notifications in the last ").append(hours).append(" h\n");
            for (int i = Math.max(0, mine.size() - 25); i < mine.size(); i++) {
                String[] p = mine.get(i).split("\\|", -1);
                sb.append("  ").append(fmt.format(new Date(Long.parseLong(p[0])))).append(" channel=").append(p.length > 2 ? p[2] : "")
                    .append(p.length > 3 && !p[3].isEmpty() ? " ongoing" : "").append(p.length > 4 && !p[4].isEmpty() ? " category=" + p[4] : "").append('\n');
            }
            return sb.toString();
        }
        Map<String, int[]> per = new LinkedHashMap<String, int[]>();
        Map<String, Long> last = new LinkedHashMap<String, Long>();
        for (String s : l) {
            String[] p = s.split("\\|", -1);
            if (p.length < 2) continue;
            long t = Long.parseLong(p[0]);
            if (t < since) continue;
            int[] n = per.get(p[1]);
            if (n == null) { n = new int[1]; per.put(p[1], n); }
            n[0]++;
            last.put(p[1], t);
        }
        sb.append("Notifications per app in the last ").append(hours).append(" h (count, last time):\n");
        for (Map.Entry<String, int[]> e : per.entrySet())
            sb.append("  ").append(e.getKey()).append(": ").append(e.getValue()[0]).append(", ").append(fmt.format(new Date(last.get(e.getKey())))).append('\n');
        return sb.toString();
    }
}
