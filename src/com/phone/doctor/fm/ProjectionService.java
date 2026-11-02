package com.phone.doctor.fm;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Android 10 requires a "media projection" foreground service while the screen is captured. */
public class ProjectionService extends Service {
    @Override
    public int onStartCommand(Intent i, int flags, int id) {
        AgentService.channels(this);
        Notification n = Compat.builder(this, AgentService.CH).setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Phone Doctor").setContentText("גישה לצילום המסך פעילה").build();
        startForeground(77, n);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent i) { return null; }
}
