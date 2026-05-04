package com.ysm.paper.nativebridge.crypto;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class YsmRawPacketCodec {
    public static final int KEY_BYTES = 56;

    private YsmRawPacketCodec() {
    }

    public static byte[] encodePlainType1(byte[] sessionKey, byte[] padding) {
        requireKey(sessionKey);
        return encodePlain(1, sessionKey, padding);
    }

    public static byte[] encodePlainType2(long unknown, byte[] sessionKey, byte[] padding) {
        requireKey(sessionKey);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, unknown);
        body.writeBytes(sessionKey);
        return encodePlain(2, body.toByteArray(), padding);
    }

    public static byte[] encodePlainType4(long value, byte[] padding) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, value);
        return encodePlain(4, body.toByteArray(), padding);
    }

    public static byte[] encodePlainType5(
            byte[] requestToken,
            long totalBytes,
            long offset,
            byte[] chunk,
            byte[] padding) {
        if (requestToken == null || requestToken.length == 0) {
            throw new IllegalArgumentException("Native cache request token must not be empty");
        }
        if (totalBytes < 0 || offset < 0) {
            throw new IllegalArgumentException("Native cache type=5 offsets must be non-negative");
        }
        byte[] safeChunk = chunk == null ? new byte[0] : chunk;
        ByteArrayOutputStream body = new ByteArrayOutputStream(
                requestToken.length + 15 + safeChunk.length);
        body.writeBytes(requestToken);
        writeVarInt(body, totalBytes);
        writeVarInt(body, offset);
        writeVarInt(body, safeChunk.length);
        body.writeBytes(safeChunk);
        return encodePlain(5, body.toByteArray(), padding);
    }

    public static byte[] encodePlainType3Keys(byte[] serverCacheKey, byte[] clientCacheKey, byte[] padding) {
        requireKey(serverCacheKey);
        requireKey(clientCacheKey);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(serverCacheKey);
        body.writeBytes(clientCacheKey);
        return encodePlain(3, body.toByteArray(), padding);
    }

    public static byte[] encodePlainType3KeyManifest(
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            List<ModelInfo> models,
            byte[] padding) {
        requireKey(serverCacheKey);
        requireKey(clientCacheKey);
        List<ModelInfo> safeModels = List.copyOf(models);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(serverCacheKey);
        body.writeBytes(clientCacheKey);
        writeVarInt(body, safeModels.size());
        for (ModelInfo model : safeModels) {
            writeString(body, model.name());
            writeVarInt(body, model.format());
            writeVarInt(body, model.fieldA());
            writeVarInt(body, model.fieldB());
        }
        return encodePlain(3, body.toByteArray(), padding);
    }

    public static byte[] encodePlainType3(
            long unknown,
            byte[] firstMetadata,
            byte[] secondMetadata,
            byte[] modelKey,
            List<ModelInfo> models,
            List<Long> preludeValues,
            byte[] padding) {
        requireKey(modelKey);
        byte[] metadataA = fixedBytes(firstMetadata, 0x1c);
        byte[] metadataB = fixedBytes(secondMetadata, 0x1c);
        List<ModelInfo> safeModels = List.copyOf(models);
        List<Long> safePrelude = preludeValues == null ? List.of() : List.copyOf(preludeValues);
        if (!safePrelude.isEmpty() && safePrelude.size() != safeModels.size()) {
            throw new IllegalArgumentException("Type=3 prelude value count must match model count");
        }

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeVarInt(body, unknown);
        body.writeBytes(metadataA);
        body.writeBytes(metadataB);
        body.writeBytes(modelKey);
        writeVarInt(body, safeModels.size());
        for (int i = 0; i < safeModels.size(); i++) {
            writeVarInt(body, safePrelude.isEmpty() ? 0L : safePrelude.get(i));
        }
        for (ModelInfo model : safeModels) {
            writeString(body, model.name());
            writeVarInt(body, 0L);
            writeVarInt(body, 0L);
            writeVarInt(body, model.format());
            writeVarInt(body, model.fieldA());
            writeVarInt(body, model.fieldB());
        }
        return encodePlain(3, body.toByteArray(), padding);
    }

    public static byte[] encodePlain(int type, byte[] body, byte[] padding) {
        if (type < 0) {
            throw new IllegalArgumentException("Packet type must be non-negative");
        }
        byte[] safePadding = padding == null ? new byte[0] : padding;
        if (safePadding.length > 0x7f) {
            throw new IllegalArgumentException("Native packet padding is limited to 127 bytes");
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(2 + safePadding.length + 5 + body.length);
        out.write(safePadding.length & 0x7f);
        out.write(0);
        out.writeBytes(safePadding);
        writeVarInt(out, type);
        out.writeBytes(body);
        return out.toByteArray();
    }

    public static byte[] encryptBodyOnly(byte[] plainPacket, byte[] currentKey) {
        requireKey(currentKey);
        byte[] encrypted = xChaCha(plainPacket, currentKey, 30);
        encrypted = YsmCrypto.mt19937Xor(encrypted, currentKey);
        return appendPacketHash(encrypted);
    }

    public static byte[] encryptWithNextKey(byte[] plainPacket, byte[] currentKey, byte[] nextKey) {
        requireKey(currentKey);
        requireKey(nextKey);
        byte[] encrypted = xChaCha(plainPacket, currentKey, 30);
        encrypted = YsmCrypto.mt19937Xor(encrypted, currentKey);

        byte[] withoutHash = Arrays.copyOf(encrypted, encrypted.length + KEY_BYTES);
        System.arraycopy(nextKey, 0, withoutHash, encrypted.length, KEY_BYTES);
        return appendPacketHash(withoutHash);
    }

    public static byte[] encryptIncludingPlainTrailer(byte[] plainPacket, byte[] currentKey, byte[] plainTrailer) {
        requireKey(currentKey);
        byte[] safeTrailer = plainTrailer == null ? new byte[0] : plainTrailer;
        byte[] fullPlain = Arrays.copyOf(plainPacket, plainPacket.length + safeTrailer.length);
        System.arraycopy(safeTrailer, 0, fullPlain, plainPacket.length, safeTrailer.length);
        byte[] encrypted = xChaCha(fullPlain, currentKey, 30);
        encrypted = YsmCrypto.mt19937Xor(encrypted, currentKey);
        return appendPacketHash(encrypted);
    }

    public static PlainPacket decryptBodyOnly(byte[] packet, byte[] currentKey) {
        requireKey(currentKey);
        if (packet.length <= 2 + 1 + Long.BYTES) {
            throw new IllegalArgumentException("Packet too short");
        }

        int hashOffset = verifyPacketHash(packet);
        return decryptEncryptedBody(Arrays.copyOf(packet, hashOffset), currentKey);
    }

    public static WirePacket decryptWithNextKey(byte[] packet, byte[] currentKey) {
        requireKey(currentKey);
        if (packet.length <= 2 + 1 + KEY_BYTES + Long.BYTES) {
            throw new IllegalArgumentException("Packet too short for next-key trailer");
        }

        int hashOffset = verifyPacketHash(packet);
        int nextKeyOffset = hashOffset - KEY_BYTES;
        if (nextKeyOffset <= 0) {
            throw new IllegalArgumentException("Packet does not contain an encrypted body before the next key");
        }

        byte[] nextKey = Arrays.copyOfRange(packet, nextKeyOffset, hashOffset);
        PlainPacket plain = decryptEncryptedBody(Arrays.copyOf(packet, nextKeyOffset), currentKey);
        return new WirePacket(plain, nextKey);
    }

    public static int verifyPacketHashOffset(byte[] packet) {
        return verifyPacketHash(packet);
    }

    public static boolean hasValidPacketHash(byte[] packet) {
        try {
            verifyPacketHash(packet);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public static PlainPacket decodePlain(byte[] plainPacket) {
        Reader reader = new Reader(plainPacket);
        int padding = reader.readUnsignedShortLE() & 0x7f;
        reader.skip(padding);
        int type = reader.readVarIntAsInt();
        byte[] body = reader.readRemainingBytes();
        ParsedBody parsedBody = parseBody(type, body);
        return new PlainPacket(type, body, parsedBody.selectedKey(), parsedBody.summary(), parsedBody.models());
    }

    public static byte[] selectedKeyOrThrow(PlainPacket packet) {
        return packet.selectedKey()
                .orElseThrow(() -> new IllegalArgumentException("Packet type " + packet.type() + " does not carry a 56-byte key"));
    }

    private static ParsedBody parseBody(int type, byte[] body) {
        Reader reader = new Reader(body);
        return switch (type) {
            case 1 -> {
                byte[] key = reader.readBytes(KEY_BYTES);
                yield new ParsedBody(Optional.of(key), "type=1 key", List.of());
            }
            case 2 -> {
                long unknown = reader.readVarInt();
                byte[] key = reader.readBytes(KEY_BYTES);
                yield new ParsedBody(Optional.of(key), "type=2 unknown=" + unknown + " key", List.of());
            }
            case 3 -> parseType3(reader);
            case 4 -> {
                long value = reader.remaining() == 0 ? -1L : reader.readVarInt();
                yield new ParsedBody(Optional.empty(), "type=4 value=" + value, List.of());
            }
            default -> new ParsedBody(Optional.empty(), "type=" + type + " bodyBytes=" + body.length, List.of());
        };
    }

    private static ParsedBody parseType3(Reader reader) {
        long unknown = reader.readVarInt();
        reader.skip(0x1c);
        reader.skip(0x1c);
        byte[] modelKey = reader.readBytes(KEY_BYTES);
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readVarInt();
        }

        java.util.ArrayList<ModelInfo> models = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = reader.readString();
            long zeroA = reader.readVarInt();
            long zeroB = reader.readVarInt();
            int format = reader.readVarIntAsInt();
            long fieldA = reader.readVarInt();
            long fieldB = reader.readVarInt();
            models.add(new ModelInfo(name, format, fieldA, fieldB));
            if (zeroA != 0 || zeroB != 0) {
                throw new IllegalArgumentException("Unexpected type=3 metadata flags for " + name
                        + ": " + zeroA + ", " + zeroB);
            }
        }
        return new ParsedBody(Optional.of(modelKey), "type=3 unknown=" + unknown
                + " models=" + models.size(), List.copyOf(models));
    }

    private static byte[] xChaCha(byte[] data, byte[] key, int rounds) {
        byte[] keyPart = Arrays.copyOfRange(key, 0, 32);
        byte[] ivPart = Arrays.copyOfRange(key, 32, 56);
        return XChaCha20.xor(XChaCha20.keySetup(keyPart, ivPart, rounds), data);
    }

    private static PlainPacket decryptEncryptedBody(byte[] encrypted, byte[] currentKey) {
        byte[] afterXor = YsmCrypto.mt19937Xor(encrypted, currentKey);
        byte[] plain = xChaCha(afterXor, currentKey, 30);
        return decodePlain(plain);
    }

    private static byte[] appendPacketHash(byte[] withoutHash) {
        byte[] out = Arrays.copyOf(withoutHash, withoutHash.length + Long.BYTES);
        LittleEndian.writeLong(out, withoutHash.length,
                CityHash64.hashWithSeed(withoutHash, YsmSeeds.PACKET_VERIFICATION));
        return out;
    }

    private static int verifyPacketHash(byte[] packet) {
        int hashOffset = packet.length - Long.BYTES;
        long expected = LittleEndian.readLong(packet, hashOffset);
        long actual = CityHash64.hashWithSeed(packet, 0, hashOffset, YsmSeeds.PACKET_VERIFICATION);
        if (actual != expected) {
            throw new IllegalArgumentException("Packet hash mismatch");
        }
        return hashOffset;
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("YSM native key must be 56 bytes");
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            out.write((int) (remaining & 0x7fL) | 0x80);
            remaining >>>= 7;
        }
        out.write((int) remaining);
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static byte[] fixedBytes(byte[] value, int length) {
        if (value == null) {
            return new byte[length];
        }
        if (value.length != length) {
            throw new IllegalArgumentException("Expected " + length + " bytes, got " + value.length);
        }
        return value;
    }

    public record PlainPacket(
            int type,
            byte[] body,
            Optional<byte[]> selectedKey,
            String summary,
            List<ModelInfo> models) {
    }

    public record WirePacket(PlainPacket plain, byte[] nextTransportKey) {
    }

    public record ModelInfo(String name, int format, long fieldA, long fieldB) {
    }

    private record ParsedBody(Optional<byte[]> selectedKey, String summary, List<ModelInfo> models) {
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        private Reader(byte[] data) {
            this.data = data;
        }

        private int readUnsignedShortLE() {
            ensure(Short.BYTES);
            int value = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
            offset += Short.BYTES;
            return value;
        }

        private long readVarInt() {
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

        private int readVarIntAsInt() {
            long value = readVarInt();
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("VarInt exceeds int range: " + value);
            }
            return (int) value;
        }

        private String readString() {
            int length = readVarIntAsInt();
            ensure(length);
            String value = new String(data, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        private byte[] readBytes(int length) {
            ensure(length);
            byte[] out = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return out;
        }

        private byte[] readRemainingBytes() {
            return readBytes(remaining());
        }

        private int remaining() {
            return data.length - offset;
        }

        private void skip(int count) {
            ensure(count);
            offset += count;
        }

        private void ensure(int count) {
            if (count < 0 || data.length - offset < count) {
                throw new IllegalArgumentException("Unexpected end of native packet buffer");
            }
        }
    }
}
