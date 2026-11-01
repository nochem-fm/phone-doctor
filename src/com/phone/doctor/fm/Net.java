package com.phone.doctor.fm;

import android.content.Context;
import android.os.Build;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URL;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS helper.
 * - Android 4.4 supports TLS 1.2 but leaves it disabled, and Google servers require it.
 * - Filtered networks (NetFree, Rimon / Etrog) re-sign HTTPS with their own root certificate.
 *   Android 7+ trusts the bundled roots through res/xml/network_security_config.xml; on older
 *   versions that file is ignored, so the same roots are added here in code.
 */
final class Net {
    /** Bundled filter roots in res/raw (no R class in this build, so they are looked up by name). */
    static final String[] FILTER_CAS = {"netfree_rsa", "netfree_rsa_2037", "netfree_ecc256", "netfree_ecc384", "netspark"};

    private static SSLSocketFactory legacy;

    static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (Build.VERSION.SDK_INT < 24 && c instanceof HttpsURLConnection) {
            try { ((HttpsURLConnection) c).setSSLSocketFactory(legacyFactory()); } catch (Exception ignored) { }
        }
        return c;
    }

    private static synchronized SSLSocketFactory legacyFactory() throws Exception {
        if (legacy != null) return legacy;
        SSLContext ctx = SSLContext.getInstance(Build.VERSION.SDK_INT < 21 ? "TLSv1.2" : "TLS");
        ctx.init(null, new TrustManager[]{trustWithFilters(Core.app)}, null);
        SSLSocketFactory f = ctx.getSocketFactory();
        legacy = Build.VERSION.SDK_INT < 21 ? new Tls12(f) : f;
        return legacy;
    }

    /** System roots first; if the chain is not trusted there, try the bundled filter roots. */
    static X509TrustManager trustWithFilters(Context c) throws Exception {
        final X509TrustManager sys = tm(null);
        X509TrustManager extra = null;
        if (c != null) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            int n = 0;
            for (String name : FILTER_CAS) {
                int id = c.getResources().getIdentifier(name, "raw", c.getPackageName());
                if (id == 0) continue;
                for (byte[] one : split(readAll(c.getResources().openRawResource(id)))) {
                    try {   // one by one: an old Android that cannot parse e.g. the ED25519 root still loads the RSA/ECC ones
                        ks.setCertificateEntry(name + "_" + n, cf.generateCertificate(new java.io.ByteArrayInputStream(one)));
                        n++;
                    } catch (Exception ignored) { }
                }
            }
            if (n > 0) extra = tm(ks);
        }
        if (extra == null) return sys;
        final X509TrustManager ex = extra;
        return new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                sys.checkClientTrusted(chain, auth);
            }

            public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                try {
                    sys.checkServerTrusted(chain, auth);
                } catch (CertificateException e) {
                    ex.checkServerTrusted(chain, auth);   // throws if it is not a filter root either
                }
            }

            public X509Certificate[] getAcceptedIssuers() {
                List<X509Certificate> l = new ArrayList<X509Certificate>();
                for (X509Certificate x : sys.getAcceptedIssuers()) l.add(x);
                for (X509Certificate x : ex.getAcceptedIssuers()) l.add(x);
                return l.toArray(new X509Certificate[0]);
            }
        };
    }

    /** A file may hold several PEM certificates (NetFree ships five); DER files are returned as is. */
    static List<byte[]> split(byte[] data) throws Exception {
        List<byte[]> out = new ArrayList<byte[]>();
        String t = new String(data, "ISO-8859-1");
        int i = t.indexOf("-----BEGIN CERTIFICATE-----");
        if (i < 0) { out.add(data); return out; }
        while (i >= 0) {
            int e = t.indexOf("-----END CERTIFICATE-----", i);
            if (e < 0) break;
            e += "-----END CERTIFICATE-----".length();
            out.add(t.substring(i, e).getBytes("ISO-8859-1"));
            i = t.indexOf("-----BEGIN CERTIFICATE-----", e);
        }
        return out;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[4096];
        int r;
        try { while ((r = in.read(b)) > 0) bo.write(b, 0, r); } finally { in.close(); }
        return bo.toByteArray();
    }

    private static X509TrustManager tm(KeyStore ks) throws Exception {
        TrustManagerFactory f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        f.init(ks);
        for (TrustManager t : f.getTrustManagers()) if (t instanceof X509TrustManager) return (X509TrustManager) t;
        throw new IllegalStateException("no X509TrustManager");
    }

    static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory d;

        Tls12(SSLSocketFactory d) {
            this.d = d;
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
