package com.ysm.paper.openysm;

public final class OpenYsmServerCacheConverter {
    public static final int SERVER_CACHE_FORMAT = 32;

    private OpenYsmServerCacheConverter() {
    }

    public static byte[] toServerCacheCleartext(byte[] decryptedYsmPayload) {
        try (YsmBinaryDeserializer deserializer = new YsmBinaryDeserializer(decryptedYsmPayload)) {
            RawYsmModel model = deserializer.deserializeKeepOpen();
            deserializer.parseYSMFooter(model);
            return toServerCacheCleartext(model);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("OpenYSM server-cache serialization failed: " + ex.getMessage(), ex);
        }
    }

    public static byte[] toServerCacheCleartext(RawYsmModel model) {
        try (YsmBinaryBuffer serialized = YsmBinarySerializer.serialize(model, SERVER_CACHE_FORMAT, true)) {
            return serialized.toArray();
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("OpenYSM server-cache serialization failed: " + ex.getMessage(), ex);
        }
    }
}
