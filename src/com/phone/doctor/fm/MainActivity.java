package com.phone.doctor.fm;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.pm.ApplicationInfo;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PermissionInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.LinearGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    int BG, SURF, SURF2, TXT, DIM, ACC, GOOD, ERR, ON_ACC;
    boolean light;
    static final int SEND = 0xFFD96570; // same red as the title gradient
    static final int M = -1, W = -2;
    static final String DEF_MODEL = "gemini-2.5-flash";

    SharedPreferences prefs;
    Store store;
    boolean rootOk;
    String screen = "";
    boolean resumed, needRefresh, expandLive, replaying;
    AlertDialog pendingDlg;

    // setup state
    boolean onSetup, flowActive, waiting;
    String rootBusy;
    final Set<String> tried = new HashSet<String>();
    final TextView[] rowIcon = new TextView[8], rowSub = new TextView[8], rowSkip = new TextView[8];
    final Set<String> skipped = new HashSet<String>();
    static final String[][] ROWS = {
        {"גישת Root", "בדיקות עומק ותיקונים"},
        {"הרשאות רגילות", "טלפון, מיקום, Bluetooth, מצלמה, מיקרופון, התראות, מדיה"},
        {"גישה לכל הקבצים", "בדיקת אחסון וקבצי מערכת"},
        {"גישה לנתוני שימוש", "איזו אפליקציה צורכת משאבים"},
        {"הצגה מעל אפליקציות", "חלונות אישור בזמן אמת"},
        {"ללא הגבלת סוללה", "שהבדיקות לא ייעצרו ברקע"},
        {"שירות נגישות", "לראות ולצלם את המסך גם בלי root"},
        {"עוזר דיגיטלי", "לחיצה ארוכה על Home מצרפת את המסך שלך"}};

    // chat state
    LinearLayout chat;
    ScrollView scroll;
    View greeting;
    EditText input;
    TextView sendBtn, queueBtn, liveHead;
    Glyph micBtn;
    Voice voice;
    String micPrefix = "";
    LinearLayout liveBody;
    JSONArray liveSteps;
    String liveSig = "";

    // ================================================================= lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("s", 0);
        Core.init(this);
        store = Core.store;
        rootOk = prefs.getBoolean("root", false);
        for (String x : prefs.getString("skipped", "").split(",")) if (!x.isEmpty()) skipped.add(x);
        applyTheme();
        // RTL paragraphs whenever the text has Hebrew, even if it starts with an English word (Gemini, root, API...)
        getWindow().getDecorView().setTextDirection(View.TEXT_DIRECTION_ANY_RTL);
        if (b == null) freshStart();   // opening the app starts on the main screen, not inside the last chat
        if (!termsOk()) showTerms(false);
        else if (prefs.getBoolean("setup", false)) showChat();
        else showSetup(true);
        handleIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Core.ui = this;
        if (stoppedAt > 0 && System.currentTimeMillis() - stoppedAt > 10 * 60000L && screen.equals("chat") && freshStart()) showChat();
        else if (needRefresh && screen.equals("chat")) showChat();
        stoppedAt = 0;
    }

    @Override
    protected void onStop() {
        super.onStop();
        needRefresh = true;
        stoppedAt = System.currentTimeMillis();
        if (pendingDlg != null) {
            pendingDlg.dismiss();
            pendingDlg = null;
            if (Core.pending != null) Core.pending.shown = false;
        }
        if (Core.ui == this) Core.ui = null;
    }

    @Override
    protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        setIntent(i);
        handleIntent(i);
    }

    long stoppedAt;

    /** Saves the open chat and clears the screen to a new one, unless the agent is still working on it. */
    boolean freshStart() {
        if (Core.running || Core.pending != null || Core.uiLog.length() == 0) return false;
        Core.saveChat();
        Core.agent = null;
        Core.curId = null;
        Core.uiLog = new JSONArray();
        Core.steps = new JSONArray();
        expandLive = false;
        return true;
    }

    void addTile() {
        if (Build.VERSION.SDK_INT < 24) { toast("אריח הגדרות מהירות נתמך מאנדרואיד 7 ומעלה"); return; }
        if (Build.VERSION.SDK_INT >= 33 && Compat.addTile(this)) return;
        toast("משוך את ההגדרות המהירות, לחץ על עריכה וגרור פנימה את האריח \"צלם ושאל\"");
    }

    void handleIntent(Intent i) {
        if (!termsOk()) return;
        if (i != null && i.getBooleanExtra("needA11y", false)) {
            i.removeExtra("needA11y");
            if (canWriteSecure()) enableAccessibility();
            else {
                toast("הפעל את Phone Doctor בשירותי הנגישות כדי שהאריח יוכל לצלם");
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
            }
        }
        if (i != null && i.hasExtra("prefill") && prefs.getBoolean("setup", false)) {
            final String pre = i.getStringExtra("prefill");
            i.removeExtra("prefill");
            if (!screen.equals("chat")) showChat();
            if (input != null && pre != null) {
                input.setText(pre);
                input.setSelection(input.getText().length());
                toast("התוצאות מוכנות בשורה. לחץ שליחה כדי שהסוכן ינתח אותן");
            }
        }
        if (i != null && i.getBooleanExtra("assist", false) && prefs.getBoolean("setup", false)) {
            i.removeExtra("assist");
            if (!Core.running && Core.uiLog.length() > 0) newChat(true);   // a capture starts its own chat
            else showChat();
            toast("צילום המסך נקלט. כתוב מה הבעיה");
            if (input != null) input.requestFocus();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        Core.ui = this;
        if (Core.pending != null) showPending();
        if (onSetup) {
            refreshRows();
            if (flowActive && waiting) {
                waiting = false;
                nextStep();
            }
        }
    }

    @Override
    public void onConfigurationChanged(Configuration nc) {
        super.onConfigurationChanged(nc);
        if (prefs.getString("theme", "system").equals("system")) {
            applyTheme();
            refreshScreen();
        }
    }

    void applyTheme() {
        String t = prefs.getString("theme", "system");
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        light = t.equals("light") || (t.equals("system") && !night);
        if (light) {
            BG = 0xFFFFFFFF; SURF = 0xFFF0F4F9; SURF2 = 0xFFE1E8F2; TXT = 0xFF1F1F1F; DIM = 0xFF5F6368;
            ACC = 0xFFC2364B; GOOD = 0xFF188038; ERR = 0xFFB3261E; ON_ACC = 0xFFFFFFFF;
        } else {
            BG = 0xFF131314; SURF = 0xFF1E1F20; SURF2 = 0xFF282A2C; TXT = 0xFFE3E3E3; DIM = 0xFF9AA0A6;
            ACC = 0xFFD96570; GOOD = 0xFF81C995; ERR = 0xFFF28B82; ON_ACC = 0xFFFFFFFF;
        }
        if (Build.VERSION.SDK_INT >= 21)
            setTheme(light ? android.R.style.Theme_Material_Light_NoActionBar : android.R.style.Theme_Material_NoActionBar);
        else
            setTheme(light ? android.R.style.Theme_Holo_Light_NoActionBar : android.R.style.Theme_Holo_NoActionBar);
        getWindow().setBackgroundDrawable(new ColorDrawable(BG));
        if (Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        }
        if (Build.VERSION.SDK_INT >= 23) {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            int mask = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            d.setSystemUiVisibility(light ? (f | mask) : (f & ~mask));
        }
    }

    int dlg() {
        int own = getResources().getIdentifier(light ? "DlgLight" : "DlgDark", "style", getPackageName());
        if (own != 0) return own;
        if (Build.VERSION.SDK_INT < 21) return light ? AlertDialog.THEME_HOLO_LIGHT : AlertDialog.THEME_HOLO_DARK;
        return light ? android.R.style.Theme_Material_Light_Dialog_Alert : android.R.style.Theme_Material_Dialog_Alert;
    }

    void refreshScreen() {
        if (screen.equals("settings")) showSettings();
        else if (screen.equals("history")) showHistory();
        else if (screen.equals("journal")) showJournal();
        else if (screen.equals("setup")) showSetup(false);
        else if (screen.equals("terms")) showTerms(termsReadOnly);
        else showChat();
    }

    void pickTheme() {
        final String[] vals = {"system", "light", "dark"};
        String[] labels = {"לפי המערכת", "בהיר", "כהה"};
        int cur = Arrays.asList(vals).indexOf(prefs.getString("theme", "system"));
        new AlertDialog.Builder(this, dlg()).setTitle("ערכת נושא")
            .setSingleChoiceItems(labels, cur, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    prefs.edit().putString("theme", vals[w]).apply();
                    d.dismiss();
                    applyTheme();
                    refreshScreen();
                }
            }).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (voice != null) voice.cancel();
        resumed = false;
        if (!Core.running) Core.saveChat();
    }

    @Override
    public void onBackPressed() {
        if (screen.equals("history") || screen.equals("settings")) showChat();
        else if (screen.equals("journal")) showSettings();
        else if (screen.equals("terms") && termsReadOnly) showSettings();
        else super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int code, Intent data) {
        super.onActivityResult(req, code, data);
        if (req == Projection.REQ) {
            if (code != RESULT_OK) toast("בלי האישור הסוכן לא יכול לצלם את המסך");
            Projection.onResult(this, code, data);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == REQ_MIC) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) onMic();
            else toast("בלי הרשאת מיקרופון אי אפשר להכתיב");
            return;
        }
        if (onSetup) {
            refreshRows();
            if (flowActive) nextStep();
        }
    }

    // ================================================================= small UI helpers

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + .5f); }

    TextView tv(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(Build.VERSION.SDK_INT < 24 ? safeGlyphs(s) : s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    /** Old Android fonts lack many symbols and draw nothing for them; swap those for symbols that exist. */
    static final java.util.HashMap<Character, Boolean> GLYPH_OK = new java.util.HashMap<Character, Boolean>();
    static final android.graphics.Paint GLYPH_PAINT = new android.graphics.Paint();

    static String safeGlyphs(String s) {
        if (s == null) return null;
        StringBuilder out = null;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 0x2190 || ch > 0x2BFF) { if (out != null) out.append(ch); continue; }
            Boolean ok = GLYPH_OK.get(ch);
            if (ok == null) {
                android.graphics.Rect r = new android.graphics.Rect();
                GLYPH_PAINT.setTextSize(48);
                GLYPH_PAINT.getTextBounds(String.valueOf(ch), 0, 1, r);
                ok = r.width() > 0 && r.height() > 0;
                GLYPH_OK.put(ch, ok);
            }
            if (ok) { if (out != null) out.append(ch); continue; }
            if (out == null) out = new StringBuilder(s.substring(0, i));
            out.append(glyphFallback(ch));
        }
        return out == null ? s : out.toString();
    }

    static String glyphFallback(char ch) {
        switch (ch) {
            case '\u2713': return "\u221A";            // ✓ -> √
            case '\u2717': case '\u2715': return "\u00D7"; // ✗ ✕ -> ×
            case '\u2630': case '\u25A4': return "\u2261"; // ☰ ▤ -> ≡
            case '\u2699': return "*";
            case '\u2726': return "\u2022";            // ✦ -> •
            case '\u270E': return "\u203A";            // ✎ -> ›
            case '\u2315': return "?";
            case '\u25A3': case '\u25A0': return "\u25A0";
            case '\u25C9': case '\u25CF': return "\u2022";
            case '\u25CB': return "o";
            case '\u25B6': case '\u25C2': return ch == '\u25B6' ? ">" : "<";
            case '\u25BE': return "v";
            case '\u26A0': return "!";
            case '\u21A9': return "\u2190";
            case '\u2191': return "^";
            case '\u2192': return ">";
            default: return "\u2022";
        }
    }

    GradientDrawable rr(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    void grad(TextView t) {
        float w = Math.max(1f, t.getPaint().measureText(t.getText().toString()));
        t.getPaint().setShader(new LinearGradient(0, 0, w, 0,
                new int[]{0xFF4285F4, 0xFF9B72CB, 0xFFD96570}, null, Shader.TileMode.CLAMP));
    }

    LinearLayout vert() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    LinearLayout.LayoutParams lp(int w, int h, float weight) { return new LinearLayout.LayoutParams(w, h, weight); }

    void show(View v) {
        v.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        setContentView(v);
    }

    void click(View v, final Runnable r) {
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { r.run(); }
        });
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    TextView icon(String glyph, int color) {
        TextView t = tv(glyph, 24, color);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        return t;
    }

    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("url", url));
            toast("לא נמצא דפדפן. הכתובת הועתקה: " + url);
        }
    }

    View glyph(int type, int color) { return new Glyph(this, type, color, dp(48)); }

    View topBar(String title) {
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), dp(12), dp(8));
        View back = glyph(Glyph.BACK, TXT);
        click(back, new Runnable() { public void run() { showChat(); } });
        TextView t = tv(title, 20, TXT);
        bar.addView(back);
        bar.addView(t, lp(0, W, 1f));
        return bar;
    }

    // ================================================================= setup / permissions

    static final int TERMS_VER = 1;
    boolean termsReadOnly;

    boolean termsOk() { return prefs.getBoolean("terms" + TERMS_VER, false); }

    /** First screen of the app: a clear risk warning that has to be accepted before anything else. */
    void showTerms(final boolean readOnly) {
        screen = "terms";
        termsReadOnly = readOnly;
        onSetup = false;
        LinearLayout col = vert();
        col.setBackgroundColor(BG);
        boolean small = getResources().getConfiguration().screenHeightDp < 600;
        col.setPadding(dp(16), dp(small ? 8 : 20), dp(16), dp(small ? 6 : 12));

        // header scrolls together with the text, so on small screens the text gets the room
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER);
        TextView warn = tv("\u26A0", small ? 22 : 30, ERR);
        warn.setPadding(0, 0, 0, 0);
        TextView title = tv("אזהרה: האפליקציה הזו מסוכנת", small ? 17 : 21, ERR);
        title.setTypeface(null, Typeface.BOLD);
        head.addView(warn);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(W, W);
        tl.setMarginStart(dp(8));
        head.addView(title, tl);
        TextView sub = tv("קרא בעיון לפני שימוש. המשך מהווה הסכמה לכל האמור כאן.", 13, DIM);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, dp(4), 0, dp(10));

        String[][] items = {
            {"השימוש באחריותך בלבד", "השימוש באפליקציה הוא באחריות המשתמש בלבד. המפתח (@נוחעם-FM), כל מי שהשתתף בפיתוח ובהפצה ומי שמפיץ אותה, אינם לוקחים כל אחריות לנזק, אובדן מידע, תקלה, עלות או תוצאה כלשהי, ישירה או עקיפה, ולא יהיו חייבים בפיצוי. האפליקציה ניתנת כפי שהיא (AS IS), ללא אחריות מכל סוג."},
            {"היא יכולה לפגוע בטלפון", "האפליקציה מריצה פקודות מערכת ומשנה הרשאות והגדרות. עם root, שירות נגישות או מנהל מכשיר (Device Owner) היא יכולה למחוק נתונים, לשבש אפליקציות, לגרום לאי-יציבות ואף למכשיר שלא עולה, ולפגוע באחריות היצרן."},
            {"הבינה המלאכותית יכולה לטעות", "Gemini עלול לטעות, להציע או לבצע פעולה שגויה או מזיקה, ולא להבין את המצב. אל תאשר פעולה שאינך מבין. במצב אוטונומי הסוכן מבצע שינויים רגילים בלי לשאול על כל שלב."},
            {"פרטיות ומידע רגיש", "פלט פקודות, טקסט המסך וצילומי מסך נשלחים לשרתי Google (Gemini) לניתוח ועלולים לכלול מידע אישי: הודעות, סיסמאות, פרטי תשלום ועוד. אל תפעיל את האפליקציה כשמידע רגיש מוצג על המסך. מפתח ה-API נשמר במכשיר ללא הצפנה."},
            {"הרשאות רחבות", "root, שירות הנגישות ומנהל המכשיר נותנים שליטה רחבה על המכשיר. הפעל אותם רק אם אתה מבין מה הם עושים. אפשר לבטל אותם בכל עת בהגדרות המערכת."},
            {"השתמש בזהירות", "קרא כל בקשת אישור לפני שאתה לוחץ. אל תאשר פעולה בלי לחשוב ובלי להבין מה היא עושה, ואם יש ספק עדיף לדחות ולשאול. מומלץ לגבות את המכשיר לפני שימוש, ולהשתמש רק במכשיר שלך או באישור מפורש של בעליו."}};
        ScrollView sv = new ScrollView(this);
        LinearLayout list = vert();
        sv.addView(list);
        list.addView(head, new LinearLayout.LayoutParams(M, W));
        list.addView(sub, new LinearLayout.LayoutParams(M, W));
        for (String[] it : items) {
            LinearLayout c = vert();
            c.setBackground(rr(SURF, 16));
            c.setPadding(dp(16), dp(12), dp(16), dp(12));
            TextView h = tv(it[0], 15, TXT);
            h.setTypeface(null, Typeface.BOLD);
            TextView b = tv(it[1], 13, DIM);
            b.setPadding(0, dp(4), 0, 0);
            b.setLineSpacing(dp(2), 1f);
            c.addView(h);
            c.addView(b);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
            l.bottomMargin = dp(8);
            list.addView(c, l);
        }
        col.addView(sv, lp(M, 0, 1f));

        if (readOnly) {
            TextView close = tv("סגור", 16, ON_ACC);
            close.setTypeface(null, Typeface.BOLD);
            close.setGravity(Gravity.CENTER);
            close.setBackground(rr(ACC, 28));
            close.setPadding(0, dp(small ? 9 : 12), 0, dp(small ? 9 : 12));
            click(close, new Runnable() { public void run() { showSettings(); } });
            col.addView(close, new LinearLayout.LayoutParams(M, W));
        } else {
            final CheckBox cb = new CheckBox(this);
            cb.setText("קראתי והבנתי. האחריות עליי בלבד.");
            cb.setTextColor(TXT);
            cb.setTextSize(small ? 13 : 14);
            cb.setPadding(0, 0, 0, 0);
            col.addView(cb, new LinearLayout.LayoutParams(M, W));
            final TextView ok = tv("אני מסכים/ה והמשך", 15, ON_ACC);
            ok.setTypeface(null, Typeface.BOLD);
            ok.setGravity(Gravity.CENTER);
            ok.setBackground(rr(ACC, 24));
            ok.setPadding(dp(8), dp(small ? 9 : 12), dp(8), dp(small ? 9 : 12));
            ok.setAlpha(0.4f);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean v) { ok.setAlpha(v ? 1f : 0.4f); }
            });
            click(ok, new Runnable() {
                public void run() {
                    if (!cb.isChecked()) { toast("סמן שקראת והבנת כדי להמשיך"); return; }
                    prefs.edit().putBoolean("terms" + TERMS_VER, true).apply();
                    if (prefs.getBoolean("setup", false)) showChat(); else showSetup(true);
                    handleIntent(getIntent());
                }
            });
            TextView exit = tv("יציאה", 14, DIM);
            exit.setGravity(Gravity.CENTER);
            exit.setPadding(dp(16), dp(small ? 9 : 12), dp(16), dp(small ? 9 : 12));
            click(exit, new Runnable() { public void run() { finish(); } });
            LinearLayout btns = new LinearLayout(this);       // one row: [continue][exit]
            btns.setGravity(Gravity.CENTER_VERTICAL);
            btns.addView(ok, lp(0, W, 1f));
            btns.addView(exit, new LinearLayout.LayoutParams(W, W));
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(M, W);
            bl.topMargin = dp(4);
            col.addView(btns, bl);
        }
        show(col);
    }

    void showSetup(boolean auto) {
        int y = keepScroll("setup");
        onSetup = true;
        screen = "setup";
        LinearLayout col = vert();
        col.setBackgroundColor(BG);
        col.setPadding(dp(24), dp(56), dp(24), dp(24));

        TextView h = tv("ברוך הבא", 34, TXT);
        h.setTypeface(null, Typeface.BOLD);
        grad(h);
        TextView sub = tv("כדי לבדוק ולתקן את הטלפון אני צריך כמה הרשאות. לחץ על הרשאה כדי לאשר אותה, או \"אשר הכל\" כדי לעבור על כולן.", 15, DIM);
        sub.setPadding(0, dp(8), 0, dp(24));
        col.addView(h);
        col.addView(sub);

        ScrollView sv = pageScroll(y);
        LinearLayout list = vert();
        sv.addView(list);
        for (int i = 0; i < ROWS.length; i++) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(rr(SURF, 16));
            r.setPadding(dp(16), dp(14), dp(16), dp(14));
            rowIcon[i] = tv("\u25CB", 20, DIM);
            rowIcon[i].setWidth(dp(36));
            LinearLayout txt = vert();
            txt.addView(tv(ROWS[i][0], 16, TXT));
            rowSub[i] = tv(ROWS[i][1], 12, DIM);
            txt.addView(rowSub[i]);
            final int idx = i;
            rowSkip[i] = tv("דלג", 12, DIM);
            rowSkip[i].setPadding(dp(12), dp(8), dp(4), dp(8));
            click(rowSkip[i], new Runnable() { public void run() { toggleSkip(idx); } });
            r.addView(rowIcon[i]);
            r.addView(txt, lp(0, W, 1f));
            r.addView(rowSkip[i]);
            click(r, new Runnable() { public void run() { grantOne(idx); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
            l.bottomMargin = dp(8);
            list.addView(r, l);
        }
        col.addView(sv, lp(M, 0, 1f));

        TextView go = tv("אשר הכל", 16, ON_ACC);
        go.setTypeface(null, Typeface.BOLD);
        go.setGravity(Gravity.CENTER);
        go.setBackground(rr(ACC, 28));
        go.setPadding(0, dp(16), 0, dp(16));
        click(go, new Runnable() {
            public void run() {
                flowActive = true;
                tried.clear();
                nextStep();
            }
        });
        TextView skip = tv("המשך", 14, DIM);
        skip.setGravity(Gravity.CENTER);
        skip.setPadding(0, dp(16), 0, dp(4));
        click(skip, new Runnable() { public void run() { finishSetup(); } });
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(M, W);
        glp.topMargin = dp(12);
        col.addView(go, glp);
        col.addView(skip, new LinearLayout.LayoutParams(M, W));
        show(col);
        refreshRows();
    }

    /** One permission at a time. With root it is granted silently; without root its own system screen opens. */
    void grantOne(final int i) {
        if (flowActive) return;
        Uri u = Uri.parse("package:" + getPackageName());
        final String pkg = getPackageName();
        String sh = null;
        Intent it = null;
        switch (i) {
            case 0:
                if (rootOk) { toast("root כבר פעיל"); return; }
                doRoot();
                return;
            case 1: {
                List<String> m = missingRuntime();
                if (m.isEmpty()) { toast("כבר ניתן"); return; }
                if (rootOk) { StringBuilder b = new StringBuilder(); for (String x : m) b.append("pm grant ").append(pkg).append(' ').append(x).append(" 2>/dev/null;"); sh = b.toString(); }
                else if (Build.VERSION.SDK_INT >= 23) { requestPermissions(m.toArray(new String[0]), 7); return; }
                break;
            }
            case 2: if (filesOk()) { toast("כבר ניתן"); return; } if (rootOk) sh = "appops set " + pkg + " MANAGE_EXTERNAL_STORAGE allow"; else it = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, u); break;
            case 3: if (hasUsage()) { toast("כבר ניתן"); return; } if (rootOk) sh = "appops set " + pkg + " GET_USAGE_STATS allow"; else it = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, u); break;
            case 4: if (overlayOk()) { toast("כבר ניתן"); return; } if (rootOk) sh = "appops set " + pkg + " SYSTEM_ALERT_WINDOW allow"; else it = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, u); break;
            case 5: if (batteryOk()) { toast("כבר ניתן"); return; } if (rootOk) sh = "dumpsys deviceidle whitelist +" + pkg; else it = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u); break;
            case 6:
                if (isAccessOn()) { toast("כבר פעיל"); return; }
                if (!canWriteSecure() && rootOk) sh = "pm grant " + pkg + " android.permission.WRITE_SECURE_SETTINGS";
                else if (canWriteSecure()) { enableAccessibility(); refreshLater(); return; }
                else {
                    toast("הפעל את Phone Doctor ברשימת שירותי הנגישות. אם האפשרות אפורה: מידע על האפליקציה > ⋮ > אפשר הגדרות מוגבלות");
                    it = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                }
                break;
            case 7:
                if (!assistSupported()) { toast("נתמך מאנדרואיד 6 ומעלה"); return; }
                if (isAssistant()) { toast("כבר מוגדר"); return; }
                if (setAssistantRoot(new Runnable() { public void run() { refreshRows(); } })) return;
                openAssistSettings();
                return;
        }
        if (it != null) {
            try { startActivity(it); } catch (Exception e) { toast("המסך הזה לא קיים במכשיר"); }
            return;
        }
        if (sh != null) {
            final String cmd = sh;
            final boolean thenAccess = i == 6;
            new Thread(new Runnable() {
                public void run() {
                    Shell.run(cmd, true, 30);
                    runOnUiThread(new Runnable() {
                        public void run() {
                            if (thenAccess && canWriteSecure()) enableAccessibility();
                            refreshLater();
                        }
                    });
                }
            }).start();
        }
    }

    void refreshLater() {
        refreshRows();
        if (rowIcon[0] != null) rowIcon[0].postDelayed(new Runnable() { public void run() { refreshRows(); } }, 800);
    }

    List<String> missingRuntime() {
        List<String> out = new ArrayList<String>();
        try {
            PackageManager pm = getPackageManager();
            PackageInfo pi = pm.getPackageInfo(getPackageName(), PackageManager.GET_PERMISSIONS);
            for (String p : pi.requestedPermissions) {
                if (p.equals("android.permission.WRITE_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT >= 30) continue;
                if (p.equals("android.permission.READ_EXTERNAL_STORAGE") && Build.VERSION.SDK_INT >= 33) continue;
                try {
                    PermissionInfo info = pm.getPermissionInfo(p, 0);
                    boolean dangerous = (info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_DANGEROUS;
                    if (dangerous && !has(p)) out.add(p);
                } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
        return out;
    }

    boolean has(String perm) {
        return getPackageManager().checkPermission(perm, getPackageName()) == PackageManager.PERMISSION_GRANTED;
    }

    boolean hasUsage() {
        if (Build.VERSION.SDK_INT < 21) return true;
        AppOpsManager a = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
        return a.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    boolean overlayOk() { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this); }

    boolean filesOk() { return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager(); }

    boolean batteryOk() {
        return Build.VERSION.SDK_INT < 23 || ((PowerManager) getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName());
    }

    void refreshRows() {
        if (!onSetup || rowIcon[0] == null) return;
        boolean[] ok = {rootOk, missingRuntime().isEmpty(), filesOk(), hasUsage(), overlayOk(), batteryOk(), isAccessOn(), !assistSupported() || isAssistant()};
        for (int i = 0; i < ok.length; i++) {
            boolean sk = skipped.contains(String.valueOf(i)) && !ok[i];
            rowIcon[i].setText(safeGlyphs(ok[i] ? "\u2713" : sk ? "\u2014" : "\u25CB"));
            rowIcon[i].setTextColor(ok[i] ? GOOD : DIM);
            rowSub[i].setText(i == 0 && rootBusy != null ? rootBusy : ROWS[i][1]);
            rowSkip[i].setVisibility(ok[i] ? View.INVISIBLE : View.VISIBLE);
            rowSkip[i].setText(sk ? "ביטול דילוג" : "דלג");
        }
    }

    boolean tryStep(String key, boolean ok, Intent i) {
        if (ok || !tried.add(key)) return false;
        waiting = true;
        try { startActivity(i); } catch (Exception e) { waiting = false; return false; }
        return true;
    }

    void toggleSkip(int i) {
        String k = String.valueOf(i);
        if (!skipped.remove(k)) skipped.add(k);
        prefs.edit().putString("skipped", android.text.TextUtils.join(",", skipped)).apply();
        refreshRows();
    }

    /** A root manager (Magisk, KernelSU, APatch) is installed, so asking for root makes sense. */
    boolean suPresent() {
        String[] files = {"/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/vendor/bin/su",
            "/debug_ramdisk/su", "/data/local/bin/su", "/data/local/xbin/su", "/system/sbin/su"};
        for (String f : files) if (new File(f).exists()) return true;
        PackageManager pm = getPackageManager();
        String[] pk = {"com.topjohnwu.magisk", "io.github.huskydg.magisk", "io.github.vvb2060.magisk", "com.topjohnwu.magisk.canary",
            "me.weishu.kernelsu", "me.bmax.apatch", "com.rifsxd.ksunext", "eu.chainfire.supersu"};
        for (String p : pk) { try { pm.getPackageInfo(p, 0); return true; } catch (Exception ignored) { } }
        try {
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                String l = String.valueOf(ai.loadLabel(pm)).toLowerCase();
                if (l.equals("magisk") || l.equals("kitsune mask") || l.startsWith("kernelsu") || l.equals("apatch")) return true;
            }
        } catch (Exception ignored) { }
        return prefs.getBoolean("root", false);
    }

    /**
     * Order: root first (only if Magisk exists; it then grants everything silently), then, only for what is still
     * missing, the normal dialogs and settings screens. Anything the user skipped is left alone.
     */
    void nextStep() {
        Uri u = Uri.parse("package:" + getPackageName());
        if (tried.add("root")) {
            if (!skipped.contains("0") && suPresent()) { doRoot(); return; }
            if (!suPresent()) { rootBusy = "לא זוהה Magisk במכשיר, לכן לא מבקשים root"; refreshRows(); }
        }
        if (!skipped.contains("1") && tried.add("rt")) {
            List<String> m = missingRuntime();
            if (!m.isEmpty() && Build.VERSION.SDK_INT >= 23) { requestPermissions(m.toArray(new String[0]), 7); return; }
        }
        if (!skipped.contains("2") && tryStep("files", filesOk(), new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, u))) return;
        if (!skipped.contains("3") && tryStep("usage", hasUsage(), new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, u))) return;
        if (!skipped.contains("4") && tryStep("overlay", overlayOk(), new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, u))) return;
        if (!skipped.contains("5") && tryStep("battery", batteryOk(), new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, u))) return;
        if (!skipped.contains("6") && !isAccessOn()) {
            if (canWriteSecure()) enableAccessibility();
            else if (!tried.contains("access")) {
                toast("הפעל את Phone Doctor ברשימת שירותי הנגישות. אם האפשרות אפורה: מידע על האפליקציה > ⋮ > אפשר הגדרות מוגבלות");
                if (tryStep("access", false, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return;
            }
        }
        if (!skipped.contains("7") && assistSupported() && !isAssistant() && tried.add("assist")) {
            if (setAssistantRoot(new Runnable() { public void run() { nextStep(); } })) return;
            waiting = true;
            openAssistSettings();
            return;
        }
        finishSetup();
    }

    // ---- advanced: root on demand, ADB, device owner

    String[] adbList() {
        String p = getPackageName();
        return new String[]{
            "pm grant " + p + " android.permission.WRITE_SECURE_SETTINGS",
            "pm grant " + p + " android.permission.DUMP",
            "pm grant " + p + " android.permission.READ_LOGS",
            "dpm set-device-owner " + p + "/.AdminReceiver"};
    }

    /** One short Hebrew explanation per command, same order as adbList(). */
    String[] adbInfo() {
        return new String[]{
            "מאפשר לאפליקציה להפעיל לבד את שירות הנגישות והעוזר הדיגיטלי",
            "מאפשר קריאת נתוני מערכת (dumpsys) לאבחון מדויק",
            "מאפשר קריאת לוגים (logcat) כדי לאתר קריסות ושגיאות",
            "מגדיר Device Owner: נותן ושולל הרשאות לכל אפליקציה בלי root. עובד רק כשאין חשבונות במכשיר"};
    }

    /** For every command in adbList(): does the app already have what it grants? */
    boolean[] adbState() {
        return new boolean[]{
            canWriteSecure(),
            has("android.permission.DUMP"),
            has("android.permission.READ_LOGS"),
            isDeviceOwner()};
    }

    boolean adbOpen;

    /** Collapsible block: every ADB command on its own row with its own "copy" and "run with root" buttons. */
    View adbBlock() {
        final LinearLayout wrap = vert();
        final String[] cmds = adbList(), infos = adbInfo();
        final boolean[] st = adbState();
        int done = 0;
        for (boolean b : st) if (b) done++;
        final String title = "פקודות ADB: " + done + " מתוך " + st.length + " כבר ניתנו";
        final TextView head = tv("", 15, done == st.length ? GOOD : TXT);
        head.setPadding(0, dp(10), 0, dp(10));
        final LinearLayout body = vert();
        body.setVisibility(adbOpen ? View.VISIBLE : View.GONE);
        head.setText(safeGlyphs(title + "  " + (adbOpen ? "\u25BE" : "\u25C2")));
        click(head, new Runnable() {
            public void run() {
                adbOpen = !adbOpen;
                body.setVisibility(adbOpen ? View.VISIBLE : View.GONE);
                head.setText(safeGlyphs(title + "  " + (adbOpen ? "\u25BE" : "\u25C2")));
            }
        });
        for (int ci = 0; ci < cmds.length; ci++) {
            final String cmd = cmds[ci];
            boolean granted = st[ci];
            LinearLayout r = vert();
            r.setPadding(0, dp(4), 0, dp(10));
            TextView badge = tv(granted ? "\u2713 כבר ניתן, אין צורך להריץ" : "\u2717 עדיין לא ניתן", 12, granted ? GOOD : ERR);
            badge.setTypeface(null, Typeface.BOLD);
            TextView cap = tv(infos[ci], 13, TXT);
            cap.setTypeface(null, Typeface.BOLD);
            cap.setPadding(0, dp(2), 0, dp(4));
            r.addView(badge);
            r.addView(cap);
            View cb = box("adb shell " + cmd, true, TXT);
            if (granted) cb.setAlpha(0.55f);
            r.addView(cb);
            LinearLayout btns = new LinearLayout(this);
            TextView copy = pillBtn("העתק", new Runnable() { public void run() { copyOne(cmd); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(W, W);
            l.setMarginEnd(dp(8));
            btns.addView(copy, l);
            if (!granted) {
                TextView run = pillBtn(rootOk ? "הרץ ב-root" : "הרץ ב-root (אין root)", new Runnable() { public void run() { runRoot(cmd); } });
                btns.addView(run, new LinearLayout.LayoutParams(W, W));
            }
            r.addView(btns);
            body.addView(r);
        }
        wrap.addView(head);
        wrap.addView(body);
        return wrap;
    }

    void copyOne(String cmd) {
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("adb", "adb shell " + cmd));
        toast("הועתק");
    }

    void runRoot(final String cmd) {
        if (!rootOk) { toast("אין root. העתק והרץ דרך ADB מהמחשב"); return; }
        toast("מריץ…");
        new Thread(new Runnable() {
            public void run() {
                final Shell.Result r = Shell.run(cmd + " 2>&1", true, 30);
                final String out = r.out.trim();
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast((r.exit == 0 ? "בוצע" : "הפקודה נכשלה") + (out.isEmpty() ? "" : "\n" + (out.length() > 200 ? out.substring(0, 200) : out)));
                        showSettings();
                    }
                });
            }
        }).start();
    }

    /** Removes Phone Doctor as device owner / device admin. */
    void removeAdmin() {
        DevicePolicyManager dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        ComponentName c = new ComponentName(this, AdminReceiver.class);
        try { if (dpm.isDeviceOwnerApp(getPackageName())) dpm.clearDeviceOwnerApp(getPackageName()); } catch (Exception ignored) { }
        try { if (dpm.isAdminActive(c)) dpm.removeActiveAdmin(c); } catch (Exception ignored) { }
        if (!isDeviceOwner() && !isAdminActive()) { toast("מנהל המכשיר הוסר"); showSettings(); return; }
        if (!rootOk) { toast("ההסרה נכשלה. אפשר להסיר ידנית: הגדרות > אבטחה > אפליקציות מנהל מכשיר"); return; }
        final String comp = getPackageName() + "/.AdminReceiver";
        new Thread(new Runnable() {
            public void run() {
                Shell.run("dpm remove-active-admin " + comp + " 2>&1", true, 20);
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(!isDeviceOwner() && !isAdminActive() ? "מנהל המכשיר הוסר" : "ההסרה נכשלה. נסה להסיר ידנית בהגדרות המערכת");
                        showSettings();
                    }
                });
            }
        }).start();
    }

    // ---- keyboard: make sure tapping a text field always opens it (some devices/ROMs do not)

    /** Closes the keyboard after a message is sent, so the answer has the whole screen. */
    void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null && input != null) imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
            if (input != null) input.clearFocus();
        } catch (Exception ignored) { }
    }

    void showIme(final EditText e) {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(e, InputMethodManager.SHOW_IMPLICIT);
        if (Build.VERSION.SDK_INT >= 30) Compat.showIme30(e);
    }

    void imeOnTouch(EditText e) {
        e.setFocusable(true);
        e.setFocusableInTouchMode(true);
        if (Build.VERSION.SDK_INT >= 21) e.setShowSoftInputOnFocus(true);
        e.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(final View v, MotionEvent ev) {
                if (ev.getAction() == MotionEvent.ACTION_UP) {
                    v.requestFocus();
                    v.postDelayed(new Runnable() { public void run() { showIme((EditText) v); } }, 80);
                }
                return false;
            }
        });
    }

    boolean isDeviceOwner() {
        return ((DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE)).isDeviceOwnerApp(getPackageName());
    }

    boolean isAdminActive() {
        return ((DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE)).isAdminActive(new ComponentName(this, AdminReceiver.class));
    }

    void setupAdmin() {
        if (isDeviceOwner()) { toast("האפליקציה כבר מנהל מכשיר (Device Owner)"); return; }
        if (!rootOk) {
            if (isAdminActive()) { adbOpen = true; toast("מנהל המכשיר פעיל. ל-Device Owner (יכולות מלאות) הרץ את פקודת ה-ADB מהמחשב"); showSettings(); return; }
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, new ComponentName(this, AdminReceiver.class));
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Phone Doctor: הפעלת מנהל מכשיר. ליכולות מלאות (מתן הרשאות לאפליקציות) צריך Device Owner דרך ADB.");
            try { startActivity(i); } catch (Exception e) { toast("לא נפתח מסך מנהל המכשיר. הפעל ידנית: הגדרות > אבטחה > אפליקציות מנהל מכשיר"); }
            return;
        }
        final String comp = getPackageName() + "/.AdminReceiver";
        toast("מגדיר מנהל מכשיר…");
        new Thread(new Runnable() {
            public void run() {
                Shell.Result r = Shell.run("dpm set-device-owner " + comp + " 2>&1", true, 30);
                final String msg;
                if (r.out.toLowerCase().contains("success")) msg = "הוגדר כמנהל מכשיר (Device Owner)";
                else {
                    Shell.Result r2 = Shell.run("dpm set-active-admin " + comp + " 2>&1", true, 30);
                    String low = r.out.toLowerCase();
                    msg = r2.out.toLowerCase().contains("success")
                        ? "Device Owner נכשל" + (low.contains("account") ? " (יש חשבונות במכשיר)" : "") + ", אבל הוגדר כמנהל פעיל"
                        : "ההגדרה נכשלה. נסה דרך ADB מהמחשב";
                }
                runOnUiThread(new Runnable() { public void run() { toast(msg); showSettings(); } });
            }
        }).start();
    }

    void requestRootNow() {
        toast("מבקש root…");
        new Thread(new Runnable() {
            public void run() {
                Shell.Result r = Shell.run("id", true, 45);
                final boolean ok = r.exit == 0 && r.out.contains("uid=0");
                runOnUiThread(new Runnable() {
                    public void run() {
                        rootOk = ok;
                        prefs.edit().putBoolean("root", ok).apply();
                        toast(ok ? "root אושר" : "root לא אושר");
                        showSettings();
                    }
                });
            }
        }).start();
    }

    boolean canWriteSecure() {
        return has("android.permission.WRITE_SECURE_SETTINGS");
    }

    boolean isAccessOn() {
        if (ScreenService.inst != null) return true;
        String v = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
        return v != null && v.contains(getPackageName() + "/");
    }

    void enableAccessibility() {
        try {
            String comp = getPackageName() + "/" + ScreenService.class.getName();
            String cur = Settings.Secure.getString(getContentResolver(), "enabled_accessibility_services");
            if (cur == null || cur.isEmpty()) cur = comp; else if (!cur.contains(comp)) cur = cur + ":" + comp;
            Settings.Secure.putString(getContentResolver(), "enabled_accessibility_services", cur);
            Settings.Secure.putString(getContentResolver(), "accessibility_enabled", "1");
        } catch (Exception e) {
            toast("לא הצלחתי להפעיל את שירות הנגישות. הפעל אותו ידנית בהגדרות הנגישות.");
        }
    }

    boolean assistSupported() { return Build.VERSION.SDK_INT >= 23; }

    boolean isAssistant() {
        try {
            if (Build.VERSION.SDK_INT >= 29 && Compat.roleAvailable(this)) return Compat.roleHeld(this);
        } catch (Exception ignored) { }
        String a = Settings.Secure.getString(getContentResolver(), "assistant");
        String v = Settings.Secure.getString(getContentResolver(), "voice_interaction_service");
        return (a != null && a.startsWith(getPackageName() + "/")) || (v != null && v.startsWith(getPackageName() + "/"));
    }

    /**
     * Makes Phone Doctor the digital assistant the way the system itself does it. On Android 10+ the
     * system screen reads the ASSISTANT role, so writing only the settings left it showing "none".
     * Needs root (role command). Returns false when it could not be done silently.
     */
    boolean setAssistantRoot(Runnable after) {
        if (!rootOk && !canWriteSecure()) return false;
        if (Build.VERSION.SDK_INT >= 29 && !rootOk) return false;   // the role can only be changed by the system shell
        final String pkg = getPackageName();
        final String comp = pkg + "/" + AssistService.class.getName();
        final String rec = pkg + "/" + StubRecognizer.class.getName();
        final Runnable done = after;
        new Thread(new Runnable() {
            public void run() {
                if (rootOk) {
                    String sh = (Build.VERSION.SDK_INT >= 29 ? "cmd role add-role-holder --user 0 android.app.role.ASSISTANT " + pkg + " 2>&1;" : "")
                        + "settings put secure assistant " + comp + ";"
                        + "settings put secure voice_interaction_service " + comp + ";"
                        + "settings put secure voice_recognition_service " + rec + ";";
                    Shell.run(sh, true, 30);
                } else {
                    setAssistant();
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        toast(isAssistant() ? "הוגדר כעוזר הדיגיטלי" : "לא הצלחתי להגדיר. בחר את Phone Doctor במסך העוזר הדיגיטלי");
                        if (!isAssistant()) openAssistSettings();
                        if (done != null) done.run();
                    }
                });
            }
        }).start();
        return true;
    }

    void openAssistSettings() {
        String[] acts = {Settings.ACTION_VOICE_INPUT_SETTINGS, "android.settings.MANAGE_DEFAULT_APPS_SETTINGS", Settings.ACTION_SETTINGS};
        toast("בחר \"Phone Doctor\" תחת \"אפליקציית עוזר דיגיטלי\"");
        for (String a : acts) {
            try { startActivity(new Intent(a)); return; } catch (Exception ignored) { }
        }
    }

    void setAssistant() {
        try {
            String comp = getPackageName() + "/" + AssistService.class.getName();
            Settings.Secure.putString(getContentResolver(), "assistant", comp);
            Settings.Secure.putString(getContentResolver(), "voice_interaction_service", comp);
        } catch (Exception e) {
            toast("לא הצלחתי להגדיר עוזר דיגיטלי. בחר אותו ידנית בהגדרות.");
        }
    }

    Intent assistIntent() {
        return new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS);   // the assistant role cannot be requested by apps
    }

    void doRoot() {
        rootBusy = "ממתין לאישור Root (חלון של Magisk/KernelSU)…";
        refreshRows();
        final String script = grantScript();
        new Thread(new Runnable() {
            public void run() {
                Shell.Result r = Shell.run("id", true, 45);
                rootOk = r.exit == 0 && r.out.contains("uid=0");
                if (rootOk && flowActive) {   // permissions are granted automatically only after "approve all"
                    runOnUiThread(new Runnable() {
                        public void run() { rootBusy = "מעניק הרשאות…"; refreshRows(); }
                    });
                    if (!script.isEmpty()) Shell.run(script, true, 60);
                }
                runOnUiThread(new Runnable() {
                    public void run() {
                        rootBusy = rootOk ? null : "Root לא זמין. אפשר להמשיך, אבל חלק מהבדיקות לא יעבדו";
                        prefs.edit().putBoolean("root", rootOk).apply();
                        refreshRows();
                        if (flowActive) nextStep();
                    }
                });
            }
        }).start();
    }

    /** With root: grant only what is still missing, in a single su call. */
    String grantScript() {
        String pkg = getPackageName();
        StringBuilder sb = new StringBuilder();
        try {
            PackageManager pm = getPackageManager();
            Set<String> skip = new HashSet<String>(Arrays.asList(
                "android.permission.MANAGE_EXTERNAL_STORAGE", "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.SYSTEM_ALERT_WINDOW", "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"));
            for (String p : pm.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS).requestedPermissions) {
                if (skip.contains(p) || has(p)) continue;
                try {
                    PermissionInfo info = pm.getPermissionInfo(p, 0);
                    if ((info.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) == PermissionInfo.PROTECTION_NORMAL) continue;
                } catch (Exception e) { continue; }
                sb.append("pm grant ").append(pkg).append(' ').append(p).append(" 2>/dev/null;");
            }
        } catch (Exception ignored) { }
        if (!filesOk()) sb.append("appops set ").append(pkg).append(" MANAGE_EXTERNAL_STORAGE allow;");
        if (!hasUsage()) sb.append("appops set ").append(pkg).append(" GET_USAGE_STATS allow;");
        if (!overlayOk()) sb.append("appops set ").append(pkg).append(" SYSTEM_ALERT_WINDOW allow;");
        if (!batteryOk()) sb.append("dumpsys deviceidle whitelist +").append(pkg).append(";");
        return sb.toString();
    }

    void finishSetup() {
        prefs.edit().putBoolean("setup", true).putBoolean("root", rootOk).apply();
        flowActive = false;
        waiting = false;
        rootBusy = null;
        onSetup = false;
        showChat();
    }

    // ================================================================= chat UI

    void keepScreen(boolean on) {
        if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    void showChat() {
        onSetup = false;
        screen = "chat";
        needRefresh = false;
        String draft = input != null ? input.getText().toString() : "";
        LinearLayout col = vert();
        col.setBackgroundColor(BG);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(12), dp(8), dp(8));
        View menu = glyph(Glyph.MENU, DIM);
        click(menu, new Runnable() { public void run() { showHistory(); } });
        TextView title = tv("Phone Doctor", 20, TXT);
        View plus = glyph(Glyph.PLUS, TXT);
        click(plus, new Runnable() { public void run() { newChat(); } });
        View gear = glyph(Glyph.GEAR, DIM);
        click(gear, new Runnable() { public void run() { showSettings(); } });
        bar.addView(menu);
        bar.addView(title, lp(0, W, 1f));
        View share = glyph(Glyph.SHARE, DIM);
        click(share, new Runnable() { public void run() { shareReport(); } });
        bar.addView(share);
        bar.addView(plus);
        bar.addView(gear);
        col.addView(bar);

        FrameLayout fl = new FrameLayout(this);
        scroll = new ScrollView(this);
        chat = vert();
        chat.setPadding(dp(16), dp(8), dp(16), dp(16));
        scroll.addView(chat);
        fl.addView(scroll, new FrameLayout.LayoutParams(M, M));

        LinearLayout g = vert();
        g.setPadding(dp(24), 0, dp(24), dp(40));
        TextView hi = tv("שלום", 36, TXT);
        hi.setTypeface(null, Typeface.BOLD);
        grad(hi);
        TextView q = tv("מה לבדוק בטלפון?", 26, DIM);
        q.setPadding(0, 0, 0, dp(24));
        g.addView(hi);
        g.addView(q);
        String[] chips = {"כמה צריכת ה-RAM הנוכחית?", "כמה זמן המכשיר דלוק?",
                "בדיקת בריאות כללית לטלפון", "למה הסוללה מתרוקנת מהר?"};
        for (final String c : chips) {
            TextView t = tv(c, 15, TXT);
            t.setBackground(rr(SURF, 16));
            t.setPadding(dp(16), dp(12), dp(16), dp(12));
            click(t, new Runnable() { public void run() { hideIme(); onSend(c); } });
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(W, W);
            l.bottomMargin = dp(8);
            g.addView(t, l);
        }
        greeting = g;
        fl.addView(g, new FrameLayout.LayoutParams(M, W, Gravity.CENTER_VERTICAL));
        col.addView(fl, lp(M, 0, 1f));

        LinearLayout wrap = new LinearLayout(this);
        wrap.setPadding(dp(12), dp(4), dp(12), dp(12));
        LinearLayout pill = new LinearLayout(this);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(rr(SURF, 28));
        pill.setPadding(dp(20), dp(6), dp(8), dp(6));
        click(pill, new Runnable() { public void run() { input.requestFocus(); showIme(input); } });
        input = new EditText(this);
        input.setBackground(null);
        input.setHint("שאל את Phone Doctor");
        input.setHintTextColor(DIM);
        input.setTextColor(TXT);
        input.setTextSize(16);
        input.setMaxLines(5);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinHeight(dp(44));
        imeOnTouch(input);
        sendBtn = tv(Core.running ? "\u25A0" : "\u2191", 22, 0xFFFFFFFF);
        sendBtn.setTypeface(null, Typeface.BOLD);
        sendBtn.setGravity(Gravity.CENTER);
        sendBtn.setBackground(rr(SEND, 22));
        click(sendBtn, new Runnable() {
            public void run() {
                if (Core.running) { Core.stopRun(); toast("עוצר…"); return; }   // busy = always stop
                String t = input.getText().toString().trim();
                if (!t.isEmpty()) {
                    stopDictation();
                    input.setText("");
                    hideIme();
                    onSend(t);
                }
            }
        });
        queueBtn = tv("\u2191", 18, ACC);
        queueBtn.setTypeface(null, Typeface.BOLD);
        queueBtn.setGravity(Gravity.CENTER);
        queueBtn.setBackground(rr(SURF2, 22));
        queueBtn.setVisibility(View.GONE);
        click(queueBtn, new Runnable() {
            public void run() {
                String t = input.getText().toString().trim();
                if (Core.running && !t.isEmpty()) {
                    Core.queue = t;
                    stopDictation();
                    input.setText("");
                    hideIme();
                    toast("ההודעה תישלח כשהסוכן יסיים");
                }
            }
        });
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b, int c, int d) { }
            public void onTextChanged(CharSequence a, int b, int c, int d) { updateQueueBtn(); }
            public void afterTextChanged(Editable e) { }
        });
        pill.addView(input, lp(0, W, 1f));
        micBtn = new Glyph(this, Glyph.MIC, TXT, dp(40));
        click(micBtn, new Runnable() { public void run() { onMic(); } });
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(dp(40), dp(40));
        ml.setMarginEnd(dp(6));
        pill.addView(micBtn, ml);
        micState(voice == null ? Voice.IDLE : voice.state());
        LinearLayout.LayoutParams ql = new LinearLayout.LayoutParams(dp(44), dp(44));
        ql.setMarginEnd(dp(6));
        pill.addView(queueBtn, ql);
        pill.addView(sendBtn, new LinearLayout.LayoutParams(dp(44), dp(44)));
        wrap.addView(pill, new LinearLayout.LayoutParams(M, W));
        col.addView(wrap);
        show(col);
        input.setText(draft);

        liveHead = null;
        liveBody = null;
        liveSteps = null;
        liveSig = "";
        int n = Core.uiLog.length();
        replaying = true;
        for (int i = 0; i < n; i++) {
            JSONObject e = Core.uiLog.optJSONObject(i);
            if (e != null) renderEntry(e, i == n - 1);
        }
        replaying = false;
        if (n > 0) scrollDown();
        boolean hasAssist = Core.hasAssist();
        if (n > 0 || hasAssist) greeting.setVisibility(View.GONE);
        if (hasAssist) chat.addView(assistChip());
        keepScreen(Core.running);
    }

    // ---- entries coming from Core

    void renderEntry(JSONObject e, boolean last) {
        String k = e.optString("k");
        if (k.equals("u")) renderUser(e.optString("t"));
        else if (k.equals("s")) renderSteps(e.optJSONArray("steps"));
        else {
            renderAI(e.optString("t"), k.equals("e"));
            if (k.equals("e") && e.optBoolean("q") && last && !Core.running) addResume();
        }
    }

    void onEntry(JSONObject e) {
        if (!screen.equals("chat") || chat == null) return;
        if (greeting != null) greeting.setVisibility(View.GONE);
        renderEntry(e, true);
        if (e.optString("k").equals("u")) {
            sendBtn.setText(safeGlyphs("\u25A0"));
            keepScreen(true);
        }
    }

    // ---- microphone

    static final int REQ_MIC = 31;

    void onMic() {
        if (Build.VERSION.SDK_INT >= 23 && checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            return;
        }
        if (voice == null) voice = new Voice(this, new Voice.Listener() {
            public void state(int st) { micState(st); }
            public void partial(String t) { setMicText(t); }
            public void done(String t) { setMicText(t); }
            public void error(String m) { toast(m); }
            public void info(String m) { toast(m); }
        });
        if (voice.state() == Voice.IDLE) {
            String cur = input == null ? "" : input.getText().toString();
            micPrefix = cur.trim().isEmpty() ? "" : cur.replaceAll("\\s+$", "") + " ";
        }
        voice.toggle();
    }

    /** Called when the box is sent: late speech results must not refill it. */
    void stopDictation() {
        micPrefix = "";
        if (voice != null && voice.state() != Voice.IDLE) voice.cancel();
    }

    void setMicText(String t) {
        if (input == null || voice == null) return;
        input.setText(micPrefix + t);
        input.setSelection(input.getText().length());
    }

    void micState(int st) {
        if (micBtn == null) return;
        boolean active = st == Voice.LISTEN || st == Voice.RECORD;
        micBtn.setBackground(rr(active ? ERR : SURF2, 20));
        micBtn.setColor(active ? 0xFFFFFFFF : TXT);
        micBtn.setAlpha(st == Voice.WORKING ? 0.45f : 1f);
        if (input != null && st == Voice.LISTEN) input.setHint("מקשיב… דבר עכשיו");
        else if (input != null && st == Voice.RECORD) input.setHint("מקליט… לחץ 🎤 לסיום");
        else if (input != null && st == Voice.WORKING) input.setHint("מתמלל דרך Gemini…");
        else if (input != null) input.setHint("שאל את Phone Doctor");
    }

    void updateQueueBtn() {
        if (queueBtn == null || input == null) return;
        boolean show = Core.running && input.getText().toString().trim().length() > 0;
        queueBtn.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    void onStatus() {
        if (!screen.equals("chat")) return;
        updateQueueBtn();
        updateLive();
        keepScreen(Core.running);
    }

    void onFinished() {
        if (sendBtn != null) sendBtn.setText(safeGlyphs("\u2191"));
        updateQueueBtn();
        keepScreen(false);
        if (screen.equals("chat")) updateLive();
    }

    // ---- messages

    void scrollDown() {
        scroll.post(new Runnable() {
            public void run() { scroll.fullScroll(View.FOCUS_DOWN); }
        });
    }

    void renderUser(String text) {
        TextView t = tv(text, 16, TXT);
        t.setBackground(rr(SURF2, 20));
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(W, W);
        l.gravity = Gravity.START;
        l.setMarginEnd(dp(48));
        l.topMargin = dp(12);
        l.bottomMargin = dp(12);
        chat.addView(t, l);
        scrollDown();
    }

    /** Options the model wrote as text ("[לפנות שטח]" lines) at the end of an answer: shown as buttons instead. */
    static final java.util.regex.Pattern OPT = java.util.regex.Pattern.compile("^\\s*(?:[•*\\-]|\\d+[.)])?\\s*\\[([^\\[\\]]{2,60})\\]\\s*$");

    /** One line with all choices: "[a|b|c]" (the ask_user format, written as text). */
    static final java.util.regex.Pattern OPT_ONE = java.util.regex.Pattern.compile("^\\s*\\[([^\\[\\]]+\\|[^\\[\\]]+)\\]\\s*$");

    static List<String> trailingOptions(String text) {
        String[] lines = text.split("\n");
        List<String> opts = new ArrayList<String>();
        int i = lines.length - 1;
        while (i >= 0 && lines[i].trim().isEmpty()) i--;
        if (i >= 0) {
            java.util.regex.Matcher one = OPT_ONE.matcher(lines[i]);
            if (one.find()) {
                for (String o : one.group(1).split("\\|")) if (o.trim().length() > 1) opts.add(o.trim());
                return opts.size() >= 2 && opts.size() <= 6 ? opts : new ArrayList<String>();
            }
        }
        for (; i >= 0; i--) {
            java.util.regex.Matcher m = OPT.matcher(lines[i]);
            if (!m.find()) break;
            opts.add(0, m.group(1).trim());
        }
        return opts.size() >= 2 && opts.size() <= 6 ? opts : new ArrayList<String>();
    }

    void renderAI(String text, boolean err) {
        TextView spark = tv("\u2726", 18, TXT);
        grad(spark);
        final List<String> opts = err ? new ArrayList<String>() : trailingOptions(text);
        if (!opts.isEmpty()) {   // cut the option lines out of the text; they become buttons below
            String[] lines = text.split("\n");
            int keep = lines.length;
            int found = 0;
            for (int i = lines.length - 1; i >= 0 && found < opts.size(); i--) {
                if (lines[i].trim().isEmpty()) { keep = i; continue; }
                if (OPT_ONE.matcher(lines[i]).find()) { keep = i; break; }
                if (OPT.matcher(lines[i]).find()) { keep = i; found++; }
            }
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < keep; i++) b.append(lines[i]).append('\n');
            text = b.toString().trim();
        }
        TextView t = tv("", 16, err ? ERR : TXT);
        t.setText(err ? text : Md.render(text, SURF2));
        t.setLineSpacing(dp(3), 1f);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(4), 0, dp(12));
        chat.addView(spark);
        chat.addView(t);
        if (!opts.isEmpty()) {
            LinearLayout box = vert();
            box.setPadding(0, 0, 0, dp(10));
            for (final String o : opts) {
                TextView bt = tv(o, 14, ACC);
                bt.setBackground(rr(SURF2, 18));
                bt.setPadding(dp(14), dp(9), dp(14), dp(9));
                click(bt, new Runnable() {
                    public void run() {
                        if (Core.running) { toast("הסוכן עדיין עובד"); return; }
                        hideIme();
                        onSend(o);
                    }
                });
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(W, W);
                bl.bottomMargin = dp(6);
                box.addView(bt, bl);
            }
            chat.addView(box);
        }
        if (err) {
            TextView det = tv("פרטים טכניים (להעתקה)", 12, DIM);
            det.setPaintFlags(det.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            det.setPadding(0, 0, 0, dp(12));
            click(det, new Runnable() { public void run() { showErrors(); } });
            chat.addView(det);
        }
        if (replaying) {
            scrollDown();
        } else {
            final View start = spark;
            scroll.post(new Runnable() {
                public void run() { scroll.smoothScrollTo(0, Math.max(0, start.getTop() - dp(8))); }
            });
        }
    }

    void addResume() {
        TextView t = tv("\u25B6 המשך מאיפה שעצרתי", 15, ON_ACC);
        t.setBackground(rr(ACC, 20));
        t.setPadding(dp(16), dp(10), dp(16), dp(10));
        click(t, new Runnable() { public void run() { onSend("המשך מהמקום שעצרת"); } });
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(W, W);
        l.bottomMargin = dp(12);
        chat.addView(t, l);
        scrollDown();
    }

    // ---- the step log (tap the status line to see exactly what the agent does)

    void renderSteps(final JSONArray steps) {
        if (steps == null) return;
        final boolean live = steps == Core.steps;
        LinearLayout box = vert();
        final TextView head = tv("", 13, DIM);
        head.setPadding(0, dp(6), 0, dp(6));
        final LinearLayout body = vert();
        body.setPadding(dp(4), 0, 0, dp(4));
        body.setVisibility(live && expandLive ? View.VISIBLE : View.GONE);
        box.addView(head);
        box.addView(body);
        chat.addView(box);
        if (live) {
            liveHead = head;
            liveBody = body;
            liveSteps = steps;
            liveSig = "";
        }
        click(head, new Runnable() {
            public void run() {
                if (body.getVisibility() == View.VISIBLE) {
                    body.setVisibility(View.GONE);
                    if (live) expandLive = false;
                } else {
                    fillSteps(body, steps);
                    body.setVisibility(View.VISIBLE);
                    if (live) expandLive = true;
                }
                setHead(head, steps, live, body.getVisibility() == View.VISIBLE);
            }
        });
        if (body.getVisibility() == View.VISIBLE) fillSteps(body, steps);
        setHead(head, steps, live, body.getVisibility() == View.VISIBLE);
        scrollDown();
    }

    void setHead(TextView head, JSONArray steps, boolean live, boolean open) {
        int n;
        synchronized (Core.LOCK) { n = steps.length(); }
        String chev = open ? "\u25BE" : "\u25C2";
        if (live && Core.running) {
            head.setVisibility(View.VISIBLE);
            head.setText(safeGlyphs("\u25CF " + Core.status + "   " + chev));
            head.setTextColor(ACC);
        } else if (n == 0) {
            head.setVisibility(View.GONE);
        } else {
            head.setVisibility(View.VISIBLE);
            head.setText(safeGlyphs("\u2713 " + n + " שלבים   " + chev));
            head.setTextColor(DIM);
        }
    }

    void updateLive() {
        if (liveHead == null || liveSteps == null) return;
        boolean open = liveBody.getVisibility() == View.VISIBLE;
        if (open) {
            StringBuilder sig = new StringBuilder();
            synchronized (Core.LOCK) {
                for (int i = 0; i < liveSteps.length(); i++) {
                    JSONObject s = liveSteps.optJSONObject(i);
                    if (s != null) sig.append(s.optString("state")).append(s.optString("out").length()).append(';');
                }
            }
            if (!sig.toString().equals(liveSig)) {
                liveSig = sig.toString();
                fillSteps(liveBody, liveSteps);
            }
        }
        setHead(liveHead, liveSteps, true, open);
    }

    void fillSteps(LinearLayout body, JSONArray steps) {
        body.removeAllViews();
        synchronized (Core.LOCK) {
            for (int i = 0; i < steps.length(); i++) {
                JSONObject s = steps.optJSONObject(i);
                if (s != null) body.addView(stepRow(s));
            }
        }
    }

    View stepRow(JSONObject s) {
        String type = s.optString("type"), state = s.optString("state");
        LinearLayout r = vert();
        r.setPadding(0, dp(3), 0, dp(3));
        LinearLayout line = new LinearLayout(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String g = type.equals("think") ? "\u270E" : type.equals("cmd") ? "$" : type.equals("shot") ? "\u25A3"
            : type.equals("search") ? "\u2315" : type.equals("screen") ? "\u25C9" : "\u25A4";
        TextView ic = tv(g, 14, state.equals("fail") ? ERR : state.equals("ok") ? GOOD : DIM);
        ic.setWidth(dp(24));
        TextView t = tv(s.optString("title") + (state.equals("run") ? " …" : ""), 13, state.equals("run") ? DIM : TXT);
        line.addView(ic);
        line.addView(t, lp(0, W, 1f));
        r.addView(line);
        final LinearLayout det = vert();
        det.setVisibility(View.GONE);
        det.setPadding(dp(24), dp(2), 0, dp(4));
        String detail = s.optString("detail"), out = s.optString("out"), img = s.optString("img");
        if (!detail.isEmpty()) det.addView(box(detail, true, DIM));
        if (!out.isEmpty()) det.addView(box(out, !type.equals("think"), TXT));
        if (!img.isEmpty()) det.addView(thumb(img));
        r.addView(det);
        long jid = s.optLong("jid", 0);
        if (jid != 0) r.addView(undoRow(jid, s.optInt("jlevel", 1)));
        if (det.getChildCount() > 0) {
            click(line, new Runnable() {
                public void run() { det.setVisibility(det.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
            });
        }
        return r;
    }

    View box(String text, boolean mono, int color) {
        TextView t = tv(text, mono ? 11 : 13, color);
        if (mono) t.setTypeface(Typeface.MONOSPACE);
        t.setBackground(rr(SURF, 8));
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        t.setTextIsSelectable(true);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
        l.bottomMargin = dp(4);
        t.setLayoutParams(l);
        return t;
    }

    View thumb(String b64) {
        ImageView iv = new ImageView(this);
        try {
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            final Bitmap bm = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bm == null) return iv;
            iv.setImageBitmap(bm);
            iv.setAdjustViewBounds(true);
            iv.setMaxWidth(dp(200));
            iv.setMaxHeight(dp(320));
            iv.setBackground(rr(SURF, 8));
            iv.setPadding(dp(2), dp(2), dp(2), dp(2));
            click(iv, new Runnable() {
                public void run() {
                    ImageView big = new ImageView(MainActivity.this);
                    big.setImageBitmap(bm);
                    big.setAdjustViewBounds(true);
                    new AlertDialog.Builder(MainActivity.this, dlg()).setView(big).setPositiveButton("סגור", null).show();
                }
            });
        } catch (Exception ignored) { }
        return iv;
    }

    // ================================================================= undo journal

    View undoRow(final long jid, int level) {
        JSONObject e = Journal.get(jid);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(24), dp(2), 0, dp(4));
        String st = e == null ? "" : e.optString("state");
        boolean can = e != null && Journal.canUndo(e);
        String label = st.equals("undone") ? "\u2713 בוטל"
            : level >= 3 && !can ? "\u26A0 בלתי הפיך"
            : e != null && !e.optString("dir").isEmpty() ? "\uD83D\uDCBE נשמר גיבוי" : "\u21A9 ניתן לביטול";
        row.addView(tv(label, 12, st.equals("undone") ? GOOD : level >= 3 && !can ? ERR : DIM), lp(0, W, 1f));
        if (can) row.addView(pillBtn("בטל", new Runnable() { public void run() { confirmUndo(jid); } }));
        return row;
    }

    void confirmUndo(final long jid) {
        JSONObject e = Journal.get(jid);
        if (e == null) { toast("הרשומה לא נמצאה"); return; }
        StringBuilder sb = new StringBuilder(e.optString("purpose")).append("\n\nהפעולה שבוצעה:\n").append(e.optString("cmd"));
        if (e.optString("kind").equals("admin")) sb.append("\n\nהביטול יחזיר את המצב הקודם דרך מנהל המכשיר.");
        else {
            org.json.JSONArray u = e.optJSONArray("undo");
            sb.append("\n\nפקודות הביטול:");
            for (int i = 0; u != null && i < u.length(); i++) sb.append("\n").append(u.optString(i));
        }
        if (!e.optString("notes").isEmpty()) sb.append("\n\n").append(e.optString("notes"));
        new AlertDialog.Builder(this, dlg()).setTitle("לבטל את השינוי?").setMessage(sb.toString())
            .setPositiveButton("בטל שינוי", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { doUndo(jid); }
            }).setNegativeButton("השאר", null).show();
    }

    void doUndo(final long jid) {
        toast("מבטל…");
        new Thread(new Runnable() {
            public void run() {
                final String res = Journal.undo(jid);
                runOnUiThread(new Runnable() {
                    public void run() { toast(res); refreshAfterUndo(); }
                });
            }
        }).start();
    }

    void refreshAfterUndo() {
        if (screen.equals("journal")) showJournal(); else if (screen.equals("chat")) updateLive();
    }

    void undoAll() {
        new Thread(new Runnable() {
            public void run() {
                int n = 0;
                for (JSONObject e : Journal.list()) {
                    if (Journal.canUndo(e)) { Journal.undo(e.optLong("id")); n++; }
                }
                final int cnt = n;
                runOnUiThread(new Runnable() {
                    public void run() { toast("בוטלו " + cnt + " שינויים"); showJournal(); }
                });
            }
        }).start();
    }

    void showJournal() {
        int y = keepScroll("journal");
        screen = "journal";
        onSetup = false;
        LinearLayout col = vert();
        col.setBackgroundColor(BG);
        col.addView(topBar("יומן שינויים"));
        ScrollView sv = pageScroll(y);
        LinearLayout list = vert();
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(list);
        col.addView(sv, lp(M, 0, 1f));
        List<JSONObject> all = Journal.list();
        list.addView(note("כל שינוי שהסוכן עשה נרשם כאן. לפני מחיקה נשמר גיבוי, ואפשר לבטל בלחיצה."));
        if (all.isEmpty()) {
            TextView em = tv("אין עדיין שינויים", 15, DIM);
            em.setGravity(Gravity.CENTER);
            em.setPadding(0, dp(48), 0, 0);
            list.addView(em, new LinearLayout.LayoutParams(M, W));
        } else {
            LinearLayout bar = new LinearLayout(this);
            bar.setPadding(0, 0, 0, dp(8));
            if (Journal.activeCount() > 0) bar.addView(pillBtn("בטל את כל השינויים הפעילים", new Runnable() {
                public void run() {
                    new AlertDialog.Builder(MainActivity.this, dlg()).setTitle("לבטל הכול?")
                        .setMessage("כל השינויים הפעילים ייבוטלו, מהחדש לישן.")
                        .setPositiveButton("בטל הכול", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { undoAll(); }
                        }).setNegativeButton("חזור", null).show();
                }
            }));
            TextView clr = pillBtn("נקה יומן", new Runnable() {
                public void run() {
                    new AlertDialog.Builder(MainActivity.this, dlg()).setTitle("לנקות את היומן?")
                        .setMessage("הרשומות והגיבויים יימחקו ולא יהיה אפשר לבטל אותם יותר.")
                        .setPositiveButton("נקה", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) {
                                new Thread(new Runnable() { public void run() {
                                    Journal.clearAll();
                                    runOnUiThread(new Runnable() { public void run() { showJournal(); } });
                                } }).start();
                            }
                        }).setNegativeButton("חזור", null).show();
                }
            });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(W, W);
            cl.rightMargin = dp(8);
            bar.addView(clr, cl);
            list.addView(bar);
        }
        for (final JSONObject e : all) {
            LinearLayout r = vert();
            r.setBackground(rr(SURF, 16));
            r.setPadding(dp(16), dp(12), dp(16), dp(12));
            boolean can = Journal.canUndo(e), undone = e.optString("state").equals("undone");
            r.addView(tv(e.optString("purpose"), 15, TXT));
            r.addView(tv(DateFormat.format("dd/MM/yyyy HH:mm", e.optLong("id")).toString()
                + (undone ? "  \u2022  בוטל" : can ? "" : "  \u2022  בלתי הפיך"), 12, undone ? GOOD : can ? DIM : ERR));
            String c = e.optString("cmd");
            r.addView(box(c.length() > 400 ? c.substring(0, 400) + "\u2026" : c, true, DIM));
            if (!e.optString("undoError").isEmpty()) r.addView(tv(e.optString("undoError"), 12, ERR));
            if (can) {
                final long id = e.optLong("id");
                LinearLayout b = new LinearLayout(this);
                b.addView(pillBtn("בטל", new Runnable() { public void run() { confirmUndo(id); } }));
                r.addView(b);
            }
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
            l.bottomMargin = dp(8);
            list.addView(r, l);
        }
        show(col);
    }

    void showErrors() {
        final String txt = ErrorLog.text(30);
        TextView t = tv(txt, 11, TXT);
        t.setTypeface(Typeface.MONOSPACE);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        t.setTextDirection(View.TEXT_DIRECTION_LTR);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new AlertDialog.Builder(this, dlg()).setTitle("יומן שגיאות Gemini").setView(sv)
            .setPositiveButton("העתק", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("errors", txt));
                    toast("הועתק. אפשר להדביק ולשלוח");
                }
            })
            .setNeutralButton("נקה", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) { ErrorLog.clear(); toast("היומן נוקה"); if (screen.equals("settings")) showSettings(); }
            })
            .setNegativeButton("סגור", null).show();
    }

    void showMemory() {
        final String[] facts = Memory.items("fact"), recipes = Memory.items("recipe");
        StringBuilder sb = new StringBuilder();
        if (facts.length == 0 && recipes.length == 0) sb.append("הזיכרון ריק. הסוכן ימלא אותו תוך כדי עבודה.");
        if (facts.length > 0) { sb.append("עובדות על המכשיר:\n"); for (String f : facts) sb.append("• ").append(f).append('\n'); }
        if (recipes.length > 0) { sb.append("\nתיקונים שעבדו:\n"); for (String r : recipes) sb.append("• ").append(r).append('\n'); }
        AlertDialog.Builder b = new AlertDialog.Builder(this, dlg()).setTitle("זיכרון המכשיר").setMessage(sb.toString().trim())
            .setPositiveButton("סגור", null);
        if (facts.length + recipes.length > 0) b.setNeutralButton("מחק הכל", new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) { Memory.clear(); toast("הזיכרון נמחק"); showSettings(); }
        });
        b.show();
    }

    // ================================================================= share report

    /** The current chat as a tidy text report (problem, steps with commands, answers) for a forum or a technician. */
    String reportText() {
        StringBuilder sb = new StringBuilder();
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        sb.append("דוח Phone Doctor ").append(ver).append('\n');
        sb.append("מכשיר: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(", אנדרואיד ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("תאריך: ").append(DateFormat.format("dd/MM/yyyy HH:mm", System.currentTimeMillis())).append("\n");
        JSONArray log = Core.uiLog;
        for (int i = 0; i < log.length(); i++) {
            JSONObject e = log.optJSONObject(i);
            if (e == null) continue;
            String k = e.optString("k");
            if (k.equals("u")) sb.append("\n━━━━━━━━━━\nשאלה: ").append(e.optString("t")).append('\n');
            else if (k.equals("a")) sb.append("\nתשובה:\n").append(e.optString("t").replace("**", "")).append('\n');
            else if (k.equals("e")) sb.append("\nשגיאה: ").append(e.optString("t")).append('\n');
            else if (k.equals("s")) {
                JSONArray st = e.optJSONArray("steps");
                if (st == null || st.length() == 0) continue;
                sb.append("\nשלבים:\n");
                for (int j = 0; j < st.length(); j++) {
                    JSONObject x = st.optJSONObject(j);
                    if (x == null || x.optString("type").equals("think")) continue;
                    String state = x.optString("state");
                    sb.append(state.equals("ok") ? "  [v] " : state.equals("fail") ? "  [x] " : "  [ ] ").append(x.optString("title")).append('\n');
                    String d = x.optString("detail");
                    if (!d.isEmpty() && !x.optString("type").equals("shot")) sb.append("      $ ").append(d.length() > 600 ? d.substring(0, 600) + "…" : d.replace("\n", "\n        ")).append('\n');
                    String o = x.optString("out");   // the tool's output (logs), already capped at 3000 chars per step
                    if (!o.isEmpty()) {
                        sb.append("      --- פלט ---\n");
                        for (String ln : o.split("\n")) sb.append("      | ").append(ln).append('\n');
                    }
                    if (x.has("jid")) {
                        JSONObject j2 = Journal.get(x.optLong("jid"));
                        if (j2 != null) sb.append("      ").append(j2.optString("state").equals("undone") ? "(בוטל)" : Journal.canUndo(j2) ? "(ניתן לביטול)" : "(בלתי הפיך)").append('\n');
                    }
                }
            }
        }
        if (ErrorLog.count() > 0) sb.append("\n━━━━━━━━━━\nיומן שגיאות Gemini (אחרונות):\n").append(ErrorLog.text(8));
        String r = sb.toString().trim();
        return r.length() > 400000 ? r.substring(0, 400000) + "\n…[הדוח קוצר]" : r;   // share intents have a size limit
    }

    void shareReport() {
        if (Core.uiLog.length() == 0) { toast("אין עדיין שיחה לשתף"); return; }
        String text = reportText();
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "דוח Phone Doctor");
        i.putExtra(Intent.EXTRA_TEXT, text);
        try {
            startActivity(Intent.createChooser(i, "שיתוף הדוח"));
        } catch (Exception e) {
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("report", text));
            toast("אין אפליקציה לשיתוף. הדוח הועתק ללוח");
        }
    }

    // ================================================================= history

    /** The waiting capture, with a thumbnail and a button to drop it. */
    View assistChip() {
        final LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(rr(SURF, 14));
        row.setPadding(dp(10), dp(8), dp(6), dp(8));
        if (Core.assistB64 != null) {
            View th = thumb(Core.assistB64);
            if (th instanceof ImageView) { ((ImageView) th).setMaxHeight(dp(64)); ((ImageView) th).setMaxWidth(dp(40)); }
            row.addView(th, new LinearLayout.LayoutParams(W, W));
        }
        TextView h = tv((Core.assistB64 != null ? "צילום המסך" : "טקסט המסך") + " יצורף להודעה הבאה", 13, TXT);
        h.setPadding(dp(10), 0, dp(6), 0);
        row.addView(h, lp(0, W, 1f));
        TextView x = tv("\u2715 הסר", 13, ACC);
        x.setPadding(dp(10), dp(8), dp(10), dp(8));
        click(x, new Runnable() {
            public void run() {
                Core.clearAssist();
                row.setVisibility(View.GONE);
                toast("הצילום הוסר");
            }
        });
        row.addView(x);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
        l.topMargin = dp(8);
        l.bottomMargin = dp(8);
        row.setLayoutParams(l);
        return row;
    }

    void newChat() { newChat(false); }

    /** keepAssist: the screen capture just taken goes to the new chat (tile / assistant); "+" starts clean. */
    void newChat(boolean keepAssist) {
        if (Core.running) { toast("הסוכן עדיין עובד. לחץ על \u25A0 כדי לעצור"); return; }
        if (!keepAssist) Core.clearAssist();
        Core.saveChat();
        Core.agent = null;
        Core.curId = null;
        Core.uiLog = new JSONArray();
        Core.steps = new JSONArray();
        expandLive = false;
        showChat();
    }

    void openChat(String id) {
        if (Core.running) { toast("הסוכן עדיין עובד. לחץ על \u25A0 כדי לעצור"); return; }
        Core.saveChat();
        JSONObject j = store.load(id);
        if (j == null) return;
        Core.curId = id;
        JSONArray ui = j.optJSONArray("ui");
        Core.uiLog = ui == null ? new JSONArray() : ui;
        Core.steps = new JSONArray();
        Core.agent = null;
        Core.ensureAgent();
        Core.agent.importContents(j.optJSONArray("contents"));
        expandLive = false;
        showChat();
    }

    void showHistory() {
        screen = "history";
        onSetup = false;
        if (!Core.running) Core.saveChat();
        LinearLayout col = vert();
        col.setBackgroundColor(BG);
        col.addView(topBar("היסטוריית שיחות"));
        ScrollView sv = new ScrollView(this);
        LinearLayout list = vert();
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(list);
        col.addView(sv, lp(M, 0, 1f));
        List<Store.Meta> metas = store.list();
        if (metas.isEmpty()) {
            TextView e = tv("אין עדיין שיחות שמורות", 15, DIM);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, dp(48), 0, 0);
            list.addView(e, new LinearLayout.LayoutParams(M, W));
        }
        for (final Store.Meta mt : metas) {
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(rr(SURF, 16));
            r.setPadding(dp(16), dp(12), dp(8), dp(12));
            LinearLayout txt = vert();
            txt.addView(tv(mt.title, 16, TXT));
            txt.addView(tv(DateFormat.format("dd/MM/yyyy HH:mm", mt.updated).toString(), 12, DIM));
            TextView del = icon("\u2715", DIM);
            click(txt, new Runnable() { public void run() { openChat(mt.id); } });
            click(del, new Runnable() {
                public void run() {
                    if (mt.id.equals(Core.curId)) {
                        if (Core.running) { toast("הסוכן עדיין עובד"); return; }
                        Core.curId = null;
                        Core.agent = null;
                        Core.uiLog = new JSONArray();
                    }
                    store.delete(mt.id);
                    showHistory();
                }
            });
            r.addView(txt, lp(0, W, 1f));
            r.addView(del);
            LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
            l.bottomMargin = dp(8);
            list.addView(r, l);
        }
        show(col);
    }

    // ================================================================= approvals + sending

    void showPending() {
        final Core.Pending p = Core.pending;
        if (p == null || p.shown) return;
        p.shown = true;
        if (p.options != null) {   // ask_user: one button per option
            pendingDlg = new AlertDialog.Builder(this, dlg()).setTitle(p.purpose).setCancelable(false)
                .setItems(p.options, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        p.answer = p.options[w];
                        pendingDlg = null;
                        Core.pending = null;
                        p.latch.countDown();
                    }
                })
                .setNegativeButton("סגור בלי לבחור", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        pendingDlg = null;
                        Core.pending = null;
                        p.latch.countDown();
                    }
                }).show();
            return;
        }
        String rl = p.risk.equals("dangerous") ? "\u26A0 פעולה מסוכנת" : "שינוי במערכת";
        String warn = Safety.warnings(p.cmd);   // what the command really does, independent of the model's description
        String msg = (warn.isEmpty() ? "" : "מה הפקודה עושה בפועל:\n" + warn + "\n\n") + p.purpose + "\n\n" + rl + (p.root ? " \u00B7 root" : "") + "\n\n" + p.cmd;
        pendingDlg = new AlertDialog.Builder(this, dlg()).setTitle("נדרש אישור").setMessage(msg).setCancelable(false)
            .setPositiveButton("אשר והרץ", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    p.ok = true;
                    pendingDlg = null;
                    Core.pending = null;
                    p.latch.countDown();
                }
            })
            .setNegativeButton("דחה", new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface d, int w) {
                    pendingDlg = null;
                    Core.pending = null;
                    p.latch.countDown();
                }
            }).show();
    }

    List<String> fbModels() {
        List<String> out = new ArrayList<String>();
        for (String s : prefs.getString("fbmodels", "").split("\n")) if (!s.trim().isEmpty()) out.add(s.trim());
        return out;
    }

    void onSend(final String t) {
        if (t.isEmpty()) return;
        if (prefs.getString("key", "").isEmpty()) {
            toast("הכנס מפתח Gemini API בהגדרות");
            showSettings();
            return;
        }
        if (Core.running) {
            Core.queue = t;
            toast("ההודעה תישלח כשהסוכן יסיים");
            return;
        }
        Core.send(t);
    }

    // ================================================================= settings

    interface ModelsCb { void got(List<String> models); }

    void withModels(final boolean refresh, final ModelsCb cb) {
        final String key = prefs.getString("key", "");
        if (key.isEmpty()) { toast("הכנס קודם מפתח API"); return; }
        String cached = prefs.getString("models", "");
        if (!refresh && !cached.isEmpty()) { cb.got(Arrays.asList(cached.split("\n"))); return; }
        toast("טוען מודלים…");
        new Thread(new Runnable() {
            public void run() {
                try {
                    final List<String> m = Models.fetch(key);
                    StringBuilder sb = new StringBuilder();
                    for (String s : m) sb.append(s).append('\n');
                    SharedPreferences.Editor ed = prefs.edit().putString("models", sb.toString().trim());
                    if (!prefs.contains("fbmodels")) {   // default: flash-lite first, then every other model as backup
                        String all = sb.toString().trim();
                        ed.putString("fbmodels", (m.contains("gemini-2.5-flash-lite") ? "gemini-2.5-flash-lite\n" : "") + all.replace("gemini-2.5-flash-lite\n", "").replace("\ngemini-2.5-flash-lite", ""));
                    }
                    ed.apply();
                    runOnUiThread(new Runnable() { public void run() { cb.got(m); } });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        public void run() { toast("שגיאה בטעינת מודלים: " + Agent.hebrew(e)); }
                    });
                }
            }
        }).start();
    }

    void pickModel() {
        withModels(false, new ModelsCb() {
            public void got(List<String> models) {
                final String[] arr = models.toArray(new String[0]);
                String cur = prefs.getString("model", DEF_MODEL);
                new AlertDialog.Builder(MainActivity.this, dlg())
                    .setTitle("בחר מודל")
                    .setSingleChoiceItems(arr, Arrays.asList(arr).indexOf(cur), new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            prefs.edit().putString("model", arr[w]).apply();
                            d.dismiss();
                            showSettings();
                        }
                    }).show();
            }
        });
    }

    void pickBackups() {
        withModels(false, new ModelsCb() {
            public void got(List<String> models) {
                final String[] arr = models.toArray(new String[0]);
                final boolean[] chk = new boolean[arr.length];
                List<String> cur = fbModels();
                boolean never = !prefs.contains("fbmodels");
                for (int i = 0; i < arr.length; i++) chk[i] = never || cur.contains(arr[i]);
                new AlertDialog.Builder(MainActivity.this, dlg())
                    .setTitle("מודלי גיבוי (לפי הסדר מהרשימה)")
                    .setMultiChoiceItems(arr, chk, new DialogInterface.OnMultiChoiceClickListener() {
                        public void onClick(DialogInterface d, int w, boolean on) { chk[w] = on; }
                    })
                    .setPositiveButton("שמור", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < arr.length; i++) if (chk[i]) sb.append(arr[i]).append('\n');
                            prefs.edit().putString("fbmodels", sb.toString().trim()).apply();
                            showSettings();
                        }
                    })
                    .setNegativeButton("ביטול", null).show();
            }
        });
    }

    LinearLayout card(LinearLayout list, String heading) {
        LinearLayout c = vert();
        c.setBackground(rr(SURF, 20));
        c.setPadding(dp(16), dp(14), dp(16), dp(10));
        TextView h = tv(heading, 13, ACC);
        h.setTypeface(null, Typeface.BOLD);
        h.setPadding(0, 0, 0, dp(6));
        c.addView(h);
        LinearLayout.LayoutParams l = new LinearLayout.LayoutParams(M, W);
        l.bottomMargin = dp(12);
        list.addView(c, l);
        return c;
    }

    TextView note(String s) {
        TextView t = tv(s, 12, DIM);
        t.setPadding(0, dp(2), 0, dp(8));
        return t;
    }

    View row(String title, String value, Runnable onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        r.addView(tv(title, 15, TXT), lp(0, W, 1f));
        r.addView(tv(value + "  \u2039", 13, DIM));
        click(r, onClick);
        return r;
    }

    TextView pillBtn(String label, Runnable onClick) {
        TextView b = tv(label, 14, ACC);
        b.setBackground(rr(SURF2, 20));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        click(b, onClick);
        return b;
    }

    Switch sw(String label, boolean on, final String key) {
        Switch s = new Switch(this);
        s.setText(label);
        s.setTextColor(TXT);
        s.setTextSize(14);
        s.setChecked(on);
        s.setPadding(0, dp(8), 0, dp(8));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                prefs.edit().putBoolean(key, v).apply();
            }
        });
        return s;
    }

    EditText field(String hint, String value, boolean secret, int extraType) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextColor(TXT);
        e.setHintTextColor(DIM);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | extraType | (secret ? InputType.TYPE_TEXT_VARIATION_PASSWORD : 0));
        imeOnTouch(e);
        return e;
    }

    void bind(EditText e, final String key, final boolean number) {
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(Editable s) {
                String v = s.toString().trim();
                if (number) {
                    int n = 5;
                    try { n = Math.max(0, Math.min(600, Integer.parseInt(v))); } catch (Exception ignored) { }
                    prefs.edit().putInt(key, n).apply();
                } else {
                    prefs.edit().putString(key, v).apply();
                }
            }
        });
    }

    ScrollView pageSv;

    /** Rebuilding a screen must not jump back to the top: remember where the user was. */
    int keepScroll(String name) {
        return screen.equals(name) && pageSv != null ? pageSv.getScrollY() : 0;
    }

    ScrollView pageScroll(final int y) {
        final ScrollView sv = new ScrollView(this);
        sv.setDescendantFocusability(android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        sv.setFocusable(true);
        sv.setFocusableInTouchMode(true);   // the scroll view takes focus, not the first text field
        pageSv = sv;
        if (y > 0) sv.post(new Runnable() { public void run() { sv.scrollTo(0, y); } });
        return sv;
    }

    void showSettings() {
        int y = keepScroll("settings");
        screen = "settings";
        onSetup = false;
        LinearLayout col = vert();
        col.setBackgroundColor(BG);
        col.addView(topBar("הגדרות"));
        ScrollView sv = pageScroll(y);
        LinearLayout list = vert();
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        sv.addView(list);
        col.addView(sv, lp(M, 0, 1f));

        // --- appearance
        LinearLayout c0 = card(list, "מראה");
        String tl = prefs.getString("theme", "system");
        c0.addView(row("ערכת נושא", tl.equals("light") ? "בהיר" : tl.equals("dark") ? "כהה" : "לפי המערכת",
            new Runnable() { public void run() { pickTheme(); } }));

        // --- API key
        LinearLayout c = card(list, "מפתח Gemini API");
        final EditText k = field("הדבק כאן את המפתח", prefs.getString("key", ""), true, 0);
        bind(k, "key", false);
        final TextView eye = tv("הצג", 13, ACC);
        eye.setPadding(dp(12), dp(8), 0, dp(8));
        click(eye, new Runnable() {
            public void run() {
                boolean hidden = k.getTransformationMethod() instanceof PasswordTransformationMethod;
                k.setTransformationMethod(hidden ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                eye.setText(hidden ? "הסתר" : "הצג");
            }
        });
        LinearLayout kr = new LinearLayout(this);
        kr.setGravity(Gravity.CENTER_VERTICAL);
        kr.addView(k, lp(0, W, 1f));
        kr.addView(eye);
        c.addView(kr);
        LinearLayout keyHelp = new LinearLayout(this);
        keyHelp.setGravity(Gravity.CENTER_VERTICAL);
        keyHelp.setPadding(0, dp(2), 0, dp(8));
        keyHelp.addView(tv("יצירת מפתח חינמי: ", 12, DIM));
        TextView keyLink = tv("aistudio.google.com/apikey", 13, ACC);
        keyLink.setPaintFlags(keyLink.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        keyLink.setPadding(0, dp(6), 0, dp(6));   // bigger touch area
        click(keyLink, new Runnable() { public void run() { openUrl("https://aistudio.google.com/apikey"); } });
        keyHelp.addView(keyLink);
        c.addView(keyHelp);
        c.addView(note("המפתח נשמר רק במכשיר."));
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(W, W);
        bl.bottomMargin = dp(6);
        c.addView(pillBtn("בדוק מפתח ורענן רשימת מודלים", new Runnable() {
            public void run() {
                withModels(true, new ModelsCb() {
                    public void got(List<String> m) { toast("המפתח תקין. נמצאו " + m.size() + " מודלים"); }
                });
            }
        }), bl);

        // --- models
        c = card(list, "מודלים");
        c.addView(row("מודל ראשי", prefs.getString("model", DEF_MODEL), new Runnable() { public void run() { pickModel(); } }));
        c.addView(sw("מעבר אוטומטי למודל גיבוי כשנגמרת המכסה", prefs.getBoolean("fb", true), "fb"));
        c.addView(row("מודלי גיבוי", fbModels().size() + " נבחרו", new Runnable() { public void run() { pickBackups(); } }));
        c.addView(note("במכסה החינמית לכל מודל יש מכסה נפרדת, אז כדאי לסמן כמה מודלי גיבוי."));

        // --- free tier
        c = card(list, "מכסה חינמית");
        c.addView(sw("מצב מכסה חינמית: להמתין ולנסות שוב במקום להיכשל", prefs.getBoolean("free", true), "free"));
        LinearLayout rr = new LinearLayout(this);
        rr.setGravity(Gravity.CENTER_VERTICAL);
        rr.addView(tv("מקסימום בקשות בדקה לכל מודל", 15, TXT), lp(0, W, 1f));
        EditText rpm = field("5", String.valueOf(prefs.getInt("rpm", 5)), false, InputType.TYPE_CLASS_NUMBER);
        rpm.setInputType(InputType.TYPE_CLASS_NUMBER);
        rpm.setGravity(Gravity.CENTER);
        bind(rpm, "rpm", true);
        rr.addView(rpm, new LinearLayout.LayoutParams(dp(64), W));
        c.addView(rr);
        c.addView(note("כשמודל מגיע למגבלת הדקה או למכסה היומית, הסוכן עובר מיד למודל הגיבוי הבא. הוא ממתין רק כשכל המודלים תפוסים באותה דקה, "
            + "ואם המכסה היומית נגמרה בכולם, השיחה נשמרת ואפשר להמשיך אחר כך. בחשבון בתשלום אפשר לכבות את המצב."));

        // --- agent
        c = card(list, "התנהגות הסוכן");
        c.addView(sw("מצב אוטונומי: תיקונים רגילים בלי לשאול", prefs.getBoolean("auto", false), "auto"));
        c.addView(note("פעולות מסוכנות (מחיקה, הסרת אפליקציות, setprop, iptables ועוד) תמיד דורשות אישור."));

        // --- screen access without root
        c = card(list, "גישה למסך בלי root");
        c.addView(row("שירות נגישות (צילום וקריאת מסך)", isAccessOn() ? "פעיל" : "כבוי", new Runnable() {
            public void run() {
                if (isAccessOn()) { toast("כבר פעיל"); return; }
                if (canWriteSecure()) { enableAccessibility(); toast("מפעיל…"); showSettings(); return; }
                toast("הפעל את Phone Doctor ברשימה. אם האפשרות אפורה: מידע על האפליקציה > ⋮ > אפשר הגדרות מוגבלות");
                try { startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } catch (Exception ignored) { }
            }
        }));
        c.addView(row("עוזר דיגיטלי (לחיצה ארוכה על Home)", !assistSupported() ? "לא נתמך" : isAssistant() ? "פעיל" : "כבוי", new Runnable() {
            public void run() {
                if (!assistSupported()) { toast("עוזר דיגיטלי נתמך מאנדרואיד 6 ומעלה"); return; }
                if (isAssistant()) { openAssistSettings(); return; }
                if (setAssistantRoot(new Runnable() { public void run() { showSettings(); } })) return;
                openAssistSettings();
            }
        }));
        c.addView(note("בעזרת שני אלה הסוכן רואה ומצלם את המסך גם בלי root, ובלחיצה ארוכה על Home הוא מקבל צילום של האפליקציה שבה אתה נמצא."));
        c.addView(row("אריח הגדרות מהירות \"צלם ושאל\"", "הוסף", new Runnable() { public void run() { addTile(); } }));
        c.addView(note("האריח מצלם את המסך שמאחורי ההגדרות המהירות ופותח את הצ'אט עם הצילום מצורף. דורש את שירות הנגישות."));

        // --- permissions
        c = card(list, "הרשאות מתקדמות");
        c.addView(row("Root", rootOk ? "פעיל" : "לא אושר", new Runnable() { public void run() { requestRootNow(); } }));
        c.addView(note(suPresent() ? "לחיצה מבקשת root (Magisk). ההרשאות לא ניתנות אוטומטית: כל אחת מאשרים בנפרד, או ב\"אשר הכל\" במסך ההרשאות."
            : "לא זוהה Magisk במכשיר. אם יש לך root מוסתר, לחיצה תנסה בכל זאת."));
        c.addView(row("מנהל מכשיר", isDeviceOwner() ? "\u2713 Device Owner" : isAdminActive() ? "\u2713 מנהל פעיל" : "\u2717 כבוי", new Runnable() { public void run() { setupAdmin(); } }));
        if (isDeviceOwner() || isAdminActive()) {
            c.addView(row("הסר מנהל מכשיר", "", new Runnable() {
                public void run() {
                    new AlertDialog.Builder(MainActivity.this, dlg()).setMessage("להסיר את Phone Doctor כמנהל מכשיר? הסוכן יאבד את יכולת ניהול ההרשאות בלי root.")
                        .setPositiveButton("הסר", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { removeAdmin(); }
                        }).setNegativeButton("ביטול", null).show();
                }
            }));
        }
        c.addView(adbBlock());
        c.addView(note("חבר את הטלפון למחשב עם ניפוי באגים והרץ את הפקודות. Device Owner אפשר להגדיר רק דרך ADB (או root), ורק כשאין חשבונות מוגדרים במכשיר. "
            + "רק כ-Device Owner הסוכן יכול לתת ולשלול הרשאות לכל אפליקציה, להסתיר ולהשעות אפליקציות בלי root. מנהל מכשיר רגיל (מההגדרות) לא נותן לו את היכולות האלה."));
        c.addView(row("הגדרת כל ההרשאות מחדש", "", new Runnable() {
            public void run() {
                skipped.clear();
                prefs.edit().remove("skipped").apply();
                showSetup(true);
            }
        }));

        // --- history
        c = card(list, "היסטוריה");
        c.addView(row("מחק את כל השיחות", "", new Runnable() {
            public void run() {
                new AlertDialog.Builder(MainActivity.this, dlg())
                    .setMessage("למחוק את כל השיחות השמורות?")
                    .setPositiveButton("מחק", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            if (Core.running) { toast("הסוכן עדיין עובד"); return; }
                            store.deleteAll();
                            Core.curId = null;
                            Core.agent = null;
                            Core.uiLog = new JSONArray();
                            toast("נמחק");
                        }
                    }).setNegativeButton("ביטול", null).show();
            }
        }));
        c.addView(note("פלט הבדיקות וצילומי המסך נשלחים ל-Gemini לניתוח."));

        c = card(list, "זיכרון ומעקב");
        c.addView(row("זיכרון המכשיר", Memory.count("fact") + " עובדות, " + Memory.count("recipe") + " תיקונים", new Runnable() { public void run() { showMemory(); } }));
        c.addView(note("מה שהסוכן למד על הטלפון הזה ותיקונים שעבדו. הוא משתמש בזה בכל שיחה חדשה. לא נשמר מידע אישי."));
        Switch watchSw = new Switch(this);
        watchSw.setText("מעקב ברקע: התראה כשמשהו לא תקין");
        watchSw.setTextColor(TXT);
        watchSw.setTextSize(14);
        watchSw.setChecked(prefs.getBoolean("watch", false));
        watchSw.setPadding(0, dp(8), 0, dp(8));
        watchSw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                Background.setWatch(MainActivity.this, v);
                toast(v ? "המעקב פעיל: בדיקה כל כמה שעות, בלי שימוש במכסה" : "המעקב כבוי");
            }
        });
        c.addView(watchSw);
        c.addView(note("כל כמה שעות, בלי AI ובלי מכסה: אחסון כמעט מלא, סוללה שיורדת מהר, אפליקציה שקורסת שוב ושוב (דורש root או הרשאת DUMP). התראה רק כשנמצאה בעיה."));
        c.addView(row("גישה להתראות (היסטוריית התראות)", NotifyService.enabled(this) ? "פעיל" : "כבוי", new Runnable() {
            public void run() {
                if (NotifyService.enabled(MainActivity.this)) { toast("כבר פעיל"); return; }
                if (rootOk && Build.VERSION.SDK_INT >= 23) {
                    new Thread(new Runnable() {
                        public void run() {
                            Shell.run("cmd notification allow_listener " + NotifyService.component(MainActivity.this), true, 15);
                            runOnUiThread(new Runnable() { public void run() { showSettings(); toast(NotifyService.enabled(MainActivity.this) ? "הופעל" : "לא הצלחתי. הפעל ידנית"); } });
                        }
                    }).start();
                    return;
                }
                toast("הפעל את Phone Doctor ברשימה");
                try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (Exception e) { toast("המסך לא נמצא במכשיר"); }
            }
        }));
        c.addView(note("נשמרים רק אפליקציה ושעה, בלי תוכן ההתראה. כך הסוכן יודע אם התראה בכלל הגיעה."));
        final long bkKb = AppBackup.totalKb(this);
        c.addView(row("גיבויי אפליקציות", bkKb > 0 ? (bkKb / 1024) + " MB" : "אין", new Runnable() {
            public void run() {
                String l = AppBackup.list(MainActivity.this, "");
                AlertDialog.Builder b = new AlertDialog.Builder(MainActivity.this, dlg()).setTitle("גיבויי אפליקציות").setMessage(l).setPositiveButton("סגור", null);
                if (bkKb > 0) b.setNeutralButton("מחק הכל", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        new Thread(new Runnable() { public void run() {
                            AppBackup.deleteAll(MainActivity.this, rootOk);
                            runOnUiThread(new Runnable() { public void run() { toast("הגיבויים נמחקו"); showSettings(); } });
                        } }).start();
                    }
                });
                b.show();
            }
        }));

        c = card(list, "יומן שגיאות Gemini");
        c.addView(row("שגיאות שמורות", String.valueOf(ErrorLog.count()), new Runnable() { public void run() { showErrors(); } }));
        c.addView(note("כל שגיאה שחזרה מ-Gemini או מהרשת, עם הטקסט המקורי, המודל והשעה. אפשר להעתיק ולשלוח למפתח."));

        c = card(list, "יומן שינויים");
        int ac = Journal.activeCount();
        c.addView(row("שינויים שאפשר לבטל", String.valueOf(ac), new Runnable() { public void run() { showJournal(); } }));
        c.addView(note("כל פעולה של הסוכן נרשמת עם כפתור \"בטל\", ולפני מחיקה נשמר גיבוי."));

        c = card(list, "אודות");
        c.addView(row("הצהרת סיכון ואחריות", "", new Runnable() { public void run() { showTerms(true); } }));
        String ver = "";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView about = tv("Phone Doctor " + ver, 12, DIM);
        about.setGravity(Gravity.CENTER);
        about.setPadding(0, dp(8), 0, 0);
        list.addView(about);
        TextView credit = tv("נבנה על ידי @נוחעם-FM מפורום מתמחים טופ", 13, ACC);
        credit.setGravity(Gravity.CENTER);
        credit.setPaintFlags(credit.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        credit.setPadding(0, dp(6), 0, dp(12));
        click(credit, new Runnable() {
            public void run() {
                openUrl("https://mitmachim.top/user/%D7%A0%D7%95%D7%97%D7%A2%D7%9D-fm");
            }
        });
        list.addView(credit);
        show(col);
    }
}
