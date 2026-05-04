package com.ysm.paper.nativebridge.sync;

import com.ysm.paper.nativebridge.crypto.YsmRawPacketCodec;
import com.ysm.paper.protocol.YsmProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class FreesiaCaptureAnalyzerMain {
    private static final Pattern FREESIA_S2C_HEX_PATTERN =
            Pattern.compile("S2C Packet Data \\((\\d+) bytes\\):\\s*([0-9A-Fa-f ]+)");
    private static final Pattern FREESIA_C2S_HEX_PATTERN =
            Pattern.compile("YSM Packet from .* \\(len=(\\d+)\\):\\s*([0-9A-Fa-f ]+)");
    private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("[0-9A-Fa-f]{2}");
    private static final HexFormat HEX = HexFormat.of();

    private FreesiaCaptureAnalyzerMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Usage: FreesiaCaptureAnalyzerMain <freesia-latest.log> [dump-dir]");
        }

        Path log = Path.of(args[0]);
        Path dumpDir = args.length == 2 ? Path.of(args[1]) : null;
        List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
        List<byte[]> serverRawBodies = new ArrayList<>();
        Map<Integer, Integer> serverFirstByteCounts = new LinkedHashMap<>();
        List<Integer> clientRawLengths = new ArrayList<>();
        List<Integer> clientSelectionLengths = new ArrayList<>();

        for (String line : lines) {
            Matcher s2c = FREESIA_S2C_HEX_PATTERN.matcher(line);
            if (s2c.find()) {
                int expectedBytes = Integer.parseInt(s2c.group(1));
                byte[] payload = parseHexBytes(s2c.group(2));
                if (payload.length != expectedBytes) {
                    continue;
                }
                int id = payload.length == 0 ? -1 : payload[0] & 0xff;
                serverFirstByteCounts.merge(id, 1, Integer::sum);
                if (id == YsmProtocol.SERVER_RAW_PACKET_ID) {
                    serverRawBodies.add(Arrays.copyOfRange(payload, 1, payload.length));
                }
                continue;
            }

            Matcher c2s = FREESIA_C2S_HEX_PATTERN.matcher(line);
            if (c2s.find()) {
                int expectedBytes = Integer.parseInt(c2s.group(1));
                byte[] payload = parseHexBytes(c2s.group(2));
                if (payload.length == 0) {
                    continue;
                }
                int id = payload[0] & 0xff;
                if (id == YsmProtocol.CLIENT_RAW_PACKET_ID) {
                    clientRawLengths.add(expectedBytes - 1);
                } else if (id == YsmProtocol.CLIENT_MODEL_SELECTION_ID) {
                    clientSelectionLengths.add(expectedBytes);
                }
            }
        }

        System.out.println("Freesia capture: " + log.toAbsolutePath());
        System.out.println("S2C first-byte counts: " + serverFirstByteCounts);
        System.out.println("S2C id=1 packets: " + serverRawBodies.size()
                + ", totalBodyBytes=" + serverRawBodies.stream().mapToLong(body -> body.length).sum());
        System.out.println("C2S id=2 body lengths from log headers: " + clientRawLengths);
        System.out.println("C2S id=5 payload lengths from log headers: " + clientSelectionLengths);

        printLengthSummary(serverRawBodies);
        analyzeFirstRawPacket(serverRawBodies);
        if (dumpDir != null) {
            dumpServerRawBodies(serverRawBodies, dumpDir);
        }
    }

    private static void printLengthSummary(List<byte[]> rawBodies) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (byte[] body : rawBodies) {
            counts.merge(body.length, 1, Integer::sum);
        }
        System.out.println("First 12 id=1 body lengths: " + rawBodies.stream()
                .limit(12)
                .map(body -> Integer.toString(body.length))
                .toList());
        System.out.println("Small id=1 body length histogram: " + counts.entrySet().stream()
                .filter(entry -> entry.getKey() <= 4096)
                .map(entry -> entry.getKey() + "x" + entry.getValue())
                .toList());
    }

    private static void analyzeFirstRawPacket(List<byte[]> rawBodies) {
        if (rawBodies.isEmpty()) {
            return;
        }

        byte[] first = rawBodies.get(0);
        System.out.println("First id=1 raw body bytes=" + first.length
                + ", preview=" + HEX.formatHex(Arrays.copyOf(first, Math.min(32, first.length))));
        try {
            int hashOffset = YsmRawPacketCodec.verifyPacketHashOffset(first);
            int encryptedBytes = hashOffset;
            int type1PaddingCandidate = encryptedBytes - (2 + 1 + YsmRawPacketCodec.KEY_BYTES);
            int type2PaddingCandidate = encryptedBytes - (2 + 1 + 1 + YsmRawPacketCodec.KEY_BYTES);
            int fullType1PaddingCandidate = encryptedBytes - YsmRawPacketCodec.KEY_BYTES
                    - (2 + 1 + YsmRawPacketCodec.KEY_BYTES);
            System.out.println("First id=1 packet hash: valid, encryptedOrBodyBytes=" + encryptedBytes);
            System.out.println("Padding candidates if body-only: type1=" + type1PaddingCandidate
                    + ", type2=" + type2PaddingCandidate);
            System.out.println("Padding candidate if full type1+next-key trailer: " + fullType1PaddingCandidate);
        } catch (RuntimeException ex) {
            System.out.println("First id=1 packet hash: invalid (" + ex.getMessage() + ")");
        }

        List<YsmNativeSyncPrototype.KeyCandidate> candidates = YsmNativeSyncPrototype.MODES.stream()
                .map(mode -> new YsmNativeSyncPrototype.KeyCandidate(
                        "mode:" + mode,
                        YsmNativeSyncPrototype.bootstrapTransportKey(mode)))
                .toList();
        Optional<YsmNativeSyncPrototype.DecodedPacket> decoded =
                YsmNativeSyncPrototype.tryDecode(first, candidates);
        if (decoded.isPresent()) {
            YsmNativeSyncPrototype.DecodedPacket packet = decoded.get();
            System.out.println("First id=1 decoded with " + packet.keyName()
                    + ": type=" + packet.packet().type()
                    + ", summary=" + packet.packet().summary()
                    + ", nextTrailer=" + packet.usedNextKeyTrailer()
                    + ", next=" + YsmNativeSyncPrototype.keyPreview(packet.nextTransportKey()));
        } else {
            System.out.println("First id=1 did not decode with current PaperYSM bootstrap key candidates.");
        }
    }

    private static void dumpServerRawBodies(List<byte[]> rawBodies, Path dumpDir) throws IOException {
        Files.createDirectories(dumpDir);
        List<String> index = new ArrayList<>();
        index.add("index\tfile\tbodyBytes\thashValid\tsha256");
        for (int i = 0; i < rawBodies.size(); i++) {
            byte[] body = rawBodies.get(i);
            String fileName = String.format(Locale.ROOT, "s2c-id1-%04d-body-%d.bin", i + 1, body.length);
            Path file = dumpDir.resolve(fileName);
            Files.write(file, body);
            index.add((i + 1) + "\t"
                    + fileName + "\t"
                    + body.length + "\t"
                    + YsmRawPacketCodec.hasValidPacketHash(body) + "\t"
                    + sha256(body));
        }
        Files.write(dumpDir.resolve("index.tsv"), index, StandardCharsets.UTF_8);
        System.out.println("Dumped S2C id=1 raw bodies: dir=" + dumpDir.toAbsolutePath()
                + ", packets=" + rawBodies.size() + ".");
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private static byte[] parseHexBytes(String text) {
        Matcher matcher = HEX_BYTE_PATTERN.matcher(text);
        byte[] bytes = new byte[Math.max(0, text.length() / 2)];
        int count = 0;
        while (matcher.find()) {
            if (count == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length + Math.max(16, bytes.length));
            }
            bytes[count++] = (byte) Integer.parseInt(matcher.group(), 16);
        }
        return Arrays.copyOf(bytes, count);
    }
}
