package com.phone.doctor.fm;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Chat history on disk: one JSON file per conversation (visible messages + Gemini context). */
final class Store {
    static final class Meta {
        String id, title;
        long updated;
    }

    private final File dir;

    Store(Context c) {
        dir = new File(c.getFilesDir(), "chats");
        dir.mkdirs();
    }

    void save(String id, String title, JSONArray ui, JSONArray contents) {
        try {
            JSONObject j = new JSONObject().put("id", id).put("title", title)
                .put("updated", System.currentTimeMillis()).put("ui", ui).put("contents", contents);
            File tmp = new File(dir, id + ".tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            o.write(j.toString().getBytes("UTF-8"));
            o.close();
            tmp.renameTo(new File(dir, id + ".json"));
        } catch (Exception ignored) { }
    }

    JSONObject load(String id) {
        try { return new JSONObject(readFile(new File(dir, id + ".json"))); } catch (Exception e) { return null; }
    }

    List<Meta> list() {
        List<Meta> out = new ArrayList<Meta>();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject j = new JSONObject(readFile(f));
                Meta m = new Meta();
                m.id = j.getString("id");
                m.title = j.optString("title", "שיחה");
                m.updated = j.optLong("updated");
                out.add(m);
            } catch (Exception ignored) { }
        }
        Collections.sort(out, new Comparator<Meta>() {
            public int compare(Meta a, Meta b) { return Long.compare(b.updated, a.updated); }
        });
        return out;
    }

    void delete(String id) { new File(dir, id + ".json").delete(); }

    void deleteAll() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private static String readFile(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }
}
