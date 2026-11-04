package com.phone.doctor.fm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * Counts Gemini requests per model per quota day (the free tier resets at midnight Pacific time) and
 * warns with a phone notification when the daily requests of all chosen models are about to run out, or did.
 */
final class Quotas {
    static final int DEFAULT_LIMIT = 20;      // free tier: requests per day per model (learned from errors when it differs)
    static final int WARN_AT = 5;

    private static final TimeZone PT = TimeZone.getTimeZone("America/Los_Angeles");

    private static SharedPreferences p() { return Core.prefs(); }

    static String day() {
        Calendar c = Calendar.getInstance(PT);
        return c.get(Calendar.YEAR) + "-" + c.get(Calendar.DAY_OF_YEAR);
    }

    /** When today's quota resets, in this phone's local time ("HH:mm"). */
    static String resetAt() {
        Calendar c = Calendar.getInstance(PT);
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        return new java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(c.getTime());
    }

    private static JSONObject counts() {
        try {
            JSONObject o = new JSONObject(p().getString("q_counts", "{}"));
            if (!day().equals(o.optString("_day"))) { o = new JSONObject(); o.put("_day", day()); }
            return o;
        } catch (Exception e) { return new JSONObject(); }
    }

    static synchronized void sent(String model) {
        try {
            JSONObject o = counts();
            o.put("_day", day());
            o.put(model, o.optInt(model) + 1);
            p().edit().putString("q_counts", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    /** The real per-day limit, read from a 429 error (quotaValue). */
    static synchronized void learn(String model, int limit) {
        if (limit <= 0) return;
        try {
            JSONObject o = new JSONObject(p().getString("q_limits", "{}"));
            o.put(model, limit);
            p().edit().putString("q_limits", o.toString()).apply();
        } catch (Exception ignored) { }
    }

    private static int limit(String model) {
        try { return new JSONObject(p().getString("q_limits", "{}")).optInt(model, DEFAULT_LIMIT); } catch (Exception e) { return DEFAULT_LIMIT; }
    }

    /** Requests left today over all the chosen models; -1 when unknown (e.g. a paid key that never hit the limit). */
    static synchronized int left(List<String> models, java.util.Set<String> exhausted) {
        JSONObject o = counts();
        int sum = 0;
        for (String m : models) {
            if (exhausted.contains(m)) continue;
            int lim = limit(m), used = o.optInt(m);
            if (used > lim) return -1;       // more than the assumed limit went through: not the free tier
            sum += lim - used;
        }
        return sum;
    }

    /** Called after each request: notifies once a day when the quota is nearly used up, and once when it is gone. */
    static void check(Context c, List<String> models, java.util.Set<String> exhausted) {
        int left = left(models, exhausted);
        if (left < 0) return;
        if (left == 0) allOut(c);
        else if (left <= WARN_AT && !day().equals(p().getString("q_warned", ""))) {
            p().edit().putString("q_warned", day()).apply();
            notify(c, 4301, "המכסה היומית של Gemini כמעט נגמרה",
                "נשארו בערך " + left + " בקשות היום בכל המודלים שבחרת. כל צעד של הסוכן הוא בקשה. המכסה מתאפסת ב-" + resetAt() + ".");
        }
    }

    static void allOut(Context c) {
        if (day().equals(p().getString("q_out", ""))) return;
        p().edit().putString("q_out", day()).apply();
        notify(c, 4302, "המכסה היומית של Gemini נגמרה",
            "נגמרו הבקשות של היום בכל המודלים שבחרת. המכסה מתאפסת ב-" + resetAt() + ". אפשר להוסיף מודלי גיבוי או מפתח מחשבון Google אחר בהגדרות.");
    }

    private static void notify(Context c, int id, String title, String text) {
        try {
            AgentService.channels(c);
            Intent open = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pi = PendingIntent.getActivity(c, id, open, Compat.immutable() | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = Compat.builder(c, AgentService.CH_ALERT).setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi).build();
            ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
        } catch (Exception ignored) { }
    }
}
