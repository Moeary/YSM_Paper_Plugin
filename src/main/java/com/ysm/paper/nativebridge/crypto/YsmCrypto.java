package com.ysm.paper.nativebridge.crypto;

import io.airlift.compress.zstd.ZstdOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Arrays;

public final class YsmCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    private YsmCrypto() {
    }

    public static EncryptedPacket encryptPacket(byte[] plainPacket, byte[] currentKey) {
        requireKey(currentKey);
        byte[] body = xChaCha(plainPacket, currentKey, 30);
        body = mt19937Xor(body, currentKey);

        byte[] nextKey = new byte[56];
        RANDOM.nextBytes(nextKey);

        byte[] withoutHash = new byte[body.length + nextKey.length];
        System.arraycopy(body, 0, withoutHash, 0, body.length);
        System.arraycopy(nextKey, 0, withoutHash, body.length, nextKey.length);

        byte[] out = Arrays.copyOf(withoutHash, withoutHash.length + 8);
        LittleEndian.writeLong(out, withoutHash.length,
                CityHash64.hashWithSeed(withoutHash, YsmSeeds.PACKET_VERIFICATION));
        return new EncryptedPacket(out, nextKey);
    }

    public static DecryptedPacket verifyAndDecryptPacket(byte[] packet, byte[] currentKey) {
        requireKey(currentKey);
        if (packet.length <= 65) {
            throw new IllegalArgumentException("Packet too short");
        }

        int hashOffset = packet.length - 8;
        long expected = LittleEndian.readLong(packet, hashOffset);
        long actual = CityHash64.hashWithSeed(packet, 0, hashOffset, YsmSeeds.PACKET_VERIFICATION);
        if (actual != expected) {
            throw new IllegalArgumentException("Packet hash mismatch");
        }

        int nextKeyOffset = hashOffset - 56;
        if (nextKeyOffset < 0) {
            throw new IllegalArgumentException("Packet has no next key");
        }

        byte[] encryptedBody = Arrays.copyOfRange(packet, 0, nextKeyOffset);
        byte[] nextKey = Arrays.copyOfRange(packet, nextKeyOffset, hashOffset);
        byte[] afterXor = mt19937Xor(encryptedBody, currentKey);
        byte[] plain = xChaCha(afterXor, currentKey, 30);
        return new DecryptedPacket(plain, nextKey);
    }

    public static long[] calculateModelHashes(String modelHash, byte[] serverKey) {
        requireKey(serverKey);
        String safeHash = modelHash == null ? "" : modelHash;
        byte[] xored = mt19937Xor(safeHash.getBytes(java.nio.charset.StandardCharsets.UTF_8), serverKey);
        return new long[] {
                CityHash64.hashWithSeed(xored, YsmSeeds.CACHE_VERIFICATION),
                CityHash64.hashWithSeed(xored, YsmSeeds.CACHE_DECRYPTION)
        };
    }

    public static byte[] ysmZstdCompress(byte[] clearText) {
        byte[] input = clearText == null ? new byte[0] : clearText;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(128, input.length / 2));
            try (ZstdOutputStream zstd = new ZstdOutputStream(out)) {
                zstd.write(input);
            }
            return obfuscateYsmZstd(out.toByteArray());
        } catch (IOException ex) {
            throw new IllegalArgumentException("YSM zstd compression failed: " + ex.getMessage(), ex);
        }
    }

    public static long[] deriveHashFromFileName(String fileName, byte[] runtimeKey) {
        requireKey(runtimeKey);
        if (fileName.length() != 40) {
            throw new IllegalArgumentException("Hashed file name must be 40 hex characters");
        }

        byte[] buffer = new byte[20];
        for (int i = 0; i < buffer.length; i++) {
            int hi = Character.digit(fileName.charAt(i * 2), 16);
            int lo = Character.digit(fileName.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex in hashed file name");
            }
            buffer[i] = (byte) ((hi << 4) | lo);
        }

        for (int i = 0; i < buffer.length; i++) {
            buffer[i] ^= runtimeKey[i % runtimeKey.length];
        }

        long seed = LittleEndian.readUnsignedInt(buffer, 0);
        Mt19937_64 mt = new Mt19937_64(seed);
        return new long[] {
                LittleEndian.readLong(buffer, 4) ^ mt.nextLong(),
                LittleEndian.readLong(buffer, 12) ^ mt.nextLong()
        };
    }

    public static byte[] decryptCachedModel(byte[] cachedModel, String hashedFileName, byte[] runtimeKey) {
        long[] fileHash = deriveHashFromFileName(hashedFileName, runtimeKey);
        return decryptCachedModel(cachedModel, fileHash[0], fileHash[1], runtimeKey);
    }

    public static byte[] decryptCachedModel(
            byte[] cachedModel,
            long fileHashA,
            long fileHashB,
            byte[] runtimeKey) {
        requireKey(runtimeKey);
        if (cachedModel.length < 16) {
            throw new IllegalArgumentException("Cached model is too short");
        }

        int hashOffset = cachedModel.length - 8;
        long actual = CityHash64.hashWithSeed(cachedModel, 0, hashOffset, YsmSeeds.CACHE_VERIFICATION)
                ^ fileHashA
                ^ fileHashB;
        long expected = LittleEndian.readLong(cachedModel, hashOffset);
        if (actual != expected) {
            throw new IllegalArgumentException("Cached model hash mismatch");
        }

        YsmByteReader reader = new YsmByteReader(cachedModel);
        expect(reader.readVarInt(), 1);
        expect(reader.readVarInt(), 0);
        expect(reader.readVarInt(), 0);
        expect(reader.readVarInt(), 0);
        reader.readVarInt();
        expect(reader.readVarInt(), 0);
        expect(reader.readVarInt(), 0);
        expect(reader.readVarInt(), 0);
        expect(reader.readVarInt(), 0);

        byte[] encryptedData = reader.readBytes(reader.remaining() - 8);
        byte[] chacha = modifiedChaCha(encryptedData, runtimeKey, YsmSeeds.CACHE_DECRYPTION, false);
        byte[] plainPadded = mt19937Xor(chacha, runtimeKey);
        if (plainPadded.length < 2) {
            throw new IllegalArgumentException("Cached model plaintext is too short");
        }

        int padding = ((plainPadded[0] & 0xff) | ((plainPadded[1] & 0xff) << 8)) & 0x3ff;
        int start = 2 + padding;
        if (start > plainPadded.length) {
            throw new IllegalArgumentException("Cached model padding exceeds payload");
        }
        return Arrays.copyOfRange(plainPadded, start, plainPadded.length);
    }

    public static byte[] encryptCachedModel(
            byte[] payload,
            int format,
            long fileHashA,
            long fileHashB,
            byte[] runtimeKey) {
        return encryptCachedModel(payload, format, fileHashA, fileHashB, runtimeKey, new byte[0]);
    }

    public static long cachedModelBodyVerificationHash(byte[] cachedModel) {
        if (cachedModel == null || cachedModel.length < Long.BYTES) {
            throw new IllegalArgumentException("Cached model is too short");
        }
        return CityHash64.hashWithSeed(
                cachedModel,
                0,
                cachedModel.length - Long.BYTES,
                YsmSeeds.CACHE_VERIFICATION);
    }

    public static byte[] encryptCachedModel(
            byte[] payload,
            int format,
            long fileHashA,
            long fileHashB,
            byte[] runtimeKey,
            byte[] padding) {
        requireKey(runtimeKey);
        byte[] safePayload = payload == null ? new byte[0] : payload;
        byte[] safePadding = padding == null ? new byte[0] : padding;
        if (safePadding.length > 0x3ff) {
            throw new IllegalArgumentException("Cached model padding is limited to 1023 bytes");
        }

        byte[] plainPadded = new byte[2 + safePadding.length + safePayload.length];
        plainPadded[0] = (byte) (safePadding.length & 0xff);
        plainPadded[1] = (byte) ((safePadding.length >>> 8) & 0x03);
        System.arraycopy(safePadding, 0, plainPadded, 2, safePadding.length);
        System.arraycopy(safePayload, 0, plainPadded, 2 + safePadding.length, safePayload.length);

        byte[] xored = mt19937Xor(plainPadded, runtimeKey);
        byte[] encryptedData = modifiedChaCha(xored, runtimeKey, YsmSeeds.CACHE_DECRYPTION, true);

        ByteArrayOutputStream body = new ByteArrayOutputStream(16 + encryptedData.length + Long.BYTES);
        writeVarInt(body, 1);
        writeVarInt(body, 0);
        writeVarInt(body, 0);
        writeVarInt(body, 0);
        writeVarInt(body, format);
        writeVarInt(body, 0);
        writeVarInt(body, 0);
        writeVarInt(body, 0);
        writeVarInt(body, 0);
        body.writeBytes(encryptedData);

        byte[] withoutHash = body.toByteArray();
        byte[] out = Arrays.copyOf(withoutHash, withoutHash.length + Long.BYTES);
        LittleEndian.writeLong(out, withoutHash.length,
                CityHash64.hashWithSeed(withoutHash, YsmSeeds.CACHE_VERIFICATION)
                        ^ fileHashA
                        ^ fileHashB);
        return out;
    }

    private static byte[] obfuscateYsmZstd(byte[] standardZstd) {
        if (standardZstd == null || standardZstd.length < 5) {
            throw new IllegalArgumentException("Zstd frame is too short");
        }
        byte[] data = Arrays.copyOf(standardZstd, standardZstd.length);
        if (LittleEndian.readInt(data, 0) != 0xfd2fb528) {
            throw new IllegalArgumentException("Not a standard Zstd frame");
        }

        int frameHeaderSize = calculateZstdFrameHeaderSize(data[4] & 0xff);
        int offset = 4 + frameHeaderSize;
        while (offset + 3 <= data.length) {
            int cBlockHeader = (data[offset] & 0xff)
                    | ((data[offset + 1] & 0xff) << 8)
                    | ((data[offset + 2] & 0xff) << 16);
            int lastBlock = cBlockHeader & 1;
            int blockTypeStd = (cBlockHeader >>> 1) & 3;
            int cSize = cBlockHeader >>> 3;

            int blockDataSize = blockTypeStd == 1 ? 1 : cSize;
            int blockTypeYsm = switch (blockTypeStd) {
                case 0 -> 3;
                case 1 -> 1;
                case 2 -> 0;
                case 3 -> 2;
                default -> throw new IllegalStateException("Unknown Zstd block type");
            };

            int rawSize = cSize ^ 0xd4e9;
            data[offset] = (byte) ((lastBlock << 7) | (blockTypeYsm << 5) | ((rawSize >>> 16) & 0x1f));
            data[offset + 1] = (byte) rawSize;
            data[offset + 2] = (byte) (rawSize >>> 8);

            offset += 3 + blockDataSize;
            if (offset > data.length) {
                throw new IllegalArgumentException("Zstd block exceeds frame");
            }
            if (lastBlock == 1) {
                break;
            }
        }
        return data;
    }

    private static int calculateZstdFrameHeaderSize(int fhd) {
        boolean singleSegment = ((fhd >>> 5) & 1) == 1;

        int dictIdSize = switch (fhd & 3) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> throw new IllegalStateException("unreachable dict id size");
        };

        int fcsSize = switch ((fhd >>> 6) & 3) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new IllegalStateException("unreachable frame content size");
        };

        return 1 + (singleSegment ? 0 : 1) + dictIdSize + fcsSize;
    }

    static byte[] mt19937Xor(byte[] data, byte[] key) {
        requireKey(key);
        long seed = CityHash64.hashWithSeed(key, YsmSeeds.KEY_DERIVATION);
        Mt19937_64 mt = new Mt19937_64(seed);
        byte[] out = new byte[data.length];
        int i = 0;
        while (i < data.length) {
            long random = mt.nextLong();
            for (int j = 0; j < 8 && i < data.length; j++) {
                out[i] = (byte) (data[i] ^ (byte) (random >>> (j * 8)));
                i++;
            }
        }
        return out;
    }

    static byte[] modifiedChaCha(byte[] data, byte[] key, long seed, boolean encrypt) {
        requireKey(key);
        byte[] keyPart = Arrays.copyOfRange(key, 0, 32);
        byte[] ivPart = Arrays.copyOfRange(key, 32, 56);
        long hash = CityHash64.hashWithSeed(key, seed);

        int nextRoundSize = (int) (((hash & 0x3fL) | 0x40L) << 6);
        int offset = 0;
        XChaCha20.Context ctx = XChaCha20.keySetup(keyPart, ivPart,
                10 * (int) Long.remainderUnsigned(hash, 3) + 10);
        byte[] out = new byte[data.length];

        while (offset < data.length) {
            int count = Math.min(nextRoundSize, data.length - offset);
            byte[] block = Arrays.copyOfRange(data, offset, offset + count);
            byte[] result = XChaCha20.xor(ctx, block);
            System.arraycopy(result, 0, out, offset, count);

            byte[] plainForHash = encrypt ? block : result;
            long blockHash = CityHash64.hashWithSeed(plainForHash, seed);
            ctx.updateState(blockHash);
            nextRoundSize = (int) (((blockHash & 0x3fL) | 0x40L) << 6);
            offset += count;
        }
        return out;
    }

    private static byte[] xChaCha(byte[] data, byte[] key, int rounds) {
        byte[] keyPart = Arrays.copyOfRange(key, 0, 32);
        byte[] ivPart = Arrays.copyOfRange(key, 32, 56);
        return XChaCha20.xor(XChaCha20.keySetup(keyPart, ivPart, rounds), data);
    }

    private static void writeVarInt(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            out.write((int) (remaining & 0x7fL) | 0x80);
            remaining >>>= 7;
        }
        out.write((int) remaining);
    }

    private static void requireKey(byte[] key) {
        if (key == null || key.length != 56) {
            throw new IllegalArgumentException("YSM runtime key must be 56 bytes");
        }
    }

    private static void expect(long actual, long expected) {
        if (actual != expected) {
            throw new IllegalArgumentException("Unexpected YSM field: " + actual + ", expected " + expected);
        }
    }

    public record EncryptedPacket(byte[] payload, byte[] nextKey) {
    }

    public record DecryptedPacket(byte[] payload, byte[] nextKey) {
    }
}
