package com.phone.doctor.fm;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * A minimal adb client in Java, enough to talk to this phone's own adbd over wireless debugging (Android 11+):
 *  - pairing with the 6-digit code (TLS 1.3 + SPAKE2 + AES-128-GCM, as in adb's pairing_connection);
 *  - connecting (CNXN, STLS upgrade to TLS with our paired key) and running "shell:" commands as the shell user.
 * The shell user may grant development permissions and set a device owner, which an app cannot do for itself.
 */
final class AdbWifi {
    private final Context ctx;
    private KeyPair keys;
    private X509Certificate cert;

    AdbWifi(Context c) throws Exception {
        ctx = c.getApplicationContext();
        loadKeys();
    }

    // ------------------------------------------------------------------ our adb key (kept in the app's private files)

    private void loadKeys() throws Exception {
        File pk = new File(ctx.getFilesDir(), "adbkey"), pub = new File(ctx.getFilesDir(), "adbkey.pub.der");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (pk.exists() && pub.exists()) {
            keys = new KeyPair(kf.generatePublic(new X509EncodedKeySpec(read(pub))), kf.generatePrivate(new PKCS8EncodedKeySpec(read(pk))));
        } else {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            keys = g.generateKeyPair();
            write(pk, keys.getPrivate().getEncoded());
            write(pub, keys.getPublic().getEncoded());
        }
        cert = selfSigned(keys);
    }

    private static byte[] read(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static void write(File f, byte[] d) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(d);
        o.close();
    }

    // ---- DER, just what a self-signed certificate needs

    private static byte[] der(int tag, byte[]... parts) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] p : parts) body.write(p, 0, p.length);
        byte[] b = body.toByteArray();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(tag);
        int n = b.length;
        if (n < 128) o.write(n);
        else if (n < 256) { o.write(0x81); o.write(n); }
        else if (n < 65536) { o.write(0x82); o.write(n >> 8); o.write(n & 0xff); }
        else { o.write(0x83); o.write(n >> 16); o.write((n >> 8) & 0xff); o.write(n & 0xff); }
        o.write(b, 0, b.length);
        return o.toByteArray();
    }

    private static byte[] seq(byte[]... p) { return der(0x30, p); }

    private static final byte[] SHA256_RSA = seq(new byte[]{0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x0b}, new byte[]{0x05, 0x00});

    private static byte[] name(String cn) throws Exception {
        byte[] oidCn = {0x06, 0x03, 0x55, 0x04, 0x03};
        return seq(der(0x31, seq(oidCn, der(0x0c, cn.getBytes("UTF-8")))));
    }

    private static X509Certificate selfSigned(KeyPair kp) throws Exception {
        byte[] version = der(0xa0, new byte[]{0x02, 0x01, 0x02});
        byte[] serial = der(0x02, BigInteger.valueOf(System.currentTimeMillis()).toByteArray());
        byte[] validity = seq(der(0x17, "200101000000Z".getBytes("US-ASCII")), der(0x18, "20491231235959Z".getBytes("US-ASCII")));
        byte[] tbs = seq(version, serial, SHA256_RSA, name("Phone Doctor"), validity, name("Phone Doctor"), kp.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(kp.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] certDer = seq(tbs, SHA256_RSA, der(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(certDer));
    }

    /** adb's public key format: base64 of the mincrypt RSAPublicKey struct, then " name". */
    String adbPublicKey() {
        RSAPublicKey pub = (RSAPublicKey) keys.getPublic();
        BigInteger n = pub.getModulus();
        int words = 64;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        b.putInt(words);
        b.putInt(n0inv.intValue());
        b.put(Spake2.toLe(n, 256));
        b.put(Spake2.toLe(rr, 256));
        b.putInt(pub.getPublicExponent().intValue());
        return android.util.Base64.encodeToString(b.array(), android.util.Base64.NO_WRAP) + " phonedoctor@" + android.os.Build.MODEL.replace(' ', '_');
    }

    // ------------------------------------------------------------------ TLS

    private SSLContext tls() throws Exception {
        final PrivateKey priv = keys.getPrivate();
        final X509Certificate[] chain = {cert};
        KeyManager km = new X509ExtendedKeyManager() {
            public String chooseClientAlias(String[] t, java.security.Principal[] i, Socket s) { return "adb"; }
            public String chooseServerAlias(String t, java.security.Principal[] i, Socket s) { return null; }
            public X509Certificate[] getCertificateChain(String a) { return chain; }
            public String[] getClientAliases(String t, java.security.Principal[] i) { return new String[]{"adb"}; }
            public String[] getServerAliases(String t, java.security.Principal[] i) { return null; }
            public PrivateKey getPrivateKey(String a) { return priv; }
            @Override public String chooseEngineClientAlias(String[] t, java.security.Principal[] i, javax.net.ssl.SSLEngine e) { return "adb"; }
        };
        TrustManager tm = new X509TrustManager() {   // adbd's certificate is self-signed; the pairing code is what authenticates it
            public void checkClientTrusted(X509Certificate[] c, String a) { }
            public void checkServerTrusted(X509Certificate[] c, String a) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext c = SSLContext.getInstance("TLS");
        c.init(new KeyManager[]{km}, new TrustManager[]{tm}, new SecureRandom());
        return c;
    }

    private static void tls13(SSLSocket s) {
        try { s.setEnabledProtocols(new String[]{"TLSv1.3"}); } catch (Exception ignored) { }
    }

    private static byte[] exportKey(SSLSocket s) throws Exception {
        String label = "adb-label\u0000";
        if (android.os.Build.VERSION.SDK_INT >= 31) return Compat.exportKeyingMaterial(s, label, 64);
        // Android 11: the same Conscrypt call, not yet public
        Class<?> c = Class.forName("com.android.org.conscrypt.Conscrypt");
        return (byte[]) c.getMethod("exportKeyingMaterial", SSLSocket.class, String.class, byte[].class, int.class).invoke(null, s, label, null, 64);
    }

    // ------------------------------------------------------------------ pairing

    private static void packet(OutputStream o, int type, byte[] payload) throws Exception {
        byte[] h = {1, (byte) type, (byte) (payload.length >>> 24), (byte) (payload.length >>> 16), (byte) (payload.length >>> 8), (byte) payload.length};
        o.write(h);
        o.write(payload);
        o.flush();
    }

    private static byte[] packet(DataInputStream in, int wantType) throws Exception {
        byte[] h = new byte[6];
        in.readFully(h);
        int len = ((h[2] & 0xff) << 24) | ((h[3] & 0xff) << 16) | ((h[4] & 0xff) << 8) | (h[5] & 0xff);
        if (h[0] != 1 || h[1] != wantType || len < 0 || len > 16384) throw new Exception("pairing: unexpected packet (version " + h[0] + ", type " + h[1] + ", size " + len + ")");
        byte[] p = new byte[len];
        in.readFully(p);
        return p;
    }

    private static byte[] hkdf(byte[] ikm, String info) throws Exception {
        Mac m = Mac.getInstance("HmacSHA256");
        m.init(new SecretKeySpec(new byte[32], "HmacSHA256"));     // no salt = HashLen zero bytes
        byte[] prk = m.doFinal(ikm);
        m.init(new SecretKeySpec(prk, "HmacSHA256"));
        m.update(info.getBytes("US-ASCII"));
        m.update((byte) 1);
        byte[] okm = m.doFinal();
        byte[] key = new byte[16];
        System.arraycopy(okm, 0, key, 0, 16);
        return key;
    }

    private static byte[] gcm(boolean enc, byte[] key, long seq, byte[] data) throws Exception {
        byte[] nonce = new byte[12];
        for (int i = 0; i < 8; i++) nonce[i] = (byte) (seq >>> (8 * i));
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(enc ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        return c.doFinal(data);
    }

    /** Pairs with the "Pair device with pairing code" dialog. Throws with a readable reason on failure. */
    void pair(String host, int port, String code) throws Exception {
        SSLSocket s = (SSLSocket) tls().getSocketFactory().createSocket();
        try {
            s.connect(new InetSocketAddress(host, port), 8000);
            s.setSoTimeout(15000);
            tls13(s);
            s.startHandshake();
            byte[] exported = exportKey(s);
            byte[] pw = new byte[code.length() + exported.length];
            System.arraycopy(code.getBytes("US-ASCII"), 0, pw, 0, code.length());
            System.arraycopy(exported, 0, pw, code.length(), exported.length);
            Spake2 sp = new Spake2(true, "adb pair client\u0000".getBytes("US-ASCII"), "adb pair server\u0000".getBytes("US-ASCII"), pw, new SecureRandom());
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            packet(out, 0, sp.msg());
            byte[] theirs = packet(in, 0);
            byte[] key = hkdf(sp.process(theirs), "adb pairing_auth aes-128-gcm key");
            byte[] info = new byte[8192];            // PeerInfo { type = ADB_RSA_PUB_KEY (0), data[8191] }
            byte[] pk = adbPublicKey().getBytes("UTF-8");
            System.arraycopy(pk, 0, info, 1, Math.min(pk.length, 8190));
            packet(out, 1, gcm(true, key, 0, info));
            byte[] peer;
            try {
                peer = gcm(false, key, 0, packet(in, 1));
            } catch (java.io.EOFException e) {
                throw new Exception("קוד הצימוד שגוי (המכשיר סגר את החיבור)");
            } catch (javax.crypto.AEADBadTagException e) {
                throw new Exception("קוד הצימוד שגוי");
            }
            if (peer.length < 1) throw new Exception("pairing: empty peer info");
        } finally {
            try { s.close(); } catch (Exception ignored) { }
        }
    }

    // ------------------------------------------------------------------ adb protocol

    static final int CNXN = 0x4e584e43, STLS = 0x534c5453, OPEN = 0x4e45504f, OKAY = 0x59414b4f, WRTE = 0x45545257, CLSE = 0x45534c43, AUTH = 0x48545541;

    private static void send(OutputStream o, int cmd, int a0, int a1, byte[] data) throws Exception {
        if (data == null) data = new byte[0];
        long sum = 0;
        for (byte b : data) sum += b & 0xff;
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(data.length).putInt((int) sum).putInt(cmd ^ 0xffffffff);
        o.write(h.array());
        o.write(data);
        o.flush();
    }

    private static final class Msg { int cmd, a0, a1; byte[] data; }

    private static Msg recv(DataInputStream in) throws Exception {
        byte[] h = new byte[24];
        in.readFully(h);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = b.getInt();
        m.a0 = b.getInt();
        m.a1 = b.getInt();
        int len = b.getInt();
        if (len < 0 || len > 1024 * 1024) throw new Exception("adb: bad message length " + len);
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    /** Opens a connection and runs shell commands as the shell user; returns the combined output. */
    String shell(String host, int port, String cmd) throws Exception {
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(host, port), 8000);
        raw.setSoTimeout(20000);
        Socket sock = raw;
        try {
            OutputStream out = raw.getOutputStream();
            DataInputStream in = new DataInputStream(raw.getInputStream());
            send(out, CNXN, 0x01000001, 1024 * 1024, "host::\u0000".getBytes("US-ASCII"));
            Msg m = recv(in);
            if (m.cmd == STLS) {
                send(out, STLS, 0x01000000, 0, null);
                SSLSocket s = (SSLSocket) tls().getSocketFactory().createSocket(raw, host, port, true);
                tls13(s);
                s.startHandshake();
                sock = s;
                out = s.getOutputStream();
                in = new DataInputStream(s.getInputStream());
                m = recv(in);
            }
            if (m.cmd == AUTH) throw new Exception("המכשיר לא מכיר את המפתח של Phone Doctor. צריך לצמד שוב.");
            if (m.cmd != CNXN) throw new Exception("adb: unexpected reply 0x" + Integer.toHexString(m.cmd));
            send(out, OPEN, 1, 0, ("shell:" + cmd + "\u0000").getBytes("UTF-8"));
            ByteArrayOutputStream res = new ByteArrayOutputStream();
            int remote = 0;
            sock.setSoTimeout(60000);
            while (true) {
                m = recv(in);
                if (m.cmd == OKAY) remote = m.a0;
                else if (m.cmd == WRTE) { res.write(m.data, 0, m.data.length); send(out, OKAY, 1, m.a0, null); }
                else if (m.cmd == CLSE) { try { send(out, CLSE, 1, m.a0, null); } catch (Exception ignored) { } break; }
            }
            return res.toString("UTF-8");
        } finally {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }
}
