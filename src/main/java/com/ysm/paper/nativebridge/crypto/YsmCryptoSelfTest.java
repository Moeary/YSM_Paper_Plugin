package com.ysm.paper.nativebridge.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class YsmCryptoSelfTest {
    private YsmCryptoSelfTest() {
    }

    public static Result run() {
        byte[] key = new byte[56];
        for (int i = 0; i < key.length; i++) {
            key[i] = (byte) (i * 7 + 3);
        }

        byte[] plain = "paper-ysm packet crypto self test".getBytes(StandardCharsets.UTF_8);
        YsmCrypto.EncryptedPacket encrypted = YsmCrypto.encryptPacket(plain, key);
        YsmCrypto.DecryptedPacket decrypted = YsmCrypto.verifyAndDecryptPacket(encrypted.payload(), key);

        boolean packetRoundTrip = Arrays.equals(plain, decrypted.payload())
                && Arrays.equals(encrypted.nextKey(), decrypted.nextKey());

        long[] hash = YsmCrypto.deriveHashFromFileName("00112233445566778899aabbccddeeff00112233", key);
        boolean filenameHashLooksValid = hash.length == 2 && (hash[0] != 0 || hash[1] != 0);

        return new Result(packetRoundTrip && filenameHashLooksValid, packetRoundTrip, filenameHashLooksValid);
    }

    public record Result(boolean success, boolean packetRoundTrip, boolean filenameHashLooksValid) {
        public String describe() {
            return "success=" + success
                    + ", packetRoundTrip=" + packetRoundTrip
                    + ", filenameHash=" + filenameHashLooksValid;
        }
    }
}
