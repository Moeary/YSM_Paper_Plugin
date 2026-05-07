package com.ysm.paper.model;

import com.ysm.paper.nativebridge.crypto.YsmArchiveProbe;
import com.ysm.paper.nativebridge.crypto.YsmCrypto;
import com.ysm.paper.openysm.OpenYsmServerCacheConverter;
import com.ysm.paper.openysm.RawYsmModel;
import com.ysm.paper.openysm.YsmFolderDeserializer;
import com.ysm.paper.openysm.YsmLegacyArchiveExtractor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class YsmDistributionRepository {
    private static final HexFormat HEX = HexFormat.of();

    private final Map<String, PreparedModel> prepared = new ConcurrentHashMap<>();
    private final List<Failure> failures = new CopyOnWriteArrayList<>();
    private volatile Path cacheRoot;

    public Path cacheRoot() {
        return cacheRoot;
    }

    public List<PreparedModel> prepared() {
        return prepared.values().stream()
                .sorted((left, right) -> left.modelId().compareTo(right.modelId()))
                .toList();
    }

    public List<Failure> failures() {
        return List.copyOf(failures);
    }

    public Optional<PreparedModel> find(String modelId) {
        return Optional.ofNullable(prepared.get(modelId));
    }

    public void clear() {
        prepared.clear();
        failures.clear();
    }

    public PreparedModel prepareOne(
            Path cacheRoot,
            YsmModelRepository.Entry entry,
            int chunkBytes,
            boolean writeCacheFiles) throws IOException {
        this.cacheRoot = cacheRoot;
        PreparedModel model = prepare(cacheRoot, entry, chunkBytes, writeCacheFiles);
        prepared.put(model.modelId(), model);
        failures.removeIf(failure -> failure.modelId().equals(entry.modelId()));
        return model;
    }

    public PreparedModel prepareEphemeral(
            Path cacheRoot,
            YsmModelRepository.Entry entry,
            int chunkBytes,
            boolean writeCacheFiles) throws IOException {
        this.cacheRoot = cacheRoot;
        return prepare(cacheRoot, entry, chunkBytes, writeCacheFiles);
    }

    public void prepareAll(
            Path cacheRoot,
            List<YsmModelRepository.Entry> entries,
            int chunkBytes,
            boolean writeCacheFiles) throws IOException {
        this.cacheRoot = cacheRoot;
        prepared.clear();
        failures.clear();

        if (writeCacheFiles) {
            Files.createDirectories(cacheRoot);
        }

        for (YsmModelRepository.Entry entry : entries) {
            try {
                PreparedModel model = prepare(cacheRoot, entry, chunkBytes, writeCacheFiles);
                prepared.put(model.modelId(), model);
            } catch (Exception ex) {
                failures.add(new Failure(entry.modelId(), entry.file(), ex.getMessage()));
            }
        }
    }

    public long totalDecompressedBytes() {
        return prepared.values().stream()
                .mapToLong(PreparedModel::decompressedBytes)
                .sum();
    }

    public long totalTransferBytes() {
        return prepared.values().stream()
                .mapToLong(PreparedModel::transferBytes)
                .sum();
    }

    public long totalChunkCount() {
        return prepared.values().stream()
                .mapToLong(PreparedModel::chunkCount)
                .sum();
    }

    private static PreparedModel prepare(
            Path cacheRoot,
            YsmModelRepository.Entry entry,
            int configuredChunkBytes,
            boolean writeCacheFiles) throws IOException {
        if (!"V3".equalsIgnoreCase(entry.version())) {
            return prepareLegacy(cacheRoot, entry, configuredChunkBytes, writeCacheFiles);
        }
        int chunkBytes = Math.max(1024, configuredChunkBytes);
        YsmArchiveProbe.ExtractedV3Archive extracted = YsmArchiveProbe.extractV3(entry.file());
        byte[] decompressed = extracted.decompressed();
        byte[] washedZstd = extracted.washedZstd();
        byte[] serverCachePlaintext = serverCachePlaintext(extracted);
        byte[] serverCacheYsmZstd = YsmCrypto.ysmZstdCompress(serverCachePlaintext);
        String payloadSha256 = sha256Hex(decompressed);
        String zstdSha256 = sha256Hex(washedZstd);
        String serverCachePlainSha256 = sha256Hex(serverCachePlaintext);
        String serverCacheYsmZstdSha256 = sha256Hex(serverCacheYsmZstd);
        String modelHash = extracted.result().modelHash();
        if (modelHash == null || modelHash.isBlank()) {
            modelHash = payloadSha256;
        }
        byte[] transferPayload = washedZstd;
        int chunkCount = (transferPayload.length + chunkBytes - 1) / chunkBytes;
        Path modelCacheDir = cacheRoot.resolve(cacheDirectoryName(entry.modelId(), payloadSha256));

        if (writeCacheFiles) {
            writeCacheFiles(
                    modelCacheDir,
                    entry,
                    extracted,
                    payloadSha256,
                    zstdSha256,
                    serverCachePlaintext,
                    serverCacheYsmZstd,
                    serverCachePlainSha256,
                    serverCacheYsmZstdSha256,
                    chunkBytes,
                    chunkCount);
        }

        return new PreparedModel(
                entry.modelId(),
                entry.file(),
                extracted.result().format(),
                extracted.result().size(),
                decompressed.length,
                washedZstd.length,
                serverCachePlaintext.length,
                serverCacheYsmZstd.length,
                modelHash,
                payloadSha256,
                zstdSha256,
                serverCachePlainSha256,
                serverCacheYsmZstdSha256,
                "washed-zstd",
                transferPayload.length,
                zstdSha256,
                chunkBytes,
                chunkCount,
                Instant.now(),
                writeCacheFiles ? modelCacheDir : null,
                extracted.result().payloadSummary(),
                serverCacheYsmZstd,
                transferPayload);
    }

    private static PreparedModel prepareLegacy(
            Path cacheRoot,
            YsmModelRepository.Entry entry,
            int configuredChunkBytes,
            boolean writeCacheFiles) throws IOException {
        int chunkBytes = Math.max(1024, configuredChunkBytes);
        Path tempRoot = cacheRoot.resolve(".legacy-temp");
        Files.createDirectories(tempRoot);
        try (YsmLegacyArchiveExtractor.ExtractedLegacyArchive legacy =
                     YsmLegacyArchiveExtractor.extractToDirectory(entry.file(), tempRoot);
             YsmFolderDeserializer deserializer = new YsmFolderDeserializer(legacy.directory())) {
            RawYsmModel rawModel = deserializer.deserialize();
            rawModel.modelId = entry.modelId();
            byte[] serverCachePlaintext = OpenYsmServerCacheConverter.toServerCacheCleartext(rawModel);
            byte[] serverCacheYsmZstd = YsmCrypto.ysmZstdCompress(serverCachePlaintext);
            String payloadSha256 = sha256Hex(serverCachePlaintext);
            String serverCacheYsmZstdSha256 = sha256Hex(serverCacheYsmZstd);
            String modelHash = rawModel.properties.sha256 == null || rawModel.properties.sha256.isBlank()
                    ? payloadSha256
                    : rawModel.properties.sha256;
            int chunkCount = (serverCacheYsmZstd.length + chunkBytes - 1) / chunkBytes;
            Path modelCacheDir = cacheRoot.resolve(cacheDirectoryName(entry.modelId(), payloadSha256));
            String summary = "legacy=V" + legacy.version()
                    + ", resources=" + legacy.resourceCount()
                    + ", resourceBytes=" + legacy.resourceBytes()
                    + ", folderHash=" + modelHash;

            if (writeCacheFiles) {
                writeLegacyCacheFiles(
                        modelCacheDir,
                        entry,
                        legacy,
                        payloadSha256,
                        serverCachePlaintext,
                        serverCacheYsmZstd,
                        serverCacheYsmZstdSha256,
                        modelHash,
                        chunkBytes,
                        chunkCount,
                        summary);
            }

            return new PreparedModel(
                    entry.modelId(),
                    entry.file(),
                    65535,
                    entry.size(),
                    serverCachePlaintext.length,
                    serverCacheYsmZstd.length,
                    serverCachePlaintext.length,
                    serverCacheYsmZstd.length,
                    modelHash,
                    payloadSha256,
                    serverCacheYsmZstdSha256,
                    payloadSha256,
                    serverCacheYsmZstdSha256,
                    "server-cache",
                    serverCacheYsmZstd.length,
                    serverCacheYsmZstdSha256,
                    chunkBytes,
                    chunkCount,
                    Instant.now(),
                    writeCacheFiles ? modelCacheDir : null,
                    summary,
                    serverCacheYsmZstd,
                    serverCacheYsmZstd);
        }
    }

    private static byte[] serverCachePlaintext(YsmArchiveProbe.ExtractedV3Archive extracted) {
        return OpenYsmServerCacheConverter.toServerCacheCleartext(extracted.decompressed());
    }

    private static void writeCacheFiles(
            Path modelCacheDir,
            YsmModelRepository.Entry entry,
            YsmArchiveProbe.ExtractedV3Archive extracted,
            String payloadSha256,
            String zstdSha256,
            byte[] serverCachePlaintext,
            byte[] serverCacheYsmZstd,
            String serverCachePlainSha256,
            String serverCacheYsmZstdSha256,
            int chunkBytes,
            int chunkCount) throws IOException {
        Files.createDirectories(modelCacheDir);
        Files.write(modelCacheDir.resolve("decompressed.bin"), extracted.decompressed());
        Files.write(modelCacheDir.resolve("washed.zst"), extracted.washedZstd());
        Files.write(modelCacheDir.resolve("server-cache-clear.bin"), serverCachePlaintext);
        Files.write(modelCacheDir.resolve("server-cache.ysm.zst"), serverCacheYsmZstd);

        Properties manifest = new Properties();
        manifest.setProperty("modelId", entry.modelId());
        manifest.setProperty("sourceFile", entry.file().toAbsolutePath().toString());
        manifest.setProperty("version", extracted.result().version());
        manifest.setProperty("format", Integer.toString(extracted.result().format()));
        manifest.setProperty("sourceBytes", Long.toString(extracted.result().size()));
        manifest.setProperty("decompressedBytes", Integer.toString(extracted.decompressed().length));
        manifest.setProperty("washedZstdBytes", Integer.toString(extracted.washedZstd().length));
        manifest.setProperty("serverCachePlainBytes", Integer.toString(extracted.decompressed().length - Integer.BYTES));
        manifest.setProperty("serverCacheYsmZstdBytes", Integer.toString(serverCacheYsmZstd.length));
        manifest.setProperty("modelHash", extracted.result().modelHash());
        manifest.setProperty("payloadSha256", payloadSha256);
        manifest.setProperty("zstdSha256", zstdSha256);
        manifest.setProperty("serverCachePlainSha256", serverCachePlainSha256);
        manifest.setProperty("serverCacheYsmZstdSha256", serverCacheYsmZstdSha256);
        manifest.setProperty("transferKind", "washed-zstd");
        manifest.setProperty("transferBytes", Integer.toString(extracted.washedZstd().length));
        manifest.setProperty("transferSha256", zstdSha256);
        manifest.setProperty("chunkBytes", Integer.toString(chunkBytes));
        manifest.setProperty("chunkCount", Integer.toString(chunkCount));
        manifest.setProperty("summary", extracted.result().payloadSummary());
        try (var output = Files.newOutputStream(modelCacheDir.resolve("manifest.properties"))) {
            manifest.store(output, "PaperYSM internal distribution cache");
        }
    }

    private static void writeLegacyCacheFiles(
            Path modelCacheDir,
            YsmModelRepository.Entry entry,
            YsmLegacyArchiveExtractor.ExtractedLegacyArchive legacy,
            String payloadSha256,
            byte[] serverCachePlaintext,
            byte[] serverCacheYsmZstd,
            String serverCacheYsmZstdSha256,
            String modelHash,
            int chunkBytes,
            int chunkCount,
            String summary) throws IOException {
        Files.createDirectories(modelCacheDir);
        Files.write(modelCacheDir.resolve("server-cache-clear.bin"), serverCachePlaintext);
        Files.write(modelCacheDir.resolve("server-cache.ysm.zst"), serverCacheYsmZstd);

        Properties manifest = new Properties();
        manifest.setProperty("modelId", entry.modelId());
        manifest.setProperty("sourceFile", entry.file().toAbsolutePath().toString());
        manifest.setProperty("version", "V" + legacy.version());
        manifest.setProperty("format", "65535");
        manifest.setProperty("sourceBytes", Long.toString(entry.size()));
        manifest.setProperty("legacyResourceCount", Integer.toString(legacy.resourceCount()));
        manifest.setProperty("legacyResourceBytes", Long.toString(legacy.resourceBytes()));
        manifest.setProperty("serverCachePlainBytes", Integer.toString(serverCachePlaintext.length));
        manifest.setProperty("serverCacheYsmZstdBytes", Integer.toString(serverCacheYsmZstd.length));
        manifest.setProperty("modelHash", modelHash);
        manifest.setProperty("payloadSha256", payloadSha256);
        manifest.setProperty("serverCachePlainSha256", payloadSha256);
        manifest.setProperty("serverCacheYsmZstdSha256", serverCacheYsmZstdSha256);
        manifest.setProperty("transferKind", "server-cache");
        manifest.setProperty("transferBytes", Integer.toString(serverCacheYsmZstd.length));
        manifest.setProperty("transferSha256", serverCacheYsmZstdSha256);
        manifest.setProperty("chunkBytes", Integer.toString(chunkBytes));
        manifest.setProperty("chunkCount", Integer.toString(chunkCount));
        manifest.setProperty("summary", summary);
        try (var output = Files.newOutputStream(modelCacheDir.resolve("manifest.properties"))) {
            manifest.store(output, "PaperYSM legacy distribution cache");
        }
    }

    private static String cacheDirectoryName(String modelId, String payloadSha256) {
        String sanitized = modelId.replace('\\', '/').replaceAll("[^A-Za-z0-9._-]+", "_");
        if (sanitized.length() > 64) {
            sanitized = sanitized.substring(sanitized.length() - 64);
        }
        return payloadSha256.substring(0, 16) + "-" + sanitized;
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record PreparedModel(
            String modelId,
            Path sourceFile,
            int format,
            long sourceBytes,
            int decompressedBytes,
            int washedZstdBytes,
            int serverCachePlainBytes,
            int serverCacheYsmZstdBytes,
            String modelHash,
            String payloadSha256,
            String zstdSha256,
            String serverCachePlainSha256,
            String serverCacheYsmZstdSha256,
            String transferKind,
            int transferBytes,
            String transferSha256,
            int chunkBytes,
            int chunkCount,
            Instant preparedAt,
            Path cacheDirectory,
            String summary,
            byte[] serverCacheYsmZstd,
            byte[] transferPayload) {
        public int chunkLength(int index) {
            if (index < 0 || index >= chunkCount) {
                throw new IndexOutOfBoundsException(index);
            }
            int offset = index * chunkBytes;
            return Math.min(chunkBytes, transferPayload.length - offset);
        }

        public byte[] chunk(int index) {
            int length = chunkLength(index);
            int offset = index * chunkBytes;
            byte[] out = new byte[length];
            System.arraycopy(transferPayload, offset, out, 0, length);
            return out;
        }
    }

    public record Failure(String modelId, Path file, String message) {
    }
}
