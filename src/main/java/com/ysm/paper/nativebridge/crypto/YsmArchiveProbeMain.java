package com.ysm.paper.nativebridge.crypto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class YsmArchiveProbeMain {
    private YsmArchiveProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        Options options = Options.parse(args);
        if (options.input() == null) {
            System.err.println("Usage: YsmArchiveProbeMain <file-or-directory> [--limit N] "
                    + "[--dump-decompressed DIR] [--dump-zstd DIR]");
            System.exit(2);
        }

        List<Path> files = collectYsmFiles(options.input());
        int total = Math.min(files.size(), options.limit());
        int success = 0;
        int failed = 0;

        System.out.println("ysmProbeInput=" + options.input().toAbsolutePath());
        System.out.println("ysmProbeFiles=" + files.size() + ", selected=" + total);

        for (Path file : files.stream().limit(total).toList()) {
            try {
                YsmArchiveProbe.Result result;
                YsmArchiveProbe.ExtractedV3Archive extracted = null;
                if (options.dumpDecompressed() != null || options.dumpZstd() != null) {
                    extracted = YsmArchiveProbe.extractV3(file);
                    result = extracted.result();
                } else {
                    result = YsmArchiveProbe.probe(file);
                }
                success++;
                System.out.println("OK "
                        + relativize(options.input(), file)
                        + " version=" + result.version()
                        + " format=" + result.format()
                        + " size=" + result.size()
                        + " encrypted=" + result.encryptedBytes()
                        + " zstd=" + result.zstdBytes()
                        + " blocks=" + result.zstdBlocks()
                        + " decompressed=" + result.decompressedBytes()
                        + " payloadConsumed=" + result.payloadConsumedAll()
                        + " payloadTrailing=" + result.payloadTrailingBytes()
                        + " resources={" + result.payloadSummary() + "}"
                        + " profile={" + result.profile().compact() + "}"
                        + " animationMap={" + result.profile().animationDebugSummary(12) + "}");
                if (extracted != null) {
                    dumpIfRequested(options, file, extracted);
                }
            } catch (Exception ex) {
                failed++;
                System.out.println("FAIL " + relativize(options.input(), file) + " error=" + ex.getMessage());
            }
        }

        System.out.println("ysmProbeSuccess=" + success);
        System.out.println("ysmProbeFailed=" + failed);
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static void dumpIfRequested(
            Options options,
            Path file,
            YsmArchiveProbe.ExtractedV3Archive extracted) throws Exception {
        if (options.dumpZstd() != null) {
            Path out = dumpPath(options.input(), file, options.dumpZstd(), ".washed.zst");
            Files.createDirectories(out.getParent());
            Files.write(out, extracted.washedZstd());
            System.out.println("DUMP zstd " + out.toAbsolutePath());
        }
        if (options.dumpDecompressed() != null) {
            Path out = dumpPath(options.input(), file, options.dumpDecompressed(), ".decompressed.bin");
            Files.createDirectories(out.getParent());
            Files.write(out, extracted.decompressed());
            System.out.println("DUMP decompressed " + out.toAbsolutePath());
        }
    }

    private static Path dumpPath(Path input, Path file, Path dumpRoot, String suffix) {
        Path base = Files.isDirectory(input) ? input : input.getParent();
        Path relative = base == null ? file.getFileName() : base.toAbsolutePath().relativize(file.toAbsolutePath());
        String fileName = relative.getFileName().toString();
        int dot = fileName.toLowerCase(Locale.ROOT).lastIndexOf(".ysm");
        if (dot >= 0) {
            fileName = fileName.substring(0, dot);
        }
        Path parent = relative.getParent();
        Path withSuffix = parent == null ? Path.of(fileName + suffix) : parent.resolve(fileName + suffix);
        return dumpRoot.resolve(withSuffix);
    }

    private static List<Path> collectYsmFiles(Path input) throws Exception {
        if (Files.isRegularFile(input)) {
            return List.of(input);
        }
        try (var stream = Files.walk(input)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ysm"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
    }

    private static String relativize(Path input, Path file) {
        Path base = Files.isDirectory(input) ? input : input.getParent();
        if (base == null) {
            return file.toString();
        }
        try {
            return base.toAbsolutePath().relativize(file.toAbsolutePath()).toString();
        } catch (IllegalArgumentException ex) {
            return file.toString();
        }
    }

    private record Options(Path input, int limit, Path dumpDecompressed, Path dumpZstd) {
        private static Options parse(String[] args) {
            Path input = null;
            int limit = Integer.MAX_VALUE;
            Path dumpDecompressed = null;
            Path dumpZstd = null;
            for (int i = 0; i < args.length; i++) {
                if ("--limit".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--limit requires a value");
                    }
                    limit = Integer.parseInt(args[++i]);
                    if (limit < 0) {
                        throw new IllegalArgumentException("--limit must be >= 0");
                    }
                    continue;
                }
                if ("--dump-decompressed".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--dump-decompressed requires a directory");
                    }
                    dumpDecompressed = Path.of(args[++i]);
                    continue;
                }
                if ("--dump-zstd".equals(args[i])) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("--dump-zstd requires a directory");
                    }
                    dumpZstd = Path.of(args[++i]);
                    continue;
                }
                if (input != null) {
                    throw new IllegalArgumentException("Only one input path is supported");
                }
                input = Path.of(args[i]);
            }
            return new Options(input, limit, dumpDecompressed, dumpZstd);
        }
    }
}
