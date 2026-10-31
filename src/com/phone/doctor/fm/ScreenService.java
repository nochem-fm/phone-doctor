package com.phone.doctor.fm;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Screen access without root: screenshots, reading the UI tree, taps/swipes/global actions. */
public class ScreenService extends AccessibilityService {
    static volatile ScreenService inst;

    @Override
    protected void onServiceConnected() {
        inst = this;
    }

    @Override
    public boolean onUnbind(Intent i) {
        inst = null;
        return super.onUnbind(i);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) { }

    @Override
    public void onInterrupt() { }

    // ------------------------------------------------------------ capture + open the chat (used by the quick-settings tile)

    /** Closes the quick-settings shade, captures the screen underneath, and opens the chat with it attached. */
    void captureAndOpen(final boolean collapseShade) {
        Core.init(this);
        new Thread(new Runnable() {
            public void run() {
                if (collapseShade) {
                    boolean done = false;
                    if (Build.VERSION.SDK_INT >= 31) done = performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
                    if (!done && Core.prefs().getBoolean("root", false)) Shell.run("cmd statusbar collapse", true, 5);
                }
                try { Thread.sleep(collapseShade ? 750 : 250); } catch (Exception ignored) { }
                String txt = dump();
                String b64 = null;
                try { b64 = Agent.jpeg(shot(), 1024, 70); } catch (Exception ignored) { }
                Core.assistB64 = b64;
                Core.assistText = txt;
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    public void run() {
                        try {
                            startActivity(new Intent(ScreenService.this, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("assist", true));
                        } catch (Exception e) {
                            Core.bringFront();
                        }
                    }
                });
            }
        }).start();
    }

    // ------------------------------------------------------------ screenshot

    Bitmap shot() throws Exception {
        if (Build.VERSION.SDK_INT < 30) throw new Exception("צילום מסך בלי root דורש Android 11 ומעלה");
        return ShotApi30.shot(this);
    }

    // ------------------------------------------------------------ reading the screen

    String dump() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "(אין חלון פעיל או שהמסך מוגן)";
        StringBuilder sb = new StringBuilder();
        sb.append("package=").append(root.getPackageName()).append("\n");
        walk(root, 0, sb);
        return sb.length() > 9000 ? sb.substring(0, 9000) + "\n...[truncated]" : sb.toString();
    }

    private static String indent(int depth) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(depth, 12); i++) b.append("  ");
        return b.toString();
    }

    private void walk(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || sb.length() > 9000 || depth > 25) return;
        CharSequence t = n.getText(), d = n.getContentDescription();
        boolean interesting = (t != null && t.length() > 0) || (d != null && d.length() > 0) || n.isClickable() || n.isCheckable() || n.isEditable();
        if (interesting && n.isVisibleToUser()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            String cls = String.valueOf(n.getClassName());
            sb.append(indent(depth)).append(cls.substring(cls.lastIndexOf('.') + 1));
            if (t != null && t.length() > 0) sb.append(" \"").append(t.length() > 120 ? t.subSequence(0, 120) : t).append("\"");
            if (d != null && d.length() > 0) sb.append(" desc=\"").append(d).append("\"");
            if (n.getViewIdResourceName() != null) sb.append(" id=").append(n.getViewIdResourceName());
            if (n.isClickable()) sb.append(" [clickable]");
            if (n.isCheckable()) sb.append(n.isChecked() ? " [checked]" : " [unchecked]");
            if (!n.isEnabled()) sb.append(" [disabled]");
            sb.append(" @").append(r.centerX()).append(",").append(r.centerY()).append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), depth + 1, sb);
    }

    // ------------------------------------------------------------ actions

    String act(String action, int x, int y, int x2, int y2, String text) {
        try {
            switch (action) {
                case "tap": return gesture(x, y, x, y, 60) ? "OK" : "FAILED: ההקשה לא בוצעה";
                case "long_press": return gesture(x, y, x, y, 700) ? "OK" : "FAILED";
                case "swipe": return gesture(x, y, x2, y2, 350) ? "OK" : "FAILED: ההחלקה לא בוצעה";
                case "back": return performGlobalAction(GLOBAL_ACTION_BACK) ? "OK" : "FAILED";
                case "home": return performGlobalAction(GLOBAL_ACTION_HOME) ? "OK" : "FAILED";
                case "recents": return performGlobalAction(GLOBAL_ACTION_RECENTS) ? "OK" : "FAILED";
                case "notifications": return performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS) ? "OK" : "FAILED";
                case "quick_settings": return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS) ? "OK" : "FAILED";
                case "click_text": return clickText(text);
                case "type": return typeText(text);
                default: return "FAILED: unknown action " + action;
            }
        } catch (Exception e) {
            return "FAILED: " + e;
        }
    }

    private boolean gesture(int x1, int y1, int x2, int y2, long ms) throws Exception {
        if (Build.VERSION.SDK_INT >= 24) return GestureApi24.gesture(this, x1, y1, x2, y2, ms);
        Core.init(this);
        if (!Core.prefs().getBoolean("root", false)) throw new Exception("הקשות והחלקות בלי root דורשות Android 7 ומעלה");
        String c = (x1 == x2 && y1 == y2 && ms < 500) ? "input tap " + x1 + " " + y1
            : "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms;
        return Shell.run(c, true, 10).exit == 0;
    }

    private String clickText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return "FAILED: אין חלון פעיל";
        List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(text);
        if (found == null || found.isEmpty()) return "FAILED: לא נמצא אלמנט עם הטקסט \"" + text + "\"";
        for (AccessibilityNodeInfo n : found) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return "OK";
        }
        return "FAILED: האלמנט נמצא אך אי אפשר ללחוץ עליו";
    }

    private String typeText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (f == null) return "FAILED: אין שדה טקסט במוקד";
        if (Build.VERSION.SDK_INT < 21) { // no ACTION_SET_TEXT: paste through the clipboard
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("t", text));
            return f.performAction(AccessibilityNodeInfo.ACTION_PASTE) ? "OK" : "FAILED";
        }
        Bundle b = new Bundle();
        b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        return f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b) ? "OK" : "FAILED";
    }
}
