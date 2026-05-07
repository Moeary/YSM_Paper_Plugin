package com.ysm.paper.openysm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class YsmBinaryBuffer implements AutoCloseable {
    private final ByteArrayOutputStream out;
    private final byte[] in;
    private int offset;

    YsmBinaryBuffer() {
        this.out = new ByteArrayOutputStream();
        this.in = null;
    }

    YsmBinaryBuffer(byte[] data) {
        this.out = null;
        this.in = data == null ? new byte[0] : data;
    }

    int getOffset() {
        return offset;
    }

    void setOffset(int offset) {
        if (offset < 0 || offset > input().length) {
            throw new IndexOutOfBoundsException(offset);
        }
        this.offset = offset;
    }

    byte readByte() {
        requireReadable(1);
        return input()[offset++];
    }

    float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    long readDword() {
        return Integer.toUnsignedLong(readInt());
    }

    int readVarInt() {
        int value = 0;
        int position = 0;
        while (true) {
            byte currentByte = readByte();
            value |= (currentByte & 0x7f) << position;
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 64) {
                throw new IllegalArgumentException("VarInt too big");
            }
        }
    }

    long readVarLong() {
        long value = 0L;
        int position = 0;
        while (true) {
            byte currentByte = readByte();
            value |= (long) (currentByte & 0x7f) << position;
            if ((currentByte & 0x80) == 0) {
                return value;
            }
            position += 7;
            if (position >= 64) {
                throw new IllegalArgumentException("VarLong too big");
            }
        }
    }

    byte[] readByteArray() {
        int length = readVarInt();
        if (length == 0) {
            return new byte[0];
        }
        return readBytes(length);
    }

    String readString() {
        int length = readVarInt();
        if (length == 0) {
            return "";
        }
        return new String(readBytes(length), StandardCharsets.UTF_8);
    }

    void skipBytes(int length) {
        requireReadable(length);
        offset += length;
    }

    void writeVarInt(int value) {
        while ((value & -128) != 0) {
            writeByte((byte) ((value & 127) | 128));
            value >>>= 7;
        }
        writeByte((byte) value);
    }

    void writeVarLong(long value) {
        while ((value & -128L) != 0L) {
            writeByte((byte) ((value & 127L) | 128L));
            value >>>= 7;
        }
        writeByte((byte) value);
    }

    void writeString(String value) {
        if (value == null || value.isEmpty()) {
            writeVarInt(0);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(bytes.length);
        writeBytes(bytes);
    }

    void writeByte(byte value) {
        output().write(value);
    }

    void writeFloat(float value) {
        writeInt(Float.floatToIntBits(value));
    }

    void writeByteArray(byte[] data) {
        if (data == null || data.length == 0) {
            writeVarInt(0);
            return;
        }
        writeVarInt(data.length);
        writeBytes(data);
    }

    byte[] toArray() {
        return out != null ? out.toByteArray() : Arrays.copyOfRange(input(), offset, input().length);
    }

    @Override
    public void close() {
    }

    private int readInt() {
        requireReadable(Integer.BYTES);
        byte[] data = input();
        int value = (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
        offset += Integer.BYTES;
        return value;
    }

    private byte[] readBytes(int length) {
        requireReadable(length);
        byte[] bytes = Arrays.copyOfRange(input(), offset, offset + length);
        offset += length;
        return bytes;
    }

    private void writeBytes(byte[] data) {
        output().writeBytes(data);
    }

    private void writeInt(int value) {
        output().write(value & 0xff);
        output().write((value >>> 8) & 0xff);
        output().write((value >>> 16) & 0xff);
        output().write((value >>> 24) & 0xff);
    }

    private void requireReadable(int length) {
        if (length < 0 || offset + length > input().length) {
            throw new IndexOutOfBoundsException("Need " + length + " byte(s) at " + offset);
        }
    }

    private byte[] input() {
        if (in == null) {
            throw new IllegalStateException("Buffer is write-only");
        }
        return in;
    }

    private ByteArrayOutputStream output() {
        if (out == null) {
            throw new IllegalStateException("Buffer is read-only");
        }
        return out;
    }
}
