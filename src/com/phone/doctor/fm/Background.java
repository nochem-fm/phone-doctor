package com.phone.doctor.fm;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Work that happens while the app is closed, without Gemini:
 * - FOLLOWUP: a read-only check the agent scheduled ("check again in 2 hours"); the result is shown as a
 *   notification, and tapping it opens the chat with before/after ready to send.
 * - WATCH: optional background monitoring every few hours (storage, fast battery drain, apps that keep
 *   crashing); it notifies only when something is wrong.
 */
public class Background extends BroadcastReceiver {
    static final String FOLLOWUP = "com.phone.doctor.fm.FOLLOWUP", WATCH = "com.phone.doctor.fm.WATCH";
    static final long WATCH_EVERY = 4 * 3600 * 1000L;

    @Override
    public void onReceive(final Context c, final Intent i) {
        Core.init(c);
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            public void run() {
                try {
                    String a = i.getAction();
                    if (Intent.ACTION_BOOT_COMPLETED.equals(a) || "android.intent.action.MY_PACKAGE_REPLACED".equals(a)) rearm(c);
                    else if (FOLLOWUP.equals(a)) followup(c, i.getLongExtra("id", 0));
                    else if (WATCH.equals(a)) watch(c);
                } catch (Throwable ignored) {
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("s", 0); }

    private static PendingIntent pi(Context c, String action, long id) {
        Intent i = new Intent(c, Background.class).setAction(action).putExtra("id", id);
        return PendingIntent.getBroadcast(c, (int) (id % 100000), i, Compat.immutable() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void at(Context c, long when, PendingIntent p) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        if (Build.VERSION.SDK_INT >= 23) Compat.alarmIdle(am, when, p);
        else am.set(AlarmManager.RTC_WAKEUP, when, p);
    }

    // ---------------------------------------------------------------- follow-ups scheduled by the agent

    /** Stores a follow-up and sets its alarm. Returns its id. */
    static long schedule(Context c, String purpose, String cmd, boolean root, int minutes, String before) {
        long id = System.currentTimeMillis();
        try {
            JSONArray l = new JSONArray(prefs(c).getString("followups", "[]"));
            l.put(new JSONObject().put("id", id).put("purpose", purpose).put("cmd", cmd).put("root", root)
                .put("before", cut(before, 1500)).put("at", id + minutes * 60000L));
            while (l.length() > 20) l.remove(0);
            prefs(c).edit().putString("followups", l.toString()).apply();
        } catch (Exception ignored) { }
        at(c, id + minutes * 60000L, pi(c, FOLLOWUP, id));
        return id;
    }

    private static void followup(Context c, long id) throws Exception {
        JSONArray l = new JSONArray(prefs(c).getString("followups", "[]")), keep = new JSONArray();
        JSONObject f = null;
        for (int i = 0; i < l.length(); i++) { JSONObject x = l.getJSONObject(i); if (x.optLong("id") == id) f = x; else keep.put(x); }
        prefs(c).edit().putString("followups", keep.toString()).apply();
        if (f == null) return;
        String cmd = f.optString("cmd");
        if (!Safety.readOnly(cmd)) return;   // only checks run unattended, never changes
        boolean root = f.optBoolean("root") && prefs(c).getBoolean("root", false);
        String after = Shell.run(cmd, root, 60).out.trim();
        String when = new SimpleDateFormat("dd/MM HH:mm", Locale.US).format(new Date(id));
        String msg = "תוצאות בדיקת ההמשך: " + f.optString("purpose") + "\n"
            + "פקודה: " + cmd + "\n"
            + "לפני (" + when + "):\n" + f.optString("before") + "\n"
            + "אחרי (עכשיו):\n" + cut(after, 1500) + "\n"
            + "מה המסקנה? האם התיקון עבד?";
        notify(c, (int) (id % 100000) + 1000, "בדיקת המשך הסתיימה", f.optString("purpose"), msg);
    }

    // ---------------------------------------------------------------- background monitoring

    static void setWatch(Context c, boolean on) {
        prefs(c).edit().putBoolean("watch", on).apply();
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        PendingIntent p = pi(c, WATCH, 7);
        am.cancel(p);
        if (on) am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 15 * 60000L, WATCH_EVERY, p);
    }

    static void rearm(Context c) {
        if (prefs(c).getBoolean("watch", false)) setWatch(c, true);
        try {   // follow-ups that were due while the phone was off run soon after boot
            JSONArray l = new JSONArray(prefs(c).getString("followups", "[]"));
            for (int i = 0; i < l.length(); i++) {
                JSONObject x = l.getJSONObject(i);
                at(c, Math.max(x.optLong("at"), System.currentTimeMillis() + 120000L), pi(c, FOLLOWUP, x.optLong("id")));
            }
        } catch (Exception ignored) { }
    }

    private static void watch(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean("watch", false)) return;
        boolean root = p.getBoolean("root", false);
        StringBuilder found = new StringBuilder();

        // 1. storage
        try {
            StatFs s = new StatFs(Environment.getDataDirectory().getPath());
            long free = (long) s.getAvailableBlocks() * s.getBlockSize(), total = (long) s.getBlockCount() * s.getBlockSize();
            if (total > 0 && (free < 1024L * 1024 * 1024 || free * 100 / total < 8))
                found.append("• האחסון כמעט מלא: נשארו ").append(free >> 20).append(" MB פנויים מתוך ").append(total >> 30).append(" GB\n");
        } catch (Exception ignored) { }

        // 2. battery drain while not charging
        try {
            Intent b = c.registerReceiver(null, new android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int lvl = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / Math.max(1, b.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
            boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            long now = System.currentTimeMillis();
            int lastLvl = p.getInt("w_lvl", -1);
            long lastT = p.getLong("w_t", 0);
            boolean lastPlugged = p.getBoolean("w_pl", true);
            if (!plugged && !lastPlugged && lastLvl > lvl && now - lastT > 3600000L && now - lastT < 12 * 3600000L) {
                double perHour = (lastLvl - lvl) * 3600000.0 / (now - lastT);
                if (perHour >= 8) found.append("• הסוללה יורדת מהר: בערך ").append((int) perHour).append("% בשעה בלי טעינה\n");
            }
            p.edit().putInt("w_lvl", lvl).putLong("w_t", now).putBoolean("w_pl", plugged).apply();
        } catch (Exception ignored) { }

        // 3. apps that keep crashing (needs the DUMP permission or root; silently skipped otherwise)
        try {
            long since = p.getLong("w_crash", System.currentTimeMillis() - WATCH_EVERY);
            String out = Shell.run("dumpsys dropbox --print data_app_crash 2>/dev/null | grep -E '^[0-9]{4}-[0-9]{2}-[0-9]{2} |^Process: ' | tail -400", root, 30).out;
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            Matcher m = Pattern.compile("(?m)^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})[^\\n]*\\n(?:[^\\n]*\\n)*?Process: ([\\w.:]+)").matcher(out);
            Map<String, Integer> n = new HashMap<String, Integer>();
            while (m.find()) {
                try {
                    if (fmt.parse(m.group(1)).getTime() <= since) continue;
                } catch (Exception e) { continue; }
                String pk = m.group(2).split(":")[0];
                n.put(pk, n.containsKey(pk) ? n.get(pk) + 1 : 1);
            }
            for (Map.Entry<String, Integer> e : n.entrySet())
                if (e.getValue() >= 3) found.append("• האפליקציה ").append(e.getKey()).append(" קרסה ").append(e.getValue()).append(" פעמים מאז הבדיקה הקודמת\n");
            p.edit().putLong("w_crash", System.currentTimeMillis()).apply();
        } catch (Exception ignored) { }

        if (found.length() == 0) return;
        String text = found.toString().trim();
        notify(c, 4242, "Phone Doctor מצא משהו", text,
            "קיבלתי התראה מהמעקב ברקע:\n" + text + "\nתבדוק מה הסיבה ותתקן אם צריך.");
    }

    // ---------------------------------------------------------------- notification that opens the chat with a ready message

    static void notify(Context c, int id, String title, String text, String prefill) {
        AgentService.channels(c);
        Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("prefill", prefill);
        PendingIntent p = PendingIntent.getActivity(c, id, open, Compat.immutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = Compat.builder(c, AgentService.CH_ALERT).setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true).setContentIntent(p).build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
    }

    private static String cut(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
