package com.phone.doctor.fm;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;

/**
 * Microphone input for the chat box.
 * 1) The phone's own speech recognizer, in-app (no dialog), with live text while speaking.
 * 2) Backup when there is no recognizer (filtered phones without Google apps) or it fails:
 *    record AAC and let Gemini transcribe it.
 */
final class Voice {
    interface Listener {
        void state(int st);            // IDLE, LISTEN, RECORD, WORKING
        void partial(String text);     // live text (mode 1)
        void done(String text);        // final text
        void error(String hebrew);
        void info(String hebrew);
    }

    static final int IDLE = 0, LISTEN = 1, RECORD = 2, WORKING = 3;
    private static final int MAX_REC_MS = 60000;

    private final Context ctx;
    private final Listener l;
    private final Handler h = new Handler(Looper.getMainLooper());
    private SpeechRecognizer sr;
    private MediaRecorder rec;
    private File recFile;
    private int state = IDLE;
    private boolean heard;
    private int gen;   // bumped by cancel(): late results from an old session are dropped

    Voice(Context c, Listener l) {
        this.ctx = c;
        this.l = l;
    }

    int state() { return state; }

    private void set(int s) {
        state = s;
        l.state(s);
    }

    /** Mic button: start, or stop and finish what is in progress. */
    void toggle() {
        if (state == LISTEN) { if (sr != null) sr.stopListening(); return; }
        if (state == RECORD) { stopRecording(true); return; }
        if (state == WORKING) return;
        ComponentName svc = recognizer(ctx);
        if (svc != null) startRecognizer(svc); else startRecording(false);
    }

    void cancel() {
        gen++;
        h.removeCallbacksAndMessages(null);
        if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; }
        if (rec != null) stopRecording(false);
        set(IDLE);
    }

    // ------------------------------------------------------------------ 1. device speech recognizer

    /**
     * A real speech recognizer, chosen explicitly. Phone Doctor itself registers a stub recognizer
     * (needed for the digital-assistant role), so the system default may point back at us.
     */
    static ComponentName recognizer(Context c) {
        List<ResolveInfo> l;
        try {
            l = c.getPackageManager().queryIntentServices(new Intent("android.speech.RecognitionService"), 0);
        } catch (Exception e) {
            return null;
        }
        ComponentName best = null;
        int bestScore = -1;
        for (ResolveInfo r : l) {
            if (r.serviceInfo == null || c.getPackageName().equals(r.serviceInfo.packageName)) continue;
            String p = r.serviceInfo.packageName;
            int sc = p.equals("com.google.android.googlequicksearchbox") ? 3
                : p.equals("com.google.android.as") || p.equals("com.google.android.tts") ? 2 : 1;
            if (sc > bestScore) { bestScore = sc; best = new ComponentName(p, r.serviceInfo.name); }
        }
        return best;
    }

    private void startRecognizer(ComponentName svc) {
        try {
            sr = SpeechRecognizer.createSpeechRecognizer(ctx, svc);
        } catch (Exception e) {
            sr = null;
        }
        if (sr == null) { startRecording(true); return; }
        heard = false;
        final int myGen = gen;
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { }
            public void onBeginningOfSpeech() { heard = true; }
            public void onRmsChanged(float v) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { }
            public void onEvent(int t, Bundle b) { }

            public void onPartialResults(Bundle b) {
                if (myGen != gen) return;
                String t = first(b);
                if (t != null && !t.isEmpty()) l.partial(t);
            }

            public void onResults(Bundle b) {
                if (myGen != gen) return;
                String t = first(b);
                release();
                set(IDLE);
                if (t == null || t.trim().isEmpty()) l.error("לא שמעתי מה אמרת. נסה שוב.");
                else l.done(t.trim());
            }

            public void onError(int e) {
                if (myGen != gen) return;   // cancelled on purpose: do not fall back to recording
                release();
                // 6 speech timeout, 7 no match: the user simply did not speak
                if (e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || e == SpeechRecognizer.ERROR_NO_MATCH) {
                    set(IDLE);
                    l.error("לא שמעתי מה אמרת. נסה שוב.");
                    return;
                }
                if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    set(IDLE);
                    l.error("אין הרשאת מיקרופון לשירות זיהוי הדיבור של המכשיר.");
                    return;
                }
                // network, server, busy, client, language not supported (12/13)...: fall back to Gemini
                l.info("זיהוי הדיבור של המכשיר לא זמין (" + e + "). מקליט ושולח ל-Gemini…");
                startRecording(true);
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "he-IL");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        try {
            sr.startListening(i);
            set(LISTEN);
        } catch (Exception e) {
            release();
            startRecording(true);
        }
    }

    private void release() {
        if (sr != null) { try { sr.destroy(); } catch (Exception ignored) { } sr = null; }
    }

    private static String first(Bundle b) {
        if (b == null) return null;
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------------ 2. record + Gemini

    private void startRecording(boolean fallback) {
        if (Core.prefs().getString("key", "").isEmpty()) {
            set(IDLE);
            l.error("אין במכשיר שירות זיהוי דיבור, ולתמלול דרך Gemini צריך מפתח API בהגדרות.");
            return;
        }
        try {
            recFile = new File(ctx.getCacheDir(), "voice.aac");
            rec = new MediaRecorder();
            rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioChannels(1);
            rec.setAudioSamplingRate(16000);
            rec.setAudioEncodingBitRate(32000);
            rec.setMaxDuration(MAX_REC_MS);
            rec.setOutputFile(recFile.getAbsolutePath());
            rec.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                public void onInfo(MediaRecorder mr, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(true);
                }
            });
            rec.prepare();
            rec.start();
            set(RECORD);
            if (!fallback) l.info("מקליט… לחץ שוב על המיקרופון כשסיימת (עד דקה).");
        } catch (Exception e) {
            if (rec != null) { try { rec.release(); } catch (Exception ignored) { } rec = null; }
            set(IDLE);
            l.error("לא הצלחתי להפעיל את המיקרופון. בדוק שאפליקציה אחרת לא משתמשת בו.");
        }
    }

    private void stopRecording(boolean send) {
        MediaRecorder r = rec;
        rec = null;
        if (r == null) return;
        boolean ok = true;
        try { r.stop(); } catch (Exception e) { ok = false; }   // stop() throws when nothing was recorded
        try { r.release(); } catch (Exception ignored) { }
        if (!send) return;
        if (!ok || recFile == null || recFile.length() < 1500) {
            set(IDLE);
            l.error("ההקלטה קצרה מדי. החזק את המיקרופון ודבר, ואז לחץ שוב.");
            return;
        }
        set(WORKING);
        final File f = recFile;
        final int myGen = gen;
        new Thread(new Runnable() {
            public void run() {
                String text = null, err = null;
                try { text = transcribe(f); } catch (Exception e) { err = Agent.hebrew(e); }
                f.delete();
                final String t = text, er = err;
                h.post(new Runnable() {
                    public void run() {
                        if (myGen != gen) return;   // cancelled (message already sent)
                        set(IDLE);
                        if (er != null) l.error("התמלול נכשל: " + er);
                        else if (t == null || t.isEmpty()) l.error("לא שמעתי מה אמרת. נסה שוב.");
                        else l.done(t);
                    }
                });
            }
        }).start();
    }

    private static String transcribe(File f) throws Exception {
        byte[] audio = readAll(new FileInputStream(f));
        SharedPreferences p = Core.prefs();
        String key = p.getString("key", "");
        List<String> models = new ArrayList<String>();
        models.add(p.getString("model", "gemini-2.5-flash-lite"));
        if (p.getBoolean("fb", true))
            for (String s : (p.contains("fbmodels") ? p.getString("fbmodels", "") : p.getString("models", "")).split("\n")) if (!s.trim().isEmpty() && !models.contains(s.trim())) models.add(s.trim());
        JSONObject body = new JSONObject().put("contents", new JSONArray().put(new JSONObject().put("role", "user").put("parts", new JSONArray()
            .put(new JSONObject().put("inline_data", new JSONObject().put("mime_type", "audio/aac").put("data", Base64.encodeToString(audio, Base64.NO_WRAP))))
            .put(new JSONObject().put("text", "Transcribe this recording word for word, in the language spoken (usually Hebrew). "
                + "Output ONLY the transcription text, with no quotes, notes or translation. If nothing is said, output nothing.")))));
        byte[] data = body.toString().getBytes("UTF-8");
        Exception last = null;
        for (String m : models) {
            if (m.startsWith("models/")) m = m.substring(7);
            HttpURLConnection c = Net.open("https://generativelanguage.googleapis.com/v1beta/models/" + m + ":generateContent");
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(20000);
            c.setReadTimeout(90000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            OutputStream o = c.getOutputStream();
            o.write(data);
            o.close();
            int code = c.getResponseCode();
            String s = new String(readAll(code < 400 ? c.getInputStream() : c.getErrorStream()), "UTF-8");
            c.disconnect();
            if (code < 400) {
                StringBuilder sb = new StringBuilder();
                JSONArray cands = new JSONObject(s).optJSONArray("candidates");
                JSONObject ct = cands != null && cands.length() > 0 ? cands.getJSONObject(0).optJSONObject("content") : null;
                JSONArray parts = ct == null ? null : ct.optJSONArray("parts");
                for (int i = 0; parts != null && i < parts.length(); i++) {
                    JSONObject pt = parts.getJSONObject(i);
                    if (!pt.optBoolean("thought")) sb.append(pt.optString("text"));
                }
                return sb.toString().trim();
            }
            String msg = s;
            try { msg = new JSONObject(s).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            last = new Agent.ApiError(code, msg);
            if (code != 429 && code != 404 && code < 500) break;   // other models will not help
        }
        throw last;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        if (in != null) {
            while ((n = in.read(b)) > 0) bo.write(b, 0, n);
            in.close();
        }
        return bo.toByteArray();
    }
}
