package com.ysm.paper.nativebridge.crypto;

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
        if (cachedModel.length < 16) {
            throw new IllegalArgumentException("Cached model is too short");
        }

        int hashOffset = cachedModel.length - 8;
        long actual = CityHash64.hashWithSeed(cachedModel, 0, hashOffset, YsmSeeds.CACHE_VERIFICATION)
                ^ fileHash[0]
                ^ fileHash[1];
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
