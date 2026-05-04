package com.ysm.paper.nativebridge.crypto;

final class Mt19937_64 {
    private static final int NN = 312;
    private static final int MM = 156;
    private static final long MATRIX_A = 0xB5026F5AA96619E9L;
    private static final long UM = 0xFFFFFFFF80000000L;
    private static final long LM = 0x7FFFFFFFL;

    private final long[] mt = new long[NN];
    private int index = NN + 1;

    Mt19937_64(long seed) {
        seed(seed);
    }

    long nextLong() {
        if (index >= NN) {
            twist();
        }

        long x = mt[index++];
        x ^= (x >>> 29) & 0x5555555555555555L;
        x ^= (x << 17) & 0x71D67FFFEDA60000L;
        x ^= (x << 37) & 0xFFF7EEE000000000L;
        x ^= x >>> 43;
        return x;
    }

    private void seed(long seed) {
        mt[0] = seed;
        for (index = 1; index < NN; index++) {
            mt[index] = 6364136223846793005L * (mt[index - 1] ^ (mt[index - 1] >>> 62)) + index;
        }
    }

    private void twist() {
        int i = 0;
        for (; i < NN - MM; i++) {
            long x = (mt[i] & UM) | (mt[i + 1] & LM);
            mt[i] = mt[i + MM] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        }
        for (; i < NN - 1; i++) {
            long x = (mt[i] & UM) | (mt[i + 1] & LM);
            mt[i] = mt[i + (MM - NN)] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        }
        long x = (mt[NN - 1] & UM) | (mt[0] & LM);
        mt[NN - 1] = mt[MM - 1] ^ (x >>> 1) ^ ((x & 1L) == 0 ? 0 : MATRIX_A);
        index = 0;
    }
}
