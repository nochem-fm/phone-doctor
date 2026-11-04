package com.phone.doctor.fm;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Process-wide state of the conversation and the running agent. The agent lives here (not in the Activity),
 * so it keeps working in the background through AgentService and the UI simply re-attaches to it.
 */
final class Core {
    static Context app;
    static Store store;
    static MainActivity ui;            // currently attached activity (or null)
    static Agent agent;
    static JSONArray uiLog = new JSONArray();
    static String curId;
    static volatile boolean running;
    static String status = "";
    static JSONArray steps = new JSONArray();   // step log of the current run (also embedded in uiLog)
    static final Object LOCK = new Object();
    static volatile Pending pending;
    static String queue;               // message typed while the agent was busy
    static String lastReply = "";
    static String assistB64, assistText;   // screen captured by the digital-assistant gesture / quick-settings tile
    static long assistAt;                   // when it was captured: an old capture is never attached

    static void setAssist(String b64, String txt) {
        assistB64 = b64;
        assistText = txt;
        assistAt = System.currentTimeMillis();
    }

    static void clearAssist() {
        assistB64 = null;
        assistText = null;
        assistAt = 0;
    }

    /** A capture waiting to be attached to the next message (expires after 15 minutes). */
    static boolean hasAssist() {
        if ((assistB64 == null && (assistText == null || assistText.isEmpty()))) return false;
        if (System.currentTimeMillis() - assistAt > 15 * 60000L) { clearAssist(); return false; }
        return true;
    }
    static final Handler H = new Handler(Looper.getMainLooper());

    static final class Pending {
        String purpose, cmd, risk;
        boolean root, ok, shown;
        String[] options;      // set: this is a question with buttons (ask_user), not an approval
        String answer;
        final CountDownLatch latch = new CountDownLatch(1);
    }

    static void init(Context c) {
        if (app == null) {
            app = c.getApplicationContext();
            store = new Store(app);
        }
    }

    static SharedPreferences prefs() { return app.getSharedPreferences("s", 0); }

    static JSONObject entry(String k, String t) {
        try { return new JSONObject().put("k", k).put("t", t); } catch (Exception e) { return new JSONObject(); }
    }

    static void add(JSONObject e) {
        synchronized (LOCK) { uiLog.put(e); }
        if (ui != null) ui.onEntry(e);
    }

    static void ensureAgent() {
        if (agent == null) agent = new Agent(app, AU, prefs().getBoolean("root", false));
    }

    static void stopRun() {
        queue = null;
        Shell.cancel = true;
        Shell.Job.stopAll();
        if (agent != null) agent.abort();
        Pending p = pending;
        if (p != null) { p.ok = false; p.latch.countDown(); }
        status = "עוצר…";
        H.post(new Runnable() { public void run() { if (ui != null) ui.onStatus(); } });
    }

    static void saveChat() {
        if (curId == null || uiLog.length() == 0) return;
        String title = "שיחה";
        for (int i = 0; i < uiLog.length(); i++) {
            JSONObject e = uiLog.optJSONObject(i);
            if (e != null && e.optString("k").equals("u")) {
                title = e.optString("t").replace('\n', ' ');
                if (title.length() > 45) title = title.substring(0, 45) + "…";
                break;
            }
        }
        synchronized (LOCK) {
            store.save(curId, title, uiLog, agent == null ? new JSONArray() : agent.exportContents());
        }
    }

    // ------------------------------------------------------------ run

    static void send(String text) {
        SharedPreferences p = prefs();
        ensureAgent();
        if (!hasAssist()) {
            String local = null;
            // never answer locally when the agent just asked the user something: the message is a reply, not a new question
            String lr = lastReply == null ? "" : lastReply.trim();
            boolean replying = lr.length() > 0 && lr.substring(Math.max(0, lr.length() - 300)).contains("?")
                && uiLog.length() > 0 && !uiLog.optJSONObject(uiLog.length() - 1).optString("t").contains("נענה מקומית");
            try { if (!replying) local = LocalAnswers.answer(app, text); } catch (Exception ignored) { }
            if (local != null) {   // simple factual question: answered on the phone, no request, no quota
                if (curId == null) curId = String.valueOf(System.currentTimeMillis());
                add(entry("u", text));
                lastReply = local;
                add(entry("a", local));
                agent.note(text, local);
                saveChat();
                if (ui != null) ui.onFinished();
                return;
            }
        }
        boolean free = p.getBoolean("free", true);
        agent.rootOk = p.getBoolean("root", false);
        agent.key = p.getString("key", "");
        agent.model = p.getString("model", "gemini-2.5-flash");
        agent.retry = free;
        agent.rpm = free ? p.getInt("rpm", 5) : 0;
        agent.fallbackOn = p.getBoolean("fb", true);
        List<String> fb = new ArrayList<String>();
        // default (never edited): every model the key can use is a backup
        String fbs = p.contains("fbmodels") ? p.getString("fbmodels", "") : "gemini-2.5-flash-lite\n" + p.getString("models", "");
        for (String s : fbs.split("\n")) if (!s.trim().isEmpty()) fb.add(s.trim());
        agent.fallbacks = fb;
        agent.auto = p.getBoolean("auto", false);
        agent.scoped = true;   // locked: phone topics only
        agent.cacheDir = app.getCacheDir().getAbsolutePath();
        agent.stop = false;
        Shell.cancel = false;
        agent.actions = 0;

        String shown = text;
        if (hasAssist()) {
            if (assistB64 != null) agent.attachImage(assistB64);
            if (assistText != null && !assistText.isEmpty())
                agent.attachText("[Text on the screen the user was looking at when they invoked the assistant]\n" + assistText);
            shown += assistB64 != null ? "\n\uD83D\uDCCE צילום מסך מצורף" : "\n\uD83D\uDCCE טקסט המסך מצורף";
            clearAssist();
        }
        if (curId == null) curId = String.valueOf(System.currentTimeMillis());
        steps = new JSONArray();
        add(entry("u", shown));
        try { add(new JSONObject().put("k", "s").put("steps", steps)); } catch (Exception ignored) { }
        running = true;
        status = "חושב…";
        saveChat();
        try { Compat.startFg(app, new Intent(app, AgentService.class)); } catch (Exception ignored) { }
        final Agent a = agent;
        final String msg = text;
        new Thread(new Runnable() {
            public void run() {
                Exception err = null;
                if (a.rootOk) {   // re-ask the root manager once per request: if the user revoked root, stop using it
                    Shell.RootShell.reset();
                    boolean ok = Shell.run("id", true, 25).out.contains("uid=0");
                    if (!ok) { a.rootOk = false; prefs().edit().putBoolean("root", false).apply(); }
                }
                try { a.send(msg); } catch (Exception e) { err = e; }
                final Exception fe = err;
                H.post(new Runnable() { public void run() { finish(a, fe); } });
            }
        }).start();
    }

    static void finish(Agent a, Exception fe) {
        running = false;
        if (fe != null) {
            boolean q = fe instanceof Agent.QuotaException;
            JSONObject e = entry("e", q ? fe.getMessage() : "שגיאה: " + Agent.hebrew(fe));
            ErrorLog.add("shown", a.lastModelName(), 0, "user saw: " + Agent.hebrew(fe) + "\ncause: " + fe);
            try { e.put("q", q); } catch (Exception ignored) { }
            add(e);
        }
        status = a.actions > 0 ? "בוצעו " + a.actions + " פעולות" : "";
        saveChat();
        try { app.stopService(new Intent(app, AgentService.class)); } catch (Exception ignored) { }
        if (ui != null) ui.onFinished();
        else {
            String txt = fe != null ? Agent.hebrew(fe) : lastReply;
            AgentService.alert(app, 2, "Phone Doctor סיים", txt.length() > 300 ? txt.substring(0, 300) + "…" : txt);
        }
        String q = queue;
        queue = null;
        if (q != null && fe == null) send(q);
    }

    // ------------------------------------------------------------ approvals

    static void onPending() {
        Pending p = pending;
        if (p == null) return;
        if (ui != null && ui.resumed) { ui.showPending(); return; }
        bringFront();
        AgentService.alert(app, 3, "Phone Doctor ממתין לאישור שלך", p.purpose);
    }

    static void bringFront() {
        try {
            app.startActivity(new Intent(app, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception e) {
            if (prefs().getBoolean("root", false))
                Shell.run("am start --user 0 -n " + app.getPackageName() + "/.MainActivity", true, 10);
        }
    }

    // ------------------------------------------------------------ what the agent calls back into

    static final Agent.UI AU = new Agent.UI() {
        public void status(final String s) {
            H.post(new Runnable() {
                public void run() {
                    status = s;
                    if (ui != null) ui.onStatus();
                    AgentService.update(app, s);
                }
            });
        }

        public void reply(final String t) {
            H.post(new Runnable() {
                public void run() {
                    lastReply = t;
                    add(entry("a", t));
                }
            });
        }

        public int step(String type, String title, String detail) {
            int idx;
            synchronized (LOCK) {
                try {
                    steps.put(new JSONObject().put("type", type).put("title", title).put("detail", detail).put("state", "run"));
                } catch (Exception ignored) { }
                idx = steps.length() - 1;
            }
            H.post(new Runnable() { public void run() { if (ui != null) ui.onStatus(); } });
            return idx;
        }

        public void stepDone(int idx, boolean ok, String out, String img) {
            synchronized (LOCK) {
                JSONObject s = steps.optJSONObject(idx);
                if (s != null) {
                    try {
                        s.put("state", ok ? "ok" : "fail");
                        if (out != null) s.put("out", out.length() > 3000 ? out.substring(0, 3000) + "\n…" : out);
                        if (img != null) s.put("img", img);
                    } catch (Exception ignored) { }
                }
            }
            H.post(new Runnable() { public void run() { if (ui != null) ui.onStatus(); } });
        }

        public void stepUndo(int idx, long jid, int level) {
            synchronized (LOCK) {
                JSONObject s = steps.optJSONObject(idx);
                if (s != null) {
                    try { s.put("jid", jid).put("jlevel", level); } catch (Exception ignored) { }
                }
            }
            H.post(new Runnable() { public void run() { if (ui != null) ui.onStatus(); } });
        }

        public void model(final String m) {
            H.post(new Runnable() { public void run() { add(entry("m", m)); } });
        }

        public String choose(String question, String[] options) {
            Pending p = new Pending();
            p.purpose = question;
            p.cmd = "";
            p.risk = "";
            p.options = options;
            pending = p;
            H.post(new Runnable() { public void run() { onPending(); } });
            try { p.latch.await(15, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
            pending = null;
            return p.answer;
        }

        public boolean confirm(String purpose, String cmd, boolean root, String risk) {
            Pending p = new Pending();
            p.purpose = purpose;
            p.cmd = cmd;
            p.root = root;
            p.risk = risk;
            pending = p;
            H.post(new Runnable() { public void run() { onPending(); } });
            try { p.latch.await(10, TimeUnit.MINUTES); } catch (InterruptedException ignored) { }
            pending = null;
            return p.ok;
        }
    };
}
