package com.ysm.sniffer;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.io.IOException;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;

@Plugin(
        id = "ysm_sniffer",
        name = "YSMSniffer",
        version = "0.1.1",
        authors = {"PaperYSM"})
public final class YsmSniffer {
    private static final MinecraftChannelIdentifier YSM_2_6_0 =
            MinecraftChannelIdentifier.from("yes_steve_model:2_6_0");
    private static final MinecraftChannelIdentifier YSM_2_4_0 =
            MinecraftChannelIdentifier.from("yes_steve_model:2_4_0");
    private static final HexFormat HEX = HexFormat.of().withUpperCase();
    private static final int LOG_PREVIEW_BYTES = 96;

    private final ProxyServer proxy;
    private final Logger logger;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Object indexLock = new Object();
    private Path captureRoot;
    private Path indexFile;

    @Inject
    public YsmSniffer(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(YSM_2_6_0, YSM_2_4_0);
        captureRoot = Paths.get("plugins", "ysm-sniffer-captures");
        indexFile = captureRoot.resolve("index.tsv");
        try {
            Files.createDirectories(captureRoot);
            Files.writeString(
                    indexFile,
                    "seq\ttime\tchannel\tlen\tfirstByte\tsource\ttarget\tfile\tpreview\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            logger.error("[YSM-SNIFFER] Failed to initialize capture directory {}", captureRoot, e);
        }
        logger.info(
                "[YSM-SNIFFER] Registered YSM channels: {}, {}; captureRoot={}",
                YSM_2_6_0.getId(),
                YSM_2_4_0.getId(),
                captureRoot);
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        String channel = event.getIdentifier().getId();
        if (!channel.startsWith("yes_steve_model:")) {
            return;
        }
        byte[] data = event.getData();
        int seq = sequence.incrementAndGet();
        String fileName = String.format(
                "%06d-%s-id%02x-%db.bin",
                seq,
                channel.replace(':', '_'),
                data.length == 0 ? 0 : data[0] & 0xff,
                data.length);
        writeCapture(seq, channel, data, fileName, event);
        logger.info(
                "[YSM-SNIFFER] seq={} channel={} len={} source={} target={} file={} preview={}",
                seq,
                channel,
                data.length,
                describe(event.getSource()),
                describe(event.getTarget()),
                fileName,
                hex(data, LOG_PREVIEW_BYTES));
    }

    private void writeCapture(int seq, String channel, byte[] data, String fileName, PluginMessageEvent event) {
        if (captureRoot == null || indexFile == null) {
            return;
        }
        Path file = captureRoot.resolve(fileName);
        String preview = hex(data, LOG_PREVIEW_BYTES);
        try {
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String line = String.join(
                    "\t",
                    Integer.toString(seq),
                    Instant.now().toString(),
                    channel,
                    Integer.toString(data.length),
                    data.length == 0 ? "" : String.format("%02X", data[0] & 0xff),
                    sanitize(describe(event.getSource())),
                    sanitize(describe(event.getTarget())),
                    fileName,
                    preview)
                    + System.lineSeparator();
            synchronized (indexLock) {
                Files.writeString(indexFile, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            logger.error("[YSM-SNIFFER] Failed to write capture {}", file, e);
        }
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "(" + value + ")";
    }

    private static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String hex(byte[] data, int limit) {
        if (data.length == 0) {
            return "";
        }
        int count = Math.min(data.length, limit);
        StringBuilder out = new StringBuilder(count * 3 + 32);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(HEX.toHexDigits(data[i]));
        }
        if (data.length > limit) {
            out.append(" ... +").append(data.length - limit).append(" bytes");
        }
        return out.toString();
    }
}
