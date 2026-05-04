package com.ysm.paper.nativebridge.crypto;

public final class YsmCryptoSelfTestMain {
    private YsmCryptoSelfTestMain() {
    }

    public static void main(String[] args) {
        YsmCryptoSelfTest.Result result = YsmCryptoSelfTest.run();
        System.out.println(result.describe());
        if (!result.success()) {
            System.exit(1);
        }
    }
}
