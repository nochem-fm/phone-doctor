package com.phone.doctor.fm;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

/** Foreground service: keeps the agent alive while the app is in the background. */
public class AgentService extends Service {
    static final String CH = "pd_run", CH_ALERT = "pd_alert";
    private static long lastNotify;
    private static String lastText = "";
    private PowerManager.WakeLock wl;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        if (i != null && "STOP".equals(i.getAction())) {
            Core.stopRun();
            return START_NOT_STICKY;
        }
        startForeground(1, build(this, Core.status.isEmpty() ? "עובד על הבעיה…" : Core.status));
        if (wl == null) {
            wl = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonedoctor:agent");
            wl.acquire(60 * 60 * 1000L);
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wl != null && wl.isHeld()) wl.release();
        super.onDestroy();
    }

    static PendingIntent openApp(Context c) {
        Intent i = new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(c, 0, i, Compat.immutable() | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    static void channels(Context c) {
        Compat.channels(c);
    }

    static Notification build(Context c, String text) {
        channels(c);
        PendingIntent stop = PendingIntent.getService(c, 1, new Intent(c, AgentService.class).setAction("STOP"),
                Compat.immutable() | PendingIntent.FLAG_UPDATE_CURRENT);
        return Compat.builder(c, CH).setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Phone Doctor").setContentText(text).setOngoing(true)
                .setContentIntent(openApp(c)).addAction(android.R.drawable.ic_menu_close_clear_cancel, "עצור", stop).build();
    }

    /** Updates the ongoing notification (throttled). */
    static void update(Context c, String text) {
        long now = System.currentTimeMillis();
        if (!Core.running || text.equals(lastText) || now - lastNotify < 1500) return;
        lastNotify = now;
        lastText = text;
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(1, build(c, text));
    }

    /** One-off alert (approval needed / finished while the app is not visible). */
    static void alert(Context c, int id, String title, String text) {
        channels(c);
        Notification n = Compat.builder(c, CH_ALERT).setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text))
                .setPriority(Notification.PRIORITY_HIGH).setAutoCancel(true).setContentIntent(openApp(c)).build();
        ((NotificationManager) c.getSystemService(NOTIFICATION_SERVICE)).notify(id, n);
    }
}
