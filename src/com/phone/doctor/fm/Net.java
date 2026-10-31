package com.phone.doctor.fm;

import android.os.Build;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/** HTTPS helper. Android 4.4 supports TLS 1.2 but leaves it disabled, and Google servers require it. */
final class Net {
    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (Build.VERSION.SDK_INT < 21 && c instanceof HttpsURLConnection) {
            try { ((HttpsURLConnection) c).setSSLSocketFactory(new Tls12(SSLContext.getInstance("TLSv1.2"))); }
            catch (Exception ignored) { }
        }
        return c;
    }

    static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12(SSLContext ctx) throws Exception {
            ctx.init(null, null, null);
            d = ctx.getSocketFactory();
        }

        private Socket on(Socket s) {
            if (s instanceof SSLSocket) {
                try { ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"}); } catch (Exception ignored) { }
            }
            return s;
        }

        @Override public String[] getDefaultCipherSuites() { return d.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return d.getSupportedCipherSuites(); }
        @Override public Socket createSocket(Socket s, String host, int port, boolean auto) throws IOException { return on(d.createSocket(s, host, port, auto)); }
        @Override public Socket createSocket(String host, int port) throws IOException { return on(d.createSocket(host, port)); }
        @Override public Socket createSocket(String host, int port, InetAddress la, int lp) throws IOException { return on(d.createSocket(host, port, la, lp)); }
        @Override public Socket createSocket(InetAddress host, int port) throws IOException { return on(d.createSocket(host, port)); }
        @Override public Socket createSocket(InetAddress a, int port, InetAddress la, int lp) throws IOException { return on(d.createSocket(a, port, la, lp)); }
    }
}
