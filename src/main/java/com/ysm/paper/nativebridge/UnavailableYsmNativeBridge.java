package com.ysm.paper.nativebridge;

public final class UnavailableYsmNativeBridge implements YsmNativeBridge {
    private final String reason;

    public UnavailableYsmNativeBridge(String reason) {
        this.reason = reason;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String implementationName() {
        return "unavailable: " + reason;
    }

    @Override
    public EncryptedPacket encryptPacket(byte[] plainPacket, byte[] currentKey) {
        throw unsupported();
    }

    @Override
    public DecryptedPacket verifyAndDecryptPacket(byte[] encryptedPacket, byte[] currentKey) {
        throw unsupported();
    }

    @Override
    public CachedModel decryptCachedModel(byte[] cachedModel, String hashedFileName, byte[] runtimeKey) {
        throw unsupported();
    }

    private IllegalStateException unsupported() {
        return new IllegalStateException("YSM native bridge is not available: " + reason);
    }
}
