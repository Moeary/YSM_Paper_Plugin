package com.ysm.paper.model;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class YsmDistributionProbeMain {
    private YsmDistributionProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        List<YsmModelRepository.Entry> entries = loadEntries(options.input(), options.limit());
        YsmDistributionRepository repository = new YsmDistributionRepository();
        repository.prepareAll(
                options.cacheRoot(),
                entries,
                options.chunkBytes(),
                options.writeCacheFiles());

        System.out.println("ysmDistributionInput=" + options.input().toAbsolutePath());
        System.out.println("ysmDistributionPrepared=" + repository.prepared().size());
        long skipped = repository.failures().stream()
                .filter(YsmDistributionProbeMain::isSkippableFailure)
                .count();
        long failed = repository.failures().size() - skipped;
        System.out.println("ysmDistributionSkipped=" + skipped);
        System.out.println("ysmDistributionFailed=" + failed);
        System.out.println("ysmDistributionDecompressedBytes=" + repository.totalDecompressedBytes());
        System.out.println("ysmDistributionTransferBytes=" + repository.totalTransferBytes());
        System.out.println("ysmDistributionChunks=" + repository.totalChunkCount());
        for (YsmDistributionRepository.PreparedModel model : repository.prepared()) {
            System.out.println("PREPARED " + model.modelId()
                    + " format=" + model.format()
                    + " decompressedBytes=" + model.decompressedBytes()
                    + " zstdBytes=" + model.washedZstdBytes()
                    + " serverCacheYsmZstdBytes=" + model.serverCacheYsmZstdBytes()
                    + " transfer=" + model.transferKind()
                    + "/" + model.transferBytes()
                    + " chunks=" + model.chunkCount()
                    + " modelHash=" + model.modelHash()
                    + " payloadSha256=" + model.payloadSha256().substring(0, 16)
                    + " serverCacheSha256=" + model.serverCacheYsmZstdSha256().substring(0, 16)
                    + " transferSha256=" + model.transferSha256().substring(0, 16)
                    + " summary={" + model.summary() + "}");
        }
        for (YsmDistributionRepository.Failure failure : repository.failures()) {
            String status = isSkippableFailure(failure) ? "SKIPPED" : "FAILED";
            System.out.println(status + " " + failure.modelId() + " file=" + failure.file()
                    + " reason=" + failure.message());
        }

        if (failed > 0) {
            System.exit(2);
        }
    }

    private static boolean isSkippableFailure(YsmDistributionRepository.Failure failure) {
        String message = failure.message();
        return message != null && message.startsWith("legacy YSM ");
    }

    private static List<YsmModelRepository.Entry> loadEntries(Path input, int limit) throws Exception {
        Path root = Files.isDirectory(input) ? input : input.getParent();
        if (root == null) {
            root = Path.of(".");
        }

        YsmModelRepository repository = new YsmModelRepository();
        repository.reload(root);
        List<YsmModelRepository.Entry> entries = repository.entries();

        if (Files.isRegularFile(input)) {
            Path absoluteInput = input.toAbsolutePath().normalize();
            entries = entries.stream()
                    .filter(entry -> entry.file().toAbsolutePath().normalize().equals(absoluteInput))
                    .toList();
        }

        if (limit > 0 && entries.size() > limit) {
            entries = entries.stream()
                    .sorted(Comparator.comparing(YsmModelRepository.Entry::modelId))
                    .limit(limit)
                    .toList();
        }
        return entries;
    }

    private record Options(Path input, int limit, int chunkBytes, Path cacheRoot, boolean writeCacheFiles) {
        private static Options parse(String[] args) {
            if (args.length == 0) {
                throw new IllegalArgumentException("Usage: YsmDistributionProbeMain <file-or-directory> "
                        + "[--limit N] [--chunk-bytes N] [--write-cache DIR]");
            }

            Path input = Path.of(args[0]);
            int limit = 0;
            int chunkBytes = 24576;
            Path cacheRoot = Path.of("build", "distribution-cache");
            boolean writeCacheFiles = false;

            for (int i = 1; i < args.length; i++) {
                String arg = args[i].toLowerCase(Locale.ROOT);
                if ("--limit".equals(arg)) {
                    limit = Integer.parseInt(requireValue(args, ++i, "--limit"));
                    continue;
                }
                if ("--chunk-bytes".equals(arg)) {
                    chunkBytes = Integer.parseInt(requireValue(args, ++i, "--chunk-bytes"));
                    continue;
                }
                if ("--write-cache".equals(arg)) {
                    cacheRoot = Path.of(requireValue(args, ++i, "--write-cache"));
                    writeCacheFiles = true;
                    continue;
                }
                throw new IllegalArgumentException("Unknown argument: " + args[i]);
            }
            return new Options(input, limit, chunkBytes, cacheRoot, writeCacheFiles);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException(option + " requires a value");
            }
            return args[index];
        }
    }
}
