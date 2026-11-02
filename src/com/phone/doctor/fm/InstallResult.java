package com.phone.doctor.fm;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Receives the result of a PackageInstaller session (install or uninstall) and hands it to the waiting tool. */
public class InstallResult extends BroadcastReceiver {
    private static CountDownLatch latch;
    private static String result;

    static void prepare() {
        latch = new CountDownLatch(1);
        result = null;
    }

    static String await(int sec) throws InterruptedException {
        CountDownLatch l = latch;
        if (l != null) l.await(sec, TimeUnit.SECONDS);
        return result == null ? "NO RESULT within " + sec + " s (the user may not have answered the system dialog yet)" : result;
    }

    @Override
    public void onReceive(Context c, Intent i) {
        int st = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (st == PackageInstaller.STATUS_PENDING_USER_ACTION) {   // not silent: show the system confirmation
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Exception ignored) { }
            }
            return;   // the final status arrives in a second broadcast
        }
        String msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        result = (st == PackageInstaller.STATUS_SUCCESS ? "Success" : "FAILED status " + st) + (msg == null ? "" : ": " + msg);
        if (latch != null) latch.countDown();
    }
}
