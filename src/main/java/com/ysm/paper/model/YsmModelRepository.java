package com.ysm.paper.model;

import com.ysm.paper.nativebridge.crypto.YsmArchiveProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class YsmModelRepository {
    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final List<Failure> failures = new CopyOnWriteArrayList<>();
    private volatile Path root;

    public Path root() {
        return root;
    }

    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public Optional<Entry> find(String modelId) {
        return entries.stream()
                .filter(entry -> entry.modelId().equals(modelId))
                .findFirst();
    }

    public void reload(Path root) throws IOException {
        this.root = root;
        entries.clear();
        failures.clear();

        if (!Files.exists(root)) {
            return;
        }

        List<Path> files;
        try (var stream = Files.walk(root)) {
            files = stream
                    .filter(Files::isRegularFile)
                    .filter(YsmModelRepository::isYsmFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        for (Path file : files) {
            try {
                YsmArchiveProbe.Result result = YsmArchiveProbe.probe(file);
                entries.add(new Entry(
                        modelId(root, file),
                        file,
                        result.version(),
                        result.format(),
                        result.size(),
                        result.decompressedBytes(),
                        result.payloadTrailingBytes(),
                        result.payloadSummary()));
            } catch (Exception ex) {
                failures.add(new Failure(file, ex.getMessage()));
            }
        }
    }

    private static boolean isYsmFile(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm");
    }

    private static String modelId(Path root, Path file) {
        Path relative;
        try {
            relative = root.toAbsolutePath().relativize(file.toAbsolutePath());
        } catch (IllegalArgumentException ex) {
            relative = file.getFileName();
        }
        String value = relative.toString().replace('\\', '/');
        if (value.toLowerCase(Locale.ROOT).endsWith(".ysm")) {
            value = value.substring(0, value.length() - 4);
        }
        return value;
    }

    public record Entry(
            String modelId,
            Path file,
            String version,
            int format,
            long size,
            int decompressedBytes,
            int payloadTrailingBytes,
            String summary) {
    }

    public record Failure(Path file, String message) {
    }
}
