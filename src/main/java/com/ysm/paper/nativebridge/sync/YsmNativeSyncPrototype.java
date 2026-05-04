package com.ysm.paper.nativebridge.sync;

import com.ysm.paper.nativebridge.crypto.YsmRawPacketCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class YsmNativeSyncPrototype {
    public static final String MODE_ZERO = "zero";
    public static final String MODE_SEQUENCE = "sequence";
    public static final String MODE_ONES = "ones";
    public static final String MODE_ASCENDING = "ascending";
    public static final String MODE_DESCENDING = "descending";
    public static final String MODE_PROTOCOL = "protocol";
    public static final String MODE_YSM = "ysm";
    public static final String MODE_FABRIC_PRODUCTION = "fabric-production";
    public static final String MODE_FABRIC_PROTOCOL = "fabric-protocol";
    public static final String MODE_YSMS0 = "ysms0";
    public static final String MODE_YSMS1 = "ysms1";
    public static final String MODE_YSMS2 = "ysms2";
    public static final String MODE_YSMS0_MARKED = "ysms0-marked";
    public static final String MODE_YSMS1_MARKED = "ysms1-marked";
    public static final String MODE_2YSMC1 = "2ysmc1";
    public static final String MODE_YSM_CORE = "ysm-core";
    public static final String MODE_YSM_CORE_LIB = "ysm-core-lib";
    public static final List<String> MODES = List.of(
            MODE_ZERO,
            MODE_SEQUENCE,
            MODE_ONES,
            MODE_ASCENDING,
            MODE_DESCENDING,
            MODE_PROTOCOL,
            MODE_YSM,
            MODE_FABRIC_PRODUCTION,
            MODE_FABRIC_PROTOCOL,
            MODE_YSMS0,
            MODE_YSMS1,
            MODE_YSMS2,
            MODE_YSMS0_MARKED,
            MODE_YSMS1_MARKED,
            MODE_2YSMC1,
            MODE_YSM_CORE,
            MODE_YSM_CORE_LIB);

    public static final String VARIANT_FULL = "full";
    public static final String VARIANT_BODY = "body";
    public static final String VARIANT_ENCRYPTED_TRAILER = "encrypted-trailer";
    public static final String VARIANT_TYPE2_FULL = "type2-full";
    public static final String VARIANT_TYPE2_BODY = "type2-body";
    public static final List<String> VARIANTS = List.of(
            VARIANT_FULL,
            VARIANT_BODY,
            VARIANT_ENCRYPTED_TRAILER,
            VARIANT_TYPE2_FULL,
            VARIANT_TYPE2_BODY);

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();
    private static final int FREESIA_TYPE1_BODY_PADDING_BYTES = 15;
    private static final int FREESIA_TYPE2_BODY_PADDING_BYTES = 14;

    private YsmNativeSyncPrototype() {
    }

    public static BootstrapPacket createBootstrap(String mode, String variant) {
        return createBootstrap(mode, variant, 0);
    }

    public static BootstrapPacket createBootstrap(String mode, String variant, int paddingBytes) {
        byte[] bootstrapKey = bootstrapTransportKey(mode);
        byte[] advertisedKey = randomKey();
        byte[] nextTransportKey = randomKey();
        String normalizedVariant = normalizeVariant(variant);
        int normalizedPaddingBytes = normalizePaddingBytes(paddingBytes);
        byte[] rawBody = encodeBootstrapRawBody(
                bootstrapKey,
                advertisedKey,
                nextTransportKey,
                normalizedVariant,
                normalizedPaddingBytes);
        return new BootstrapPacket(
                normalizeMode(mode),
                normalizedVariant,
                normalizedPaddingBytes,
                bootstrapKey,
                advertisedKey,
                nextTransportKey,
                rawBody,
                Instant.now());
    }

    public static byte[] encodeBootstrapRawBody(
            byte[] bootstrapKey,
            byte[] advertisedKey,
            byte[] nextTransportKey) {
        return encodeBootstrapRawBody(bootstrapKey, advertisedKey, nextTransportKey, VARIANT_FULL);
    }

    public static byte[] encodeBootstrapRawBody(
            byte[] bootstrapKey,
            byte[] advertisedKey,
            byte[] nextTransportKey,
            String variant) {
        return encodeBootstrapRawBody(bootstrapKey, advertisedKey, nextTransportKey, variant, 0);
    }

    public static byte[] encodeBootstrapRawBody(
            byte[] bootstrapKey,
            byte[] advertisedKey,
            byte[] nextTransportKey,
            String variant,
            int paddingBytes) {
        String normalizedVariant = normalizeVariant(variant);
        byte[] padding = padding(normalizePaddingBytes(paddingBytes));
        byte[] plain = switch (normalizedVariant) {
            case VARIANT_TYPE2_FULL, VARIANT_TYPE2_BODY ->
                    YsmRawPacketCodec.encodePlainType2(0L, advertisedKey, padding);
            default -> YsmRawPacketCodec.encodePlainType1(advertisedKey, padding);
        };

        return switch (normalizedVariant) {
            case VARIANT_BODY, VARIANT_TYPE2_BODY -> YsmRawPacketCodec.encryptBodyOnly(plain, bootstrapKey);
            case VARIANT_ENCRYPTED_TRAILER ->
                    YsmRawPacketCodec.encryptIncludingPlainTrailer(plain, bootstrapKey, nextTransportKey);
            case VARIANT_FULL, VARIANT_TYPE2_FULL ->
                    YsmRawPacketCodec.encryptWithNextKey(plain, bootstrapKey, nextTransportKey);
            default -> throw new IllegalArgumentException("Unsupported native bootstrap variant: " + variant);
        };
    }

    public static List<ProbeSpec> quickProbeSpecs() {
        List<ProbeSpec> specs = new ArrayList<>();
        for (String mode : List.of(
                MODE_ZERO,
                MODE_SEQUENCE,
                MODE_ASCENDING,
                MODE_PROTOCOL,
                MODE_FABRIC_PRODUCTION,
                MODE_FABRIC_PROTOCOL,
                MODE_YSMS0,
                MODE_YSMS1,
                MODE_YSMS2,
                MODE_YSMS0_MARKED,
                MODE_2YSMC1,
                MODE_YSM_CORE)) {
            specs.add(new ProbeSpec(mode, VARIANT_BODY, FREESIA_TYPE1_BODY_PADDING_BYTES));
            specs.add(new ProbeSpec(mode, VARIANT_TYPE2_BODY, FREESIA_TYPE2_BODY_PADDING_BYTES));
            for (String variant : List.of(VARIANT_FULL, VARIANT_BODY, VARIANT_ENCRYPTED_TRAILER, VARIANT_TYPE2_FULL)) {
                specs.add(new ProbeSpec(mode, variant, 0));
            }
        }
        return List.copyOf(specs);
    }

    public static List<ProbeSpec> fullProbeSpecs() {
        List<ProbeSpec> specs = new ArrayList<>();
        for (String mode : MODES) {
            for (String variant : VARIANTS) {
                specs.add(new ProbeSpec(mode, variant, 0));
            }
            specs.add(new ProbeSpec(mode, VARIANT_BODY, FREESIA_TYPE1_BODY_PADDING_BYTES));
            specs.add(new ProbeSpec(mode, VARIANT_TYPE2_BODY, FREESIA_TYPE2_BODY_PADDING_BYTES));
        }
        return List.copyOf(specs);
    }

    public static byte[] deterministicKey(String purpose, String labelA, String labelB) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] input = ("PaperYSM native probe/" + stableLabel(purpose)
                    + "/" + stableLabel(labelA)
                    + "/" + stableLabel(labelB)).getBytes(StandardCharsets.UTF_8);
            return Arrays.copyOf(digest.digest(input), YsmRawPacketCodec.KEY_BYTES);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-512 is not available", ex);
        }
    }

    public static BootstrapPacket createDeterministicProbe(String mode, String variant) {
        return createDeterministicProbe(mode, variant, 0);
    }

    public static BootstrapPacket createDeterministicProbe(String mode, String variant, int paddingBytes) {
        String normalizedMode = normalizeMode(mode);
        String normalizedVariant = normalizeVariant(variant);
        int normalizedPaddingBytes = normalizePaddingBytes(paddingBytes);
        byte[] bootstrapKey = bootstrapTransportKey(normalizedMode);
        String variantAndPadding = normalizedVariant + "/padding=" + normalizedPaddingBytes;
        byte[] advertisedKey = deterministicKey("advertised", normalizedMode, variantAndPadding);
        byte[] nextTransportKey = deterministicKey("next", normalizedMode, variantAndPadding);
        byte[] rawBody = encodeBootstrapRawBody(
                bootstrapKey,
                advertisedKey,
                nextTransportKey,
                normalizedVariant,
                normalizedPaddingBytes);
        return new BootstrapPacket(
                normalizedMode,
                normalizedVariant,
                normalizedPaddingBytes,
                bootstrapKey,
                advertisedKey,
                nextTransportKey,
                rawBody,
                Instant.now());
    }

    public static byte[] encodeLegacyBootstrapRawBody(
            byte[] bootstrapKey,
            byte[] advertisedKey,
            byte[] nextTransportKey) {
        byte[] plain = YsmRawPacketCodec.encodePlainType1(advertisedKey, new byte[0]);
        return YsmRawPacketCodec.encryptWithNextKey(plain, bootstrapKey, nextTransportKey);
    }

    public static byte[] bootstrapTransportKey(String mode) {
        String normalized = normalizeMode(mode);
        byte[] key = new byte[YsmRawPacketCodec.KEY_BYTES];
        if (MODE_ZERO.equals(normalized)) {
            return key;
        }
        if (MODE_SEQUENCE.equals(normalized)) {
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (i * 7 + 3);
            }
            return key;
        }
        if (MODE_ONES.equals(normalized)) {
            Arrays.fill(key, (byte) 0xff);
            return key;
        }
        if (MODE_ASCENDING.equals(normalized)) {
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) i;
            }
            return key;
        }
        if (MODE_DESCENDING.equals(normalized)) {
            for (int i = 0; i < key.length; i++) {
                key[i] = (byte) (key.length - 1 - i);
            }
            return key;
        }
        if (MODE_PROTOCOL.equals(normalized)) {
            return hashKey("yes_steve_model:2_6_0/2.6.0");
        }
        if (MODE_YSM.equals(normalized)) {
            return hashKey("Yes Steve Model native sync");
        }
        if (MODE_FABRIC_PRODUCTION.equals(normalized)) {
            return hashKey("YSM_FABRIC_1210_PRODUCTION");
        }
        if (MODE_FABRIC_PROTOCOL.equals(normalized)) {
            return hashKey("YSM_FABRIC_1210_PRODUCTION/yes_steve_model:2_6_0/2.6.0");
        }
        if (MODE_YSMS0.equals(normalized)) {
            return hashKey("YSMS0");
        }
        if (MODE_YSMS1.equals(normalized)) {
            return hashKey("YSMS1");
        }
        if (MODE_YSMS2.equals(normalized)) {
            return hashKey("YSMS2");
        }
        if (MODE_YSMS0_MARKED.equals(normalized)) {
            return hashKey("@YSMS0");
        }
        if (MODE_YSMS1_MARKED.equals(normalized)) {
            return hashKey("`YSMS1");
        }
        if (MODE_2YSMC1.equals(normalized)) {
            return hashKey("2YsmC1");
        }
        if (MODE_YSM_CORE.equals(normalized)) {
            return hashKey("ysm-core.dll");
        }
        if (MODE_YSM_CORE_LIB.equals(normalized)) {
            return hashKey("YSM_CORE_LIB");
        }
        throw new IllegalArgumentException("Unsupported native bootstrap mode: " + mode);
    }

    public static Optional<DecodedPacket> tryDecode(byte[] rawBody, List<KeyCandidate> candidates) {
        for (KeyCandidate candidate : candidates) {
            Optional<DecodedPacket> decoded = tryDecode(rawBody, candidate);
            if (decoded.isPresent()) {
                return decoded;
            }
        }
        return Optional.empty();
    }

    public static Optional<DecodedPacket> tryDecode(byte[] rawBody, KeyCandidate candidate) {
        try {
            YsmRawPacketCodec.WirePacket wire = YsmRawPacketCodec.decryptWithNextKey(rawBody, candidate.key());
            if (isPlausible(wire.plain())) {
                return Optional.of(new DecodedPacket(
                        candidate.name(),
                        wire.plain(),
                        wire.nextTransportKey(),
                        true));
            }
        } catch (RuntimeException ignored) {
            // Fall through to the body-only variant for old captures and partial probes.
        }

        try {
            YsmRawPacketCodec.PlainPacket plain = YsmRawPacketCodec.decryptBodyOnly(rawBody, candidate.key());
            if (isPlausible(plain)) {
                return Optional.of(new DecodedPacket(
                        candidate.name(),
                        plain,
                        null,
                        false));
            }
        } catch (RuntimeException ignored) {
            // The caller logs one compact failure line after all candidates are exhausted.
        }
        return Optional.empty();
    }

    public static String keyPreview(byte[] key) {
        if (key == null) {
            return "none";
        }
        return HEX.formatHex(Arrays.copyOf(key, Math.min(8, key.length)));
    }

    public static byte[] randomKey() {
        byte[] key = new byte[YsmRawPacketCodec.KEY_BYTES];
        RANDOM.nextBytes(key);
        return key;
    }

    public static String normalizeMode(String mode) {
        String normalized = mode == null ? MODE_ZERO : mode.toLowerCase(Locale.ROOT);
        if (!MODES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported native bootstrap mode: " + mode
                    + " (expected " + String.join("|", MODES) + ")");
        }
        return normalized;
    }

    public static String normalizeVariant(String variant) {
        String normalized = variant == null ? VARIANT_FULL : variant.toLowerCase(Locale.ROOT);
        if (!VARIANTS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported native bootstrap variant: " + variant
                    + " (expected " + String.join("|", VARIANTS) + ")");
        }
        return normalized;
    }

    public static int normalizePaddingBytes(int paddingBytes) {
        if (paddingBytes < 0 || paddingBytes > 0x7f) {
            throw new IllegalArgumentException("Native packet padding must be between 0 and 127 bytes: " + paddingBytes);
        }
        return paddingBytes;
    }

    private static byte[] hashKey(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            return Arrays.copyOf(digest.digest(value.getBytes(StandardCharsets.UTF_8)), YsmRawPacketCodec.KEY_BYTES);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-512 is not available", ex);
        }
    }

    private static String stableLabel(String value) {
        return value == null ? "default" : value.toLowerCase(Locale.ROOT);
    }

    private static byte[] padding(int paddingBytes) {
        byte[] out = new byte[normalizePaddingBytes(paddingBytes)];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * 31 + 17);
        }
        return out;
    }

    private static boolean isPlausible(YsmRawPacketCodec.PlainPacket packet) {
        boolean selectedKeyLooksValid = packet.selectedKey()
                .map(key -> key.length == YsmRawPacketCodec.KEY_BYTES)
                .orElse(true);
        if (!selectedKeyLooksValid) {
            return false;
        }
        return switch (packet.type()) {
            case 1 -> packet.body().length == YsmRawPacketCodec.KEY_BYTES;
            case 2 -> packet.body().length >= YsmRawPacketCodec.KEY_BYTES + 1
                    && packet.body().length <= YsmRawPacketCodec.KEY_BYTES + 10;
            case 3 -> packet.body().length >= 1 + 0x1c + 0x1c + YsmRawPacketCodec.KEY_BYTES;
            case 4 -> packet.body().length <= 10;
            default -> false;
        };
    }

    public record BootstrapPacket(
            String mode,
            String variant,
            int paddingBytes,
            byte[] bootstrapKey,
            byte[] advertisedKey,
            byte[] nextTransportKey,
            byte[] rawBody,
            Instant createdAt) {
    }

    public record KeyCandidate(String name, byte[] key) {
        public KeyCandidate {
            if (key == null || key.length != YsmRawPacketCodec.KEY_BYTES) {
                throw new IllegalArgumentException("Native key candidate must be 56 bytes: " + name);
            }
        }
    }

    public record DecodedPacket(
            String keyName,
            YsmRawPacketCodec.PlainPacket packet,
            byte[] nextTransportKey,
            boolean usedNextKeyTrailer) {
    }

    public record ProbeSpec(String mode, String variant, int paddingBytes) {
        public ProbeSpec(String mode, String variant) {
            this(mode, variant, 0);
        }

        public ProbeSpec {
            mode = normalizeMode(mode);
            variant = normalizeVariant(variant);
            paddingBytes = normalizePaddingBytes(paddingBytes);
        }

        public ProbeSpec withPadding(int paddingBytes) {
            return new ProbeSpec(mode, variant, paddingBytes);
        }
    }
}
