package com.ysm.paper.session;

import java.time.Instant;
import java.util.UUID;

public record YsmClientSession(
        UUID playerId,
        String playerName,
        Instant firstSeenAt,
        Instant lastHandshakeSentAt,
        Instant lastHandshakeResponseAt,
        String protocolVersion,
        boolean compatible,
        Instant lastAuthorizedModelsSentAt,
        int lastAuthorizedModelCount,
        int lastAuthorizedPayloadBytes,
        Instant lastServerRawPacketAt,
        int serverRawPacketsSent,
        long serverRawBytesSent,
        Instant lastClientRawPacketAt,
        int clientRawPacketsReceived,
        long clientRawBytesReceived,
        int lastSubpacketId,
        int lastPayloadBytes,
        Instant lastPacketAt) {

    public static YsmClientSession pending(UUID playerId, String playerName) {
        Instant now = Instant.now();
        return new YsmClientSession(
                playerId,
                playerName,
                now,
                null,
                null,
                null,
                false,
                null,
                0,
                0,
                null,
                0,
                0L,
                null,
                0,
                0L,
                -1,
                0,
                null);
    }

    public YsmClientSession withHandshakeSent(Instant sentAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                sentAt,
                lastHandshakeResponseAt,
                protocolVersion,
                compatible,
                lastAuthorizedModelsSentAt,
                lastAuthorizedModelCount,
                lastAuthorizedPayloadBytes,
                lastServerRawPacketAt,
                serverRawPacketsSent,
                serverRawBytesSent,
                lastClientRawPacketAt,
                clientRawPacketsReceived,
                clientRawBytesReceived,
                lastSubpacketId,
                lastPayloadBytes,
                lastPacketAt);
    }

    public YsmClientSession withHandshakeResponse(String version, boolean isCompatible, Instant receivedAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                lastHandshakeSentAt,
                receivedAt,
                version,
                isCompatible,
                lastAuthorizedModelsSentAt,
                lastAuthorizedModelCount,
                lastAuthorizedPayloadBytes,
                lastServerRawPacketAt,
                serverRawPacketsSent,
                serverRawBytesSent,
                lastClientRawPacketAt,
                clientRawPacketsReceived,
                clientRawBytesReceived,
                52,
                0,
                receivedAt);
    }

    public YsmClientSession withAuthorizedModelsSent(int modelCount, int payloadBytes, Instant sentAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                lastHandshakeSentAt,
                lastHandshakeResponseAt,
                protocolVersion,
                compatible,
                sentAt,
                modelCount,
                payloadBytes,
                lastServerRawPacketAt,
                serverRawPacketsSent,
                serverRawBytesSent,
                lastClientRawPacketAt,
                clientRawPacketsReceived,
                clientRawBytesReceived,
                6,
                payloadBytes,
                sentAt);
    }

    public YsmClientSession withServerRawPacketSent(int payloadBytes, Instant sentAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                lastHandshakeSentAt,
                lastHandshakeResponseAt,
                protocolVersion,
                compatible,
                lastAuthorizedModelsSentAt,
                lastAuthorizedModelCount,
                lastAuthorizedPayloadBytes,
                sentAt,
                serverRawPacketsSent + 1,
                serverRawBytesSent + payloadBytes,
                lastClientRawPacketAt,
                clientRawPacketsReceived,
                clientRawBytesReceived,
                1,
                payloadBytes,
                sentAt);
    }

    public YsmClientSession withClientRawPacketReceived(int payloadBytes, Instant receivedAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                lastHandshakeSentAt,
                lastHandshakeResponseAt,
                protocolVersion,
                compatible,
                lastAuthorizedModelsSentAt,
                lastAuthorizedModelCount,
                lastAuthorizedPayloadBytes,
                lastServerRawPacketAt,
                serverRawPacketsSent,
                serverRawBytesSent,
                receivedAt,
                clientRawPacketsReceived + 1,
                clientRawBytesReceived + payloadBytes,
                2,
                payloadBytes,
                receivedAt);
    }

    public YsmClientSession withLastPacket(int subpacketId, int payloadBytes, Instant receivedAt) {
        return new YsmClientSession(
                playerId,
                playerName,
                firstSeenAt,
                lastHandshakeSentAt,
                lastHandshakeResponseAt,
                protocolVersion,
                compatible,
                lastAuthorizedModelsSentAt,
                lastAuthorizedModelCount,
                lastAuthorizedPayloadBytes,
                lastServerRawPacketAt,
                serverRawPacketsSent,
                serverRawBytesSent,
                lastClientRawPacketAt,
                clientRawPacketsReceived,
                clientRawBytesReceived,
                subpacketId,
                payloadBytes,
                receivedAt);
    }

    public String describe() {
        String state = compatible ? "compatible" : "pending";
        String version = protocolVersion == null ? "unknown" : protocolVersion;
        String packet = lastSubpacketId < 0 ? "none" : lastSubpacketId + "/" + lastPayloadBytes + "b";
        String auth = lastAuthorizedModelsSentAt == null
                ? "not-sent"
                : lastAuthorizedModelCount + "/" + lastAuthorizedPayloadBytes + "b";
        return state + ", protocol=" + version
                + ", lastPacket=" + packet
                + ", auth=" + auth
                + ", s2cRaw=" + serverRawPacketsSent + "/" + serverRawBytesSent + "b"
                + ", c2sRaw=" + clientRawPacketsReceived + "/" + clientRawBytesReceived + "b";
    }
}
