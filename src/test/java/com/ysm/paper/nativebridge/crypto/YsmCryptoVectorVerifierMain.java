package com.ysm.paper.nativebridge.crypto;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public final class YsmCryptoVectorVerifierMain {
    private YsmCryptoVectorVerifierMain() {
    }

    public static void main(String[] args) throws Exception {
        Path vectorPath = args.length >= 1
                ? Path.of(args[0])
                : Path.of("src/test/resources/ysm-crypto-vectors.json");
        Map<String, String> vectors = parseVectorFile(Files.readString(vectorPath, StandardCharsets.UTF_8));

        byte[] key = fromHex(required(vectors, "key"));
        byte[] sample = fromHex(required(vectors, "sample"));
        byte[] modifiedPlain = fromHex(required(vectors, "modifiedPlain"));
        byte[] packetPlain = fromHex(required(vectors, "packetPlain"));
        byte[] packetEncrypted = fromHex(required(vectors, "packetEncrypted"));

        checkHex("cityHash64WithSeed",
                toHex(CityHash64.hashWithSeed(sample, YsmSeeds.FILE_VERIFICATION)),
                required(vectors, "cityHash64WithSeed"));

        checkHex("mt19937Xor",
                toHex(YsmCrypto.mt19937Xor(sample, key)),
                required(vectors, "mt19937Xor"));

        byte[] keyPart = java.util.Arrays.copyOfRange(key, 0, 32);
        byte[] ivPart = java.util.Arrays.copyOfRange(key, 32, 56);
        checkHex("xchacha30",
                toHex(XChaCha20.xor(XChaCha20.keySetup(keyPart, ivPart, 30), sample)),
                required(vectors, "xchacha30"));

        checkHex("modifiedChaCha",
                toHex(YsmCrypto.modifiedChaCha(
                        fromHex(required(vectors, "modifiedEncrypted")),
                        key,
                        YsmSeeds.CACHE_DECRYPTION,
                        false)),
                toHex(modifiedPlain));

        YsmCrypto.DecryptedPacket decryptedPacket = YsmCrypto.verifyAndDecryptPacket(packetEncrypted, key);
        checkHex("packetPlain", toHex(decryptedPacket.payload()), toHex(packetPlain));
        checkHex("packetNextKey", toHex(decryptedPacket.nextKey()), required(vectors, "packetNextKey"));

        long[] fileHash = YsmCrypto.deriveHashFromFileName(required(vectors, "hashedFileName"), key);
        checkHex("fileHash0", toHex(fileHash[0]), required(vectors, "fileHash0"));
        checkHex("fileHash1", toHex(fileHash[1]), required(vectors, "fileHash1"));

        System.out.println("cppVectorVerify=true");
    }

    private static Map<String, String> parseVectorFile(String json) {
        Map<String, String> result = new LinkedHashMap<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"")
                .matcher(json);
        while (matcher.find()) {
            result.put(matcher.group(1), matcher.group(2));
        }
        return result;
    }

    private static String required(Map<String, String> vectors, String key) {
        String value = vectors.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing vector field: " + key);
        }
        return value;
    }

    private static void checkHex(String name, String actual, String expected) {
        if (!actual.equalsIgnoreCase(expected)) {
            throw new AssertionError(name + " mismatch\nexpected=" + expected + "\nactual=" + actual);
        }
    }

    private static byte[] fromHex(String hex) {
        if ((hex.length() & 1) != 0) {
            throw new IllegalArgumentException("Odd hex length");
        }
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(hex.charAt(i * 2), 16);
            int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("Invalid hex input");
            }
            out[i] = (byte) ((hi << 4) | lo);
        }
        return out;
    }

    private static String toHex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            out.append(Character.forDigit(value & 0x0f, 16));
        }
        return out.toString();
    }

    private static String toHex(long value) {
        return String.format("%016x", value);
    }
}
