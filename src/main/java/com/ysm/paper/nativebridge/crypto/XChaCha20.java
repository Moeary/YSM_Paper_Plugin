package com.ysm.paper.nativebridge.crypto;

import java.util.Arrays;

final class XChaCha20 {
    private XChaCha20() {
    }

    static Context keySetup(byte[] key, byte[] iv, int rounds) {
        if (key.length != 32) {
            throw new IllegalArgumentException("XChaCha20 key must be 32 bytes");
        }
        if (iv.length != 24) {
            throw new IllegalArgumentException("XChaCha20 IV must be 24 bytes");
        }

        byte[] subKey = hChaCha20(key, iv, rounds);
        Context ctx = new Context(rounds);
        ctx.input[0] = 0x61707865;
        ctx.input[1] = 0x3320646e;
        ctx.input[2] = 0x79622d32;
        ctx.input[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) {
            ctx.input[4 + i] = LittleEndian.readInt(subKey, i * 4);
        }
        ctx.input[12] = 0;
        ctx.input[13] = 0;
        ctx.input[14] = LittleEndian.readInt(iv, 16);
        ctx.input[15] = LittleEndian.readInt(iv, 20);
        return ctx;
    }

    static byte[] xor(Context ctx, byte[] data) {
        byte[] out = new byte[data.length];
        int offset = 0;
        byte[] block = new byte[64];
        while (offset < data.length) {
            block(ctx, block);
            int count = Math.min(64, data.length - offset);
            for (int i = 0; i < count; i++) {
                out[offset + i] = (byte) (data[offset + i] ^ block[i]);
            }
            offset += count;
        }
        return out;
    }

    private static byte[] hChaCha20(byte[] key, byte[] nonce, int rounds) {
        int[] x = new int[16];
        x[0] = 0x61707865;
        x[1] = 0x3320646e;
        x[2] = 0x79622d32;
        x[3] = 0x6b206574;
        for (int i = 0; i < 8; i++) {
            x[4 + i] = LittleEndian.readInt(key, i * 4);
        }
        for (int i = 0; i < 4; i++) {
            x[12 + i] = LittleEndian.readInt(nonce, i * 4);
        }

        for (int i = 0; i < rounds; i += 2) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14);
            quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15);
            quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13);
            quarterRound(x, 3, 4, 9, 14);
        }

        byte[] out = new byte[32];
        writeInt(out, 0, x[0]);
        writeInt(out, 4, x[1]);
        writeInt(out, 8, x[2]);
        writeInt(out, 12, x[3]);
        writeInt(out, 16, x[12]);
        writeInt(out, 20, x[13]);
        writeInt(out, 24, x[14]);
        writeInt(out, 28, x[15]);
        return out;
    }

    private static void block(Context ctx, byte[] out) {
        int[] x = Arrays.copyOf(ctx.input, 16);
        for (int i = ctx.rounds; i > 0; i -= 2) {
            quarterRound(x, 0, 4, 8, 12);
            quarterRound(x, 1, 5, 9, 13);
            quarterRound(x, 2, 6, 10, 14);
            quarterRound(x, 3, 7, 11, 15);
            quarterRound(x, 0, 5, 10, 15);
            quarterRound(x, 1, 6, 11, 12);
            quarterRound(x, 2, 7, 8, 13);
            quarterRound(x, 3, 4, 9, 14);
        }
        for (int i = 0; i < 16; i++) {
            x[i] += ctx.input[i];
            writeInt(out, i * 4, x[i]);
        }

        ctx.input[12]++;
        if (ctx.input[12] == 0) {
            ctx.input[13]++;
        }
    }

    private static void quarterRound(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    private static void writeInt(byte[] out, int offset, int value) {
        out[offset] = (byte) value;
        out[offset + 1] = (byte) (value >>> 8);
        out[offset + 2] = (byte) (value >>> 16);
        out[offset + 3] = (byte) (value >>> 24);
    }

    static final class Context {
        private final int[] input = new int[16];
        private int rounds;

        private Context(int rounds) {
            this.rounds = rounds;
        }

        void updateState(long hash) {
            rounds = 10 * (int) Long.remainderUnsigned(hash, 3) + 10;
            int lo = (int) hash;
            int hi = (int) (hash >>> 32);
            for (int i = 4; i < 16; i++) {
                input[i] ^= (i % 2 == 0) ? lo : hi;
            }
        }
    }
}
