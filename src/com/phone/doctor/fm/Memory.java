package com.phone.doctor.fm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * What the agent learned about THIS phone, kept across chats: stable device facts and fixes that were
 * verified to work ("recipes"). It is added to every request, so a new chat does not start from zero.
 */
final class Memory {
    static final int MAX_FACTS = 40, MAX_RECIPES = 30, MAX_LEN = 300;
    private static JSONObject cache;

    private static File file() { return new File(Core.app.getFilesDir(), "memory.json"); }

    private static synchronized JSONObject load() {
        if (cache != null) return cache;
        try {
            File f = file();
            if (f.exists()) {
                FileInputStream in = new FileInputStream(f);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                in.close();
                cache = new JSONObject(bo.toString("UTF-8"));
            }
        } catch (Exception ignored) { }
        if (cache == null) cache = new JSONObject();
        return cache;
    }

    private static synchronized void save() {
        try {
            FileOutputStream o = new FileOutputStream(file());
            o.write(load().toString().getBytes("UTF-8"));
            o.close();
        } catch (Exception ignored) { }
    }

    private static JSONArray arr(String kind) {
        JSONObject m = load();
        String k = kind.equals("recipe") ? "recipes" : "facts";
        JSONArray a = m.optJSONArray(k);
        if (a == null) { a = new JSONArray(); try { m.put(k, a); } catch (Exception ignored) { } }
        return a;
    }

    /** Adds or replaces (same text) an entry. Returns a short status for the agent. */
    static synchronized String add(String kind, String text) {
        text = text == null ? "" : text.trim().replace('\n', ' ');
        if (text.isEmpty()) return "empty";
        if (text.length() > MAX_LEN) text = text.substring(0, MAX_LEN);
        JSONArray a = arr(kind);
        for (int i = 0; i < a.length(); i++) if (text.equalsIgnoreCase(a.optString(i))) return "already saved";
        a.put(text);
        int max = kind.equals("recipe") ? MAX_RECIPES : MAX_FACTS;
        while (a.length() > max) a.remove(0);
        save();
        return "saved";
    }

    /** Removes entries containing the given text (either kind). */
    static synchronized int forget(String text) {
        int n = 0;
        String t = text == null ? "" : text.trim().toLowerCase();
        if (t.isEmpty()) return 0;
        for (String kind : new String[]{"fact", "recipe"}) {
            JSONArray a = arr(kind), keep = new JSONArray();
            for (int i = 0; i < a.length(); i++) { if (a.optString(i).toLowerCase().contains(t)) n++; else keep.put(a.optString(i)); }
            try { load().put(kind.equals("recipe") ? "recipes" : "facts", keep); } catch (Exception ignored) { }
        }
        if (n > 0) save();
        return n;
    }

    static int count(String kind) { return arr(kind).length(); }

    static String[] items(String kind) {
        JSONArray a = arr(kind);
        String[] r = new String[a.length()];
        for (int i = 0; i < a.length(); i++) r[i] = a.optString(i);
        return r;
    }

    static synchronized void clear() {
        cache = new JSONObject();
        save();
    }

    /** Block for the system prompt, or "" when empty. */
    static String prompt() {
        StringBuilder sb = new StringBuilder();
        JSONArray f = arr("fact"), r = arr("recipe");
        if (f.length() == 0 && r.length() == 0) return "";
        sb.append("DEVICE MEMORY (saved in earlier chats on this phone; trust it, but re-check if it matters for a change):\n");
        for (int i = 0; i < f.length(); i++) sb.append("- ").append(f.optString(i)).append('\n');
        if (r.length() > 0) {
            sb.append("PROVEN FIXES on this phone (problem -> fix that was verified to work):\n");
            for (int i = 0; i < r.length(); i++) sb.append("- ").append(r.optString(i)).append('\n');
        }
        return sb.toString();
    }
}
