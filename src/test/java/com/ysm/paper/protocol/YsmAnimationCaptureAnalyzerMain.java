package com.ysm.paper.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class YsmAnimationCaptureAnalyzerMain {
    private static final String DIRECTION_C2S = "c2s";
    private static final String DIRECTION_S2C = "s2c";

    private YsmAnimationCaptureAnalyzerMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: YsmAnimationCaptureAnalyzerMain <capture-dir>");
        }

        Path captureDir = Path.of(args[0]);
        List<CapturePacket> packets = loadPackets(captureDir);
        List<DecodedClientAnimation> clientAnimations = new ArrayList<>();
        List<DecodedServerAnimation> serverAnimations = new ArrayList<>();

        for (CapturePacket packet : packets) {
            if (packet.subpacketId() == YsmProtocol.CLIENT_ANIMATION_REQUEST_ID
                    && !DIRECTION_S2C.equals(packet.direction())) {
                try {
                    clientAnimations.add(new DecodedClientAnimation(
                            packet,
                            YsmProtocol.decodeClientAnimationRequest(packet.payload())));
                } catch (YsmProtocolException ex) {
                    System.out.println("WARN failed to decode C2S id=7: file="
                            + packet.fileName() + ", error=" + ex.getMessage());
                }
            } else if (packet.subpacketId() == YsmProtocol.ANIMATION_ID
                    && !DIRECTION_C2S.equals(packet.direction())) {
                try {
                    serverAnimations.add(new DecodedServerAnimation(packet, decodeServerAnimation(packet.payload())));
                } catch (YsmProtocolException ex) {
                    System.out.println("WARN failed to decode S2C id=21: file="
                            + packet.fileName() + ", error=" + ex.getMessage());
                }
            }
        }

        List<AnimationPair> pairs = pairAnimations(clientAnimations, serverAnimations);

        System.out.println("YSM animation capture: " + captureDir.toAbsolutePath());
        System.out.println("Packets=" + packets.size()
                + ", c2sId7=" + clientAnimations.size()
                + ", s2cId21=" + serverAnimations.size()
                + ", paired=" + pairs.size());
        System.out.println();
        System.out.println("pair\tc2sSeq\tc2sFile\tc2sAction\tc2sName\tc2sTarget"
                + "\ts2cSeq\ts2cFile\ts2cEntity\ts2cLayer\ts2cAction\ts2cName\tgapPackets");
        for (int i = 0; i < pairs.size(); i++) {
            AnimationPair pair = pairs.get(i);
            YsmProtocol.ClientAnimationRequest request = pair.client().request();
            ServerAnimation animation = pair.server().animation();
            System.out.println((i + 1)
                    + "\t" + pair.client().packet().sequence()
                    + "\t" + pair.client().packet().fileName()
                    + "\t" + request.action()
                    + "\t" + tsv(request.name())
                    + "\t" + request.targetEntityId()
                    + "\t" + pair.server().packet().sequence()
                    + "\t" + pair.server().packet().fileName()
                    + "\t" + animation.entityId()
                    + "\t" + animation.layer()
                    + "\t" + animation.action()
                    + "\t" + tsv(animation.name())
                    + "\t" + (pair.server().packet().order() - pair.client().packet().order()));
        }

        System.out.println();
        printSummary(pairs);
        printUnpaired("Unpaired C2S id=7", clientAnimations.stream()
                .filter(client -> pairs.stream().noneMatch(pair -> pair.client() == client))
                .map(DecodedClientAnimation::packet)
                .toList());
        printUnpaired("Unpaired S2C id=21", serverAnimations.stream()
                .filter(server -> pairs.stream().noneMatch(pair -> pair.server() == server))
                .map(DecodedServerAnimation::packet)
                .toList());
    }

    private static List<CapturePacket> loadPackets(Path captureDir) throws IOException {
        if (!Files.isDirectory(captureDir)) {
            throw new IllegalArgumentException("Capture path is not a directory: " + captureDir);
        }

        Path index = captureDir.resolve("index.tsv");
        if (Files.isRegularFile(index)) {
            return loadFromIndex(captureDir, index);
        }

        try (Stream<Path> stream = Files.list(captureDir)) {
            List<Path> files = stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".bin"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
            List<CapturePacket> packets = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                Path file = files.get(i);
                byte[] payload = Files.readAllBytes(file);
                packets.add(new CapturePacket(
                        i + 1,
                        i,
                        inferDirection(file.getFileName().toString()),
                        file.getFileName().toString(),
                        payload));
            }
            return packets;
        }
    }

    private static List<CapturePacket> loadFromIndex(Path captureDir, Path index) throws IOException {
        List<String> lines = Files.readAllLines(index, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> columns = columns(lines.get(0));
        int fileColumn = requireColumn(columns, "file");
        Integer seqColumn = columns.get("seq");
        Integer directionColumn = columns.get("direction");
        List<CapturePacket> packets = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (fileColumn >= parts.length) {
                continue;
            }
            String fileName = parts[fileColumn];
            Path file = captureDir.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                System.out.println("WARN index entry missing file: " + file.toAbsolutePath());
                continue;
            }
            int sequence = seqColumn == null || seqColumn >= parts.length
                    ? packets.size() + 1
                    : parseInt(parts[seqColumn], packets.size() + 1);
            String direction = directionColumn == null || directionColumn >= parts.length
                    ? inferDirection(fileName)
                    : normalizeDirection(parts[directionColumn], fileName);
            packets.add(new CapturePacket(sequence, packets.size(), direction, fileName, Files.readAllBytes(file)));
        }
        return packets;
    }

    private static List<AnimationPair> pairAnimations(
            List<DecodedClientAnimation> clientAnimations,
            List<DecodedServerAnimation> serverAnimations) {
        ArrayDeque<DecodedClientAnimation> pending = new ArrayDeque<>(clientAnimations);
        List<AnimationPair> pairs = new ArrayList<>();
        for (DecodedServerAnimation server : serverAnimations) {
            while (!pending.isEmpty() && pending.peek().packet().order() < server.packet().order()) {
                DecodedClientAnimation client = pending.remove();
                pairs.add(new AnimationPair(client, server));
                break;
            }
        }
        return pairs;
    }

    private static void printSummary(List<AnimationPair> pairs) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AnimationPair pair : pairs) {
            YsmProtocol.ClientAnimationRequest request = pair.client().request();
            ServerAnimation animation = pair.server().animation();
            String key = "client(action=" + request.action()
                    + ", name=" + display(request.name())
                    + ", target=" + request.targetEntityId()
                    + ") -> server(entity=" + animation.entityId()
                    + ", layer=" + animation.layer()
                    + ", action=" + animation.action()
                    + ", name=" + display(animation.name())
                    + ")";
            counts.merge(key, 1, Integer::sum);
        }

        System.out.println("Observed mappings:");
        if (counts.isEmpty()) {
            System.out.println("  none");
            return;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            System.out.println("  " + entry.getKey() + " x" + entry.getValue());
        }
    }

    private static void printUnpaired(String title, List<CapturePacket> packets) {
        if (packets.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(title + ":");
        for (CapturePacket packet : packets) {
            System.out.println("  seq=" + packet.sequence() + ", file=" + packet.fileName());
        }
    }

    private static ServerAnimation decodeServerAnimation(byte[] payload) {
        Reader reader = new Reader(payload);
        int id = reader.readUnsignedByte();
        if (id != YsmProtocol.ANIMATION_ID) {
            throw new YsmProtocolException("expected server animation id 21, got " + id);
        }
        int entityId = reader.readVarInt();
        int layer = reader.readVarInt();
        int action = reader.readVarInt();
        String name = reader.readUtf();
        if (reader.remaining() != 0) {
            throw new YsmProtocolException("server animation has " + reader.remaining() + " trailing byte(s)");
        }
        return new ServerAnimation(entityId, layer, action, name);
    }

    private static Map<String, Integer> columns(String header) {
        String[] values = header.split("\t", -1);
        Map<String, Integer> columns = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i++) {
            columns.put(values[i].toLowerCase(Locale.ROOT), i);
        }
        return columns;
    }

    private static int requireColumn(Map<String, Integer> columns, String name) {
        return Optional.ofNullable(columns.get(name))
                .orElseThrow(() -> new IllegalArgumentException("index.tsv is missing column: " + name));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String inferDirection(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.contains("-c2s-") || lower.startsWith("c2s-")) {
            return DIRECTION_C2S;
        }
        if (lower.contains("-s2c-") || lower.startsWith("s2c-")) {
            return DIRECTION_S2C;
        }
        return "";
    }

    private static String normalizeDirection(String value, String fileName) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (DIRECTION_C2S.equals(lower) || DIRECTION_S2C.equals(lower)) {
            return lower;
        }
        return inferDirection(fileName);
    }

    private static String tsv(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\t", "\\t")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    private static String display(String value) {
        return value.isEmpty() ? "<empty>" : "\"" + value + "\"";
    }

    private record CapturePacket(int sequence, int order, String direction, String fileName, byte[] payload) {
        private CapturePacket {
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(fileName, "fileName");
            Objects.requireNonNull(payload, "payload");
        }

        int subpacketId() {
            return payload.length == 0 ? -1 : payload[0] & 0xff;
        }
    }

    private record DecodedClientAnimation(CapturePacket packet, YsmProtocol.ClientAnimationRequest request) {
    }

    private record DecodedServerAnimation(CapturePacket packet, ServerAnimation animation) {
    }

    private record AnimationPair(DecodedClientAnimation client, DecodedServerAnimation server) {
    }

    private record ServerAnimation(int entityId, int layer, int action, String name) {
    }

    private static final class Reader {
        private final byte[] payload;
        private int offset;

        private Reader(byte[] payload) {
            this.payload = payload;
        }

        private int readUnsignedByte() {
            ensureAvailable(1);
            return payload[offset++] & 0xff;
        }

        private int readVarInt() {
            int value = 0;
            int numRead = 0;
            int current;
            do {
                ensureAvailable(1);
                current = payload[offset++] & 0xff;
                value |= (current & 0x7f) << (7 * numRead);
                numRead++;
                if (numRead > 5) {
                    throw new YsmProtocolException("VarInt is too large");
                }
            } while ((current & 0x80) != 0);
            return value;
        }

        private String readUtf() {
            int length = readVarInt();
            if (length < 0 || length > 32767) {
                throw new YsmProtocolException("invalid UTF byte length: " + length);
            }
            ensureAvailable(length);
            String value = new String(payload, offset, length, StandardCharsets.UTF_8);
            offset += length;
            return value;
        }

        private int remaining() {
            return payload.length - offset;
        }

        private void ensureAvailable(int count) {
            if (payload.length - offset < count) {
                throw new YsmProtocolException("payload ended early");
            }
        }
    }
}
