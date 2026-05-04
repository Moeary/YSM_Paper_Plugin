package com.ysm.paper.nativebridge.crypto;

final class YsmByteReader {
    private final byte[] data;
    private int offset;

    YsmByteReader(byte[] data) {
        this.data = data;
    }

    long readVarInt() {
        long value = 0;
        int shift = 0;
        while (true) {
            ensure(1);
            int b = data[offset++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
            if (shift >= 64) {
                throw new IllegalArgumentException("VarInt too large");
            }
        }
    }

    int readVarIntAsInt() {
        long value = readVarInt();
        if (value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("VarInt exceeds int range: " + value);
        }
        return (int) value;
    }

    int readUnsignedByte() {
        ensure(1);
        return data[offset++] & 0xff;
    }

    int readInt() {
        ensure(Integer.BYTES);
        int value = LittleEndian.readInt(data, offset);
        offset += Integer.BYTES;
        return value;
    }

    float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    String readString() {
        int length = readVarIntAsInt();
        if (length == 0) {
            return "";
        }
        ensure(length);
        String value = new String(data, offset, length, java.nio.charset.StandardCharsets.UTF_8);
        offset += length;
        return value;
    }

    byte[] readByteSequence() {
        return readBytes(readVarIntAsInt());
    }

    byte[] readBytes(int length) {
        ensure(length);
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        offset += length;
        return out;
    }

    int remaining() {
        return data.length - offset;
    }

    void skip(int count) {
        ensure(count);
        offset += count;
    }

    void skipFloats(int count) {
        skip(count * Float.BYTES);
    }

    int position() {
        return offset;
    }

    int size() {
        return data.length;
    }

    private void ensure(int count) {
        if (data.length - offset < count) {
            throw new IllegalArgumentException("Unexpected end of YSM buffer");
        }
    }
}
