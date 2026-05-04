package com.ysm.paper.nativebridge.crypto;

import com.ysm.paper.nativebridge.YsmNativeBridge;

public final class PureJavaYsmNativeBridge implements YsmNativeBridge {
    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String implementationName() {
        return "pure-java";
    }

    @Override
    public EncryptedPacket encryptPacket(byte[] plainPacket, byte[] currentKey) {
        YsmCrypto.EncryptedPacket encrypted = YsmCrypto.encryptPacket(plainPacket, currentKey);
        return new EncryptedPacket(encrypted.payload(), encrypted.nextKey());
    }

    @Override
    public DecryptedPacket verifyAndDecryptPacket(byte[] encryptedPacket, byte[] currentKey) {
        YsmCrypto.DecryptedPacket decrypted = YsmCrypto.verifyAndDecryptPacket(encryptedPacket, currentKey);
        return new DecryptedPacket(decrypted.payload(), decrypted.nextKey());
    }

    @Override
    public CachedModel decryptCachedModel(byte[] cachedModel, String hashedFileName, byte[] runtimeKey) {
        return new CachedModel(YsmCrypto.decryptCachedModel(cachedModel, hashedFileName, runtimeKey));
    }
}
