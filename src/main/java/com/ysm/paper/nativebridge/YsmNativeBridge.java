package com.ysm.paper.nativebridge;

public interface YsmNativeBridge {
    boolean available();

    String implementationName();

    EncryptedPacket encryptPacket(byte[] plainPacket, byte[] currentKey);

    DecryptedPacket verifyAndDecryptPacket(byte[] encryptedPacket, byte[] currentKey);

    CachedModel decryptCachedModel(byte[] cachedModel, String hashedFileName, byte[] runtimeKey);

    record EncryptedPacket(byte[] payload, byte[] nextKey) {
    }

    record DecryptedPacket(byte[] payload, byte[] nextKey) {
    }

    record CachedModel(byte[] payload) {
    }
}
