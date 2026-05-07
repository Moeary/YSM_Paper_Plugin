package com.ysm.paper.nativebridge.crypto;

import io.airlift.compress.zstd.ZstdInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class YsmArchiveProbe {
    private static final int YSGP_MAGIC = 0x50475359;
    private static final int V3_CRYPTO = 3;
    private static final int TAIL_SIZE = 64;
    private static final Pattern FORMAT_PATTERN = Pattern.compile("<format>\\s*(\\d+)");

    private YsmArchiveProbe() {
    }

    public static Result probe(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length < 8) {
            throw new IllegalArgumentException("file is too short");
        }

        if (hasV3Magic(bytes)) {
            return extractV3(file, bytes).result();
        }

        if (LittleEndian.readInt(bytes, 0) == YSGP_MAGIC) {
            int crypto = readBigEndianInt(bytes, 4);
            return new Result(file, bytes.length, "V" + crypto, crypto, false, false, false,
                    0, 0, 0, 0, 0, 0, false, "", "", YsmModelProfile.EMPTY);
        }

        throw new IllegalArgumentException("unsupported YSM magic");
    }

    public static ExtractedV3Archive extractV3(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        if (!hasV3Magic(bytes)) {
            throw new IllegalArgumentException("not a V3 YSM archive");
        }
        return extractV3(file, bytes);
    }

    private static ExtractedV3Archive extractV3(Path file, byte[] bytes) {
        int headerEnd = findNull(bytes, 0);
        if (headerEnd < 0) {
            throw new IllegalArgumentException("V3 header terminator not found");
        }
        if (bytes.length < headerEnd + 1 + Integer.BYTES + TAIL_SIZE) {
            throw new IllegalArgumentException("V3 file is too short for body and key tail");
        }

        String header = new String(bytes, 0, headerEnd, StandardCharsets.UTF_8);
        int format = extractFormat(header);
        int bodyOffset = headerEnd + 1;
        int crypto = LittleEndian.readInt(bytes, bodyOffset);
        if (crypto != V3_CRYPTO) {
            throw new IllegalArgumentException("unexpected V3 crypto marker: " + crypto);
        }

        long expectedHash = LittleEndian.readLong(bytes, bytes.length - Long.BYTES);
        long actualHash = CityHash64.hashWithSeed(bytes, 0, bytes.length - Long.BYTES, YsmSeeds.FILE_VERIFICATION);
        boolean fileHashOk = actualHash == expectedHash;
        if (!fileHashOk) {
            throw new IllegalArgumentException("file hash mismatch");
        }

        byte[] runtimeKey = Arrays.copyOfRange(bytes, bytes.length - TAIL_SIZE, bytes.length - Long.BYTES);
        byte[] encryptedBody = Arrays.copyOfRange(bytes, bodyOffset + Integer.BYTES, bytes.length - TAIL_SIZE);
        byte[] chacha = YsmCrypto.modifiedChaCha(encryptedBody, runtimeKey, YsmSeeds.RES_VERIFICATION, false);
        byte[] xored = YsmCrypto.mt19937Xor(chacha, runtimeKey);
        if (xored.length < 2) {
            throw new IllegalArgumentException("decrypted body is too short");
        }

        int padding = ((xored[0] & 0xff) | ((xored[1] & 0xff) << 8)) & 0x3ff;
        int payloadOffset = 2 + padding;
        if (payloadOffset > xored.length) {
            throw new IllegalArgumentException("decrypted padding exceeds body");
        }

        byte[] ysmZstd = Arrays.copyOfRange(xored, payloadOffset, xored.length);
        ZstdWashResult zstd = washYsmZstd(ysmZstd);
        byte[] decompressed = decompressZstd(zstd.data());
        if (decompressed.length < Integer.BYTES) {
            throw new IllegalArgumentException("decompressed payload is too short");
        }

        int payloadFormat = LittleEndian.readInt(decompressed, 0);
        if (payloadFormat != format) {
            throw new IllegalArgumentException("decompressed format mismatch: " + payloadFormat + " != " + format);
        }
        YsmV3PayloadScanner.ScanResult scan = YsmV3PayloadScanner.scan(decompressed, format);

        Result result = new Result(file, bytes.length, "V3", format, true, true, zstd.complete(),
                encryptedBody.length, ysmZstd.length, zstd.blocks(), decompressed.length,
                scan.parsedBytes(), scan.trailingBytes(), scan.consumedAll(), scan.compact(), scan.modelHash(), scan.profile());
        return new ExtractedV3Archive(result, zstd.data(), decompressed);
    }

    private static boolean hasV3Magic(byte[] bytes) {
        return bytes.length >= 7
                && (bytes[0] & 0xff) == 0xef
                && (bytes[1] & 0xff) == 0xbb
                && (bytes[2] & 0xff) == 0xbf
                && LittleEndian.readInt(bytes, 3) == YSGP_MAGIC;
    }

    private static int findNull(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int extractFormat(String header) {
        Matcher matcher = FORMAT_PATTERN.matcher(header);
        if (!matcher.find()) {
            throw new IllegalArgumentException("format tag not found");
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static ZstdWashResult washYsmZstd(byte[] compressed) {
        if (compressed.length < 5) {
            throw new IllegalArgumentException("YSM zstd payload is too short");
        }

        byte[] data = Arrays.copyOf(compressed, compressed.length);
        int magic = LittleEndian.readInt(data, 0);
        if (magic != 0xfd2fb528) {
            throw new IllegalArgumentException("YSM zstd magic mismatch");
        }

        int fhd = data[4] & 0xff;
        data[4] = (byte) (fhd & 0xfb);
        int offset = 4 + calculateFrameHeaderSize(fhd);
        int blocks = 0;
        boolean complete = false;

        while (offset + 3 <= data.length) {
            int b0 = data[offset] & 0xff;
            int b1 = data[offset + 1] & 0xff;
            int b2 = data[offset + 2] & 0xff;

            int lastBlock = (b0 >>> 7) & 1;
            int ysmType = (b0 >>> 5) & 3;
            int rawSize = ((b0 & 0x1f) << 16) | b1 | (b2 << 8);
            int compressedSize = rawSize ^ 0xd4e9;
            int standardType = switch (ysmType) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 0;
                default -> throw new IllegalStateException("unreachable block type");
            };

            int standardHeader = lastBlock | (standardType << 1) | (compressedSize << 3);
            data[offset] = (byte) standardHeader;
            data[offset + 1] = (byte) (standardHeader >>> 8);
            data[offset + 2] = (byte) (standardHeader >>> 16);

            int blockDataSize = standardType == 1 ? 1 : compressedSize;
            offset += 3 + blockDataSize;
            blocks++;

            if (offset > data.length) {
                throw new IllegalArgumentException("YSM zstd block exceeds payload");
            }
            if (lastBlock == 1) {
                complete = true;
                break;
            }
        }

        if (!complete) {
            throw new IllegalArgumentException("YSM zstd frame did not reach last block");
        }
        return new ZstdWashResult(data, blocks, true);
    }

    private static byte[] decompressZstd(byte[] data) {
        try (ZstdInputStream input = new ZstdInputStream(new ByteArrayInputStream(data))) {
            return input.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalArgumentException("Zstd decompress failed: " + ex.getMessage(), ex);
        }
    }

    private static int calculateFrameHeaderSize(int fhd) {
        boolean singleSegment = ((fhd >>> 5) & 1) == 1;

        int dictIdBits = fhd & 3;
        int dictIdSize = switch (dictIdBits) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            case 3 -> 4;
            default -> throw new IllegalStateException("unreachable dict id size");
        };

        int fcsBits = (fhd >>> 6) & 3;
        int fcsSize = switch (fcsBits) {
            case 0 -> singleSegment ? 1 : 0;
            case 1 -> 2;
            case 2 -> 4;
            case 3 -> 8;
            default -> throw new IllegalStateException("unreachable fcs size");
        };

        int windowDescriptorSize = singleSegment ? 0 : 1;
        return 1 + windowDescriptorSize + dictIdSize + fcsSize;
    }

    public record Result(
            Path file,
            long size,
            String version,
            int format,
            boolean fileHashOk,
            boolean decryptOk,
            boolean zstdFrameOk,
            int encryptedBytes,
            int zstdBytes,
            int zstdBlocks,
            int decompressedBytes,
            int payloadParsedBytes,
            int payloadTrailingBytes,
            boolean payloadConsumedAll,
            String payloadSummary,
            String modelHash,
            YsmModelProfile profile) {
    }

    public record ExtractedV3Archive(Result result, byte[] washedZstd, byte[] decompressed) {
    }

    private record ZstdWashResult(byte[] data, int blocks, boolean complete) {
    }
}
