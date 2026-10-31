package org.telegram.messenger.zephyr.plugins;

import java.security.MessageDigest;

/**
 * Verify-only Ed25519 (RFC 8032, pure Ed25519), pure Java, no dependencies (works on API 24+).
 * Port of the public-domain TweetNaCl reference implementation (crypto_sign_open), using the
 * platform MessageDigest "SHA-512". Variable-time is fine: verification handles public data only.
 *
 * Public API:
 *   static boolean verify(byte[] message, byte[] signature64, byte[] publicKey32)
 * Rejects non-canonical scalars / point encodings and small-order public keys.
 */
public final class Ed25519Verify {

    private Ed25519Verify() {
    }

    private static final long[] GF0 = new long[16];
    private static final long[] GF1 = {1};
    private static final long[] D = {0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
            0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203};
    private static final long[] D2 = {0xf159, 0x26b2, 0x9b94, 0xebd6, 0xb156, 0x8283, 0x149a, 0x00e0,
            0xd130, 0xeef3, 0x80f2, 0x198e, 0xfce7, 0x56df, 0xd9dc, 0x2406};
    private static final long[] X = {0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
            0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169};
    private static final long[] Y = {0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
            0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666};
    private static final long[] I = {0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
            0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83};
    private static final long[] L = {0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6, 0x9c, 0xf7, 0xa2,
            0xde, 0xf9, 0xde, 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10};

    public static boolean verify(byte[] message, byte[] signature, byte[] publicKey) {
        if (message == null || signature == null || publicKey == null
                || signature.length != 64 || publicKey.length != 32) {
            return false;
        }
        // canonical S check: S < L
        boolean less = false;
        for (int i = 31; i >= 0; i--) {
            int s = signature[32 + i] & 0xff;
            int l = (int) L[i];
            if (s < l) {
                less = true;
                break;
            }
            if (s > l) {
                break;
            }
        }
        if (!less) {
            return false;
        }
        long[][] p = new long[4][16];
        long[][] q = new long[4][16];
        if (unpackneg(q, publicKey) != 0 || isSmallOrder(q)) {
            return false;
        }
        byte[] h;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            md.update(signature, 0, 32);
            md.update(publicKey, 0, 32);
            md.update(message);
            h = md.digest();
        } catch (Exception e) {
            return false;
        }
        byte[] hr = new byte[32];
        reduce(hr, h);
        scalarmult(p, q, hr);
        byte[] s = new byte[32];
        System.arraycopy(signature, 32, s, 0, 32);
        scalarbase(q, s);
        add(p, q);
        byte[] t = new byte[32];
        pack(t, p);
        int diff = 0;
        for (int i = 0; i < 32; i++) {
            diff |= (t[i] ^ signature[i]) & 0xff;
        }
        return diff == 0;
    }

    // A weak trusted key must never turn an all-zero scalar into a valid signature.
    // The curve cofactor is 8; exactly the small-order points satisfy [8]A = identity.
    // Work on a copy because the normal verification path still needs the decoded key.
    private static boolean isSmallOrder(long[][] point) {
        long[][] multiple = new long[4][16];
        for (int i = 0; i < 4; i++) {
            System.arraycopy(point[i], 0, multiple[i], 0, 16);
        }
        for (int i = 0; i < 3; i++) add(multiple, multiple);
        return !neq25519(multiple[0], GF0) && !neq25519(multiple[1], multiple[2]);
    }

    // ---- field arithmetic mod 2^255-19 (16 limbs of 16 bits, in longs) ----

    private static void set25519(long[] r, long[] a) {
        for (int i = 0; i < 16; i++) {
            r[i] = i < a.length ? a[i] : 0;
        }
    }

    private static void car25519(long[] o) {
        for (int i = 0; i < 16; i++) {
            o[i] += (1L << 16);
            long c = o[i] >> 16;
            o[(i + 1) * (i < 15 ? 1 : 0)] += c - 1 + (37 * (c - 1)) * (i == 15 ? 1 : 0);
            o[i] -= c << 16;
        }
    }

    private static void sel25519(long[] p, long[] q, int b) {
        long c = ~((long) b - 1);
        for (int i = 0; i < 16; i++) {
            long t = c & (p[i] ^ q[i]);
            p[i] ^= t;
            q[i] ^= t;
        }
    }

    private static void pack25519(byte[] o, long[] n) {
        long[] m = new long[16];
        long[] t = new long[16];
        System.arraycopy(n, 0, t, 0, 16);
        car25519(t);
        car25519(t);
        car25519(t);
        for (int j = 0; j < 2; j++) {
            m[0] = t[0] - 0xffed;
            for (int i = 1; i < 15; i++) {
                m[i] = t[i] - 0xffff - ((m[i - 1] >> 16) & 1);
                m[i - 1] &= 0xffff;
            }
            m[15] = t[15] - 0x7fff - ((m[14] >> 16) & 1);
            int b = (int) ((m[15] >> 16) & 1);
            m[14] &= 0xffff;
            sel25519(t, m, 1 - b);
        }
        for (int i = 0; i < 16; i++) {
            o[2 * i] = (byte) (t[i] & 0xff);
            o[2 * i + 1] = (byte) (t[i] >> 8);
        }
    }

    private static boolean neq25519(long[] a, long[] b) {
        byte[] c = new byte[32];
        byte[] d = new byte[32];
        pack25519(c, a);
        pack25519(d, b);
        int diff = 0;
        for (int i = 0; i < 32; i++) {
            diff |= c[i] ^ d[i];
        }
        return diff != 0;
    }

    private static int par25519(long[] a) {
        byte[] d = new byte[32];
        pack25519(d, a);
        return d[0] & 1;
    }

    private static void unpack25519(long[] o, byte[] n) {
        for (int i = 0; i < 16; i++) {
            o[i] = (n[2 * i] & 0xffL) + ((n[2 * i + 1] & 0xffL) << 8);
        }
        o[15] &= 0x7fff;
    }

    private static void A(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) {
            o[i] = a[i] + b[i];
        }
    }

    private static void Z(long[] o, long[] a, long[] b) {
        for (int i = 0; i < 16; i++) {
            o[i] = a[i] - b[i];
        }
    }

    private static void M(long[] o, long[] a, long[] b) {
        long[] t = new long[31];
        for (int i = 0; i < 16; i++) {
            for (int j = 0; j < 16; j++) {
                t[i + j] += a[i] * b[j];
            }
        }
        for (int i = 0; i < 15; i++) {
            t[i] += 38 * t[i + 16];
        }
        System.arraycopy(t, 0, o, 0, 16);
        car25519(o);
        car25519(o);
    }

    private static void S(long[] o, long[] a) {
        M(o, a, a);
    }

    private static void inv25519(long[] o, long[] in) {
        long[] c = new long[16];
        System.arraycopy(in, 0, c, 0, 16);
        for (int a = 253; a >= 0; a--) {
            S(c, c);
            if (a != 2 && a != 4) {
                M(c, c, in);
            }
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    private static void pow2523(long[] o, long[] in) {
        long[] c = new long[16];
        System.arraycopy(in, 0, c, 0, 16);
        for (int a = 250; a >= 0; a--) {
            S(c, c);
            if (a != 1) {
                M(c, c, in);
            }
        }
        System.arraycopy(c, 0, o, 0, 16);
    }

    // ---- group operations (extended coordinates) ----

    private static void add(long[][] p, long[][] q) {
        long[] a = new long[16], b = new long[16], c = new long[16], d = new long[16];
        long[] t = new long[16], e = new long[16], f = new long[16], g = new long[16], h = new long[16];
        Z(a, p[1], p[0]);
        Z(t, q[1], q[0]);
        M(a, a, t);
        A(b, p[0], p[1]);
        A(t, q[0], q[1]);
        M(b, b, t);
        M(c, p[3], q[3]);
        M(c, c, D2);
        M(d, p[2], q[2]);
        A(d, d, d);
        Z(e, b, a);
        Z(f, d, c);
        A(g, d, c);
        A(h, b, a);
        M(p[0], e, f);
        M(p[1], h, g);
        M(p[2], g, f);
        M(p[3], e, h);
    }

    private static void cswap(long[][] p, long[][] q, int b) {
        for (int i = 0; i < 4; i++) {
            sel25519(p[i], q[i], b);
        }
    }

    private static void pack(byte[] r, long[][] p) {
        long[] tx = new long[16], ty = new long[16], zi = new long[16];
        inv25519(zi, p[2]);
        M(tx, p[0], zi);
        M(ty, p[1], zi);
        pack25519(r, ty);
        r[31] ^= par25519(tx) << 7;
    }

    private static void scalarmult(long[][] p, long[][] q, byte[] s) {
        set25519(p[0], GF0);
        set25519(p[1], GF1);
        set25519(p[2], GF1);
        set25519(p[3], GF0);
        for (int i = 255; i >= 0; --i) {
            int b = ((s[i / 8] & 0xff) >> (i & 7)) & 1;
            cswap(p, q, b);
            add(q, p);
            add(p, p);
            cswap(p, q, b);
        }
    }

    private static void scalarbase(long[][] p, byte[] s) {
        long[][] q = new long[4][16];
        set25519(q[0], X);
        set25519(q[1], Y);
        set25519(q[2], GF1);
        M(q[3], X, Y);
        scalarmult(p, q, s);
    }

    private static int unpackneg(long[][] r, byte[] p) {
        long[] t = new long[16], chk = new long[16], num = new long[16], den = new long[16];
        long[] den2 = new long[16], den4 = new long[16], den6 = new long[16];
        set25519(r[2], GF1);
        unpack25519(r[1], p);
        // RFC 8032 section 5.1.3 requires y < 2^255-19, not reduction modulo p.
        byte[] canonicalY = new byte[32];
        pack25519(canonicalY, r[1]);
        for (int i = 0; i < 32; i++) {
            int encoded = p[i] & (i == 31 ? 0x7f : 0xff);
            if ((canonicalY[i] & 0xff) != encoded) return -1;
        }
        S(num, r[1]);
        M(den, num, D);
        Z(num, num, r[2]);
        A(den, r[2], den);

        S(den2, den);
        S(den4, den2);
        M(den6, den4, den2);
        M(t, den6, num);
        M(t, t, den);

        pow2523(t, t);
        M(t, t, num);
        M(t, t, den);
        M(t, t, den);
        M(r[0], t, den);

        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num)) {
            M(r[0], r[0], I);
        }
        S(chk, r[0]);
        M(chk, chk, den);
        if (neq25519(chk, num)) {
            return -1;
        }
        // Zero has no negative encoding; reject x=0 with sign bit 1.
        if ((p[31] & 0x80) != 0 && !neq25519(r[0], GF0)) return -1;
        if (par25519(r[0]) == ((p[31] & 0xff) >> 7)) {
            Z(r[0], GF0, r[0]);
        }
        M(r[3], r[0], r[1]);
        return 0;
    }

    // ---- scalar reduction mod L ----

    private static void modL(byte[] r, long[] x) {
        long carry;
        int i, j;
        for (i = 63; i >= 32; --i) {
            carry = 0;
            for (j = i - 32; j < i - 12; ++j) {
                x[j] += carry - 16 * x[i] * L[j - (i - 32)];
                carry = (x[j] + 128) >> 8;
                x[j] -= carry << 8;
            }
            x[j] += carry;
            x[i] = 0;
        }
        carry = 0;
        for (j = 0; j < 32; j++) {
            x[j] += carry - (x[31] >> 4) * L[j];
            carry = x[j] >> 8;
            x[j] &= 255;
        }
        for (j = 0; j < 32; j++) {
            x[j] -= carry * L[j];
        }
        for (i = 0; i < 32; i++) {
            x[i + 1] += x[i] >> 8;
            r[i] = (byte) (x[i] & 255);
        }
    }

    private static void reduce(byte[] out32, byte[] h64) {
        long[] x = new long[64];
        for (int i = 0; i < 64; i++) {
            x[i] = h64[i] & 0xffL;
        }
        modL(out32, x);
    }
}
