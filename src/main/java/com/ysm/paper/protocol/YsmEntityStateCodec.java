package com.ysm.paper.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class YsmEntityStateCodec {
    private static final int FULL_SYNC_FLAG = 0x0001;
    private static final int MODEL_SWITCH_FLAG = 0x0800;
    private static final int MOLANG_STORAGE_FLAG = 0x1000;

    private YsmEntityStateCodec() {
    }

    public static byte[] encodeModelSelectionBody(
            int entityId,
            String modelId,
            String textureId,
            boolean disabled) {
        return encodeModelSelectionBody(entityId, modelId, textureId, disabled, "", 0, Map.of());
    }

    public static byte[] encodeModelSelectionBody(
            int entityId,
            String modelId,
            String textureId,
            boolean disabled,
            String modelSwitchId,
            int molangModelId,
            Map<String, Float> molangStorage) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUtf(out, modelId);
        writeUtf(out, textureId);
        writeBoolean(out, disabled);
        int flags = 0;
        if (molangStorage != null && !molangStorage.isEmpty()) {
            flags |= FULL_SYNC_FLAG;
        }
        writeEntityState(out, new EntityState(
                entityId,
                flags,
                modelSwitchId == null ? "" : modelSwitchId,
                molangModelId,
                molangStorage == null ? Map.of() : molangStorage));
        return out.toByteArray();
    }

    public static byte[] encodeEntityStateBody(EntityState state) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeEntityState(out, state);
        return out.toByteArray();
    }

    private static void writeEntityState(ByteArrayOutputStream out, EntityState state) {
        int flags = state.flags();
        if (!state.molangStorage().isEmpty()) {
            flags |= MOLANG_STORAGE_FLAG;
        }
        if (!state.modelSwitchId().isEmpty()) {
            flags |= MODEL_SWITCH_FLAG;
        }

        writeVarInt(out, state.entityId());
        writeShort(out, flags);

        if ((flags & MODEL_SWITCH_FLAG) != 0) {
            writeUtf(out, state.modelSwitchId());
        }

        if ((flags & MOLANG_STORAGE_FLAG) != 0) {
            writeInt(out, state.molangModelId());
            writeVarInt(out, state.molangStorage().size());
            for (Map.Entry<String, Float> entry : state.molangStorage().entrySet()) {
                writeUtf(out, entry.getKey());
                writeFloat(out, entry.getValue());
            }
        }
    }

    private static void writeUtf(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) {
            throw new YsmProtocolException("UTF payload is too long: " + bytes.length);
        }
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeBoolean(ByteArrayOutputStream out, boolean value) {
        out.write(value ? 1 : 0);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            out.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >>> 24) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write(value & 0xff);
    }

    private static void writeFloat(ByteArrayOutputStream out, float value) {
        writeInt(out, Float.floatToIntBits(value));
    }

    public record EntityState(
            int entityId,
            int flags,
            String modelSwitchId,
            int molangModelId,
            Map<String, Float> molangStorage) {
        public EntityState {
            modelSwitchId = modelSwitchId == null ? "" : modelSwitchId;
            molangStorage = Map.copyOf(molangStorage);
        }
    }
}
