package com.phone.doctor.fm;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * SPAKE2 over Ed25519, exactly as BoringSSL implements it (crypto/curve25519/spake25519.c), which is what
 * adb's wireless-debugging pairing uses. Plain BigInteger math: slow but only a handful of scalar
 * multiplications are needed per pairing.
 *
 * Alice (the adb client) sends x*B + w*M, Bob (adbd) sends y*B + w*N. Both derive SHA-512 of the transcript.
 */
final class Spake2 {
    static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    static final BigInteger L = BigInteger.ONE.shiftLeft(252).add(new BigInteger("27742317777372353535851937790883648493"));
    static final BigInteger D = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    static final BigInteger SQRT_M1 = BigInteger.valueOf(2).modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    /** Point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Pt {
        final BigInteger x, y, z, t;
        Pt(BigInteger x, BigInteger y, BigInteger z, BigInteger t) { this.x = x; this.y = y; this.z = z; this.t = t; }
    }

    static final Pt ZERO = new Pt(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);
    static final Pt BASE;
    // "edwards25519 point generation seed (M)" / "(N)": SHA-256 of the seed decodes to a point directly
    static final Pt M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));
    static final Pt N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));

    static {
        BigInteger by = BigInteger.valueOf(4).multiply(BigInteger.valueOf(5).modInverse(P)).mod(P);
        BigInteger bx = xFromY(by, false);
        BASE = new Pt(bx, by, BigInteger.ONE, bx.multiply(by).mod(P));
    }

    static byte[] hex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return b;
    }

    private static BigInteger xFromY(BigInteger y, boolean odd) {
        BigInteger yy = y.multiply(y).mod(P);
        BigInteger u = yy.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(yy).add(BigInteger.ONE).mod(P);
        BigInteger xx = u.multiply(v.modInverse(P)).mod(P);
        BigInteger x = xx.modPow(P.add(BigInteger.valueOf(3)).shiftRight(3), P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) x = x.multiply(SQRT_M1).mod(P);
        if (!x.multiply(x).subtract(xx).mod(P).equals(BigInteger.ZERO)) throw new IllegalArgumentException("not a point");
        if (x.testBit(0) != odd) x = P.subtract(x).mod(P);
        return x;
    }

    static BigInteger le(byte[] b) {
        byte[] r = new byte[b.length + 1];
        for (int i = 0; i < b.length; i++) r[b.length - i] = b[i];
        return new BigInteger(r);
    }

    static byte[] toLe(BigInteger v, int len) {
        byte[] be = v.toByteArray(), out = new byte[len];
        for (int i = 0; i < len && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static Pt decode(byte[] s) {
        byte[] c = s.clone();
        boolean odd = (c[31] & 0x80) != 0;
        c[31] &= 0x7f;
        BigInteger y = le(c);
        if (y.compareTo(P) >= 0) throw new IllegalArgumentException("bad point");
        BigInteger x = xFromY(y, odd);
        return new Pt(x, y, BigInteger.ONE, x.multiply(y).mod(P));
    }

    static byte[] encode(Pt p) {
        BigInteger zi = p.z.modInverse(P);
        BigInteger x = p.x.multiply(zi).mod(P), y = p.y.multiply(zi).mod(P);
        byte[] out = toLe(y, 32);
        if (x.testBit(0)) out[31] |= (byte) 0x80;
        return out;
    }

    static Pt add(Pt a, Pt b) {
        // add-2008-hwcd-3 for a = -1
        BigInteger A = a.y.subtract(a.x).multiply(b.y.subtract(b.x)).mod(P);
        BigInteger B = a.y.add(a.x).multiply(b.y.add(b.x)).mod(P);
        BigInteger C = a.t.multiply(BigInteger.valueOf(2)).multiply(D).multiply(b.t).mod(P);
        BigInteger Dd = a.z.multiply(BigInteger.valueOf(2)).multiply(b.z).mod(P);
        BigInteger E = B.subtract(A), F = Dd.subtract(C), G = Dd.add(C), H = B.add(A);
        return new Pt(E.multiply(F).mod(P), G.multiply(H).mod(P), F.multiply(G).mod(P), E.multiply(H).mod(P));
    }

    static Pt neg(Pt a) { return new Pt(P.subtract(a.x).mod(P), a.y, a.z, P.subtract(a.t).mod(P)); }

    static Pt mul(Pt p, BigInteger k) {
        Pt r = ZERO, q = p;
        for (int i = 0; i < k.bitLength(); i++) {
            if (k.testBit(i)) r = add(r, q);
            q = add(q, q);
        }
        return r;
    }

    // ------------------------------------------------------------------ protocol

    private final boolean alice;
    private final byte[] myName, theirName, pwHash = new byte[64], myMsg;
    private final BigInteger priv, pwScalar;

    Spake2(boolean alice, byte[] myName, byte[] theirName, byte[] password, SecureRandom rnd) throws Exception {
        this.alice = alice;
        this.myName = myName;
        this.theirName = theirName;
        byte[] r = new byte[64];
        rnd.nextBytes(r);
        priv = le(r).mod(L).shiftLeft(3);                 // x25519_sc_reduce, then multiply by the cofactor
        System.arraycopy(MessageDigest.getInstance("SHA-512").digest(password), 0, pwHash, 0, 64);
        BigInteger s = le(pwHash).mod(L);
        // BoringSSL's fix-up: a scalar equal to h mod l that is also a multiple of 8
        if (s.testBit(0)) s = s.add(L);
        if (s.testBit(1)) s = s.add(L.shiftLeft(1));
        if (s.testBit(2)) s = s.add(L.shiftLeft(2));
        pwScalar = s;
        Pt mine = add(mul(BASE, priv), mul(alice ? M : N, pwScalar));
        myMsg = encode(mine);
    }

    byte[] msg() { return myMsg.clone(); }

    private static void lp(MessageDigest md, byte[] d) {
        long l = d.length;
        byte[] len = new byte[8];
        for (int i = 0; i < 8; i++) { len[i] = (byte) (l & 0xff); l >>= 8; }
        md.update(len);
        md.update(d);
    }

    /** The 64-byte shared key. */
    byte[] process(byte[] theirMsg) throws Exception {
        if (theirMsg.length != 32) throw new IllegalArgumentException("bad SPAKE2 message length");
        Pt q = decode(theirMsg);
        Pt qExt = add(q, neg(mul(alice ? N : M, pwScalar)));
        byte[] dh = encode(mul(qExt, priv));
        MessageDigest md = MessageDigest.getInstance("SHA-512");
        if (alice) { lp(md, myName); lp(md, theirName); lp(md, myMsg); lp(md, theirMsg); }
        else { lp(md, theirName); lp(md, myName); lp(md, theirMsg); lp(md, myMsg); }
        lp(md, dh);
        lp(md, pwHash);
        return md.digest();
    }
}
