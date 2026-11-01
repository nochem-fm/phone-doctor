package com.phone.doctor.fm;

import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only file diagnostics for the agent: is a file damaged, what fills the storage, duplicates. */
final class FileTools {
    private static final long TIME_LIMIT = 20000;

    static String run(String path, String mode, boolean rootOk) {
        if (path == null || path.trim().isEmpty()) path = "/sdcard";
        path = path.trim();
        File f = new File(path);
        try {
            switch (mode == null ? "check" : mode) {
                case "usage": return usage(f, rootOk);
                case "duplicates": return duplicates(f);
                default: return check(f, rootOk);
            }
        } catch (Throwable t) {
            return "ERROR: " + t;
        }
    }

    // ---------------------------------------------------------------- damaged files

    private static String check(File f, boolean rootOk) {
        if (!f.exists()) {
            if (rootOk && Shell.run("[ -e '" + f.getPath().replace("'", "'\\''") + "' ] && echo y", true, 10).out.contains("y"))
                return checkViaRoot(f);
            return "NOT FOUND (or no permission to see it): " + f.getPath();
        }
        if (f.isDirectory()) {
            StringBuilder sb = new StringBuilder("Folder " + f.getPath() + "\n");
            File[] l = f.listFiles();
            if (l == null) return sb.append("cannot list (no permission). Try with root, or grant 'all files access'.").toString();
            int n = 0, bad = 0;
            long end = System.currentTimeMillis() + TIME_LIMIT;
            for (File x : l) {
                if (!x.isFile()) continue;
                if (n >= 200 || System.currentTimeMillis() > end) { sb.append("...stopped after ").append(n).append(" files\n"); break; }
                n++;
                String r = checkFile(x);
                if (!r.startsWith("OK")) { bad++; sb.append(x.getName()).append(": ").append(r).append('\n'); }
            }
            return sb.append("checked ").append(n).append(" files, problems: ").append(bad).toString();
        }
        if (!f.canRead() && rootOk) return checkViaRoot(f);
        return f.getPath() + ": " + checkFile(f);
    }

    private static String checkViaRoot(File f) {
        File tmp = new File(Core.app.getCacheDir(), "fchk_" + f.getName());
        String q = f.getPath().replace("'", "'\\''");
        Shell.Result r = Shell.run("s=$(stat -c %s '" + q + "' 2>/dev/null); [ \"${s:-0}\" -le 104857600 ] && cp '" + q + "' '" + tmp.getPath() + "' && chmod 666 '" + tmp.getPath() + "' && echo copied", true, 60);
        if (!r.out.contains("copied")) return f.getPath() + ": cannot read even with root, or file larger than 100MB";
        String res = checkFile(tmp);
        tmp.delete();
        return f.getPath() + " (read with root): " + res;
    }

    static String type(byte[] h, int n) {
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) return "jpg";
        if (n >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G') return "png";
        if (n >= 3 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F') return "gif";
        if (n >= 4 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F') return "pdf";
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) return "zip";
        if (n >= 4 && h[0] == 'R' && h[1] == 'a' && h[2] == 'r' && h[3] == '!') return "rar";
        if (n >= 2 && h[0] == '7' && h[1] == 'z') return "7z";
        if (n >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') {
            if (h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P') return "webp";
            if (h[8] == 'W' && h[9] == 'A' && h[10] == 'V' && h[11] == 'E') return "wav";
            if (h[8] == 'A' && h[9] == 'V' && h[10] == 'I') return "avi";
        }
        if (n >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p') return "mp4";
        if (n >= 3 && h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
        if (n >= 2 && (h[0] & 0xFF) == 0xFF && ((h[1] & 0xE0) == 0xE0)) return "mp3";
        if (n >= 4 && h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        if (n >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45 && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3) return "mkv";
        if (n >= 4 && h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        return "unknown";
    }

    private static String family(String ext) {
        switch (ext) {
            case "jpg": case "jpeg": return "jpg";
            case "docx": case "xlsx": case "pptx": case "apk": case "jar": case "odt": case "epub": case "zip": return "zip";
            case "m4a": case "mov": case "3gp": case "mp4": case "heic": return "mp4";
            case "mkv": case "webm": return "mkv";
            default: return ext;
        }
    }

    static String checkFile(File f) {
        long size = f.length();
        if (!f.canRead()) return "NO READ PERMISSION";
        if (size == 0) return "EMPTY FILE (0 bytes): the content is missing; it cannot be opened";
        byte[] h = new byte[16];
        int n;
        try {
            InputStream in = new FileInputStream(f);
            n = in.read(h);
            in.close();
        } catch (Exception e) {
            return "READ ERROR: " + e.getMessage();
        }
        String real = type(h, n);
        String name = f.getName().toLowerCase(Locale.US);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
        String note = "";
        if (!real.equals("unknown") && !ext.isEmpty() && !family(ext).equals(real))
            note = " | extension ." + ext + " but content is " + real + " (renaming may fix opening)";
        String content;
        switch (real) {
            case "zip": content = zipCheck(f); break;
            case "jpg": case "png": case "gif": case "webp": content = imageCheck(f); break;
            case "pdf": content = pdfCheck(f); break;
            case "mp4": case "mp3": case "ogg": case "wav": case "mkv": case "flac": case "avi": content = mediaCheck(f); break;
            default: content = "type not recognized from content (" + ext + "), size " + size + " bytes";
        }
        return content + note;
    }

    private static String zipCheck(File f) {
        if (f.length() > 300L * 1024 * 1024) return "OK? zip too large to test fully (" + (f.length() >> 20) + " MB)";
        try {
            ZipFile z = new ZipFile(f);
            int n = 0;
            byte[] b = new byte[16384];
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                InputStream in = z.getInputStream(en);
                while (in.read(b) > 0) { }   // reading checks the CRC of every entry
                in.close();
                n++;
            }
            z.close();
            return "OK zip/archive, " + n + " entries, all readable";
        } catch (Exception e) {
            return "DAMAGED archive: " + e.getMessage();
        }
    }

    private static String imageCheck(File f) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        if (o.outWidth <= 0) return "DAMAGED image: header cannot be read";
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = Math.max(1, Math.max(o.outWidth, o.outHeight) / 512);
        android.graphics.Bitmap bm = BitmapFactory.decodeFile(f.getPath(), o2);
        if (bm == null) return "DAMAGED image: " + o.outWidth + "x" + o.outHeight + " but the data cannot be decoded";
        bm.recycle();
        if (o.outMimeType != null && o.outMimeType.contains("jpeg")) {
            try {   // a cut JPEG often still decodes: check the end marker
                RandomAccessFile r = new RandomAccessFile(f, "r");
                r.seek(Math.max(0, f.length() - 2));
                int a = r.read(), b = r.read();
                r.close();
                if (!(a == 0xFF && b == 0xD9)) return "PARTLY DAMAGED jpg: opens, but the end is missing (file was cut)";
            } catch (Exception ignored) { }
        }
        return "OK image " + o.outWidth + "x" + o.outHeight;
    }

    private static String pdfCheck(File f) {
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            int len = (int) Math.min(2048, f.length());
            r.seek(f.length() - len);
            byte[] b = new byte[len];
            r.readFully(b);
            r.close();
            return new String(b, "ISO-8859-1").contains("%%EOF") ? "OK pdf (complete)" : "DAMAGED pdf: the end of the file is missing (download/copy was cut)";
        } catch (Exception e) {
            return "READ ERROR: " + e.getMessage();
        }
    }

    private static String mediaCheck(File f) {
        MediaMetadataRetriever m = new MediaMetadataRetriever();
        try {
            m.setDataSource(f.getPath());
            String d = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (d == null || d.equals("0")) return "SUSPICIOUS media: no duration (may be damaged or unsupported codec)";
            String mime = m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);
            return "OK media, " + (Long.parseLong(d) / 1000) + " s" + (mime == null ? "" : ", " + mime);
        } catch (Exception e) {
            return "DAMAGED or unsupported media: " + e.getMessage();
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- what fills the storage

    private static long walk(File f, long end, int[] count) {
        if (System.currentTimeMillis() > end || count[0] > 300000) return 0;
        if (f.isFile()) { count[0]++; return f.length(); }
        File[] l = f.listFiles();
        if (l == null) return 0;
        long s = 0;
        for (File x : l) s += walk(x, end, count);
        return s;
    }

    private static String usage(File f, boolean rootOk) {
        if (rootOk && !f.canRead()) {
            String q = f.getPath().replace("'", "'\\''");
            return "(with root) KB  path\n" + Shell.run("du -sk '" + q + "'/* 2>/dev/null | sort -rn | head -25", true, 60).out;
        }
        File[] l = f.listFiles();
        if (l == null) return "cannot list " + f.getPath() + " (no permission)";
        final Map<File, Long> sizes = new HashMap<File, Long>();
        long end = System.currentTimeMillis() + TIME_LIMIT;
        int[] count = {0};
        for (File x : l) sizes.put(x, walk(x, end, count));
        List<File> sorted = new ArrayList<File>(sizes.keySet());
        Collections.sort(sorted, new Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(sizes.get(b), sizes.get(a)); }
        });
        StringBuilder sb = new StringBuilder("Biggest items in " + f.getPath() + ":\n");
        long total = 0;
        for (File x : sorted) total += sizes.get(x);
        for (int i = 0; i < Math.min(25, sorted.size()); i++) {
            File x = sorted.get(i);
            sb.append(mb(sizes.get(x))).append("  ").append(x.getName()).append(x.isDirectory() ? "/" : "").append('\n');
        }
        sb.append("total ").append(mb(total)).append(", files counted ").append(count[0]);
        if (System.currentTimeMillis() > end) sb.append(" (time limit reached, numbers are partial)");
        return sb.toString();
    }

    private static String mb(long b) {
        return b >= 1L << 30 ? String.format(Locale.US, "%.1f GB", b / 1073741824.0) : String.format(Locale.US, "%.1f MB", b / 1048576.0);
    }

    // ---------------------------------------------------------------- duplicates

    private static void collect(File f, List<File> out, long end) {
        if (System.currentTimeMillis() > end || out.size() > 20000) return;
        File[] l = f.listFiles();
        if (l == null) return;
        for (File x : l) {
            if (x.isDirectory()) { if (!x.getName().startsWith(".")) collect(x, out, end); }
            else if (x.length() >= 100 * 1024) out.add(x);
        }
    }

    private static String hash(File f, long limit) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        InputStream in = new FileInputStream(f);
        byte[] b = new byte[65536];
        long left = limit;
        int n;
        while (left > 0 && (n = in.read(b, 0, (int) Math.min(b.length, left))) > 0) { md.update(b, 0, n); left -= n; }
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static String duplicates(File root) {
        long end = System.currentTimeMillis() + TIME_LIMIT * 2;
        List<File> files = new ArrayList<File>();
        collect(root, files, end);
        Map<Long, List<File>> bySize = new HashMap<Long, List<File>>();
        for (File f : files) {
            List<File> l = bySize.get(f.length());
            if (l == null) { l = new ArrayList<File>(); bySize.put(f.length(), l); }
            l.add(f);
        }
        Map<String, List<File>> groups = new HashMap<String, List<File>>();
        for (List<File> same : bySize.values()) {
            if (same.size() < 2) continue;
            for (File f : same) {
                if (System.currentTimeMillis() > end) break;
                try {
                    String k = f.length() + ":" + hash(f, Math.min(f.length(), 8L * 1024 * 1024));
                    List<File> g = groups.get(k);
                    if (g == null) { g = new ArrayList<File>(); groups.put(k, g); }
                    g.add(f);
                } catch (Exception ignored) { }
            }
        }
        List<List<File>> dup = new ArrayList<List<File>>();
        for (List<File> g : groups.values()) if (g.size() > 1) dup.add(g);
        Collections.sort(dup, new Comparator<List<File>>() {
            public int compare(List<File> a, List<File> b) {
                return Long.compare(b.get(0).length() * (b.size() - 1), a.get(0).length() * (a.size() - 1));
            }
        });
        long wasted = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dup.size(); i++) {
            List<File> g = dup.get(i);
            wasted += g.get(0).length() * (g.size() - 1);
            if (i < 20) {
                sb.append(g.size()).append(" copies of ").append(mb(g.get(0).length())).append(":\n");
                for (File f : g) sb.append("   ").append(f.getPath()).append('\n');
            }
        }
        String head = "Scanned " + files.size() + " files >=100KB under " + root.getPath() + ". Duplicate groups: " + dup.size()
            + ", space that could be freed: " + mb(wasted) + (System.currentTimeMillis() > end ? " (time limit reached, partial)" : "") + "\n";
        return head + sb;
    }
}
