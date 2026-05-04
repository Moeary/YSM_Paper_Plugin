package com.ysm.paper.nativebridge.crypto;

final class LittleEndian {
    private LittleEndian() {
    }

    static int readInt(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    static long readUnsignedInt(byte[] data, int offset) {
        return readInt(data, offset) & 0xffffffffL;
    }

    static long readLong(byte[] data, int offset) {
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24)
                | (((long) data[offset + 4] & 0xff) << 32)
                | (((long) data[offset + 5] & 0xff) << 40)
                | (((long) data[offset + 6] & 0xff) << 48)
                | (((long) data[offset + 7] & 0xff) << 56);
    }

    static void writeLong(byte[] data, int offset, long value) {
        for (int i = 0; i < Long.BYTES; i++) {
            data[offset + i] = (byte) (value >>> (i * 8));
        }
    }
}
