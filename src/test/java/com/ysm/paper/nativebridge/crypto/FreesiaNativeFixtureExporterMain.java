package com.ysm.paper.nativebridge.crypto;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FreesiaNativeFixtureExporterMain {
    private static final Pattern FREESIA_S2C_HEX_PATTERN =
            Pattern.compile("S2C Packet Data \\((\\d+) bytes\\):\\s*([0-9A-Fa-f ]+)");
    private static final Pattern FREESIA_C2S_HEX_PATTERN =
            Pattern.compile("YSM Packet from .* \\(len=(\\d+)\\):\\s*([0-9A-Fa-f ]+)");
    private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("[0-9A-Fa-f]{2}");
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] BOOT_KEY = HEX.parseHex(
            "0fc77ef3f4b8353aa2ba7fd31779468e"
                    + "6542d0988a9bb019804f8156366a1262"
                    + "be0ee5ad4701d45ee4ebfb36cb474298"
                    + "f9e57a5c3cdb2c76");
    private static final int KEY_BYTES = 56;

    private FreesiaNativeFixtureExporterMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<CaptureRecord> records = loadRecords(options.captureDir());
        List<CaptureRecord> c2sRecords = options.c2sDir() == null
                ? records
                : loadRecords(options.c2sDir());
        if (records.isEmpty()) {
            throw new IllegalArgumentException("No YSM capture records found under " + options.captureDir());
        }

        List<CaptureRecord> s2cId1 = records.stream()
                .filter(record -> "s2c".equals(record.direction()))
                .filter(record -> record.id() == 0x01)
                .sorted(Comparator.comparingInt(CaptureRecord::seq))
                .toList();
        List<CaptureRecord> c2sId2 = c2sRecords.stream()
                .filter(record -> "c2s".equals(record.direction()))
                .filter(record -> record.id() == 0x02)
                .sorted(Comparator.comparingInt(CaptureRecord::seq))
                .toList();

        if (s2cId1.isEmpty()) {
            throw new IllegalArgumentException("No S2C id=1 native packets found. ysm-sniffer only catches type3 when Freesia sends worker sync through Velocity.");
        }
        if (c2sId2.isEmpty()) {
            throw new IllegalArgumentException("No C2S id=2 native packets found. Join through Velocity with a YSM client and trigger worker cache sync.");
        }

        NativePacket type1 = findFirstDecoded(s2cId1, BOOT_KEY, 1, 0);
        byte[] s2cKey = Arrays.copyOf(type1.decoded().body(), KEY_BYTES);
        NativePacket type2 = findFirstDecoded(c2sId2, s2cKey, 2, 0);
        Cursor type2Body = new Cursor(type2.decoded().body());
        long type2Unknown = type2Body.readVarLong();
        byte[] c2sKey = type2Body.readBytes(KEY_BYTES);
        NativePacket type3 = findFirstDecoded(s2cId1, c2sKey, 3, type1.record().seq());
        List<Entry> entries = parseEntries(type3.decoded().body(), findEntryStart(type3.decoded().body()));
        if (entries.isEmpty()) {
            throw new IllegalArgumentException("Decoded type3 but found no manifest entries: " + type3.record().file());
        }

        NativePacket type4 = findFirstDecoded(c2sId2, s2cKey, 4, type2.record().seq());
        String type4Warning = "";
        List<Entry> requested;
        try {
            requested = parseType4(type4.decoded().body(), entries);
        } catch (RuntimeException ex) {
            requested = List.of();
            type4Warning = ex.getMessage();
        }
        List<Entry> chunkMatches = requested.isEmpty() ? entries : requested;
        Map<String, Group> groups = new LinkedHashMap<>();
        int type5Packets = 0;
        int skippedType5 = 0;
        int unmatchedType5 = 0;
        for (CaptureRecord record : s2cId1) {
            if (record.seq() <= type3.record().seq()) {
                continue;
            }
            Decoded decoded;
            try {
                decoded = decode(stripEnvelope(record.payload()), s2cKey);
            } catch (RuntimeException ex) {
                skippedType5++;
                continue;
            }
            if (decoded.type() != 5) {
                skippedType5++;
                continue;
            }
            type5Packets++;
            Chunk chunk;
            try {
                chunk = parseType5(decoded.body(), chunkMatches, record.file().getFileName().toString());
            } catch (RuntimeException ex) {
                unmatchedType5++;
                continue;
            }
            groups.computeIfAbsent(HEX.formatHex(chunk.entry().token()), ignored -> new Group(chunk.entry()))
                    .add(chunk);
        }

        Files.createDirectories(options.outDir());
        Path serverCacheDir = options.outDir().resolve("server-cache");
        Files.createDirectories(serverCacheDir);
        Files.write(options.outDir().resolve("type3-body.bin"), type3.decoded().body());
        Files.writeString(
                options.outDir().resolve("type1-padding.txt"),
                Integer.toString(type1.decoded().padding()),
                StandardCharsets.UTF_8);
        Files.writeString(
                options.outDir().resolve("type3-padding.txt"),
                Integer.toString(type3.decoded().padding()),
                StandardCharsets.UTF_8);

        ArrayList<String> cacheMap = new ArrayList<>();
        ArrayList<String> report = new ArrayList<>();
        cacheMap.add("tokenHex\tfile\tname\tbytes");
        report.add("index\ttokenHex\tname\trequested\tchunks\ttotalBytes\treceivedBytes\tgaps\tfile");
        int completeGroups = 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            Group group = groups.get(HEX.formatHex(entry.token()));
            boolean wasRequested = requested.stream().anyMatch(item -> Arrays.equals(item.token(), entry.token()));
            if (group == null) {
                report.add(i + "\t"
                        + HEX.formatHex(entry.token()) + "\t"
                        + entry.name() + "\t"
                        + wasRequested + "\t0\t0\t0\t0\t");
                continue;
            }
            GroupResult result = group.assemble();
            String fileName = safeName(entry.name()) + "--" + HEX.formatHex(entry.token()) + ".bin";
            Path output = serverCacheDir.resolve(fileName);
            if (result.complete()) {
                Files.write(output, result.bytes());
                completeGroups++;
                cacheMap.add(HEX.formatHex(entry.token()) + "\t"
                        + "server-cache/" + fileName + "\t"
                        + entry.name() + "\t"
                        + result.bytes().length);
            }
            report.add(i + "\t"
                    + HEX.formatHex(entry.token()) + "\t"
                    + entry.name() + "\t"
                    + wasRequested + "\t"
                    + group.chunks().size() + "\t"
                    + result.totalBytes() + "\t"
                    + result.receivedBytes() + "\t"
                    + result.gaps() + "\t"
                    + (result.complete() ? "server-cache/" + fileName : ""));
        }
        Files.write(options.outDir().resolve("cache-map.tsv"), cacheMap, StandardCharsets.UTF_8);
        Files.write(options.outDir().resolve("export-report.tsv"), report, StandardCharsets.UTF_8);

        System.out.println("captureDir=" + options.captureDir().toAbsolutePath());
        if (options.c2sDir() != null) {
            System.out.println("c2sDir=" + options.c2sDir().toAbsolutePath());
        }
        System.out.println("outDir=" + options.outDir().toAbsolutePath());
        System.out.println("records=" + records.size()
                + ", c2sRecords=" + c2sRecords.size()
                + ", s2cId1=" + s2cId1.size()
                + ", c2sId2=" + c2sId2.size());
        System.out.println("type1=" + type1.record().file().getFileName()
                + ", padding=" + type1.decoded().padding()
                + ", s2cKey=" + keyPreview(s2cKey));
        System.out.println("type2=" + type2.record().file().getFileName()
                + ", unknown=" + type2Unknown
                + ", c2sKey=" + keyPreview(c2sKey));
        System.out.println("type3=" + type3.record().file().getFileName()
                + ", bodyBytes=" + type3.decoded().body().length
                + ", padding=" + type3.decoded().padding()
                + ", entries=" + entries.size());
        System.out.println("type4=" + type4.record().file().getFileName()
                + ", requested=" + requested.size());
        if (!type4Warning.isBlank()) {
            System.out.println("warning=type4 request list was not fully parsed: " + type4Warning);
        }
        System.out.println("type5Packets=" + type5Packets
                + ", skippedAfterType4=" + skippedType5
                + ", unmatchedType5=" + unmatchedType5
                + ", completeCacheEntries=" + completeGroups
                + ", cacheMapRows=" + (cacheMap.size() - 1));
        if (completeGroups != entries.size()) {
            System.out.println("warning=not every type3 entry has complete type5 chunks; use a clean client cache capture for a full replay fixture");
        }
        System.out.println("paperCommand=/ysm source default " + options.outDir().getFileName());
        System.out.println("paperCommand=/ysm sync");
    }

    private static List<CaptureRecord> loadRecords(Path captureDir) throws IOException {
        if (Files.isRegularFile(captureDir)) {
            return loadFreesiaDebugLog(captureDir);
        }
        Path index = captureDir.resolve("index.tsv");
        if (Files.exists(index)) {
            return loadIndexedRecords(captureDir, index);
        }
        ArrayList<CaptureRecord> records = new ArrayList<>();
        int seq = 0;
        try (var stream = Files.list(captureDir)) {
            for (Path path : stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                String fileName = path.getFileName().toString();
                String direction = fileName.contains("-s2c-") ? "s2c" : fileName.contains("-c2s-") ? "c2s" : "unknown";
                byte[] payload = Files.readAllBytes(path);
                records.add(new CaptureRecord(++seq, direction, payload.length == 0 ? -1 : payload[0] & 0xff, path, payload));
            }
        }
        return records;
    }

    private static List<CaptureRecord> loadFreesiaDebugLog(Path log) throws IOException {
        ArrayList<CaptureRecord> records = new ArrayList<>();
        int seq = 0;
        try (BufferedReader reader = Files.newBufferedReader(log, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Matcher s2c = FREESIA_S2C_HEX_PATTERN.matcher(line);
                if (s2c.find()) {
                    byte[] payload = parseHexBytes(s2c.group(2), Integer.parseInt(s2c.group(1)));
                    if (payload.length > 0) {
                        records.add(new CaptureRecord(++seq, "s2c", payload[0] & 0xff, log, payload));
                    }
                    continue;
                }
                Matcher c2s = FREESIA_C2S_HEX_PATTERN.matcher(line);
                if (c2s.find()) {
                    byte[] payload = parseHexBytes(c2s.group(2), Integer.parseInt(c2s.group(1)));
                    if (payload.length > 0) {
                        records.add(new CaptureRecord(++seq, "c2s", payload[0] & 0xff, log, payload));
                    }
                }
            }
        }
        return List.copyOf(records);
    }

    private static List<CaptureRecord> loadIndexedRecords(Path captureDir, Path index) throws IOException {
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }
        String[] header = lines.get(0).split("\t", -1);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            columns.put(header[i], i);
        }
        ArrayList<CaptureRecord> records = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            String fileName = column(parts, columns, "file");
            if (fileName.isBlank()) {
                continue;
            }
            Path file = captureDir.resolve(fileName);
            if (!Files.exists(file)) {
                continue;
            }
            byte[] payload = Files.readAllBytes(file);
            if (payload.length == 0) {
                continue;
            }
            String channel = column(parts, columns, "channel");
            if (!channel.isBlank() && !channel.startsWith("yes_steve_model:")) {
                continue;
            }
            int seq = parseInt(column(parts, columns, "seq"), i);
            String direction = column(parts, columns, "direction");
            if (direction.isBlank()) {
                direction = inferDirection(column(parts, columns, "source"), column(parts, columns, "target"), fileName);
            }
            records.add(new CaptureRecord(seq, direction, payload[0] & 0xff, file, payload));
        }
        records.sort(Comparator.comparingInt(CaptureRecord::seq));
        return List.copyOf(records);
    }

    private static String column(String[] parts, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        if (index == null || index < 0 || index >= parts.length) {
            return "";
        }
        return parts[index];
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static String inferDirection(String source, String target, String fileName) {
        if (source.contains("VelocityServerConnection") && target.contains("ConnectedPlayer")) {
            return "s2c";
        }
        if (source.contains("ConnectedPlayer") && target.contains("VelocityServerConnection")) {
            return "c2s";
        }
        if (fileName.contains("-s2c-")) {
            return "s2c";
        }
        if (fileName.contains("-c2s-")) {
            return "c2s";
        }
        return "unknown";
    }

    private static NativePacket findFirstDecoded(
            List<CaptureRecord> records,
            byte[] key,
            int expectedType,
            int afterSeq) {
        for (CaptureRecord record : records) {
            if (record.seq() <= afterSeq) {
                continue;
            }
            try {
                Decoded decoded = decode(stripEnvelope(record.payload()), key);
                if (decoded.type() == expectedType) {
                    return new NativePacket(record, decoded);
                }
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalArgumentException("Could not decode native type " + expectedType + " after seq " + afterSeq);
    }

    private static byte[] stripEnvelope(byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalArgumentException("payload too short");
        }
        return Arrays.copyOfRange(payload, 1, payload.length);
    }

    private static Decoded decode(byte[] packet, byte[] key) {
        int hashOffset = packet.length - Long.BYTES;
        if (hashOffset <= 0) {
            throw new IllegalArgumentException("packet too short");
        }
        long expected = LittleEndian.readLong(packet, hashOffset);
        long actual = CityHash64.hashWithSeed(packet, 0, hashOffset, YsmSeeds.PACKET_VERIFICATION);
        if (actual != expected) {
            throw new IllegalArgumentException("packet hash mismatch");
        }
        byte[] encrypted = Arrays.copyOf(packet, hashOffset);
        byte[] plain = xChaCha(YsmCrypto.mt19937Xor(encrypted, key), key);
        Cursor cursor = new Cursor(plain);
        int padding = cursor.readUnsignedShortLE() & 0x7f;
        cursor.skip(padding);
        int type = cursor.readVarIntAsInt();
        return new Decoded(type, padding, cursor.remainingBytes());
    }

    private static byte[] xChaCha(byte[] data, byte[] key) {
        return XChaCha20.xor(
                XChaCha20.keySetup(Arrays.copyOfRange(key, 0, 32), Arrays.copyOfRange(key, 32, 56), 30),
                data);
    }

    private static int findEntryStart(byte[] body) {
        for (int offset = KEY_BYTES * 2; offset < Math.min(body.length, KEY_BYTES * 2 + 96); offset++) {
            try {
                parseEntryAt(body, offset);
                return offset;
            } catch (RuntimeException ignored) {
            }
        }
        throw new IllegalArgumentException("Could not find type3 manifest entry start");
    }

    private static List<Entry> parseEntries(byte[] body, int start) {
        ArrayList<Entry> entries = new ArrayList<>();
        int pos = start;
        while (pos < body.length) {
            Entry entry;
            try {
                entry = parseEntryAt(body, pos).withIndex(entries.size());
            } catch (RuntimeException ex) {
                break;
            }
            entries.add(entry);
            pos = entry.end();
        }
        return List.copyOf(entries);
    }

    private static Entry parseEntryAt(byte[] body, int pos) {
        Entry best = null;
        for (int tokenEnd = pos + 1; tokenEnd <= Math.min(pos + 40, body.length); tokenEnd++) {
            try {
                Cursor cursor = new Cursor(body, tokenEnd);
                String name = cursor.readString();
                long flagA = cursor.readVarLong();
                long flagB = cursor.readVarLong();
                long format = cursor.readVarLong();
                int tokenLen = tokenEnd - pos;
                if (tokenLen < 8 || tokenLen > 40 || !plausibleName(name) || flagA > 4 || flagB > 4 || format > 65535) {
                    continue;
                }
                Entry candidate = new Entry(
                        -1,
                        Arrays.copyOfRange(body, pos, tokenEnd),
                        name,
                        flagA,
                        flagB,
                        format,
                        pos,
                        cursor.offset());
                if (best == null || candidate.end() < best.end()) {
                    best = candidate;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (best == null) {
            throw new IllegalArgumentException("Could not parse type3 entry at " + pos);
        }
        return best;
    }

    private static boolean plausibleName(String value) {
        if (value.isEmpty() || value.length() > 200) {
            return false;
        }
        return value.contains("/") || value.endsWith(".ysm") || value.contains("default");
    }

    private static List<Entry> parseType4(byte[] body, List<Entry> entries) {
        Cursor cursor = new Cursor(body);
        int count = cursor.readVarIntAsInt();
        ArrayList<Entry> requested = new ArrayList<>();
        for (int i = 0; i < count && cursor.remaining() > 0; i++) {
            Entry match = matchEntry(body, cursor.offset(), entries);
            if (match == null) {
                throw new IllegalArgumentException("Unknown type4 token at offset " + cursor.offset());
            }
            requested.add(match);
            cursor.skip(match.token().length);
        }
        if (cursor.remaining() != 0) {
            throw new IllegalArgumentException("Trailing bytes in type4 request: " + cursor.remaining());
        }
        return List.copyOf(requested);
    }

    private static Chunk parseType5(byte[] body, List<Entry> entries, String fileName) {
        Entry match = matchEntry(body, 0, entries);
        if (match == null) {
            throw new IllegalArgumentException("type5 token not found: " + fileName);
        }
        Cursor cursor = new Cursor(body, match.token().length);
        long total = cursor.readVarLong();
        long offset = cursor.readVarLong();
        long length = cursor.readVarLong();
        if (length > Integer.MAX_VALUE || length != cursor.remaining()) {
            throw new IllegalArgumentException("Bad type5 length in " + fileName);
        }
        return new Chunk(match, total, offset, cursor.readBytes((int) length), fileName);
    }

    private static Entry matchEntry(byte[] body, int offset, List<Entry> entries) {
        Entry best = null;
        for (Entry entry : entries) {
            byte[] token = entry.token();
            if (offset + token.length > body.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < token.length; i++) {
                if (body[offset + i] != token[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches && (best == null || token.length > best.token().length)) {
                best = entry;
            }
        }
        return best;
    }

    private static String safeName(String name) {
        String cleaned = name.replace('\\', '/')
                .replaceAll("[<>:\"/\\\\|?*\\x00-\\x1F]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_");
        if (cleaned.isBlank()) {
            cleaned = "model";
        }
        return cleaned.length() > 96 ? cleaned.substring(0, 96) : cleaned;
    }

    private static byte[] parseHexBytes(String text, int expectedBytes) {
        Matcher matcher = HEX_BYTE_PATTERN.matcher(text);
        byte[] bytes = new byte[expectedBytes];
        int count = 0;
        while (matcher.find()) {
            if (count == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length + Math.max(16, bytes.length));
            }
            bytes[count++] = (byte) Integer.parseInt(matcher.group(), 16);
        }
        return count == expectedBytes ? bytes : new byte[0];
    }

    private static String keyPreview(byte[] key) {
        return HEX.formatHex(key, 0, Math.min(8, key.length));
    }

    private record Options(Path captureDir, Path outDir, Path c2sDir) {
        static Options parse(String[] args) {
            Path captureDir = null;
            Path outDir = null;
            Path c2sDir = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--capture-dir".equals(arg)) {
                    captureDir = Path.of(args[++i]);
                } else if ("--out".equals(arg)) {
                    outDir = Path.of(args[++i]);
                } else if ("--c2s-dir".equals(arg)) {
                    c2sDir = Path.of(args[++i]);
                } else if (captureDir == null) {
                    captureDir = Path.of(arg);
                } else if (outDir == null) {
                    outDir = Path.of(arg);
                } else {
                    throw new IllegalArgumentException("Unexpected argument: " + arg);
                }
            }
            if (captureDir == null || outDir == null) {
                throw new IllegalArgumentException("Usage: FreesiaNativeFixtureExporterMain <capture-dir> <out-dir> [--c2s-dir <sniffer-dir>]");
            }
            return new Options(
                    captureDir.toAbsolutePath().normalize(),
                    outDir.toAbsolutePath().normalize(),
                    c2sDir == null ? null : c2sDir.toAbsolutePath().normalize());
        }
    }

    private record CaptureRecord(int seq, String direction, int id, Path file, byte[] payload) {
    }

    private record NativePacket(CaptureRecord record, Decoded decoded) {
    }

    private record Decoded(int type, int padding, byte[] body) {
    }

    private record Entry(
            int index,
            byte[] token,
            String name,
            long flagA,
            long flagB,
            long format,
            int start,
            int end) {
        Entry withIndex(int nextIndex) {
            return new Entry(nextIndex, token, name, flagA, flagB, format, start, end);
        }
    }

    private record Chunk(Entry entry, long total, long offset, byte[] data, String fileName) {
    }

    private record GroupResult(
            boolean complete,
            byte[] bytes,
            long totalBytes,
            long receivedBytes,
            int gaps) {
    }

    private static final class Group {
        private final Entry entry;
        private final ArrayList<Chunk> chunks = new ArrayList<>();

        Group(Entry entry) {
            this.entry = entry;
        }

        List<Chunk> chunks() {
            return chunks;
        }

        void add(Chunk chunk) {
            chunks.add(chunk);
        }

        GroupResult assemble() {
            chunks.sort(Comparator.comparingLong(Chunk::offset));
            long total = chunks.isEmpty() ? 0 : chunks.get(0).total();
            long received = 0;
            long expectedOffset = 0;
            int gaps = 0;
            for (Chunk chunk : chunks) {
                received += chunk.data().length;
                if (chunk.offset() != expectedOffset) {
                    gaps++;
                    expectedOffset = chunk.offset();
                }
                expectedOffset += chunk.data().length;
            }
            boolean complete = total > 0 && total <= Integer.MAX_VALUE && received == total && gaps == 0;
            byte[] out = new byte[complete ? (int) total : 0];
            if (complete) {
                for (Chunk chunk : chunks) {
                    System.arraycopy(chunk.data(), 0, out, (int) chunk.offset(), chunk.data().length);
                }
            }
            return new GroupResult(complete, out, total, received, gaps);
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        Cursor(byte[] data) {
            this(data, 0);
        }

        Cursor(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return data.length - offset;
        }

        int readUnsignedShortLE() {
            ensure(2);
            int value = (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
            offset += 2;
            return value;
        }

        int readVarIntAsInt() {
            long value = readVarLong();
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("varint too large");
            }
            return (int) value;
        }

        long readVarLong() {
            long value = 0;
            int shift = 0;
            while (shift < 70) {
                ensure(1);
                int b = data[offset++] & 0xff;
                value |= (long) (b & 0x7f) << shift;
                if ((b & 0x80) == 0) {
                    return value;
                }
                shift += 7;
            }
            throw new IllegalArgumentException("varint too long");
        }

        String readString() {
            int length = readVarIntAsInt();
            if (length <= 0 || length > 512) {
                throw new IllegalArgumentException("bad string length " + length);
            }
            byte[] bytes = readBytes(length);
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException ex) {
                throw new IllegalArgumentException("bad utf8", ex);
            }
        }

        void skip(int length) {
            ensure(length);
            offset += length;
        }

        byte[] readBytes(int length) {
            ensure(length);
            byte[] out = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return out;
        }

        byte[] remainingBytes() {
            return readBytes(remaining());
        }

        private void ensure(int length) {
            if (length < 0 || offset + length > data.length) {
                throw new IllegalArgumentException("unexpected end");
            }
        }
    }
}
