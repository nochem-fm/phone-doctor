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
        if (!Core.prefs().getBoolean("root", false)) return nodeGesture(x1, y1, x2, y2, ms);
        String c = (x1 == x2 && y1 == y2 && ms < 500) ? "input tap " + x1 + " " + y1
            : "input swipe " + x1 + " " + y1 + " " + x2 + " " + y2 + " " + ms;
        return Shell.run(c, true, 10).exit == 0;
    }

    // ------------------------------------------------------------ Android 4.4-6 without root: act on the element instead of the point

    /** The deepest element at (x,y) that accepts the wanted action. */
    private AccessibilityNodeInfo nodeAt(AccessibilityNodeInfo n, int x, int y, int action) {
        if (n == null) return null;
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        if (!r.contains(x, y)) return null;
        for (int i = n.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo c = nodeAt(n.getChild(i), x, y, action);
            if (c != null) return c;
        }
        boolean ok = action == AccessibilityNodeInfo.ACTION_CLICK ? n.isClickable()
            : action == AccessibilityNodeInfo.ACTION_LONG_CLICK ? n.isLongClickable() : n.isScrollable();
        return ok ? n : null;
    }

    private boolean nodeGesture(int x1, int y1, int x2, int y2, long ms) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        if (x1 == x2 && y1 == y2) {
            int a = ms >= 500 ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
            AccessibilityNodeInfo n = nodeAt(root, x1, y1, a);
            return n != null && n.performAction(a);
        }
        AccessibilityNodeInfo n = nodeAt(root, x1, y1, AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        if (n == null) return false;
        boolean forward = Math.abs(y2 - y1) >= Math.abs(x2 - x1) ? y2 < y1 : x2 < x1;   // finger up/left = content moves forward
        return n.performAction(forward ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
    }

    // ------------------------------------------------------------ app info screen actions without root

    private boolean clickAny(String[] labels, long waitMs) throws InterruptedException {
        long end = System.currentTimeMillis() + waitMs;
        while (System.currentTimeMillis() < end) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                for (String l : labels) {
                    List<AccessibilityNodeInfo> f = root.findAccessibilityNodeInfosByText(l);
                    if (f == null) continue;
                    for (AccessibilityNodeInfo n : f) {
                        CharSequence t = n.getText() != null ? n.getText() : n.getContentDescription();
                        if (t == null || !t.toString().trim().equalsIgnoreCase(l)) continue;   // exact label, so "Clear cache" never hits "Clear storage"
                        AccessibilityNodeInfo c = n;
                        while (c != null && !c.isClickable()) c = c.getParent();
                        if (c != null && c.isEnabled() && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
                        if (c != null && !c.isEnabled()) return false;
                    }
                }
            }
            Thread.sleep(300);
        }
        return false;
    }

    static final String[] FORCE_STOP = {"Force stop", "FORCE STOP", "עצירה בכפייה", "עצירה בכוח", "עצור בכוח", "הפסקה בכפייה", "כפה עצירה", "עצור"};
    static final String[] CONFIRM = {"OK", "אישור", "Force stop", "עצירה בכפייה", "עצור", "Disable app", "השבתת האפליקציה", "השבת אפליקציה", "השבתה", "Disable"};
    static final String[] STORAGE = {"Storage & cache", "Storage and cache", "Storage", "אחסון ומטמון", "אחסון וזיכרון מטמון", "אחסון"};
    static final String[] CLEAR_CACHE = {"Clear cache", "CLEAR CACHE", "ניקוי המטמון", "ניקוי מטמון", "נקה מטמון", "מחיקת המטמון", "נקה את המטמון"};
    static final String[] DISABLE = {"Disable", "DISABLE", "השבתה", "השבת", "כבה"};
    static final String[] ENABLE = {"Enable", "ENABLE", "הפעלה", "הפעל"};
    static final String[] PERMS = {"Permissions", "הרשאות", "הרשאות אפליקציה"};

    /** Opens the app's info screen and presses the buttons for the action. Returns a log for the agent. */
    String appAction(String pkg, String action) throws Exception {
        Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:" + pkg))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        Thread.sleep(1800);
        StringBuilder log = new StringBuilder("opened app info of " + pkg + "\n");
        boolean ok;
        switch (action) {
            case "force_stop":
                ok = clickAny(FORCE_STOP, 4000);
                log.append(ok ? "pressed Force stop\n" : "Force stop button not found or disabled (the app may already be stopped)\n");
                if (ok) { ok = clickAny(CONFIRM, 3000); log.append(ok ? "confirmed\n" : "no confirmation dialog\n"); ok = true; }
                break;
            case "clear_cache":
                ok = clickAny(STORAGE, 4000);
                log.append(ok ? "opened Storage\n" : "Storage entry not found\n");
                if (ok) { Thread.sleep(1200); ok = clickAny(CLEAR_CACHE, 4000); log.append(ok ? "pressed Clear cache\n" : "Clear cache not found or disabled (cache may be empty)\n"); }
                break;
            case "disable":
                ok = clickAny(DISABLE, 4000);
                log.append(ok ? "pressed Disable\n" : "Disable not found or not allowed for this app\n");
                if (ok) { ok = clickAny(CONFIRM, 3000); log.append(ok ? "confirmed\n" : "no confirmation\n"); }
                break;
            case "enable":
                ok = clickAny(ENABLE, 4000);
                log.append(ok ? "pressed Enable\n" : "Enable not found (already enabled?)\n");
                break;
            case "open_permissions":
                ok = clickAny(PERMS, 4000);
                log.append(ok ? "opened Permissions\n" : "Permissions entry not found\n");
                return (ok ? "OK " : "FAILED ") + log + "screen now:\n" + dump();
            default:
                return "FAILED unknown action " + action;
        }
        Thread.sleep(800);
        String screen = dump();
        performGlobalAction(GLOBAL_ACTION_BACK);
        return (ok ? "OK " : "FAILED ") + log + (ok ? "" : "screen was:\n" + screen);
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
