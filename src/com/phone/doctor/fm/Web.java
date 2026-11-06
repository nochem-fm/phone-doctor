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

    /** Which content filter blocked the request (NetFree, Rimon, Etrog...), or null. */
    static String filterBlock(int code, String finalUrl, String body) {
        String u = String.valueOf(finalUrl).toLowerCase(), b = body == null ? "" : body.toLowerCase();
        String[][] f = {{"netfree", "נטפרי (NetFree)"}, {"rimon", "רימון"}, {"etrog", "אתרוג"}, {"jnet", "JNet"}, {"meshimer", "משימר"}, {"koshernet", "כשר-נט"},
            {"nativ", "נתיב"}, {"internet-rimon", "רימון"}};
        for (String[] x : f) if (u.contains(x[0])) return x[1];
        if (code == 418 || code == 451 || b.contains("netfree") || b.contains("נטפרי")) return b.contains("rimon") || b.contains("רימון") ? "רימון" : "נטפרי (NetFree) או סינון דומה";
        if ((code == 403 || b.length() < 20000) && (b.contains("האתר חסום") || b.contains("הדף חסום") || b.contains("site is blocked") || b.contains("blocked by") || b.contains("סינון")))
            return "סינון אינטרנט (הדף חסום)";
        return null;
    }

    static final String BLOCK_NOTE = "\nBLOCKED BY THE INTERNET FILTER (%s). This is NOT a broken link or a phone problem: tell the user plainly that the filter blocks this file/page, "
        + "and that they can ask the filter to open it (NetFree: the request-to-open form on the block page or netfree.link; Rimon/Etrog: their support), "
        + "or get the file another way and you will install it from the phone. You may try one other official source; do not keep retrying.";

    /** {download/APK links, other links} of an HTML page; also data-url / data-href buttons (Uptodown and others). */
    static String[] links(String body, String base) {
        Set<String> links = new LinkedHashSet<String>();
        Matcher m = Pattern.compile("(?:href|data-url|data-href|data-link)\\s*=\\s*[\"']([^\"'#]+)[\"']", Pattern.CASE_INSENSITIVE).matcher(body);
        while (m.find() && links.size() < 400) {
            String h = m.group(1).replace("&amp;", "&").trim();
            if (h.startsWith("javascript:") || h.startsWith("mailto:")) continue;
            try { links.add(new URL(new URL(base), h).toString()); } catch (Exception ignored) { }
        }
        StringBuilder dl = new StringBuilder(), other = new StringBuilder();
        for (String l : links) {
            String x = l.toLowerCase();
            boolean d = x.contains(".apk") || x.contains("download") || x.contains("/dl/") || x.contains("/dwn/") || x.contains("key=")
                || x.contains("d.apkpure") || x.contains("releases/download");
            boolean asset = x.matches(".*\\.(css|js|png|jpg|jpeg|svg|webp|ico|woff2?|ttf)(\\?.*)?$");
            if (asset) continue;
            (d ? dl : other).append(l).append('\n');
        }
        return new String[]{dl.toString(), other.toString()};
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
        String blk = filterBlock(code, finalUrl, body.length() > 30000 ? body.substring(0, 30000) : body);
        StringBuilder sb = new StringBuilder("HTTP " + code + " " + type + "\nfinal url: " + finalUrl + "\n");
        if (blk != null) sb.append(String.format(BLOCK_NOTE, blk)).append('\n');
        sb.append('\n');
        if (type.contains("html")) {
            String[] lk = links(body, finalUrl);
            String text = body.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ").replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n");
            if (lk[0].length() > 0) sb.append("DOWNLOAD / APK LINKS (try these first):\n").append(lk[0]).append('\n');
            sb.append(text.trim().length() > max / 2 ? text.trim().substring(0, max / 2) : text.trim());
            sb.append("\nOTHER LINKS:\n").append(lk[1].length() > max / 3 ? lk[1].substring(0, max / 3) : lk[1]);
        } else {
            sb.append(body);
        }
        String s = sb.toString();
        return s.length() > max ? s.substring(0, max) + "\n...[cut at " + max + " chars]" : s;
    }

    static final class Download { String path, type, sha256, url, blocked, page; long size; int code; }

    /** Downloads into the app's own folder (readable by the installer). Max 400 MB. */
    static Download download(String url, String name, boolean executable, Agent.Stopper stop) throws Exception {
        HttpURLConnection c = get(url, 60000);
        Download d = new Download();
        d.code = c.getResponseCode();
        d.type = String.valueOf(c.getContentType());
        d.url = c.getURL().toString();
        if (d.code >= 400) {
            String body = "";
            try { InputStream es = c.getErrorStream(); if (es != null) body = Agent.read(es); } catch (Exception ignored) { }
            d.blocked = filterBlock(d.code, d.url, body.length() > 30000 ? body.substring(0, 30000) : body);
            c.disconnect();
            return d;
        }
        if (d.type.contains("text/html")) {   // a web page, not the file: do not save it, hand back its download links
            String body = "";
            try { body = Agent.read(c.getInputStream()); } catch (Exception ignored) { }
            c.disconnect();
            d.blocked = filterBlock(d.code, d.url, body.length() > 30000 ? body.substring(0, 30000) : body);
            if (d.blocked == null) {
                String[] lk = links(body, d.url);
                d.page = "NOT A FILE: " + d.url + " is a web page (text/html), nothing was saved. Use one of its download links below "
                    + "(often a second page leads to the final file; open it with fetch_url), or another source.\n"
                    + (lk[0].isEmpty() ? "No download links on this page (it may need JavaScript or be protected): try another source." : "DOWNLOAD LINKS ON THE PAGE:\n" + (lk[0].length() > 6000 ? lk[0].substring(0, 6000) : lk[0]));
            }
            return d;
        }
        if (name == null || name.trim().isEmpty()) {
            String cd = c.getHeaderField("Content-Disposition");
            Matcher m = cd == null ? null : Pattern.compile("filename\\*?=(?:UTF-8'')?\"?([^\";]+)").matcher(cd);
            name = m != null && m.find() ? m.group(1) : d.url.replaceAll("[?#].*", "").replaceAll(".*/", "");
            if (name.isEmpty()) name = "download.bin";
        }
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        // executables go to internal storage (shared storage is mounted noexec); targetSdk 28 allows running them
        File dir = executable ? new File(Core.app.getFilesDir(), "bin") : new File(Core.app.getExternalFilesDir(null), "downloads");
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
        if (executable) f.setExecutable(true, false);
        d.path = f.getAbsolutePath();
        if (total < 200000 && (d.type.contains("html") || d.type.contains("text"))) {   // a small page instead of the file: maybe a filter's block page
            try {
                java.io.FileInputStream fi = new java.io.FileInputStream(f);
                byte[] b = new byte[(int) total];
                int off = 0, k;
                while (off < b.length && (k = fi.read(b, off, b.length - off)) > 0) off += k;
                fi.close();
                d.blocked = filterBlock(d.code, d.url, new String(b, "UTF-8"));
            } catch (Exception ignored) { }
        }
        d.size = total;
        StringBuilder h = new StringBuilder();
        for (byte b : md.digest()) h.append(String.format("%02x", b));
        d.sha256 = h.toString();
        return d;
    }
}
