package com.ysm.paper.openysm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class YsmLegacyArchiveExtractor {
    private static final int YSGP_MAGIC = 0x59534750;
    private static final int VERSION_V1 = 1;
    private static final int VERSION_V2 = 2;

    private YsmLegacyArchiveExtractor() {
    }

    public static ExtractedLegacyArchive extractToDirectory(Path sourceFile, Path tempRoot) throws IOException {
        byte[] bytes = Files.readAllBytes(sourceFile);
        if (bytes.length < 24) {
            throw new IllegalArgumentException("legacy YSM file is too short");
        }
        int magic = readBigEndianInt(bytes, 0);
        int version = readBigEndianInt(bytes, 4);
        if (magic != YSGP_MAGIC || (version != VERSION_V1 && version != VERSION_V2)) {
            throw new IllegalArgumentException("not a V1/V2 YSM archive");
        }

        byte[] expectedMd5 = Arrays.copyOfRange(bytes, 8, 24);
        byte[] body = Arrays.copyOfRange(bytes, 24, bytes.length);
        byte[] actualMd5 = digest("MD5", body);
        if (!Arrays.equals(expectedMd5, actualMd5)) {
            throw new IllegalArgumentException("legacy YSM body md5 mismatch");
        }

        Files.createDirectories(tempRoot);
        Path tempDir = Files.createTempDirectory(tempRoot, "legacy-ysm-");
        boolean complete = false;
        try {
            Map<String, byte[]> resources = extractResources(body, version);
            resources.computeIfAbsent("ysm.json", ignored -> synthesizeLegacyYsmJson(sourceFile, resources));
            for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
                Path output = safeResolve(tempDir, entry.getKey());
                Files.createDirectories(output.getParent());
                Files.write(output, entry.getValue());
            }
            complete = true;
            return new ExtractedLegacyArchive(version, tempDir, resources.size(), totalBytes(resources));
        } finally {
            if (!complete) {
                deleteTree(tempDir);
            }
        }
    }

    private static Map<String, byte[]> extractResources(byte[] body, int version) {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        int offset = 0;
        while (offset < body.length) {
            EntryResult result = version == VERSION_V1
                    ? readV1Entry(body, offset)
                    : readV2Entry(body, offset);
            resources.put(result.name(), result.data());
            offset = result.nextOffset();
        }
        return resources;
    }

    private static byte[] synthesizeLegacyYsmJson(Path sourceFile, Map<String, byte[]> resources) {
        String mainModel = firstExisting(resources, "main.json");
        String armModel = firstExisting(resources, "arm.json");
        StringBuilder json = new StringBuilder(512);
        json.append("{\"metadata\":{\"name\":\"")
                .append(jsonEscape(stripExtension(sourceFile.getFileName().toString())))
                .append("\"},\"properties\":{\"default_texture\":\"")
                .append(jsonEscape(defaultLegacyTexture(resources)))
                .append("\"},\"files\":{\"player\":{");

        boolean wroteSection = false;
        if (mainModel != null || armModel != null) {
            json.append("\"model\":{");
            boolean wroteModel = false;
            if (mainModel != null) {
                json.append("\"main\":\"").append(jsonEscape(mainModel)).append("\"");
                wroteModel = true;
            }
            if (armModel != null) {
                if (wroteModel) {
                    json.append(',');
                }
                json.append("\"arm\":\"").append(jsonEscape(armModel)).append("\"");
            }
            json.append('}');
            wroteSection = true;
        }

        List<String> textures = resources.keySet().stream()
                .filter(YsmLegacyArchiveExtractor::isTextureResource)
                .sorted()
                .toList();
        if (!textures.isEmpty()) {
            if (wroteSection) {
                json.append(',');
            }
            json.append("\"texture\":[");
            for (int i = 0; i < textures.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(jsonEscape(textures.get(i))).append('"');
            }
            json.append(']');
            wroteSection = true;
        }

        Map<String, String> animations = legacyAnimationFiles(resources);
        if (!animations.isEmpty()) {
            if (wroteSection) {
                json.append(',');
            }
            json.append("\"animation\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : animations.entrySet()) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(jsonEscape(entry.getKey())).append("\":\"")
                        .append(jsonEscape(entry.getValue())).append('"');
            }
            json.append('}');
            wroteSection = true;
        }

        List<String> controllers = resources.keySet().stream()
                .filter(name -> name.endsWith(".animation_controllers.json")
                        || name.endsWith(".animation_controller.json"))
                .sorted()
                .toList();
        if (!controllers.isEmpty()) {
            if (wroteSection) {
                json.append(',');
            }
            json.append("\"animation_controllers\":[");
            for (int i = 0; i < controllers.size(); i++) {
                if (i > 0) {
                    json.append(',');
                }
                json.append('"').append(jsonEscape(controllers.get(i))).append('"');
            }
            json.append(']');
        }

        json.append("}}}");
        return json.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, String> legacyAnimationFiles(Map<String, byte[]> resources) {
        Map<String, String> animations = new LinkedHashMap<>();
        resources.keySet().stream()
                .filter(name -> name.endsWith(".animation.json"))
                .sorted()
                .forEach(name -> {
                    String key = name.substring(0, name.length() - ".animation.json".length());
                    int slash = Math.max(key.lastIndexOf('/'), key.lastIndexOf('\\'));
                    if (slash >= 0) {
                        key = key.substring(slash + 1);
                    }
                    animations.put(key, name);
                });
        return animations;
    }

    private static String defaultLegacyTexture(Map<String, byte[]> resources) {
        return resources.keySet().stream()
                .filter(YsmLegacyArchiveExtractor::isTextureResource)
                .sorted()
                .map(YsmLegacyArchiveExtractor::stripExtension)
                .findFirst()
                .orElse("default");
    }

    private static boolean isTextureResource(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".png")
                || lower.endsWith(".jpg")
                || lower.endsWith(".jpeg")
                || lower.endsWith(".bmp")
                || lower.endsWith(".webp")
                || lower.endsWith(".avif");
    }

    private static String firstExisting(Map<String, byte[]> resources, String name) {
        return resources.containsKey(name) ? name : null;
    }

    private static String stripExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String fileName = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static EntryResult readV1Entry(byte[] body, int offset) {
        int nameLength = readBigEndianInt(body, offset);
        offset += Integer.BYTES;
        String name = new String(readRange(body, offset, nameLength), StandardCharsets.UTF_8);
        offset += nameLength;
        int encryptedLength = readBigEndianInt(body, offset);
        offset += Integer.BYTES;
        byte[] key = readRange(body, offset, 16);
        offset += 16;
        byte[] iv = readRange(body, offset, 16);
        offset += 16;
        byte[] encrypted = readRange(body, offset, encryptedLength);
        offset += encryptedLength;
        byte[] decrypted = aesCbcDecrypt(key, iv, encrypted);
        return new EntryResult(name, inflate(decrypted), offset);
    }

    private static EntryResult readV2Entry(byte[] body, int offset) {
        int encodedNameLength = readBigEndianInt(body, offset);
        offset += Integer.BYTES;
        byte[] encodedName = readRange(body, offset, encodedNameLength);
        offset += encodedNameLength;
        String name = new String(Base64.getDecoder().decode(encodedName), StandardCharsets.UTF_8);
        int encryptedLength = readBigEndianInt(body, offset);
        offset += Integer.BYTES;
        int encryptedKeyLength = readBigEndianInt(body, offset);
        offset += Integer.BYTES;
        byte[] encryptedKey = readRange(body, offset, encryptedKeyLength);
        offset += encryptedKeyLength;
        byte[] iv = readRange(body, offset, 16);
        offset += 16;
        byte[] encrypted = readRange(body, offset, encryptedLength);
        offset += encryptedLength;
        byte[] key = aesCbcDecrypt(legacyRandomKey(encrypted), iv, encryptedKey);
        byte[] decrypted = aesCbcDecrypt(key, iv, encrypted);
        return new EntryResult(name, inflate(decrypted), offset);
    }

    private static byte[] legacyRandomKey(byte[] encryptedData) {
        byte[] md5 = digest("MD5", encryptedData);
        long seed = 0L;
        for (byte b : md5) {
            seed = (seed << 8) + (b & 0xffL);
        }
        byte[] key = new byte[16];
        new Random(seed).nextBytes(key);
        return key;
    }

    private static byte[] aesCbcDecrypt(byte[] key, byte[] iv, byte[] encrypted) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher.doFinal(encrypted);
        } catch (Exception ex) {
            throw new IllegalArgumentException("legacy AES decrypt failed: " + ex.getMessage(), ex);
        }
    }

    private static byte[] inflate(byte[] compressed) {
        Inflater inflater = new Inflater();
        inflater.setInput(compressed);
        byte[] buffer = new byte[8192];
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, compressed.length * 2))) {
            while (!inflater.finished()) {
                int read = inflater.inflate(buffer);
                if (read == 0) {
                    if (inflater.needsInput()) {
                        break;
                    }
                    throw new IllegalArgumentException("legacy zlib inflate made no progress");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } catch (IOException | DataFormatException ex) {
            throw new IllegalArgumentException("legacy zlib inflate failed: " + ex.getMessage(), ex);
        } finally {
            inflater.end();
        }
    }

    private static Path safeResolve(Path root, String relativeName) {
        String normalizedName = relativeName.replace('\\', '/');
        Path relative = Path.of(normalizedName);
        if (relative.isAbsolute()) {
            throw new IllegalArgumentException("legacy resource path is absolute: " + relativeName);
        }
        Path output = root.resolve(relative).normalize();
        if (!output.startsWith(root)) {
            throw new IllegalArgumentException("legacy resource path escapes archive root: " + relativeName);
        }
        return output;
    }

    private static byte[] readRange(byte[] bytes, int offset, int length) {
        if (length < 0 || offset < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("legacy archive entry exceeds file bounds");
        }
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    private static int readBigEndianInt(byte[] bytes, int offset) {
        if (offset + Integer.BYTES > bytes.length) {
            throw new IllegalArgumentException("legacy int exceeds file bounds");
        }
        return ((bytes[offset] & 0xff) << 24)
                | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8)
                | (bytes[offset + 3] & 0xff);
    }

    private static long totalBytes(Map<String, byte[]> resources) {
        long total = 0L;
        for (byte[] bytes : resources.values()) {
            total += bytes.length;
        }
        return total;
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (Exception ex) {
            throw new IllegalStateException(algorithm + " is not available", ex);
        }
    }

    private static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private record EntryResult(String name, byte[] data, int nextOffset) {
    }

    public record ExtractedLegacyArchive(int version, Path directory, int resourceCount, long resourceBytes)
            implements AutoCloseable {
        @Override
        public void close() {
            deleteTree(directory);
        }
    }
}
