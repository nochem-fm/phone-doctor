package com.phone.doctor.fm;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.view.Display;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Accessibility screenshot (Android 11+), kept in its own class so older devices never load it. */
final class ShotApi30 {
    static Bitmap shot(AccessibilityService s) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            final Bitmap[] out = new Bitmap[1];
            final int[] err = {0};
            final CountDownLatch l = new CountDownLatch(1);
            s.takeScreenshot(Display.DEFAULT_DISPLAY, s.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    try {
                        HardwareBuffer hb = r.getHardwareBuffer();
                        Bitmap b = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
                        out[0] = b.copy(Bitmap.Config.ARGB_8888, false);
                        hb.close();
                    } catch (Exception e) { err[0] = -1; }
                    l.countDown();
                }

                @Override
                public void onFailure(int code) { err[0] = code; l.countDown(); }
            });
            if (!l.await(8, TimeUnit.SECONDS)) throw new Exception("צילום המסך לא הסתיים בזמן");
            if (out[0] != null) return out[0];
            if (err[0] == 3 && attempt == 0) { Thread.sleep(1200); continue; } // too soon after the previous shot
            throw new Exception("צילום המסך נכשל (קוד " + err[0] + "). ייתכן שהמסך מוגן.");
        }
        throw new Exception("צילום המסך נכשל");
    }
}
