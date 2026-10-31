package com.phone.doctor.fm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/** Hidden terminal: runs one command via sh (or su) and returns merged stdout+stderr. */
final class Shell {
    static final int MAX = 12000;
    static volatile boolean cancel;

    static final class Result {
        int exit;
        String out = "";
    }

    static Result run(String cmd, boolean root, int timeoutSec) {
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
                        char[] buf = new char[2048];
                        int n;
                        while ((n = br.read(buf)) > 0) {
                            synchronized (sb) {
                                if (sb.length() < MAX + 2048) sb.append(buf, 0, n);
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
                synchronized (sb) { sb.append(cancel ? "\n[הופסק]" : "\n[timeout]"); }
            }
            t.join(1500);
        } catch (Exception e) {
            r.exit = -1;
            synchronized (sb) { sb.append("exec error: ").append(e); }
        }
        String s;
        synchronized (sb) { s = sb.toString(); }
        if (s.length() > MAX) s = s.substring(0, MAX) + "\n...[truncated]";
        r.out = s;
        return r;
    }
}
