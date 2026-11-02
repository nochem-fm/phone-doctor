package com.phone.doctor.fm;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Screenshots without root on Android 5-10 (Android 11+ uses the accessibility service): the system
 * "start recording/casting" consent, asked once per session. Only used for single frames.
 */
final class Projection {
    static final int REQ = 41;
    private static MediaProjection mp;
    private static CountDownLatch wait;
    private static int resultCode;
    private static Intent resultData;

    static boolean supported() { return Build.VERSION.SDK_INT >= 21 && Build.VERSION.SDK_INT < 30; }

    static boolean ready() { return mp != null; }

    /** Called on the agent thread. Asks the user (the app must be in front). Returns true when granted. */
    static boolean request() throws InterruptedException {
        if (mp != null) return true;
        final MainActivity a = Core.ui;
        if (a == null || !a.resumed) return false;
        wait = new CountDownLatch(1);
        Core.H.post(new Runnable() {
            public void run() {
                try {
                    MediaProjectionManager m = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                    a.startActivityForResult(m.createScreenCaptureIntent(), REQ);
                } catch (Exception e) {
                    wait.countDown();
                }
            }
        });
        wait.await(90, TimeUnit.SECONDS);
        return mp != null;
    }

    /** From MainActivity.onActivityResult. */
    static void onResult(Activity a, int code, Intent data) {
        try {
            if (code == Activity.RESULT_OK && data != null) {
                resultCode = code;
                resultData = data;
                if (Build.VERSION.SDK_INT == 29) {   // Android 10: a media-projection foreground service must run first
                    a.startForegroundService(new Intent(a, ProjectionService.class));
                    Thread.sleep(400);
                }
                MediaProjectionManager m = (MediaProjectionManager) a.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
                mp = m.getMediaProjection(resultCode, resultData);
            }
        } catch (Exception ignored) {
        } finally {
            if (wait != null) wait.countDown();
        }
    }

    static void stop() {
        try { if (mp != null) mp.stop(); } catch (Exception ignored) { }
        mp = null;
        try { Core.app.stopService(new Intent(Core.app, ProjectionService.class)); } catch (Exception ignored) { }
    }

    /** One frame of the current screen. */
    static Bitmap shot(Context c) throws Exception {
        if (mp == null) throw new Exception("no screen-capture consent");
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) c.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        int w = dm.widthPixels, h = dm.heightPixels;
        final ImageReader ir = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
        VirtualDisplay vd = null;
        try {
            vd = mp.createVirtualDisplay("pd_shot", w, h, dm.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, ir.getSurface(), null, null);
            Image img = null;
            for (int i = 0; i < 25 && img == null; i++) { Thread.sleep(120); img = ir.acquireLatestImage(); }
            if (img == null) throw new Exception("no frame");
            Image.Plane p = img.getPlanes()[0];
            ByteBuffer buf = p.getBuffer();
            int rowPad = p.getRowStride() - p.getPixelStride() * w;
            Bitmap b = Bitmap.createBitmap(w + rowPad / p.getPixelStride(), h, Bitmap.Config.ARGB_8888);
            b.copyPixelsFromBuffer(buf);
            img.close();
            return rowPad == 0 ? b : Bitmap.createBitmap(b, 0, 0, w, h);
        } finally {
            if (vd != null) vd.release();
            ir.close();
        }
    }
}
