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
                case "archive": return f.isDirectory() ? forEach(f, "archive") : archive(f);
                case "exif": return f.isDirectory() ? forEach(f, "exif") : exif(f);
                case "strip_location": return stripLocation(f);
                case "media": return f.isDirectory() ? forEach(f, "media") : media(f);
                case "media_deep": return f.isDirectory() ? "media_deep needs ONE file, not a folder. Use find_files to locate it." : mediaDeep(f);
                case "remux": return f.isDirectory() ? "remux needs ONE file, not a folder." : remux(f);
                case "large": return large(f, 50);
                case "downloads": return downloadCopies(f);
                case "doc": return f.isDirectory() ? forEach(f, "doc") : doc(f);
                case "deleted": return deleted(f);
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
            return "header OK (metadata only: " + (Long.parseLong(d) / 1000) + " s" + (mime == null ? "" : ", " + mime)
                + "). This does NOT prove the middle of the file is fine; use mode=media_deep for that.";
        } catch (Exception e) {
            return "DAMAGED or unsupported media: " + e.getMessage();
        } finally {
            try { m.release(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- a folder given to a per-file mode

    private static boolean wanted(String mode, String n) {
        n = n.toLowerCase(Locale.US);
        switch (mode) {
            case "media": return n.matches(".*\\.(mp4|mkv|avi|mov|3gp|webm|ts|m4v|mp3|m4a|aac|ogg|opus|wav|flac|amr)$");
            case "exif": return n.matches(".*\\.(jpe?g|heic|dng)$");
            case "archive": return n.matches(".*\\.(zip|rar|7z|apk|apks|xapk|jar)$");
            default: return n.matches(".*\\.(pdf|docx?|xlsx?|pptx?|odt|ods)$");
        }
    }

    /** Runs a per-file mode on every matching file in a folder (max 25), so a folder never looks like "one broken file". */
    private static String forEach(File d, String mode) {
        File[] l = d.listFiles();
        if (l == null) return "cannot list " + d.getPath() + " (no permission)";
        StringBuilder sb = new StringBuilder("FOLDER " + d.getPath() + " (mode " + mode + " runs per file):\n");
        int n = 0;
        for (File x : l) {
            if (!x.isFile() || !wanted(mode, x.getName())) continue;
            if (++n > 25) { sb.append("...more files not checked\n"); break; }
            String r = mode.equals("media") ? media(x) : mode.equals("exif") ? exif(x) : mode.equals("archive") ? archive(x) : doc(x);
            sb.append("- ").append(x.getName()).append(": ").append(r.replace("\n", " | ")).append('\n');
        }
        if (n == 0) sb.append("no matching files in this folder. Use find_files to locate the file.");
        return sb.toString();
    }

    // ---------------------------------------------------------------- deep media scan: holes in the middle of a file

    /**
     * Reads EVERY sample of every track (no decoding) and looks for what makes "the picture freezes but the sound
     * goes on": gaps in the video timestamps, a video track that ends early, unreadable or empty samples, and a
     * resolution/profile the hardware decoder cannot handle.
     */
    private static String mediaDeep(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        long end = System.currentTimeMillis() + 60000;
        try {
            ex.setDataSource(f.getPath());
            int tracks = ex.getTrackCount();
            StringBuilder sb = new StringBuilder("DEEP SCAN of " + f.getName() + " (" + mb(f.length()) + "), every sample read:\n");
            long[] first = new long[tracks], last = new long[tracks], maxGap = new long[tracks], gapAt = new long[tracks], bytes = new long[tracks];
            int[] count = new int[tracks], gaps = new int[tracks], empty = new int[tracks], sync = new int[tracks];
            String[] mime = new String[tracks];
            long[] expect = new long[tracks];
            List<String> gapList = new ArrayList<String>();
            for (int i = 0; i < tracks; i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                mime[i] = mf.getString(android.media.MediaFormat.KEY_MIME);
                ex.selectTrack(i);
                first[i] = -1;
                int fps = 0;
                try { if (mf.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) fps = mf.getInteger(android.media.MediaFormat.KEY_FRAME_RATE); } catch (Exception ignored) { }
                expect[i] = mime[i] != null && mime[i].startsWith("video/") ? (fps > 0 ? 1000000L / fps : 40000L) : 100000L;
            }
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024);
            String readErr = null;
            boolean timeout = false;
            while (true) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                if (System.currentTimeMillis() > end) { timeout = true; break; }
                long pts = ex.getSampleTime();
                int size;
                try { buf.clear(); size = ex.readSampleData(buf, 0); } catch (Exception e) { readErr = "read error at " + (pts / 1000000) + "s in track " + t + ": " + e.getMessage(); break; }
                if (size <= 0) empty[t]++; else bytes[t] += size;
                if ((ex.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0) sync[t]++;
                if (first[t] < 0) first[t] = pts;
                if (count[t] > 0) {
                    long g = pts - last[t];
                    if (g > Math.max(1000000L, expect[t] * 10)) {   // a hole of 1s+ (B-frames make small reorderings normal)
                        gaps[t]++;
                        if (gapList.size() < 15) gapList.add("track " + t + " (" + mime[t] + "): no data from " + fmt(last[t]) + " to " + fmt(pts) + " (" + (g / 1000) + " ms)");
                        if (g > maxGap[t]) { maxGap[t] = g; gapAt[t] = last[t]; }
                    }
                }
                if (pts > last[t]) last[t] = pts;
                count[t]++;
                if (!ex.advance()) break;
            }
            long longest = 0;
            for (int i = 0; i < tracks; i++) longest = Math.max(longest, last[i]);
            for (int i = 0; i < tracks; i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                sb.append("track ").append(i).append(' ').append(mime[i]);
                if (mf.containsKey(android.media.MediaFormat.KEY_WIDTH)) sb.append(' ').append(mf.getInteger(android.media.MediaFormat.KEY_WIDTH)).append('x').append(mf.getInteger(android.media.MediaFormat.KEY_HEIGHT));
                long dur = last[i] - Math.max(0, first[i]);
                sb.append(": ").append(count[i]).append(" samples, ").append(fmt(Math.max(0, first[i]))).append(" to ").append(fmt(last[i]));
                if (dur > 0) sb.append(", avg ").append(bytes[i] * 8 / Math.max(1, dur / 1000000) / 1000).append(" kbps");
                if (mime[i] != null && mime[i].startsWith("video/")) {
                    sb.append(", keyframes ").append(sync[i]);
                    if (sync[i] > 0 && dur > 0) sb.append(" (one every ").append(dur / sync[i] / 1000).append(" ms)");
                    if (longest - last[i] > 3000000L) sb.append(" | VIDEO ENDS ").append((longest - last[i]) / 1000000).append(" s BEFORE THE AUDIO");
                    if (android.os.Build.VERSION.SDK_INT >= 21 && mf.containsKey(android.media.MediaFormat.KEY_WIDTH))
                        sb.append(" | ").append(Compat.decoderCan(mime[i], mf.getInteger(android.media.MediaFormat.KEY_WIDTH), mf.getInteger(android.media.MediaFormat.KEY_HEIGHT)));
                }
                if (gaps[i] > 0) sb.append(" | GAPS: ").append(gaps[i]).append(", longest ").append(maxGap[i] / 1000).append(" ms at ").append(fmt(gapAt[i]));
                if (empty[i] > 0) sb.append(" | empty samples: ").append(empty[i]);
                sb.append('\n');
            }
            if (!gapList.isEmpty()) sb.append("holes:\n  ").append(android.text.TextUtils.join("\n  ", gapList)).append('\n');
            if (readErr != null) sb.append("READ ERROR: ").append(readErr).append(" (the file is damaged from this point)\n");
            if (timeout) sb.append("(stopped after 60 s; the rest of the file was not scanned)\n");
            sb.append("Note: this scan reads the compressed data; it cannot see damage INSIDE a frame's picture data, only missing/unreadable/mis-timed frames.\n");
            return sb.toString();
        } catch (Exception e) {
            return "Cannot read the media container: " + e.getMessage();
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
        }
    }

    private static String fmt(long us) {
        long s = us / 1000000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    // ---------------------------------------------------------------- rebuild an MP4 without re-encoding

    /**
     * Copies the audio and video streams into a NEW clean MP4 (no re-encoding, so quality is identical and it is
     * fast). Fixes broken indexes, bad interleaving and odd containers; it does not make the video lighter.
     * Writes "<name>_fixed.mp4"; the original is not touched.
     */
    private static String remux(File f) {
        if (android.os.Build.VERSION.SDK_INT < 18) return "FAILED: needs Android 4.3+";
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        android.media.MediaMuxer mx = null;
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        File out = new File(f.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + "_fixed.mp4");
        try {
            ex.setDataSource(f.getPath());
            mx = new android.media.MediaMuxer(out.getPath(), android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int n = ex.getTrackCount();
            int[] map = new int[n];
            int used = 0;
            StringBuilder skipped = new StringBuilder();
            for (int i = 0; i < n; i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                String m = mf.getString(android.media.MediaFormat.KEY_MIME);
                map[i] = -1;
                try {
                    map[i] = mx.addTrack(mf);
                    ex.selectTrack(i);
                    used++;
                } catch (Exception e) {
                    skipped.append(m).append(' ');
                }
            }
            if (used == 0) { out.delete(); return "FAILED: no track can go into MP4 (" + skipped + "). Converting needs a converter app or a computer."; }
            mx.start();
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(8 * 1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            long t0 = System.currentTimeMillis();
            int samples = 0;
            while (true) {
                int t = ex.getSampleTrackIndex();
                if (t < 0) break;
                if (System.currentTimeMillis() - t0 > 240000) { mx.stop(); mx.release(); out.delete(); return "FAILED: took too long (file too big)"; }
                int size = ex.readSampleData(buf, 0);
                if (size > 0 && map[t] >= 0) {
                    info.offset = 0;
                    info.size = size;
                    info.presentationTimeUs = ex.getSampleTime();
                    info.flags = (ex.getSampleFlags() & android.media.MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? android.media.MediaCodec.BUFFER_FLAG_SYNC_FRAME : 0;
                    mx.writeSampleData(map[t], buf, info);
                    samples++;
                }
                if (!ex.advance()) break;
            }
            mx.stop();
            mx.release();
            mx = null;
            return "OK new file " + out.getPath() + " (" + mb(out.length()) + ", " + samples + " samples, no re-encoding; original unchanged)"
                + (skipped.length() > 0 ? ". Tracks left out (not allowed in MP4): " + skipped : "") + ". Ask the user to play the new file.";
        } catch (Exception e) {
            out.delete();
            return "FAILED: " + e.getMessage();
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
            if (mx != null) { try { mx.release(); } catch (Exception ignored) { } }
        }
    }

    // ---------------------------------------------------------------- find a file by (part of) its name, any language

    static String find(android.content.Context c, String query, String kind) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        File root = android.os.Environment.getExternalStorageDirectory();
        List<File> all = new ArrayList<File>();
        long end = System.currentTimeMillis() + TIME_LIMIT;
        findWalk(root, q, kind == null ? "" : kind, all, end, 0);
        StringBuilder sb = new StringBuilder();
        Collections.sort(all, new Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); } });
        for (int i = 0; i < Math.min(30, all.size()); i++) {
            File f = all.get(i);
            sb.append(f.getPath()).append("  (").append(mb(f.length())).append(", ")
                .append(android.text.format.DateFormat.format("dd/MM/yyyy", f.lastModified())).append(")\n");
        }
        if (all.isEmpty()) {
            File[] top = root.listFiles();
            return "No files matching '" + query + "'" + (kind == null || kind.isEmpty() ? "" : " of type " + kind) + " under " + root.getPath()
                + (top == null ? ". The app cannot list the storage: 'all files access' is missing (Settings > permissions)." : ". Try a shorter part of the name.")
                + (System.currentTimeMillis() > end ? " (search time limit reached)" : "");
        }
        return "Found " + all.size() + (all.size() > 30 ? " (newest 30 shown)" : "") + ":\n" + sb;
    }

    private static void findWalk(File d, String q, String kind, List<File> out, long end, int depth) {
        if (depth > 14 || System.currentTimeMillis() > end || out.size() > 300) return;
        File[] l = d.listFiles();
        if (l == null) return;
        for (File x : l) {
            if (x.isDirectory()) {
                if (depth == 0 && x.getName().equals("Android")) findWalk(new File(x, "media"), q, kind, out, end, depth + 2);   // WhatsApp etc. keep media here
                else findWalk(x, q, kind, out, end, depth + 1);
                continue;
            }
            String n = x.getName().toLowerCase(Locale.ROOT);
            if (!q.isEmpty() && !n.contains(q)) continue;
            if (!kind.isEmpty() && !kind.equals("any") && !wanted(kind.equals("video") || kind.equals("audio") ? "media" : kind.equals("image") ? "exif" : kind, n)) continue;
            out.add(x);
        }
    }

    // ---------------------------------------------------------------- archives

    private static String archive(File f) {
        byte[] h = new byte[16];
        int n = 0;
        try { InputStream in = new FileInputStream(f); n = in.read(h); in.close(); } catch (Exception e) { return "READ ERROR: " + e.getMessage(); }
        String t = type(h, n);
        if (t.equals("rar") || t.equals("7z")) return t.toUpperCase(Locale.US) + " archive, " + mb(f.length()) + ". Listing its content needs an app such as ZArchiver; the header is valid.";
        if (!t.equals("zip")) return "Not a ZIP/RAR/7z archive (content looks like: " + t + ").";
        StringBuilder sb = new StringBuilder();
        try {
            ZipFile z = new ZipFile(f);
            int cnt = 0, bad = 0, enc = 0;
            long total = 0;
            byte[] b = new byte[16384];
            for (Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                cnt++;
                total += Math.max(0, en.getSize());
                String st = "";
                if (!en.isDirectory()) {
                    try {
                        InputStream in = z.getInputStream(en);
                        while (in.read(b) > 0) { }
                        in.close();
                    } catch (Exception ex) {
                        String m = String.valueOf(ex.getMessage()).toLowerCase(Locale.US);
                        if (m.contains("encrypt")) { enc++; st = " [PASSWORD-PROTECTED]"; } else { bad++; st = " [DAMAGED: " + ex.getMessage() + "]"; }
                    }
                }
                if (cnt <= 60) sb.append("  ").append(en.getName()).append(en.isDirectory() ? "" : " (" + (en.getSize() >> 10) + " KB)").append(st).append('\n');
            }
            z.close();
            return "ZIP: " + cnt + " entries, " + mb(total) + " uncompressed, damaged " + bad + ", password-protected " + enc + "\n" + sb
                + (cnt > 60 ? "  ...\n" : "");
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            return "DAMAGED or unsupported ZIP (" + m + "). If it was downloaded, the download was probably cut; download it again.";
        }
    }

    // ---------------------------------------------------------------- photos

    private static String exif(File f) {
        try {
            android.media.ExifInterface x = new android.media.ExifInterface(f.getPath());
            StringBuilder sb = new StringBuilder("EXIF of " + f.getName() + ":\n");
            String[][] tags = {{android.media.ExifInterface.TAG_MAKE, "make"}, {android.media.ExifInterface.TAG_MODEL, "model"},
                {android.media.ExifInterface.TAG_DATETIME, "date"}, {android.media.ExifInterface.TAG_ORIENTATION, "orientation"},
                {android.media.ExifInterface.TAG_IMAGE_WIDTH, "width"}, {android.media.ExifInterface.TAG_IMAGE_LENGTH, "height"},
                {"Software", "software"}};
            for (String[] t : tags) { String v = x.getAttribute(t[0]); if (v != null) sb.append(t[1]).append(": ").append(v).append('\n'); }
            float[] ll = new float[2];
            if (x.getLatLong(ll)) sb.append("LOCATION: ").append(ll[0]).append(", ").append(ll[1]).append(" (the photo reveals where it was taken; strip_location makes a copy without it)\n");
            else sb.append("location: none\n");
            return sb.toString();
        } catch (Exception e) {
            return "No EXIF (not a JPEG/supported image, or damaged): " + e.getMessage();
        }
    }

    /** Never touches the original: writes "<name>_no_location.jpg" next to it. */
    private static String stripLocation(File f) {
        try {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            File out = new File(f.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + "_no_location" + (dot > 0 ? name.substring(dot) : ".jpg"));
            FileInputStream in = new FileInputStream(f);
            java.io.FileOutputStream o = new java.io.FileOutputStream(out);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) o.write(b, 0, n);
            in.close();
            o.close();
            android.media.ExifInterface x = new android.media.ExifInterface(out.getPath());
            String[] gps = {"GPSLatitude", "GPSLatitudeRef", "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSTimeStamp", "GPSDateStamp", "GPSProcessingMethod"};
            for (String g : gps) x.setAttribute(g, null);
            x.saveAttributes();
            float[] ll = new float[2];
            return x.getLatLong(ll) ? "FAILED: location could not be removed from " + out.getPath() : "OK copy without location: " + out.getPath() + " (original unchanged)";
        } catch (Exception e) {
            return "FAILED: " + e.getMessage() + " (only JPEG is supported)";
        }
    }

    // ---------------------------------------------------------------- video / audio: codec and whether this phone can play it

    private static boolean hasDecoder(String mime) {
        try {
            int n = android.media.MediaCodecList.getCodecCount();
            for (int i = 0; i < n; i++) {
                android.media.MediaCodecInfo ci = android.media.MediaCodecList.getCodecInfoAt(i);
                if (ci.isEncoder()) continue;
                for (String t : ci.getSupportedTypes()) if (t.equalsIgnoreCase(mime)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    private static String media(File f) {
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getPath());
            StringBuilder sb = new StringBuilder(f.getName() + ": " + ex.getTrackCount() + " tracks\n");
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                String mime = mf.getString(android.media.MediaFormat.KEY_MIME);
                sb.append("  ").append(mime);
                if (mf.containsKey(android.media.MediaFormat.KEY_WIDTH)) sb.append(' ').append(mf.getInteger(android.media.MediaFormat.KEY_WIDTH)).append('x').append(mf.getInteger(android.media.MediaFormat.KEY_HEIGHT));
                if (mf.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) { try { sb.append(' ').append(mf.getInteger(android.media.MediaFormat.KEY_FRAME_RATE)).append("fps"); } catch (Exception ignored) { } }
                if (mf.containsKey(android.media.MediaFormat.KEY_SAMPLE_RATE)) sb.append(' ').append(mf.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)).append("Hz");
                if (mf.containsKey(android.media.MediaFormat.KEY_CHANNEL_COUNT)) sb.append(' ').append(mf.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT)).append("ch");
                if (mf.containsKey(android.media.MediaFormat.KEY_DURATION)) sb.append(' ').append(mf.getLong(android.media.MediaFormat.KEY_DURATION) / 1000000).append('s');
                boolean dec = mime != null && (mime.startsWith("video/") || mime.startsWith("audio/")) ? hasDecoder(mime) : true;
                sb.append(dec ? " | decoder on this phone: yes" : " | NO DECODER ON THIS PHONE: the built-in player cannot play this track (use VLC/MX Player or convert)").append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return "Cannot read the media container (damaged or unsupported format): " + e.getMessage();
        } finally {
            try { ex.release(); } catch (Exception ignored) { }
        }
    }

    // ---------------------------------------------------------------- big old files and repeated downloads

    private static void walkFiles(File d, List<File> out, long end, int depth) {
        if (depth > 12 || System.currentTimeMillis() > end || out.size() > 100000) return;
        File[] l = d.listFiles();
        if (l == null) return;
        for (File x : l) { if (x.isDirectory()) walkFiles(x, out, end, depth + 1); else out.add(x); }
    }

    private static String large(File root, int minMb) {
        List<File> all = new ArrayList<File>();
        walkFiles(root, all, System.currentTimeMillis() + TIME_LIMIT, 0);
        long old = System.currentTimeMillis() - 90L * 86400000L;
        List<File> big = new ArrayList<File>();
        for (File f : all) if (f.length() >= minMb * 1048576L) big.add(f);
        Collections.sort(big, new Comparator<File>() { public int compare(File a, File b) { return Long.compare(b.length(), a.length()); } });
        StringBuilder sb = new StringBuilder("Files over " + minMb + " MB under " + root.getPath() + " (" + big.size() + "):\n");
        long sum = 0;
        for (int i = 0; i < big.size(); i++) {
            File f = big.get(i);
            sum += f.length();
            if (i < 30) sb.append("  ").append(mb(f.length())).append("  ").append(f.lastModified() < old ? "[not changed 90+ days] " : "").append(f.getPath()).append('\n');
        }
        return sb.append("total ").append(mb(sum)).toString();
    }

    private static String downloadCopies(File d) {
        File[] l = d.listFiles();
        if (l == null) return "cannot list " + d.getPath();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("^(.*?)(?: ?\\(\\d+\\)|-\\d)(\\.[^.]+)?$");
        Map<String, File> byName = new HashMap<String, File>();
        for (File f : l) if (f.isFile()) byName.put(f.getName(), f);
        StringBuilder sb = new StringBuilder();
        long waste = 0;
        int n = 0;
        for (File f : l) {
            if (!f.isFile()) continue;
            java.util.regex.Matcher m = p.matcher(f.getName());
            if (!m.matches()) continue;
            File orig = byName.get(m.group(1) + (m.group(2) == null ? "" : m.group(2)));
            if (orig == null || orig.length() != f.length()) continue;
            n++;
            waste += f.length();
            if (n <= 40) sb.append("  ").append(f.getName()).append("  = copy of ").append(orig.getName()).append(" (").append(mb(f.length())).append(")\n");
        }
        return "Repeated downloads in " + d.getPath() + ": " + n + ", " + mb(waste) + " could be freed\n" + sb;
    }

    // ---------------------------------------------------------------- documents

    private static String doc(File f) {
        byte[] h = new byte[8];
        int n = 0;
        try { InputStream in = new FileInputStream(f); n = in.read(h); in.close(); } catch (Exception e) { return "READ ERROR: " + e.getMessage(); }
        if (n >= 8 && (h[0] & 0xFF) == 0xD0 && (h[1] & 0xFF) == 0xCF && (h[2] & 0xFF) == 0x11 && (h[3] & 0xFF) == 0xE0) {
            String ext = f.getName().toLowerCase(Locale.US);
            return ext.endsWith("x") ? "PASSWORD-PROTECTED Office file (an encrypted .docx/.xlsx is stored in this old container); it opens only with the password."
                : "Old Office format (.doc/.xls/.ppt): needs an app that supports the old format.";
        }
        String t = type(h, n);
        if (t.equals("pdf")) {
            String base = pdfCheck(f);
            if (android.os.Build.VERSION.SDK_INT >= 21) base += " | " + Compat.pdfPages(f);
            return base;
        }
        if (t.equals("zip")) {
            try {
                ZipFile z = new ZipFile(f);
                String kind = z.getEntry("word/document.xml") != null ? "Word document" : z.getEntry("xl/workbook.xml") != null ? "Excel workbook"
                    : z.getEntry("ppt/presentation.xml") != null ? "PowerPoint" : z.getEntry("content.xml") != null ? "OpenDocument" : null;
                z.close();
                if (kind == null) return "ZIP container but not an Office document.";
                return "OK " + kind + "; " + zipCheck(f);
            } catch (Exception e) {
                return "DAMAGED Office document: " + e.getMessage();
            }
        }
        return checkFile(f);
    }

    // ---------------------------------------------------------------- deleted files that can still be recovered

    private static String deleted(File root) {
        String[] known = {"Android/.Trash", "DCIM/.thumbnails/.trash", "MIUI/Gallery/cloud/.trashBin", ".recycle", "Pictures/.trash",
            "Android/data/com.sec.android.gallery3d/files/.Trash", "Android/data/com.coloros.gallery3d/files/.recycle", "Android/data/com.google.android.apps.photos/files/trash"};
        StringBuilder sb = new StringBuilder();
        long total = 0;
        int cnt = 0;
        for (String k : known) {
            File d = new File(root, k);
            File[] l = d.listFiles();
            if (l == null || l.length == 0) continue;
            long s = 0;
            for (File x : l) s += x.length();
            total += s;
            cnt += l.length;
            sb.append("  ").append(d.getPath()).append(": ").append(l.length).append(" items, ").append(mb(s)).append('\n');
        }
        List<File> all = new ArrayList<File>();
        walkFiles(root, all, System.currentTimeMillis() + TIME_LIMIT, 0);
        int tr = 0;
        for (File f : all) {
            if (!f.getName().startsWith(".trashed-")) continue;
            tr++;
            total += f.length();
            if (tr <= 30) sb.append("  ").append(f.getPath()).append("  -> original name ").append(f.getName().replaceFirst("^\\.trashed-\\d+-", "")).append('\n');
        }
        cnt += tr;
        if (cnt == 0) return "No recycle-bin files found under " + root.getPath() + " (Google Photos cloud trash is not on the phone).";
        return "Deleted files still on the phone: " + cnt + ", " + mb(total) + "\n" + sb
            + "Files named .trashed-<time>-<name> are in the Android recycle bin; renaming back to <name> (or restoring from the gallery's bin) recovers them.";
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
