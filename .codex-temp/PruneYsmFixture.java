import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

public final class PruneYsmFixture {
    private static final int KEY_BYTES = 56;
    private static final HexFormat HEX = HexFormat.of();

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: PruneYsmFixture <source-fixture> <target-fixture>");
        }
        Path source = Path.of(args[0]).toAbsolutePath().normalize();
        Path target = Path.of(args[1]).toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new IllegalArgumentException("Source fixture does not exist: " + source);
        }
        if (Files.exists(target) && hasAnyEntry(target)) {
            throw new IllegalArgumentException("Target fixture already exists and is not empty: " + target);
        }

        byte[] type3 = Files.readAllBytes(source.resolve("type3-body.bin"));
        List<ReportRow> rows = readReport(source.resolve("export-report.tsv"));
        int entryStart = findEntryStart(type3);
        List<Entry> entries = parseEntries(type3, entryStart);
        if (rows.size() != entries.size()) {
            throw new IllegalStateException("Report/type3 entry count mismatch: report="
                    + rows.size() + ", type3=" + entries.size());
        }

        ArrayList<ReportRow> keptRows = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            ReportRow row = rows.get(i);
            Entry entry = entries.get(i);
            if (!row.tokenHex().equals(entry.tokenHex())) {
                throw new IllegalStateException("Token mismatch at row " + i
                        + ": report=" + row.tokenHex() + ", type3=" + entry.tokenHex());
            }
            if (row.complete()) {
                keptRows.add(row);
            }
        }
        if (keptRows.isEmpty()) {
            throw new IllegalStateException("No complete cache rows to keep.");
        }

        int tailStart = entries.get(entries.size() - 1).end();
        ByteArrayOutputStream prunedType3 = new ByteArrayOutputStream(type3.length);
        prunedType3.write(type3, 0, entryStart);
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).complete()) {
                Entry entry = entries.get(i);
                prunedType3.write(type3, entry.start(), entry.end() - entry.start());
            }
        }
        prunedType3.write(type3, tailStart, type3.length - tailStart);

        Files.createDirectories(target);
        Files.write(target.resolve("type3-body.bin"), prunedType3.toByteArray());
        copyIfPresent(source.resolve("type1-padding.txt"), target.resolve("type1-padding.txt"));
        copyIfPresent(source.resolve("type3-padding.txt"), target.resolve("type3-padding.txt"));
        copyIfPresent(source.resolve("worker-cache-models.tsv"), target.resolve("worker-cache-models.tsv"));

        ArrayList<String> cacheMap = new ArrayList<>();
        ArrayList<String> report = new ArrayList<>();
        ArrayList<String> pruneReport = new ArrayList<>();
        cacheMap.add("tokenHex\tfile\tname\tbytes");
        report.add("index\ttokenHex\tname\trequested\tchunks\ttotalBytes\treceivedBytes\tgaps\tfile");
        pruneReport.add("oldIndex\ttokenHex\tname\treason");

        int newIndex = 0;
        long linked = 0;
        long copied = 0;
        for (int i = 0; i < rows.size(); i++) {
            ReportRow row = rows.get(i);
            if (!row.complete()) {
                pruneReport.add(row.index() + "\t" + row.tokenHex() + "\t" + row.name() + "\tmissing-cache-file");
                continue;
            }
            Path srcFile = source.resolve(row.file()).normalize();
            Path dstFile = target.resolve(row.file()).normalize();
            if (!srcFile.startsWith(source) || !dstFile.startsWith(target)) {
                throw new IllegalStateException("Fixture path escaped source/target: " + row.file());
            }
            Files.createDirectories(dstFile.getParent());
            boolean hardLinked = linkOrCopy(srcFile, dstFile);
            if (hardLinked) {
                linked++;
            } else {
                copied++;
            }
            long bytes = Files.size(dstFile);
            cacheMap.add(row.tokenHex() + "\t" + row.file() + "\t" + row.name() + "\t" + bytes);
            report.add(newIndex + "\t"
                    + row.tokenHex() + "\t"
                    + row.name() + "\t"
                    + row.requested() + "\t"
                    + row.chunks() + "\t"
                    + row.totalBytes() + "\t"
                    + row.receivedBytes() + "\t"
                    + row.gaps() + "\t"
                    + row.file());
            newIndex++;
        }
        Files.write(target.resolve("cache-map.tsv"), cacheMap, StandardCharsets.UTF_8);
        Files.write(target.resolve("export-report.tsv"), report, StandardCharsets.UTF_8);
        Files.write(target.resolve("prune-report.tsv"), pruneReport, StandardCharsets.UTF_8);

        System.out.println("source=" + source);
        System.out.println("target=" + target);
        System.out.println("entryStart=" + entryStart + ", tailBytes=" + (type3.length - tailStart));
        System.out.println("manifestEntriesOriginal=" + entries.size()
                + ", kept=" + keptRows.size()
                + ", removed=" + (entries.size() - keptRows.size()));
        System.out.println("type3BytesOriginal=" + type3.length
                + ", type3BytesPruned=" + prunedType3.size());
        System.out.println("cacheMapRows=" + (cacheMap.size() - 1)
                + ", serverCacheLinks=" + linked
                + ", serverCacheCopies=" + copied);
    }

    private static boolean hasAnyEntry(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.findAny().isPresent();
        }
    }

    private static void copyIfPresent(Path source, Path target) throws IOException {
        if (Files.exists(source)) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean linkOrCopy(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            return false;
        }
        try {
            Files.createLink(target, source);
            return true;
        } catch (UnsupportedOperationException | IOException | SecurityException ex) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
            return false;
        }
    }

    private static List<ReportRow> readReport(Path reportFile) throws IOException {
        List<String> lines = Files.readAllLines(reportFile, StandardCharsets.UTF_8);
        ArrayList<ReportRow> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split("\t", -1);
            if (parts.length < 9) {
                throw new IllegalArgumentException("Bad export-report.tsv line " + (i + 1));
            }
            rows.add(new ReportRow(
                    Integer.parseInt(parts[0]),
                    normalizeHex(parts[1]),
                    parts[2],
                    parts[3],
                    parseLong(parts[4]),
                    parseLong(parts[5]),
                    parseLong(parts[6]),
                    parseLong(parts[7]),
                    parts[8]));
        }
        return List.copyOf(rows);
    }

    private static long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        return Long.parseLong(value.trim());
    }

    private static String normalizeHex(String value) {
        return value.replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .toLowerCase(Locale.ROOT);
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
                entry = parseEntryAt(body, pos);
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
                if (tokenLen < 8 || tokenLen > 40 || !plausibleName(name)
                        || flagA > 4 || flagB > 4 || format > 65535) {
                    continue;
                }
                Entry candidate = new Entry(
                        Arrays.copyOfRange(body, pos, tokenEnd),
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

    private record ReportRow(
            int index,
            String tokenHex,
            String name,
            String requested,
            long chunks,
            long totalBytes,
            long receivedBytes,
            long gaps,
            String file) {
        boolean complete() {
            return !file.isBlank() && gaps == 0L && totalBytes > 0L && totalBytes == receivedBytes;
        }
    }

    private record Entry(byte[] token, int start, int end) {
        String tokenHex() {
            return HEX.formatHex(token);
        }
    }

    private static final class Cursor {
        private final byte[] data;
        private int offset;

        Cursor(byte[] data, int offset) {
            this.data = data;
            this.offset = offset;
        }

        int offset() {
            return offset;
        }

        long readVarLong() {
            long value = 0L;
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

        int readVarIntAsInt() {
            long value = readVarLong();
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("varint too large");
            }
            return (int) value;
        }

        String readString() {
            int length = readVarIntAsInt();
            if (length <= 0 || length > 512) {
                throw new IllegalArgumentException("bad string length " + length);
            }
            ensure(length);
            byte[] bytes = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            try {
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString();
            } catch (CharacterCodingException ex) {
                throw new IllegalArgumentException("bad utf8", ex);
            }
        }

        private void ensure(int length) {
            if (length < 0 || offset + length > data.length) {
                throw new IllegalArgumentException("unexpected end");
            }
        }
    }
}
