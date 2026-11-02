package com.phone.doctor.fm;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * Hidden terminal. Non-root commands run in their own `sh -c`. Root commands go to ONE long-lived `su`
 * shell (opened once per session): much faster, and root managers do not show a toast for every command.
 * If that shell cannot be used, it falls back to `su -c` per command.
 * Output longer than MAX is cut for the model, but the full text is saved so it can be searched later.
 */
final class Shell {
    static final int MAX = 12000;
    static volatile boolean cancel;

    static final class Result {
        int exit;
        String out = "";
    }

    static Result run(String cmd, boolean root, int timeoutSec) {
        Result r = null;
        if (root) r = RootShell.run(cmd, timeoutSec);
        if (r == null) r = spawn(cmd, root, timeoutSec);
        r.out = clip(r.out);
        return r;
    }

    /** Cuts long output for the model and keeps the whole text in a file (see read_output). */
    static String clip(String s) {
        if (s.length() <= MAX) return s;
        String id = Outputs.save(s);
        return s.substring(0, MAX) + "\n...[truncated: full output is " + s.length() + " chars, " + s.split("\n").length
            + " lines, saved as output_id=" + id + "; use read_output to search or page it]";
    }

    /** One process per command (non-root, or root when the persistent shell is not available). */
    static Result spawn(String cmd, boolean root, int timeoutSec) {
        final Result r = new Result();
        final StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = root ? new ProcessBuilder("su", "-c", cmd) : new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            p.getOutputStream().close();
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                        char[] buf = new char[4096];
                        int n;
                        while ((n = br.read(buf)) > 0) {
                            synchronized (sb) {
                                if (sb.length() < Outputs.KEEP) sb.append(buf, 0, n);
                            }
                        }
                    } catch (IOException ignored) {
                    }
                }
            });
            t.start();
            long end = System.currentTimeMillis() + timeoutSec * 1000L;
            boolean done = false;
            while (System.currentTimeMillis() < end && !cancel) {
                try {
                    r.exit = p.exitValue();
                    done = true;
                    break;
                } catch (IllegalThreadStateException stillRunning) {
                    Thread.sleep(40);
                }
            }
            if (!done) {
                p.destroy();
                r.exit = -2;
                synchronized (sb) { sb.append(cancel ? "\n[הופסק]" : "\n[timeout after " + timeoutSec + " s; for long jobs use run_background]"); }
            }
            t.join(1500);
        } catch (Exception e) {
            r.exit = -1;
            synchronized (sb) { sb.append("exec error: ").append(e); }
        }
        synchronized (sb) { r.out = sb.toString(); }
        return r;
    }

    // ================================================================ persistent root shell

    static final class RootShell {
        private static Process proc;
        private static OutputStream in;
        private static final StringBuilder buf = new StringBuilder();
        private static final Object LOCK = new Object();
        private static boolean broken;          // su refused or this su cannot run interactively: use su -c
        private static int seq;

        private static boolean open() {
            if (proc != null) return true;
            if (broken) return false;
            try {
                ProcessBuilder pb = new ProcessBuilder("su");
                pb.redirectErrorStream(true);
                proc = pb.start();
                in = proc.getOutputStream();
                final InputStream out = proc.getInputStream();
                Thread t = new Thread(new Runnable() {
                    public void run() {
                        byte[] b = new byte[8192];
                        int n;
                        try {
                            while ((n = out.read(b)) > 0) {
                                String s = new String(b, 0, n, "UTF-8");
                                synchronized (buf) { if (buf.length() < Outputs.KEEP) buf.append(s); buf.notifyAll(); }
                            }
                        } catch (IOException ignored) {
                        }
                        synchronized (buf) { buf.notifyAll(); }
                    }
                });
                t.setDaemon(true);
                t.start();
                Result probe = exec("id", 45);   // first use: the root manager asks the user here
                if (probe == null || !probe.out.contains("uid=0")) { close(); broken = true; return false; }
                return true;
            } catch (Exception e) {
                close();
                broken = true;
                return false;
            }
        }

        static void close() {
            try { if (proc != null) proc.destroy(); } catch (Exception ignored) { }
            proc = null;
            in = null;
        }

        /** Returns null when the caller should fall back to `su -c`. */
        static Result run(String cmd, int timeoutSec) {
            synchronized (LOCK) {
                if (!open()) return null;
                Result r = exec(cmd, timeoutSec);
                if (r == null) { close(); return null; }   // the shell died: next call reopens it
                return r;
            }
        }

        private static Result exec(String cmd, int timeoutSec) {
            String mark = "__PD_END_" + (++seq) + "_" + System.nanoTime() + "__";
            synchronized (buf) { buf.setLength(0); }
            try {
                // run in a subshell, stdin closed (so a command never eats the next one), exit code after the mark
                String w = "(\n" + cmd + "\n) </dev/null 2>&1\necho \"" + mark + " $?\"\n";
                in.write(w.getBytes("UTF-8"));
                in.flush();
            } catch (Exception e) {
                return null;
            }
            long end = System.currentTimeMillis() + timeoutSec * 1000L;
            synchronized (buf) {
                while (true) {
                    int i = buf.indexOf(mark);
                    if (i >= 0) {
                        Result r = new Result();
                        r.out = buf.substring(0, i);
                        String rest = buf.substring(i + mark.length()).trim().split("\\s+")[0];
                        try { r.exit = Integer.parseInt(rest); } catch (Exception e) { r.exit = 0; }
                        if (r.out.endsWith("\n")) r.out = r.out.substring(0, r.out.length() - 1);
                        return r;
                    }
                    long left = end - System.currentTimeMillis();
                    if (left <= 0 || cancel || !alive()) {
                        Result r = new Result();
                        r.exit = -2;
                        r.out = buf.toString() + (cancel ? "\n[הופסק]" : !alive() ? "\n[root shell closed]" : "\n[timeout after " + timeoutSec + " s; for long jobs use run_background]");
                        close();   // the subshell may still run: start a fresh root shell next time
                        return r;
                    }
                    try { buf.wait(Math.min(left, 200)); } catch (InterruptedException e) { return null; }
                }
            }
        }

        private static boolean alive() {
            if (proc == null) return false;
            try { proc.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
        }
    }

    // ================================================================ full outputs and background jobs

    static final class Outputs {
        static final int KEEP = 3 * 1024 * 1024;     // keep up to 3 MB per command
        private static int n;

        static File dir() {
            File d = new File(Core.app.getCacheDir(), "outputs");
            d.mkdirs();
            return d;
        }

        static synchronized String save(String s) {
            try {
                String id = "out" + (++n) + "_" + (System.currentTimeMillis() % 100000);
                FileOutputStream o = new FileOutputStream(new File(dir(), id + ".txt"));
                o.write(s.getBytes("UTF-8"));
                o.close();
                File[] all = dir().listFiles();   // keep the folder small
                if (all != null && all.length > 30) {
                    java.util.Arrays.sort(all, new java.util.Comparator<File>() {
                        public int compare(File a, File b) { return Long.compare(a.lastModified(), b.lastModified()); }
                    });
                    for (int i = 0; i < all.length - 30; i++) all[i].delete();
                }
                return id;
            } catch (Exception e) {
                return "unsaved";
            }
        }

        /** Lines matching grep (regex, case-insensitive), or a page of lines. */
        static String read(String id, String grep, int from, int count) {
            if (!id.matches("[A-Za-z0-9_]+")) return "invalid output_id";
            File f = new File(dir(), id + ".txt");
            if (!f.exists()) return "no such output_id (outputs are kept for this session only)";
            Job j = Job.get(id);
            StringBuilder sb = new StringBuilder();
            if (j != null) sb.append(j.running() ? "[job still RUNNING, partial output]\n" : "[job finished, exit " + j.exit() + "]\n");
            try {
                BufferedReader r = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
                String line;
                int ln = 0, shown = 0;
                java.util.regex.Pattern p = grep == null || grep.isEmpty() ? null : java.util.regex.Pattern.compile(grep, java.util.regex.Pattern.CASE_INSENSITIVE);
                while ((line = r.readLine()) != null) {
                    ln++;
                    if (p != null) {
                        if (p.matcher(line).find()) { sb.append(ln).append(": ").append(line).append('\n'); if (++shown >= count) break; }
                    } else if (ln >= from) {
                        sb.append(ln).append(": ").append(line).append('\n');
                        if (++shown >= count) break;
                    }
                }
                r.close();
                sb.append("[total lines so far: ").append(ln).append(shown >= count ? "+" : "").append("]");
            } catch (Exception e) {
                return "read error: " + e.getMessage();
            }
            String s = sb.toString();
            return s.length() > MAX ? s.substring(0, MAX) + "\n...[page is long: ask for fewer lines]" : s;
        }
    }

    /** A long READ-ONLY command running in the background; its output grows in an Outputs file. */
    static final class Job {
        private static final java.util.Map<String, Job> JOBS = new java.util.HashMap<String, Job>();
        final Process p;

        private Job(Process p) { this.p = p; }

        boolean running() { try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; } }

        int exit() { try { return p.exitValue(); } catch (Exception e) { return -1; } }

        static synchronized Job get(String id) { return JOBS.get(id); }

        static synchronized String start(String cmd, boolean root) throws Exception {
            String id = "job" + (JOBS.size() + 1) + "_" + (System.currentTimeMillis() % 100000);
            File f = new File(Outputs.dir(), id + ".txt");
            String q = f.getPath();
            String full = "(" + cmd + ") </dev/null > '" + q + "' 2>&1; chmod 666 '" + q + "'";
            ProcessBuilder pb = root ? new ProcessBuilder("su", "-c", full) : new ProcessBuilder("sh", "-c", full);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getOutputStream().close();
            JOBS.put(id, new Job(p));
            return id;
        }

        static synchronized void stopAll() {
            for (Job j : JOBS.values()) { try { j.p.destroy(); } catch (Exception ignored) { } }
        }
    }
}
