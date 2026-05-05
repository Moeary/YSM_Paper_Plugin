package com.ysm.paper.nativebridge.crypto;

import java.util.ArrayList;
import java.util.List;

final class YsmV3PayloadScanner {
    private static final int FACE_BYTES = 92;

    private YsmV3PayloadScanner() {
    }

    static ScanResult scan(byte[] decompressed, int expectedFormat) {
        YsmByteReader reader = new YsmByteReader(decompressed);
        int format = reader.readInt();
        if (format != expectedFormat) {
            throw new IllegalArgumentException("payload format mismatch: " + format + " != " + expectedFormat);
        }

        Counts counts = new Counts();
        if (format < 4) {
            scanLegacyV1(reader, format, counts);
        } else if (format <= 15) {
            scanLegacyV15(reader, format, counts);
        } else {
            scanModern(reader, format, counts);
        }
        return counts.toResult(reader.position(), reader.size());
    }

    private static void scanLegacyV1(YsmByteReader reader, int format, Counts counts) {
        reader.skip(reader.readVarIntAsInt());

        int modelCount = reader.readVarIntAsInt();
        for (int i = 0; i < modelCount; i++) {
            reader.readVarInt();
            reader.readVarInt();
            skipModel(reader, format, counts);
        }

        int animationBlobCount = reader.readVarIntAsInt();
        for (int i = 0; i < animationBlobCount; i++) {
            reader.readVarInt();
            reader.readVarInt();
            skipAnimations(reader, format, counts);
            counts.animationFiles++;
        }

        int textureCount = reader.readVarIntAsInt();
        for (int i = 0; i < textureCount; i++) {
            reader.readString();
            if (format < 4) {
                reader.readVarInt();
            }
            reader.readByteSequence();
            reader.readVarInt();
            reader.readVarInt();
            counts.textures++;
        }

        skipIdStringTable(reader);
        skipIdStringTable(reader);
        int textureTableSize = reader.readVarIntAsInt();
        for (int i = 0; i < textureTableSize; i++) {
            reader.readString();
            reader.readString();
        }
    }

    private static void scanLegacyV15(YsmByteReader reader, int format, Counts counts) {
        reader.skip(reader.readVarIntAsInt());

        int modelCount = reader.readVarIntAsInt();
        for (int i = 0; i < modelCount; i++) {
            reader.readVarInt();
            reader.readVarInt();
            skipModel(reader, format, counts);
        }

        int animationBlobCount = reader.readVarIntAsInt();
        for (int i = 0; i < animationBlobCount; i++) {
            reader.readVarInt();
            reader.readVarInt();
            skipAnimations(reader, format, counts);
            counts.animationFiles++;
        }

        if (format > 9) {
            skipAnimationControllers(reader, format, counts);
            int animationControllerTableSize = reader.readVarIntAsInt();
            for (int i = 0; i < animationControllerTableSize; i++) {
                reader.readString();
                reader.readString();
            }
        }

        int customTextureCount = reader.readVarIntAsInt();
        for (int i = 0; i < customTextureCount; i++) {
            reader.readString();
            reader.readByteSequence();
            reader.readVarInt();
            reader.readVarInt();
            counts.textures++;

            int subTextureSize = reader.readVarIntAsInt();
            for (int j = 0; j < subTextureSize; j++) {
                reader.readVarInt();
                reader.readByteSequence();
                reader.readVarInt();
                reader.readVarInt();
                counts.specialImages++;
            }
        }

        if (format > 9) {
            skipSoundFiles(reader, format, counts);
            int soundTableCount = reader.readVarIntAsInt();
            for (int i = 0; i < soundTableCount; i++) {
                reader.readString();
                reader.readString();
            }
        }

        int extraTextureCount = reader.readVarIntAsInt();
        for (int i = 0; i < extraTextureCount; i++) {
            reader.readString();
            reader.readByteSequence();
            reader.readVarInt();
            reader.readVarInt();
            counts.avatars++;
        }

        skipIdStringTable(reader);
        skipIdStringTable(reader);
        int textureTableSize = reader.readVarIntAsInt();
        for (int i = 0; i < textureTableSize; i++) {
            reader.readString();
            reader.readString();
            int subTextureSize = reader.readVarIntAsInt();
            for (int j = 0; j < subTextureSize; j++) {
                reader.readVarInt();
                reader.readString();
            }
        }

        skipYsmJson(reader, format, counts);
    }

    private static void scanModern(YsmByteReader reader, int format, Counts counts) {
        skipSoundFiles(reader, format, counts);
        skipFunctionFiles(reader, counts);
        skipLanguageFiles(reader, counts);

        if (format < 26) {
            int subEntitySize = reader.readVarIntAsInt();
            for (int i = 0; i < subEntitySize; i++) {
                skipSubEntity(reader, format, counts);
            }
            reader.readVarInt();
        } else {
            int vehiclesSize = reader.readVarIntAsInt();
            for (int i = 0; i < vehiclesSize; i++) {
                skipSubEntity(reader, format, counts);
            }

            int projectileSize = reader.readVarIntAsInt();
            for (int i = 0; i < projectileSize; i++) {
                skipSubEntity(reader, format, counts);
            }
        }

        expect(reader.readVarInt(), 1, "modern footer marker");

        int animationCount = reader.readVarIntAsInt();
        for (int i = 0; i < animationCount; i++) {
            reader.readVarInt();
            skipAnimations(reader, format, counts);
            counts.animationFiles++;
        }

        skipAnimationControllers(reader, format, counts);
        skipTextureFiles(reader, counts);

        int modelCount = reader.readVarIntAsInt();
        for (int i = 0; i < modelCount; i++) {
            reader.readVarInt();
            skipModel(reader, format, counts);
        }

        skipYsmJson(reader, format, counts);
    }

    private static void skipSubEntity(YsmByteReader reader, int format, Counts counts) {
        if (format <= 26) {
            reader.readString();
        }

        boolean hasSubAnim = reader.readVarInt() != 0;
        if (hasSubAnim) {
            skipAnimations(reader, format, counts);
            counts.animationFiles++;
        }

        expect(reader.readVarInt(), 0, "sub-entity separator");

        skipSpecialImage(reader, counts);
        reader.readVarInt();
        reader.readVarInt();
        reader.readVarInt();
        reader.readVarInt();
        counts.textures++;

        int subTextureSize = reader.readVarIntAsInt();
        for (int i = 0; i < subTextureSize; i++) {
            long specularType = reader.readVarInt();
            if (specularType != 1 && specularType != 2) {
                throw new IllegalArgumentException("unknown sub-entity texture type: " + specularType);
            }
            skipSpecialImage(reader, counts);
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            counts.textures++;
        }

        skipModel(reader, format, counts);
        if (format > 26) {
            reader.readVarInt();
            reader.readString();
        }
        counts.subEntities++;
    }

    private static void skipModel(YsmByteReader reader, int format, Counts counts) {
        if (format > 15) {
            reader.readString();
        }

        int boneCount = reader.readVarIntAsInt();
        counts.bones += boneCount;
        for (int i = 0; i < boneCount; i++) {
            reader.readString();
            int cubeCount = reader.readVarIntAsInt();
            counts.cubes += cubeCount;
            for (int j = 0; j < cubeCount; j++) {
                int faceCount = reader.readVarIntAsInt();
                counts.faces += faceCount;
                reader.skip(faceCount * FACE_BYTES);
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
            }

            reader.readString();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            reader.skipFloats(6);
        }

        reader.readString();
        reader.skipFloats(4);
        int visibleBoundsOffsetSize = reader.readVarIntAsInt();
        reader.skipFloats(visibleBoundsOffsetSize);
        reader.skipFloats(2);
        if (reader.readVarInt() > 0) {
            skipLegacyYsmInfo(reader);
        }
        reader.readVarInt();
        reader.readVarInt();
        reader.readVarInt();
        counts.models++;
    }

    private static void skipLegacyYsmInfo(YsmByteReader reader) {
        reader.readString();
        reader.readString();
        int extraAnims = reader.readVarIntAsInt();
        for (int i = 0; i < extraAnims; i++) {
            reader.readString();
        }
        int authors = reader.readVarIntAsInt();
        for (int i = 0; i < authors; i++) {
            reader.readString();
        }
        reader.readString();
        reader.readVarInt();
    }

    private static void skipAnimations(YsmByteReader reader, int format, Counts counts) {
        if (format > 15) {
            reader.readString();
        }

        int animationCount = reader.readVarIntAsInt();
        counts.animationClips += animationCount;
        for (int i = 0; i < animationCount; i++) {
            reader.readString();
            reader.readFloat();
            reader.readVarInt();

            if (format > 9) {
                reader.readVarInt();
                reader.readVarInt();
                int blendWeightMolangs = reader.readVarIntAsInt();
                for (int j = 0; j < blendWeightMolangs; j++) {
                    skipMolangValue(reader);
                }
                reader.readVarInt();
            }

            int boneCount = reader.readVarIntAsInt();
            for (int j = 0; j < boneCount; j++) {
                reader.readString();
                skipChannel(reader);
                skipChannel(reader);
                skipChannel(reader);
            }

            skipTimeline(reader);
            if (format > 9) {
                skipEffect(reader);
            }
        }
    }

    private static void skipChannel(YsmByteReader reader) {
        int keyframes = reader.readVarIntAsInt();
        for (int i = 0; i < keyframes; i++) {
            reader.readFloat();
            reader.readVarInt();
            skipMolangPair(reader);
            long hasPre = reader.readVarInt();
            if (hasPre >= 2) {
                throw new IllegalArgumentException("unexpected keyframe pre flag: " + hasPre);
            }
            if (hasPre != 0) {
                skipMolangPair(reader);
            }
        }
    }

    private static void skipMolangPair(YsmByteReader reader) {
        skipMolangValue(reader);
        skipMolangValue(reader);
        skipMolangValue(reader);
    }

    private static void skipMolangValue(YsmByteReader reader) {
        int type = reader.readUnsignedByte();
        if (type == 1) {
            reader.readFloat();
        } else if (type == 2) {
            reader.readString();
        } else {
            throw new IllegalArgumentException("unknown molang value type: " + type);
        }
    }

    private static void skipTimeline(YsmByteReader reader) {
        int header = reader.readVarIntAsInt();
        for (int i = 0; i < header; i++) {
            int inside = reader.readVarIntAsInt();
            for (int j = 0; j < inside; j++) {
                reader.readString();
            }
            reader.readFloat();
        }
    }

    private static void skipEffect(YsmByteReader reader) {
        int header = reader.readVarIntAsInt();
        for (int i = 0; i < header; i++) {
            reader.readString();
            reader.readFloat();
        }
    }

    private static void skipSpecialImage(YsmByteReader reader, Counts counts) {
        reader.readString();
        reader.readByteSequence();
        counts.specialImages++;
    }

    private static void skipSoundFiles(YsmByteReader reader, int format, Counts counts) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
            if (format > 15) {
                reader.readString();
            }
            reader.readByteSequence();
        }
        counts.sounds += count;
    }

    private static void skipFunctionFiles(YsmByteReader reader, Counts counts) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
            reader.readString();
            reader.readByteSequence();
        }
        counts.functions += count;
    }

    private static void skipLanguageFiles(YsmByteReader reader, Counts counts) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
            reader.readString();
            int nodes = reader.readVarIntAsInt();
            for (int j = 0; j < nodes; j++) {
                reader.readString();
                reader.readString();
            }
        }
        counts.languages += count;
    }

    private static void skipAnimationControllers(YsmByteReader reader, int format, Counts counts) {
        int count = reader.readVarIntAsInt();
        counts.animationControllers += count;
        for (int i = 0; i < count; i++) {
            if (format <= 15) {
                reader.readVarInt();
            } else {
                reader.readString();
                reader.readString();
            }

            int controllerCount = reader.readVarIntAsInt();
            for (int j = 0; j < controllerCount; j++) {
                reader.readString();
                reader.readString();
                int statesCount = reader.readVarIntAsInt();
                counts.animationControllerStates += statesCount;
                for (int k = 0; k < statesCount; k++) {
                    reader.readString();
                    skipStringPairs(reader);
                    skipStringPairs(reader);
                    skipStrings(reader);
                    skipStrings(reader);
                    if (reader.readVarInt() != 0) {
                        reader.readFloat();
                    } else {
                        int blendTransitions = reader.readVarIntAsInt();
                        reader.skipFloats(blendTransitions * 2);
                    }
                    reader.readVarInt();
                    if (format > 26) {
                        skipStrings(reader);
                    }
                }
            }
        }
    }

    private static void skipTextureFiles(YsmByteReader reader, Counts counts) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
            reader.readString();
            reader.readByteSequence();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            reader.readVarInt();
            counts.textures++;

            int subTextureSize = reader.readVarIntAsInt();
            for (int j = 0; j < subTextureSize; j++) {
                reader.readVarInt();
                skipSpecialImage(reader, counts);
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
            }
        }
    }

    private static void skipYsmJson(YsmByteReader reader, int format, Counts counts) {
        reader.readString();
        if (reader.readVarInt() == 0) {
            counts.profile = YsmModelProfile.EMPTY;
            return;
        }

        if (format <= 15) {
            reader.readVarInt();
        }

        String metadataName = reader.readString();
        reader.readString();
        reader.readString();
        reader.readString();

        int authors = reader.readVarIntAsInt();
        for (int i = 0; i < authors; i++) {
            reader.readString();
            reader.readString();
            int contacts = reader.readVarIntAsInt();
            for (int j = 0; j < contacts; j++) {
                reader.readString();
                reader.readString();
            }
            reader.readString();
        }

        skipStringPairs(reader);
        reader.skipFloats(2);
        List<YsmModelProfile.ExtraAnimation> extraAnimations = readStringPairs(reader);

        List<YsmModelProfile.ExtraAnimationButton> extraAnimationButtons = new ArrayList<>();
        List<YsmModelProfile.ExtraAnimationClassify> extraAnimationClassifies = new ArrayList<>();
        if (format > 9) {
            int buttons = reader.readVarIntAsInt();
            for (int i = 0; i < buttons; i++) {
                String id = reader.readString();
                String name = reader.readString();
                expect(reader.readVarInt(), 0, "extra animation button marker");
                int forms = reader.readVarIntAsInt();
                List<YsmModelProfile.ButtonForm> buttonForms = new ArrayList<>(forms);
                for (int j = 0; j < forms; j++) {
                    String type = reader.readString();
                    String title = reader.readString();
                    String description = reader.readString();
                    String value = reader.readString();
                    float step = reader.readFloat();
                    float min = reader.readFloat();
                    float max = reader.readFloat();
                    List<YsmModelProfile.ExtraAnimation> labels = readStringPairs(reader);
                    buttonForms.add(new YsmModelProfile.ButtonForm(
                            type,
                            title,
                            description,
                            value,
                            step,
                            min,
                            max,
                            labels));
                }
                extraAnimationButtons.add(new YsmModelProfile.ExtraAnimationButton(id, name, buttonForms));
            }

            int classifyCount = reader.readVarIntAsInt();
            for (int i = 0; i < classifyCount; i++) {
                String id = reader.readString();
                extraAnimationClassifies.add(new YsmModelProfile.ExtraAnimationClassify(id, readStringPairs(reader)));
            }
        }

        String defaultTexture = reader.readString();
        String previewAnimation = reader.readString();
        reader.readVarInt();
        if (format > 4) {
            reader.readVarInt();
        }
        if (format >= 15) {
            reader.readVarInt();
            reader.readVarInt();
        }

        counts.profile = new YsmModelProfile(
                metadataName,
                defaultTexture,
                previewAnimation,
                extraAnimations,
                extraAnimationButtons,
                extraAnimationClassifies);

        String guiForeground = "";
        String guiBackground = "";
        if (format > 15) {
            reader.readVarInt();
            if (format >= 32) {
                reader.readVarInt();
            }

            guiForeground = reader.readString();
            guiBackground = reader.readString();

            int avatars = reader.readVarIntAsInt();
            for (int i = 0; i < avatars; i++) {
                reader.readString();
                reader.readByteSequence();
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                counts.avatars++;
            }
        }

        if (format <= 15) {
            return;
        }

        if (!guiForeground.isEmpty() || !guiBackground.isEmpty()) {
            int backgroundCount = reader.readVarIntAsInt();
            for (int i = 0; i < backgroundCount; i++) {
                reader.readString();
                reader.readByteSequence();
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                reader.readVarInt();
                counts.backgrounds++;
            }
        }
    }

    private static void skipIdStringTable(YsmByteReader reader) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readVarInt();
            reader.readString();
        }
    }

    private static void skipStringPairs(YsmByteReader reader) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
            reader.readString();
        }
    }

    private static List<YsmModelProfile.ExtraAnimation> readStringPairs(YsmByteReader reader) {
        int count = reader.readVarIntAsInt();
        List<YsmModelProfile.ExtraAnimation> pairs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            pairs.add(new YsmModelProfile.ExtraAnimation(reader.readString(), reader.readString()));
        }
        return pairs;
    }

    private static void skipStrings(YsmByteReader reader) {
        int count = reader.readVarIntAsInt();
        for (int i = 0; i < count; i++) {
            reader.readString();
        }
    }

    private static void expect(long actual, long expected, String label) {
        if (actual != expected) {
            throw new IllegalArgumentException(label + " mismatch: " + actual + " != " + expected);
        }
    }

    private static final class Counts {
        private int models;
        private int bones;
        private int cubes;
        private int faces;
        private int animationFiles;
        private int animationClips;
        private int animationControllers;
        private int animationControllerStates;
        private int textures;
        private int specialImages;
        private int avatars;
        private int backgrounds;
        private int sounds;
        private int functions;
        private int languages;
        private int subEntities;
        private YsmModelProfile profile = YsmModelProfile.EMPTY;

        private ScanResult toResult(int parsedBytes, int totalBytes) {
            return new ScanResult(
                    parsedBytes,
                    totalBytes,
                    models,
                    bones,
                    cubes,
                    faces,
                    animationFiles,
                    animationClips,
                    animationControllers,
                    animationControllerStates,
                    textures,
                    specialImages,
                    avatars,
                    backgrounds,
                    sounds,
                    functions,
                    languages,
                    subEntities,
                    profile);
        }
    }

    record ScanResult(
            int parsedBytes,
            int totalBytes,
            int models,
            int bones,
            int cubes,
            int faces,
            int animationFiles,
            int animationClips,
            int animationControllers,
            int animationControllerStates,
            int textures,
            int specialImages,
            int avatars,
            int backgrounds,
            int sounds,
            int functions,
            int languages,
            int subEntities,
            YsmModelProfile profile) {
        boolean consumedAll() {
            return parsedBytes == totalBytes;
        }

        int trailingBytes() {
            return totalBytes - parsedBytes;
        }

        String compact() {
            String summary = "models=" + models
                    + ", bones=" + bones
                    + ", animations=" + animationFiles + "/" + animationClips
                    + ", controllers=" + animationControllers
                    + ", textures=" + textures
                    + ", avatars=" + avatars
                    + ", sounds=" + sounds
                    + ", subEntities=" + subEntities
                    + ", parsed=" + parsedBytes + "/" + totalBytes;
            if (trailingBytes() > 0) {
                summary += ", trailing=" + trailingBytes();
            }
            return summary;
        }
    }
}
