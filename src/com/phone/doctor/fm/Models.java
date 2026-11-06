package com.phone.doctor.fm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lists the models this API key can use, so the user never has to type a model name. */
final class Models {
    static List<String> fetch(String key) throws Exception {
        HttpURLConnection c = Net.open("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000");
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setRequestProperty("x-goog-api-key", key);
        int code = c.getResponseCode();
        String s = Agent.read(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            String msg = s;
            try { msg = new JSONObject(s).getJSONObject("error").getString("message"); } catch (Exception ignored) { }
            ErrorLog.add("models", "", code, s);
            throw new Agent.ApiError(code, msg);
        }
        List<String> out = new ArrayList<String>();
        JSONArray arr = new JSONObject(s).optJSONArray("models");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String n = o.optString("name").replace("models/", "");
            JSONArray me = o.optJSONArray("supportedGenerationMethods");
            boolean gen = false;
            if (me != null) for (int j = 0; j < me.length(); j++) if ("generateContent".equals(me.optString(j))) gen = true;
            if (!gen || !n.startsWith("gemini")) continue;
            if (!isChat(n)) continue;
            out.add(n);
        }
        Collections.sort(out);
        return out;
    }

    /** Gemini models that can chat and call tools (not speech, image, embedding, transcription or experimental codenames). */
    static boolean isChat(String n) {
        n = n.toLowerCase();
        String[] no = {"tts", "image", "live", "audio", "embedding", "robotics", "computer-use", "transcribe", "banana", "imagen", "veo",
            "lyria", "aqa", "learnlm", "gemma", "customtools", "argon"};
        for (String x : no) if (n.contains(x)) return false;
        return true;
    }
}
