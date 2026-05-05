package com.ysm.paper.nativebridge.crypto;

import io.airlift.compress.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class YsmNativeCacheFixtureAnalyzerMain {
    private static final HexFormat HEX = HexFormat.of();
    private static final int KEY_BYTES = 56;
    private static final int PREVIEW_BYTES = 48;

    private YsmNativeCacheFixtureAnalyzerMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        Path fixture = options.fixture();
        byte[] type3Body = Files.readAllBytes(fixture.resolve("type3-body.bin"));
        List<CacheMapEntry> cacheMap = readCacheMap(fixture.resolve("cache-map.tsv"));
        int entryStart = findFirstTokenOffset(type3Body, KEY_BYTES * 2, cacheMap);
        if (entryStart < 0) {
            throw new IllegalArgumentException("Could not find first cache token in type3-body.bin");
        }

        byte[] serverCacheKey = Arrays.copyOfRange(type3Body, 0, KEY_BYTES);
        byte[] clientCacheKey = Arrays.copyOfRange(type3Body, KEY_BYTES, KEY_BYTES * 2);
        byte[] prelude = Arrays.copyOfRange(type3Body, KEY_BYTES * 2, entryStart);

        System.out.println("fixture=" + fixture.toAbsolutePath());
        System.out.println("type3Bytes=" + type3Body.length
                + ", serverCacheKey=" + keyPreview(serverCacheKey)
                + ", clientCacheKey=" + keyPreview(clientCacheKey));
        System.out.println("entryStart=" + entryStart
                + ", preludeBytes=" + prelude.length
                + ", preludeHex=" + HEX.formatHex(prelude)
                + ", preludeVarints=" + describeVarInts(prelude));

        List<ManifestEntry> manifestEntries = parseManifestEntries(type3Body, entryStart, cacheMap);
        int tailOffset = manifestEntries.isEmpty()
                ? entryStart
                : manifestEntries.get(manifestEntries.size() - 1).nextOffset();
        byte[] tail = Arrays.copyOfRange(type3Body, tailOffset, type3Body.length);
        System.out.println("manifestEntries=" + manifestEntries.size()
                + ", tailOffset=" + tailOffset
                + ", tailBytes=" + tail.length
                + ", tailLeadingVarints=" + describeVarInts(Arrays.copyOf(tail, Math.min(tail.length, 64))));
        System.out.println("tailStrings=" + describeStrings(tail, 12));

        ArrayList<PlainResult> plainResults = new ArrayList<>();
        for (ManifestEntry manifestEntry : manifestEntries) {
            if (!options.matches(manifestEntry.cacheEntry().name())) {
                continue;
            }
            addIfPresent(plainResults, analyzeCacheEntry(fixture, manifestEntry, "server", serverCacheKey));
            addIfPresent(plainResults, analyzeCacheEntry(fixture, manifestEntry, "client", clientCacheKey));
        }
        if (options.localModel() != null) {
            analyzeLocalModel(options.localModel(), plainResults);
        }
    }

    private static List<ManifestEntry> parseManifestEntries(
            byte[] type3Body,
            int entryStart,
            List<CacheMapEntry> cacheMap) {
        ArrayList<ManifestEntry> entries = new ArrayList<>();
        int offset = entryStart;
        while (offset < type3Body.length) {
            CacheMapEntry cacheEntry = matchToken(type3Body, offset, cacheMap);
            if (cacheEntry == null) {
                break;
            }
            int entryOffset = offset;
            Offset entryOffsetRef = Offset.of(offset + cacheEntry.token().length);
            String name = readString(type3Body, entryOffsetRef);
            long flagA = readVarInt(type3Body, entryOffsetRef);
            long flagB = readVarInt(type3Body, entryOffsetRef);
            long format = readVarInt(type3Body, entryOffsetRef);
            offset = entryOffsetRef.value();
            ManifestEntry manifestEntry = new ManifestEntry(
                    entryOffset,
                    offset,
                    cacheEntry,
                    name,
                    flagA,
                    flagB,
                    format);
            entries.add(manifestEntry);
            System.out.println("entry[" + entries.size() + "] offset=" + entryOffset
                    + ", tokenBytes=" + cacheEntry.token().length
                    + ", name=" + quote(name)
                    + ", mapName=" + quote(cacheEntry.name())
                    + ", flags=" + flagA + "/" + flagB
                    + ", format=" + format
                    + ", cacheBytes=" + cacheEntry.bytes());
        }
        return entries;
    }

    private static PlainResult analyzeCacheEntry(
            Path fixture,
            ManifestEntry manifestEntry,
            String keyName,
            byte[] cacheKey) throws IOException {
        CacheMapEntry cacheEntry = manifestEntry.cacheEntry();
        Path cacheFile = fixture.resolve(cacheEntry.file());
        byte[] cacheBytes = Files.readAllBytes(cacheFile);
        long[] hashes = decodeTokenHashes(cacheEntry.token());

        System.out.println("cacheEntry=" + quote(cacheEntry.name())
                + ", token=" + HEX.formatHex(cacheEntry.token())
                + ", hashA=" + Long.toUnsignedString(hashes[0])
                + ", hashB=" + Long.toUnsignedString(hashes[1])
                + ", fileBytes=" + cacheBytes.length);
        try {
            byte[] plain = YsmCrypto.decryptCachedModel(cacheBytes, hashes[0], hashes[1], cacheKey);
            System.out.println("cachePlain key=" + keyName
                    + ", bytes=" + plain.length
                    + ", sha256=" + sha256Hex(plain)
                    + ", firstHex=" + previewHex(plain)
                    + ", zstd=" + describeZstd(plain)
                    + ", headerlessV3=" + describeHeaderlessV3(plain));
            return new PlainResult(cacheEntry.name(), keyName, plain);
        } catch (RuntimeException ex) {
            System.out.println("cachePlain error=" + ex.getMessage());
            return null;
        }
    }

    private static void analyzeLocalModel(Path model, List<PlainResult> plainResults) throws IOException {
        byte[] bytes = Files.readAllBytes(model);
        int headerEnd = findNull(bytes, 0);
        if (headerEnd < 0) {
            throw new IllegalArgumentException("Local model header terminator not found: " + model);
        }
        int bodyOffset = headerEnd + 1;
        int encryptedBodyOffset = bodyOffset + Integer.BYTES;
        int hashOffset = bytes.length - Long.BYTES;
        int runtimeKeyOffset = hashOffset - KEY_BYTES;
        byte[] runtimeKey = Arrays.copyOfRange(bytes, runtimeKeyOffset, hashOffset);
        byte[] encryptedBody = Arrays.copyOfRange(bytes, encryptedBodyOffset, runtimeKeyOffset);
        byte[] chacha = YsmCrypto.modifiedChaCha(encryptedBody, runtimeKey, YsmSeeds.RES_VERIFICATION, false);
        byte[] xored = YsmCrypto.mt19937Xor(chacha, runtimeKey);
        int padding = xored.length >= 2
                ? (((xored[0] & 0xff) | ((xored[1] & 0xff) << 8)) & 0x3ff)
                : -1;
        int payloadOffset = padding >= 0 ? 2 + padding : xored.length;
        byte[] rawYsmZstd = payloadOffset <= xored.length
                ? Arrays.copyOfRange(xored, payloadOffset, xored.length)
                : new byte[0];
        YsmArchiveProbe.ExtractedV3Archive extracted = YsmArchiveProbe.extractV3(model);

        System.out.println("localModel=" + model.toAbsolutePath()
                + ", sourceBytes=" + bytes.length
                + ", headerBytes=" + bodyOffset
                + ", crypto=" + LittleEndian.readInt(bytes, bodyOffset)
                + ", encryptedBodyBytes=" + encryptedBody.length
                + ", runtimeKey=" + keyPreview(runtimeKey)
                + ", padding=" + padding);
        printComponent("local.cryptoBodyPlusTail",
                Arrays.copyOfRange(bytes, bodyOffset, bytes.length),
                plainResults);
        printComponent("local.encryptedBodyPlusTail",
                Arrays.copyOfRange(bytes, encryptedBodyOffset, bytes.length),
                plainResults);
        printComponent("local.encryptedBody",
                encryptedBody,
                plainResults);
        printComponent("local.decryptedContainer",
                xored,
                plainResults);
        printComponent("local.rawYsmZstd",
                rawYsmZstd,
                plainResults);
        printComponent("local.washedZstd",
                extracted.washedZstd(),
                plainResults);
        printComponent("local.decompressed",
                extracted.decompressed(),
                plainResults);
    }

    private static List<CacheMapEntry> readCacheMap(Path path) throws IOException {
        List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        ArrayList<CacheMapEntry> entries = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("tokenHex")) {
                continue;
            }
            String[] parts = line.split("\t");
            if (parts.length < 4) {
                continue;
            }
            entries.add(new CacheMapEntry(
                    hex(parts[0]),
                    Path.of(parts[1]),
                    parts[2],
                    Long.parseLong(parts[3])));
        }
        entries.sort((left, right) -> Integer.compare(right.token().length, left.token().length));
        return List.copyOf(entries);
    }

    private static int findFirstTokenOffset(byte[] data, int start, List<CacheMapEntry> cacheMap) {
        for (int offset = start; offset < data.length; offset++) {
            if (matchToken(data, offset, cacheMap) != null) {
                return offset;
            }
        }
        return -1;
    }

    private static int findNull(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static CacheMapEntry matchToken(byte[] data, int offset, List<CacheMapEntry> cacheMap) {
        for (CacheMapEntry entry : cacheMap) {
            byte[] token = entry.token();
            if (offset + token.length > data.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < token.length; i++) {
                if (data[offset + i] != token[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return entry;
            }
        }
        return null;
    }

    private static byte[] hex(String value) {
        return HEX.parseHex(value.trim());
    }

    private static String readString(byte[] data, Offset offset) {
        int length = Math.toIntExact(readVarInt(data, offset));
        if (length < 0 || offset.value() + length > data.length) {
            throw new IllegalArgumentException("String exceeds buffer");
        }
        String value = new String(data, offset.value(), length, StandardCharsets.UTF_8);
        offset.add(length);
        return value;
    }

    private static long[] decodeTokenHashes(byte[] token) {
        Offset offset = Offset.of(0);
        long hashA = readVarInt(token, offset);
        long hashB = readVarInt(token, offset);
        if (offset.value() != token.length) {
            throw new IllegalArgumentException("Token has trailing bytes: " + (token.length - offset.value()));
        }
        return new long[] {hashA, hashB};
    }

    private static long readVarInt(byte[] data, Offset offset) {
        long value = 0;
        int shift = 0;
        while (shift < 64) {
            if (offset.value() >= data.length) {
                throw new IllegalArgumentException("Unexpected end of VarInt");
            }
            int b = data[offset.value()] & 0xff;
            offset.add(1);
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt too large");
    }

    private static String describeVarInts(byte[] data) {
        ArrayList<String> values = new ArrayList<>();
        Offset offset = Offset.of(0);
        while (offset.value() < data.length && values.size() < 8) {
            try {
                values.add(Long.toUnsignedString(readVarInt(data, offset)));
            } catch (RuntimeException ex) {
                values.add("error@" + offset.value() + ":" + ex.getMessage());
                break;
            }
        }
        if (offset.value() < data.length) {
            values.add("+" + (data.length - offset.value()) + "b");
        }
        return values.toString();
    }

    private static String describeStrings(byte[] data, int limit) {
        String text = new String(data, StandardCharsets.UTF_8);
        ArrayList<String> strings = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isUsefulChar(ch)) {
                current.append(ch);
            } else {
                addString(strings, current, limit);
                if (strings.size() >= limit) {
                    break;
                }
            }
        }
        addString(strings, current, limit);
        return strings.toString();
    }

    private static boolean isUsefulChar(char ch) {
        return ch >= 0x20 && ch != 0x7f && !Character.isISOControl(ch);
    }

    private static void addString(List<String> strings, StringBuilder current, int limit) {
        if (current.length() >= 3 && strings.size() < limit) {
            strings.add(quote(current.toString()));
        }
        current.setLength(0);
    }

    private static String describeZstd(byte[] data) {
        if (data.length < 4 || LittleEndian.readInt(data, 0) != 0xfd2fb528) {
            return "no";
        }
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(data))) {
            byte[] decompressed = input.readAllBytes();
            int format = decompressed.length >= 4 ? LittleEndian.readInt(decompressed, 0) : -1;
            return "yes,decompressed=" + decompressed.length
                    + ",format=" + format
                    + ",plainSha256=" + sha256Hex(decompressed)
                    + ",plainFirstHex=" + previewHex(decompressed);
        } catch (Exception ex) {
            return "yes,decompressError=" + ex.getMessage();
        }
    }

    private static String describeHeaderlessV3(byte[] data) {
        if (data.length < 128) {
            return "too-short";
        }
        ArrayList<String> variants = new ArrayList<>();
        variants.add(describeHeaderlessV3Variant(data, 0));
        variants.add(describeHeaderlessV3Variant(data, Integer.BYTES));
        return variants.toString();
    }

    private static String describeHeaderlessV3Variant(byte[] data, int encryptedBodyOffset) {
        int hashOffset = data.length - Long.BYTES;
        int runtimeKeyOffset = hashOffset - KEY_BYTES;
        if (encryptedBodyOffset < 0 || encryptedBodyOffset >= runtimeKeyOffset) {
            return "offset" + encryptedBodyOffset + ":invalid";
        }

        long expectedHash = LittleEndian.readLong(data, hashOffset);
        long actualHash = CityHash64.hashWithSeed(data, 0, hashOffset, YsmSeeds.FILE_VERIFICATION);
        byte[] runtimeKey = Arrays.copyOfRange(data, runtimeKeyOffset, hashOffset);
        byte[] encryptedBody = Arrays.copyOfRange(data, encryptedBodyOffset, runtimeKeyOffset);
        try {
            byte[] chacha = YsmCrypto.modifiedChaCha(encryptedBody, runtimeKey, YsmSeeds.RES_VERIFICATION, false);
            byte[] xored = YsmCrypto.mt19937Xor(chacha, runtimeKey);
            if (xored.length < 2) {
                return "offset" + encryptedBodyOffset + ":plain-too-short";
            }
            int padding = ((xored[0] & 0xff) | ((xored[1] & 0xff) << 8)) & 0x3ff;
            int payloadOffset = 2 + padding;
            boolean payloadOffsetOk = payloadOffset <= xored.length;
            byte[] payload = payloadOffsetOk ? Arrays.copyOfRange(xored, payloadOffset, xored.length) : new byte[0];
            return "offset" + encryptedBodyOffset
                    + "{hashOk=" + (actualHash == expectedHash)
                    + ", padding=" + padding
                    + ", payloadBytes=" + payload.length
                    + ", payloadFirstHex=" + previewHex(payload)
                    + ", payloadZstd=" + describeZstd(payload)
                    + "}";
        } catch (RuntimeException ex) {
            return "offset" + encryptedBodyOffset + ":error=" + ex.getMessage();
        }
    }

    private static String keyPreview(byte[] key) {
        return HEX.formatHex(Arrays.copyOf(key, Math.min(8, key.length)));
    }

    private static String previewHex(byte[] bytes) {
        return HEX.formatHex(Arrays.copyOf(bytes, Math.min(PREVIEW_BYTES, bytes.length)));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static void printComponent(String label, byte[] bytes, List<PlainResult> plainResults) {
        String sha256 = sha256Hex(bytes);
        System.out.println(label
                + " bytes=" + bytes.length
                + ", sha256=" + sha256
                + ", firstHex=" + previewHex(bytes)
                + ", matches=" + matchingPlainResults(sha256, plainResults));
    }

    private static String matchingPlainResults(String sha256, List<PlainResult> plainResults) {
        ArrayList<String> matches = new ArrayList<>();
        for (PlainResult result : plainResults) {
            if (sha256.equals(result.sha256())) {
                matches.add(result.name() + "/" + result.keyName());
            }
        }
        return matches.toString();
    }

    private static void addIfPresent(List<PlainResult> results, PlainResult result) {
        if (result != null) {
            results.add(result);
        }
    }

    private static String quote(String value) {
        return "\"" + value + "\"";
    }

    private record CacheMapEntry(byte[] token, Path file, String name, long bytes) {
    }

    private record ManifestEntry(
            int offset,
            int nextOffset,
            CacheMapEntry cacheEntry,
            String manifestName,
            long flagA,
            long flagB,
            long format) {
    }

    private record PlainResult(String name, String keyName, byte[] bytes) {
        String sha256() {
            return sha256Hex(bytes);
        }
    }

    private record Options(Path fixture, String filter, Path localModel) {
        private static Options parse(String[] args) {
            Path fixture = args.length > 0
                    ? Path.of(args[0])
                    : Path.of("test-server/direct-paper/plugins/PaperYSM/captures/native-cache/freesia-latest");
            String filter = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : ".ysm";
            Path localModel = args.length > 2 ? Path.of(args[2]) : null;
            return new Options(fixture, filter, localModel);
        }

        private boolean matches(String name) {
            return filter == null
                    || filter.isBlank()
                    || name.toLowerCase(Locale.ROOT).contains(filter);
        }
    }

    private static final class Offset {
        private int value;

        private Offset(int value) {
            this.value = value;
        }

        static Offset of(int value) {
            return new Offset(value);
        }

        int value() {
            return value;
        }

        void add(int amount) {
            value += amount;
        }
    }

}
