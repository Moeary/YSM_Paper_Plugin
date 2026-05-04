package com.ysm.paper.nativebridge.crypto;

import com.ysm.paper.nativebridge.sync.YsmNativeSyncPrototype;
import java.util.Arrays;
import java.util.List;

public final class YsmRawPacketCodecSelfTestMain {
    private YsmRawPacketCodecSelfTestMain() {
    }

    public static void main(String[] args) {
        byte[] transportKey = sequence(56, 7, 3);
        byte[] advertisedKey = sequence(56, 11, 5);
        byte[] padding = sequence(13, 17, 19);

        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(advertisedKey, padding);
        YsmRawPacketCodec.PlainPacket type1Decoded = YsmRawPacketCodec.decodePlain(type1Plain);
        require(type1Decoded.type() == 1, "type1 decode type");
        require(Arrays.equals(advertisedKey, YsmRawPacketCodec.selectedKeyOrThrow(type1Decoded)), "type1 decode key");

        byte[] encrypted = YsmRawPacketCodec.encryptBodyOnly(type1Plain, transportKey);
        YsmRawPacketCodec.PlainPacket decrypted = YsmRawPacketCodec.decryptBodyOnly(encrypted, transportKey);
        require(decrypted.type() == 1, "type1 decrypt type");
        require(Arrays.equals(advertisedKey, YsmRawPacketCodec.selectedKeyOrThrow(decrypted)), "type1 decrypt key");

        byte[] nextTransportKey = sequence(56, 13, 29);
        byte[] fullWire = YsmRawPacketCodec.encryptWithNextKey(type1Plain, transportKey, nextTransportKey);
        YsmRawPacketCodec.WirePacket wire = YsmRawPacketCodec.decryptWithNextKey(fullWire, transportKey);
        require(wire.plain().type() == 1, "full-wire type1 decrypt type");
        require(Arrays.equals(advertisedKey, YsmRawPacketCodec.selectedKeyOrThrow(wire.plain())), "full-wire type1 key");
        require(Arrays.equals(nextTransportKey, wire.nextTransportKey()), "full-wire next transport key");

        byte[] bootstrapRaw = YsmNativeSyncPrototype.encodeBootstrapRawBody(
                YsmNativeSyncPrototype.bootstrapTransportKey(YsmNativeSyncPrototype.MODE_SEQUENCE),
                advertisedKey,
                nextTransportKey);
        var decoded = YsmNativeSyncPrototype.tryDecode(
                bootstrapRaw,
                List.of(new YsmNativeSyncPrototype.KeyCandidate(
                        "sequence",
                        YsmNativeSyncPrototype.bootstrapTransportKey(YsmNativeSyncPrototype.MODE_SEQUENCE))));
        require(decoded.isPresent(), "prototype bootstrap decode");
        require(decoded.get().usedNextKeyTrailer(), "prototype bootstrap next-key trailer");
        require(decoded.get().packet().type() == 1, "prototype bootstrap type");
        require(YsmNativeSyncPrototype.deterministicKey("stream-model", "zero", "next").length
                == YsmRawPacketCodec.KEY_BYTES, "deterministic stream key accepts chain labels");

        byte[] type2Plain = YsmRawPacketCodec.encodePlainType2(7, advertisedKey, new byte[0]);
        YsmRawPacketCodec.PlainPacket type2Decoded = YsmRawPacketCodec.decodePlain(type2Plain);
        require(type2Decoded.type() == 2, "type2 decode type");
        require(Arrays.equals(advertisedKey, YsmRawPacketCodec.selectedKeyOrThrow(type2Decoded)), "type2 decode key");

        byte[] type4Plain = YsmRawPacketCodec.encodePlainType4(1234, new byte[] {1, 2, 3});
        YsmRawPacketCodec.PlainPacket type4Decoded = YsmRawPacketCodec.decodePlain(type4Plain);
        require(type4Decoded.type() == 4, "type4 decode type");
        require(type4Decoded.selectedKey().isEmpty(), "type4 no key");

        byte[] type3Plain = YsmRawPacketCodec.encodePlainType3(
                0,
                new byte[0x1c],
                sequence(0x1c, 3, 1),
                advertisedKey,
                List.of(new YsmRawPacketCodec.ModelInfo("demo/model", 15, 123, 456)),
                List.of(0L),
                new byte[0]);
        YsmRawPacketCodec.PlainPacket type3Decoded = YsmRawPacketCodec.decodePlain(type3Plain);
        require(type3Decoded.type() == 3, "type3 decode type");
        require(type3Decoded.models().size() == 1, "type3 model count");
        require(type3Decoded.models().get(0).format() == 15, "type3 model format");
        require(Arrays.equals(advertisedKey, YsmRawPacketCodec.selectedKeyOrThrow(type3Decoded)), "type3 selected key");

        System.out.println("ysmRawPacketSelfTest success: type1/type2/type3/type4 decode, body-only crypto, full-wire crypto, and bootstrap probe passed");
    }

    private static byte[] sequence(int length, int mul, int add) {
        byte[] out = new byte[length];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (i * mul + add);
        }
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("Failed: " + message);
        }
    }
}
