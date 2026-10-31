package com.phone.doctor.fm;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Taps and swipes through the accessibility service (Android 7+), in its own class for older devices. */
final class GestureApi24 {
    static boolean gesture(AccessibilityService s, int x1, int y1, int x2, int y2, long ms) throws Exception {
        Path p = new Path();
        p.moveTo(x1, y1);
        if (x1 != x2 || y1 != y2) p.lineTo(x2, y2);
        GestureDescription g = new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p, 0, ms)).build();
        final CountDownLatch l = new CountDownLatch(1);
        final boolean[] ok = {false};
        s.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription d) { ok[0] = true; l.countDown(); }

            @Override
            public void onCancelled(GestureDescription d) { l.countDown(); }
        }, null);
        l.await(5, TimeUnit.SECONDS);
        return ok[0];
    }
}
