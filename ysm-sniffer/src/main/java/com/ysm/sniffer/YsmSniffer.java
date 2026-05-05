package com.ysm.sniffer;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPluginMessage;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPluginMessage;
import java.io.IOException;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Properties;
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
    private static final Path CONFIG_FILE = Paths.get("plugins", "ysm-sniffer.properties");

    private final ProxyServer proxy;
    private final Logger logger;
    private final AtomicInteger sequence = new AtomicInteger();
    private final Object indexLock = new Object();
    private Path captureRoot;
    private Path indexFile;
    private boolean writePackets = true;
    private boolean resetOnStart = true;
    private boolean logPackets = false;
    private boolean captureOnlyNative = true;
    private boolean packetEvents = true;
    private boolean packetEventsActive = false;
    private int previewBytes = 0;

    @Inject
    public YsmSniffer(ProxyServer proxy, Logger logger) {
        this.proxy = proxy;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(YSM_2_6_0, YSM_2_4_0);
        loadConfig();
        indexFile = captureRoot.resolve("index.tsv");
        try {
            Files.createDirectories(captureRoot);
            if (resetOnStart || !Files.exists(indexFile)) {
                Files.writeString(
                        indexFile,
                        "seq\ttime\tchannel\tdirection\tlen\tfirstByte\tsource\ttarget\tfile\tpreview\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("[YSM-SNIFFER] Failed to initialize capture directory {}", captureRoot, e);
        }
        logger.info(
                "[YSM-SNIFFER] Registered YSM channels: {}, {}; captureRoot={}, writePackets={}, logPackets={}, captureOnlyNative={}",
                YSM_2_6_0.getId(),
                YSM_2_4_0.getId(),
                captureRoot,
                writePackets,
                logPackets,
                captureOnlyNative);
        registerPacketEventsListener();
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (packetEventsActive) {
            return;
        }
        String channel = event.getIdentifier().getId();
        if (!channel.startsWith("yes_steve_model:")) {
            return;
        }
        byte[] data = event.getData();
        capturePacket(inferDirection(event), channel, data, describe(event.getSource()), describe(event.getTarget()));
    }

    private void loadConfig() {
        Properties properties = new Properties();
        if (!Files.exists(CONFIG_FILE)) {
            try {
                Files.createDirectories(CONFIG_FILE.getParent());
                Files.writeString(
                        CONFIG_FILE,
                        String.join(
                                System.lineSeparator(),
                                "# YSMSniffer capture config for PaperYSM fixture export.",
                                "# Keep Freesia debug hex logging disabled; this sniffer writes binary packets directly.",
                                "capture-root=plugins/ysm-sniffer-captures",
                                "write-packets=true",
                                "reset-on-start=true",
                                "log-packets=false",
                                "log-preview-bytes=0",
                                "capture-only-native=true",
                                "packet-events=true",
                                ""),
                        StandardOpenOption.CREATE_NEW);
            } catch (IOException e) {
                logger.warn("[YSM-SNIFFER] Failed to create default config {}", CONFIG_FILE, e);
            }
        }
        try (var input = Files.newInputStream(CONFIG_FILE)) {
            properties.load(input);
        } catch (IOException e) {
            logger.warn("[YSM-SNIFFER] Failed to read {}; using defaults", CONFIG_FILE, e);
        }
        captureRoot = Paths.get(properties.getProperty("capture-root", "plugins/ysm-sniffer-captures"));
        writePackets = Boolean.parseBoolean(properties.getProperty("write-packets", "true"));
        resetOnStart = Boolean.parseBoolean(properties.getProperty("reset-on-start", "true"));
        logPackets = Boolean.parseBoolean(properties.getProperty("log-packets", "false"));
        captureOnlyNative = Boolean.parseBoolean(properties.getProperty("capture-only-native", "true"));
        packetEvents = Boolean.parseBoolean(properties.getProperty("packet-events", "true"));
        try {
            previewBytes = Math.max(0, Integer.parseInt(properties.getProperty("log-preview-bytes", "0")));
        } catch (NumberFormatException ignored) {
            previewBytes = 0;
        }
    }

    private void registerPacketEventsListener() {
        if (!packetEvents) {
            return;
        }
        try {
            PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract() {
                @Override
                public void onPacketSend(PacketSendEvent event) {
                    if (event.getPacketType() != PacketType.Play.Server.PLUGIN_MESSAGE) {
                        return;
                    }
                    WrapperPlayServerPluginMessage wrapper = new WrapperPlayServerPluginMessage(event);
                    capturePacket("s2c", wrapper.getChannelName(), wrapper.getData(), "PacketEvents/server", describe(event.getUser()));
                }

                @Override
                public void onPacketReceive(PacketReceiveEvent event) {
                    if (event.getPacketType() != PacketType.Play.Client.PLUGIN_MESSAGE) {
                        return;
                    }
                    WrapperPlayClientPluginMessage wrapper = new WrapperPlayClientPluginMessage(event);
                    capturePacket("c2s", wrapper.getChannelName(), wrapper.getData(), describe(event.getUser()), "PacketEvents/server");
                }
            });
            packetEventsActive = true;
            logger.info("[YSM-SNIFFER] PacketEvents listener registered; direct Freesia S2C packets should be capturable.");
        } catch (Throwable error) {
            packetEventsActive = false;
            logger.warn("[YSM-SNIFFER] PacketEvents listener unavailable; falling back to Velocity PluginMessageEvent only. Freesia plugin-originated S2C packets may be missing.", error);
        }
    }

    private void capturePacket(String direction, String channel, byte[] data, String source, String target) {
        if (!channel.startsWith("yes_steve_model:")) {
            return;
        }
        int firstByte = data.length == 0 ? -1 : data[0] & 0xff;
        if (captureOnlyNative && firstByte != 0x01 && firstByte != 0x02) {
            return;
        }
        int seq = sequence.incrementAndGet();
        String fileName = String.format(
                "%06d-%s-%s-id%02x-%db.bin",
                seq,
                direction,
                channel.replace(':', '_'),
                firstByte < 0 ? 0 : firstByte,
                data.length);
        if (writePackets) {
            writeCapture(seq, channel, direction, data, fileName, source, target);
        }
        if (logPackets) {
            logger.info(
                    "[YSM-SNIFFER] seq={} direction={} channel={} len={} source={} target={} file={} preview={}",
                    seq,
                    direction,
                    channel,
                    data.length,
                    source,
                    target,
                    fileName,
                    hex(data, previewBytes));
        }
    }

    private void writeCapture(int seq, String channel, String direction, byte[] data, String fileName, String source, String target) {
        if (captureRoot == null || indexFile == null) {
            return;
        }
        Path file = captureRoot.resolve(fileName);
        String preview = hex(data, previewBytes);
        try {
            Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            String line = String.join(
                    "\t",
                    Integer.toString(seq),
                    Instant.now().toString(),
                    channel,
                    direction,
                    Integer.toString(data.length),
                    data.length == 0 ? "" : String.format("%02X", data[0] & 0xff),
                    sanitize(source),
                    sanitize(target),
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

    private static String inferDirection(PluginMessageEvent event) {
        String source = describe(event.getSource());
        String target = describe(event.getTarget());
        if (source.contains("VelocityServerConnection") && target.contains("ConnectedPlayer")) {
            return "s2c";
        }
        if (source.contains("ConnectedPlayer") && target.contains("VelocityServerConnection")) {
            return "c2s";
        }
        return "unknown";
    }

    private static String describe(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "(" + value + ")";
    }

    private static String sanitize(String value) {
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String hex(byte[] data, int limit) {
        if (data.length == 0 || limit <= 0) {
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
