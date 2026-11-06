package com.phone.doctor.fm;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading web pages / APIs and downloading files for the agent (e.g. getting an APK straight from F-Droid or GitHub). */
final class Web {
    static final String UA = "Mozilla/5.0 (Linux; Android) PhoneDoctor";

    private static HttpURLConnection get(String url, int readMs) throws Exception {
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {   // follow redirects, also http <-> https (HttpURLConnection does not)
            c = Net.open(url);
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(readMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            return c;
        }
        return c;
    }

    /** Text of a page or API (JSON as is; HTML as text plus its links). */
    static String fetch(String url, int max) throws Exception {
        HttpURLConnection c = get(url, 30000);
        int code = c.getResponseCode();
        String type = String.valueOf(c.getContentType());
        String finalUrl = c.getURL().toString();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        if (type.contains("application/vnd.android") || type.contains("octet-stream") || type.contains("zip")) {
            c.disconnect();
            return "HTTP " + code + " " + type + " (a binary file: use download_file for it)\nfinal url: " + finalUrl;
        }
        String body = in == null ? "" : Agent.read(in);
        c.disconnect();
        StringBuilder sb = new StringBuilder("HTTP " + code + " " + type + "\nfinal url: " + finalUrl + "\n\n");
        if (type.contains("html")) {
            Set<String> links = new LinkedHashSet<String>();
            Matcher m = Pattern.compile("href\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(body);
            while (m.find() && links.size() < 300) {
                try { links.add(new URL(new URL(finalUrl), m.group(1).replace("&amp;", "&")).toString()); } catch (Exception ignored) { }
            }
            String text = body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n");
            StringBuilder apk = new StringBuilder(), other = new StringBuilder();
            for (String l : links) (l.toLowerCase().contains(".apk") ? apk : other).append(l).append('\n');
            sb.append(text.trim().length() > max / 2 ? text.trim().substring(0, max / 2) : text.trim());
            if (apk.length() > 0) sb.append("\n\nAPK LINKS:\n").append(apk);
            sb.append("\nLINKS:\n").append(other.length() > max / 3 ? other.substring(0, max / 3) : other);
        } else {
            sb.append(body);
        }
        String s = sb.toString();
        return s.length() > max ? s.substring(0, max) + "\n...[cut at " + max + " chars]" : s;
    }

    static final class Download { String path, type, sha256, url; long size; int code; }

    /** Downloads into the app's own folder (readable by the installer). Max 400 MB. */
    static Download download(String url, String name, Agent.Stopper stop) throws Exception {
        HttpURLConnection c = get(url, 60000);
        Download d = new Download();
        d.code = c.getResponseCode();
        d.type = String.valueOf(c.getContentType());
        d.url = c.getURL().toString();
        if (d.code >= 400) { c.disconnect(); return d; }
        if (name == null || name.trim().isEmpty()) {
            String cd = c.getHeaderField("Content-Disposition");
            Matcher m = cd == null ? null : Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd);
            name = m != null && m.find() ? m.group(1) : d.url.replaceAll("[?#].*", "").replaceAll(".*/", "");
            if (name.isEmpty()) name = "download.bin";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        File dir = new File(Core.app.getExternalFilesDir(null), "downloads");
        dir.mkdirs();
        File f = new File(dir, name);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        InputStream in = c.getInputStream();
        FileOutputStream o = new FileOutputStream(f);
        byte[] buf = new byte[65536];
        long total = 0;
        int n;
        try {
            while ((n = in.read(buf)) > 0) {
                if (stop != null && stop.stopped()) throw new Exception("stopped");
                o.write(buf, 0, n);
                md.update(buf, 0, n);
                total += n;
                if (total > 400L * 1024 * 1024) throw new Exception("file is larger than 400 MB");
            }
        } finally {
            o.close();
            in.close();
            c.disconnect();
        }
        f.setReadable(true, false);
        d.path = f.getAbsolutePath();
        d.size = total;
        StringBuilder h = new StringBuilder();
        for (byte b : md.digest()) h.append(String.format("%02x", b));
        d.sha256 = h.toString();
        return d;
    }
}
