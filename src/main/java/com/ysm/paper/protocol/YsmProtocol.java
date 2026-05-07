package com.ysm.paper.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;

public final class YsmProtocol {
    public static final String DEFAULT_PROTOCOL_VERSION = "2.6.0";
    public static final String DEFAULT_CHANNEL = "yes_steve_model:2_6_0";
    public static final int SERVER_RAW_PACKET_ID = 1;
    public static final int CLIENT_RAW_PACKET_ID = 2;
    public static final int MOLANG_EXECUTE_ID = 3;
    public static final int ENTITY_DATA_UPDATE_ID = 4;
    public static final int CLIENT_MODEL_SELECTION_ID = 5;
    public static final int CLIENT_ANIMATION_REQUEST_ID = 7;
    public static final int CLIENT_MOLANG_EXECUTE_REQUEST_ID = 17;
    public static final int ANIMATION_ID = 21;
    public static final int SERVER_HANDSHAKE_ID = 51;
    public static final int CLIENT_HANDSHAKE_ID = 52;

    private YsmProtocol() {
    }

    public static byte[] encodeServerHandshake(String protocolVersion) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SERVER_HANDSHAKE_ID);
        writeUtf(out, protocolVersion);
        return out.toByteArray();
    }

    public static byte[] encodeServerRawPacket(byte[] rawPacketBody) {
        byte[] payload = new byte[rawPacketBody.length + 1];
        payload[0] = (byte) SERVER_RAW_PACKET_ID;
        System.arraycopy(rawPacketBody, 0, payload, 1, rawPacketBody.length);
        return payload;
    }

    public static byte[] encodeEntityDataUpdate(int entityId, byte[] entityStateBody) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ENTITY_DATA_UPDATE_ID);
        writeVarInt(out, entityId);
        out.writeBytes(entityStateBody);
        return out.toByteArray();
    }

    public static byte[] encodeAuthorizedModelSet(Collection<String> modelIds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(6);
        writeVarInt(out, modelIds.size());
        for (String modelId : modelIds) {
            writeUtf(out, modelId);
        }
        return out.toByteArray();
    }

    public static EntityDataUpdate decodeEntityDataUpdate(byte[] payload) {
        Reader reader = new Reader(payload);
        int id = reader.readUnsignedByte();
        if (id != ENTITY_DATA_UPDATE_ID) {
            throw new YsmProtocolException("expected entity data update id 4, got " + id);
        }
        int entityId = reader.readVarInt();
        byte[] body = reader.readRemainingBytes();
        return new EntityDataUpdate(entityId, body);
    }

    public static byte[] decodeClientRawPacket(byte[] payload) {
        int id = peekSubpacketId(payload);
        if (id != CLIENT_RAW_PACKET_ID) {
            throw new YsmProtocolException("expected client raw packet id 2, got " + id);
        }
        byte[] rawPacketBody = new byte[payload.length - 1];
        System.arraycopy(payload, 1, rawPacketBody, 0, rawPacketBody.length);
        return rawPacketBody;
    }

    public static ClientModelSelection decodeClientModelSelection(byte[] payload) {
        Reader reader = new Reader(payload);
        int id = reader.readUnsignedByte();
        if (id != CLIENT_MODEL_SELECTION_ID) {
            throw new YsmProtocolException("expected client model selection id 5, got " + id);
        }
        String modelId = reader.readUtf();
        String textureId = reader.readUtf();
        if (reader.remaining() != 0) {
            throw new YsmProtocolException("client model selection has " + reader.remaining() + " trailing byte(s)");
        }
        return new ClientModelSelection(modelId, textureId);
    }

    public static ClientAnimationRequest decodeClientAnimationRequest(byte[] payload) {
        Reader reader = new Reader(payload);
        int id = reader.readUnsignedByte();
        if (id != CLIENT_ANIMATION_REQUEST_ID) {
            throw new YsmProtocolException("expected client animation request id 7, got " + id);
        }
        int action = reader.readVarInt();
        String name = reader.readUtf();
        int targetEntityId = reader.readVarInt();
        if (reader.remaining() != 0) {
            throw new YsmProtocolException("client animation request has " + reader.remaining() + " trailing byte(s)");
        }
        return new ClientAnimationRequest(action, name, targetEntityId);
    }

    public static byte[] encodeAnimation(int entityId, int layer, int action, String name) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(ANIMATION_ID);
        writeVarInt(out, entityId);
        writeVarInt(out, layer);
        writeVarInt(out, action);
        writeUtf(out, name);
        return out.toByteArray();
    }

    public static byte[] encodeMolangExecute(int[] entityIds, String expression) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(MOLANG_EXECUTE_ID);
        int[] safeEntityIds = entityIds == null ? new int[0] : entityIds;
        writeVarInt(out, safeEntityIds.length);
        for (int entityId : safeEntityIds) {
            writeVarInt(out, entityId);
        }
        writeUtf(out, expression == null ? "" : expression);
        return out.toByteArray();
    }

    public static int peekSubpacketId(byte[] payload) {
        if (payload.length == 0) {
            throw new YsmProtocolException("empty payload");
        }
        return payload[0] & 0xff;
    }

    public static String decodeClientHandshake(byte[] payload) {
        Reader reader = new Reader(payload);
        int id = reader.readUnsignedByte();
        if (id != CLIENT_HANDSHAKE_ID) {
            throw new YsmProtocolException("expected client handshake id 52, got " + id);
        }
        String version = reader.readUtf();
        if (reader.remaining() != 0) {
            throw new YsmProtocolException("client handshake has " + reader.remaining() + " trailing byte(s)");
        }
        return version;
    }

    public static String toHex(byte[] payload, int maxBytes) {
        int count = Math.min(payload.length, Math.max(0, maxBytes));
        StringBuilder builder = new StringBuilder(count * 3 + 16);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(Character.forDigit((payload[i] >>> 4) & 0x0f, 16));
            builder.append(Character.forDigit(payload[i] & 0x0f, 16));
        }
        if (payload.length > count) {
            builder.append(" ... +").append(payload.length - count).append(" bytes");
        }
        return builder.toString();
    }

    private static void writeUtf(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 32767) {
            throw new YsmProtocolException("UTF payload is too long: " + bytes.length);
        }
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            out.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        out.write(remaining);
    }

    public record EntityDataUpdate(int entityId, byte[] entityStateBody) {
    }

    public record ClientModelSelection(String modelId, String textureId) {
    }

    public record ClientAnimationRequest(int action, String name, int targetEntityId) {
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

        private int remaining() {
            return payload.length - offset;
        }

        private byte[] readRemainingBytes() {
            byte[] bytes = new byte[remaining()];
            System.arraycopy(payload, offset, bytes, 0, bytes.length);
            offset = payload.length;
            return bytes;
        }

        private void ensureAvailable(int count) {
            if (payload.length - offset < count) {
                throw new YsmProtocolException("payload ended early");
            }
        }
    }
}
