package com.phone.doctor.fm;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Other AI companies besides Gemini. The agent keeps its conversation in Gemini's format; for a model id
 * "provider:model" the request is translated to that provider's API and the answer translated back, so the
 * whole agent (tools, screenshots, quota switching between models) works the same with every provider.
 *
 *  anthropic  - Claude, Messages API
 *  openai, openrouter, groq, deepseek, xai, mistral, custom - OpenAI-compatible Chat Completions API
 */
final class Providers {
    static final String[] IDS = {"anthropic", "openai", "openrouter", "groq", "deepseek", "xai", "mistral", "custom"};
    static final String[] NAMES = {"Claude (Anthropic)", "ChatGPT (OpenAI)", "OpenRouter", "Groq", "DeepSeek", "Grok (xAI)", "Mistral", "שרת מותאם (OpenAI-compatible)"};
    static final String[] KEY_URLS = {"https://console.anthropic.com/settings/keys", "https://platform.openai.com/api-keys", "https://openrouter.ai/keys",
        "https://console.groq.com/keys", "https://platform.deepseek.com/api_keys", "https://console.x.ai", "https://console.mistral.ai/api-keys", ""};

    static String base(String p) {
        switch (p) {
            case "openai": return "https://api.openai.com/v1";
            case "openrouter": return "https://openrouter.ai/api/v1";
            case "groq": return "https://api.groq.com/openai/v1";
            case "deepseek": return "https://api.deepseek.com/v1";
            case "xai": return "https://api.x.ai/v1";
            case "mistral": return "https://api.mistral.ai/v1";
            case "custom": {
                String b = Core.prefs().getString("base_custom", "").trim();
                while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
                return b;
            }
            default: return "https://api.anthropic.com/v1";
        }
    }

    static String name(String p) {
        for (int i = 0; i < IDS.length; i++) if (IDS[i].equals(p)) return NAMES[i];
        return p;
    }

    static String key(String p) { return Core.prefs().getString("key_" + p, "").trim(); }

    /** "anthropic:claude-x" -> "anthropic"; a Gemini model has no prefix. */
    static String providerOf(String model) {
        int i = model.indexOf(':');
        return i > 0 && !model.startsWith("models/") ? model.substring(0, i) : null;
    }

    static String modelOf(String model) { return model.substring(model.indexOf(':') + 1); }

    /** For showing a model in the UI: "Claude · claude-sonnet-4-5". */
    static String label(String model) {
        String p = providerOf(model);
        if (p == null) return model;
        String n = name(p);
        int sp = n.indexOf(' ');
        return (sp > 0 ? n.substring(0, sp) : n) + " · " + modelOf(model);
    }

    static boolean anyKey() {
        for (String p : IDS) if (!key(p).isEmpty()) return true;
        return false;
    }

    static final class Result { int code; String body; }

    // ------------------------------------------------------------------ model lists

    static List<String> models(String p) throws Exception {
        String k = key(p);
        if (k.isEmpty()) return new ArrayList<String>();
        if (p.equals("custom") && base(p).isEmpty()) throw new Exception("חסרה כתובת לשרת המותאם");
        HttpURLConnection c = Net.open(base(p) + "/models" + (p.equals("anthropic") ? "?limit=1000" : ""));
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        auth(c, p, k);
        int code = c.getResponseCode();
        String s = Agent.read(code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        if (code >= 400) {
            ErrorLog.add("models", p, code, s);
            throw new Agent.ApiError(code, errMsg(s));
        }
        List<String> out = new ArrayList<String>();
        JSONArray arr = new JSONObject(s).optJSONArray("data");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            String id = o.optString("id");
            String l = id.toLowerCase();
            if (id.isEmpty()) continue;
            if (l.contains("embed") || l.contains("tts") || l.contains("whisper") || l.contains("transcribe") || l.contains("audio") || l.contains("realtime")
                || l.contains("dall-e") || l.contains("image") || l.contains("moderation") || l.contains("guard") || l.contains("babbage") || l.contains("davinci")
                || l.contains("search") || l.contains("ocr")) continue;
            if (p.equals("openai") && !(l.startsWith("gpt") || l.startsWith("o1") || l.startsWith("o3") || l.startsWith("o4") || l.startsWith("chatgpt"))) continue;
            JSONArray sp = o.optJSONArray("supported_parameters");   // OpenRouter: keep models that can call tools
            if (sp != null && !sp.toString().contains("\"tools\"")) continue;
            out.add(p + ":" + id);
        }
        Collections.sort(out);
        return out;
    }

    private static void auth(HttpURLConnection c, String p, String k) {
        if (p.equals("anthropic")) {
            c.setRequestProperty("x-api-key", k);
            c.setRequestProperty("anthropic-version", "2023-06-01");
        } else {
            c.setRequestProperty("Authorization", "Bearer " + k);
        }
        if (p.equals("openrouter")) {
            c.setRequestProperty("HTTP-Referer", "https://github.com/nochem-fm/phone-doctor");
            c.setRequestProperty("X-Title", "Phone Doctor");
        }
    }

    static String errMsg(String s) {
        try {
            JSONObject e = new JSONObject(s).optJSONObject("error");
            if (e != null && e.has("message")) return e.optString("message");
        } catch (Exception ignored) { }
        return s;
    }

    // ------------------------------------------------------------------ the call

    /** Sends a Gemini-format body to model "provider:id". On success body is a Gemini-format response. */
    static Result call(String model, JSONObject gem, int readTimeoutMs) throws Exception {
        String p = providerOf(model), id = modelOf(model), k = key(p);
        Result r = new Result();
        if (k.isEmpty()) {
            r.code = 401;
            r.body = new JSONObject().put("error", new JSONObject().put("message", "אין מפתח API ל-" + name(p) + ". הוסף אותו בהגדרות.")).toString();
            return r;
        }
        boolean claude = p.equals("anthropic");
        JSONObject req = claude ? toAnthropic(gem, id) : toOpenAi(gem, id);
        HttpURLConnection c = Net.open(base(p) + (claude ? "/messages" : "/chat/completions"));
        Agent.current(c);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(20000);
        c.setReadTimeout(readTimeoutMs);
        c.setRequestProperty("Content-Type", "application/json");
        auth(c, p, k);
        c.getOutputStream().write(req.toString().getBytes("UTF-8"));
        r.code = c.getResponseCode();
        String s = Agent.read(r.code < 400 ? c.getInputStream() : c.getErrorStream());
        c.disconnect();
        Agent.current(null);
        if (r.code >= 400) { r.body = s; return r; }
        r.body = (claude ? fromAnthropic(new JSONObject(s)) : fromOpenAi(new JSONObject(s))).toString();
        return r;
    }

    // ---- Gemini -> Claude

    private static JSONObject toAnthropic(JSONObject gem, String model) throws Exception {
        JSONObject req = new JSONObject().put("model", model).put("max_tokens", 8192);
        String sys = systemText(gem);
        if (!sys.isEmpty()) req.put("system", sys);
        JSONArray tools = new JSONArray();
        for (JSONObject f : functions(gem))
            tools.put(new JSONObject().put("name", f.getString("name")).put("description", f.optString("description")).put("input_schema", f.getJSONObject("parameters")));
        if (tools.length() > 0) req.put("tools", tools);

        JSONArray msgs = new JSONArray();
        ArrayDeque<String[]> pending = new ArrayDeque<String[]>();   // {id, name} of tool calls awaiting results
        JSONArray contents = gem.optJSONArray("contents");
        int n = 0;
        for (int i = 0; contents != null && i < contents.length(); i++) {
            JSONObject ct = contents.getJSONObject(i);
            boolean asst = "model".equals(ct.optString("role"));
            JSONArray parts = ct.optJSONArray("parts");
            JSONArray blocks = new JSONArray(), tail = new JSONArray();
            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject pt = parts.getJSONObject(j);
                if (pt.optBoolean("thought")) continue;
                if (pt.has("text")) {
                    String t = pt.optString("text");
                    if (!t.trim().isEmpty()) (asst ? blocks : tail).put(new JSONObject().put("type", "text").put("text", t));
                } else if (pt.has("functionCall") && asst) {
                    JSONObject fc = pt.getJSONObject("functionCall");
                    String tid = "toolu_" + (n++) + "_" + i;
                    pending.add(new String[]{tid, fc.optString("name")});
                    blocks.put(new JSONObject().put("type", "tool_use").put("id", tid).put("name", fc.optString("name"))
                        .put("input", fc.optJSONObject("args") == null ? new JSONObject() : fc.getJSONObject("args")));
                } else if (pt.has("functionResponse") && !asst) {
                    JSONObject fr = pt.getJSONObject("functionResponse");
                    String tid = takeId(pending, fr.optString("name"));
                    if (tid == null) { tail.put(new JSONObject().put("type", "text").put("text", "[tool " + fr.optString("name") + " result] " + fr.opt("response"))); continue; }
                    blocks.put(new JSONObject().put("type", "tool_result").put("tool_use_id", tid).put("content", String.valueOf(fr.opt("response"))));
                } else if (pt.has("inlineData")) {
                    JSONObject d = pt.getJSONObject("inlineData");
                    JSONObject img = new JSONObject().put("type", "image").put("source", new JSONObject().put("type", "base64")
                        .put("media_type", d.optString("mimeType", "image/jpeg")).put("data", d.optString("data")));
                    if (asst) continue;
                    tail.put(img);
                }
            }
            if (!asst) {   // a tool call that never got a result (app closed, stopped): close it so the API accepts the history
                while (!pending.isEmpty()) {
                    String[] x = pending.poll();
                    if (!hasResult(blocks, x[0])) blocks.put(new JSONObject().put("type", "tool_result").put("tool_use_id", x[0]).put("content", "No result."));
                }
            }
            for (int j = 0; j < tail.length(); j++) blocks.put(tail.get(j));     // tool_result blocks must come first
            if (blocks.length() == 0) continue;
            add(msgs, asst ? "assistant" : "user", blocks);
        }
        if (msgs.length() == 0 || !"user".equals(msgs.getJSONObject(0).optString("role")))
            prepend(msgs, new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "(המשך)"))));
        if ("assistant".equals(msgs.getJSONObject(msgs.length() - 1).optString("role")))
            msgs.put(new JSONObject().put("role", "user").put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", "(המשך)"))));
        req.put("messages", msgs);
        return req;
    }

    private static boolean hasResult(JSONArray blocks, String id) {
        for (int i = 0; i < blocks.length(); i++) if (id.equals(blocks.optJSONObject(i).optString("tool_use_id"))) return true;
        return false;
    }

    private static String takeId(ArrayDeque<String[]> pending, String name) {
        for (String[] x : pending) if (x[1].equals(name)) { pending.remove(x); return x[0]; }
        String[] f = pending.poll();
        return f == null ? null : f[0];
    }

    /** Claude needs alternating roles: merge consecutive messages of the same role. */
    private static void add(JSONArray msgs, String role, JSONArray blocks) throws Exception {
        if (msgs.length() > 0) {
            JSONObject last = msgs.getJSONObject(msgs.length() - 1);
            if (role.equals(last.optString("role"))) {
                JSONArray c = last.getJSONArray("content");
                for (int i = 0; i < blocks.length(); i++) c.put(blocks.get(i));
                return;
            }
        }
        msgs.put(new JSONObject().put("role", role).put("content", blocks));
    }

    private static void prepend(JSONArray a, JSONObject o) throws Exception {
        for (int i = a.length(); i > 0; i--) a.put(i, a.get(i - 1));
        a.put(0, o);
    }

    private static JSONObject fromAnthropic(JSONObject r) throws Exception {
        JSONArray parts = new JSONArray();
        JSONArray c = r.optJSONArray("content");
        for (int i = 0; c != null && i < c.length(); i++) {
            JSONObject b = c.getJSONObject(i);
            if ("text".equals(b.optString("type"))) parts.put(new JSONObject().put("text", b.optString("text")));
            else if ("tool_use".equals(b.optString("type")))
                parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", b.optString("name")).put("args", b.optJSONObject("input") == null ? new JSONObject() : b.getJSONObject("input"))));
        }
        return gemini(parts, r.optString("stop_reason"));
    }

    // ---- Gemini -> OpenAI-compatible

    private static JSONObject toOpenAi(JSONObject gem, String model) throws Exception {
        JSONObject req = new JSONObject().put("model", model);
        JSONArray msgs = new JSONArray();
        String sys = systemText(gem);
        if (!sys.isEmpty()) msgs.put(new JSONObject().put("role", "system").put("content", sys));
        JSONArray tools = new JSONArray();
        for (JSONObject f : functions(gem))
            tools.put(new JSONObject().put("type", "function").put("function", new JSONObject().put("name", f.getString("name"))
                .put("description", f.optString("description")).put("parameters", f.getJSONObject("parameters"))));
        if (tools.length() > 0) req.put("tools", tools);

        ArrayDeque<String[]> pending = new ArrayDeque<String[]>();
        JSONArray contents = gem.optJSONArray("contents");
        int n = 0;
        for (int i = 0; contents != null && i < contents.length(); i++) {
            JSONObject ct = contents.getJSONObject(i);
            boolean asst = "model".equals(ct.optString("role"));
            JSONArray parts = ct.optJSONArray("parts");
            StringBuilder text = new StringBuilder();
            JSONArray calls = new JSONArray(), images = new JSONArray();
            List<JSONObject> toolMsgs = new ArrayList<JSONObject>();
            for (int j = 0; parts != null && j < parts.length(); j++) {
                JSONObject pt = parts.getJSONObject(j);
                if (pt.optBoolean("thought")) continue;
                if (pt.has("text")) text.append(text.length() > 0 ? "\n" : "").append(pt.optString("text"));
                else if (pt.has("functionCall") && asst) {
                    JSONObject fc = pt.getJSONObject("functionCall");
                    String tid = "call_" + (n++) + "_" + i;
                    pending.add(new String[]{tid, fc.optString("name")});
                    calls.put(new JSONObject().put("id", tid).put("type", "function").put("function", new JSONObject()
                        .put("name", fc.optString("name")).put("arguments", (fc.optJSONObject("args") == null ? new JSONObject() : fc.getJSONObject("args")).toString())));
                } else if (pt.has("functionResponse") && !asst) {
                    JSONObject fr = pt.getJSONObject("functionResponse");
                    String tid = takeId(pending, fr.optString("name"));
                    if (tid == null) { text.append("\n[tool ").append(fr.optString("name")).append(" result] ").append(fr.opt("response")); continue; }
                    toolMsgs.add(new JSONObject().put("role", "tool").put("tool_call_id", tid).put("content", String.valueOf(fr.opt("response"))));
                } else if (pt.has("inlineData") && !asst) {
                    JSONObject d = pt.getJSONObject("inlineData");
                    images.put(new JSONObject().put("type", "image_url").put("image_url", new JSONObject().put("url", "data:" + d.optString("mimeType", "image/jpeg") + ";base64," + d.optString("data"))));
                }
            }
            if (asst) {
                JSONObject m = new JSONObject().put("role", "assistant");
                m.put("content", text.length() > 0 ? text.toString() : JSONObject.NULL);
                if (calls.length() > 0) m.put("tool_calls", calls);
                if (text.length() > 0 || calls.length() > 0) msgs.put(m);
            } else {
                for (JSONObject t : toolMsgs) msgs.put(t);
                while (!pending.isEmpty()) {   // unanswered tool calls
                    String[] x = pending.poll();
                    msgs.put(new JSONObject().put("role", "tool").put("tool_call_id", x[0]).put("content", "No result."));
                }
                if (images.length() > 0) {
                    JSONArray cont = new JSONArray();
                    cont.put(new JSONObject().put("type", "text").put("text", text.length() > 0 ? text.toString() : "[screenshot]"));
                    for (int j = 0; j < images.length(); j++) cont.put(images.get(j));
                    msgs.put(new JSONObject().put("role", "user").put("content", cont));
                } else if (text.length() > 0) {
                    msgs.put(new JSONObject().put("role", "user").put("content", text.toString()));
                }
            }
        }
        req.put("messages", msgs);
        return req;
    }

    private static JSONObject fromOpenAi(JSONObject r) throws Exception {
        JSONArray parts = new JSONArray();
        JSONArray ch = r.optJSONArray("choices");
        String finish = "";
        if (ch != null && ch.length() > 0) {
            JSONObject c0 = ch.getJSONObject(0);
            finish = c0.optString("finish_reason");
            JSONObject m = c0.optJSONObject("message");
            if (m != null) {
                Object content = m.opt("content");
                if (content instanceof String && !((String) content).isEmpty()) parts.put(new JSONObject().put("text", content));
                JSONArray tc = m.optJSONArray("tool_calls");
                for (int i = 0; tc != null && i < tc.length(); i++) {
                    JSONObject f = tc.getJSONObject(i).optJSONObject("function");
                    if (f == null) continue;
                    JSONObject args;
                    try { args = new JSONObject(f.optString("arguments", "{}")); } catch (Exception e) { args = new JSONObject(); }
                    parts.put(new JSONObject().put("functionCall", new JSONObject().put("name", f.optString("name")).put("args", args)));
                }
            }
        }
        return gemini(parts, finish);
    }

    // ---- shared

    private static JSONObject gemini(JSONArray parts, String finish) throws Exception {
        JSONObject cand = new JSONObject().put("finishReason", finish == null || finish.isEmpty() ? "STOP" : finish.toUpperCase());
        if (parts.length() > 0) cand.put("content", new JSONObject().put("role", "model").put("parts", parts));
        return new JSONObject().put("candidates", new JSONArray().put(cand));
    }

    private static String systemText(JSONObject gem) {
        JSONObject si = gem.optJSONObject("systemInstruction");
        JSONArray p = si == null ? null : si.optJSONArray("parts");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; p != null && i < p.length(); i++) sb.append(p.optJSONObject(i).optString("text"));
        return sb.toString();
    }

    private static List<JSONObject> functions(JSONObject gem) {
        List<JSONObject> out = new ArrayList<JSONObject>();
        JSONArray t = gem.optJSONArray("tools");
        for (int i = 0; t != null && i < t.length(); i++) {
            JSONArray d = t.optJSONObject(i).optJSONArray("functionDeclarations");
            for (int j = 0; d != null && j < d.length(); j++) out.add(d.optJSONObject(j));
        }
        return out;
    }
}
