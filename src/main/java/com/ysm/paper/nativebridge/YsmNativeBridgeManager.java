package com.ysm.paper.nativebridge;

import com.ysm.paper.nativebridge.crypto.PureJavaYsmNativeBridge;
import org.bukkit.configuration.ConfigurationSection;

public final class YsmNativeBridgeManager {
    private YsmNativeBridge bridge = new UnavailableYsmNativeBridge("not initialized");

    public YsmNativeBridge bridge() {
        return bridge;
    }

    public void reload(ConfigurationSection config) {
        if (config == null) {
            bridge = new UnavailableYsmNativeBridge("missing native config section");
            return;
        }

        if (!config.getBoolean("enabled", false)) {
            bridge = new UnavailableYsmNativeBridge("disabled in config");
            return;
        }

        String mode = config.getString("mode", "jni");
        if ("java".equalsIgnoreCase(mode) || "pure-java".equalsIgnoreCase(mode)) {
            bridge = new PureJavaYsmNativeBridge();
            return;
        }

        bridge = new UnavailableYsmNativeBridge("mode '" + mode + "' is declared but not implemented yet");
    }
}
