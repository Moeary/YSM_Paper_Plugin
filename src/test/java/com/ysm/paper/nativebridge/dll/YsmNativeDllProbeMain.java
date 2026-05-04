package com.ysm.paper.nativebridge.dll;

public final class YsmNativeDllProbeMain {
    private YsmNativeDllProbeMain() {
    }

    public static void main(String[] args) {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: YsmNativeDllProbeMain <path-to-ysm-core.dll>");
        }

        String dllPath = args[0];
        System.out.println("Loading YSM native DLL: " + dllPath);
        try {
            System.load(dllPath);
            System.out.println("YSM native DLL loaded successfully.");
        } catch (Throwable ex) {
            System.out.println("YSM native DLL load failed: " + ex.getClass().getName() + ": " + ex.getMessage());
            ex.printStackTrace(System.out);
            System.exit(1);
        }
    }
}
