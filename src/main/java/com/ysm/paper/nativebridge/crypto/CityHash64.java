package com.ysm.paper.nativebridge.crypto;

final class CityHash64 {
    private static final long K0 = 0xE4986A230E5AAA17L;
    private static final long K1 = 0x91AF10802CAB25A5L;
    private static final long K2 = 0xAF29CE778879D9C7L;
    private static final long KMUL = 0xDE0F6EE09BDBAB91L;

    private CityHash64() {
    }

    static long hash(byte[] data) {
        return hash(data, 0, data.length);
    }

    static long hashWithSeed(byte[] data, long seed) {
        return hashLen16(hash(data) - K2, seed);
    }

    static long hashWithSeed(byte[] data, int offset, int length, long seed) {
        return hashLen16(hash(data, offset, length) - K2, seed);
    }

    private static long hash(byte[] s, int off, int len) {
        if (len <= 32) {
            if (len <= 16) {
                return hashLen0to16(s, off, len);
            }
            return hashLen17to32(s, off, len);
        }
        if (len <= 64) {
            return hashLen33to64(s, off, len);
        }

        long x = fetch64(s, off + len - 40);
        long y = fetch64(s, off + len - 16) + fetch64(s, off + len - 56);
        long z = hashLen16(fetch64(s, off + len - 48) + len, fetch64(s, off + len - 24));
        Pair v = weakHashLen32WithSeeds(s, off + len - 64, len, z);
        Pair w = weakHashLen32WithSeeds(s, off + len - 32, y + K1, x);
        x = x * K1 + fetch64(s, off);

        int roundedLen = (len - 1) & ~63;
        int pos = off;
        do {
            x = Long.rotateRight(x + y + v.first + fetch64(s, pos + 8), 37) * K1;
            y = Long.rotateRight(y + v.second + fetch64(s, pos + 48), 42) * K1;
            x ^= w.second;
            y += v.first + fetch64(s, pos + 40);
            z = Long.rotateRight(z + w.first, 33) * K1;
            v = weakHashLen32WithSeeds(s, pos, v.second * K1, x + w.first);
            w = weakHashLen32WithSeeds(s, pos + 32, z + w.second, y + fetch64(s, pos + 16));
            long tmp = z;
            z = x;
            x = tmp;
            pos += 64;
            roundedLen -= 64;
        } while (roundedLen != 0);

        return hashLen16(hashLen16(v.first, w.first) + shiftMix(y) * K1 + z,
                hashLen16(v.second, w.second) + x);
    }

    private static long hashLen0to16(byte[] s, int off, int len) {
        if (len >= 8) {
            long mul = K2 + len * 2L;
            long a = fetch64(s, off) + K2;
            long b = fetch64(s, off + len - 8);
            long c = Long.rotateRight(b, 37) * mul + a;
            long d = (Long.rotateRight(a, 25) + b) * mul;
            return hashLen16(c, d, mul);
        }
        if (len >= 4) {
            long mul = K2 + len * 2L;
            long a = fetch32(s, off);
            return hashLen16(len + (a << 3), fetch32(s, off + len - 4), mul);
        }
        if (len > 0) {
            int a = s[off] & 0xff;
            int b = s[off + (len >> 1)] & 0xff;
            int c = s[off + len - 1] & 0xff;
            int y = a + (b << 8);
            int z = len + (c << 2);
            return shiftMix(y * K2 ^ z * K0) * K2;
        }
        return K2;
    }

    private static long hashLen17to32(byte[] s, int off, int len) {
        long mul = K2 + len * 2L;
        long a = fetch64(s, off) * K1;
        long b = fetch64(s, off + 8);
        long c = fetch64(s, off + len - 8) * mul;
        long d = fetch64(s, off + len - 16) * K2;
        return hashLen16(Long.rotateRight(a + b, 43) + Long.rotateRight(c, 30) + d,
                a + Long.rotateRight(b + K2, 18) + c, mul);
    }

    private static long hashLen33to64(byte[] s, int off, int len) {
        long mul = K2 + len * 2L;
        long a = fetch64(s, off) * K2;
        long b = fetch64(s, off + 8);
        long c = fetch64(s, off + len - 24);
        long d = fetch64(s, off + len - 32);
        long e = fetch64(s, off + 16) * K2;
        long f = fetch64(s, off + 24) * 9;
        long g = fetch64(s, off + len - 8);
        long h = fetch64(s, off + len - 16) * mul;
        long u = Long.rotateRight(a + g, 43) + (Long.rotateRight(b, 30) + c) * 9;
        long v = ((a + g) ^ d) + f + 1;
        long w = Long.reverseBytes((u + v) * mul) + h;
        long x = Long.rotateRight(e + f, 42) + c;
        long y = (Long.reverseBytes((v + w) * mul) + g) * mul;
        long z = e + f + c;
        a = Long.reverseBytes((x + z) * mul + y) + b;
        b = shiftMix((z + a) * mul + d + h) * mul;
        return b + x;
    }

    private static Pair weakHashLen32WithSeeds(byte[] s, int off, long a, long b) {
        return weakHashLen32WithSeeds(
                fetch64(s, off),
                fetch64(s, off + 8),
                fetch64(s, off + 16),
                fetch64(s, off + 24),
                a,
                b);
    }

    private static Pair weakHashLen32WithSeeds(long w, long x, long y, long z, long a, long b) {
        a += w;
        b = Long.rotateRight(b + a + z, 21);
        long c = a;
        a += x;
        a += y;
        b += Long.rotateRight(a, 44);
        return new Pair(a + z, b + c);
    }

    private static long hashLen16(long u, long v) {
        return hash128to64(u, v);
    }

    private static long hashLen16(long u, long v, long mul) {
        long a = (u ^ v) * mul;
        a ^= a >>> 47;
        long b = (v ^ a) * mul;
        b ^= b >>> 47;
        b *= mul;
        return b;
    }

    private static long hash128to64(long low, long high) {
        return KMUL * shiftMix(KMUL * (shiftMix((low ^ high) * KMUL) ^ low));
    }

    private static long shiftMix(long value) {
        return value ^ (value >>> 47);
    }

    private static long fetch32(byte[] data, int offset) {
        return LittleEndian.readUnsignedInt(data, offset);
    }

    private static long fetch64(byte[] data, int offset) {
        return LittleEndian.readLong(data, offset);
    }

    private record Pair(long first, long second) {
    }
}
