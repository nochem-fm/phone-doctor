package com.phone.doctor.fm;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * History of every error that came back from Gemini (or the network), with the raw text, the model and
 * the time. Shown in Settings and from any error in the chat, so a tester can copy the exact reason
 * instead of describing it.
 */
final class ErrorLog {
    private static final int MAX = 60;
    private static JSONArray cache;

    private static File file() { return new File(Core.app.getFilesDir(), "errors.json"); }

    private static synchronized JSONArray load() {
        if (cache != null) return cache;
        try {
            File f = file();
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                cache = new JSONArray(bo.toString("UTF-8"));
            }
        } catch (Exception ignored) { }
        if (cache == null) cache = new JSONArray();
        return cache;
    }

    /**
     * @param where  chat / summary / voice / models / shown (the message the user saw)
     * @param code   HTTP code, 0 when there was no HTTP answer
     */
    static synchronized void add(String where, String model, int code, String raw) {
        try {
            if (Core.app == null) return;
            JSONArray a = load();
            a.put(new JSONObject().put("t", System.currentTimeMillis()).put("w", where).put("m", model == null ? "" : model)
                .put("c", code).put("r", raw == null ? "" : raw.length() > 2500 ? raw.substring(0, 2500) + "…" : raw));
            while (a.length() > MAX) a.remove(0);
            FileOutputStream o = new FileOutputStream(file());
            o.write(a.toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    static synchronized int count() { return load().length(); }

    static synchronized void clear() {
        cache = new JSONArray();
        file().delete();
    }

    /** Newest first, ready to copy and paste. */
    static synchronized String text(int max) {
        JSONArray a = load();
        String ver = "";
        try { ver = Core.app.getPackageManager().getPackageInfo(Core.app.getPackageName(), 0).versionName; } catch (Exception ignored) { }
        StringBuilder sb = new StringBuilder("Phone Doctor " + ver + " | " + Build.MANUFACTURER + " " + Build.MODEL
            + " | Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n");
        SimpleDateFormat f = new SimpleDateFormat("dd/MM HH:mm:ss", Locale.US);
        int shown = 0;
        for (int i = a.length() - 1; i >= 0 && shown < max; i--, shown++) {
            JSONObject e = a.optJSONObject(i);
            if (e == null) continue;
            sb.append("\n[").append(f.format(new Date(e.optLong("t")))).append("] ").append(e.optString("w"));
            if (!e.optString("m").isEmpty()) sb.append(" | ").append(e.optString("m"));
            if (e.optInt("c") != 0) sb.append(" | HTTP ").append(e.optInt("c"));
            sb.append('\n').append(e.optString("r")).append('\n');
        }
        if (a.length() == 0) sb.append("\nאין שגיאות שמורות.");
        return sb.toString();
    }
}
