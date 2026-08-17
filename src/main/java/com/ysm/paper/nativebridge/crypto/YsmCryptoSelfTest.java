package com.ysm.paper.nativebridge.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

public final class YsmCryptoSelfTest {
    private YsmCryptoSelfTest() {
    }

    public static Result run() {
        byte[] key = new byte[56];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 3);
        }

        byte[] plain = "paper-ysm packet crypto self test".getBytes(StandardCharsets.UTF_8);
        YsmCrypto.EncryptedPacket encrypted = YsmCrypto.encryptPacket(plain, key);
        YsmCrypto.DecryptedPacket decrypted = YsmCrypto.verifyAndDecryptPacket(encrypted.payload(), key);

        boolean packetRoundTrip = Arrays.equals(plain, decrypted.payload())
                && Arrays.equals(encrypted.nextKey(), decrypted.nextKey());

        long[] hash = YsmCrypto.deriveHashFromFileName("00112233445566778899aabbccddeeff00112233", key);
        boolean filenameHashLooksValid = hash.length == 2 && (hash[0] != 0 || hash[1] != 0);

        byte[] cachedPlain = "paper-ysm cached model crypto self test".getBytes(StandardCharsets.UTF_8);
        byte[] cachedModel = YsmCrypto.encryptCachedModel(
                cachedPlain,
                15,
                0x1020304050607080L,
                0x1122334455667788L,
                key);
        byte[] cachedDecrypted = YsmCrypto.decryptCachedModel(
                cachedModel,
                0x1020304050607080L,
                0x1122334455667788L,
                key);
        boolean cacheRoundTrip = Arrays.equals(cachedPlain, cachedDecrypted);

        byte[] serverCachePayload = YsmCrypto.ysmZstdCompress(cachedPlain);
        boolean ysmZstdMagic = serverCachePayload.length >= Integer.BYTES
                && LittleEndian.readInt(serverCachePayload, 0) == YsmCrypto.YSM_ZSTD_MAGIC;
        byte[] serverCacheBody = YsmCrypto.encryptCachedModel(
                serverCachePayload,
                32,
                0x2030405060708090L,
                0x2233445566778899L,
                key);
        byte[] serverCacheDecrypted = YsmCrypto.decryptCachedModel(
                serverCacheBody,
                0x2030405060708090L,
                0x2233445566778899L,
                key);
        boolean serverCacheMagicRoundTrip = Arrays.equals(serverCachePayload, serverCacheDecrypted)
                && serverCacheDecrypted.length >= Integer.BYTES
                && LittleEndian.readInt(serverCacheDecrypted, 0) == YsmCrypto.YSM_ZSTD_MAGIC;

        byte[] clientKey = new byte[56];
        for (int i = 0; i < clientKey.length; i++) {
            clientKey[i] = (byte) (i * 11 + 5);
        }
        long[] displayHashes = YsmCrypto.calculateModelHashes(
                "Blue Archive/BA_alias|model-content-hash",
                key);
        long[] physicalHashes = YsmCrypto.calculateModelHashes(
                "32|server-cache-payload-sha256",
                key);
        byte[] sharedPhysicalCache = YsmCrypto.encryptCachedModel(
                serverCachePayload,
                32,
                physicalHashes[0],
                physicalHashes[1],
                key);
        long sharedBodyHash = YsmCrypto.cachedModelBodyVerificationHash(sharedPhysicalCache);
        byte[] aliasStream = new byte[sharedPhysicalCache.length];
        int splitOffset = sharedPhysicalCache.length - 4;
        byte[] firstChunk = Arrays.copyOfRange(sharedPhysicalCache, 0, splitOffset);
        byte[] secondChunk = Arrays.copyOfRange(sharedPhysicalCache, splitOffset, sharedPhysicalCache.length);
        YsmCrypto.patchCachedModelVerificationFooter(
                firstChunk,
                0,
                sharedPhysicalCache.length,
                sharedBodyHash,
                displayHashes[0],
                displayHashes[1]);
        YsmCrypto.patchCachedModelVerificationFooter(
                secondChunk,
                splitOffset,
                sharedPhysicalCache.length,
                sharedBodyHash,
                displayHashes[0],
                displayHashes[1]);
        System.arraycopy(firstChunk, 0, aliasStream, 0, firstChunk.length);
        System.arraycopy(secondChunk, 0, aliasStream, firstChunk.length, secondChunk.length);
        boolean aliasFooterRewrite = YsmCrypto.cachedModelIdentityMatches(
                aliasStream,
                displayHashes[0],
                displayHashes[1])
                && !YsmCrypto.cachedModelIdentityMatches(
                        aliasStream,
                        physicalHashes[0],
                        physicalHashes[1])
                && Arrays.equals(
                        serverCachePayload,
                        YsmCrypto.decryptCachedModel(
                                aliasStream,
                                displayHashes[0],
                                displayHashes[1],
                                key));

        byte[] persistedClientCache = YsmCrypto.encryptCachedModel(
                serverCachePayload,
                32,
                displayHashes[0],
                displayHashes[1],
                clientKey);
        String persistedFileName = cacheFileName(
                displayHashes[0],
                displayHashes[1],
                clientKey,
                114514);
        long[] restartedHashes = YsmCrypto.deriveHashFromFileName(persistedFileName, clientKey);
        boolean persistentClientCacheRoundTrip = Arrays.equals(displayHashes, restartedHashes)
                && YsmCrypto.cachedModelIdentityMatches(
                        persistedClientCache,
                        restartedHashes[0],
                        restartedHashes[1])
                && Arrays.equals(
                        serverCachePayload,
                        YsmCrypto.decryptCachedModel(persistedClientCache, persistedFileName, clientKey));

        return new Result(
                packetRoundTrip
                        && filenameHashLooksValid
                        && cacheRoundTrip
                        && ysmZstdMagic
                        && serverCacheMagicRoundTrip
                        && aliasFooterRewrite
                        && persistentClientCacheRoundTrip,
                packetRoundTrip,
                filenameHashLooksValid,
                cacheRoundTrip,
                ysmZstdMagic,
                serverCacheMagicRoundTrip,
                aliasFooterRewrite,
                persistentClientCacheRoundTrip);
    }

    private static String cacheFileName(long hashA, long hashB, byte[] runtimeKey, int seed) {
        byte[] buffer = new byte[20];
        buffer[0] = (byte) seed;
        buffer[1] = (byte) (seed >>> 8);
        buffer[2] = (byte) (seed >>> 16);
        buffer[3] = (byte) (seed >>> 24);
        Mt19937_64 mt = new Mt19937_64(Integer.toUnsignedLong(seed));
        LittleEndian.writeLong(buffer, 4, hashA ^ mt.nextLong());
        LittleEndian.writeLong(buffer, 12, hashB ^ mt.nextLong());
        for (int i = 0; i < buffer.length; i++) {
            buffer[i] ^= runtimeKey[i % runtimeKey.length];
        }
        return HexFormat.of().formatHex(buffer);
    }

    public record Result(
            boolean success,
            boolean packetRoundTrip,
            boolean filenameHashLooksValid,
            boolean cacheRoundTrip,
            boolean ysmZstdMagic,
            boolean serverCacheMagicRoundTrip,
            boolean aliasFooterRewrite,
            boolean persistentClientCacheRoundTrip) {
        public String describe() {
            return "success=" + success
                    + ", packetRoundTrip=" + packetRoundTrip
                    + ", filenameHash=" + filenameHashLooksValid
                    + ", cacheRoundTrip=" + cacheRoundTrip
                    + ", ysmZstdMagic=0x" + Integer.toHexString(YsmCrypto.YSM_ZSTD_MAGIC)
                    + ":" + ysmZstdMagic
                    + ", serverCacheMagicRoundTrip=" + serverCacheMagicRoundTrip
                    + ", aliasFooterRewrite=" + aliasFooterRewrite
                    + ", persistentClientCacheRoundTrip=" + persistentClientCacheRoundTrip;
        }
    }
}
