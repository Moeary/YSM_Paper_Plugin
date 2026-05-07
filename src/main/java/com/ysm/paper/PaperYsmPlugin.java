package com.ysm.paper;

import com.ysm.paper.model.YsmDistributionRepository;
import com.ysm.paper.model.YsmModelRepository;
import com.ysm.paper.protocol.YsmProtocol;
import com.ysm.paper.protocol.YsmProtocolException;
import com.ysm.paper.protocol.YsmEntityStateCodec;
import com.ysm.paper.nativebridge.YsmNativeBridgeManager;
import com.ysm.paper.nativebridge.crypto.YsmCrypto;
import com.ysm.paper.nativebridge.crypto.YsmCryptoSelfTest;
import com.ysm.paper.nativebridge.crypto.YsmModelProfile;
import com.ysm.paper.nativebridge.crypto.YsmRawPacketCodec;
import com.ysm.paper.nativebridge.sync.YsmNativeSyncPrototype;
import com.ysm.paper.openysm.OpenYsmServerCacheConverter;
import com.ysm.paper.session.YsmClientSession;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class PaperYsmPlugin extends JavaPlugin implements Listener, PluginMessageListener, TabExecutor {
    private static final Pattern FREESIA_S2C_HEX_PATTERN =
            Pattern.compile("S2C Packet Data \\((\\d+) bytes\\):\\s*([0-9A-Fa-f ]+)");
    private static final Pattern HEX_BYTE_PATTERN = Pattern.compile("[0-9A-Fa-f]{2}");
    private static final String REPLAY_MODE_FAST = "fast";
    private static final String REPLAY_MODE_FREESIA = "freesia";
    private static final String REPLAY_MODE_FREESIA_PRELUDE = "freesia-prelude";
    private static final int DEFAULT_RAW_REPLAY_HANDSHAKE_TIMEOUT_TICKS = 20 * 75;
    private static final int FREESIA_REPLAY_WAIT_INTERVAL_TICKS = 2;
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] REPORT_NATIVE_BOOTSTRAP_KEY = HEX.parseHex(
            "0fc77ef3f4b8353aa2ba7fd31779468e6542d0988a9bb019804f8156366a1262"
                    + "be0ee5ad4701d45ee4ebfb36cb474298f9e57a5c3cdb2c76");
    private static final int REPORT_NATIVE_TYPE1_PADDING_BYTES = 15;
    private static final String REPORT_TYPE3_LAYOUT_KEYS = "keys";
    private static final String REPORT_TYPE3_LAYOUT_KEYS_MODELS = "keys-models";
    private static final String REPORT_TYPE3_LAYOUT_LEGACY = "legacy";
    private static final String REPORT_TYPE3_KEY_S2C = "s2c";
    private static final String REPORT_TYPE3_KEY_C2S = "c2s";
    private static final String REPORT_TYPE3_KEY_BOTH = "both";
    private static final String NATIVE_CACHE_REPLAY_ROOT = "cache";
    private static final String LEGACY_NATIVE_CACHE_REPLAY_ROOT = "captures/native-cache";
    private static final String DEFAULT_NATIVE_CACHE_SOURCE = "freesia-from-velocity";
    private static final int FREESIA_NATIVE_TYPE1_PADDING_BYTES = 70;
    private static final int FREESIA_NATIVE_TYPE3_PADDING_BYTES = 115;
    private static final int FREESIA_NATIVE_CACHE_CHUNK_BYTES = 29963;
    private static final byte[] FREESIA_NATIVE_TYPE3_ENTRY_PRELUDE =
            HEX.parseHex("f8e893f095701958b7e215");
    private static final String GENERATED_CACHE_LAYOUT_OPENYSM = "openysm";
    private static final String GENERATED_CACHE_LAYOUT_LEGACY = "legacy";
    private static final String GENERATED_CACHE_LAYOUT_KEYS = "keys";
    private static final String GENERATED_CACHE_PAYLOAD_SERVER_CACHE = "server-cache";
    private static final String GENERATED_CACHE_PAYLOAD_WASHED_ZSTD = "washed-zstd";
    private static final String GENERATED_CACHE_PAYLOAD_HEADERLESS_V3 = "headerless-v3";
    private static final String GENERATED_CACHE_PAYLOAD_ENCRYPTED_V3 = "encrypted-v3";
    private static final String GENERATED_CACHE_MODEL_SAVED = "saved";
    private static final String GENERATED_CACHE_OPENYSM_CHANNEL = "openysm";
    private static final String GENERATED_CACHE_SESSION_ROOT = "cache/" + GENERATED_CACHE_OPENYSM_CHANNEL;
    private static final String GENERATED_CACHE_OPENYSM_INDEX_FILE = "index.properties";
    private static final String GENERATED_CACHE_SERVER_ROOT = "cache/" + GENERATED_CACHE_OPENYSM_CHANNEL + "/server-cache";
    private static final String GENERATED_CACHE_SERVER_INDEX_FILE = "index.tsv";
    private static final String GENERATED_CACHE_TOKEN_VERSION = "openysm-alias-v2";
    private static final long GENERATED_CACHE_OPENYSM_CLIENT_KEY_SEED = 114514L;
    private static final int DEFAULT_AUTO_GENERATED_CACHE_MAX_MODELS = 32;
    private static final int DEFAULT_AUTO_GENERATED_CACHE_CHUNK_BYTES = 65536;
    private static final int DEFAULT_AUTO_GENERATED_CACHE_TYPE5_PACKETS_PER_TICK = 2;
    private static final long MODEL_STATE_REPLAY_DELAY_TICKS = 5L;
    private static final long MODEL_STATE_LATE_REPLAY_DELAY_TICKS = 40L;
    private static final long SAVED_MODEL_RESTORE_DELAY_TICKS = 10L;
    private static final long SAVED_MODEL_RESTORE_LATE_DELAY_TICKS = 80L;

    private final Map<UUID, YsmClientSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, AppliedModelState> appliedModelStates = new ConcurrentHashMap<>();
    private final Map<UUID, SavedModelState> savedModelStates = new ConcurrentHashMap<>();
    private final Map<UUID, String> activeAnimationStates = new ConcurrentHashMap<>();
    private final Map<UUID, Map<Integer, Map<String, Float>>> playerMolangStates = new ConcurrentHashMap<>();
    private final YsmNativeBridgeManager nativeBridgeManager = new YsmNativeBridgeManager();
    private final YsmModelRepository modelRepository = new YsmModelRepository();
    private final YsmDistributionRepository distributionRepository = new YsmDistributionRepository();
    private final AtomicInteger rawPacketCaptureCounter = new AtomicInteger();
    private final Set<UUID> nativeSyncGapWarnings = ConcurrentHashMap.newKeySet();
    private final Map<UUID, NativeSyncState> nativeSyncStates = new ConcurrentHashMap<>();
    private final Map<UUID, ReportNativeSession> reportNativeSessions = new ConcurrentHashMap<>();
    private final Map<UUID, NativeCacheReplaySession> nativeCacheReplaySessions = new ConcurrentHashMap<>();
    private final Map<UUID, GeneratedCacheBatchQueue> generatedCacheBatchQueues = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerNativeCacheSources = new ConcurrentHashMap<>();
    private final Map<UUID, CopyOnWriteArrayList<YsmNativeSyncPrototype.KeyCandidate>> nativeRecentDecodeKeys =
            new ConcurrentHashMap<>();
    private final Object generatedOpenYsmCacheKeysLock = new Object();
    private final Object generatedServerCacheIndexLock = new Object();
    private volatile GeneratedOpenYsmCacheKeys generatedOpenYsmCacheKeys;

    private String protocolVersion;
    private String channel;
    private String modelsDir;
    private String distributionCacheDir;
    private String rawPacketCaptureDir;
    private String rawReplayDir;
    private boolean scanModelsOnEnable;
    private boolean prepareDistributionOnReload;
    private boolean writeDistributionCacheFiles;
    private boolean captureClientRawPackets;
    private boolean sendAuthorizedModelsOnHandshake;
    private boolean warnMissingNativeSyncOnHandshake;
    private boolean enableRawReplay;
    private boolean experimentalBootstrapOnHandshake;
    private String experimentalBootstrapMode;
    private int experimentalProbeIntervalTicks;
    private int handshakeDelayTicks;
    private int handshakeRetries;
    private int handshakeRetryIntervalTicks;
    private int distributionChunkBytes;
    private int rawReplayIntervalTicks;
    private int rawReplayHandshakeTimeoutTicks;
    private boolean autoNativeCacheOnHandshake;
    private String autoNativeCacheCaptureName;
    private int autoNativeCacheDelayTicks;
    private int autoNativeCacheIntervalTicks;
    private int autoNativeCacheChunkBytes;
    private boolean autoGeneratedCacheOnHandshake;
    private String autoGeneratedCacheModelId;
    private int autoGeneratedCacheDelayTicks;
    private int autoGeneratedCacheIntervalTicks;
    private int autoGeneratedCacheChunkBytes;
    private int autoGeneratedCacheMaxModels;
    private int autoGeneratedCacheType5PacketsPerTick;
    private boolean autoGeneratedCachePrewarmOnStartup;
    private String autoGeneratedCachePrewarmModelId;
    private int autoGeneratedCachePrewarmDelayTicks;
    private boolean autoGeneratedCacheSyncOnlineAfterPrewarm;
    private String autoGeneratedCacheLayout;
    private String autoGeneratedCachePayload;
    private String autoGeneratedCacheTokenSalt;
    private boolean rememberPlayerModels;
    private String savedPlayerModelsFile;
    private boolean debug;
    private boolean logModelScanDetails;
    private boolean logPacketDetails;
    private int logProgressIntervalModels;
    private int packetHexPreviewBytes;
    private int rawPacketHexPreviewBytes;
    private volatile boolean modelRepositoryReloadInProgress;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        nativeBridgeManager.reload(getConfig().getConfigurationSection("native"));
        loadSavedModelStates();
        scheduleStartupModelRepositoryReload();

        Bukkit.getMessenger().registerOutgoingPluginChannel(this, channel);
        Bukkit.getMessenger().registerIncomingPluginChannel(this, channel, this);
        Bukkit.getPluginManager().registerEvents(this, this);

        var command = getCommand("ysm");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
        var clientCommand = getCommand("ysmclient");
        if (clientCommand != null) {
            clientCommand.setExecutor(this);
            clientCommand.setTabCompleter(this);
        }

        getLogger().info("PaperYSM enabled on channel " + channel + " using protocol " + protocolVersion + ".");
        getLogger().info("YSM raw replay config: enabled=" + enableRawReplay
                + ", dir=" + rawReplayDir
                + ", intervalTicks=" + rawReplayIntervalTicks
                + ", handshakeTimeoutTicks=" + rawReplayHandshakeTimeoutTicks
                + ", captureClientRawPackets=" + captureClientRawPackets + ".");
        getLogger().info("YSM auto native cache config: enabled=" + autoNativeCacheOnHandshake
                + ", source=" + autoNativeCacheCaptureName
                + ", delayTicks=" + autoNativeCacheDelayTicks
                + ", intervalTicks=" + autoNativeCacheIntervalTicks
                + ", chunkBytes=" + autoNativeCacheChunkBytes + ".");
        getLogger().info("YSM auto generated cache config: enabled=" + autoGeneratedCacheOnHandshake
                + ", model=" + autoGeneratedCacheModelId
                + ", layout=" + autoGeneratedCacheLayout
                + ", payload=" + autoGeneratedCachePayload
                + ", tokenSalt=" + logValue(autoGeneratedCacheTokenSalt)
                + ", delayTicks=" + autoGeneratedCacheDelayTicks
                + ", intervalTicks=" + autoGeneratedCacheIntervalTicks
                + ", chunkBytes=" + autoGeneratedCacheChunkBytes
                + ", maxModels=" + autoGeneratedCacheMaxModels
                + ", type5PacketsPerTick=" + autoGeneratedCacheType5PacketsPerTick
                + ", prewarmOnStartup=" + autoGeneratedCachePrewarmOnStartup
                + ", prewarmModel=" + autoGeneratedCachePrewarmModelId
                + ", prewarmDelayTicks=" + autoGeneratedCachePrewarmDelayTicks
                + ", syncOnlineAfterPrewarm=" + autoGeneratedCacheSyncOnlineAfterPrewarm + ".");
        getLogger().info("YSM native bridge: " + nativeBridgeManager.bridge().implementationName() + ".");
        scheduleAutoGeneratedServerCachePrewarm();
    }

    @Override
    public void onDisable() {
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this, channel, this);
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(this, channel);
        sessions.clear();
        appliedModelStates.clear();
        saveSavedModelStates();
        savedModelStates.clear();
        distributionRepository.clear();
        nativeSyncGapWarnings.clear();
        nativeSyncStates.clear();
        reportNativeSessions.clear();
        for (NativeCacheReplaySession state : nativeCacheReplaySessions.values()) {
            cleanupGeneratedCacheSessionDirectoryNow(state.sourceDir(), "plugin-disable");
        }
        nativeCacheReplaySessions.clear();
        generatedCacheBatchQueues.clear();
        playerNativeCacheSources.clear();
        nativeRecentDecodeKeys.clear();
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        sessions.put(player.getUniqueId(), YsmClientSession.pending(player.getUniqueId(), player.getName()));
        scheduleHandshake(player, 0);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        persistRuntimeStateForQuit(event.getPlayer());
        appliedModelStates.remove(event.getPlayer().getUniqueId());
        activeAnimationStates.remove(event.getPlayer().getUniqueId());
        playerMolangStates.remove(event.getPlayer().getUniqueId());
        nativeSyncGapWarnings.remove(event.getPlayer().getUniqueId());
        nativeSyncStates.remove(event.getPlayer().getUniqueId());
        reportNativeSessions.remove(event.getPlayer().getUniqueId());
        NativeCacheReplaySession removed = nativeCacheReplaySessions.remove(event.getPlayer().getUniqueId());
        if (removed != null) {
            cleanupGeneratedCacheSessionDirectory(removed.sourceDir(), "player-quit");
        }
        generatedCacheBatchQueues.remove(event.getPlayer().getUniqueId());
        playerNativeCacheSources.remove(event.getPlayer().getUniqueId());
        nativeRecentDecodeKeys.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(@NotNull String incomingChannel, @NotNull Player player, byte @NotNull [] message) {
        if (!channel.equals(incomingChannel)) {
            return;
        }

        YsmClientSession current = sessions.computeIfAbsent(
                player.getUniqueId(),
                ignored -> YsmClientSession.pending(player.getUniqueId(), player.getName()));

        try {
            int subpacketId = YsmProtocol.peekSubpacketId(message);
            if (debug) {
                getLogger().info("YSM <- " + player.getName() + " id=" + subpacketId + " bytes=" + message.length);
            }

            if (subpacketId == YsmProtocol.CLIENT_HANDSHAKE_ID) {
                String clientVersion = YsmProtocol.decodeClientHandshake(message);
                boolean compatible = protocolVersion.equals(clientVersion);
                sessions.put(player.getUniqueId(), current.withHandshakeResponse(clientVersion, compatible, Instant.now()));

                if (compatible) {
                    getLogger().info(player.getName() + " confirmed YSM protocol " + clientVersion + ".");
                    if (sendAuthorizedModelsOnHandshake) {
                        sendAuthorizedModelSet(player);
                    }
                    boolean bootstrapped = false;
                    boolean cacheReplayScheduled = false;
                    if (autoGeneratedCacheOnHandshake && modelRepositoryReloadInProgress) {
                        bootstrapped = true;
                        cacheReplayScheduled = true;
                        getLogger().info("YSM auto generated cache sync deferred until model scan finishes: player="
                                + player.getName() + ".");
                    } else if (autoGeneratedCacheOnHandshake && hasGeneratedCacheSource()) {
                        bootstrapped = scheduleAutoGeneratedCacheReplay(player);
                        cacheReplayScheduled = bootstrapped;
                    } else if (autoNativeCacheOnHandshake) {
                        bootstrapped = scheduleAutoNativeCacheReplay(player);
                        cacheReplayScheduled = bootstrapped;
                    } else if (experimentalBootstrapOnHandshake) {
                        bootstrapped = startNativeBootstrap(
                                null,
                                player,
                                experimentalBootstrapMode,
                                YsmNativeSyncPrototype.VARIANT_FULL,
                                0,
                                "handshake");
                    }
                    if (!bootstrapped) {
                        maybeLogMissingNativeSync(player);
                    }
                    if (cacheReplayScheduled) {
                        getLogger().info("YSM saved model restore deferred until native cache result: player="
                                + player.getName() + ".");
                    } else {
                        scheduleSavedModelRestore(player, MODEL_STATE_REPLAY_DELAY_TICKS, "handshake");
                        scheduleSavedModelRestore(player, MODEL_STATE_LATE_REPLAY_DELAY_TICKS, "handshake-late");
                        scheduleModelStateReplay(player, MODEL_STATE_REPLAY_DELAY_TICKS, "handshake");
                        scheduleModelStateReplay(player, MODEL_STATE_LATE_REPLAY_DELAY_TICKS, "handshake-late");
                    }
                } else {
                    getLogger().warning(player.getName() + " replied with unsupported YSM protocol " + clientVersion
                            + " (expected " + protocolVersion + ").");
                }
                return;
            }

            if (subpacketId == YsmProtocol.CLIENT_RAW_PACKET_ID) {
                byte[] rawPacketBody = YsmProtocol.decodeClientRawPacket(message);
                captureClientRawPacket(player, rawPacketBody);
                sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                    YsmClientSession base = existing != null
                            ? existing
                            : YsmClientSession.pending(player.getUniqueId(), player.getName());
                    return base.withClientRawPacketReceived(rawPacketBody.length, Instant.now());
                });
                inspectClientRawPacket(player, rawPacketBody);
                inspectReportNativePacket(player, rawPacketBody);
                inspectNativeCacheReplayPacket(player, rawPacketBody);
                return;
            }

            if (subpacketId == YsmProtocol.CLIENT_MODEL_SELECTION_ID) {
                YsmProtocol.ClientModelSelection selection = YsmProtocol.decodeClientModelSelection(message);
                sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                    YsmClientSession base = existing != null
                            ? existing
                            : YsmClientSession.pending(player.getUniqueId(), player.getName());
                    return base.withLastPacket(subpacketId, message.length, Instant.now());
                });
                handleClientModelSelection(player, selection);
                return;
            }

            if (subpacketId == YsmProtocol.CLIENT_ANIMATION_REQUEST_ID) {
                YsmProtocol.ClientAnimationRequest request = YsmProtocol.decodeClientAnimationRequest(message);
                sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                    YsmClientSession base = existing != null
                            ? existing
                            : YsmClientSession.pending(player.getUniqueId(), player.getName());
                    return base.withLastPacket(subpacketId, message.length, Instant.now());
                });
                handleClientAnimationRequest(player, request);
                return;
            }

            if (subpacketId == YsmProtocol.CLIENT_COMPLETE_FEEDBACK_ID) {
                YsmProtocol.ClientMolangFeedback feedback = YsmProtocol.decodeClientMolangFeedback(message);
                sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                    YsmClientSession base = existing != null
                            ? existing
                            : YsmClientSession.pending(player.getUniqueId(), player.getName());
                    return base.withLastPacket(subpacketId, message.length, Instant.now());
                });
                handleClientMolangFeedback(player, feedback);
                return;
            }

            if (subpacketId == YsmProtocol.CLIENT_ANIMATION_EXPRESSION_SYNC_ID) {
                float[] values = YsmProtocol.decodeClientAnimationExpressionSync(message);
                sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                    YsmClientSession base = existing != null
                            ? existing
                            : YsmClientSession.pending(player.getUniqueId(), player.getName());
                    return base.withLastPacket(subpacketId, message.length, Instant.now());
                });
                handleClientAnimationExpressionSync(player, values);
                return;
            }

            sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                YsmClientSession base = existing != null
                        ? existing
                        : YsmClientSession.pending(player.getUniqueId(), player.getName());
                return base.withLastPacket(subpacketId, message.length, Instant.now());
            });
        } catch (YsmProtocolException ex) {
            getLogger().warning("Failed to decode YSM packet from " + player.getName() + ": " + ex.getMessage());
            if (debug) {
                getLogger().warning("Raw payload: " + YsmProtocol.toHex(message, 96));
            }
        }
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            String @NotNull [] args) {
        if ("ysmclient".equalsIgnoreCase(command.getName())) {
            return handleYsmClientCompatibilityCommand(sender, args);
        }

        String action = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        if ("status".equals(action)) {
            return handleStatusCommand(sender, args);
        }

        if ("admin".equals(action)) {
            if (!requireAdmin(sender, "/ysm admin")) {
                return true;
            }
            return handleAdminCommand(sender, args);
        }

        if ("ping".equals(action)) {
            return handleYsmPingCompatibilityCommand(sender);
        }

        if ("auth".equals(action)) {
            return handleYsmAuthCompatibilityCommand(sender, args);
        }

        if ("model".equals(action)) {
            return handleYsmModelCompatibilityCommand(sender, args);
        }

        if ("play".equals(action)) {
            return handleYsmPlayCompatibilityCommand(sender, args);
        }

        if ("molang".equals(action)) {
            return handleYsmMolangCompatibilityCommand(sender, args);
        }

        if ("sync".equals(action)) {
            return handleSyncCommand(sender, args);
        }

        if ("diagnose".equals(action) || "diag".equals(action)) {
            return handleDiagnoseCommand(sender, args);
        }

        if ("models".equals(action)) {
            return handleModelsCommand(sender, args);
        }

        if ("source".equals(action)) {
            if (!requireAdmin(sender, "/ysm source")) {
                return true;
            }
            return handleSourceCommand(sender, args);
        }

        if ("config".equals(action)) {
            if (!requireAdmin(sender, "/ysm config")) {
                return true;
            }
            return handleConfigCommand(sender, args);
        }

        if ("debug".equals(action)) {
            if (!requireAdmin(sender, "/ysm debug")) {
                return true;
            }
            return handleDebugCommand(sender, args);
        }

        if ("handshake".equals(action)) {
            if (!requireAdmin(sender, "/ysm handshake")) {
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm handshake <player>");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                return true;
            }
            sendHandshake(player);
            sender.sendMessage(ChatColor.GREEN + "Sent YSM handshake to " + player.getName() + ".");
            return true;
        }

        if ("apply".equals(action)) {
            if (!requireAdmin(sender, "/ysm apply")) {
                return true;
            }
            return handleApplyCommand(sender, args);
        }

        if ("dist".equals(action) || "distribution".equals(action)) {
            if (!requireAdmin(sender, "/ysm dist")) {
                return true;
            }
            try {
                return handleDistributionCommand(sender, args);
            } catch (RuntimeException ex) {
                getLogger().log(Level.WARNING, "Unhandled PaperYSM distribution command failure: "
                        + String.join(" ", args), ex);
                sender.sendMessage(ChatColor.RED + "PaperYSM distribution command failed: "
                        + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
                return true;
            }
        }

        if ("native".equals(action)) {
            if (!requireAdmin(sender, "/ysm native")) {
                return true;
            }
            if (args.length >= 2 && "selftest".equalsIgnoreCase(args[1])) {
                YsmCryptoSelfTest.Result result = YsmCryptoSelfTest.run();
                sender.sendMessage((result.success() ? ChatColor.GREEN : ChatColor.RED)
                        + "YSM native selftest: " + result.describe());
                return true;
            }
            sender.sendMessage(ChatColor.AQUA + "YSM native bridge: "
                    + nativeBridgeManager.bridge().implementationName());
            return true;
        }

        sendMainUsage(sender);
        return true;
    }

    private boolean handleStatusCommand(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                return true;
            }
            if (!canInspectPlayer(sender, player)) {
                sender.sendMessage(ChatColor.RED + "普通玩家只能查看自己的 YSM 状态。");
                return true;
            }
            sendStatus(sender, player);
            sendNativeCacheReplayStatus(sender, player);
            return true;
        }

        if (sender instanceof Player player && !isAdmin(sender)) {
            sendStatus(sender, player);
            sendNativeCacheReplayStatus(sender, player);
            sender.sendMessage(ChatColor.GRAY + "Use /ysm sync to request model cache sync.");
            return true;
        }

        sender.sendMessage(ChatColor.AQUA + "PaperYSM sessions: " + sessions.size()
                + ", default cache source=" + nativeCacheDefaultSource()
                + ", autosync=" + autoNativeCacheOnHandshake
                + ", speed=" + autoNativeCacheIntervalTicks + "t/" + autoNativeCacheChunkBytes + "b.");
        for (Player player : Bukkit.getOnlinePlayers()) {
            sendStatus(sender, player);
            sendNativeCacheReplayStatus(sender, player);
        }
        return true;
    }

    private boolean handleSyncCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm sync <player|all> [modelId|all]");
                return true;
            }
            startDefaultModelCacheSync(sender, player);
            return true;
        }

        if (!requireAdmin(sender, "/ysm sync <player|all> [modelId|all]")) {
            return true;
        }

        String targetName = args[1];
        String modelId = args.length >= 3 ? args[2].trim() : "all";
        syncGeneratedServerCacheCatalog(sender, targetName, modelId, "command-root-sync");
        return true;
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "help";
        if ("help".equals(action) || "?".equals(action)) {
            sendAdminUsage(sender);
            return true;
        }
        if ("status".equals(action)) {
            sendDistributionStatus(sender);
            return true;
        }
        if ("scan".equals(action) || "reload".equals(action)) {
            scheduleModelRepositoryReload(true, sender);
            return true;
        }
        if ("source".equals(action)) {
            if (args.length < 3 || "status".equalsIgnoreCase(args[2])) {
                sender.sendMessage(ChatColor.AQUA + "PaperYSM default native source: " + nativeCacheDefaultSource());
                sender.sendMessage(ChatColor.GRAY + "Usage: /ysm admin source <cacheSource|clear>");
                return true;
            }
            String source = "clear".equalsIgnoreCase(args[2]) ? DEFAULT_NATIVE_CACHE_SOURCE : args[2].trim();
            if (source.isBlank()) {
                sender.sendMessage(ChatColor.RED + "Cache source cannot be empty.");
                return true;
            }
            setDefaultNativeCacheSource(source, true);
            playerNativeCacheSources.clear();
            sender.sendMessage(ChatColor.GREEN + "PaperYSM default native source is now " + nativeCacheDefaultSource()
                    + "; online per-player overrides were cleared.");
            return true;
        }
        if ("cache".equals(action) || "generate".equals(action) || "gen".equals(action)) {
            if (args.length >= 3 && ("compact".equalsIgnoreCase(args[2]) || "index".equalsIgnoreCase(args[2]))) {
                compactGeneratedServerCacheIndex(sender);
                return true;
            }
            String modelId = args.length >= 3 ? args[2] : "all";
            startGeneratedServerCachePrewarmAsync(sender, modelId, null, false, "admin-cache");
            return true;
        }
        if ("incremental".equals(action) || "inc".equals(action) || "update".equals(action)) {
            String modelId = args.length >= 3 ? args[2] : "all";
            startGeneratedServerCachePrewarmAsync(sender, modelId, "all", false, "admin-incremental");
            return true;
        }
        if ("fullsync".equals(action) || "full".equals(action)) {
            String modelId = args.length >= 3 ? args[2] : "all";
            startGeneratedServerCachePrewarmAsync(sender, modelId, "all", true, "admin-fullsync");
            return true;
        }
        if ("syncall".equals(action) || "sync".equals(action) || "latest".equals(action)) {
            String modelId = args.length >= 3 ? args[2] : "all";
            syncGeneratedServerCacheCatalog(sender, "all", modelId, "admin-syncall");
            return true;
        }
        if ("speed".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.AQUA + "PaperYSM generated sync speed: chunkBytes="
                        + autoGeneratedCacheChunkBytes
                        + ", type5PacketsPerTick=" + autoGeneratedCacheType5PacketsPerTick
                        + ", intervalTicks=" + autoGeneratedCacheIntervalTicks + ".");
                sender.sendMessage(ChatColor.GRAY + "Usage: /ysm admin speed <chunkBytes> <type5PacketsPerTick> [intervalTicks]");
                return true;
            }
            autoGeneratedCacheChunkBytes = parsePositiveInt(args[2], autoGeneratedCacheChunkBytes);
            autoGeneratedCacheType5PacketsPerTick = parsePositiveInt(args[3], autoGeneratedCacheType5PacketsPerTick);
            if (args.length >= 5) {
                autoGeneratedCacheIntervalTicks = parsePositiveInt(args[4], autoGeneratedCacheIntervalTicks);
            }
            getConfig().set("sync.auto-generated-cache-chunk-bytes", autoGeneratedCacheChunkBytes);
            getConfig().set("sync.auto-generated-cache-type5-packets-per-tick", autoGeneratedCacheType5PacketsPerTick);
            getConfig().set("sync.auto-generated-cache-interval-ticks", autoGeneratedCacheIntervalTicks);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "PaperYSM generated sync speed updated: chunkBytes="
                    + autoGeneratedCacheChunkBytes
                    + ", type5PacketsPerTick=" + autoGeneratedCacheType5PacketsPerTick
                    + ", intervalTicks=" + autoGeneratedCacheIntervalTicks + ".");
            return true;
        }
        if ("debug".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.AQUA + "PaperYSM debug=" + debug
                        + ", packetDetails=" + logPacketDetails
                        + ", progressIntervalModels=" + logProgressIntervalModels + ".");
                sender.sendMessage(ChatColor.GRAY + "Usage: /ysm admin debug <on|off>");
                return true;
            }
            Boolean value = parseToggle(sender, args, 2, "debug");
            if (value == null) {
                return true;
            }
            debug = value;
            getConfig().set("debug", debug);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "PaperYSM debug is now " + debug + ".");
            return true;
        }

        sendAdminUsage(sender);
        return true;
    }

    private void sendAdminUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Usage: /ysm admin <source|scan|cache|incremental|fullsync|syncall|speed|debug|status>");
        sender.sendMessage(ChatColor.GRAY + "/ysm admin cache [all|modelId] - generate/update OpenYSM server-cache.");
        sender.sendMessage(ChatColor.GRAY + "/ysm admin cache compact - rewrite the generated cache index and name map.");
        sender.sendMessage(ChatColor.GRAY + "/ysm admin incremental [all|modelId] - generate changed cache, then sync online players if changed.");
        sender.sendMessage(ChatColor.GRAY + "/ysm admin fullsync [all|modelId] - check cache, then force sync all compatible online players.");
        sender.sendMessage(ChatColor.GRAY + "/ysm admin syncall [all|modelId] - send the existing cache catalog to all compatible online players.");
    }

    private boolean handleYsmPingCompatibilityCommand(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.AQUA + "PaperYSM protocol shim: " + protocolVersion);
            return true;
        }
        sendHandshake(player);
        sendAuthorizedModelSet(player);
        sender.sendMessage(ChatColor.GREEN + "PaperYSM protocol shim: " + protocolVersion + ", auth list refreshed.");
        return true;
    }

    private boolean handleYsmAuthCompatibilityCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm auth <player|all> <all|clear|add|remove> [modelId]");
                return true;
            }
            sendAuthorizedModelSet(player);
            sender.sendMessage(ChatColor.GREEN + "Refreshed your YSM authorized model list.");
            return true;
        }

        if (!requireAdmin(sender, "/ysm auth")) {
            return true;
        }
        List<Player> targets = resolveCompatibilityTargets(sender, args[1], "/ysm auth <player|all> <all|clear|add|remove> [modelId]");
        if (targets.isEmpty()) {
            return true;
        }

        String mode = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "all";
        if ("clear".equals(mode)) {
            for (Player target : targets) {
                sendEmptyModelSet(target, 6, "compat-auth-clear");
            }
            sender.sendMessage(ChatColor.GREEN + "Cleared YSM authorized model list for " + targets.size() + " player(s).");
            return true;
        }

        if ("add".equals(mode) || "remove".equals(mode)) {
            String modelId = args.length >= 4 ? args[3] : "";
            if (modelId.isBlank()) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm auth <player|all> " + mode + " <modelId>");
                return true;
            }
            if ("remove".equals(mode)) {
                sender.sendMessage(ChatColor.YELLOW
                        + "PaperYSM currently treats auth as a full server catalog; remove is acknowledged but not persisted.");
            }
        }

        int sent = 0;
        for (Player target : targets) {
            sendAuthorizedModelSet(target);
            sent++;
        }
        sender.sendMessage(ChatColor.GREEN + "Sent full YSM authorized model catalog to " + sent + " player(s).");
        return true;
    }

    private boolean handleYsmModelCompatibilityCommand(CommandSender sender, String[] args) {
        if (args.length >= 2 && "reload".equalsIgnoreCase(args[1])) {
            if (!requireAdmin(sender, "/ysm model reload")) {
                return true;
            }
            scheduleModelRepositoryReload(true, sender);
            return true;
        }

        if (args.length >= 2 && "set".equalsIgnoreCase(args[1])) {
            if (!requireAdmin(sender, "/ysm model set")) {
                return true;
            }
            if (args.length < 5) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm model set <player|all> <modelId> <textureId|-> [ignoreAuth]");
                return true;
            }
            List<Player> targets = resolveCompatibilityTargets(sender, args[2],
                    "/ysm model set <player|all> <modelId> <textureId|-> [ignoreAuth]");
            if (targets.isEmpty()) {
                return true;
            }
            String modelId = args[3];
            String textureId = normalizeCompatibilityTexture(modelId, args[4]);
            int applied = 0;
            for (Player target : targets) {
                if (applyModelSelection(sender, target, modelId, textureId, false, "compat-model-set").applied()) {
                    applied++;
                }
            }
            sender.sendMessage(ChatColor.GREEN + "Applied YSM model to " + applied + "/" + targets.size() + " player(s).");
            return true;
        }

        if (args.length >= 2 && "disable".equalsIgnoreCase(args[1])) {
            if (!requireAdmin(sender, "/ysm model disable")) {
                return true;
            }
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm model disable <player|all> <true|false>");
                return true;
            }
            List<Player> targets = resolveCompatibilityTargets(sender, args[2],
                    "/ysm model disable <player|all> <true|false>");
            if (targets.isEmpty()) {
                return true;
            }
            boolean disabled = Boolean.parseBoolean(args[3]);
            int applied = 0;
            for (Player target : targets) {
                AppliedModelState current = appliedModelStates.get(target.getUniqueId());
                String modelId = current == null ? "default" : current.modelId();
                String textureId = current == null ? "default" : current.textureId();
                if (applyModelSelection(sender, target, modelId, textureId, disabled, "compat-model-disable").applied()) {
                    applied++;
                }
            }
            sender.sendMessage(ChatColor.GREEN + "Updated YSM disabled state for " + applied + "/" + targets.size()
                    + " player(s).");
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm model <reload|set|disable> ...");
            return true;
        }
        if (args.length < 2) {
            AppliedModelState current = appliedModelStates.get(player.getUniqueId());
            if (current == null) {
                sender.sendMessage(ChatColor.GRAY + "No PaperYSM model state is currently applied.");
            } else {
                sender.sendMessage(ChatColor.AQUA + "Current PaperYSM model: "
                        + current.modelId() + "/" + current.textureId()
                        + ", disabled=" + current.disabled());
            }
            return true;
        }

        String modelId = args[1];
        String textureId = normalizeCompatibilityTexture(modelId, args.length >= 3 ? args[2] : "-");
        ModelSelectionApplyResult result = applyModelSelection(null, player, modelId, textureId, false, "compat-model-self");
        sender.sendMessage((result.applied() ? ChatColor.GREEN : ChatColor.RED) + result.message());
        return true;
    }

    private boolean handleYsmPlayCompatibilityCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm play <animation> or /ysm play <player|all> <animation>");
            return true;
        }

        List<Player> targets;
        String animationName;
        if (args.length >= 3) {
            if (!requireAdmin(sender, "/ysm play <player|all> <animation>")) {
                return true;
            }
            targets = resolveCompatibilityTargets(sender, args[1], "/ysm play <player|all> <animation>");
            animationName = args[2];
        } else if (sender instanceof Player player) {
            targets = List.of(player);
            animationName = args[1];
        } else {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm play <player|all> <animation>");
            return true;
        }

        int sentTargets = 0;
        for (Player target : targets) {
            sentTargets += broadcastAnimation(target, "stop".equalsIgnoreCase(animationName) ? "" : animationName,
                    "compat-play");
        }
        sender.sendMessage(ChatColor.GREEN + "Relayed YSM animation to " + targets.size()
                + " target(s), viewer packets=" + sentTargets + ".");
        return true;
    }

    private boolean handleYsmMolangCompatibilityCommand(CommandSender sender, String[] args) {
        if (!requireAdmin(sender, "/ysm molang")) {
            return true;
        }
        if (args.length < 4 || !"execute".equalsIgnoreCase(args[1])) {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm molang execute <player|all> <expression>");
            return true;
        }
        List<Player> targets = resolveCompatibilityTargets(sender, args[2],
                "/ysm molang execute <player|all> <expression>");
        if (targets.isEmpty()) {
            return true;
        }
        String expression = String.join(" ", Arrays.copyOfRange(args, 3, args.length)).trim();
        if (expression.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "MoLang expression cannot be empty.");
            return true;
        }
        int[] entityIds = targets.stream().mapToInt(Player::getEntityId).toArray();
        byte[] payload = YsmProtocol.encodeMolangExecute(entityIds, expression);
        int sent = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
            if (viewerSession != null && viewerSession.compatible()) {
                sendYsmPayload(viewer, payload, "compat-molang");
                sent++;
            }
        }
        sender.sendMessage(ChatColor.GREEN + "Relayed YSM MoLang expression to " + sent + " compatible viewer(s).");
        return true;
    }

    private boolean handleYsmClientCompatibilityCommand(CommandSender sender, String[] args) {
        String action = args.length == 0 ? "debug" : args[0].toLowerCase(Locale.ROOT);
        if ("debug".equals(action)) {
            if (args.length >= 2 && isAdmin(sender)) {
                Boolean value = parseToggle(sender, args, 1, "debug");
                if (value != null) {
                    debug = value;
                    getConfig().set("debug", debug);
                    saveConfig();
                }
            }
            sender.sendMessage(ChatColor.AQUA + "PaperYSM /ysmclient debug shim: server raw logging=" + debug + ".");
            sender.sendMessage(ChatColor.GRAY + "Client overlay/debug watches still run inside the local YSM/OpenYSM mod.");
            return true;
        }
        if ("molang".equals(action) || "watch".equals(action)) {
            sender.sendMessage(ChatColor.YELLOW + "/ysmclient " + action
                    + " is client-local in OpenYSM; PaperYSM accepts the command but cannot control the local overlay.");
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Usage: /ysmclient <debug|molang|watch>");
        return true;
    }

    private boolean handleDiagnoseCommand(CommandSender sender, String[] args) {
        if (args.length >= 2) {
            Player player = Bukkit.getPlayerExact(args[1]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
                return true;
            }
            if (!canInspectPlayer(sender, player)) {
                sender.sendMessage(ChatColor.RED + "普通玩家只能诊断自己的 YSM 同步状态。");
                return true;
            }
            sendDistributionDiagnostics(sender, player.getName());
            return true;
        }

        if (sender instanceof Player player && !isAdmin(sender)) {
            sendDistributionDiagnostics(sender, player.getName());
            return true;
        }
        sendDistributionDiagnostics(sender, null);
        return true;
    }

    private boolean handleSourceCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(ChatColor.AQUA + "Default YSM cache source: " + nativeCacheDefaultSource());
            if (playerNativeCacheSources.isEmpty()) {
                sender.sendMessage(ChatColor.GRAY + "No online player cache source overrides.");
            } else {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    String override = playerNativeCacheSources.get(player.getUniqueId());
                    if (override != null) {
                        sender.sendMessage(ChatColor.GRAY + "- " + player.getName() + ": " + override);
                    }
                }
            }
            sender.sendMessage(ChatColor.GRAY + "Usage: /ysm source <default|all|player> <cacheSource|clear>");
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm source <default|all|player> <cacheSource|clear>");
            return true;
        }

        String target = args[1];
        String source = args[2].trim();
        if (source.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Cache source cannot be empty.");
            return true;
        }

        if ("default".equalsIgnoreCase(target) || "all".equalsIgnoreCase(target) || "*".equals(target)) {
            if ("clear".equalsIgnoreCase(source)) {
                source = DEFAULT_NATIVE_CACHE_SOURCE;
            }
            setDefaultNativeCacheSource(source, true);
            if ("all".equalsIgnoreCase(target) || "*".equals(target)) {
                playerNativeCacheSources.clear();
            }
            sender.sendMessage(ChatColor.GREEN + "Default YSM cache source is now " + source + ".");
            return true;
        }

        Player player = Bukkit.getPlayerExact(target);
        if (player == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + target);
            return true;
        }
        if ("clear".equalsIgnoreCase(source)) {
            playerNativeCacheSources.remove(player.getUniqueId());
            sender.sendMessage(ChatColor.GREEN + "Cleared " + player.getName()
                    + " cache source override; current source=" + nativeCacheSourceFor(player) + ".");
            return true;
        }
        playerNativeCacheSources.put(player.getUniqueId(), source);
        sender.sendMessage(ChatColor.GREEN + "Set " + player.getName()
                + " cache source to " + source + ".");
        return true;
    }

    private boolean handleConfigCommand(CommandSender sender, String[] args) {
        String key = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("status".equals(key)) {
            sender.sendMessage(ChatColor.AQUA + "YSM cache source: " + nativeCacheDefaultSource());
            sender.sendMessage(ChatColor.AQUA + "YSM animation model-dir: "
                    + resolvePluginPath(modelsDir).toAbsolutePath());
            sender.sendMessage(ChatColor.AQUA + "YSM autosync: " + autoNativeCacheOnHandshake
                    + ", delay=" + autoNativeCacheDelayTicks
                    + "t, speed=" + autoNativeCacheIntervalTicks + "t/"
                    + autoNativeCacheChunkBytes + "b.");
            sender.sendMessage(ChatColor.AQUA + "YSM generated sync: " + autoGeneratedCacheOnHandshake
                    + ", model=" + autoGeneratedCacheModelId
                    + ", batch=" + autoGeneratedCacheMaxModels
                    + ", speed=" + autoGeneratedCacheIntervalTicks + "t/"
                    + autoGeneratedCacheChunkBytes + "b"
                    + ", burst=" + autoGeneratedCacheType5PacketsPerTick + "/tick.");
            sender.sendMessage(ChatColor.AQUA + "YSM generated prewarm: " + autoGeneratedCachePrewarmOnStartup
                    + ", model=" + autoGeneratedCachePrewarmModelId
                    + ", delay=" + autoGeneratedCachePrewarmDelayTicks + "t"
                    + ", syncOnline=" + autoGeneratedCacheSyncOnlineAfterPrewarm + ".");
            sender.sendMessage(ChatColor.AQUA + "YSM debug: raw=" + debug
                    + ", packetLog=" + logPacketDetails
                    + ", modelScanLog=" + logModelScanDetails
                    + ", progressEvery=" + logProgressIntervalModels + " model(s)"
                    + ", captureClientRaw=" + captureClientRawPackets + ".");
            sender.sendMessage(ChatColor.GRAY
                    + "Usage: /ysm config <source|speed|generatedspeed|autosync|packetlog|modelscan|modeldir|capture> ...");
            return true;
        }
        if ("source".equals(key)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm config source <cacheSource>");
                return true;
            }
            setDefaultNativeCacheSource(args[2], true);
            playerNativeCacheSources.clear();
            sender.sendMessage(ChatColor.GREEN + "Default YSM cache source is now " + nativeCacheDefaultSource() + ".");
            return true;
        }
        if ("speed".equals(key)) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm config speed <intervalTicks> <chunkBytes>");
                return true;
            }
            autoNativeCacheIntervalTicks = parsePositiveInt(args[2], autoNativeCacheIntervalTicks);
            autoNativeCacheChunkBytes = parsePositiveInt(args[3], autoNativeCacheChunkBytes);
            getConfig().set("sync.auto-native-cache-interval-ticks", autoNativeCacheIntervalTicks);
            getConfig().set("sync.auto-native-cache-chunk-bytes", autoNativeCacheChunkBytes);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM native cache speed is now interval="
                    + autoNativeCacheIntervalTicks + " tick(s), chunkBytes=" + autoNativeCacheChunkBytes + ".");
            return true;
        }
        if ("autosync".equals(key)) {
            Boolean value = parseToggle(sender, args, 2, "autosync");
            if (value == null) {
                return true;
            }
            autoNativeCacheOnHandshake = value;
            getConfig().set("sync.auto-native-cache-on-handshake", autoNativeCacheOnHandshake);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM auto native cache sync is now " + autoNativeCacheOnHandshake + ".");
            return true;
        }
        if ("packetlog".equals(key)) {
            Boolean value = parseToggle(sender, args, 2, "packetlog");
            if (value == null) {
                return true;
            }
            logPacketDetails = value;
            getConfig().set("logging.packet-details", logPacketDetails);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM packet detail logging is now " + logPacketDetails + ".");
            return true;
        }
        if ("modelscan".equals(key)) {
            Boolean value = parseToggle(sender, args, 2, "modelscan");
            if (value == null) {
                return true;
            }
            logModelScanDetails = value;
            getConfig().set("logging.model-scan-details", logModelScanDetails);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM model scan detail logging is now " + logModelScanDetails + ".");
            return true;
        }
        if ("generatedspeed".equals(key) || "genspeed".equals(key)) {
            if (args.length < 5) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm config generatedspeed <batchModels> <chunkBytes> <type5PacketsPerTick> [intervalTicks]");
                return true;
            }
            autoGeneratedCacheMaxModels = parseNonNegativeInt(args[2], autoGeneratedCacheMaxModels);
            autoGeneratedCacheChunkBytes = parsePositiveInt(args[3], autoGeneratedCacheChunkBytes);
            autoGeneratedCacheType5PacketsPerTick = parsePositiveInt(args[4], autoGeneratedCacheType5PacketsPerTick);
            if (args.length >= 6) {
                autoGeneratedCacheIntervalTicks = parsePositiveInt(args[5], autoGeneratedCacheIntervalTicks);
            }
            getConfig().set("sync.auto-generated-cache-max-models", autoGeneratedCacheMaxModels);
            getConfig().set("sync.auto-generated-cache-chunk-bytes", autoGeneratedCacheChunkBytes);
            getConfig().set("sync.auto-generated-cache-type5-packets-per-tick", autoGeneratedCacheType5PacketsPerTick);
            getConfig().set("sync.auto-generated-cache-interval-ticks", autoGeneratedCacheIntervalTicks);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM generated cache speed is now batchModels="
                    + autoGeneratedCacheMaxModels
                    + ", interval=" + autoGeneratedCacheIntervalTicks
                    + " tick(s), chunkBytes=" + autoGeneratedCacheChunkBytes
                    + ", type5PacketsPerTick=" + autoGeneratedCacheType5PacketsPerTick + ".");
            return true;
        }
        if ("modeldir".equals(key) || "modelsdir".equals(key)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.AQUA + "YSM animation model-dir: "
                        + resolvePluginPath(modelsDir).toAbsolutePath());
                sender.sendMessage(ChatColor.GRAY + "Usage: /ysm config modeldir <path>");
                return true;
            }
            String next = String.join(" ", Arrays.copyOfRange(args, 2, args.length)).trim();
            if (next.isEmpty()) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm config modeldir <path>");
                return true;
            }
            modelsDir = next;
            getConfig().set("models-dir", modelsDir);
            saveConfig();
            scheduleModelRepositoryReload(true, sender);
            sender.sendMessage(ChatColor.GREEN + "YSM animation model-dir is now "
                    + resolvePluginPath(modelsDir).toAbsolutePath() + ".");
            sender.sendMessage(ChatColor.GRAY
                    + "This directory is only used as an animation/model metadata reference; native cache sync can still run without it.");
            return true;
        }
        if ("capture".equals(key)) {
            Boolean value = parseToggle(sender, args, 2, "capture");
            if (value == null) {
                return true;
            }
            captureClientRawPackets = value;
            getConfig().set("capture.client-raw-packets", captureClientRawPackets);
            saveConfig();
            sender.sendMessage(ChatColor.GREEN + "YSM client raw packet capture is now " + captureClientRawPackets + ".");
            return true;
        }

        sender.sendMessage(ChatColor.RED
                + "Usage: /ysm config <status|source|speed|generatedspeed|autosync|packetlog|modelscan|modeldir|capture>");
        return true;
    }

    private boolean handleDebugCommand(CommandSender sender, String[] args) {
        if (args.length == 1 || "status".equalsIgnoreCase(args[1])) {
            sender.sendMessage(ChatColor.AQUA + "PaperYSM debug raw packet logging: " + debug);
            sender.sendMessage(ChatColor.GRAY + "Usage: /ysm debug <on|off>");
            return true;
        }
        Boolean value = parseToggle(sender, args, 1, "debug");
        if (value == null) {
            return true;
        }
        debug = value;
        getConfig().set("debug", debug);
        saveConfig();
        sender.sendMessage(ChatColor.GREEN + "PaperYSM debug raw packet logging is now " + debug + ".");
        return true;
    }

    private void sendMainUsage(CommandSender sender) {
        sender.sendMessage(ChatColor.RED + "Usage: /ysm <sync|ping|auth|model|play|molang>");
        if (isAdmin(sender)) {
            sender.sendMessage(ChatColor.GRAY + "Admin: /ysm admin <source|scan|cache|incremental|fullsync|syncall|speed|debug|status>");
        }
    }

    private boolean canInspectPlayer(CommandSender sender, Player player) {
        return isAdmin(sender)
                || (sender instanceof Player self && self.getUniqueId().equals(player.getUniqueId()));
    }

    private boolean isAdmin(CommandSender sender) {
        return !(sender instanceof Player) || sender.hasPermission("paperysm.admin") || sender.isOp();
    }

    private boolean requireAdmin(CommandSender sender, String usage) {
        if (isAdmin(sender)) {
            return true;
        }
        sender.sendMessage(ChatColor.RED + "Only OP/admin can use " + usage + ".");
        return false;
    }

    private boolean verboseLogging() {
        return debug || logPacketDetails;
    }

    private boolean shouldLogRawPacket(String reason) {
        if (!verboseLogging()) {
            return false;
        }
        if (reason != null && reason.contains(":type5-")) {
            return logPacketDetails;
        }
        return true;
    }

    private List<Player> resolveCompatibilityTargets(CommandSender sender, String targetName, String usage) {
        if ("all".equalsIgnoreCase(targetName) || "*".equals(targetName)) {
            if (!requireAdmin(sender, usage)) {
                return List.of();
            }
            List<Player> players = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                players.add(player);
            }
            return players;
        }

        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + targetName);
            return List.of();
        }
        if (!canInspectPlayer(sender, target)) {
            sender.sendMessage(ChatColor.RED + "普通玩家只能操作自己的 YSM 状态。");
            return List.of();
        }
        return List.of(target);
    }

    private String normalizeCompatibilityTexture(String modelId, String textureId) {
        if (textureId != null && !textureId.isBlank() && !"-".equals(textureId)) {
            return textureId;
        }
        return findModelEntry(modelId)
                .map(YsmModelRepository.Entry::profile)
                .map(YsmModelProfile::defaultTexture)
                .filter(value -> !value.isBlank())
                .orElse("default");
    }

    private int broadcastAnimation(Player target, String animationName, String reason) {
        String safeAnimationName = animationName == null ? "" : animationName;
        rememberActiveAnimation(target, safeAnimationName);
        byte[] payload = YsmProtocol.encodePlayerAnimationSwitch(target.getEntityId(), safeAnimationName);
        int sent = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
            if (viewerSession != null && viewerSession.compatible()) {
                sendYsmPayload(viewer, payload, "animation:" + reason + ":" + target.getName());
                sent++;
            }
        }
        if (debug) {
            getLogger().info("YSM animation relayed: target=" + target.getName()
                    + ", name=" + logValue(safeAnimationName)
                    + ", viewers=" + sent
                    + ", reason=" + reason + ".");
        }
        return sent;
    }

    private String nativeCacheDefaultSource() {
        return autoNativeCacheCaptureName == null || autoNativeCacheCaptureName.isBlank()
                ? DEFAULT_NATIVE_CACHE_SOURCE
                : autoNativeCacheCaptureName.trim();
    }

    private void setDefaultNativeCacheSource(String source, boolean persist) {
        autoNativeCacheCaptureName = source == null || source.isBlank() ? DEFAULT_NATIVE_CACHE_SOURCE : source.trim();
        if (persist) {
            getConfig().set("sync.auto-native-cache-capture", autoNativeCacheCaptureName);
            saveConfig();
        }
    }

    private List<String> nativeCacheSourceSuggestions(String prefix) {
        String normalizedPrefix = prefix.toLowerCase(Locale.ROOT);
        Path root = getDataFolder().toPath().resolve(NATIVE_CACHE_REPLAY_ROOT);
        Path legacyRoot = getDataFolder().toPath().resolve(LEGACY_NATIVE_CACHE_REPLAY_ROOT);
        List<String> sources = new ArrayList<>(List.of(nativeCacheDefaultSource(), DEFAULT_NATIVE_CACHE_SOURCE));
        for (Path candidateRoot : List.of(root, legacyRoot)) {
            if (Files.isDirectory(candidateRoot)) {
                try {
                    Files.list(candidateRoot)
                            .filter(Files::isDirectory)
                            .map(path -> path.getFileName().toString())
                            .forEach(sources::add);
                } catch (IOException ignored) {
                    // Tab completion should stay best-effort.
                }
            }
        }
        return sources.stream()
                .distinct()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalizedPrefix))
                .limit(20)
                .toList();
    }

    private static @Nullable Boolean parseToggle(CommandSender sender, String[] args, int index, String name) {
        if (args.length <= index) {
            String usage = "debug".equals(name) ? "/ysm debug" : "/ysm config " + name;
            sender.sendMessage(ChatColor.RED + "Usage: " + usage + " <on|off>");
            return null;
        }
        String value = args[index].toLowerCase(Locale.ROOT);
        return switch (value) {
            case "on", "true", "yes", "1", "enable", "enabled" -> true;
            case "off", "false", "no", "0", "disable", "disabled" -> false;
            default -> {
                sender.sendMessage(ChatColor.RED + "Expected on/off for " + name + ", got: " + args[index]);
                yield null;
            }
        };
    }

    @Override
    public java.util.List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
        String @NotNull [] args) {
        boolean admin = isAdmin(sender);
        if ("ysmclient".equalsIgnoreCase(command.getName())) {
            if (args.length == 1) {
                return List.of("debug", "molang", "watch").stream()
                        .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 2 && "debug".equalsIgnoreCase(args[0]) && admin) {
                return List.of("on", "off").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if (args.length == 1) {
            List<String> roots = new ArrayList<>(List.of(
                    "sync", "ping", "auth", "model", "play", "molang"));
            if (admin) {
                roots.add("admin");
            }
            return roots.stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        if ("admin".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                return List.of("source", "scan", "cache", "incremental", "fullsync", "syncall", "speed", "debug", "status").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && ("cache".equalsIgnoreCase(args[1])
                    || "generate".equalsIgnoreCase(args[1])
                    || "gen".equalsIgnoreCase(args[1])
                    || "incremental".equalsIgnoreCase(args[1])
                    || "inc".equalsIgnoreCase(args[1])
                    || "update".equalsIgnoreCase(args[1])
                    || "fullsync".equalsIgnoreCase(args[1])
                    || "full".equalsIgnoreCase(args[1])
                    || "syncall".equalsIgnoreCase(args[1])
                    || "sync".equalsIgnoreCase(args[1])
                    || "latest".equalsIgnoreCase(args[1]))) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> suggestions = new ArrayList<>();
                suggestions.add("all");
                modelRepository.entries().stream()
                        .map(YsmModelRepository.Entry::modelId)
                        .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .limit(20)
                        .forEach(suggestions::add);
                return suggestions.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 3 && "source".equalsIgnoreCase(args[1])) {
                List<String> values = new ArrayList<>(nativeCacheSourceSuggestions(args[2]));
                if ("clear".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    values.add("clear");
                }
                if ("status".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    values.add("status");
                }
                return values.stream().distinct().toList();
            }
            if (args.length == 3 && "debug".equalsIgnoreCase(args[1])) {
                return List.of("on", "off").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && "speed".equalsIgnoreCase(args[1])) {
                return List.of("65536", "49152", "32768").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 4 && "speed".equalsIgnoreCase(args[1])) {
                return List.of("2", "3", "4", "8").stream()
                        .filter(value -> value.startsWith(args[3].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 5 && "speed".equalsIgnoreCase(args[1])) {
                return List.of("1", "2").stream()
                        .filter(value -> value.startsWith(args[4].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if ("sync".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("all"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 3) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> suggestions = new ArrayList<>();
                suggestions.add("all");
                modelRepository.entries().stream()
                        .map(YsmModelRepository.Entry::modelId)
                        .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .limit(20)
                        .forEach(suggestions::add);
                return suggestions.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }
        if ("auth".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("all"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 3) {
                return List.of("all", "clear", "add", "remove").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 4 && ("add".equalsIgnoreCase(args[2]) || "remove".equalsIgnoreCase(args[2]))) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return modelRepository.entries().stream()
                        .map(YsmModelRepository.Entry::modelId)
                        .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .limit(20)
                        .toList();
            }
            return List.of();
        }
        if ("model".equals(root)) {
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> suggestions = new ArrayList<>();
                if (admin) {
                    suggestions.addAll(List.of("reload", "set", "disable"));
                }
                modelRepository.entries().stream()
                        .map(YsmModelRepository.Entry::modelId)
                        .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .limit(20)
                        .forEach(suggestions::add);
                return suggestions.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 3 && ("set".equalsIgnoreCase(args[1]) || "disable".equalsIgnoreCase(args[1])) && admin) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("all"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 4 && "set".equalsIgnoreCase(args[1]) && admin) {
                String prefix = args[3].toLowerCase(Locale.ROOT);
                return modelRepository.entries().stream()
                        .map(YsmModelRepository.Entry::modelId)
                        .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .limit(20)
                        .toList();
            }
            if (args.length == 5 && "set".equalsIgnoreCase(args[1]) && admin) {
                return List.of("-", "default", "texture").stream()
                        .filter(value -> value.startsWith(args[4].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 4 && "disable".equalsIgnoreCase(args[1]) && admin) {
                return List.of("false", "true").stream()
                        .filter(value -> value.startsWith(args[3].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if ("play".equals(root)) {
            if (args.length == 2 && admin) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("all", "stop"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }
        if ("molang".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                return List.of("execute").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && "execute".equalsIgnoreCase(args[1])) {
                String prefix = args[2].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("all"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }
        if ("status".equals(root) || "diagnose".equals(root) || "diag".equals(root)) {
            if (args.length == 2 && admin) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                return Bukkit.getOnlinePlayers().stream()
                        .map(Player::getName)
                        .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            return List.of();
        }
        if ("source".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                String prefix = args[1].toLowerCase(Locale.ROOT);
                List<String> targets = new ArrayList<>(List.of("default", "all"));
                targets.addAll(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList());
                return targets.stream()
                        .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                        .toList();
            }
            if (args.length == 3) {
                List<String> values = new ArrayList<>(nativeCacheSourceSuggestions(args[2]));
                if ("clear".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    values.add("clear");
                }
                return values;
            }
            return List.of();
        }
        if ("config".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                return List.of("status", "source", "speed", "generatedspeed", "autosync", "packetlog", "modelscan", "modeldir", "capture").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && List.of("autosync", "packetlog", "modelscan", "capture")
                    .contains(args[1].toLowerCase(Locale.ROOT))) {
                return List.of("on", "off").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && "source".equalsIgnoreCase(args[1])) {
                return nativeCacheSourceSuggestions(args[2]);
            }
            if (args.length == 3 && "speed".equalsIgnoreCase(args[1])) {
                return List.of("1", "2", "4").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 4 && "speed".equalsIgnoreCase(args[1])) {
                return List.of("24576", "29963", "59926").stream()
                        .filter(value -> value.startsWith(args[3].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 3 && ("generatedspeed".equalsIgnoreCase(args[1]) || "genspeed".equalsIgnoreCase(args[1]))) {
                return List.of("32", "64", "0").stream()
                        .filter(value -> value.startsWith(args[2].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 4 && ("generatedspeed".equalsIgnoreCase(args[1]) || "genspeed".equalsIgnoreCase(args[1]))) {
                return List.of("65536", "98304", "131072").stream()
                        .filter(value -> value.startsWith(args[3].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            if (args.length == 5 && ("generatedspeed".equalsIgnoreCase(args[1]) || "genspeed".equalsIgnoreCase(args[1]))) {
                return List.of("4", "8", "12", "16").stream()
                        .filter(value -> value.startsWith(args[4].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if ("debug".equals(root)) {
            if (!admin) {
                return List.of();
            }
            if (args.length == 2) {
                return List.of("status", "on", "off").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if ("models".equals(root)) {
            if (args.length == 2 && admin) {
                return List.of("reload").stream()
                        .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                        .toList();
            }
            return List.of();
        }
        if (!admin) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("status", "handshake", "debug", "models", "dist", "apply", "native").stream()
                    .filter(value -> value.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && ("status".equalsIgnoreCase(args[0]) || "handshake".equalsIgnoreCase(args[0]))) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && "models".equalsIgnoreCase(args[0])) {
            return List.of("reload").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 2 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))) {
            return List.of("status", "prepare", "prewarm", "sync", "incremental", "auth", "diagnose", "ysmcache", "nativecache", "bootstrap", "stream", "streamprobe", "probe", "replay", "report", "clear").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        if (args.length == 3 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "prepare".equalsIgnoreCase(args[1])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return modelRepository.entries().stream()
                    .map(YsmModelRepository.Entry::modelId)
                    .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .toList();
        }
        if (args.length == 3 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && ("prewarm".equalsIgnoreCase(args[1]) || "warm".equalsIgnoreCase(args[1]))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            modelRepository.entries().stream()
                    .map(YsmModelRepository.Entry::modelId)
                    .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .forEach(suggestions::add);
            return suggestions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && ("sync".equalsIgnoreCase(args[1])
                || "catalog".equalsIgnoreCase(args[1])
                || "incremental".equalsIgnoreCase(args[1])
                || "inc".equalsIgnoreCase(args[1])
                || "update".equalsIgnoreCase(args[1]))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .forEach(suggestions::add);
            return suggestions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && ("sync".equalsIgnoreCase(args[1])
                || "catalog".equalsIgnoreCase(args[1])
                || "incremental".equalsIgnoreCase(args[1])
                || "inc".equalsIgnoreCase(args[1])
                || "update".equalsIgnoreCase(args[1]))) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();
            suggestions.add("all");
            modelRepository.entries().stream()
                    .map(YsmModelRepository.Entry::modelId)
                    .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .forEach(suggestions::add);
            return suggestions.stream()
                    .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "auth".equalsIgnoreCase(args[1])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && ("diagnose".equalsIgnoreCase(args[1])
                || "nativecache".equalsIgnoreCase(args[1])
                || "ysmcache".equalsIgnoreCase(args[1])
                || "cache".equalsIgnoreCase(args[1])
                || "replay".equalsIgnoreCase(args[1])
                || "report".equalsIgnoreCase(args[1])
                || "bootstrap".equalsIgnoreCase(args[1])
                || "stream".equalsIgnoreCase(args[1])
                || "streamprobe".equalsIgnoreCase(args[1])
                || "probe".equalsIgnoreCase(args[1]))) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && ("bootstrap".equalsIgnoreCase(args[1]) || "stream".equalsIgnoreCase(args[1]))) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return YsmNativeSyncPrototype.MODES.stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 5 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "stream".equalsIgnoreCase(args[1])) {
            String prefix = args[4].toLowerCase(Locale.ROOT);
            return List.of("next", "selected", "initial").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 5 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "bootstrap".equalsIgnoreCase(args[1])) {
            String prefix = args[4].toLowerCase(Locale.ROOT);
            return YsmNativeSyncPrototype.VARIANTS.stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 5 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "replay".equalsIgnoreCase(args[1])) {
            String prefix = args[4].toLowerCase(Locale.ROOT);
            return List.of(REPLAY_MODE_FAST, REPLAY_MODE_FREESIA, REPLAY_MODE_FREESIA_PRELUDE).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "ysmcache".equalsIgnoreCase(args[1])) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return modelRepository.entries().stream()
                    .map(YsmModelRepository.Entry::modelId)
                    .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .toList();
        }
        if (args.length == 7 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "ysmcache".equalsIgnoreCase(args[1])) {
            String prefix = args[6].toLowerCase(Locale.ROOT);
            return List.of(GENERATED_CACHE_LAYOUT_OPENYSM, GENERATED_CACHE_LAYOUT_KEYS, GENERATED_CACHE_LAYOUT_LEGACY).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "report".equalsIgnoreCase(args[1])) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return List.of(REPORT_TYPE3_LAYOUT_KEYS, REPORT_TYPE3_LAYOUT_KEYS_MODELS, REPORT_TYPE3_LAYOUT_LEGACY).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 5 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "report".equalsIgnoreCase(args[1])) {
            String prefix = args[4].toLowerCase(Locale.ROOT);
            return List.of(REPORT_TYPE3_KEY_S2C, REPORT_TYPE3_KEY_C2S, REPORT_TYPE3_KEY_BOTH).stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 6 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "report".equalsIgnoreCase(args[1])) {
            String prefix = args[5].toLowerCase(Locale.ROOT);
            return List.of("0", "15", "31", "63").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 4 && ("dist".equalsIgnoreCase(args[0]) || "distribution".equalsIgnoreCase(args[0]))
                && "probe".equalsIgnoreCase(args[1])) {
            String prefix = args[3].toLowerCase(Locale.ROOT);
            return List.of("quick", "full").stream()
                    .filter(value -> value.startsWith(prefix))
                    .toList();
        }
        if (args.length == 2 && "apply".equalsIgnoreCase(args[0])) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .toList();
        }
        if (args.length == 3 && "apply".equalsIgnoreCase(args[0])) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            return modelRepository.entries().stream()
                    .map(YsmModelRepository.Entry::modelId)
                    .filter(modelId -> modelId.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .limit(20)
                    .toList();
        }
        if (args.length == 2 && "native".equalsIgnoreCase(args[0])) {
            return List.of("selftest").stream()
                    .filter(value -> value.startsWith(args[1].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    private void reloadSettings() {
        protocolVersion = getConfig().getString("protocol-version", YsmProtocol.DEFAULT_PROTOCOL_VERSION);
        channel = getConfig().getString("channel", YsmProtocol.DEFAULT_CHANNEL);
        modelsDir = getConfig().getString("models-dir", "models");
        scanModelsOnEnable = getConfig().getBoolean("scan-models-on-enable", true);
        distributionCacheDir = getConfig().getString("distribution.cache-dir", "cache/openysm/research-distribution");
        prepareDistributionOnReload = getConfig().getBoolean("distribution.prepare-on-reload", false);
        writeDistributionCacheFiles = getConfig().getBoolean("distribution.write-cache-files", false);
        distributionChunkBytes = Math.max(1024, getConfig().getInt("distribution.chunk-bytes", 24576));
        sendAuthorizedModelsOnHandshake = getConfig().getBoolean("sync.send-authorized-models-on-handshake", true);
        warnMissingNativeSyncOnHandshake = getConfig().getBoolean("sync.warn-missing-native-sync-on-handshake", true);
        enableRawReplay = getConfig().getBoolean("sync.enable-raw-replay", false);
        rawReplayDir = getConfig().getString("sync.raw-replay-dir", "debug/replay");
        rawReplayIntervalTicks = Math.max(1, getConfig().getInt("sync.raw-replay-interval-ticks", 2));
        rawReplayHandshakeTimeoutTicks = Math.max(20, getConfig().getInt(
                "sync.raw-replay-handshake-timeout-ticks",
                DEFAULT_RAW_REPLAY_HANDSHAKE_TIMEOUT_TICKS));
        autoNativeCacheOnHandshake = getConfig().getBoolean("sync.auto-native-cache-on-handshake", false);
        autoNativeCacheCaptureName = getConfig().getString("sync.auto-native-cache-capture", DEFAULT_NATIVE_CACHE_SOURCE);
        autoNativeCacheDelayTicks = Math.max(1, getConfig().getInt("sync.auto-native-cache-delay-ticks", 20));
        autoNativeCacheIntervalTicks = Math.max(1, getConfig().getInt(
                "sync.auto-native-cache-interval-ticks",
                rawReplayIntervalTicks));
        autoNativeCacheChunkBytes = Math.max(1, getConfig().getInt(
                "sync.auto-native-cache-chunk-bytes",
                FREESIA_NATIVE_CACHE_CHUNK_BYTES));
        autoGeneratedCacheOnHandshake = getConfig().getBoolean("sync.auto-generated-cache-on-handshake", false);
        autoGeneratedCacheModelId = getConfig().getString(
                "sync.auto-generated-cache-model",
                "all");
        autoGeneratedCacheDelayTicks = Math.max(1, getConfig().getInt("sync.auto-generated-cache-delay-ticks", 20));
        autoGeneratedCacheIntervalTicks = Math.max(1, getConfig().getInt(
                "sync.auto-generated-cache-interval-ticks",
                1));
        autoGeneratedCacheChunkBytes = Math.max(1, getConfig().getInt(
                "sync.auto-generated-cache-chunk-bytes",
                DEFAULT_AUTO_GENERATED_CACHE_CHUNK_BYTES));
        autoGeneratedCacheMaxModels = Math.max(0, getConfig().getInt(
                "sync.auto-generated-cache-max-models",
                DEFAULT_AUTO_GENERATED_CACHE_MAX_MODELS));
        autoGeneratedCacheType5PacketsPerTick = Math.max(1, getConfig().getInt(
                "sync.auto-generated-cache-type5-packets-per-tick",
                DEFAULT_AUTO_GENERATED_CACHE_TYPE5_PACKETS_PER_TICK));
        autoGeneratedCachePrewarmOnStartup = getConfig().getBoolean(
                "sync.auto-generated-cache-prewarm-on-startup",
                false);
        autoGeneratedCachePrewarmModelId = getConfig().getString(
                "sync.auto-generated-cache-prewarm-model",
                "all");
        autoGeneratedCachePrewarmDelayTicks = Math.max(1, getConfig().getInt(
                "sync.auto-generated-cache-prewarm-delay-ticks",
                20 * 60));
        autoGeneratedCacheSyncOnlineAfterPrewarm = getConfig().getBoolean(
                "sync.auto-generated-cache-sync-online-after-prewarm",
                false);
        autoGeneratedCacheLayout = normalizeGeneratedCacheLayout(getConfig().getString(
                "sync.auto-generated-cache-layout",
                GENERATED_CACHE_LAYOUT_OPENYSM));
        if (autoGeneratedCacheLayout == null) {
            getLogger().warning("Invalid sync.auto-generated-cache-layout; falling back to openysm.");
            autoGeneratedCacheLayout = GENERATED_CACHE_LAYOUT_OPENYSM;
        }
        autoGeneratedCachePayload = normalizeGeneratedCachePayload(getConfig().getString(
                "sync.auto-generated-cache-payload",
                GENERATED_CACHE_PAYLOAD_SERVER_CACHE));
        if (autoGeneratedCachePayload == null) {
            getLogger().warning("Invalid sync.auto-generated-cache-payload; falling back to server-cache.");
            autoGeneratedCachePayload = GENERATED_CACHE_PAYLOAD_SERVER_CACHE;
        }
        autoGeneratedCacheTokenSalt = getConfig().getString("sync.auto-generated-cache-token-salt", "");
        rememberPlayerModels = getConfig().getBoolean("state.remember-player-models", true);
        savedPlayerModelsFile = getConfig().getString("state.saved-models-file", "player-models.yml");
        experimentalBootstrapOnHandshake = getConfig().getBoolean("sync.experimental-bootstrap-on-handshake", false);
        experimentalBootstrapMode = getConfig().getString("sync.experimental-bootstrap-mode", YsmNativeSyncPrototype.MODE_ZERO);
        try {
            experimentalBootstrapMode = YsmNativeSyncPrototype.normalizeMode(experimentalBootstrapMode);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Invalid sync.experimental-bootstrap-mode: " + ex.getMessage()
                    + "; falling back to " + YsmNativeSyncPrototype.MODE_ZERO + ".");
            experimentalBootstrapMode = YsmNativeSyncPrototype.MODE_ZERO;
        }
        experimentalProbeIntervalTicks = Math.max(1, getConfig().getInt("sync.experimental-probe-interval-ticks", 8));
        captureClientRawPackets = getConfig().getBoolean("capture.client-raw-packets", false);
        rawPacketCaptureDir = getConfig().getString("capture.raw-packet-dir", "debug/raw-packets");
        handshakeDelayTicks = Math.max(1, getConfig().getInt("handshake-delay-ticks", 20));
        handshakeRetries = Math.max(0, getConfig().getInt("handshake-retries", 2));
        handshakeRetryIntervalTicks = Math.max(1, getConfig().getInt("handshake-retry-interval-ticks", 60));
        debug = getConfig().getBoolean("debug", false);
        logModelScanDetails = getConfig().getBoolean("logging.model-scan-details", false);
        logPacketDetails = getConfig().getBoolean("logging.packet-details", false);
        logProgressIntervalModels = Math.max(1, getConfig().getInt("logging.progress-interval-models", 32));
        packetHexPreviewBytes = Math.max(0, getConfig().getInt("logging.packet-hex-preview-bytes", 96));
        rawPacketHexPreviewBytes = Math.max(0, getConfig().getInt("logging.raw-packet-hex-preview-bytes", 128));
    }

    private void scheduleHandshake(Player player, int attempt) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            YsmClientSession session = sessions.get(player.getUniqueId());
            if (session != null && session.compatible()) {
                return;
            }

            sendHandshake(player);

            if (attempt < handshakeRetries) {
                scheduleHandshake(player, attempt + 1);
            }
        }, attempt == 0 ? handshakeDelayTicks : handshakeRetryIntervalTicks);
    }

    private void sendHandshake(Player player) {
        byte[] payload = YsmProtocol.encodeServerHandshake(protocolVersion);
        player.sendPluginMessage(this, channel, payload);
        sessions.compute(player.getUniqueId(), (ignored, existing) -> {
            YsmClientSession base = existing != null
                    ? existing
                    : YsmClientSession.pending(player.getUniqueId(), player.getName());
            return base.withHandshakeSent(Instant.now());
        });

        if (debug) {
            getLogger().info("YSM -> " + player.getName() + " id=" + YsmProtocol.SERVER_HANDSHAKE_ID
                    + " bytes=" + payload.length);
        }
    }

    private boolean scheduleAutoNativeCacheReplay(Player player) {
        String captureName = nativeCacheSourceFor(player);
        if (captureName.isEmpty()) {
            getLogger().warning("YSM auto native cache replay skipped: player=" + player.getName()
                    + ", reason=empty native cache source.");
            return false;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) {
                return;
            }
            YsmClientSession session = sessions.get(playerId);
            if (session == null || !session.compatible()) {
                getLogger().warning("YSM auto native cache replay skipped: player=" + current.getName()
                        + ", source=" + captureName
                        + ", reason=session-not-compatible.");
                return;
            }
            startNativeCacheReplay(
                    null,
                    current,
                    captureName,
                    autoNativeCacheIntervalTicks,
                    autoNativeCacheChunkBytes);
        }, autoNativeCacheDelayTicks);

        getLogger().info("YSM auto native cache replay scheduled: player=" + player.getName()
                + ", source=" + captureName
                + ", delayTicks=" + autoNativeCacheDelayTicks
                + ", intervalTicks=" + autoNativeCacheIntervalTicks
                + ", chunkBytes=" + autoNativeCacheChunkBytes + ".");
        return true;
    }

    private String nativeCacheSourceFor(Player player) {
        String override = playerNativeCacheSources.get(player.getUniqueId());
        String source = override == null ? autoNativeCacheCaptureName : override;
        return source == null ? "" : source.trim();
    }

    private boolean scheduleAutoGeneratedCacheReplay(Player player) {
        String modelId = autoGeneratedCacheModelId == null ? "" : autoGeneratedCacheModelId.trim();
        if (modelId.isEmpty()) {
            modelId = GENERATED_CACHE_MODEL_SAVED;
        }
        if (GENERATED_CACHE_MODEL_SAVED.equalsIgnoreCase(modelId)) {
            Optional<String> savedModelId = generatedSavedModelId(player.getUniqueId());
            if (savedModelId.isEmpty() || findModelEntry(savedModelId.get()).isEmpty()) {
                getLogger().info("YSM auto generated cache skipped: player=" + player.getName()
                        + ", model=saved"
                        + ", reason=no-restorable-saved-model.");
                return false;
            }
        }

        UUID playerId = player.getUniqueId();
        String requestedModelId = modelId;
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) {
                return;
            }
            YsmClientSession session = sessions.get(playerId);
            if (session == null || !session.compatible()) {
                getLogger().warning("YSM auto generated cache skipped: player=" + current.getName()
                        + ", model=" + requestedModelId
                        + ", reason=session-not-compatible.");
                return;
            }
            startGeneratedYsmCacheCatalogAsync(
                    null,
                    current,
                    requestedModelId,
                    autoGeneratedCacheIntervalTicks,
                    autoGeneratedCacheChunkBytes,
                    autoGeneratedCacheLayout,
                    autoGeneratedCachePayload,
                    "auto-handshake");
        }, autoGeneratedCacheDelayTicks);

        getLogger().info("YSM auto generated cache scheduled: player=" + player.getName()
                + ", model=" + requestedModelId
                + ", layout=" + autoGeneratedCacheLayout
                + ", payload=" + autoGeneratedCachePayload
                + ", delayTicks=" + autoGeneratedCacheDelayTicks
                + ", intervalTicks=" + autoGeneratedCacheIntervalTicks
                + ", chunkBytes=" + autoGeneratedCacheChunkBytes
                + ", type5PacketsPerTick=" + autoGeneratedCacheType5PacketsPerTick + ".");
        return true;
    }

    private boolean hasGeneratedCacheSource() {
        return !modelRepository.entries().isEmpty() || !distributionRepository.prepared().isEmpty();
    }

    private void scheduleAutoGeneratedServerCachePrewarm() {
        if (!autoGeneratedCachePrewarmOnStartup) {
            return;
        }
        String requestedModelId = generatedServerCachePrewarmModelId(autoGeneratedCachePrewarmModelId);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!isEnabled()) {
                return;
            }
            startGeneratedServerCachePrewarmAsync(
                    null,
                    requestedModelId,
                    autoGeneratedCacheSyncOnlineAfterPrewarm ? "all" : null,
                    false,
                    "startup-prewarm");
        }, autoGeneratedCachePrewarmDelayTicks);
        getLogger().info("YSM generated OpenYSM server-cache prewarm scheduled: model="
                + requestedModelId
                + ", delayTicks=" + autoGeneratedCachePrewarmDelayTicks
                + ", syncOnlineAfterPrewarm=" + autoGeneratedCacheSyncOnlineAfterPrewarm + ".");
    }

    private void startDefaultModelCacheSync(@Nullable CommandSender sender, Player player) {
        if (hasGeneratedCacheSource()) {
            startGeneratedYsmCacheCatalogAsync(
                    sender,
                    player,
                    autoGeneratedCacheModelId == null || autoGeneratedCacheModelId.isBlank()
                            ? "all"
                            : autoGeneratedCacheModelId,
                    autoGeneratedCacheIntervalTicks,
                    autoGeneratedCacheChunkBytes,
                    autoGeneratedCacheLayout,
                    autoGeneratedCachePayload,
                    "player-sync");
            return;
        }
        startNativeCacheReplay(
                sender,
                player,
                nativeCacheSourceFor(player),
                autoNativeCacheIntervalTicks,
                autoNativeCacheChunkBytes);
    }

    public void sendEntityDataUpdate(Player player, int entityId, byte[] entityStateBody) {
        byte[] payload = YsmProtocol.encodeEntityDataUpdate(entityId, entityStateBody);
        sendEntityDataUpdate(player, entityId, entityStateBody, payload);
    }

    private void sendEntityDataUpdate(Player player, int entityId, byte[] entityStateBody, byte[] payload) {
        player.sendPluginMessage(this, channel, payload);

        if (debug) {
            getLogger().info("YSM -> " + player.getName() + " id=" + YsmProtocol.ENTITY_DATA_UPDATE_ID
                    + " entity=" + entityId
                    + " bodyBytes=" + entityStateBody.length
                    + " payloadBytes=" + payload.length);
        }
    }

    private boolean handleModelsCommand(CommandSender sender, String[] args) {
        if (args.length >= 2 && "reload".equalsIgnoreCase(args[1])) {
            if (!requireAdmin(sender, "/ysm models reload")) {
                return true;
            }
            scheduleModelRepositoryReload(true, sender);
            return true;
        }

        Path root = modelRepository.root();
        sender.sendMessage(ChatColor.AQUA + "PaperYSM animation model reference root: "
                + (root == null ? "(not scanned)" : root.toAbsolutePath()));
        sender.sendMessage(ChatColor.AQUA + "Loaded reference models: " + modelRepository.entries().size()
                + ", failed: " + modelRepository.failures().size());
        sender.sendMessage(ChatColor.AQUA + "Prepared distribution packages: "
                + distributionRepository.prepared().size()
                + ", chunks: " + distributionRepository.totalChunkCount());

        modelRepository.entries().stream().limit(8).forEach(entry ->
                sender.sendMessage(ChatColor.GRAY + "- " + entry.modelId()
                        + " format=" + entry.format()
                        + " tail=" + entry.payloadTrailingBytes()
                        + " animations=" + entry.profile().extraAnimations().size()
                        + " buttons=" + entry.profile().extraAnimationButtons().size()
                        + " bytes=" + entry.size()));

        int remaining = modelRepository.entries().size() - Math.min(8, modelRepository.entries().size());
        if (remaining > 0) {
            sender.sendMessage(ChatColor.GRAY + "... +" + remaining + " more");
        }

        modelRepository.failures().stream().limit(3).forEach(failure ->
                sender.sendMessage(ChatColor.RED + "- failed " + failure.file().getFileName()
                        + ": " + failure.message()));
        return true;
    }

    private void handleClientModelSelection(Player player, YsmProtocol.ClientModelSelection selection) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            getLogger().warning("Ignoring YSM model selection before compatible handshake: player=" + player.getName()
                    + ", model=" + selection.modelId()
                    + ", texture=" + selection.textureId() + ".");
            return;
        }
        if (isDefaultModelId(selection.modelId()) && hasRestorableSavedModel(player)) {
            if (shouldDeferModelStateDuringNativeCache(player)) {
                if (debug) {
                    getLogger().info("YSM client default model selection restore deferred until native cache sync settles: player="
                            + player.getName() + ".");
                }
                return;
            }
            if (debug) {
                getLogger().info("YSM client default model selection ignored because a saved server model exists: player="
                        + player.getName() + ".");
            }
            scheduleSavedModelRestore(player, MODEL_STATE_REPLAY_DELAY_TICKS, "client-default-ignored");
            return;
        }
        ModelSelectionApplyResult result = applyModelSelection(
                null,
                player,
                selection.modelId(),
                selection.textureId(),
                false,
                "client-id5");
        if (result.applied()) {
            if (debug) {
                getLogger().info("YSM client model selection applied: player=" + player.getName()
                        + ", model=" + selection.modelId()
                        + ", texture=" + selection.textureId()
                        + ", compatibleViewers=" + result.compatibleViewers() + "/" + result.onlineViewers()
                        + ", distributionPrepared=" + result.distributionPrepared() + ".");
            }
        } else {
            getLogger().warning("YSM client model selection rejected: player=" + player.getName()
                    + ", model=" + selection.modelId()
                    + ", texture=" + selection.textureId()
                    + ", reason=" + result.message() + ".");
        }
    }

    private boolean shouldDeferModelStateDuringNativeCache(Player player) {
        NativeCacheReplaySession state = nativeCacheReplaySessions.get(player.getUniqueId());
        if (state == null) {
            return false;
        }

        long activeTicks;
        if (state.type5Packets() > 0) {
            int packetsPerTick = Math.max(1, state.packetsPerTick());
            activeTicks = (((long) state.type5Packets() + packetsPerTick - 1L) / packetsPerTick)
                    * Math.max(1, state.intervalTicks())
                    + MODEL_STATE_LATE_REPLAY_DELAY_TICKS
                    + 40L;
        } else {
            activeTicks = Math.max(rawReplayHandshakeTimeoutTicks, 20 * 60);
        }
        long activeMillis = Math.max(1L, activeTicks) * 50L;
        long elapsedMillis = Math.max(0L, Instant.now().toEpochMilli() - state.startedAt().toEpochMilli());
        return elapsedMillis <= activeMillis;
    }

    private void rememberClientModelSelectionWithoutBroadcast(
            Player player,
            YsmProtocol.ClientModelSelection selection) {
        AppliedModelState appliedState = new AppliedModelState(
                selection.modelId(),
                selection.textureId(),
                false,
                Instant.now());
        appliedModelStates.put(player.getUniqueId(), appliedState);
        rememberModelSelection(player, appliedState, "client-id5-deferred");
        if (debug) {
            getLogger().info("YSM client model selection remembered without immediate model-state broadcast: player="
                    + player.getName()
                    + ", model=" + selection.modelId()
                    + ", texture=" + selection.textureId()
                    + ", reason=native-cache-sync-active.");
        }
    }

    private void handleClientAnimationRequest(Player player, YsmProtocol.ClientAnimationRequest request) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            getLogger().warning("Ignoring YSM animation request before compatible handshake: player=" + player.getName()
                    + ", action=" + request.action()
                    + ", requestName=" + logValue(request.name())
                    + ", targetEntity=" + request.targetEntityId() + ".");
            return;
        }

        int entityId = request.targetEntityId() < 0 ? player.getEntityId() : request.targetEntityId();
        AnimationResolution resolution = resolveAnimationName(player, request);
        if (entityId == player.getEntityId()) {
            rememberActiveAnimation(player, resolution.name());
        }
        byte[] payload = YsmProtocol.encodePlayerAnimationSwitch(entityId, resolution.name());

        int sent = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
            if (viewerSession != null && viewerSession.compatible()) {
                sendYsmPayload(viewer, payload, "animation:" + player.getName()
                        + ":action=" + request.action());
                sent++;
            }
        }
        if (debug) {
            getLogger().info("YSM animation request relayed: player=" + player.getName()
                    + ", action=" + request.action()
                    + ", requestName=" + logValue(request.name())
                    + ", targetEntity=" + entityId
                    + ", resolvedName=" + logValue(resolution.name())
                    + ", resolution=" + resolution.source()
                    + ", selectedModel=" + logValue(resolution.selectedModelId())
                    + ", repositoryModel=" + logValue(resolution.repositoryModelId())
                    + ", texture=" + logValue(resolution.textureId())
                    + ", profileExtraAnimations=" + resolution.profile().extraAnimations().size()
                    + ", profileButtons=" + resolution.profile().extraAnimationButtons().size()
                    + ", profileClassifies=" + resolution.profile().extraAnimationClassifies().size()
                    + ", compatibleViewers=" + sent + "/" + Bukkit.getOnlinePlayers().size()
                    + ".");
        }
        if (debug && resolution.source().startsWith("fallback")) {
            getLogger().warning("YSM animation mapping fallback: player=" + player.getName()
                    + ", action=" + request.action()
                    + ", fallbackName=" + logValue(resolution.name())
                    + ", selectedModel=" + logValue(resolution.selectedModelId())
                    + ", repositoryModel=" + logValue(resolution.repositoryModelId())
                    + ", profile={" + resolution.profile().compact() + "}.");
        }
        if (debug && resolution.profile().hasAnimationMapping()) {
            getLogger().info("YSM animation profile candidates: player=" + player.getName()
                    + ", action=" + request.action()
                    + ", model=" + logValue(resolution.repositoryModelId())
                    + ", " + resolution.profile().animationDebugSummary(16) + ".");
        }
    }

    private AnimationResolution resolveAnimationName(Player player, YsmProtocol.ClientAnimationRequest request) {
        AppliedModelState state = appliedModelStates.get(player.getUniqueId());
        String selectedModelId = state == null ? "" : state.modelId();
        String textureId = state == null ? "" : state.textureId();
        Optional<YsmModelRepository.Entry> entry = state == null ? Optional.empty() : findModelEntry(state.modelId());
        String repositoryModelId = entry.map(YsmModelRepository.Entry::modelId).orElse("");
        YsmModelProfile profile = entry.map(YsmModelRepository.Entry::profile).orElse(YsmModelProfile.EMPTY);

        if (request.action() < 0) {
            return new AnimationResolution("", "stop", selectedModelId, repositoryModelId, textureId, profile);
        }

        if (!request.name().isEmpty()) {
            Optional<String> classifiedName = classifiedAnimationProtocolName(profile, request.name(), request.action());
            if (classifiedName.isPresent()) {
                return new AnimationResolution(
                        classifiedName.get(),
                        "profile-classified-animation-index",
                        selectedModelId,
                        repositoryModelId,
                        textureId,
                        profile);
            }
            Optional<String> buttonFormName = buttonFormAnimationProtocolName(profile, request.name(), request.action());
            if (buttonFormName.isPresent()) {
                return new AnimationResolution(
                        buttonFormName.get(),
                        "profile-button-form-index",
                        selectedModelId,
                        repositoryModelId,
                        textureId,
                        profile);
            }
        }

        Optional<String> buttonName = profile.extraAnimationButtonAt(request.action())
                .flatMap(PaperYsmPlugin::buttonProtocolName);
        if (buttonName.isPresent()) {
            return new AnimationResolution(
                    buttonName.get(),
                    "profile-button-index",
                    selectedModelId,
                    repositoryModelId,
                    textureId,
                    profile);
        }

        Optional<String> extraName = profile.extraAnimationAt(request.action())
                .flatMap(PaperYsmPlugin::extraAnimationProtocolName);
        if (extraName.isPresent()) {
            return new AnimationResolution(
                    extraName.get(),
                    "profile-extra-animation-index",
                    selectedModelId,
                    repositoryModelId,
                    textureId,
                    profile);
        }

        if (request.action() > 0) {
            Optional<String> oneBasedButtonName = profile.extraAnimationButtonAt(request.action() - 1)
                    .flatMap(PaperYsmPlugin::buttonProtocolName);
            if (oneBasedButtonName.isPresent()) {
                return new AnimationResolution(
                        oneBasedButtonName.get(),
                        "profile-button-index-one-based",
                        selectedModelId,
                        repositoryModelId,
                        textureId,
                        profile);
            }

            Optional<String> oneBasedExtraName = profile.extraAnimationAt(request.action() - 1)
                    .flatMap(PaperYsmPlugin::extraAnimationProtocolName);
            if (oneBasedExtraName.isPresent()) {
                return new AnimationResolution(
                        oneBasedExtraName.get(),
                        "profile-extra-animation-index-one-based",
                        selectedModelId,
                        repositoryModelId,
                        textureId,
                        profile);
            }
        }

        if (!request.name().isEmpty()) {
            return new AnimationResolution(
                    request.name(),
                    profile.hasAnimationMapping() ? "client-name-after-profile-miss" : "client-name",
                    selectedModelId,
                    repositoryModelId,
                    textureId,
                    profile);
        }

        return new AnimationResolution(
                "extra" + request.action(),
                profile.hasAnimationMapping() ? "fallback-missing-action" : "fallback-no-profile-mapping",
                selectedModelId,
                repositoryModelId,
                textureId,
                profile);
    }

    private static Optional<String> buttonProtocolName(YsmModelProfile.ExtraAnimationButton button) {
        if (!button.id().isEmpty()) {
            return Optional.of(button.id());
        }
        if (!button.name().isEmpty()) {
            return Optional.of(button.name());
        }
        return Optional.empty();
    }

    private static Optional<String> classifiedAnimationProtocolName(
            YsmModelProfile profile,
            String requestName,
            int index) {
        if (index < 0) {
            return Optional.empty();
        }
        for (YsmModelProfile.ExtraAnimationClassify classify : profile.extraAnimationClassifies()) {
            if (!animationMenuNameMatches(classify.id(), requestName)) {
                continue;
            }
            if (index < classify.animations().size()) {
                return extraAnimationProtocolName(classify.animations().get(index));
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static Optional<String> buttonFormAnimationProtocolName(
            YsmModelProfile profile,
            String requestName,
            int index) {
        if (index < 0) {
            return Optional.empty();
        }
        for (YsmModelProfile.ExtraAnimationButton button : profile.extraAnimationButtons()) {
            if (!animationMenuNameMatches(button.id(), requestName)
                    && !animationMenuNameMatches(button.name(), requestName)) {
                continue;
            }

            int cursor = 0;
            for (YsmModelProfile.ButtonForm form : button.forms()) {
                for (YsmModelProfile.ExtraAnimation label : form.labels()) {
                    if (cursor == index) {
                        return extraAnimationProtocolName(label);
                    }
                    cursor++;
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean animationMenuNameMatches(String left, String right) {
        return left != null && right != null && !left.isEmpty() && left.equals(right);
    }

    private static Optional<String> extraAnimationProtocolName(YsmModelProfile.ExtraAnimation animation) {
        if (!animation.name().isEmpty()) {
            return Optional.of(animation.name());
        }
        if (!animation.value().isEmpty()) {
            return Optional.of(animation.value());
        }
        return Optional.empty();
    }

    private void handleClientMolangFeedback(Player player, YsmProtocol.ClientMolangFeedback feedback) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            getLogger().warning("Ignoring YSM molang feedback before compatible handshake: player="
                    + player.getName()
                    + ", modelHashId=" + feedback.modelHashId()
                    + ", variables=" + feedback.variables().size() + ".");
            return;
        }
        if (feedback.entityId() != player.getEntityId()) {
            if (debug) {
                getLogger().info("Ignoring YSM molang feedback for non-player entity: player="
                        + player.getName()
                        + ", entity=" + feedback.entityId()
                        + ", ownEntity=" + player.getEntityId() + ".");
            }
            return;
        }
        if (feedback.variables().isEmpty()) {
            return;
        }

        rememberMolangVariables(player, feedback.modelHashId(), feedback.variables());
        byte[] payload = YsmProtocol.encodeMolangVariableSync(
                player.getEntityId(),
                feedback.modelHashId(),
                feedback.variables());
        int sent = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
            if (viewerSession != null && viewerSession.compatible()) {
                sendYsmPayload(viewer, payload, "molang-feedback:" + player.getName());
                sent++;
            }
        }
        if (debug) {
            getLogger().info("YSM molang feedback synced: player=" + player.getName()
                    + ", modelHashId=" + feedback.modelHashId()
                    + ", variables=" + feedback.variables().size()
                    + ", viewers=" + sent + ".");
        }
    }

    private void handleClientAnimationExpressionSync(Player player, float[] values) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            return;
        }
        byte[] payload = YsmProtocol.encodeAnimationExpressionSync(player.getEntityId(), values);
        int sent = 0;
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
            if (viewerSession != null && viewerSession.compatible()) {
                sendYsmPayload(viewer, payload, "animation-expression:" + player.getName());
                sent++;
            }
        }
        if (debug) {
            getLogger().info("YSM animation expression synced: player=" + player.getName()
                    + ", values=" + values.length
                    + ", viewers=" + sent + ".");
        }
    }

    private boolean handleApplyCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /ysm apply <player> <modelId> [textureId] [disabled]");
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(ChatColor.RED + "Player not found: " + args[1]);
            return true;
        }

        String modelId = args[2];
        String textureId = args.length >= 4 ? args[3] : "default";
        boolean disabled = args.length >= 5 && Boolean.parseBoolean(args[4]);
        applyModelSelection(sender, target, modelId, textureId, disabled, "command");
        return true;
    }

    private ModelSelectionApplyResult applyModelSelection(
            @Nullable CommandSender sender,
            Player target,
            String modelId,
            String textureId,
            boolean disabled,
            String trigger) {
        Optional<YsmModelRepository.Entry> modelEntry = findModelEntry(modelId);
        boolean defaultModel = isDefaultModelId(modelId);
        boolean strictRepositoryModel = sender != null;
        if (modelEntry.isEmpty() && !defaultModel && strictRepositoryModel) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Loaded model not found: " + modelId);
                sender.sendMessage(ChatColor.GRAY + "Use /ysm models reload after adding .ysm files.");
            }
            return new ModelSelectionApplyResult(false, 0, Bukkit.getOnlinePlayers().size(), false, false, "loaded model not found");
        }
        if (modelEntry.isEmpty() && !defaultModel) {
            if (debug) {
                getLogger().info("YSM client/cache-local model selection accepted without server repository entry: player="
                        + target.getName()
                        + ", model=" + modelId
                        + ", texture=" + textureId
                        + ".");
            }
        }
        String repositoryModelId = modelEntry.map(YsmModelRepository.Entry::modelId).orElse(modelId);
        if (modelEntry.isPresent() && distributionRepository.find(repositoryModelId).isEmpty()) {
            prepareDistributionModel(sender, modelEntry.get());
        }
        YsmDistributionRepository.PreparedModel preparedModel = modelEntry.isPresent()
                ? distributionRepository.find(repositoryModelId).orElse(null)
                : null;
        AppliedModelState appliedState = new AppliedModelState(modelId, textureId, disabled, Instant.now());
        appliedModelStates.put(target.getUniqueId(), appliedState);
        if (!trigger.startsWith("saved-state:")) {
            activeAnimationStates.remove(target.getUniqueId());
        }
        rememberModelSelection(target, appliedState, trigger);

        int molangModelHashId = modelEntry
                .map(entry -> modelHashId(entry.modelHash()))
                .orElse(0);
        Map<String, Float> molangState = molangModelHashId == 0
                ? Map.of()
                : currentMolangVariables(target.getUniqueId(), molangModelHashId);
        String activeAnimation = activeAnimationStates.getOrDefault(target.getUniqueId(), "");
        byte[] body = YsmEntityStateCodec.encodeModelSelectionBody(
                target.getEntityId(),
                modelId,
                textureId,
                disabled,
                activeAnimation,
                molangModelHashId,
                molangState);
        byte[] payload = YsmProtocol.encodeEntityDataUpdate(target.getEntityId(), body);

        String requestedBy = sender == null ? trigger : sender.getName();
        if (debug) {
            getLogger().info("YSM model-state apply requested by " + requestedBy
                    + ": target=" + target.getName()
                    + ", entity=" + target.getEntityId()
                    + ", model=" + modelId
                    + (repositoryModelId.equals(modelId) ? "" : ", repositoryModel=" + repositoryModelId)
                    + ", texture=" + textureId
                    + ", disabled=" + disabled
                    + ", bodyBytes=" + body.length
                    + ", payloadBytes=" + payload.length
                    + ", distributionPrepared=" + (preparedModel != null)
                    + (preparedModel == null ? "" : ", distributionChunks=" + preparedModel.chunkCount())
                    + ".");
        }

        int sent = 0;
        int online = Bukkit.getOnlinePlayers().size();
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            YsmClientSession session = sessions.get(viewer.getUniqueId());
            if (session != null && session.compatible()) {
                sendEntityDataUpdate(viewer, target.getEntityId(), body, payload);
                sent++;
                if (logPacketDetails) {
                    getLogger().info("YSM model-state packet sent: viewer=" + viewer.getName()
                            + ", target=" + target.getName()
                            + ", entity=" + target.getEntityId()
                            + ", subpacket=" + YsmProtocol.ENTITY_DATA_UPDATE_ID
                            + ", payloadBytes=" + payload.length
                            + ", bodyBytes=" + body.length + ".");
                }
            }
        }

        if (logPacketDetails) {
            getLogger().info("YSM model-state payload preview: " + YsmProtocol.toHex(payload, packetHexPreviewBytes));
        }
        if (debug) {
            getLogger().info("YSM model-state apply finished: target=" + target.getName()
                    + ", compatibleViewers=" + sent + "/" + online
                    + ", model=" + modelId
                    + ", texture=" + textureId + ".");
        }
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Sent YSM model state for " + target.getName()
                    + " to " + sent + " compatible client(s): " + modelId + "/" + textureId + ".");
            if (preparedModel == null && !defaultModel) {
                sender.sendMessage(ChatColor.YELLOW + "Model state was sent, but no prepared distribution package exists yet.");
            }
        }
        return new ModelSelectionApplyResult(
                true,
                sent,
                online,
                modelEntry.isPresent() || defaultModel,
                preparedModel != null,
                "ok");
    }

    private void scheduleModelStateReplay(Player viewer, long delayTicks, String reason) {
        UUID viewerId = viewer.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(viewerId);
            if (current != null && current.isOnline()) {
                replayKnownModelStatesToViewer(current, reason);
            }
        }, Math.max(1L, delayTicks));
    }

    private void scheduleAppliedModelStateBroadcast(Player target, long delayTicks, String reason) {
        UUID targetId = target.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(targetId);
            if (current == null || !current.isOnline()) {
                return;
            }
            AppliedModelState state = appliedModelStates.get(targetId);
            if (state == null) {
                return;
            }
            int sent = 0;
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
                if (viewerSession != null && viewerSession.compatible()) {
                    sendStoredModelState(viewer, current, state, "model-state-broadcast:" + reason);
                    sent++;
                }
            }
            if (sent > 0) {
                if (debug) {
                    getLogger().info("YSM deferred model-state broadcast sent: target=" + current.getName()
                            + ", viewers=" + sent
                            + ", model=" + state.modelId()
                            + ", texture=" + state.textureId()
                            + ", reason=" + reason + ".");
                }
            }
        }, Math.max(1L, delayTicks));
    }

    private void replayKnownModelStatesToViewer(Player viewer, String reason) {
        YsmClientSession viewerSession = sessions.get(viewer.getUniqueId());
        if (viewerSession == null || !viewerSession.compatible()) {
            return;
        }

        int sent = 0;
        for (Player target : Bukkit.getOnlinePlayers()) {
            AppliedModelState state = appliedModelStates.get(target.getUniqueId());
            if (state == null) {
                continue;
            }
            sendStoredModelState(viewer, target, state, "model-state-replay:" + reason);
            sent++;
        }
        if (sent > 0) {
            if (debug) {
                getLogger().info("YSM model-state replay sent: viewer=" + viewer.getName()
                        + ", states=" + sent
                        + ", reason=" + reason + ".");
            }
        }
    }

    private void sendStoredModelState(Player viewer, Player target, AppliedModelState state, String reason) {
        Optional<YsmModelRepository.Entry> modelEntry = findModelEntry(state.modelId());
        int molangModelHashId = modelEntry.map(entry -> modelHashId(entry.modelHash())).orElse(0);
        Map<String, Float> molangState = molangModelHashId == 0
                ? Map.of()
                : currentMolangVariables(target.getUniqueId(), molangModelHashId);
        String activeAnimation = activeAnimationStates.getOrDefault(target.getUniqueId(), "");
        byte[] body = YsmEntityStateCodec.encodeModelSelectionBody(
                target.getEntityId(),
                state.modelId(),
                state.textureId(),
                state.disabled(),
                activeAnimation,
                molangModelHashId,
                molangState);
        byte[] payload = YsmProtocol.encodeEntityDataUpdate(target.getEntityId(), body);
        sendEntityDataUpdate(viewer, target.getEntityId(), body, payload);
        if (logPacketDetails) {
            getLogger().info("YSM stored model-state packet sent: viewer=" + viewer.getName()
                    + ", target=" + target.getName()
                    + ", entity=" + target.getEntityId()
                    + ", model=" + state.modelId()
                    + ", texture=" + state.textureId()
                    + ", reason=" + reason
                    + ", payloadBytes=" + payload.length + ".");
        }
    }

    private void scheduleSavedModelRestore(Player player, long delayTicks, String reason) {
        if (!rememberPlayerModels || !hasRestorableSavedModel(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current != null && current.isOnline()) {
                restoreSavedModelState(current, reason);
            }
        }, Math.max(1L, delayTicks));
    }

    private void restoreSavedModelState(Player player, String reason) {
        if (!rememberPlayerModels) {
            return;
        }
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            return;
        }

        SavedModelState saved = savedModelStates.get(player.getUniqueId());
        if (saved == null || saved.modelId().isBlank()) {
            return;
        }
        if (isDefaultModelId(saved.modelId())) {
            if (debug) {
                getLogger().info("YSM saved model restore skipped default entry: player=" + player.getName()
                        + ", reason=" + reason + ".");
            }
            return;
        }

        restoreRuntimeState(player.getUniqueId(), saved);
        ModelSelectionApplyResult result = applyModelSelection(
                null,
                player,
                saved.modelId(),
                saved.textureId(),
                saved.disabled(),
                "saved-state:" + reason);
        if (result.applied()) {
            if (debug) {
                getLogger().info("YSM saved model restored: player=" + player.getName()
                        + ", model=" + saved.modelId()
                        + ", texture=" + saved.textureId()
                        + ", disabled=" + saved.disabled()
                        + ", reason=" + reason
                        + ", compatibleViewers=" + result.compatibleViewers() + "/" + result.onlineViewers()
                        + ".");
            }
        } else {
            getLogger().warning("YSM saved model restore failed: player=" + player.getName()
                    + ", model=" + saved.modelId()
                    + ", texture=" + saved.textureId()
                    + ", reason=" + result.message() + ".");
        }
    }

    private boolean hasRestorableSavedModel(Player player) {
        SavedModelState saved = savedModelStates.get(player.getUniqueId());
        return saved != null
                && !saved.modelId().isBlank()
                && !isDefaultModelId(saved.modelId());
    }

    private void rememberModelSelection(Player target, AppliedModelState state, String trigger) {
        if (!rememberPlayerModels || trigger.startsWith("saved-state:")) {
            return;
        }
        if (isDefaultModelId(state.modelId())) {
            if ("client-id5".equals(trigger)) {
                if (logPacketDetails) {
                    getLogger().info("YSM client default model selection not persisted: player="
                            + target.getName() + ".");
                }
                return;
            }
            SavedModelState removed = savedModelStates.remove(target.getUniqueId());
            if (removed != null) {
                saveSavedModelStates();
                if (debug) {
                    getLogger().info("YSM saved model cleared: player=" + target.getName()
                            + ", trigger=" + trigger + ".");
                }
            }
            return;
        }

        SavedModelState saved = new SavedModelState(
                target.getName(),
                state.modelId(),
                state.textureId(),
                state.disabled(),
                activeAnimationStates.getOrDefault(target.getUniqueId(), ""),
                snapshotMolangStorage(target.getUniqueId()),
                state.updatedAt());
        SavedModelState previous = savedModelStates.put(target.getUniqueId(), saved);
        if (!sameSavedModelSelection(previous, saved)) {
            saveSavedModelStates();
            if (debug) {
                getLogger().info("YSM saved model remembered: player=" + target.getName()
                        + ", model=" + saved.modelId()
                        + ", texture=" + saved.textureId()
                        + ", disabled=" + saved.disabled()
                        + ".");
            }
        }
    }

    private void loadSavedModelStates() {
        savedModelStates.clear();
        if (!rememberPlayerModels) {
            getLogger().info("YSM saved model restore disabled by state.remember-player-models=false.");
            return;
        }

        Path file = resolvePluginPath(savedPlayerModelsFile);
        if (!Files.exists(file)) {
            getLogger().info("YSM saved model state file not found yet: file=" + file.toAbsolutePath() + ".");
            return;
        }

        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            getLogger().info("YSM saved model state loaded: players=0, file=" + file.toAbsolutePath() + ".");
            return;
        }

        int failed = 0;
        int defaultSkipped = 0;
        for (String key : players.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                ConfigurationSection section = players.getConfigurationSection(key);
                if (section == null) {
                    continue;
                }
                String modelId = section.getString("model", "");
                if (modelId == null || modelId.isBlank()) {
                    continue;
                }
                if (isDefaultModelId(modelId)) {
                    defaultSkipped++;
                    continue;
                }
                String textureId = section.getString("texture", "default");
                String updatedAt = section.getString("updatedAt", "");
                Map<Integer, Map<String, Float>> molangStorage = loadSavedMolangStorage(section);
                savedModelStates.put(playerId, new SavedModelState(
                        section.getString("name", ""),
                        modelId,
                        textureId == null || textureId.isBlank() ? "default" : textureId,
                        section.getBoolean("disabled", false),
                        section.getString("animation", ""),
                        molangStorage,
                        parseInstant(updatedAt)));
            } catch (RuntimeException ex) {
                failed++;
                getLogger().warning("YSM saved model state entry ignored: playerId=" + key
                        + ", reason=" + ex.getMessage() + ".");
            }
        }

        getLogger().info("YSM saved model state loaded: players=" + savedModelStates.size()
                + ", failed=" + failed
                + ", defaultSkipped=" + defaultSkipped
                + ", file=" + file.toAbsolutePath() + ".");
    }

    private void saveSavedModelStates() {
        if (!rememberPlayerModels) {
            return;
        }

        Path file = resolvePluginPath(savedPlayerModelsFile);
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            YamlConfiguration yaml = new YamlConfiguration();
            for (Map.Entry<UUID, SavedModelState> entry : savedModelStates.entrySet()) {
                String path = "players." + entry.getKey();
                SavedModelState state = entry.getValue();
                if (isDefaultModelId(state.modelId())) {
                    continue;
                }
                yaml.set(path + ".name", state.playerName());
                yaml.set(path + ".model", state.modelId());
                yaml.set(path + ".texture", state.textureId());
                yaml.set(path + ".disabled", state.disabled());
                if (!state.animationName().isBlank()) {
                    yaml.set(path + ".animation", state.animationName());
                }
                for (Map.Entry<Integer, Map<String, Float>> molangEntry : state.molangStorage().entrySet()) {
                    String molangPath = path + ".molang." + molangEntry.getKey();
                    for (Map.Entry<String, Float> variable : molangEntry.getValue().entrySet()) {
                        yaml.set(molangPath + "." + encodeSavedMolangKey(variable.getKey()), variable.getValue());
                    }
                }
                yaml.set(path + ".updatedAt", state.updatedAt().toString());
            }
            yaml.save(file.toFile());
        } catch (IOException ex) {
            getLogger().warning("Failed to save YSM player model states to " + file.toAbsolutePath()
                    + ": " + ex.getMessage());
        }
    }

    private static boolean sameSavedModelSelection(@Nullable SavedModelState left, SavedModelState right) {
        return left != null
                && left.playerName().equals(right.playerName())
                && left.modelId().equals(right.modelId())
                && left.textureId().equals(right.textureId())
                && left.disabled() == right.disabled()
                && left.animationName().equals(right.animationName())
                && left.molangStorage().equals(right.molangStorage());
    }

    private void persistRuntimeStateForQuit(Player player) {
        AppliedModelState state = appliedModelStates.get(player.getUniqueId());
        if (state != null) {
            rememberModelSelection(player, state, "quit");
        }
    }

    private void rememberActiveAnimation(Player player, String animationName) {
        String safeAnimationName = animationName == null ? "" : animationName;
        if (safeAnimationName.isBlank()) {
            activeAnimationStates.remove(player.getUniqueId());
        } else {
            activeAnimationStates.put(player.getUniqueId(), safeAnimationName);
        }
        AppliedModelState state = appliedModelStates.get(player.getUniqueId());
        if (state != null) {
            rememberModelSelection(player, state, "animation");
        }
    }

    private void rememberMolangVariables(Player player, int modelHashId, Map<String, Float> variables) {
        if (modelHashId == 0 || variables.isEmpty()) {
            return;
        }
        playerMolangStates.compute(player.getUniqueId(), (ignored, existing) -> {
            Map<Integer, Map<String, Float>> byModel = existing == null
                    ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(existing);
            Map<String, Float> current = new LinkedHashMap<>(byModel.getOrDefault(modelHashId, Map.of()));
            current.putAll(variables);
            byModel.put(modelHashId, Map.copyOf(current));
            return Map.copyOf(byModel);
        });
        AppliedModelState state = appliedModelStates.get(player.getUniqueId());
        if (state != null) {
            rememberModelSelection(player, state, "molang-feedback");
        }
    }

    private void restoreRuntimeState(UUID playerId, SavedModelState saved) {
        if (saved.animationName().isBlank()) {
            activeAnimationStates.remove(playerId);
        } else {
            activeAnimationStates.put(playerId, saved.animationName());
        }
        if (saved.molangStorage().isEmpty()) {
            playerMolangStates.remove(playerId);
        } else {
            playerMolangStates.put(playerId, saved.molangStorage());
        }
    }

    private Map<String, Float> currentMolangVariables(UUID playerId, int modelHashId) {
        Map<Integer, Map<String, Float>> byModel = playerMolangStates.get(playerId);
        if (byModel == null) {
            return Map.of();
        }
        return byModel.getOrDefault(modelHashId, Map.of());
    }

    private Map<Integer, Map<String, Float>> snapshotMolangStorage(UUID playerId) {
        return deepCopyMolangStorage(playerMolangStates.getOrDefault(playerId, Map.of()));
    }

    private static Map<Integer, Map<String, Float>> deepCopyMolangStorage(
            @Nullable Map<Integer, Map<String, Float>> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        LinkedHashMap<Integer, Map<String, Float>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, Map<String, Float>> entry : source.entrySet()) {
            if (entry.getValue() == null || entry.getValue().isEmpty()) {
                continue;
            }
            copy.put(entry.getKey(), Map.copyOf(new LinkedHashMap<>(entry.getValue())));
        }
        return Map.copyOf(copy);
    }

    private static Map<Integer, Map<String, Float>> loadSavedMolangStorage(ConfigurationSection section) {
        ConfigurationSection molang = section.getConfigurationSection("molang");
        if (molang == null) {
            return Map.of();
        }
        LinkedHashMap<Integer, Map<String, Float>> result = new LinkedHashMap<>();
        for (String hashKey : molang.getKeys(false)) {
            int modelHashId;
            try {
                modelHashId = Integer.parseInt(hashKey);
            } catch (NumberFormatException ex) {
                continue;
            }
            ConfigurationSection variables = molang.getConfigurationSection(hashKey);
            if (variables == null) {
                continue;
            }
            LinkedHashMap<String, Float> values = new LinkedHashMap<>();
            for (String encodedKey : variables.getKeys(false)) {
                String key = decodeSavedMolangKey(encodedKey);
                if (key.isBlank()) {
                    continue;
                }
                values.put(key, (float) variables.getDouble(encodedKey, 0.0d));
            }
            if (!values.isEmpty()) {
                result.put(modelHashId, Map.copyOf(values));
            }
        }
        return Map.copyOf(result);
    }

    private static String encodeSavedMolangKey(String key) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString((key == null ? "" : key).getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeSavedMolangKey(String key) {
        try {
            return new String(Base64.getUrlDecoder().decode(key), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return key == null ? "" : key;
        }
    }

    private static int modelHashId(String modelHash) {
        if (modelHash == null || modelHash.length() < 8) {
            return 0;
        }
        try {
            return (int) Long.parseUnsignedLong(modelHash.substring(0, 8), 16);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean isDefaultModelId(String modelId) {
        return "default".equalsIgnoreCase(modelId);
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ex) {
            return Instant.EPOCH;
        }
    }

    private Optional<YsmModelRepository.Entry> findModelEntry(String modelId) {
        Optional<YsmModelRepository.Entry> exact = modelRepository.find(modelId);
        if (exact.isPresent()) {
            return exact;
        }
        String normalized = normalizeClientModelId(modelId);
        if (normalized.equals(modelId)) {
            return Optional.empty();
        }
        return modelRepository.find(normalized);
    }

    private static String normalizeClientModelId(String modelId) {
        String normalized = modelId.replace('\\', '/');
        if (normalized.toLowerCase(Locale.ROOT).endsWith(".ysm")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private boolean handleDistributionCommand(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        if ("status".equals(action)) {
            sendDistributionStatus(sender);
            return true;
        }

        if ("clear".equals(action)) {
            distributionRepository.clear();
            nativeSyncGapWarnings.clear();
            nativeSyncStates.clear();
            reportNativeSessions.clear();
            nativeCacheReplaySessions.clear();
            nativeRecentDecodeKeys.clear();
            sender.sendMessage(ChatColor.GREEN + "Cleared in-memory YSM distribution packages.");
            getLogger().info("YSM distribution cache cleared by " + sender.getName() + ".");
            return true;
        }

        if ("auth".equals(action)) {
            if (args.length >= 3) {
                Player player = Bukkit.getPlayerExact(args[2]);
                if (player == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                    return true;
                }
                sendAuthorizedModelSet(player);
                sender.sendMessage(ChatColor.GREEN + "Sent YSM authorized model list to " + player.getName() + ".");
                return true;
            }

            int sent = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                YsmClientSession session = sessions.get(player.getUniqueId());
                if (session != null && session.compatible()) {
                    sendAuthorizedModelSet(player);
                    sent++;
                }
            }
            sender.sendMessage(ChatColor.GREEN + "Sent YSM authorized model list to "
                    + sent + " compatible client(s).");
            return true;
        }

        if ("diagnose".equals(action) || "diag".equals(action)) {
            sendDistributionDiagnostics(sender, args.length >= 3 ? args[2] : null);
            return true;
        }

        if ("report".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist report <player> [keys|keys-models|legacy] [s2c|c2s|both] [paddingBytes]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            String layout = normalizeReportType3Layout(args.length >= 4 ? args[3] : REPORT_TYPE3_LAYOUT_KEYS);
            if (layout == null) {
                sender.sendMessage(ChatColor.RED + "Unsupported report type3 layout: " + args[3]
                        + " (expected keys|keys-models|legacy)");
                return true;
            }
            String keyMode = normalizeReportType3KeyMode(args.length >= 5 ? args[4] : REPORT_TYPE3_KEY_S2C);
            if (keyMode == null) {
                sender.sendMessage(ChatColor.RED + "Unsupported report type3 key mode: " + args[4]
                        + " (expected s2c|c2s|both)");
                return true;
            }
            Integer paddingBytes = args.length >= 6 ? parsePaddingBytes(sender, args[5]) : 0;
            if (paddingBytes == null) {
                return true;
            }
            startReportNativeSync(sender, player, layout, keyMode, paddingBytes);
            return true;
        }

        if ("bootstrap".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist bootstrap <player> [mode] [variant] [paddingBytes]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            Integer paddingBytes = args.length >= 6 ? parsePaddingBytes(sender, args[5]) : 0;
            if (paddingBytes == null) {
                return true;
            }
            startNativeBootstrap(
                    sender,
                    player,
                    args.length >= 4 ? args[3] : experimentalBootstrapMode,
                    args.length >= 5 ? args[4] : YsmNativeSyncPrototype.VARIANT_FULL,
                    paddingBytes,
                    "command");
            return true;
        }

        if ("probe".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist probe <player> [quick|full] [intervalTicks] [paddingBytes]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            String profile = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "quick";
            int intervalTicks = args.length >= 5 ? parsePositiveInt(args[4], experimentalProbeIntervalTicks) : experimentalProbeIntervalTicks;
            Integer forcedPaddingBytes = args.length >= 6 ? parsePaddingBytes(sender, args[5]) : null;
            if (args.length >= 6 && forcedPaddingBytes == null) {
                return true;
            }
            startNativeProbe(sender, player, profile, intervalTicks, forcedPaddingBytes);
            return true;
        }

        if ("stream".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist stream <player> [mode] [next|selected|initial]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            startNativeManifestStream(
                    sender,
                    player,
                    args.length >= 4 ? args[3] : experimentalBootstrapMode,
                    args.length >= 5 ? args[4] : "next");
            return true;
        }

        if ("streamprobe".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist streamprobe <player> [intervalTicks]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            int intervalTicks = args.length >= 4 ? parsePositiveInt(args[3], experimentalProbeIntervalTicks) : experimentalProbeIntervalTicks;
            startNativeManifestStreamProbe(sender, player, intervalTicks);
            return true;
        }

        if ("replay".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist replay <player> <captureNameOrFile> [fast|freesia|freesia-prelude]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            String replayMode = args.length >= 5 ? args[4] : REPLAY_MODE_FAST;
            startRawReplay(sender, player, args[3], replayMode);
            return true;
        }

        if ("nativecache".equals(action) || "cache".equals(action) || "freesia-cache".equals(action)) {
            if (args.length < 4) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist nativecache <player> <captureName> [intervalTicks] [chunkBytes]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            int intervalTicks = args.length >= 5 ? parsePositiveInt(args[4], rawReplayIntervalTicks) : rawReplayIntervalTicks;
            int chunkBytes = args.length >= 6 ? parsePositiveInt(args[5], FREESIA_NATIVE_CACHE_CHUNK_BYTES) : FREESIA_NATIVE_CACHE_CHUNK_BYTES;
            startNativeCacheReplay(sender, player, args[3], intervalTicks, chunkBytes);
            return true;
        }

        if ("ysmcache".equals(action) || "localcache".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist ysmcache <player> [modelId|all] [intervalTicks] [chunkBytes] [openysm|keys|legacy] [server-cache|washed-zstd|headerless-v3|encrypted-v3]");
                return true;
            }
            Player player = Bukkit.getPlayerExact(args[2]);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + args[2]);
                return true;
            }
            String modelId = args.length >= 4 ? args[3] : GENERATED_CACHE_MODEL_SAVED;
            int intervalTicks = args.length >= 5 ? parsePositiveInt(args[4], rawReplayIntervalTicks) : rawReplayIntervalTicks;
            int chunkBytes = args.length >= 6 ? parsePositiveInt(args[5], distributionChunkBytes) : distributionChunkBytes;
            String requestedLayout = args.length >= 7 ? args[6] : autoGeneratedCacheLayout;
            String layout = normalizeGeneratedCacheLayout(requestedLayout);
            if (layout == null) {
                sender.sendMessage(ChatColor.RED + "Unsupported generated cache layout: " + requestedLayout
                        + " (expected openysm|keys|legacy)");
                return true;
            }
            String requestedPayload = args.length >= 8 ? args[7] : autoGeneratedCachePayload;
            String payload = normalizeGeneratedCachePayload(requestedPayload);
            if (payload == null) {
                sender.sendMessage(ChatColor.RED + "Unsupported generated cache payload: " + requestedPayload
                        + " (expected server-cache|washed-zstd|headerless-v3|encrypted-v3)");
                return true;
            }
            startGeneratedYsmCacheAsync(sender, player, modelId, intervalTicks, chunkBytes, layout, payload);
            return true;
        }

        if ("prewarm".equals(action) || "warm".equals(action)) {
            String modelId = args.length >= 3 ? args[2] : "all";
            startGeneratedServerCachePrewarmAsync(sender, modelId, null, false, "command-prewarm");
            return true;
        }

        if ("sync".equals(action) || "catalog".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist sync <player|all> [modelId|all]");
                return true;
            }
            String modelId = args.length >= 4 ? args[3] : "all";
            syncGeneratedServerCacheCatalog(sender, args[2], modelId, "command-sync");
            return true;
        }

        if ("incremental".equals(action) || "inc".equals(action) || "update".equals(action)) {
            if (args.length < 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /ysm dist incremental <player|all> [modelId|all]");
                return true;
            }
            String modelId = args.length >= 4 ? args[3] : "all";
            startGeneratedServerCachePrewarmAsync(sender, modelId, args[2], true, "command-incremental");
            return true;
        }

        if ("prepare".equals(action)) {
            if (args.length >= 3) {
                Optional<YsmModelRepository.Entry> entry = modelRepository.find(args[2]);
                if (entry.isEmpty()) {
                    sender.sendMessage(ChatColor.RED + "Loaded model not found: " + args[2]);
                    return true;
                }
                prepareDistributionModel(sender, entry.get());
                sendDistributionStatus(sender);
                return true;
            }

            prepareDistributionRepository(true);
            sendDistributionStatus(sender);
            return true;
        }

        sender.sendMessage(ChatColor.RED + "Usage: /ysm dist <status|prepare|prewarm|sync|incremental|auth|diagnose|ysmcache|nativecache|bootstrap|stream|streamprobe|probe|replay|report|clear> [args]");
        return true;
    }

    private void sendAuthorizedModelSet(Player player) {
        List<String> modelIds = modelRepository.entries().stream()
                .map(YsmModelRepository.Entry::modelId)
                .toList();
        byte[] payload = YsmProtocol.encodeAuthorizedModelSet(modelIds);
        sendYsmPayload(player, payload, "authorized-model-list");
        sessions.compute(player.getUniqueId(), (ignored, existing) -> {
            YsmClientSession base = existing != null
                    ? existing
                    : YsmClientSession.pending(player.getUniqueId(), player.getName());
            return base.withAuthorizedModelsSent(modelIds.size(), payload.length, Instant.now());
        });
        if (debug) {
            getLogger().info("YSM authorized model list sent: player=" + player.getName()
                    + ", models=" + modelIds.size()
                    + ", bytes=" + payload.length
                    + ", preview=" + YsmProtocol.toHex(payload, packetHexPreviewBytes) + ".");
        }
    }

    private void sendEmptyModelSet(Player player, int packetId, String reason) {
        byte[] payload = new byte[] {(byte) packetId, 0};
        sendYsmPayload(player, payload, reason);
        if (debug) {
            getLogger().info("YSM empty model-set packet sent: player=" + player.getName()
                    + ", reason=" + reason
                    + ", subpacket=" + packetId
                    + ", payloadBytes=" + payload.length
                    + ", preview=" + YsmProtocol.toHex(payload, packetHexPreviewBytes) + ".");
        }
    }

    private void sendDistributionStatus(CommandSender sender) {
        Path cacheRoot = distributionRepository.cacheRoot();
        sender.sendMessage(ChatColor.AQUA + "PaperYSM distribution cache root: "
                + (cacheRoot == null ? resolvePluginPath(distributionCacheDir).toAbsolutePath() : cacheRoot.toAbsolutePath()));
        sender.sendMessage(ChatColor.AQUA + "Prepared packages: " + distributionRepository.prepared().size()
                + ", failed: " + distributionRepository.failures().size()
                + ", chunks: " + distributionRepository.totalChunkCount()
                + ", transfer: " + formatBytes(distributionRepository.totalTransferBytes())
                + ", decompressed: " + formatBytes(distributionRepository.totalDecompressedBytes()));
        GeneratedServerCacheStats generatedStats = generatedServerCacheStats();
        sender.sendMessage(ChatColor.AQUA + "OpenYSM generated server cache: indexedModels="
                + generatedStats.indexedEntries()
                + ", files=" + generatedStats.files()
                + ", bytes=" + formatBytes(generatedStats.bytes())
                + ", root=" + generatedStats.root().toAbsolutePath());
        if (!distributionRepository.prepared().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW
                    + "Client-visible model download still requires S2C raw/native id=1 packets; id=6 auth alone will not add roulette entries.");
        }

        distributionRepository.prepared().stream().limit(8).forEach(model ->
                sender.sendMessage(ChatColor.GRAY + "- " + model.modelId()
                        + " format=" + model.format()
                        + " chunks=" + model.chunkCount()
                        + " transfer=" + formatBytes(model.transferBytes())
                        + " sha=" + model.transferSha256().substring(0, 12)));

        int remaining = distributionRepository.prepared().size()
                - Math.min(8, distributionRepository.prepared().size());
        if (remaining > 0) {
            sender.sendMessage(ChatColor.GRAY + "... +" + remaining + " more");
        }

        distributionRepository.failures().stream().limit(3).forEach(failure ->
                sender.sendMessage(ChatColor.RED + "- failed " + failure.modelId()
                        + ": " + failure.message()));
    }

    private void sendDistributionDiagnostics(CommandSender sender, @Nullable String playerName) {
        sendDistributionStatus(sender);
        if (playerName != null) {
            Player player = Bukkit.getPlayerExact(playerName);
            if (player == null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + playerName);
                return;
            }
            sendStatus(sender, player);
            sendNativeSyncStatus(sender, player);
            sendReportNativeStatus(sender, player);
            sendNativeCacheReplayStatus(sender, player);
            sendSyncGapHint(sender, player);
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            sendStatus(sender, player);
            sendNativeSyncStatus(sender, player);
            sendReportNativeStatus(sender, player);
            sendNativeCacheReplayStatus(sender, player);
            sendSyncGapHint(sender, player);
        }
    }

    private void sendSyncGapHint(CommandSender sender, Player player) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            return;
        }
        if (session.lastAuthorizedModelsSentAt() != null
                && session.serverRawPacketsSent() == 0
                && !distributionRepository.prepared().isEmpty()) {
            sender.sendMessage(ChatColor.YELLOW + player.getName()
                    + ": authorized ids were sent, but no S2C raw/native id=1 packets have been sent yet.");
            return;
        }

        NativeSyncState state = nativeSyncStates.get(player.getUniqueId());
        if (state != null && session.serverRawPacketsSent() > 0 && session.clientRawPacketsReceived() == 0) {
            sender.sendMessage(ChatColor.YELLOW + player.getName()
                    + ": native bootstrap id=1 was sent; waiting for a C2S raw/native id=2 response.");
        } else if (state != null && session.clientRawPacketsReceived() > 0 && state.lastDecodedAt() == null) {
            sender.sendMessage(ChatColor.YELLOW + player.getName()
                    + ": C2S raw/native packets were captured, but the bootstrap decoder has not parsed them yet.");
        } else if (state != null && state.lastDecodedAt() != null) {
            sender.sendMessage(ChatColor.GREEN + player.getName()
                    + ": native raw decoded; next step is generating the type=3/model cache stream.");
        }
    }

    private void sendNativeSyncStatus(CommandSender sender, Player player) {
        NativeSyncState state = nativeSyncStates.get(player.getUniqueId());
        if (state == null) {
            sender.sendMessage(ChatColor.GRAY + player.getName() + ": nativeBootstrap=not-started");
            return;
        }
        sender.sendMessage(ChatColor.LIGHT_PURPLE + player.getName() + ": " + state.describe());
    }

    private void sendReportNativeStatus(CommandSender sender, Player player) {
        ReportNativeSession state = reportNativeSessions.get(player.getUniqueId());
        if (state == null) {
            sender.sendMessage(ChatColor.GRAY + player.getName() + ": reportNative=not-started");
            return;
        }
        sender.sendMessage(ChatColor.GOLD + player.getName() + ": " + state.describe());
    }

    private void sendNativeCacheReplayStatus(CommandSender sender, Player player) {
        NativeCacheReplaySession state = nativeCacheReplaySessions.get(player.getUniqueId());
        if (state == null) {
            sender.sendMessage(ChatColor.GRAY + player.getName() + ": nativeCache=not-started");
            return;
        }
        sender.sendMessage(ChatColor.DARK_AQUA + player.getName() + ": " + state.describe());
    }

    private void startReportNativeSync(
            CommandSender sender,
            Player player,
            String type3Layout,
            String type3KeyMode,
            int type3PaddingBytes) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            sender.sendMessage(ChatColor.RED + player.getName() + " has not completed the YSM 51/52 handshake yet.");
            return;
        }
        if (modelRepository.entries().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No loaded YSM models. Put .ysm files under the models folder and run /ysm models reload.");
            return;
        }
        if (distributionRepository.prepared().isEmpty()) {
            prepareDistributionRepository(true);
        }
        if (distributionRepository.prepared().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No prepared distribution packages exist yet.");
            return;
        }

        byte[] s2cKey = YsmNativeSyncPrototype.randomKey();
        byte[] serverCacheKey = YsmNativeSyncPrototype.randomKey();
        byte[] clientCacheKey = YsmNativeSyncPrototype.randomKey();
        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(s2cKey, reportNativePadding());
        byte[] type1Raw = YsmRawPacketCodec.encryptBodyOnly(type1Plain, REPORT_NATIVE_BOOTSTRAP_KEY);

        ReportNativeSession reportState = ReportNativeSession.started(
                s2cKey,
                serverCacheKey,
                clientCacheKey,
                distributionRepository.prepared().size(),
                type3Layout,
                type3KeyMode,
                type3PaddingBytes);
        reportNativeSessions.put(player.getUniqueId(), reportState);
        sendServerRawPacket(player, type1Raw, "report-native:type1");

        sender.sendMessage(ChatColor.GREEN + "Started report-native YSM sync for " + player.getName()
                + ": sent type1, waiting for C2S type2 key. type3="
                + type3Layout + "/" + type3KeyMode + "/padding" + type3PaddingBytes + ".");
        getLogger().info("YSM report-native sync started: player=" + player.getName()
                + ", models=" + distributionRepository.prepared().size()
                + ", chunks=" + distributionRepository.totalChunkCount()
                + ", type3Layout=" + type3Layout
                + ", type3KeyMode=" + type3KeyMode
                + ", type3PaddingBytes=" + type3PaddingBytes
                + ", type1PlainBytes=" + type1Plain.length
                + ", type1RawBytes=" + type1Raw.length
                + ", bootstrapKey=report-hardcoded"
                + ", s2cKey=" + YsmNativeSyncPrototype.keyPreview(s2cKey)
                + ", serverCacheKey=" + YsmNativeSyncPrototype.keyPreview(serverCacheKey)
                + ", clientCacheKey=" + YsmNativeSyncPrototype.keyPreview(clientCacheKey)
                + ".");
    }

    private void inspectReportNativePacket(Player player, byte[] rawPacketBody) {
        ReportNativeSession state = reportNativeSessions.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        if (state.c2sKey() == null) {
            try {
                YsmRawPacketCodec.PlainPacket packet =
                        YsmRawPacketCodec.decryptBodyOnly(rawPacketBody, state.s2cKey());
                if (packet.type() != 2 || packet.selectedKey().isEmpty()) {
                    getLogger().warning("YSM report-native unexpected first client packet: player=" + player.getName()
                            + ", type=" + packet.type()
                            + ", summary=" + packet.summary()
                            + ", bodyBytes=" + packet.body().length + ".");
                    reportNativeSessions.put(player.getUniqueId(), state.withClientPacket(packet));
                    return;
                }

                byte[] c2sKey = packet.selectedKey().get();
                ReportNativeSession withC2s = state.withC2sKey(c2sKey, Instant.now()).withClientPacket(packet);
                reportNativeSessions.put(player.getUniqueId(), withC2s);
                getLogger().info("YSM report-native client type2 decoded: player=" + player.getName()
                        + ", rawBytes=" + rawPacketBody.length
                        + ", bodyBytes=" + packet.body().length
                        + ", c2sKey=" + YsmNativeSyncPrototype.keyPreview(c2sKey)
                        + ", summary=" + packet.summary()
                        + ", preview=" + YsmProtocol.toHex(packet.body(), rawPacketHexPreviewBytes)
                        + ".");
                sendReportNativeManifest(player, withC2s);
            } catch (RuntimeException ex) {
                getLogger().warning("YSM report-native type2 decode failed: player=" + player.getName()
                        + ", rawBytes=" + rawPacketBody.length
                        + ", key=s2c"
                        + ", reason=" + ex.getMessage() + ".");
            }
            return;
        }

        try {
            YsmRawPacketCodec.PlainPacket packet =
                    YsmRawPacketCodec.decryptBodyOnly(rawPacketBody, state.c2sKey());
            reportNativeSessions.put(player.getUniqueId(), state.withClientPacket(packet));
            getLogger().info("YSM report-native client packet decoded: player=" + player.getName()
                    + ", type=" + packet.type()
                    + ", summary=" + packet.summary()
                    + ", rawBytes=" + rawPacketBody.length
                    + ", bodyBytes=" + packet.body().length
                    + ", preview=" + YsmProtocol.toHex(packet.body(), rawPacketHexPreviewBytes)
                    + ".");
            if (packet.type() == 4) {
                getLogger().info("YSM report-native reached model request stage: player=" + player.getName()
                        + ", nextStep=encode type5 cache chunks with report serverCacheKey.");
            }
        } catch (RuntimeException ex) {
            getLogger().warning("YSM report-native client packet decode failed: player=" + player.getName()
                    + ", rawBytes=" + rawPacketBody.length
                    + ", key=c2s"
                    + ", reason=" + ex.getMessage() + ".");
        }
    }

    private void sendReportNativeManifest(Player player, ReportNativeSession state) {
        byte[] type3Plain = createReportNativeManifestPlain(state);
        if (REPORT_TYPE3_KEY_C2S.equals(state.type3KeyMode())) {
            sendReportNativeManifestAttempt(player, state, type3Plain, state.c2sKey(), REPORT_TYPE3_KEY_C2S, 0);
            return;
        }
        if (REPORT_TYPE3_KEY_BOTH.equals(state.type3KeyMode())) {
            sendReportNativeManifestAttempt(player, state, type3Plain, state.s2cKey(), REPORT_TYPE3_KEY_S2C, 0);
            sendReportNativeManifestAttempt(player, state, type3Plain, state.c2sKey(), REPORT_TYPE3_KEY_C2S, 6);
            return;
        }
        sendReportNativeManifestAttempt(player, state, type3Plain, state.s2cKey(), REPORT_TYPE3_KEY_S2C, 0);
    }

    private void sendReportNativeManifestAttempt(
            Player player,
            ReportNativeSession state,
            byte[] type3Plain,
            @Nullable byte[] transportKey,
            String keyLabel,
            long delayTicks) {
        if (transportKey == null) {
            getLogger().warning("YSM report-native type3 skipped: player=" + player.getName()
                    + ", key=" + keyLabel
                    + ", reason=key-not-available.");
            return;
        }
        UUID playerId = player.getUniqueId();
        Runnable send = () -> {
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) {
                return;
            }
            byte[] type3Raw = YsmRawPacketCodec.encryptBodyOnly(type3Plain, transportKey);
            ReportNativeSession latest = reportNativeSessions.get(playerId);
            if (latest != null) {
                reportNativeSessions.put(playerId, latest.withType3Sent(type3Raw.length, Instant.now()));
            }
            sendServerRawPacket(current, type3Raw, "report-native:type3-" + state.type3Layout() + "-" + keyLabel);
            getLogger().info("YSM report-native type3 sent: player=" + current.getName()
                    + ", layout=" + state.type3Layout()
                    + ", key=" + keyLabel
                    + ", models=" + distributionRepository.prepared().size()
                    + ", chunks=" + distributionRepository.totalChunkCount()
                    + ", plainBytes=" + type3Plain.length
                    + ", rawBytes=" + type3Raw.length
                    + ", type3PaddingBytes=" + state.type3PaddingBytes()
                    + ", transportKey=" + YsmNativeSyncPrototype.keyPreview(transportKey)
                    + ", s2cKey=" + YsmNativeSyncPrototype.keyPreview(state.s2cKey())
                    + ", c2sKey=" + YsmNativeSyncPrototype.keyPreview(state.c2sKey())
                    + ", serverCacheKey=" + YsmNativeSyncPrototype.keyPreview(state.serverCacheKey())
                    + ", clientCacheKey=" + YsmNativeSyncPrototype.keyPreview(state.clientCacheKey())
                    + ".");
        };
        if (delayTicks <= 0) {
            send.run();
        } else {
            Bukkit.getScheduler().runTaskLater(this, send, delayTicks);
        }
    }

    private byte[] createReportNativeManifestPlain(ReportNativeSession state) {
        byte[] padding = reportNativePadding(state.type3PaddingBytes());
        List<YsmRawPacketCodec.ModelInfo> models = reportNativeModelInfo();
        return switch (state.type3Layout()) {
            case REPORT_TYPE3_LAYOUT_KEYS -> YsmRawPacketCodec.encodePlainType3Keys(
                    state.serverCacheKey(),
                    state.clientCacheKey(),
                    padding);
            case REPORT_TYPE3_LAYOUT_KEYS_MODELS -> YsmRawPacketCodec.encodePlainType3KeyManifest(
                    state.serverCacheKey(),
                    state.clientCacheKey(),
                    models,
                    padding);
            case REPORT_TYPE3_LAYOUT_LEGACY -> createLegacyReportNativeManifestPlain(
                    state.serverCacheKey(),
                    state.clientCacheKey(),
                    models,
                    padding);
            default -> throw new IllegalArgumentException("Unsupported report type3 layout: " + state.type3Layout());
        };
    }

    private List<YsmRawPacketCodec.ModelInfo> reportNativeModelInfo() {
        return distributionRepository.prepared().stream()
                .map(model -> new YsmRawPacketCodec.ModelInfo(
                        model.modelId(),
                        model.format(),
                        model.transferBytes(),
                        model.decompressedBytes()))
                .toList();
    }

    private byte[] createLegacyReportNativeManifestPlain(
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            List<YsmRawPacketCodec.ModelInfo> models,
            byte[] padding) {
        List<Long> prelude = models.stream()
                .map(model -> 0L)
                .toList();
        return YsmRawPacketCodec.encodePlainType3(
                0,
                Arrays.copyOfRange(serverCacheKey, 0, 0x1c),
                Arrays.copyOfRange(serverCacheKey, 0x1c, YsmRawPacketCodec.KEY_BYTES),
                clientCacheKey,
                models,
                prelude,
                padding);
    }

    private static byte[] reportNativePadding() {
        byte[] padding = new byte[REPORT_NATIVE_TYPE1_PADDING_BYTES];
        for (int i = 0; i < padding.length; i++) {
            padding[i] = (byte) (0x41 + i);
        }
        return padding;
    }

    private static byte[] reportNativePadding(int paddingBytes) {
        int normalizedPaddingBytes = YsmNativeSyncPrototype.normalizePaddingBytes(paddingBytes);
        byte[] padding = new byte[normalizedPaddingBytes];
        for (int i = 0; i < padding.length; i++) {
            padding[i] = (byte) (0x31 + (i & 0x3f));
        }
        return padding;
    }

    private static @Nullable String normalizeReportType3Layout(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "key", "keys" -> REPORT_TYPE3_LAYOUT_KEYS;
            case "manifest", "keys-manifest", "key-manifest", "keys-models" -> REPORT_TYPE3_LAYOUT_KEYS_MODELS;
            case "legacy", "old" -> REPORT_TYPE3_LAYOUT_LEGACY;
            default -> null;
        };
    }

    private static @Nullable String normalizeGeneratedCacheLayout(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return GENERATED_CACHE_LAYOUT_OPENYSM;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "openysm", "server-cache", "native", "real" -> GENERATED_CACHE_LAYOUT_OPENYSM;
            case "legacy", "old" -> GENERATED_CACHE_LAYOUT_LEGACY;
            case "keys", "key", "server-client-keys" -> GENERATED_CACHE_LAYOUT_KEYS;
            default -> null;
        };
    }

    private static @Nullable String normalizeGeneratedCachePayload(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return GENERATED_CACHE_PAYLOAD_SERVER_CACHE;
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "server-cache", "openysm", "native-cache", "cache" -> GENERATED_CACHE_PAYLOAD_SERVER_CACHE;
            case "zstd", "washed", "washed-zstd" -> GENERATED_CACHE_PAYLOAD_WASHED_ZSTD;
            case "headerless", "headerless-v3", "crypto-body", "crypto-body-v3" ->
                    GENERATED_CACHE_PAYLOAD_HEADERLESS_V3;
            case "encrypted", "encrypted-v3", "encrypted-body", "encrypted-body-v3" ->
                    GENERATED_CACHE_PAYLOAD_ENCRYPTED_V3;
            default -> null;
        };
    }

    private static @Nullable String normalizeReportType3KeyMode(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "s2c", "server" -> REPORT_TYPE3_KEY_S2C;
            case "c2s", "client" -> REPORT_TYPE3_KEY_C2S;
            case "both", "all" -> REPORT_TYPE3_KEY_BOTH;
            default -> null;
        };
    }

    private void startNativeCacheReplay(
            @Nullable CommandSender sender,
            Player player,
            String captureName,
            int intervalTicks,
            int chunkBytes) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + player.getName() + " has not completed the YSM 51/52 handshake yet.");
            }
            getLogger().warning("YSM native cache replay skipped: player=" + player.getName()
                    + ", source=" + captureName
                    + ", reason=session-not-compatible.");
            return;
        }

        Path sourceDir = resolveNativeCacheReplayDir(captureName);
        Path type3BodyFile = sourceDir.resolve("type3-body.bin");
        Path serverCacheFile = sourceDir.resolve("server-cache.bin");
        byte[] type3Body;
        Map<String, NativeCacheEntry> cacheEntries;
        @Nullable byte[] fallbackServerCacheBytes = null;
        int type1PaddingBytes;
        int type3PaddingBytes;
        try {
            type3Body = Files.readAllBytes(type3BodyFile);
            cacheEntries = loadNativeCacheEntries(sourceDir);
            if (cacheEntries.isEmpty() && Files.exists(serverCacheFile)) {
                fallbackServerCacheBytes = Files.readAllBytes(serverCacheFile);
            }
            type1PaddingBytes = readOptionalPadding(sourceDir.resolve("type1-padding.txt"), FREESIA_NATIVE_TYPE1_PADDING_BYTES);
            type3PaddingBytes = readOptionalPadding(sourceDir.resolve("type3-padding.txt"), FREESIA_NATIVE_TYPE3_PADDING_BYTES);
        } catch (IOException | IllegalArgumentException ex) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Failed to load native cache replay from "
                        + sourceDir.toAbsolutePath() + ": " + ex.getMessage());
            }
            getLogger().warning("YSM native cache replay load failed: player=" + player.getName()
                    + ", source=" + sourceDir.toAbsolutePath()
                    + ", reason=" + ex.getMessage() + ".");
            return;
        }
        long cacheBytes = nativeCacheBytes(cacheEntries, fallbackServerCacheBytes);
        if (type3Body.length < 112 || cacheBytes == 0) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Native cache replay files are incomplete under "
                        + sourceDir.toAbsolutePath() + ".");
            }
            getLogger().warning("YSM native cache replay load failed: player=" + player.getName()
                    + ", source=" + sourceDir.toAbsolutePath()
                    + ", reason=incomplete-files"
                    + ", type3BodyBytes=" + type3Body.length
                    + ", cacheBytes=" + cacheBytes + ".");
            return;
        }

        byte[] s2cKey = YsmNativeSyncPrototype.randomKey();
        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(s2cKey, reportNativePadding(type1PaddingBytes));
        byte[] type1Raw = YsmRawPacketCodec.encryptBodyOnly(type1Plain, REPORT_NATIVE_BOOTSTRAP_KEY);
        NativeCacheReplaySession state = NativeCacheReplaySession.started(
                captureName,
                sourceDir,
                s2cKey,
                type3Body,
                type1PaddingBytes,
                type3PaddingBytes,
                cacheEntries,
                fallbackServerCacheBytes,
                cacheBytes,
                intervalTicks,
                chunkBytes,
                1);
        nativeCacheReplaySessions.put(player.getUniqueId(), state);
        sendServerRawPacket(player, type1Raw, "native-cache:" + captureName + ":type1");

        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Started native-cache replay for " + player.getName()
                    + ": source=" + captureName
                    + ", entries=" + cacheEntries.size()
                    + ", cache=" + formatBytes(cacheBytes)
                    + ", waiting for C2S type2.");
        }
        getLogger().info("YSM native cache replay started: player=" + player.getName()
                + ", source=" + sourceDir.toAbsolutePath()
                + ", type3BodyBytes=" + type3Body.length
                + ", cacheEntries=" + cacheEntries.size()
                + ", serverCacheBytes=" + cacheBytes
                + ", type1PaddingBytes=" + type1PaddingBytes
                + ", type3PaddingBytes=" + type3PaddingBytes
                + ", chunkBytes=" + chunkBytes
                + ", intervalTicks=" + intervalTicks
                + ", s2cKey=" + YsmNativeSyncPrototype.keyPreview(s2cKey)
                + ".");
    }

    private void startGeneratedServerCachePrewarmAsync(
            @Nullable CommandSender sender,
            String requestedModelId,
            @Nullable String syncTarget,
            boolean syncEvenWhenUnchanged,
            String reason) {
        String modelRequest = generatedServerCachePrewarmModelId(requestedModelId);
        List<YsmModelRepository.Entry> entries = generatedServerCachePrewarmEntries(modelRequest);
        if (entries.isEmpty()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "No local YSM model matched generated cache prewarm request: "
                        + modelRequest + ".");
            }
            getLogger().warning("YSM generated OpenYSM server-cache prewarm skipped: request="
                    + modelRequest
                    + ", reason=no-models.");
            return;
        }

        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Prewarming generated OpenYSM server-cache in the background: request="
                    + modelRequest
                    + ", matchedModels=" + entries.size()
                    + ", incremental=true.");
        }
        getLogger().info("YSM generated OpenYSM server-cache prewarm scheduled: request="
                + modelRequest
                + ", reason=" + reason
                + ", matchedModels=" + entries.size()
                + ", syncTarget=" + (syncTarget == null ? "<none>" : syncTarget)
                + ", syncEvenWhenUnchanged=" + syncEvenWhenUnchanged + ".");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            GeneratedServerCachePrewarmResult result;
            try {
                result = prewarmGeneratedServerCache(modelRequest, entries);
            } catch (Exception ex) {
                getLogger().warning("YSM generated OpenYSM server-cache prewarm failed: request="
                        + modelRequest
                        + ", reason=" + ex.getMessage() + ".");
                if (sender != null) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED
                            + "Generated OpenYSM server-cache prewarm failed: " + ex.getMessage()));
                }
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                if (sender != null) {
                    sender.sendMessage(ChatColor.GREEN + "Generated OpenYSM server-cache prewarm finished: request="
                            + result.requestedModelId()
                            + ", prepared=" + result.preparedModels()
                            + ", current=" + result.currentModels()
                            + ", failed=" + result.failedModels()
                            + ", cache=" + formatBytes(result.cacheBytes()) + ".");
                    for (String failure : result.failureSamples()) {
                        sender.sendMessage(ChatColor.YELLOW + "- " + failure);
                    }
                }
                getLogger().info("YSM generated OpenYSM server-cache prewarm finished: request="
                        + result.requestedModelId()
                        + ", matchedModels=" + result.matchedModels()
                        + ", prepared=" + result.preparedModels()
                        + ", current=" + result.currentModels()
                        + ", failed=" + result.failedModels()
                        + ", cacheBytes=" + result.cacheBytes() + ".");
                if (syncTarget == null) {
                    return;
                }
                if (syncEvenWhenUnchanged || result.preparedModels() > 0) {
                    syncGeneratedServerCacheCatalog(sender, syncTarget, result.requestedModelId(), reason + "-catalog");
                } else {
                    getLogger().info("YSM generated OpenYSM server-cache startup sync skipped: request="
                            + result.requestedModelId()
                            + ", reason=no-new-cache-files.");
                }
            });
        });
    }

    private GeneratedServerCachePrewarmResult prewarmGeneratedServerCache(
            String requestedModelId,
            List<YsmModelRepository.Entry> entries) throws IOException {
        GeneratedOpenYsmCacheKeys keys = generatedOpenYsmCacheKeys();
        Map<String, GeneratedServerCacheIndexEntry> indexByModelId = generatedServerCacheIndexByModelId();
        int currentModels = 0;
        int preparedModels = 0;
        int failedModels = 0;
        long cacheBytesTotal = 0L;
        List<String> failureSamples = new ArrayList<>();
        int yieldEvery = Math.max(
                1,
                autoGeneratedCacheMaxModels > 0
                        ? autoGeneratedCacheMaxModels
                        : DEFAULT_AUTO_GENERATED_CACHE_MAX_MODELS);
        int processed = 0;

        for (YsmModelRepository.Entry entry : entries) {
            GeneratedServerCacheIndexEntry indexed = indexByModelId.get(entry.modelId());
            if (indexed != null && indexed.isCurrentFor(entry)) {
                currentModels++;
                processed++;
                logGeneratedPrewarmProgress(
                        requestedModelId,
                        processed,
                        entries.size(),
                        preparedModels,
                        currentModels,
                        failedModels);
                continue;
            }
            try {
                YsmDistributionRepository.PreparedModel model = prepareEphemeralDistributionModel(entry);
                GeneratedCachePayload payload = generatedCachePayload(
                        model,
                        GENERATED_CACHE_PAYLOAD_SERVER_CACHE);
                GeneratedCacheTokens tokens = generatedCacheTokens(model, keys.serverCacheKey());
                byte[] cacheBytes = YsmCrypto.encryptCachedModel(
                        payload.bytes(),
                        OpenYsmServerCacheConverter.SERVER_CACHE_FORMAT,
                        tokens.physicalHashA(),
                        tokens.physicalHashB(),
                        keys.serverCacheKey());
                persistGeneratedServerCache(entry, model, tokens, cacheBytes);
                cacheBytesTotal += cacheBytes.length;
                preparedModels++;
                if (debug && logProgressIntervalModels <= 1) {
                    getLogger().info("YSM generated OpenYSM server-cache prepared: model="
                            + model.modelId()
                            + ", cacheBytes=" + cacheBytes.length
                            + ", serverCacheYsmZstdBytes=" + model.serverCacheYsmZstdBytes()
                            + ".");
                }
            } catch (Exception ex) {
                failedModels++;
                if (failureSamples.size() < 5) {
                    failureSamples.add(entry.modelId() + ": " + ex.getMessage());
                }
                getLogger().warning("YSM generated OpenYSM server-cache model failed: request="
                        + requestedModelId
                        + ", model=" + entry.modelId()
                        + ", reason=" + ex.getMessage() + ".");
            }

            processed++;
            logGeneratedPrewarmProgress(
                    requestedModelId,
                    processed,
                    entries.size(),
                    preparedModels,
                    currentModels,
                    failedModels);
            if (processed % yieldEvery == 0) {
                try {
                    TimeUnit.MILLISECONDS.sleep(25L);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new GeneratedServerCachePrewarmResult(
                requestedModelId,
                entries.size(),
                currentModels,
                preparedModels,
                failedModels,
                cacheBytesTotal,
                List.copyOf(failureSamples));
    }

    private void logGeneratedPrewarmProgress(
            String requestedModelId,
            int processed,
            int total,
            int preparedModels,
            int currentModels,
            int failedModels) {
        if (!debug) {
            return;
        }
        int interval = Math.max(1, logProgressIntervalModels);
        if (processed < total && processed % interval != 0) {
            return;
        }
        getLogger().info("YSM generated OpenYSM server-cache progress: request="
                + requestedModelId
                + ", processed=" + processed + "/" + total
                + ", prepared=" + preparedModels
                + ", current=" + currentModels
                + ", failed=" + failedModels + ".");
    }

    private List<YsmModelRepository.Entry> generatedServerCachePrewarmEntries(String requestedModelId) {
        String modelId = generatedServerCachePrewarmModelId(requestedModelId);
        if (isGeneratedCacheAllRequest(modelId)) {
            return modelRepository.entries();
        }
        return findModelEntry(modelId).stream().toList();
    }

    private static String generatedServerCachePrewarmModelId(@Nullable String requestedModelId) {
        if (requestedModelId == null || requestedModelId.isBlank()) {
            return "all";
        }
        String modelId = requestedModelId.trim();
        if (GENERATED_CACHE_MODEL_SAVED.equalsIgnoreCase(modelId)) {
            return "all";
        }
        return modelId;
    }

    private int syncGeneratedServerCacheCatalog(
            @Nullable CommandSender sender,
            String targetName,
            String requestedModelId,
            String reason) {
        String modelRequest = requestedModelId == null || requestedModelId.isBlank()
                ? "all"
                : requestedModelId;
        if ("all".equalsIgnoreCase(targetName) || "*".equals(targetName)) {
            int started = 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                YsmClientSession session = sessions.get(player.getUniqueId());
                if (session == null || !session.compatible()) {
                    continue;
                }
                startGeneratedYsmCacheCatalogAsync(
                        sender,
                        player,
                        modelRequest,
                        autoGeneratedCacheIntervalTicks,
                        autoGeneratedCacheChunkBytes,
                        GENERATED_CACHE_LAYOUT_OPENYSM,
                        GENERATED_CACHE_PAYLOAD_SERVER_CACHE,
                        reason);
                started++;
            }
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "Requested generated OpenYSM catalog sync for "
                        + started + " compatible online player(s): model=" + modelRequest + ".");
            }
            return started;
        }

        Player player = Bukkit.getPlayerExact(targetName);
        if (player == null) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Player not found: " + targetName);
            }
            return 0;
        }
        startGeneratedYsmCacheCatalogAsync(
                sender,
                player,
                modelRequest,
                autoGeneratedCacheIntervalTicks,
                autoGeneratedCacheChunkBytes,
                GENERATED_CACHE_LAYOUT_OPENYSM,
                GENERATED_CACHE_PAYLOAD_SERVER_CACHE,
                reason);
        return 1;
    }

    private void startGeneratedYsmCacheAsync(
            @Nullable CommandSender sender,
            Player player,
            String requestedModelId,
            int intervalTicks,
            int chunkBytes,
            String layout,
            String payloadMode) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + player.getName() + " has not completed the YSM 51/52 handshake yet.");
            }
            getLogger().warning("YSM generated cache skipped: player=" + player.getName()
                    + ", model=" + requestedModelId
                    + ", layout=" + layout
                    + ", reason=session-not-compatible.");
            return;
        }

        String manifestLayout = normalizeGeneratedCacheLayout(layout);
        if (manifestLayout == null) {
            manifestLayout = GENERATED_CACHE_LAYOUT_OPENYSM;
        }
        String normalizedPayloadMode = normalizeGeneratedCachePayload(payloadMode);
        if (normalizedPayloadMode == null) {
            normalizedPayloadMode = GENERATED_CACHE_PAYLOAD_SERVER_CACHE;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String modelRequest = requestedModelId == null || requestedModelId.isBlank()
                ? GENERATED_CACHE_MODEL_SAVED
                : requestedModelId;
        int sendIntervalTicks = Math.max(1, intervalTicks);
        int sendChunkBytes = Math.max(1, chunkBytes);
        List<YsmModelRepository.Entry> requestedEntries = generatedCacheEntries(playerId, modelRequest);
        int availableModelCount = modelRepository.entries().size();

        if (isGeneratedCacheAllRequest(modelRequest)
                && autoGeneratedCacheMaxModels > 0
                && requestedEntries.size() > autoGeneratedCacheMaxModels) {
            GeneratedCacheBatchQueue queue = new GeneratedCacheBatchQueue(
                    modelRequest,
                    selectedGeneratedCacheSourceName(modelRequest, 0),
                    manifestLayout,
                    normalizedPayloadMode,
                    sendIntervalTicks,
                    sendChunkBytes,
                    requestedEntries,
                    autoGeneratedCacheMaxModels,
                    0,
                    0,
                    0,
                    0);
            generatedCacheBatchQueues.put(playerId, queue);
            if (sender != null) {
                sender.sendMessage(ChatColor.YELLOW + "Preparing generated YSM cache for " + playerName
                        + " in batches: models=" + requestedEntries.size()
                        + ", batchSize=" + autoGeneratedCacheMaxModels
                        + ", layout=" + manifestLayout
                        + ", payload=" + normalizedPayloadMode + ".");
            }
            if (debug) {
                getLogger().info("YSM generated cache batch queue started: player=" + playerName
                        + ", modelRequest=" + modelRequest
                        + ", models=" + requestedEntries.size()
                        + ", batchSize=" + autoGeneratedCacheMaxModels
                        + ", layout=" + manifestLayout
                        + ", payload=" + normalizedPayloadMode
                        + ", chunkBytes=" + sendChunkBytes
                        + ", intervalTicks=" + sendIntervalTicks + ".");
            }
            startNextGeneratedYsmCacheBatch(sender, playerId, playerName);
            return;
        }

        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Preparing generated YSM cache for " + playerName
                    + " in the background: model=" + modelRequest
                    + ", layout=" + manifestLayout
                    + ", payload=" + normalizedPayloadMode + ".");
        }
        if (debug) {
            getLogger().info("YSM generated cache prepare scheduled: player=" + playerName
                    + ", modelRequest=" + modelRequest
                    + ", layout=" + manifestLayout
                    + ", payload=" + normalizedPayloadMode
                    + ", chunkBytes=" + sendChunkBytes
                    + ", intervalTicks=" + sendIntervalTicks + ".");
        }

        String selectedLayout = manifestLayout;
        String selectedPayloadMode = normalizedPayloadMode;
        List<YsmModelRepository.Entry> selectedEntries = requestedEntries;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            GeneratedCacheStart prepared;
            try {
                prepared = buildGeneratedYsmCacheStart(
                        playerId,
                        playerName,
                        modelRequest,
                        selectedEntries,
                        availableModelCount,
                        false,
                        sendIntervalTicks,
                        sendChunkBytes,
                        selectedLayout,
                        selectedPayloadMode);
            } catch (Exception ex) {
                getLogger().warning("YSM generated cache prepare failed: player=" + playerName
                        + ", modelRequest=" + modelRequest
                        + ", layout=" + selectedLayout
                        + ", payload=" + selectedPayloadMode
                        + ", reason=" + ex.getMessage() + ".");
                if (sender != null) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED
                            + "Failed to prepare generated YSM cache for " + playerName
                            + ": " + ex.getMessage()));
                }
                return;
            }

            Bukkit.getScheduler().runTask(this, () ->
                    finishGeneratedYsmCacheStart(sender, playerId, playerName, prepared));
        });
    }

    private void startNextGeneratedYsmCacheBatch(
            @Nullable CommandSender sender,
            UUID playerId,
            String playerName) {
        GeneratedCacheBatchQueue queue = generatedCacheBatchQueues.get(playerId);
        if (queue == null) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            generatedCacheBatchQueues.remove(playerId);
            return;
        }
        YsmClientSession session = sessions.get(playerId);
        if (session == null || !session.compatible()) {
            generatedCacheBatchQueues.remove(playerId);
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + playerName + " no longer has a compatible YSM session.");
            }
            getLogger().warning("YSM generated cache batch queue stopped: player=" + playerName
                    + ", reason=session-not-compatible.");
            return;
        }

        int from = queue.nextIndex();
        if (from >= queue.entries().size()) {
            generatedCacheBatchQueues.remove(playerId);
            startGeneratedYsmCacheCatalogAsync(
                    sender,
                    player,
                    queue.requestedModelId(),
                    queue.intervalTicks(),
                    queue.chunkBytes(),
                    queue.manifestLayout(),
                    queue.payloadMode(),
                    "batch-catalog");
            return;
        }
        int to = Math.min(queue.entries().size(), from + queue.batchSize());
        int batchNumber = queue.startedBatches() + 1;
        int totalBatches = Math.max(1, (queue.entries().size() + queue.batchSize() - 1) / queue.batchSize());
        List<YsmModelRepository.Entry> batchEntries = List.copyOf(queue.entries().subList(from, to));
        GeneratedCacheBatchQueue nextQueue = queue.withProgress(to, batchNumber, 0, 0);
        generatedCacheBatchQueues.put(playerId, nextQueue);

        String batchRequestId = selectedGeneratedCacheSourceName(queue.requestedModelId(), batchNumber);
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Preparing generated YSM cache batch "
                    + batchNumber + "/" + totalBatches
                    + " for " + playerName
                    + ": models=" + batchEntries.size() + ".");
        }
        if (debug) {
            getLogger().info("YSM generated cache batch prepare scheduled: player=" + playerName
                    + ", batch=" + batchNumber + "/" + totalBatches
                    + ", range=" + from + "-" + (to - 1)
                    + ", models=" + batchEntries.size()
                    + ", remaining=" + Math.max(0, queue.entries().size() - to)
                    + ", layout=" + queue.manifestLayout()
                    + ", payload=" + queue.payloadMode() + ".");
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            GeneratedCacheStart prepared;
            try {
                prepared = buildGeneratedYsmCacheStart(
                        playerId,
                        playerName,
                        batchRequestId,
                        batchEntries,
                        queue.entries().size(),
                        true,
                        queue.intervalTicks(),
                        queue.chunkBytes(),
                        queue.manifestLayout(),
                        queue.payloadMode());
            } catch (Exception ex) {
                getLogger().warning("YSM generated cache batch prepare failed: player=" + playerName
                        + ", batch=" + batchNumber + "/" + totalBatches
                        + ", reason=" + ex.getMessage() + ".");
                Bukkit.getScheduler().runTask(this, () -> {
                    scheduleNextGeneratedCacheBatch(playerId, playerName, 1L, "batch-prepare-failed");
                    if (sender != null) {
                        sender.sendMessage(ChatColor.YELLOW + "Skipped generated YSM cache batch "
                                + batchNumber + "/" + totalBatches
                                + " for " + playerName
                                + ": " + ex.getMessage());
                    }
                });
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                finishGeneratedYsmCacheStart(sender, playerId, playerName, prepared);
                if (prepared.empty()) {
                    scheduleNextGeneratedCacheBatch(playerId, playerName, 1L, "empty-batch");
                }
            });
        });
    }

    private void startGeneratedYsmCacheCatalogAsync(
            @Nullable CommandSender sender,
            Player player,
            String requestedModelId,
            int intervalTicks,
            int chunkBytes,
            String layout,
            String payloadMode,
            String reason) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + player.getName() + " has not completed the YSM 51/52 handshake yet.");
            }
            return;
        }
        String manifestLayout = normalizeGeneratedCacheLayout(layout);
        if (manifestLayout == null) {
            manifestLayout = GENERATED_CACHE_LAYOUT_OPENYSM;
        }
        String normalizedPayloadMode = normalizeGeneratedCachePayload(payloadMode);
        if (normalizedPayloadMode == null) {
            normalizedPayloadMode = GENERATED_CACHE_PAYLOAD_SERVER_CACHE;
        }
        UUID playerId = player.getUniqueId();
        String playerName = player.getName();
        String modelRequest = requestedModelId == null || requestedModelId.isBlank()
                ? GENERATED_CACHE_MODEL_SAVED
                : requestedModelId;
        int sendIntervalTicks = Math.max(1, intervalTicks);
        int sendChunkBytes = Math.max(1, chunkBytes);
        List<YsmModelRepository.Entry> requestedEntries = generatedCacheEntries(playerId, modelRequest);
        int availableModelCount = modelRepository.entries().size();

        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "Sending generated YSM catalog for " + playerName
                    + ": model=" + modelRequest
                    + ", layout=" + manifestLayout
                    + ", source=server-cache-index.");
        }
        if (debug) {
            getLogger().info("YSM generated cache catalog scheduled: player=" + playerName
                    + ", modelRequest=" + modelRequest
                    + ", reason=" + reason
                    + ", entries=" + requestedEntries.size()
                    + ", layout=" + manifestLayout
                    + ", payload=" + normalizedPayloadMode + ".");
        }

        String selectedLayout = manifestLayout;
        String selectedPayloadMode = normalizedPayloadMode;
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            GeneratedCacheStart prepared;
            try {
                prepared = buildGeneratedYsmCacheCatalogStart(
                        playerId,
                        playerName,
                        modelRequest,
                        requestedEntries,
                        availableModelCount,
                        sendIntervalTicks,
                        sendChunkBytes,
                        selectedLayout,
                        selectedPayloadMode);
            } catch (Exception ex) {
                getLogger().warning("YSM generated cache catalog failed: player=" + playerName
                        + ", modelRequest=" + modelRequest
                        + ", reason=" + ex.getMessage() + ".");
                if (sender != null) {
                    Bukkit.getScheduler().runTask(this, () -> sender.sendMessage(ChatColor.RED
                            + "Failed to send generated YSM catalog for " + playerName
                            + ": " + ex.getMessage()));
                }
                return;
            }
            Bukkit.getScheduler().runTask(this, () ->
                    finishGeneratedYsmCacheStart(sender, playerId, playerName, prepared));
        });
    }

    private GeneratedCacheStart buildGeneratedYsmCacheStart(
            UUID playerId,
            String playerName,
            String requestedModelId,
            List<YsmModelRepository.Entry> requestedEntries,
            int availableModelCount,
            boolean modelLimitApplied,
            int intervalTicks,
            int chunkBytes,
            String manifestLayout,
            String normalizedPayloadMode) throws IOException {
        if (requestedEntries.isEmpty()) {
            return GeneratedCacheStart.empty(
                    requestedModelId,
                    manifestLayout,
                    normalizedPayloadMode,
                    availableModelCount,
                    modelLimitApplied,
                    0);
        }

        byte[] modelKey = YsmNativeSyncPrototype.randomKey();
        boolean openYsmLayout = GENERATED_CACHE_LAYOUT_OPENYSM.equals(manifestLayout);
        boolean keyLayout = openYsmLayout || GENERATED_CACHE_LAYOUT_KEYS.equals(manifestLayout);
        GeneratedOpenYsmCacheKeys openYsmCacheKeys = keyLayout
                ? generatedOpenYsmCacheKeys()
                : null;
        byte[] serverCacheKey = keyLayout
                ? openYsmCacheKeys.serverCacheKey()
                : modelKey;
        byte[] clientCacheKey = keyLayout
                ? openYsmCacheKeys.clientCacheKey()
                : modelKey;
        byte[] transferCacheKey = keyLayout ? serverCacheKey : modelKey;
        LinkedHashMap<String, NativeCacheEntry> cacheEntries = new LinkedHashMap<>();
        List<GeneratedNativeCacheModel> manifestModels = new ArrayList<>();
        Path sessionDir = generatedCacheSessionDir(playerId, playerName);
        boolean completed = false;
        int skippedModels = 0;
        try {
            Files.createDirectories(sessionDir);
            for (YsmModelRepository.Entry requestedEntry : requestedEntries) {
                try {
                    YsmDistributionRepository.PreparedModel model = distributionRepository.find(requestedEntry.modelId())
                            .orElseGet(() -> prepareEphemeralDistributionModel(requestedEntry));
                    GeneratedCachePayload payload = generatedCachePayload(model, normalizedPayloadMode);
                    int nativeFormat = openYsmLayout
                            && GENERATED_CACHE_PAYLOAD_SERVER_CACHE.equals(normalizedPayloadMode)
                            ? OpenYsmServerCacheConverter.SERVER_CACHE_FORMAT
                            : model.format();
                    long hashA;
                    long hashB;
                    String tokenHex;
                    byte[] token;
                    Path cacheFile;
                    long bodyHash = 0L;
                    if (openYsmLayout) {
                        GeneratedCacheTokens tokens = generatedCacheTokens(model, serverCacheKey);
                        hashA = tokens.displayHashA();
                        hashB = tokens.displayHashB();
                        token = tokens.displayToken();
                        tokenHex = tokens.displayTokenHex();
                        byte[] cacheBytes = YsmCrypto.encryptCachedModel(
                                payload.bytes(),
                                nativeFormat,
                                tokens.physicalHashA(),
                                tokens.physicalHashB(),
                                transferCacheKey);
                        cacheFile = persistGeneratedServerCache(requestedEntry, model, tokens, cacheBytes);
                        bodyHash = YsmCrypto.cachedModelBodyVerificationHash(cacheBytes);
                    } else {
                        hashA = generatedCacheHash(model, payload.sha256(), autoGeneratedCacheTokenSalt, 0);
                        hashB = generatedCacheHash(model, payload.sha256(), autoGeneratedCacheTokenSalt, 8);
                        token = nativeCacheToken(hashA, hashB);
                        tokenHex = HEX.formatHex(token);
                        byte[] cacheBytes = YsmCrypto.encryptCachedModel(
                                payload.bytes(),
                                nativeFormat,
                                hashA,
                                hashB,
                                transferCacheKey);
                        cacheFile = persistGeneratedServerCache(requestedEntry, model, tokenHex, cacheBytes);
                    }
                    String nativeName = nativeCacheModelName(model.modelId());
                    long cacheBytesLength = Files.size(cacheFile);
                    NativeCacheEntry entry = NativeCacheEntry.fromFile(
                            tokenHex,
                            token,
                            nativeName,
                            cacheFile,
                            cacheBytesLength,
                            openYsmLayout ? bodyHash : null,
                            hashA,
                            hashB);
                    cacheEntries.put(tokenHex, entry);
                    manifestModels.add(new GeneratedNativeCacheModel(
                            token,
                            nativeName,
                            nativeFormat,
                            payload.bytes().length,
                            Math.toIntExact(Math.min(Integer.MAX_VALUE, cacheBytesLength))));
                } catch (Exception ex) {
                    skippedModels++;
                    getLogger().warning("YSM generated cache model skipped: player=" + playerName
                            + ", request=" + requestedModelId
                            + ", model=" + requestedEntry.modelId()
                            + ", version=" + requestedEntry.version()
                            + ", format=" + requestedEntry.format()
                            + ", reason=" + ex.getMessage() + ".");
                }
            }
            completed = true;
        } finally {
            if (!completed) {
                cleanupGeneratedCacheSessionDirectory(sessionDir, "prepare-failed");
            }
        }
        if (manifestModels.isEmpty()) {
            cleanupGeneratedCacheSessionDirectory(sessionDir, "prepare-empty");
            return GeneratedCacheStart.empty(
                    requestedModelId,
                    manifestLayout,
                    normalizedPayloadMode,
                    availableModelCount,
                    modelLimitApplied,
                    skippedModels);
        }

        byte[] type3Body = createGeneratedCacheManifestBody(
                manifestLayout,
                modelKey,
                serverCacheKey,
                clientCacheKey,
                manifestModels);
        writeGeneratedCacheSessionDebugFiles(
                sessionDir,
                requestedModelId,
                manifestLayout,
                normalizedPayloadMode,
                manifestModels.size(),
                type3Body,
                cacheEntries,
                serverCacheKey,
                clientCacheKey);
        long cacheBytes = nativeCacheBytes(cacheEntries, null);
        byte[] s2cKey = YsmNativeSyncPrototype.randomKey();
        int type1PaddingBytes = FREESIA_NATIVE_TYPE1_PADDING_BYTES;
        int type3PaddingBytes = FREESIA_NATIVE_TYPE3_PADDING_BYTES;
        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(s2cKey, reportNativePadding(type1PaddingBytes));
        byte[] type1Raw = YsmRawPacketCodec.encryptBodyOnly(type1Plain, REPORT_NATIVE_BOOTSTRAP_KEY);
        String sourceName = "generated-ysm:" + manifestLayout + ":" + ("all".equalsIgnoreCase(requestedModelId)
                ? "all"
                : requestedModelId);
        NativeCacheReplaySession state = NativeCacheReplaySession.started(
                sourceName,
                sessionDir,
                s2cKey,
                type3Body,
                type1PaddingBytes,
                type3PaddingBytes,
                cacheEntries,
                null,
                cacheBytes,
                intervalTicks,
                chunkBytes,
                autoGeneratedCacheType5PacketsPerTick);
        return new GeneratedCacheStart(
                requestedModelId,
                manifestLayout,
                normalizedPayloadMode,
                requestedEntries.size(),
                availableModelCount,
                modelLimitApplied,
                skippedModels,
                cacheEntries.size(),
                type3Body.length,
                cacheBytes,
                modelKey,
                serverCacheKey,
                clientCacheKey,
                s2cKey,
                type1Raw,
                state);
    }

    private GeneratedCacheStart buildGeneratedYsmCacheCatalogStart(
            UUID playerId,
            String playerName,
            String requestedModelId,
            List<YsmModelRepository.Entry> requestedEntries,
            int availableModelCount,
            int intervalTicks,
            int chunkBytes,
            String manifestLayout,
            String normalizedPayloadMode) throws IOException {
        if (!GENERATED_CACHE_LAYOUT_OPENYSM.equals(manifestLayout)
                || !GENERATED_CACHE_PAYLOAD_SERVER_CACHE.equals(normalizedPayloadMode)) {
            return buildGeneratedYsmCacheStart(
                    playerId,
                    playerName,
                    requestedModelId,
                    requestedEntries,
                    availableModelCount,
                    false,
                    intervalTicks,
                    chunkBytes,
                    manifestLayout,
                    normalizedPayloadMode);
        }

        GeneratedOpenYsmCacheKeys openYsmCacheKeys = generatedOpenYsmCacheKeys();
        Map<String, GeneratedServerCacheIndexEntry> indexByModelId = generatedServerCacheIndexByModelId();
        LinkedHashMap<String, NativeCacheEntry> cacheEntries = new LinkedHashMap<>();
        List<GeneratedNativeCacheModel> manifestModels = new ArrayList<>();
        int skippedModels = 0;

        for (YsmModelRepository.Entry requestedEntry : requestedEntries) {
            GeneratedServerCacheIndexEntry indexed = indexByModelId.get(requestedEntry.modelId());
            if (indexed == null || !indexed.isCurrentFor(requestedEntry)) {
                skippedModels++;
                continue;
            }
            Path cacheFile = indexed.cacheFile();
            if (!Files.exists(cacheFile) || Files.size(cacheFile) != indexed.cacheBytes()) {
                skippedModels++;
                continue;
            }
            byte[] token = HEX.parseHex(indexed.tokenHex());
            NativeCacheEntry entry = NativeCacheEntry.fromFile(
                    indexed.tokenHex(),
                    token,
                    nativeCacheModelName(indexed.modelId()),
                    cacheFile,
                    indexed.cacheBytes(),
                    indexed.bodyHash(),
                    indexed.displayHashA(),
                    indexed.displayHashB());
            cacheEntries.put(indexed.tokenHex(), entry);
            manifestModels.add(new GeneratedNativeCacheModel(
                    token,
                    nativeCacheModelName(indexed.modelId()),
                    OpenYsmServerCacheConverter.SERVER_CACHE_FORMAT,
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, indexed.serverCacheBytes())),
                    Math.toIntExact(Math.min(Integer.MAX_VALUE, indexed.cacheBytes()))));
        }

        if (manifestModels.isEmpty()) {
            return GeneratedCacheStart.empty(
                    requestedModelId,
                    manifestLayout,
                    normalizedPayloadMode,
                    availableModelCount,
                    false,
                    skippedModels);
        }

        byte[] modelKey = YsmNativeSyncPrototype.randomKey();
        byte[] type3Body = createGeneratedCacheManifestBody(
                manifestLayout,
                modelKey,
                openYsmCacheKeys.serverCacheKey(),
                openYsmCacheKeys.clientCacheKey(),
                manifestModels);
        Path sessionDir = generatedCacheSessionDir(playerId, playerName);
        Files.createDirectories(sessionDir);
        writeGeneratedCacheSessionDebugFiles(
                sessionDir,
                requestedModelId + "#catalog",
                manifestLayout,
                normalizedPayloadMode,
                manifestModels.size(),
                type3Body,
                cacheEntries,
                openYsmCacheKeys.serverCacheKey(),
                openYsmCacheKeys.clientCacheKey());
        long cacheBytes = nativeCacheBytes(cacheEntries, null);
        byte[] s2cKey = YsmNativeSyncPrototype.randomKey();
        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(
                s2cKey,
                reportNativePadding(FREESIA_NATIVE_TYPE1_PADDING_BYTES));
        byte[] type1Raw = YsmRawPacketCodec.encryptBodyOnly(type1Plain, REPORT_NATIVE_BOOTSTRAP_KEY);
        NativeCacheReplaySession state = NativeCacheReplaySession.started(
                "generated-ysm:" + manifestLayout + ":" + requestedModelId + "#catalog",
                sessionDir,
                s2cKey,
                type3Body,
                FREESIA_NATIVE_TYPE1_PADDING_BYTES,
                FREESIA_NATIVE_TYPE3_PADDING_BYTES,
                cacheEntries,
                null,
                cacheBytes,
                intervalTicks,
                chunkBytes,
                autoGeneratedCacheType5PacketsPerTick);
        return new GeneratedCacheStart(
                requestedModelId + "#catalog",
                manifestLayout,
                normalizedPayloadMode,
                requestedEntries.size(),
                availableModelCount,
                false,
                skippedModels,
                cacheEntries.size(),
                type3Body.length,
                cacheBytes,
                modelKey,
                openYsmCacheKeys.serverCacheKey(),
                openYsmCacheKeys.clientCacheKey(),
                s2cKey,
                type1Raw,
                state);
    }

    private void finishGeneratedYsmCacheStart(
            @Nullable CommandSender sender,
            UUID playerId,
            String playerName,
            GeneratedCacheStart prepared) {
        if (prepared.empty()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "No generated YSM cache model matched request: "
                        + prepared.requestedModelId()
                        + (prepared.skippedModelCount() > 0
                                ? " (" + prepared.skippedModelCount() + " model(s) skipped)."
                                : "."));
            }
            getLogger().warning("YSM generated cache skipped: player=" + playerName
                    + ", modelRequest=" + prepared.requestedModelId()
                    + ", skippedModels=" + prepared.skippedModelCount()
                    + ", reason=no-models.");
            return;
        }

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            cleanupGeneratedCacheSessionDirectory(prepared.state().sourceDir(), "player-offline-before-send");
            return;
        }
        YsmClientSession session = sessions.get(playerId);
        if (session == null || !session.compatible()) {
            cleanupGeneratedCacheSessionDirectory(prepared.state().sourceDir(), "session-not-compatible-before-send");
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + playerName + " no longer has a compatible YSM session.");
            }
            getLogger().warning("YSM generated cache skipped: player=" + playerName
                    + ", modelRequest=" + prepared.requestedModelId()
                    + ", reason=session-not-compatible-after-prepare.");
            return;
        }

        NativeCacheReplaySession previous = nativeCacheReplaySessions.put(playerId, prepared.state());
        if (previous != null) {
            cleanupGeneratedCacheSessionDirectory(previous.sourceDir(), "replaced-by-new-generated-sync");
        }
        sendServerRawPacket(player, prepared.type1Raw(), "generated-ysm-cache:" + prepared.requestedModelId() + ":type1");
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Started generated YSM cache sync for " + player.getName()
                    + ": models=" + prepared.modelCount()
                    + ", cacheEntries=" + prepared.cacheEntryCount()
                    + ", layout=" + prepared.manifestLayout()
                    + ", payload=" + prepared.payloadMode()
                    + ", cache=" + formatBytes(prepared.cacheBytes())
                    + ", type3Body=" + prepared.type3BodyBytes() + "b"
                    + ", waiting for C2S type2.");
            if (prepared.modelLimitApplied()) {
                sender.sendMessage(ChatColor.YELLOW + "This is a generated-cache batch; the next batch will start after this type3/type5 step completes.");
            }
            if (prepared.skippedModelCount() > 0) {
                sender.sendMessage(ChatColor.YELLOW + "Skipped " + prepared.skippedModelCount()
                        + " model(s) that the Java cache generator cannot prepare yet.");
            }
            if (GENERATED_CACHE_LAYOUT_KEYS.equals(prepared.manifestLayout())) {
                sender.sendMessage(ChatColor.YELLOW + "This is experimental: keys layout tests ServerCacheKey/ClientCacheKey type3 and ServerCacheKey-encrypted type5 chunks.");
            } else if (GENERATED_CACHE_LAYOUT_LEGACY.equals(prepared.manifestLayout())) {
                sender.sendMessage(ChatColor.YELLOW + "This is experimental: legacy layout is kept because it currently reaches type4/type5.");
            }
        }
        if (debug) {
            getLogger().info("YSM generated cache started: player=" + player.getName()
                    + ", modelRequest=" + prepared.requestedModelId()
                    + ", layout=" + prepared.manifestLayout()
                    + ", payload=" + prepared.payloadMode()
                    + ", models=" + prepared.modelCount()
                    + ", availableModels=" + prepared.availableModelCount()
                    + ", batch=" + prepared.modelLimitApplied()
                    + ", skippedModels=" + prepared.skippedModelCount()
                    + ", type3BodyBytes=" + prepared.type3BodyBytes()
                    + ", cacheEntries=" + prepared.cacheEntryCount()
                    + ", cacheBytes=" + prepared.cacheBytes()
                    + ", chunkBytes=" + prepared.state().chunkBytes()
                    + ", intervalTicks=" + prepared.state().intervalTicks()
                    + ", tokenSalt=" + logValue(autoGeneratedCacheTokenSalt)
                    + ", sessionDir=" + prepared.state().sourceDir().toAbsolutePath()
                    + ", modelKey=" + YsmNativeSyncPrototype.keyPreview(prepared.modelKey())
                    + ", serverCacheKey=" + YsmNativeSyncPrototype.keyPreview(prepared.serverCacheKey())
                    + ", clientCacheKey=" + YsmNativeSyncPrototype.keyPreview(prepared.clientCacheKey())
                    + ", s2cKey=" + YsmNativeSyncPrototype.keyPreview(prepared.s2cKey())
                    + ".");
        } else {
            getLogger().info("YSM generated cache sync started: player=" + player.getName()
                    + ", models=" + prepared.modelCount()
                    + ", cache=" + formatBytes(prepared.cacheBytes()) + ".");
        }
    }

    private List<YsmModelRepository.Entry> generatedCacheEntries(UUID playerId, String requestedModelId) {
        String modelId = requestedModelId == null || requestedModelId.isBlank()
                ? GENERATED_CACHE_MODEL_SAVED
                : requestedModelId;
        if (GENERATED_CACHE_MODEL_SAVED.equalsIgnoreCase(modelId)) {
            Optional<String> savedModelId = generatedSavedModelId(playerId);
            if (savedModelId.isEmpty()) {
                return List.of();
            }
            return findModelEntry(savedModelId.get()).stream().toList();
        }
        if ("all".equalsIgnoreCase(modelId) || "*".equals(modelId)) {
            return modelRepository.entries();
        }

        return findModelEntry(modelId).stream().toList();
    }

    private static boolean isGeneratedCacheAllRequest(String modelId) {
        return "all".equalsIgnoreCase(modelId) || "*".equals(modelId);
    }

    private static String selectedGeneratedCacheSourceName(String requestedModelId, int batchNumber) {
        if (batchNumber > 0) {
            return requestedModelId + "#batch-" + batchNumber;
        }
        return requestedModelId;
    }

    private Optional<String> generatedSavedModelId(UUID playerId) {
        AppliedModelState applied = appliedModelStates.get(playerId);
        if (applied != null && !applied.modelId().isBlank() && !isDefaultModelId(applied.modelId())) {
            return Optional.of(applied.modelId());
        }
        SavedModelState saved = savedModelStates.get(playerId);
        if (saved != null && !saved.modelId().isBlank() && !isDefaultModelId(saved.modelId())) {
            return Optional.of(saved.modelId());
        }
        return Optional.empty();
    }

    private GeneratedOpenYsmCacheKeys generatedOpenYsmCacheKeys() throws IOException {
        GeneratedOpenYsmCacheKeys cached = generatedOpenYsmCacheKeys;
        if (cached != null) {
            return cached;
        }
        synchronized (generatedOpenYsmCacheKeysLock) {
            cached = generatedOpenYsmCacheKeys;
            if (cached != null) {
                return cached;
            }

            Path cacheRoot = resolvePluginPath(GENERATED_CACHE_SESSION_ROOT);
            Files.createDirectories(cacheRoot);
            Path indexFile = cacheRoot.resolve(GENERATED_CACHE_OPENYSM_INDEX_FILE);
            Properties index = new Properties();
            if (Files.exists(indexFile)) {
                try (var input = Files.newInputStream(indexFile)) {
                    index.load(input);
                }
            }

            byte[] serverCacheKey = readGeneratedCacheKey(index, "serverCacheKey")
                    .orElseGet(YsmNativeSyncPrototype::randomKey);
            byte[] clientCacheKey = readGeneratedCacheKey(index, "clientCacheKey")
                    .orElseGet(PaperYsmPlugin::defaultGeneratedOpenYsmClientCacheKey);
            index.setProperty("serverCacheKey", Base64.getEncoder().encodeToString(serverCacheKey));
            index.setProperty("clientCacheKey", Base64.getEncoder().encodeToString(clientCacheKey));
            index.setProperty("cacheFolder", "0");
            index.setProperty("clientKeySeed", Long.toString(GENERATED_CACHE_OPENYSM_CLIENT_KEY_SEED));
            try (var output = Files.newOutputStream(indexFile)) {
                index.store(output, "PaperYSM OpenYSM generated cache keys");
            }

            cached = new GeneratedOpenYsmCacheKeys(serverCacheKey, clientCacheKey, indexFile);
            generatedOpenYsmCacheKeys = cached;
            getLogger().info("YSM generated OpenYSM cache keys loaded: file=" + indexFile.toAbsolutePath()
                    + ", serverCacheKey=" + YsmNativeSyncPrototype.keyPreview(serverCacheKey)
                    + ", clientCacheKey=" + YsmNativeSyncPrototype.keyPreview(clientCacheKey) + ".");
            return cached;
        }
    }

    private static Optional<byte[]> readGeneratedCacheKey(Properties index, String propertyName) {
        String value = index.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            byte[] key = Base64.getDecoder().decode(value.trim());
            if (key.length == YsmRawPacketCodec.KEY_BYTES) {
                return Optional.of(key);
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid local key material is regenerated below.
        }
        return Optional.empty();
    }

    private static byte[] defaultGeneratedOpenYsmClientCacheKey() {
        byte[] key = new byte[YsmRawPacketCodec.KEY_BYTES];
        new Random(GENERATED_CACHE_OPENYSM_CLIENT_KEY_SEED).nextBytes(key);
        return key;
    }

    private static String generatedCacheOpenYsmModelHash(
            YsmDistributionRepository.PreparedModel model,
            @Nullable String tokenSalt) {
        String modelHash = model.modelId() + "|" + model.modelHash();
        if (tokenSalt == null || tokenSalt.isBlank()) {
            return modelHash;
        }
        return modelHash + "|" + tokenSalt;
    }

    private static String generatedCacheOpenYsmPhysicalHash(YsmDistributionRepository.PreparedModel model) {
        return OpenYsmServerCacheConverter.SERVER_CACHE_FORMAT
                + "|"
                + model.serverCacheYsmZstdSha256();
    }

    private GeneratedCacheTokens generatedCacheTokens(
            YsmDistributionRepository.PreparedModel model,
            byte[] serverCacheKey) {
        long[] displayHashes = YsmCrypto.calculateModelHashes(
                generatedCacheOpenYsmModelHash(model, autoGeneratedCacheTokenSalt),
                serverCacheKey);
        long[] physicalHashes = YsmCrypto.calculateModelHashes(
                generatedCacheOpenYsmPhysicalHash(model),
                serverCacheKey);
        byte[] displayToken = nativeCacheToken(displayHashes[0], displayHashes[1]);
        byte[] physicalToken = nativeCacheToken(physicalHashes[0], physicalHashes[1]);
        return new GeneratedCacheTokens(
                displayHashes[0],
                displayHashes[1],
                displayToken,
                HEX.formatHex(displayToken),
                physicalHashes[0],
                physicalHashes[1],
                HEX.formatHex(physicalToken));
    }

    private Path persistGeneratedServerCache(
            YsmModelRepository.Entry sourceEntry,
            YsmDistributionRepository.PreparedModel model,
            String tokenHex,
            byte[] cacheBytes) throws IOException {
        return persistGeneratedServerCache(sourceEntry, model, tokenHex, tokenHex, 0L, 0L, 0L, cacheBytes);
    }

    private Path persistGeneratedServerCache(
            YsmModelRepository.Entry sourceEntry,
            YsmDistributionRepository.PreparedModel model,
            GeneratedCacheTokens tokens,
            byte[] cacheBytes) throws IOException {
        return persistGeneratedServerCache(
                sourceEntry,
                model,
                tokens.displayTokenHex(),
                tokens.physicalTokenHex(),
                YsmCrypto.cachedModelBodyVerificationHash(cacheBytes),
                tokens.displayHashA(),
                tokens.displayHashB(),
                cacheBytes);
    }

    private Path persistGeneratedServerCache(
            YsmModelRepository.Entry sourceEntry,
            YsmDistributionRepository.PreparedModel model,
            String displayTokenHex,
            String physicalTokenHex,
            long bodyHash,
            long displayHashA,
            long displayHashB,
            byte[] cacheBytes) throws IOException {
        Path root = resolvePluginPath(GENERATED_CACHE_SERVER_ROOT);
        Files.createDirectories(root);
        Path cacheFile = root.resolve(physicalTokenHex + ".bin");
        boolean writeFile = !Files.exists(cacheFile) || Files.size(cacheFile) != cacheBytes.length;
        if (writeFile) {
            Files.write(cacheFile, cacheBytes);
        }

        synchronized (generatedServerCacheIndexLock) {
            String sourcePath = sourceEntry.file().toAbsolutePath().normalize().toString().replace('\t', ' ');
            long sourceMtime = Files.getLastModifiedTime(sourceEntry.file()).toMillis();
            Map<String, GeneratedServerCacheIndexEntry> indexed = new LinkedHashMap<>(generatedServerCacheIndexByModelId());
            indexed.put(model.modelId(), new GeneratedServerCacheIndexEntry(
                    displayTokenHex,
                    model.modelId().replace('\t', ' '),
                    Path.of(sourcePath),
                    sourceEntry.size(),
                    sourceMtime,
                    cacheFile,
                    cacheBytes.length,
                    model.serverCacheYsmZstdBytes(),
                    model.modelHash(),
                    model.serverCacheYsmZstdSha256(),
                    GENERATED_CACHE_TOKEN_VERSION,
                    physicalTokenHex,
                    bodyHash,
                    displayHashA,
                    displayHashB));
            writeGeneratedServerCacheIndexes(root, indexed);
        }
        return cacheFile;
    }

    private GeneratedServerCacheStats generatedServerCacheStats() {
        Path root = resolvePluginPath(GENERATED_CACHE_SERVER_ROOT);
        Map<String, GeneratedServerCacheIndexEntry> indexed = generatedServerCacheIndexByModelId();

        int files = 0;
        long bytes = 0L;
        if (Files.isDirectory(root)) {
            try (var stream = Files.list(root)) {
                for (Path path : stream.filter(Files::isRegularFile).toList()) {
                    if (!path.getFileName().toString().endsWith(".bin")) {
                        continue;
                    }
                    files++;
                    bytes += Files.size(path);
                }
            } catch (IOException ex) {
                getLogger().warning("Failed to scan generated OpenYSM server-cache root "
                        + root.toAbsolutePath() + ": " + ex.getMessage());
            }
        }
        return new GeneratedServerCacheStats(root, indexed.size(), files, bytes);
    }

    private void compactGeneratedServerCacheIndex(CommandSender sender) {
        Path root = resolvePluginPath(GENERATED_CACHE_SERVER_ROOT);
        synchronized (generatedServerCacheIndexLock) {
            try {
                Map<String, GeneratedServerCacheIndexEntry> indexed = generatedServerCacheIndexByModelId();
                writeGeneratedServerCacheIndexes(root, indexed);
                GeneratedServerCacheStats stats = generatedServerCacheStats();
                sender.sendMessage(ChatColor.GREEN + "Generated OpenYSM cache index compacted: models="
                        + indexed.size()
                        + ", files=" + stats.files()
                        + ", bytes=" + formatBytes(stats.bytes())
                        + ", map=" + root.resolve("cache-map.tsv").toAbsolutePath() + ".");
            } catch (IOException ex) {
                sender.sendMessage(ChatColor.RED + "Failed to compact generated cache index: " + ex.getMessage());
                getLogger().warning("YSM generated OpenYSM cache index compact failed: root="
                        + root.toAbsolutePath()
                        + ", reason=" + ex.getMessage() + ".");
            }
        }
    }

    private Map<String, GeneratedServerCacheIndexEntry> generatedServerCacheIndexByModelId() {
        Path root = resolvePluginPath(GENERATED_CACHE_SERVER_ROOT);
        Path indexFile = root.resolve(GENERATED_CACHE_SERVER_INDEX_FILE);
        Map<String, GeneratedServerCacheIndexEntry> indexed = new LinkedHashMap<>();
        if (!Files.exists(indexFile)) {
            return indexed;
        }
        try {
            for (String line : Files.readAllLines(indexFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 10) {
                    continue;
                }
                String tokenHex = parts[0];
                long sourceBytes = parseLong(parts[2], -1L);
                long sourceMtime = parseLong(parts[3], -1L);
                long cacheBytes = parseLong(parts[4], 0L);
                long serverCacheBytes = parseLong(parts[5], 0L);
                String modelHash = parts[6];
                String sha256 = parts[7];
                String modelId = parts[8];
                Path sourcePath = Path.of(parts[9]);
                String tokenVersion = parts.length >= 11 ? parts[10] : "";
                String physicalTokenHex = parts.length >= 12 && !parts[11].isBlank() ? parts[11] : tokenHex;
                long bodyHash = parseLong(parts.length >= 13 ? parts[12] : "", 0L);
                long displayHashA = parseLong(parts.length >= 14 ? parts[13] : "", 0L);
                long displayHashB = parseLong(parts.length >= 15 ? parts[14] : "", 0L);
                Path cacheFile = root.resolve(physicalTokenHex + ".bin");
                indexed.put(modelId, new GeneratedServerCacheIndexEntry(
                        tokenHex,
                        modelId,
                        sourcePath,
                        sourceBytes,
                        sourceMtime,
                        cacheFile,
                        cacheBytes,
                        serverCacheBytes,
                        modelHash,
                        sha256,
                        tokenVersion,
                        physicalTokenHex,
                        bodyHash,
                        displayHashA,
                        displayHashB));
            }
        } catch (IOException ex) {
            getLogger().warning("Failed to read generated OpenYSM server-cache index "
                    + indexFile.toAbsolutePath() + ": " + ex.getMessage());
        }
        return indexed;
    }

    private static void writeGeneratedServerCacheIndexes(
            Path root,
            Map<String, GeneratedServerCacheIndexEntry> indexed) throws IOException {
        Files.createDirectories(root);
        StringBuilder rawIndex = new StringBuilder(indexed.size() * 192);
        StringBuilder friendlyIndex = new StringBuilder(indexed.size() * 192);
        friendlyIndex.append("modelId\tdisplayToken\tphysicalToken\tcacheFile\tcacheBytes\tserverCacheBytes\tsourceFile\n");
        for (GeneratedServerCacheIndexEntry entry : indexed.values().stream()
                .sorted(Comparator.comparing(GeneratedServerCacheIndexEntry::modelId))
                .toList()) {
            rawIndex.append(generatedServerCacheIndexLine(entry)).append(System.lineSeparator());
            friendlyIndex.append(entry.modelId().replace('\t', ' '))
                    .append('\t').append(entry.tokenHex())
                    .append('\t').append(entry.physicalTokenHex())
                    .append('\t').append(root.relativize(entry.cacheFile()).toString().replace('\\', '/'))
                    .append('\t').append(entry.cacheBytes())
                    .append('\t').append(entry.serverCacheBytes())
                    .append('\t').append(entry.sourcePath().toString().replace('\t', ' '))
                    .append(System.lineSeparator());
        }
        Files.writeString(
                root.resolve(GENERATED_CACHE_SERVER_INDEX_FILE),
                rawIndex.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        Files.writeString(
                root.resolve("cache-map.tsv"),
                friendlyIndex.toString(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static String generatedServerCacheIndexLine(GeneratedServerCacheIndexEntry entry) {
        return String.join("\t",
                entry.tokenHex(),
                Long.toString(System.currentTimeMillis()),
                Long.toString(entry.sourceBytes()),
                Long.toString(entry.sourceMtime()),
                Long.toString(entry.cacheBytes()),
                Long.toString(entry.serverCacheBytes()),
                entry.modelHash(),
                entry.sha256(),
                entry.modelId().replace('\t', ' '),
                entry.sourcePath().toString().replace('\t', ' '),
                entry.tokenVersion(),
                entry.physicalTokenHex(),
                Long.toString(entry.bodyHash()),
                Long.toString(entry.displayHashA()),
                Long.toString(entry.displayHashB()));
    }

    private YsmDistributionRepository.PreparedModel prepareEphemeralDistributionModel(YsmModelRepository.Entry entry) {
        try {
            return distributionRepository.prepareEphemeral(
                    resolvePluginPath(distributionCacheDir),
                    entry,
                    distributionChunkBytes,
                    false);
        } catch (IOException ex) {
            throw new IllegalStateException("prepare failed for " + entry.modelId() + ": " + ex.getMessage(), ex);
        }
    }

    private static byte[] createGeneratedCacheManifestBody(
            String layout,
            byte[] modelKey,
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            List<GeneratedNativeCacheModel> models) {
        if (GENERATED_CACHE_LAYOUT_OPENYSM.equals(layout)) {
            return createGeneratedCacheOpenYsmManifestBody(serverCacheKey, clientCacheKey, models);
        }
        if (GENERATED_CACHE_LAYOUT_KEYS.equals(layout)) {
            return createGeneratedCacheKeyManifestBody(serverCacheKey, clientCacheKey, models);
        }
        return createGeneratedCacheLegacyManifestBody(modelKey, models);
    }

    private static byte[] createGeneratedCacheOpenYsmManifestBody(
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            List<GeneratedNativeCacheModel> models) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(
                1 + YsmRawPacketCodec.KEY_BYTES * 2 + 8 + models.size() * 96);
        writeNativeVarInt(body, 0L);
        body.writeBytes(serverCacheKey);
        body.writeBytes(clientCacheKey);
        writeNativeVarInt(body, models.size());
        for (GeneratedNativeCacheModel model : models) {
            body.writeBytes(model.token());
            writeNativeString(body, model.name());
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, model.format());
        }
        writeNativeVarInt(body, 0);
        writeNativeVarInt(body, 0);
        return body.toByteArray();
    }

    private static byte[] createGeneratedCacheLegacyManifestBody(
            byte[] modelKey,
            List<GeneratedNativeCacheModel> models) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(128 + models.size() * 64);
        writeNativeVarInt(body, 0);
        body.writeBytes(Arrays.copyOf(
                YsmNativeSyncPrototype.deterministicKey("generated-cache-meta-a", "type3", "manifest"),
                0x1c));
        body.writeBytes(Arrays.copyOf(
                YsmNativeSyncPrototype.deterministicKey("generated-cache-meta-b", "type3", "manifest"),
                0x1c));
        body.writeBytes(modelKey);
        writeNativeVarInt(body, models.size());
        for (GeneratedNativeCacheModel model : models) {
            body.writeBytes(model.token());
            writeNativeString(body, model.name());
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, model.format());
        }
        writeNativeVarInt(body, 0);
        return body.toByteArray();
    }

    private static byte[] createGeneratedCacheKeyManifestBody(
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            List<GeneratedNativeCacheModel> models) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(
                YsmRawPacketCodec.KEY_BYTES * 2
                        + FREESIA_NATIVE_TYPE3_ENTRY_PRELUDE.length
                        + models.size() * 64
                        + 8);
        body.writeBytes(serverCacheKey);
        body.writeBytes(clientCacheKey);
        body.writeBytes(FREESIA_NATIVE_TYPE3_ENTRY_PRELUDE);
        for (GeneratedNativeCacheModel model : models) {
            body.writeBytes(model.token());
            writeNativeString(body, model.name());
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, 0);
            writeNativeVarInt(body, model.format());
        }
        writeNativeVarInt(body, 0);
        return body.toByteArray();
    }

    private static GeneratedCachePayload generatedCachePayload(
            YsmDistributionRepository.PreparedModel model,
            String payloadMode) throws IOException {
        return switch (payloadMode) {
            case GENERATED_CACHE_PAYLOAD_SERVER_CACHE -> new GeneratedCachePayload(
                    GENERATED_CACHE_PAYLOAD_SERVER_CACHE,
                    model.serverCacheYsmZstd(),
                    model.serverCacheYsmZstdSha256());
            case GENERATED_CACHE_PAYLOAD_WASHED_ZSTD -> new GeneratedCachePayload(
                    GENERATED_CACHE_PAYLOAD_WASHED_ZSTD,
                    model.transferPayload(),
                    model.transferSha256());
            case GENERATED_CACHE_PAYLOAD_HEADERLESS_V3 -> {
                byte[] payload = readHeaderlessV3Payload(model.sourceFile(), true);
                yield new GeneratedCachePayload(payloadMode, payload, sha256Hex(payload));
            }
            case GENERATED_CACHE_PAYLOAD_ENCRYPTED_V3 -> {
                byte[] payload = readHeaderlessV3Payload(model.sourceFile(), false);
                yield new GeneratedCachePayload(payloadMode, payload, sha256Hex(payload));
            }
            default -> throw new IllegalArgumentException("Unsupported generated cache payload: " + payloadMode);
        };
    }

    private static byte[] readHeaderlessV3Payload(Path sourceFile, boolean includeCryptoMarker) throws IOException {
        byte[] bytes = Files.readAllBytes(sourceFile);
        if (bytes.length < 7
                || (bytes[0] & 0xff) != 0xef
                || (bytes[1] & 0xff) != 0xbb
                || (bytes[2] & 0xff) != 0xbf
                || readLittleEndianInt(bytes, 3) != 0x50475359) {
            throw new IllegalArgumentException("not a V3 YSM archive");
        }
        int headerEnd = findNull(bytes, 0);
        if (headerEnd < 0) {
            throw new IllegalArgumentException("V3 header terminator not found");
        }
        int payloadOffset = headerEnd + 1 + (includeCryptoMarker ? 0 : Integer.BYTES);
        if (payloadOffset >= bytes.length) {
            throw new IllegalArgumentException("V3 payload is empty");
        }
        return Arrays.copyOfRange(bytes, payloadOffset, bytes.length);
    }

    private static int findNull(byte[] bytes, int offset) {
        for (int i = offset; i < bytes.length; i++) {
            if (bytes[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static long generatedCacheHash(
            YsmDistributionRepository.PreparedModel model,
            String payloadSha256,
            @Nullable String tokenSalt,
            int offset) {
        byte[] key = YsmNativeSyncPrototype.deterministicKey(
                "generated-cache-token",
                model.modelId(),
                payloadSha256 + "|" + (tokenSalt == null ? "" : tokenSalt));
        return readLittleEndianLong(key, offset);
    }

    private static byte[] nativeCacheToken(long hashA, long hashB) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(20);
        writeNativeVarInt(out, hashA);
        writeNativeVarInt(out, hashB);
        return out.toByteArray();
    }

    private static String nativeCacheModelName(String modelId) {
        String normalized = modelId.replace('\\', '/');
        return normalized.toLowerCase(Locale.ROOT).endsWith(".ysm")
                ? normalized
                : normalized + ".ysm";
    }

    private void inspectNativeCacheReplayPacket(Player player, byte[] rawPacketBody) {
        UUID playerId = player.getUniqueId();
        NativeCacheReplaySession state = nativeCacheReplaySessions.get(playerId);
        if (state == null) {
            return;
        }

        if (state.c2sKey() == null) {
            try {
                YsmRawPacketCodec.PlainPacket packet =
                        YsmRawPacketCodec.decryptBodyOnly(rawPacketBody, state.s2cKey());
                NativeCacheReplaySession updated = state.withClientPacket(packet);
                if (packet.type() != 2 || packet.selectedKey().isEmpty()) {
                    nativeCacheReplaySessions.put(playerId, updated);
                    getLogger().warning("YSM native cache replay unexpected first client packet: player="
                            + player.getName()
                            + ", type=" + packet.type()
                            + ", summary=" + packet.summary()
                            + ", bodyBytes=" + packet.body().length + ".");
                    return;
                }

                byte[] c2sKey = packet.selectedKey().get();
                updated = updated.withC2sKey(c2sKey, Instant.now());
                nativeCacheReplaySessions.put(playerId, updated);
                if (debug) {
                    getLogger().info("YSM native cache replay client type2 decoded: player=" + player.getName()
                            + ", rawBytes=" + rawPacketBody.length
                            + ", bodyBytes=" + packet.body().length
                            + ", c2sKey=" + YsmNativeSyncPrototype.keyPreview(c2sKey)
                            + ", summary=" + packet.summary() + ".");
                }
                sendNativeCacheManifest(player, updated);
            } catch (RuntimeException ex) {
                getLogger().warning("YSM native cache replay type2 decode failed: player=" + player.getName()
                        + ", rawBytes=" + rawPacketBody.length
                        + ", key=s2c"
                        + ", reason=" + ex.getMessage() + ".");
            }
            return;
        }

        try {
            YsmRawPacketCodec.PlainPacket packet =
                    YsmRawPacketCodec.decryptBodyOnly(rawPacketBody, state.s2cKey());
            NativeCacheReplaySession updated = state.withClientPacket(packet);
            nativeCacheReplaySessions.put(playerId, updated);
            if (debug) {
                getLogger().info("YSM native cache replay client packet decoded: player=" + player.getName()
                        + ", type=" + packet.type()
                        + ", summary=" + packet.summary()
                        + ", rawBytes=" + rawPacketBody.length
                        + ", bodyBytes=" + packet.body().length
                        + ", preview=" + YsmProtocol.toHex(packet.body(), rawPacketHexPreviewBytes)
                        + ".");
            }
            if (packet.type() == 4) {
                NativeCacheRequest request = parseNativeCacheRequest(
                        packet.body(),
                        updated.cacheEntries(),
                        updated.fallbackServerCacheBytes());
                NativeCacheReplaySession ready = updated.withType4Decoded(request.count(), request.tokenBytes());
                nativeCacheReplaySessions.put(playerId, ready);
                if (request.entries().isEmpty()) {
                    if (debug) {
                        getLogger().info("YSM native cache replay client requested no cache entries: player="
                                + player.getName()
                                + ", count=" + request.count()
                                + ", tokenBytes=" + request.tokenBytes()
                                + ", treating as cache-already-present.");
                    }
                    if (scheduleNextGeneratedCacheBatch(
                            playerId,
                            player.getName(),
                            MODEL_STATE_REPLAY_DELAY_TICKS,
                            "cache-already-present")) {
                        return;
                    }
                    scheduleSavedModelRestore(player, MODEL_STATE_REPLAY_DELAY_TICKS, "native-cache-already-present");
                    scheduleModelStateReplay(player, MODEL_STATE_REPLAY_DELAY_TICKS, "native-cache-already-present");
                    scheduleAppliedModelStateBroadcast(player, MODEL_STATE_REPLAY_DELAY_TICKS, "native-cache-already-present");
                    scheduleNativeCacheReplaySessionCleanup(
                            playerId,
                            MODEL_STATE_REPLAY_DELAY_TICKS + 2L,
                            "native-cache-already-present");
                    return;
                }
                if (debug) {
                    getLogger().info("YSM native cache replay client requested cache entries: player="
                            + player.getName()
                            + ", count=" + request.count()
                            + ", tokenBytes=" + request.tokenBytes()
                            + ", entries=" + describeNativeCacheRequestedEntries(request.entries())
                            + ".");
                }
                sendNativeCacheChunks(player, ready, request.entries());
            }
        } catch (RuntimeException ex) {
            getLogger().warning("YSM native cache replay client packet decode failed: player=" + player.getName()
                    + ", rawBytes=" + rawPacketBody.length
                    + ", key=s2c"
                    + ", reason=" + ex.getMessage() + ".");
        }
    }

    private void sendNativeCacheManifest(Player player, NativeCacheReplaySession state) {
        if (state.c2sKey() == null) {
            return;
        }
        byte[] type3Plain = YsmRawPacketCodec.encodePlain(
                3,
                state.type3Body(),
                reportNativePadding(state.type3PaddingBytes()));
        byte[] type3Raw = YsmRawPacketCodec.encryptBodyOnly(type3Plain, state.c2sKey());
        NativeCacheReplaySession sentState = state.withType3Sent(type3Raw.length, Instant.now());
        nativeCacheReplaySessions.put(player.getUniqueId(), sentState);
        sendServerRawPacket(player, type3Raw, "native-cache:" + state.captureName() + ":type3-manifest");
        if (debug) {
            getLogger().info("YSM native cache replay type3 sent: player=" + player.getName()
                    + ", source=" + state.sourceDir().toAbsolutePath()
                    + ", bodyBytes=" + state.type3Body().length
                    + ", plainBytes=" + type3Plain.length
                    + ", rawBytes=" + type3Raw.length
                    + ", type3PaddingBytes=" + state.type3PaddingBytes()
                    + ", c2sKey=" + YsmNativeSyncPrototype.keyPreview(state.c2sKey())
                    + ".");
        }
        scheduleNativeCacheType4Watchdog(player.getUniqueId(), player.getName(), sentState);
    }

    private void scheduleNativeCacheType4Watchdog(
            UUID playerId,
            String playerName,
            NativeCacheReplaySession sentState) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            NativeCacheReplaySession latest = nativeCacheReplaySessions.get(playerId);
            if (latest == null
                    || !latest.captureName().equals(sentState.captureName())
                    || !latest.startedAt().equals(sentState.startedAt())
                    || latest.type4DecodedAt() != null) {
                return;
            }
            getLogger().warning("YSM native cache replay type3-no-type4: player=" + playerName
                    + ", source=" + latest.sourceDir().toAbsolutePath()
                    + ", capture=" + latest.captureName()
                    + ", type3BodyBytes=" + latest.type3Body().length
                    + ", type3RawBytes=" + latest.type3RawBytes()
                    + ", cacheEntries=" + latest.cacheEntries().size()
                    + ", cacheBytes=" + latest.cacheBytes()
                    + ". Client did not request cache entries after type3; manifest layout is likely rejected.");
        }, 20L * 5L);
    }

    private void sendNativeCacheChunks(
            Player player,
            NativeCacheReplaySession state,
            List<NativeCacheRequestedEntry> requestedEntries) {
        if (state.type5Packets() > 0) {
            getLogger().info("YSM native cache replay type5 already scheduled: player=" + player.getName()
                    + ", packets=" + state.type5Packets() + ".");
            return;
        }
        int chunkBytes = Math.max(1, state.chunkBytes());
        int packetCount = 0;
        long totalCacheBytes = 0;
        for (NativeCacheRequestedEntry entry : requestedEntries) {
            int entryPacketCount = nativeCacheChunkCount(entry.cacheBytes(), chunkBytes);
            packetCount = Math.addExact(packetCount, entryPacketCount);
            totalCacheBytes += entry.cacheBytes();
        }
        NativeCacheReplaySession scheduled = state.withType5Scheduled(packetCount, totalCacheBytes);
        nativeCacheReplaySessions.put(player.getUniqueId(), scheduled);

        UUID playerId = player.getUniqueId();
        int packetsPerTick = Math.max(1, state.packetsPerTick());
        int intervalTicks = Math.max(1, state.intervalTicks());
        int sequence = 0;
        for (NativeCacheRequestedEntry entry : requestedEntries) {
            long entryBytes = entry.cacheBytes();
            int entryPacketCount = nativeCacheChunkCount(entryBytes, chunkBytes);
            for (int i = 0; i < entryPacketCount; i++) {
                int index = sequence++;
                int entryIndex = i;
                long offset = (long) i * chunkBytes;
                int readBytes = (int) Math.min((long) chunkBytes, entryBytes - offset);
                byte[] token = entry.token();
                long totalBytes = entryBytes;
                String entryName = entry.name();
                Bukkit.getScheduler().runTaskLater(this, () -> {
                    Player current = Bukkit.getPlayer(playerId);
                    if (current == null || !current.isOnline()) {
                        return;
                    }
                    NativeCacheReplaySession latest = nativeCacheReplaySessions.get(playerId);
                    if (latest == null) {
                        return;
                    }
                    byte[] chunk;
                    try {
                        chunk = readNativeCacheChunk(entry, offset, readBytes);
                    } catch (IOException ex) {
                        getLogger().warning("YSM native cache replay type5 chunk read failed: player="
                                + current.getName()
                                + ", source=" + latest.sourceDir().toAbsolutePath()
                                + ", entry=" + entryName
                                + ", offset=" + offset
                                + ", bytes=" + readBytes
                                + ", reason=" + ex.getMessage() + ".");
                        return;
                    }
                    byte[] type5Plain = YsmRawPacketCodec.encodePlainType5(
                            token,
                            totalBytes,
                            offset,
                            chunk,
                            new byte[0]);
                    byte[] type5Raw = YsmRawPacketCodec.encryptBodyOnly(type5Plain, latest.s2cKey());
                    sendServerRawPacket(current, type5Raw, "native-cache:" + latest.captureName()
                            + ":type5-" + (index + 1) + "/" + latest.type5Packets()
                            + ":" + entryName + ":" + (entryIndex + 1) + "/" + entryPacketCount);
                }, ((long) index / packetsPerTick) * intervalTicks);
            }
        }
        if (debug) {
            getLogger().info("YSM native cache replay type5 scheduled: player=" + player.getName()
                    + ", packets=" + packetCount
                    + ", requestedEntries=" + requestedEntries.size()
                    + ", cacheBytes=" + totalCacheBytes
                    + ", chunkBytes=" + chunkBytes
                    + ", intervalTicks=" + state.intervalTicks()
                    + ", packetsPerTick=" + packetsPerTick
                    + ", firstToken=" + YsmProtocol.toHex(requestedEntries.get(0).token(), rawPacketHexPreviewBytes)
                    + ".");
        }
        long sendTicks = ((long) packetCount + packetsPerTick - 1L) / packetsPerTick * intervalTicks;
        long replayDelayTicks = sendTicks + MODEL_STATE_LATE_REPLAY_DELAY_TICKS;
        if (scheduleNextGeneratedCacheBatch(player.getUniqueId(), player.getName(), replayDelayTicks, "type5-complete")) {
            return;
        }
        scheduleSavedModelRestore(player, replayDelayTicks, "native-cache");
        scheduleModelStateReplay(player, replayDelayTicks, "native-cache");
        scheduleAppliedModelStateBroadcast(player, replayDelayTicks, "native-cache");
        scheduleNativeCacheReplaySessionCleanup(
                player.getUniqueId(),
                replayDelayTicks + 2L,
                "native-cache-complete");
    }

    private void scheduleNativeCacheReplaySessionCleanup(UUID playerId, long delayTicks, String reason) {
        NativeCacheReplaySession expected = nativeCacheReplaySessions.get(playerId);
        Instant expectedStartedAt = expected == null ? null : expected.startedAt();
        String expectedCaptureName = expected == null ? "" : expected.captureName();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            NativeCacheReplaySession latest = nativeCacheReplaySessions.get(playerId);
            if (latest == null
                    || expectedStartedAt == null
                    || !latest.startedAt().equals(expectedStartedAt)
                    || !latest.captureName().equals(expectedCaptureName)) {
                return;
            }
            NativeCacheReplaySession removed = nativeCacheReplaySessions.remove(playerId);
            if (removed == null) {
                return;
            }
            cleanupGeneratedCacheSessionDirectory(removed.sourceDir(), reason);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && appliedModelStates.containsKey(playerId)) {
                scheduleAppliedModelStateBroadcast(player, 1L, reason + "-session-finished");
            }
            getLogger().info("YSM native cache replay session finished: player="
                    + (player == null ? playerId : player.getName())
                    + ", capture=" + removed.captureName()
                    + ", requestedTokens=" + removed.requestedTokens()
                    + ", type5Packets=" + removed.type5Packets()
                    + ", reason=" + reason + ".");
        }, Math.max(1L, delayTicks));
    }

    private boolean scheduleNextGeneratedCacheBatch(
            UUID playerId,
            String playerName,
            long delayTicks,
            String reason) {
        if (!generatedCacheBatchQueues.containsKey(playerId)) {
            return false;
        }
        long safeDelayTicks = Math.max(1L, delayTicks);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!generatedCacheBatchQueues.containsKey(playerId)) {
                return;
            }
            Player current = Bukkit.getPlayer(playerId);
            if (current == null || !current.isOnline()) {
                generatedCacheBatchQueues.remove(playerId);
                NativeCacheReplaySession removed = nativeCacheReplaySessions.remove(playerId);
                if (removed != null) {
                    cleanupGeneratedCacheSessionDirectory(removed.sourceDir(), "generated-next-batch-player-offline");
                }
                return;
            }
            startNextGeneratedYsmCacheBatch(null, playerId, current.getName());
        }, safeDelayTicks);
        if (debug) {
            getLogger().info("YSM generated cache next batch scheduled: player=" + playerName
                    + ", reason=" + reason
                    + ", delayTicks=" + safeDelayTicks + ".");
        }
        return true;
    }

    private static int nativeCacheChunkCount(long cacheBytes, int chunkBytes) {
        if (cacheBytes <= 0) {
            return 0;
        }
        return Math.toIntExact((cacheBytes + chunkBytes - 1L) / chunkBytes);
    }

    private static byte[] readNativeCacheChunk(
            NativeCacheRequestedEntry entry,
            long offset,
            int length) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }
        byte[] inlineCacheBytes = entry.inlineCacheBytes();
        if (inlineCacheBytes != null) {
            int start = Math.toIntExact(offset);
            int end = Math.addExact(start, length);
            return Arrays.copyOfRange(inlineCacheBytes, start, end);
        }

        Path cacheFile = entry.cacheFile();
        if (cacheFile == null) {
            throw new IOException("No native cache file for " + entry.name());
        }
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (SeekableByteChannel channel = Files.newByteChannel(cacheFile, StandardOpenOption.READ)) {
            channel.position(offset);
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer);
                if (read < 0) {
                    break;
                }
            }
        }
        if (buffer.position() != length) {
            throw new IOException("Short native cache read for " + cacheFile
                    + ": expected=" + length
                    + ", actual=" + buffer.position());
        }
        byte[] chunk = buffer.array();
        Long bodyHash = entry.bodyHash();
        if (bodyHash != null) {
            patchNativeCacheAliasFooter(chunk, offset, entry.cacheBytes(), bodyHash, entry.hashA(), entry.hashB());
        }
        return chunk;
    }

    private static void patchNativeCacheAliasFooter(
            byte[] chunk,
            long offset,
            long cacheBytes,
            long bodyHash,
            long hashA,
            long hashB) {
        long footerOffset = cacheBytes - Long.BYTES;
        long chunkEnd = offset + chunk.length;
        if (chunk.length == 0 || chunkEnd <= footerOffset || offset >= cacheBytes) {
            return;
        }

        byte[] footer = new byte[Long.BYTES];
        writeLittleEndianLong(footer, 0, bodyHash ^ hashA ^ hashB);
        int footerStartInChunk = Math.toIntExact(Math.max(0L, footerOffset - offset));
        int footerStartInFooter = Math.toIntExact(Math.max(0L, offset - footerOffset));
        int bytes = Math.min(Long.BYTES - footerStartInFooter, chunk.length - footerStartInChunk);
        if (bytes > 0) {
            System.arraycopy(footer, footerStartInFooter, chunk, footerStartInChunk, bytes);
        }
    }

    private static void writeGeneratedCacheSessionDebugFiles(
            Path sessionDir,
            String requestedModelId,
            String manifestLayout,
            String payloadMode,
            int modelCount,
            byte[] type3Body,
            Map<String, NativeCacheEntry> cacheEntries,
            byte[] serverCacheKey,
            byte[] clientCacheKey) throws IOException {
        Files.write(sessionDir.resolve("type3-body.bin"), type3Body);
        StringBuilder manifest = new StringBuilder();
        manifest.append("request=").append(requestedModelId).append('\n');
        manifest.append("layout=").append(manifestLayout).append('\n');
        manifest.append("payload=").append(payloadMode).append('\n');
        manifest.append("models=").append(modelCount).append('\n');
        manifest.append("type3BodyBytes=").append(type3Body.length).append('\n');
        manifest.append("serverCacheKey=").append(HEX.formatHex(serverCacheKey)).append('\n');
        manifest.append("clientCacheKey=").append(HEX.formatHex(clientCacheKey)).append('\n');
        manifest.append("entries=").append(cacheEntries.size()).append('\n');
        for (NativeCacheEntry entry : cacheEntries.values()) {
            manifest.append(entry.tokenHex())
                    .append('\t')
                    .append(entry.name())
                    .append('\t')
                    .append(entry.cacheBytes())
                    .append('\t')
                    .append(entry.cacheFile() == null ? "" : entry.cacheFile().getFileName())
                    .append('\n');
        }
        Files.writeString(sessionDir.resolve("manifest.tsv"), manifest.toString(), StandardCharsets.UTF_8);
    }

    private Path generatedCacheSessionDir(UUID playerId, String playerName) {
        String directoryName = sanitizeFileName(playerName)
                + "-"
                + playerId
                + "-"
                + Long.toUnsignedString(System.nanoTime(), 16);
        return resolvePluginPath(GENERATED_CACHE_SESSION_ROOT).resolve(directoryName).normalize();
    }

    private boolean isGeneratedCacheSessionDir(Path path) {
        Path root = resolvePluginPath(GENERATED_CACHE_SESSION_ROOT).toAbsolutePath().normalize();
        Path target = path.toAbsolutePath().normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return false;
        }
        Path fileName = target.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString();
        return !"server-cache".equalsIgnoreCase(name)
                && !"distribution".equalsIgnoreCase(name)
                && !"index.properties".equalsIgnoreCase(name);
    }

    private void cleanupGeneratedCacheSessionDirectory(Path sessionDir, String reason) {
        if (!isGeneratedCacheSessionDir(sessionDir)) {
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(this, () ->
                cleanupGeneratedCacheSessionDirectoryNow(sessionDir, reason));
    }

    private void cleanupGeneratedCacheSessionDirectoryNow(Path sessionDir, String reason) {
        if (!isGeneratedCacheSessionDir(sessionDir) || !Files.exists(sessionDir)) {
            return;
        }
        try (var paths = Files.walk(sessionDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    getLogger().fine("Failed to delete generated YSM cache session path "
                            + path.toAbsolutePath()
                            + ": " + ex.getMessage());
                }
            });
            getLogger().info("YSM generated cache session cleaned: dir="
                    + sessionDir.toAbsolutePath()
                    + ", reason=" + reason + ".");
        } catch (IOException ex) {
            getLogger().warning("Failed to clean generated YSM cache session "
                    + sessionDir.toAbsolutePath()
                    + ": " + ex.getMessage());
        }
    }

    private Path resolveNativeCacheReplayDir(String captureName) {
        Path path = Path.of(captureName);
        if (path.isAbsolute()) {
            return path;
        }
        Path cacheChannel = getDataFolder().toPath().resolve(NATIVE_CACHE_REPLAY_ROOT).resolve(captureName).normalize();
        if (Files.exists(cacheChannel)) {
            return cacheChannel;
        }
        Path legacyCapture = getDataFolder().toPath()
                .resolve(LEGACY_NATIVE_CACHE_REPLAY_ROOT)
                .resolve(captureName)
                .normalize();
        if (Files.exists(legacyCapture)) {
            return legacyCapture;
        }
        return cacheChannel;
    }

    private static Map<String, NativeCacheEntry> loadNativeCacheEntries(Path sourceDir) throws IOException {
        Path mapFile = sourceDir.resolve("cache-map.tsv");
        if (!Files.exists(mapFile)) {
            return Map.of();
        }

        Path sourceRoot = sourceDir.toAbsolutePath().normalize();
        LinkedHashMap<String, NativeCacheEntry> entries = new LinkedHashMap<>();
        List<String> lines = Files.readAllLines(mapFile, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("tokenHex\t")) {
                continue;
            }
            String[] parts = line.split("\t", 4);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Bad cache-map.tsv line " + (i + 1));
            }
            String tokenHex = normalizeNativeCacheTokenHex(parts[0]);
            byte[] token = HEX.parseHex(tokenHex);
            Path cacheFile = sourceDir.resolve(parts[1]).toAbsolutePath().normalize();
            if (!cacheFile.startsWith(sourceRoot)) {
                throw new IllegalArgumentException("cache-map.tsv path escapes source dir at line " + (i + 1));
            }
            long cacheBytes = Files.size(cacheFile);
            if (cacheBytes == 0) {
                throw new IllegalArgumentException("Empty cache file at line " + (i + 1));
            }
            String name = parts.length >= 3 && !parts[2].isBlank() ? parts[2] : tokenHex;
            if (entries.put(tokenHex, NativeCacheEntry.fromFile(tokenHex, token, name, cacheFile, cacheBytes)) != null) {
                throw new IllegalArgumentException("Duplicate cache token at line " + (i + 1));
            }
        }
        return Map.copyOf(entries);
    }

    private static String normalizeNativeCacheTokenHex(String value) {
        String normalized = value.replace(" ", "").replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
        if (normalized.isEmpty() || (normalized.length() & 1) != 0) {
            throw new IllegalArgumentException("Invalid cache token hex: " + value);
        }
        return normalized;
    }

    private static long nativeCacheBytes(
            Map<String, NativeCacheEntry> cacheEntries,
            @Nullable byte[] fallbackServerCacheBytes) {
        if (!cacheEntries.isEmpty()) {
            long total = 0;
            for (NativeCacheEntry entry : cacheEntries.values()) {
                total += entry.cacheBytes();
            }
            return total;
        }
        return fallbackServerCacheBytes == null ? 0 : fallbackServerCacheBytes.length;
    }

    private static int readOptionalPadding(Path path, int fallback) throws IOException {
        if (!Files.exists(path)) {
            return fallback;
        }
        String text = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return YsmNativeSyncPrototype.normalizePaddingBytes(Integer.parseInt(text));
    }

    private static NativeCacheRequest parseNativeCacheRequest(
            byte[] body,
            Map<String, NativeCacheEntry> cacheEntries,
            @Nullable byte[] fallbackServerCacheBytes) {
        int[] offset = {0};
        int count = readVarIntAsInt(body, offset);
        int tokenStart = offset[0];
        ArrayList<NativeCacheRequestedEntry> requested = new ArrayList<>();
        if (count == 0) {
            return new NativeCacheRequest(0, List.of(), 0);
        }
        if (cacheEntries.isEmpty()) {
            if (count != 1 || fallbackServerCacheBytes == null || offset[0] >= body.length) {
                throw new IllegalArgumentException("Native cache request needs cache-map.tsv for " + count + " token(s)");
            }
            byte[] token = Arrays.copyOfRange(body, offset[0], body.length);
            requested.add(NativeCacheRequestedEntry.fromBytes(token, "server-cache.bin", fallbackServerCacheBytes));
            return new NativeCacheRequest(count, List.copyOf(requested), token.length);
        }

        for (int i = 0; i < count; i++) {
            NativeCacheEntry entry = matchNativeCacheEntry(body, offset[0], cacheEntries.values());
            if (entry == null) {
                throw new IllegalArgumentException("Unknown native cache token at request index " + i
                        + ", offset=" + offset[0]
                        + ", remaining=" + (body.length - offset[0]));
            }
            requested.add(entry.asRequestedEntry());
            offset[0] += entry.token().length;
        }
        if (offset[0] != body.length) {
            throw new IllegalArgumentException("Native cache request has trailing bytes: " + (body.length - offset[0]));
        }
        return new NativeCacheRequest(count, List.copyOf(requested), body.length - tokenStart);
    }

    private static String describeNativeCacheRequestedEntries(List<NativeCacheRequestedEntry> entries) {
        int limit = Math.min(entries.size(), 4);
        ArrayList<String> names = new ArrayList<>(limit + 1);
        for (int i = 0; i < limit; i++) {
            NativeCacheRequestedEntry entry = entries.get(i);
            names.add(entry.name() + "/" + formatBytes(entry.cacheBytes()));
        }
        if (entries.size() > limit) {
            names.add("+" + (entries.size() - limit) + " more");
        }
        return String.join(", ", names);
    }

    private static @Nullable NativeCacheEntry matchNativeCacheEntry(
            byte[] body,
            int offset,
            Iterable<NativeCacheEntry> entries) {
        NativeCacheEntry best = null;
        for (NativeCacheEntry entry : entries) {
            byte[] token = entry.token();
            if (offset + token.length > body.length) {
                continue;
            }
            boolean matches = true;
            for (int i = 0; i < token.length; i++) {
                if (body[offset + i] != token[i]) {
                    matches = false;
                    break;
                }
            }
            if (matches && (best == null || token.length > best.token().length)) {
                best = entry;
            }
        }
        return best;
    }

    private static int readVarIntAsInt(byte[] data, int[] offsetRef) {
        long value = 0;
        int shift = 0;
        while (shift < 64) {
            if (offsetRef[0] >= data.length) {
                throw new IllegalArgumentException("Unexpected end of VarInt");
            }
            int b = data[offsetRef[0]++] & 0xff;
            value |= (long) (b & 0x7f) << shift;
            if ((b & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("VarInt exceeds int range: " + value);
                }
                return (int) value;
            }
            shift += 7;
        }
        throw new IllegalArgumentException("VarInt too large");
    }

    private static void writeNativeVarInt(ByteArrayOutputStream out, long value) {
        long remaining = value;
        while ((remaining & ~0x7fL) != 0) {
            out.write((int) (remaining & 0x7fL) | 0x80);
            remaining >>>= 7;
        }
        out.write((int) remaining);
    }

    private static void writeNativeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeNativeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    private static long readLittleEndianLong(byte[] data, int offset) {
        if (offset < 0 || offset + Long.BYTES > data.length) {
            throw new IllegalArgumentException("Little-endian long offset is out of range");
        }
        return ((long) data[offset] & 0xff)
                | (((long) data[offset + 1] & 0xff) << 8)
                | (((long) data[offset + 2] & 0xff) << 16)
                | (((long) data[offset + 3] & 0xff) << 24)
                | (((long) data[offset + 4] & 0xff) << 32)
                | (((long) data[offset + 5] & 0xff) << 40)
                | (((long) data[offset + 6] & 0xff) << 48)
                | (((long) data[offset + 7] & 0xff) << 56);
    }

    private static void writeLittleEndianLong(byte[] data, int offset, long value) {
        if (offset < 0 || offset + Long.BYTES > data.length) {
            throw new IllegalArgumentException("Little-endian long offset is out of range");
        }
        for (int i = 0; i < Long.BYTES; i++) {
            data[offset + i] = (byte) (value >>> (i * 8));
        }
    }

    private static int readLittleEndianInt(byte[] data, int offset) {
        if (offset < 0 || offset + Integer.BYTES > data.length) {
            throw new IllegalArgumentException("Little-endian int offset is out of range");
        }
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | ((data[offset + 3] & 0xff) << 24);
    }

    private boolean startNativeBootstrap(
            @Nullable CommandSender sender,
            Player player,
            String requestedMode,
            String requestedVariant,
            int paddingBytes,
            String trigger) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.compatible()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + player.getName() + " has not completed the YSM 51/52 handshake yet.");
            }
            return false;
        }

        if (distributionRepository.prepared().isEmpty() && sender != null) {
            sender.sendMessage(ChatColor.YELLOW
                    + "No prepared distribution packages exist yet; bootstrap will only test the native raw key path.");
        }

        YsmNativeSyncPrototype.BootstrapPacket packet;
        try {
            packet = YsmNativeSyncPrototype.createBootstrap(requestedMode, requestedVariant, paddingBytes);
        } catch (IllegalArgumentException ex) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + ex.getMessage());
            }
            getLogger().warning("YSM native bootstrap rejected: player=" + player.getName()
                    + ", mode=" + requestedMode
                    + ", variant=" + requestedVariant
                    + ", reason=" + ex.getMessage() + ".");
            return false;
        }

        return sendNativeBootstrapPacket(sender, player, packet, trigger);
    }

    private boolean sendNativeBootstrapPacket(
            @Nullable CommandSender sender,
            Player player,
            YsmNativeSyncPrototype.BootstrapPacket packet,
            String trigger) {
        NativeSyncState state = NativeSyncState.started(packet, trigger);
        nativeSyncStates.put(player.getUniqueId(), state);
        rememberNativeDecodeKeys(player.getUniqueId(), packet);
        sendServerRawPacket(player, packet.rawBody(),
                "native-bootstrap:" + packet.mode() + ":" + packet.variant() + ":" + trigger);
        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Sent native bootstrap id=1 to " + player.getName()
                    + " mode=" + packet.mode()
                    + " variant=" + packet.variant()
                    + " padding=" + packet.paddingBytes()
                    + " bytes=" + packet.rawBody().length
                    + ". Watch for a C2S id=2 capture/decode line.");
        }
        getLogger().info("YSM native bootstrap sent: player=" + player.getName()
                + ", trigger=" + trigger
                + ", mode=" + packet.mode()
                + ", variant=" + packet.variant()
                + ", paddingBytes=" + packet.paddingBytes()
                + ", rawBytes=" + packet.rawBody().length
                + ", bootstrapKey=" + YsmNativeSyncPrototype.keyPreview(packet.bootstrapKey())
                + ", advertisedKey=" + YsmNativeSyncPrototype.keyPreview(packet.advertisedKey())
                + ", nextTransportKey=" + YsmNativeSyncPrototype.keyPreview(packet.nextTransportKey())
                + ".");
        return true;
    }

    private void startNativeProbe(
            CommandSender sender,
            Player player,
            String profile,
            int intervalTicks,
            @Nullable Integer forcedPaddingBytes) {
        List<YsmNativeSyncPrototype.ProbeSpec> specs;
        if ("full".equals(profile)) {
            specs = YsmNativeSyncPrototype.fullProbeSpecs();
        } else if ("quick".equals(profile)) {
            specs = YsmNativeSyncPrototype.quickProbeSpecs();
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown probe profile: " + profile + " (expected quick|full)");
            return;
        }

        UUID targetId = player.getUniqueId();
        String targetName = player.getName();
        sender.sendMessage(ChatColor.GREEN + "Starting native bootstrap probe for " + targetName
                + ": profile=" + profile
                + ", packets=" + specs.size()
                + ", intervalTicks=" + intervalTicks
                + ", forcedPadding=" + (forcedPaddingBytes == null ? "mixed" : forcedPaddingBytes)
                + ".");
        getLogger().info("YSM native bootstrap probe started: player=" + targetName
                + ", profile=" + profile
                + ", packets=" + specs.size()
                + ", intervalTicks=" + intervalTicks
                + ", forcedPadding=" + (forcedPaddingBytes == null ? "mixed" : forcedPaddingBytes)
                + ".");

        for (int i = 0; i < specs.size(); i++) {
            int index = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player current = Bukkit.getPlayer(targetId);
                if (current == null || !current.isOnline()) {
                    return;
                }
                YsmNativeSyncPrototype.ProbeSpec spec = specs.get(index);
                if (forcedPaddingBytes != null) {
                    spec = spec.withPadding(forcedPaddingBytes);
                }
                YsmNativeSyncPrototype.BootstrapPacket packet =
                        YsmNativeSyncPrototype.createDeterministicProbe(spec.mode(), spec.variant(), spec.paddingBytes());
                sendNativeBootstrapPacket(null, current, packet, "probe:" + profile + ":" + (index + 1) + "/" + specs.size());
            }, (long) index * intervalTicks);
        }
    }

    private void startNativeManifestStream(
            @Nullable CommandSender sender,
            Player player,
            String requestedMode,
            String requestedChainMode) {
        startNativeManifestStream(
                sender,
                player,
                requestedMode,
                requestedChainMode,
                "command",
                experimentalProbeIntervalTicks);
    }

    private void startNativeManifestStreamProbe(CommandSender sender, Player player, int intervalTicks) {
        if (distributionRepository.prepared().isEmpty()) {
            sender.sendMessage(ChatColor.RED + "No prepared distribution packages exist yet.");
            return;
        }

        List<StreamProbeSpec> specs = new ArrayList<>();
        for (String mode : YsmNativeSyncPrototype.MODES) {
            for (String chainMode : List.of("next", "selected", "initial")) {
                specs.add(new StreamProbeSpec(mode, chainMode));
            }
        }

        UUID targetId = player.getUniqueId();
        String targetName = player.getName();
        YsmClientSession initialSession = sessions.get(targetId);
        int initialClientRawPackets = initialSession == null ? 0 : initialSession.clientRawPacketsReceived();
        int packetGapTicks = Math.max(1, Math.min(experimentalProbeIntervalTicks, Math.max(1, intervalTicks / 2)));
        AtomicInteger stopLogged = new AtomicInteger(0);

        sender.sendMessage(ChatColor.GREEN + "Starting native manifest stream probe for " + targetName
                + ": streams=" + specs.size()
                + ", packets=" + (specs.size() * 2)
                + ", intervalTicks=" + intervalTicks
                + ", packetGapTicks=" + packetGapTicks + ".");
        getLogger().info("YSM native manifest stream probe started: player=" + targetName
                + ", streams=" + specs.size()
                + ", packets=" + (specs.size() * 2)
                + ", intervalTicks=" + intervalTicks
                + ", packetGapTicks=" + packetGapTicks
                + ", modes=" + YsmNativeSyncPrototype.MODES + ".");

        for (int i = 0; i < specs.size(); i++) {
            int index = i;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Player current = Bukkit.getPlayer(targetId);
                if (current == null || !current.isOnline()) {
                    return;
                }
                YsmClientSession session = sessions.get(targetId);
                if (session != null && session.clientRawPacketsReceived() > initialClientRawPackets) {
                    if (stopLogged.compareAndSet(0, 1)) {
                        getLogger().info("YSM native manifest stream probe stopped after C2S raw/native response: player="
                                + targetName
                                + ", c2sRaw=" + session.clientRawPacketsReceived()
                                + ", bytes=" + session.clientRawBytesReceived()
                                + ", completedStreams=" + index + "/" + specs.size() + ".");
                    }
                    return;
                }
                StreamProbeSpec spec = specs.get(index);
                startNativeManifestStream(
                        null,
                        current,
                        spec.mode(),
                        spec.chainMode(),
                        "streamprobe:" + (index + 1) + "/" + specs.size(),
                        packetGapTicks);
            }, (long) index * intervalTicks);
        }

        Bukkit.getScheduler().runTaskLater(this, () -> {
            YsmClientSession session = sessions.get(targetId);
            if (session == null || session.clientRawPacketsReceived() <= initialClientRawPackets) {
                getLogger().warning("YSM native manifest stream probe finished without C2S raw/native response: player="
                        + targetName
                        + ", streams=" + specs.size()
                        + ", s2cRaw="
                        + (session == null ? "unknown" : session.serverRawPacketsSent() + "/" + session.serverRawBytesSent() + "b")
                        + ".");
            }
        }, (long) specs.size() * intervalTicks + packetGapTicks + 20L);
    }

    private void startNativeManifestStream(
            @Nullable CommandSender sender,
            Player player,
            String requestedMode,
            String requestedChainMode,
            String trigger,
            int packetGapTicks) {
        if (distributionRepository.prepared().isEmpty()) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "No prepared distribution packages exist yet.");
            }
            return;
        }
        String mode;
        try {
            mode = YsmNativeSyncPrototype.normalizeMode(requestedMode);
        } catch (IllegalArgumentException ex) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + ex.getMessage());
            } else {
                getLogger().warning("YSM native manifest stream skipped: " + ex.getMessage());
            }
            return;
        }
        String chainMode = requestedChainMode.toLowerCase(Locale.ROOT);
        if (!List.of("next", "selected", "initial").contains(chainMode)) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Unknown stream chain mode: " + requestedChainMode
                        + " (expected next|selected|initial)");
            } else {
                getLogger().warning("YSM native manifest stream skipped: unknown stream chain mode "
                        + requestedChainMode + ".");
            }
            return;
        }

        byte[] initialTransportKey = YsmNativeSyncPrototype.bootstrapTransportKey(mode);
        byte[] selectedModelKey = YsmNativeSyncPrototype.deterministicKey("stream-model", mode, chainMode);
        byte[] firstNextTransportKey = YsmNativeSyncPrototype.deterministicKey("stream-next-1", mode, chainMode);
        byte[] secondNextTransportKey = YsmNativeSyncPrototype.deterministicKey("stream-next-2", mode, chainMode);
        byte[] manifestTransportKey = switch (chainMode) {
            case "selected" -> selectedModelKey;
            case "initial" -> initialTransportKey;
            default -> firstNextTransportKey;
        };

        byte[] type1Plain = YsmRawPacketCodec.encodePlainType1(selectedModelKey, new byte[0]);
        byte[] type1Raw = YsmRawPacketCodec.encryptWithNextKey(
                type1Plain,
                initialTransportKey,
                firstNextTransportKey);
        byte[] type3Plain = createNativeManifestPlain(selectedModelKey, chainMode);
        byte[] type3Raw = YsmRawPacketCodec.encryptWithNextKey(
                type3Plain,
                manifestTransportKey,
                secondNextTransportKey);

        UUID targetId = player.getUniqueId();
        nativeSyncStates.put(targetId, NativeSyncState.started(
                new YsmNativeSyncPrototype.BootstrapPacket(
                        mode,
                        "stream-" + chainMode,
                        0,
                        initialTransportKey,
                        selectedModelKey,
                        firstNextTransportKey,
                        type1Raw,
                        Instant.now()),
                trigger));
        rememberNativeDecodeKeys(targetId, new YsmNativeSyncPrototype.BootstrapPacket(
                mode,
                "stream-" + chainMode,
                0,
                initialTransportKey,
                selectedModelKey,
                firstNextTransportKey,
                type1Raw,
                Instant.now()));
        addRecentNativeDecodeKey(targetId, "stream:" + mode + ":" + chainMode + ":second-next", secondNextTransportKey);
        sendServerRawPacket(player, type1Raw, "native-stream:" + mode + ":" + chainMode + ":" + trigger + ":type1");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player current = Bukkit.getPlayer(targetId);
            if (current == null || !current.isOnline()) {
                return;
            }
            sendServerRawPacket(current, type3Raw, "native-stream:" + mode + ":" + chainMode + ":" + trigger + ":type3-manifest");
        }, Math.max(1, packetGapTicks));

        if (sender != null) {
            sender.sendMessage(ChatColor.GREEN + "Sent native type1->type3 manifest stream to " + player.getName()
                    + " mode=" + mode
                    + " chain=" + chainMode
                    + " models=" + distributionRepository.prepared().size()
                    + ".");
        }
        getLogger().info("YSM native manifest stream sent: player=" + player.getName()
                + ", mode=" + mode
                + ", chain=" + chainMode
                + ", trigger=" + trigger
                + ", models=" + distributionRepository.prepared().size()
                + ", type1Bytes=" + type1Raw.length
                + ", type3Bytes=" + type3Raw.length
                + ", selectedKey=" + YsmNativeSyncPrototype.keyPreview(selectedModelKey)
                + ", firstNext=" + YsmNativeSyncPrototype.keyPreview(firstNextTransportKey)
                + ", manifestKey=" + YsmNativeSyncPrototype.keyPreview(manifestTransportKey)
                + ", secondNext=" + YsmNativeSyncPrototype.keyPreview(secondNextTransportKey)
                + ".");
    }

    private byte[] createNativeManifestPlain(byte[] modelKey, String chainMode) {
        List<YsmRawPacketCodec.ModelInfo> models = distributionRepository.prepared().stream()
                .map(model -> new YsmRawPacketCodec.ModelInfo(
                        model.modelId(),
                        model.format(),
                        model.transferBytes(),
                        model.decompressedBytes()))
                .toList();
        List<Long> prelude = models.stream()
                .map(model -> 0L)
                .toList();
        byte[] firstMetadata = YsmNativeSyncPrototype.deterministicKey("manifest-meta-a", chainMode, "type3");
        byte[] secondMetadata = YsmNativeSyncPrototype.deterministicKey("manifest-meta-b", chainMode, "type3");
        return YsmRawPacketCodec.encodePlainType3(
                0,
                Arrays.copyOf(firstMetadata, 0x1c),
                Arrays.copyOf(secondMetadata, 0x1c),
                modelKey,
                models,
                prelude,
                new byte[0]);
    }

    private void inspectClientRawPacket(Player player, byte[] rawPacketBody) {
        NativeSyncState state = nativeSyncStates.get(player.getUniqueId());
        if (state == null) {
            return;
        }

        List<YsmNativeSyncPrototype.KeyCandidate> candidates = nativeDecodeCandidates(player.getUniqueId(), state);
        Optional<YsmNativeSyncPrototype.DecodedPacket> decoded =
                YsmNativeSyncPrototype.tryDecode(rawPacketBody, candidates);
        if (decoded.isEmpty()) {
            getLogger().warning("YSM native client raw decode failed: player=" + player.getName()
                    + ", bytes=" + rawPacketBody.length
                    + ", candidates=" + candidates.stream()
                    .map(YsmNativeSyncPrototype.KeyCandidate::name)
                    .toList()
                    + ".");
            return;
        }

        YsmNativeSyncPrototype.DecodedPacket packet = decoded.get();
        NativeSyncState updated = state.withDecoded(packet, Instant.now());
        nativeSyncStates.put(player.getUniqueId(), updated);
        getLogger().info("YSM native client raw decoded: player=" + player.getName()
                + ", key=" + packet.keyName()
                + ", type=" + packet.packet().type()
                + ", summary=" + packet.packet().summary()
                + ", rawBytes=" + rawPacketBody.length
                + ", bodyBytes=" + packet.packet().body().length
                + ", selectedKey=" + packet.packet().selectedKey()
                .map(YsmNativeSyncPrototype::keyPreview)
                .orElse("none")
                + ", nextTransportKey=" + YsmNativeSyncPrototype.keyPreview(packet.nextTransportKey())
                + ", nextKeyTrailer=" + packet.usedNextKeyTrailer()
                + ".");
    }

    private List<YsmNativeSyncPrototype.KeyCandidate> nativeDecodeCandidates(UUID playerId, NativeSyncState state) {
        List<YsmNativeSyncPrototype.KeyCandidate> candidates = new ArrayList<>();
        CopyOnWriteArrayList<YsmNativeSyncPrototype.KeyCandidate> recent =
                nativeRecentDecodeKeys.get(playerId);
        if (recent != null) {
            for (YsmNativeSyncPrototype.KeyCandidate candidate : recent) {
                addNativeDecodeCandidate(candidates, candidate.name(), candidate.key());
            }
        }
        addNativeDecodeCandidate(candidates, "client-next", state.clientNextTransportKey());
        addNativeDecodeCandidate(candidates, "server-next", state.serverNextTransportKey());
        addNativeDecodeCandidate(candidates, "advertised", state.advertisedKey());
        addNativeDecodeCandidate(candidates, "bootstrap:" + state.mode(), state.bootstrapTransportKey());
        for (String mode : YsmNativeSyncPrototype.MODES) {
            try {
                addNativeDecodeCandidate(candidates, "mode:" + mode, YsmNativeSyncPrototype.bootstrapTransportKey(mode));
            } catch (IllegalArgumentException ignored) {
                // MODES is controlled by YsmNativeSyncPrototype.
            }
        }
        return candidates;
    }

    private void rememberNativeDecodeKeys(UUID playerId, YsmNativeSyncPrototype.BootstrapPacket packet) {
        CopyOnWriteArrayList<YsmNativeSyncPrototype.KeyCandidate> candidates =
                nativeRecentDecodeKeys.computeIfAbsent(playerId, ignored -> new CopyOnWriteArrayList<>());
        String label = "probe:" + packet.mode() + ":" + packet.variant() + ":p" + packet.paddingBytes();
        addNativeDecodeCandidate(candidates, label + ":bootstrap", packet.bootstrapKey());
        addNativeDecodeCandidate(candidates, label + ":advertised", packet.advertisedKey());
        addNativeDecodeCandidate(candidates, label + ":server-next", packet.nextTransportKey());
        while (candidates.size() > 96) {
            candidates.remove(0);
        }
    }

    private void addRecentNativeDecodeKey(UUID playerId, String name, byte[] key) {
        CopyOnWriteArrayList<YsmNativeSyncPrototype.KeyCandidate> candidates =
                nativeRecentDecodeKeys.computeIfAbsent(playerId, ignored -> new CopyOnWriteArrayList<>());
        addNativeDecodeCandidate(candidates, name, key);
        while (candidates.size() > 96) {
            candidates.remove(0);
        }
    }

    private static void addNativeDecodeCandidate(
            List<YsmNativeSyncPrototype.KeyCandidate> candidates,
            String name,
            byte[] key) {
        if (key == null || key.length != YsmRawPacketCodec.KEY_BYTES) {
            return;
        }
        boolean duplicate = candidates.stream().anyMatch(existing -> Arrays.equals(existing.key(), key));
        if (!duplicate) {
            candidates.add(new YsmNativeSyncPrototype.KeyCandidate(name, key));
        }
    }

    private void startRawReplay(CommandSender sender, Player player, String replayName, String replayMode) {
        if (!enableRawReplay) {
            sender.sendMessage(ChatColor.RED + "Raw replay is disabled. Set sync.enable-raw-replay=true for controlled captures.");
            sender.sendMessage(ChatColor.GRAY + "Replay accepts raw id=1 .bin bodies or Freesia debug .log/.txt S2C hex dumps under sync.raw-replay-dir.");
            return;
        }

        String normalizedMode = replayMode == null ? REPLAY_MODE_FAST : replayMode.toLowerCase(Locale.ROOT);
        if (!REPLAY_MODE_FAST.equals(normalizedMode)
                && !REPLAY_MODE_FREESIA.equals(normalizedMode)
                && !REPLAY_MODE_FREESIA_PRELUDE.equals(normalizedMode)) {
            sender.sendMessage(ChatColor.RED + "Unsupported raw replay mode: " + replayMode
                    + " (expected fast|freesia|freesia-prelude)");
            return;
        }

        UUID targetId = player.getUniqueId();
        String targetName = player.getName();
        Path replayRoot = resolvePluginPath(rawReplayDir).toAbsolutePath().normalize();
        Path replayPath = replayRoot.resolve(replayName).normalize();
        if (!replayPath.startsWith(replayRoot)) {
            sender.sendMessage(ChatColor.RED + "Replay path must stay under " + replayRoot + ".");
            return;
        }
        sender.sendMessage(ChatColor.YELLOW + "Loading raw replay packets from " + replayPath.toAbsolutePath() + "...");

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            List<ReplayPacket> packets;
            try {
                packets = readReplayPackets(replayPath);
            } catch (IOException | IllegalArgumentException ex) {
                Bukkit.getScheduler().runTask(this, () ->
                        sender.sendMessage(ChatColor.RED + "Failed to load raw replay packets: " + ex.getMessage()));
                return;
            }

            Bukkit.getScheduler().runTask(this, () -> {
                Player live = Bukkit.getPlayer(targetId);
                if (live == null || !live.isOnline()) {
                    sender.sendMessage(ChatColor.RED + "Player went offline before replay: " + targetName);
                    return;
                }
                sender.sendMessage(ChatColor.GREEN + "Replaying " + packets.size()
                        + " raw id=1 packet(s) to " + live.getName()
                        + " every " + rawReplayIntervalTicks + " tick(s).");
                getLogger().info("YSM raw replay started: player=" + live.getName()
                        + ", source=" + replayPath.toAbsolutePath()
                        + ", packets=" + packets.size()
                        + ", mode=" + normalizedMode
                        + ", intervalTicks=" + rawReplayIntervalTicks + ".");
                if (REPLAY_MODE_FREESIA.equals(normalizedMode)
                        || REPLAY_MODE_FREESIA_PRELUDE.equals(normalizedMode)) {
                    startFreesiaPacedReplay(
                            sender,
                            targetId,
                            targetName,
                            packets,
                            REPLAY_MODE_FREESIA_PRELUDE.equals(normalizedMode));
                } else {
                    scheduleReplayPackets(targetId, packets, 0, 0L, "replay-fast");
                }
            });
        });
    }

    private void startFreesiaPacedReplay(
            CommandSender sender,
            UUID targetId,
            String targetName,
            List<ReplayPacket> packets,
            boolean sendPrelude) {
        YsmClientSession initialSession = sessions.get(targetId);
        int initialClientRawPackets = initialSession == null ? 0 : initialSession.clientRawPacketsReceived();

        if (sendPrelude) {
            Player current = Bukkit.getPlayer(targetId);
            if (current == null || !current.isOnline()) {
                sender.sendMessage(ChatColor.RED + "Player went offline before Freesia-prelude replay: " + targetName);
                return;
            }
            sendFreesiaReplayPrelude(current);
        }

        String reasonPrefix = sendPrelude ? "replay-freesia-prelude" : "replay-freesia";
        sendReplayPacket(targetId, packets.get(0), reasonPrefix + ":bootstrap-1");
        if (sendPrelude) {
            Player current = Bukkit.getPlayer(targetId);
            if (current != null && current.isOnline()) {
                sendReplayDefaultModelState(current);
            }
        }
        if (packets.size() == 1) {
            sender.sendMessage(ChatColor.YELLOW + "Freesia-paced replay had only one packet; sent bootstrap only.");
            return;
        }

        waitForClientRawPackets(targetId, targetName, initialClientRawPackets + 1, 0, () -> {
            sendReplayPacket(targetId, packets.get(1), reasonPrefix + ":bootstrap-2");
            if (packets.size() == 2) {
                return;
            }

            waitForClientRawPackets(targetId, targetName, initialClientRawPackets + 2, 0, () -> {
                sender.sendMessage(ChatColor.GREEN + "Freesia-paced replay saw two C2S id=2 response(s); streaming "
                        + (packets.size() - 2) + " remaining packet(s).");
                scheduleReplayPackets(targetId, packets, 2, rawReplayIntervalTicks, reasonPrefix + ":stream");
            }, () -> {
                sender.sendMessage(ChatColor.YELLOW + "Freesia-paced replay timed out waiting for the second C2S id=2 response; "
                        + "remaining packets were not streamed.");
            });
        }, () -> {
            sender.sendMessage(ChatColor.YELLOW + "Freesia-paced replay timed out waiting for the first C2S id=2 response; "
                    + "try reconnecting the client or use fast replay for comparison.");
        });
    }

    private void sendFreesiaReplayPrelude(Player player) {
        sendEmptyModelSet(player, 6, "replay-freesia-prelude:authorized-empty-1");
        sendEmptyModelSet(player, 6, "replay-freesia-prelude:authorized-empty-2");
        sendEmptyModelSet(player, 8, "replay-freesia-prelude:visible-empty");
        getLogger().info("YSM Freesia replay prelude sent: player=" + player.getName()
                + ", packets=3, sequence=06-00/06-00/08-00.");
    }

    private void sendReplayDefaultModelState(Player player) {
        byte[] body = YsmEntityStateCodec.encodeModelSelectionBody(
                player.getEntityId(),
                "default",
                "default",
                false);
        byte[] payload = YsmProtocol.encodeEntityDataUpdate(player.getEntityId(), body);
        sendYsmPayload(player, payload, "replay-freesia-prelude:default-state");
        getLogger().info("YSM Freesia replay default model-state sent: player=" + player.getName()
                + ", entity=" + player.getEntityId()
                + ", bodyBytes=" + body.length
                + ", payloadBytes=" + payload.length
                + ", preview=" + YsmProtocol.toHex(payload, packetHexPreviewBytes) + ".");
    }

    private void waitForClientRawPackets(
            UUID targetId,
            String targetName,
            int requiredCount,
            int waitedTicks,
            Runnable onReady,
            Runnable onTimeout) {
        Player player = Bukkit.getPlayer(targetId);
        if (player == null || !player.isOnline()) {
            getLogger().warning("YSM raw replay wait stopped because player went offline: " + targetName + ".");
            return;
        }

        YsmClientSession session = sessions.get(targetId);
        int currentCount = session == null ? 0 : session.clientRawPacketsReceived();
        if (currentCount >= requiredCount) {
            onReady.run();
            return;
        }

        if (waitedTicks >= rawReplayHandshakeTimeoutTicks) {
            getLogger().warning("YSM raw replay timed out waiting for C2S id=2: player=" + targetName
                    + ", required=" + requiredCount
                    + ", current=" + currentCount
                    + ", timeoutTicks=" + rawReplayHandshakeTimeoutTicks + ".");
            onTimeout.run();
            return;
        }

        Bukkit.getScheduler().runTaskLater(this, () -> waitForClientRawPackets(
                        targetId,
                        targetName,
                        requiredCount,
                        waitedTicks + FREESIA_REPLAY_WAIT_INTERVAL_TICKS,
                        onReady,
                        onTimeout),
                FREESIA_REPLAY_WAIT_INTERVAL_TICKS);
    }

    private void scheduleReplayPackets(
            UUID targetId,
            List<ReplayPacket> packets,
            int startIndex,
            long initialDelayTicks,
            String reasonPrefix) {
        for (int i = startIndex; i < packets.size(); i++) {
            int packetIndex = i;
            long delay = initialDelayTicks + (long) (i - startIndex) * rawReplayIntervalTicks;
            Bukkit.getScheduler().runTaskLater(this, () ->
                    sendReplayPacket(targetId, packets.get(packetIndex), reasonPrefix), delay);
        }
    }

    private void sendReplayPacket(UUID targetId, ReplayPacket packet, String reasonPrefix) {
        Player current = Bukkit.getPlayer(targetId);
        if (current == null || !current.isOnline()) {
            return;
        }
        sendServerRawPacket(current, packet.body(), reasonPrefix + ":" + packet.file().getFileName());
    }

    private List<ReplayPacket> readReplayPackets(Path replayPath) throws IOException {
        List<Path> paths;
        if (Files.isRegularFile(replayPath)) {
            paths = List.of(replayPath);
        } else if (Files.isDirectory(replayPath)) {
            try (var stream = Files.list(replayPath)) {
                paths = stream
                        .filter(Files::isRegularFile)
                        .filter(this::isReplaySourceFile)
                        .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                        .toList();
            }
        } else {
            throw new IllegalArgumentException("Path does not exist: " + replayPath.toAbsolutePath());
        }

        if (paths.isEmpty()) {
            throw new IllegalArgumentException("No .bin/.hex/.log replay packets under " + replayPath.toAbsolutePath());
        }

        List<ReplayPacket> packets = new ArrayList<>();
        for (Path path : paths) {
            packets.addAll(readReplaySourceFile(path));
        }
        if (packets.isEmpty()) {
            throw new IllegalArgumentException("No S2C id=1 raw/native packets found in " + replayPath.toAbsolutePath());
        }
        return packets;
    }

    private boolean isReplaySourceFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".bin")
                || name.endsWith(".hex")
                || name.endsWith(".txt")
                || name.endsWith(".log");
    }

    private List<ReplayPacket> readReplaySourceFile(Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".bin")) {
            byte[] body = Files.readAllBytes(path);
            if (body.length == 0) {
                throw new IllegalArgumentException("Replay packet is empty: " + path.toAbsolutePath());
            }
            return List.of(new ReplayPacket(path, normalizeReplayBody(path, body, false)));
        }

        String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        List<ReplayPacket> packets = parseFreesiaReplayLog(path, text);
        if (!packets.isEmpty()) {
            return packets;
        }

        byte[] payload = parseHexBytes(text);
        if (payload.length == 0) {
            throw new IllegalArgumentException("No hex packet bytes found in " + path.toAbsolutePath());
        }
        return List.of(new ReplayPacket(path, normalizeReplayBody(path, payload, true)));
    }

    private List<ReplayPacket> parseFreesiaReplayLog(Path path, String text) {
        List<ReplayPacket> packets = new ArrayList<>();
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            Matcher matcher = FREESIA_S2C_HEX_PATTERN.matcher(lines[i]);
            if (!matcher.find()) {
                continue;
            }

            int expectedBytes = Integer.parseInt(matcher.group(1));
            byte[] payload = parseHexBytes(matcher.group(2));
            if (payload.length != expectedBytes) {
                getLogger().warning("Freesia replay log byte count mismatch: file=" + path.toAbsolutePath()
                        + ", line=" + (i + 1)
                        + ", expected=" + expectedBytes
                        + ", parsed=" + payload.length + ".");
                continue;
            }
            if (payload.length <= 1 || (payload[0] & 0xff) != YsmProtocol.SERVER_RAW_PACKET_ID) {
                continue;
            }

            byte[] body = Arrays.copyOfRange(payload, 1, payload.length);
            if (!YsmRawPacketCodec.hasValidPacketHash(body)) {
                getLogger().warning("Skipping Freesia replay packet with invalid native hash: file=" + path.toAbsolutePath()
                        + ", line=" + (i + 1)
                        + ", bodyBytes=" + body.length + ".");
                continue;
            }
            packets.add(new ReplayPacket(path.resolveSibling(path.getFileName() + "#line-" + (i + 1)), body));
        }
        if (!packets.isEmpty()) {
            getLogger().info("Parsed Freesia S2C raw replay log: file=" + path.toAbsolutePath()
                    + ", id1Packets=" + packets.size() + ".");
        }
        return packets;
    }

    private byte[] normalizeReplayBody(Path path, byte[] bytes, boolean textHexSource) {
        if (textHexSource && bytes.length > 1 && (bytes[0] & 0xff) == YsmProtocol.SERVER_RAW_PACKET_ID) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }

        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean filenameSaysPayload = name.contains("payload")
                || name.contains("full")
                || name.contains("id1");
        if (filenameSaysPayload && bytes.length > 1 && (bytes[0] & 0xff) == YsmProtocol.SERVER_RAW_PACKET_ID) {
            return Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    private static byte[] parseHexBytes(String text) {
        Matcher matcher = HEX_BYTE_PATTERN.matcher(text);
        byte[] bytes = new byte[Math.max(0, text.length() / 2)];
        int count = 0;
        while (matcher.find()) {
            if (count == bytes.length) {
                bytes = Arrays.copyOf(bytes, bytes.length + Math.max(16, bytes.length));
            }
            bytes[count++] = (byte) Integer.parseInt(matcher.group(), 16);
        }
        return Arrays.copyOf(bytes, count);
    }

    private void sendServerRawPacket(Player player, byte[] rawPacketBody, String reason) {
        byte[] payload = YsmProtocol.encodeServerRawPacket(rawPacketBody);
        sendYsmPayload(player, payload, reason);
        sessions.compute(player.getUniqueId(), (ignored, existing) -> {
            YsmClientSession base = existing != null
                    ? existing
                    : YsmClientSession.pending(player.getUniqueId(), player.getName());
            return base.withServerRawPacketSent(rawPacketBody.length, Instant.now());
        });
        if (shouldLogRawPacket(reason)) {
            getLogger().info("YSM server raw packet sent: player=" + player.getName()
                    + ", reason=" + reason
                    + ", bodyBytes=" + rawPacketBody.length
                    + ", payloadBytes=" + payload.length
                    + ", preview=" + YsmProtocol.toHex(payload, rawPacketHexPreviewBytes) + ".");
        }
    }

    private void sendYsmPayload(Player player, byte[] payload, String reason) {
        player.sendPluginMessage(this, channel, payload);
        int subpacketId = payload.length == 0 ? -1 : payload[0] & 0xff;
        if (subpacketId == 6) {
            sessions.compute(player.getUniqueId(), (ignored, existing) -> {
                YsmClientSession base = existing != null
                        ? existing
                        : YsmClientSession.pending(player.getUniqueId(), player.getName());
                return base.withAuthorizedModelsSent(0, payload.length, Instant.now());
            });
        }
        if (logPacketDetails) {
            getLogger().info("YSM server packet sent: player=" + player.getName()
                    + ", reason=" + reason
                    + ", subpacket=" + subpacketId
                    + ", payloadBytes=" + payload.length
                    + ", preview=" + YsmProtocol.toHex(payload, packetHexPreviewBytes) + ".");
        }
    }

    private void prepareDistributionModel(@Nullable CommandSender sender, YsmModelRepository.Entry entry) {
        try {
            YsmDistributionRepository.PreparedModel model = distributionRepository.prepareOne(
                    resolvePluginPath(distributionCacheDir),
                    entry,
                    distributionChunkBytes,
                    writeDistributionCacheFiles);
            if (sender != null) {
                sender.sendMessage(ChatColor.GREEN + "Prepared YSM distribution package: "
                        + model.modelId() + " chunks=" + model.chunkCount()
                        + " server-cache=" + formatBytes(model.serverCacheYsmZstdBytes()) + ".");
            }
            logPreparedDistributionModel(model);
        } catch (Exception ex) {
            if (sender != null) {
                sender.sendMessage(ChatColor.RED + "Failed to prepare distribution package for "
                        + entry.modelId() + ": " + ex.getMessage());
            }
            getLogger().warning("YSM distribution prepare failed: model=" + entry.modelId()
                    + ", file=" + entry.file().toAbsolutePath()
                    + ", reason=" + ex.getMessage());
        }
    }

    private void reloadModelRepository(boolean manualReload) {
        if (!scanModelsOnEnable && !manualReload) {
            getLogger().info("YSM model repository scan skipped on startup because scan-models-on-enable=false.");
            return;
        }

        Path root = resolvePluginPath(modelsDir);
        long started = System.nanoTime();
        getLogger().info("YSM model repository scan started: root=" + root.toAbsolutePath()
                + ", exists=" + Files.exists(root)
                + ", trigger=" + (manualReload ? "command" : "startup") + ".");
        try {
            modelRepository.reload(root);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            getLogger().info("YSM model repository scanned " + modelRepository.entries().size()
                    + " model(s), " + modelRepository.failures().size()
                    + " failure(s), root=" + root.toAbsolutePath()
                    + ", durationMs=" + elapsedMs + ".");

            if (logModelScanDetails) {
                for (YsmModelRepository.Entry entry : modelRepository.entries()) {
                    getLogger().info("YSM model loaded: id=" + entry.modelId()
                            + ", file=" + entry.file().toAbsolutePath()
                            + ", version=" + entry.version()
                            + ", format=" + entry.format()
                            + ", encryptedBytes=" + entry.size()
                            + ", decompressedBytes=" + entry.decompressedBytes()
                            + ", payloadTrailing=" + entry.payloadTrailingBytes()
                            + ", summary=" + entry.summary()
                            + ", profile={" + entry.profile().compact() + "}"
                            + ", animationMap={" + entry.profile().animationDebugSummary(16) + "}.");
                }
            }

            for (YsmModelRepository.Failure failure : modelRepository.failures()) {
                getLogger().warning("YSM model failed: file=" + failure.file().toAbsolutePath()
                        + ", reason=" + failure.message());
            }

            if (prepareDistributionOnReload) {
                prepareDistributionRepository(false);
            } else {
                distributionRepository.clear();
                getLogger().info("YSM distribution preparation skipped because distribution.prepare-on-reload=false.");
            }
        } catch (IOException ex) {
            getLogger().warning("Failed to scan YSM model repository at " + root.toAbsolutePath()
                    + ": " + ex.getMessage());
        }
    }

    private void scheduleStartupModelRepositoryReload() {
        scheduleModelRepositoryReload(false, null);
    }

    private void scheduleModelRepositoryReload(boolean manualReload, @Nullable CommandSender sender) {
        if (!scanModelsOnEnable && !manualReload) {
            getLogger().info("YSM model repository scan skipped on startup because scan-models-on-enable=false.");
            return;
        }
        if (modelRepositoryReloadInProgress) {
            if (sender != null) {
                sender.sendMessage(ChatColor.YELLOW + "YSM model repository reload is already running.");
            }
            getLogger().info("YSM model repository reload request ignored because a reload is already running.");
            return;
        }
        modelRepositoryReloadInProgress = true;
        long delayTicks = manualReload ? 1L : 20L;
        String trigger = manualReload ? "command" : "startup";
        Path root = resolvePluginPath(modelsDir);
        getLogger().info("YSM model repository scan scheduled asynchronously: root="
                + root.toAbsolutePath()
                + ", delayTicks=" + delayTicks
                + ", trigger=" + trigger + ".");
        if (sender != null) {
            sender.sendMessage(ChatColor.YELLOW + "YSM model repository reload scheduled in the background.");
        }
        Bukkit.getScheduler().runTaskLater(this, () -> Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                reloadModelRepository(manualReload);
            } finally {
                Bukkit.getScheduler().runTask(this, () -> {
                    modelRepositoryReloadInProgress = false;
                    if (sender != null) {
                        sender.sendMessage(ChatColor.GREEN + "YSM model repository reload finished: models="
                                + modelRepository.entries().size()
                                + ", failed=" + modelRepository.failures().size() + ".");
                    }
                    if (!manualReload && sendAuthorizedModelsOnHandshake && !modelRepository.entries().isEmpty()) {
                        int sent = 0;
                        int syncScheduled = 0;
                        for (Player player : Bukkit.getOnlinePlayers()) {
                            YsmClientSession session = sessions.get(player.getUniqueId());
                            if (session != null && session.compatible()) {
                                sendAuthorizedModelSet(player);
                                sent++;
                                if (autoGeneratedCacheOnHandshake && hasGeneratedCacheSource()) {
                                    scheduleAutoGeneratedCacheReplay(player);
                                    syncScheduled++;
                                }
                            }
                        }
                        if (sent > 0) {
                            getLogger().info("YSM authorized model list refreshed after async startup scan: players="
                                    + sent
                                    + ", autoGeneratedSyncScheduled=" + syncScheduled + ".");
                        }
                    }
                });
            }
        }), delayTicks);
    }

    private void prepareDistributionRepository(boolean manual) {
        Path cacheRoot = resolvePluginPath(distributionCacheDir);
        long started = System.nanoTime();
        getLogger().info("YSM distribution preparation started: root=" + cacheRoot.toAbsolutePath()
                + ", models=" + modelRepository.entries().size()
                + ", chunkBytes=" + distributionChunkBytes
                + ", writeCacheFiles=" + writeDistributionCacheFiles
                + ", trigger=" + (manual ? "command" : "model-scan") + ".");
        try {
            distributionRepository.prepareAll(
                    cacheRoot,
                    modelRepository.entries(),
                    distributionChunkBytes,
                    writeDistributionCacheFiles);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            getLogger().info("YSM distribution preparation finished: prepared="
                    + distributionRepository.prepared().size()
                    + ", failed=" + distributionRepository.failures().size()
                    + ", chunks=" + distributionRepository.totalChunkCount()
                    + ", transferBytes=" + distributionRepository.totalTransferBytes()
                    + ", decompressedBytes=" + distributionRepository.totalDecompressedBytes()
                    + ", durationMs=" + elapsedMs + ".");

            if (logModelScanDetails) {
                for (YsmDistributionRepository.PreparedModel model : distributionRepository.prepared()) {
                    logPreparedDistributionModel(model);
                }
            }
            for (YsmDistributionRepository.Failure failure : distributionRepository.failures()) {
                getLogger().warning("YSM distribution model failed: model=" + failure.modelId()
                        + ", file=" + failure.file().toAbsolutePath()
                        + ", reason=" + failure.message());
            }
        } catch (IOException ex) {
            getLogger().warning("Failed to prepare YSM distribution cache at " + cacheRoot.toAbsolutePath()
                    + ": " + ex.getMessage());
        }
    }

    private void logPreparedDistributionModel(YsmDistributionRepository.PreparedModel model) {
        getLogger().info("YSM distribution model prepared: id=" + model.modelId()
                + ", file=" + model.sourceFile().toAbsolutePath()
                + ", format=" + model.format()
                + ", decompressedBytes=" + model.decompressedBytes()
                + ", zstdBytes=" + model.washedZstdBytes()
                + ", serverCachePlainBytes=" + model.serverCachePlainBytes()
                + ", serverCacheYsmZstdBytes=" + model.serverCacheYsmZstdBytes()
                + ", transferKind=" + model.transferKind()
                + ", transferBytes=" + model.transferBytes()
                + ", chunks=" + model.chunkCount()
                + ", chunkBytes=" + model.chunkBytes()
                + ", modelHash=" + logValue(model.modelHash())
                + ", payloadSha256=" + model.payloadSha256()
                + ", serverCacheYsmZstdSha256=" + model.serverCacheYsmZstdSha256()
                + ", transferSha256=" + model.transferSha256()
                + (model.cacheDirectory() == null ? "" : ", cacheDir=" + model.cacheDirectory().toAbsolutePath())
                + ".");
    }

    private void maybeLogMissingNativeSync(Player player) {
        if (!warnMissingNativeSyncOnHandshake || distributionRepository.prepared().isEmpty()) {
            return;
        }
        if (!nativeSyncGapWarnings.add(player.getUniqueId())) {
            return;
        }

        getLogger().warning("YSM sync diagnostic: player=" + player.getName()
                + " completed 51/52 handshake and received id=6 authorized model ids, but PaperYSM has not generated"
                + " the native S2C id=1 cache sync stream yet. The client should not show a download progress bar"
                + " or roulette entries until id=1 packets are sent. prepared="
                + distributionRepository.prepared().size()
                + ", chunks=" + distributionRepository.totalChunkCount()
                + ", transferBytes=" + distributionRepository.totalTransferBytes()
                + ", rawReplayEnabled=" + enableRawReplay + ".");
    }

    private void captureClientRawPacket(Player player, byte[] rawPacketBody) {
        if (!captureClientRawPackets) {
            return;
        }

        int index = rawPacketCaptureCounter.incrementAndGet();
        Path captureRoot = resolvePluginPath(rawPacketCaptureDir);
        String fileName = Instant.now().toString()
                .replace(':', '-')
                .replace('.', '-')
                + "-" + sanitizeFileName(player.getName())
                + "-" + index
                + "-id2-" + rawPacketBody.length + "b.bin";
        Path output = captureRoot.resolve(fileName);

        if (debug) {
            getLogger().info("YSM client raw packet captured: player=" + player.getName()
                    + ", bytes=" + rawPacketBody.length
                    + ", file=" + output.toAbsolutePath()
                    + ", preview=" + YsmProtocol.toHex(rawPacketBody, rawPacketHexPreviewBytes));
        }

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            try {
                Files.createDirectories(captureRoot);
                Files.write(output, rawPacketBody);
            } catch (IOException ex) {
                getLogger().warning("Failed to write YSM raw packet capture " + output.toAbsolutePath()
                        + ": " + ex.getMessage());
            }
        });
    }

    private static String sanitizeFileName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]+", "_");
    }

    private static String logValue(String value) {
        return value == null || value.isEmpty() ? "<empty>" : '"' + value + '"';
    }

    private static int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static int parseNonNegativeInt(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static @Nullable Integer parsePaddingBytes(CommandSender sender, String value) {
        try {
            return YsmNativeSyncPrototype.normalizePaddingBytes(Integer.parseInt(value));
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "Invalid native packet padding bytes: " + value
                    + " (expected 0-127)");
            return null;
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
    }

    private static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private Path resolvePluginPath(String configuredPath) {
        Path path = Path.of(configuredPath);
        if (path.isAbsolute()) {
            return path;
        }
        return getDataFolder().toPath().resolve(path);
    }

    private void sendStatus(CommandSender sender, Player player) {
        YsmClientSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            sender.sendMessage(ChatColor.GRAY + player.getName() + ": no YSM activity yet");
            return;
        }

        ChatColor color = session.compatible() ? ChatColor.GREEN : ChatColor.YELLOW;
        sender.sendMessage(color + player.getName() + ": " + session.describe());
        AppliedModelState applied = appliedModelStates.get(player.getUniqueId());
        SavedModelState saved = savedModelStates.get(player.getUniqueId());
        if (applied != null) {
            sender.sendMessage(ChatColor.GRAY + "  applied model: " + applied.modelId()
                    + "/" + applied.textureId()
                    + ", disabled=" + applied.disabled());
        }
        if (saved != null) {
            sender.sendMessage(ChatColor.GRAY + "  saved model: " + saved.modelId()
                    + "/" + saved.textureId()
                    + ", disabled=" + saved.disabled());
        }
    }

    private record NativeCacheRequest(int count, List<NativeCacheRequestedEntry> entries, int tokenBytes) {
    }

    private record NativeCacheRequestedEntry(
            byte[] token,
            String name,
            @Nullable byte[] inlineCacheBytes,
            @Nullable Path cacheFile,
            long cacheBytes,
            @Nullable Long bodyHash,
            long hashA,
            long hashB) {
        static NativeCacheRequestedEntry fromBytes(byte[] token, String name, byte[] cacheBytes) {
            return new NativeCacheRequestedEntry(token, name, cacheBytes, null, cacheBytes.length, null, 0L, 0L);
        }
    }

    private record NativeCacheEntry(
            String tokenHex,
            byte[] token,
            String name,
            @Nullable byte[] inlineCacheBytes,
            @Nullable Path cacheFile,
            long cacheBytes,
            @Nullable Long bodyHash,
            long hashA,
            long hashB) {
        static NativeCacheEntry fromBytes(String tokenHex, byte[] token, String name, byte[] cacheBytes) {
            return new NativeCacheEntry(tokenHex, token, name, cacheBytes, null, cacheBytes.length, null, 0L, 0L);
        }

        static NativeCacheEntry fromFile(String tokenHex, byte[] token, String name, Path cacheFile, long cacheBytes) {
            return fromFile(tokenHex, token, name, cacheFile, cacheBytes, null, 0L, 0L);
        }

        static NativeCacheEntry fromFile(
                String tokenHex,
                byte[] token,
                String name,
                Path cacheFile,
                long cacheBytes,
                @Nullable Long bodyHash,
                long hashA,
                long hashB) {
            return new NativeCacheEntry(tokenHex, token, name, null, cacheFile, cacheBytes, bodyHash, hashA, hashB);
        }

        NativeCacheRequestedEntry asRequestedEntry() {
            return new NativeCacheRequestedEntry(token, name, inlineCacheBytes, cacheFile, cacheBytes, bodyHash, hashA, hashB);
        }
    }

    private record GeneratedCacheStart(
            String requestedModelId,
            String manifestLayout,
            String payloadMode,
            int modelCount,
            int availableModelCount,
            boolean modelLimitApplied,
            int skippedModelCount,
            int cacheEntryCount,
            int type3BodyBytes,
            long cacheBytes,
            byte[] modelKey,
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            byte[] s2cKey,
            byte[] type1Raw,
            @Nullable NativeCacheReplaySession state) {
        static GeneratedCacheStart empty(String requestedModelId, String manifestLayout, String payloadMode) {
            return empty(requestedModelId, manifestLayout, payloadMode, 0, false, 0);
        }

        static GeneratedCacheStart empty(
                String requestedModelId,
                String manifestLayout,
                String payloadMode,
                int availableModelCount,
                boolean modelLimitApplied,
                int skippedModelCount) {
            return new GeneratedCacheStart(
                    requestedModelId,
                    manifestLayout,
                    payloadMode,
                    0,
                    availableModelCount,
                    modelLimitApplied,
                    skippedModelCount,
                    0,
                    0,
                    0L,
                    new byte[0],
                    new byte[0],
                    new byte[0],
                    new byte[0],
                    new byte[0],
                    null);
        }

        boolean empty() {
            return state == null;
        }
    }

    private record GeneratedNativeCacheModel(
            byte[] token,
            String name,
            int format,
            int transferBytes,
            int cacheBytes) {
    }

    private record GeneratedCachePayload(String mode, byte[] bytes, String sha256) {
    }

    private record GeneratedOpenYsmCacheKeys(byte[] serverCacheKey, byte[] clientCacheKey, Path indexFile) {
    }

    private record GeneratedCacheTokens(
            long displayHashA,
            long displayHashB,
            byte[] displayToken,
            String displayTokenHex,
            long physicalHashA,
            long physicalHashB,
            String physicalTokenHex) {
    }

    private record GeneratedServerCacheStats(Path root, int indexedEntries, int files, long bytes) {
    }

    private record GeneratedServerCacheIndexEntry(
            String tokenHex,
            String modelId,
            Path sourcePath,
            long sourceBytes,
            long sourceMtime,
            Path cacheFile,
            long cacheBytes,
            long serverCacheBytes,
            String modelHash,
            String sha256,
            String tokenVersion,
            String physicalTokenHex,
            long bodyHash,
            long displayHashA,
            long displayHashB) {
        boolean isCurrentFor(YsmModelRepository.Entry entry) {
            try {
                return modelId.equals(entry.modelId())
                        && GENERATED_CACHE_TOKEN_VERSION.equals(tokenVersion)
                        && !physicalTokenHex.isBlank()
                        && bodyHash != 0L
                        && entry.size() == sourceBytes
                        && Files.getLastModifiedTime(entry.file()).toMillis() == sourceMtime
                        && entry.file().toAbsolutePath().normalize().equals(sourcePath.toAbsolutePath().normalize())
                        && Files.exists(cacheFile)
                        && Files.size(cacheFile) == cacheBytes;
            } catch (IOException ex) {
                return false;
            }
        }
    }

    private record GeneratedServerCachePrewarmResult(
            String requestedModelId,
            int matchedModels,
            int currentModels,
            int preparedModels,
            int failedModels,
            long cacheBytes,
            List<String> failureSamples) {
    }

    private record GeneratedCacheBatchQueue(
            String requestedModelId,
            String sourceName,
            String manifestLayout,
            String payloadMode,
            int intervalTicks,
            int chunkBytes,
            List<YsmModelRepository.Entry> entries,
            int batchSize,
            int nextIndex,
            int startedBatches,
            int preparedModels,
            int skippedModels) {
        GeneratedCacheBatchQueue withProgress(
                int nextIndex,
                int startedBatches,
                int preparedModels,
                int skippedModels) {
            return new GeneratedCacheBatchQueue(
                    requestedModelId,
                    sourceName,
                    manifestLayout,
                    payloadMode,
                    intervalTicks,
                    chunkBytes,
                    entries,
                    batchSize,
                    nextIndex,
                    startedBatches,
                    this.preparedModels + preparedModels,
                    this.skippedModels + skippedModels);
        }
    }

    private record NativeCacheReplaySession(
            String captureName,
            Path sourceDir,
            byte[] s2cKey,
            @Nullable byte[] c2sKey,
            byte[] type3Body,
            int type1PaddingBytes,
            int type3PaddingBytes,
            Map<String, NativeCacheEntry> cacheEntries,
            @Nullable byte[] fallbackServerCacheBytes,
            long cacheBytes,
            int intervalTicks,
            int chunkBytes,
            int packetsPerTick,
            Instant startedAt,
            @Nullable Instant c2sDecodedAt,
            @Nullable Instant type3SentAt,
            @Nullable Instant type4DecodedAt,
            int type3RawBytes,
            int requestedTokens,
            int requestTokenBytes,
            int type5Packets,
            long type5Bytes,
            int lastClientType,
            @Nullable String lastClientSummary,
            @Nullable Instant lastClientPacketAt) {
        static NativeCacheReplaySession started(
                String captureName,
                Path sourceDir,
                byte[] s2cKey,
                byte[] type3Body,
                int type1PaddingBytes,
                int type3PaddingBytes,
                Map<String, NativeCacheEntry> cacheEntries,
                @Nullable byte[] fallbackServerCacheBytes,
                long cacheBytes,
                int intervalTicks,
                int chunkBytes,
                int packetsPerTick) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    null,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    Map.copyOf(cacheEntries),
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    Math.max(1, packetsPerTick),
                    Instant.now(),
                    null,
                    null,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    -1,
                    null,
                    null);
        }

        NativeCacheReplaySession withC2sKey(byte[] c2sKey, Instant decodedAt) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    c2sKey,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    cacheEntries,
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    packetsPerTick,
                    startedAt,
                    decodedAt,
                    type3SentAt,
                    type4DecodedAt,
                    type3RawBytes,
                    requestedTokens,
                    requestTokenBytes,
                    type5Packets,
                    type5Bytes,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        NativeCacheReplaySession withType3Sent(int rawBytes, Instant sentAt) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    c2sKey,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    cacheEntries,
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    packetsPerTick,
                    startedAt,
                    c2sDecodedAt,
                    sentAt,
                    type4DecodedAt,
                    rawBytes,
                    requestedTokens,
                    requestTokenBytes,
                    type5Packets,
                    type5Bytes,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        NativeCacheReplaySession withType4Decoded(int requestCount, int tokenBytes) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    c2sKey,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    cacheEntries,
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    packetsPerTick,
                    startedAt,
                    c2sDecodedAt,
                    type3SentAt,
                    Instant.now(),
                    type3RawBytes,
                    requestCount,
                    tokenBytes,
                    type5Packets,
                    type5Bytes,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        NativeCacheReplaySession withType5Scheduled(int packetCount, long bytes) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    c2sKey,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    cacheEntries,
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    packetsPerTick,
                    startedAt,
                    c2sDecodedAt,
                    type3SentAt,
                    type4DecodedAt,
                    type3RawBytes,
                    requestedTokens,
                    requestTokenBytes,
                    packetCount,
                    bytes,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        NativeCacheReplaySession withClientPacket(YsmRawPacketCodec.PlainPacket packet) {
            return new NativeCacheReplaySession(
                    captureName,
                    sourceDir,
                    s2cKey,
                    c2sKey,
                    type3Body,
                    type1PaddingBytes,
                    type3PaddingBytes,
                    cacheEntries,
                    fallbackServerCacheBytes,
                    cacheBytes,
                    intervalTicks,
                    chunkBytes,
                    packetsPerTick,
                    startedAt,
                    c2sDecodedAt,
                    type3SentAt,
                    type4DecodedAt,
                    type3RawBytes,
                    requestedTokens,
                    requestTokenBytes,
                    type5Packets,
                    type5Bytes,
                    packet.type(),
                    packet.summary(),
                    Instant.now());
        }

        String describe() {
            return "nativeCache=source=" + captureName
                    + ", entries=" + cacheEntries.size()
                    + ", cache=" + cacheBytes + "b"
                    + ", type3Body=" + type3Body.length + "b"
                    + ", padding=" + type1PaddingBytes + "/" + type3PaddingBytes
                    + ", chunk=" + chunkBytes
                    + ", burst=" + packetsPerTick
                    + ", s2c=" + YsmNativeSyncPrototype.keyPreview(s2cKey)
                    + ", c2s=" + YsmNativeSyncPrototype.keyPreview(c2sKey)
                    + ", c2sDecoded=" + (c2sDecodedAt != null)
                    + ", type3Sent=" + (type3SentAt == null ? "no" : type3RawBytes + "b")
                    + ", type4=" + (type4DecodedAt == null ? "no" : requestedTokens + " token(s)/" + requestTokenBytes + "b")
                    + ", type5=" + (type5Packets == 0 ? "no" : type5Packets + " packet(s)/" + type5Bytes + "b")
                    + ", lastClient="
                    + (lastClientPacketAt == null ? "none" : "type=" + lastClientType + ", " + lastClientSummary);
        }
    }

    private record ReportNativeSession(
            byte[] s2cKey,
            @Nullable byte[] c2sKey,
            byte[] serverCacheKey,
            byte[] clientCacheKey,
            int modelCount,
            String type3Layout,
            String type3KeyMode,
            int type3PaddingBytes,
            Instant startedAt,
            @Nullable Instant c2sDecodedAt,
            @Nullable Instant type3SentAt,
            int type3RawBytes,
            int type3AttemptCount,
            int lastClientType,
            @Nullable String lastClientSummary,
            @Nullable Instant lastClientPacketAt) {
        static ReportNativeSession started(
                byte[] s2cKey,
                byte[] serverCacheKey,
                byte[] clientCacheKey,
                int modelCount,
                String type3Layout,
                String type3KeyMode,
                int type3PaddingBytes) {
            return new ReportNativeSession(
                    s2cKey,
                    null,
                    serverCacheKey,
                    clientCacheKey,
                    modelCount,
                    type3Layout,
                    type3KeyMode,
                    type3PaddingBytes,
                    Instant.now(),
                    null,
                    null,
                    0,
                    0,
                    -1,
                    null,
                    null);
        }

        ReportNativeSession withC2sKey(byte[] c2sKey, Instant decodedAt) {
            return new ReportNativeSession(
                    s2cKey,
                    c2sKey,
                    serverCacheKey,
                    clientCacheKey,
                    modelCount,
                    type3Layout,
                    type3KeyMode,
                    type3PaddingBytes,
                    startedAt,
                    decodedAt,
                    type3SentAt,
                    type3RawBytes,
                    type3AttemptCount,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        ReportNativeSession withType3Sent(int rawBytes, Instant sentAt) {
            return new ReportNativeSession(
                    s2cKey,
                    c2sKey,
                    serverCacheKey,
                    clientCacheKey,
                    modelCount,
                    type3Layout,
                    type3KeyMode,
                    type3PaddingBytes,
                    startedAt,
                    c2sDecodedAt,
                    sentAt,
                    rawBytes,
                    type3AttemptCount + 1,
                    lastClientType,
                    lastClientSummary,
                    lastClientPacketAt);
        }

        ReportNativeSession withClientPacket(YsmRawPacketCodec.PlainPacket packet) {
            return new ReportNativeSession(
                    s2cKey,
                    c2sKey,
                    serverCacheKey,
                    clientCacheKey,
                    modelCount,
                    type3Layout,
                    type3KeyMode,
                    type3PaddingBytes,
                    startedAt,
                    c2sDecodedAt,
                    type3SentAt,
                    type3RawBytes,
                    type3AttemptCount,
                    packet.type(),
                    packet.summary(),
                    Instant.now());
        }

        String describe() {
            return "reportNative=models=" + modelCount
                    + ", layout=" + type3Layout
                    + ", type3Key=" + type3KeyMode
                    + ", padding=" + type3PaddingBytes
                    + ", s2c=" + YsmNativeSyncPrototype.keyPreview(s2cKey)
                    + ", c2s=" + YsmNativeSyncPrototype.keyPreview(c2sKey)
                    + ", serverCache=" + YsmNativeSyncPrototype.keyPreview(serverCacheKey)
                    + ", clientCache=" + YsmNativeSyncPrototype.keyPreview(clientCacheKey)
                    + ", c2sDecoded=" + (c2sDecodedAt != null)
                    + ", type3Sent=" + (type3SentAt == null ? "no" : type3RawBytes + "b/" + type3AttemptCount + "pkt")
                    + ", lastClient="
                    + (lastClientPacketAt == null ? "none" : "type=" + lastClientType + ", " + lastClientSummary);
        }
    }

    private record NativeSyncState(
            String mode,
            String variant,
            int paddingBytes,
            String trigger,
            byte[] bootstrapTransportKey,
            byte[] advertisedKey,
            byte[] serverNextTransportKey,
            Instant bootstrapSentAt,
            String lastDecodedKeyName,
            int lastDecodedType,
            String lastDecodedSummary,
            byte[] lastSelectedKey,
            byte[] clientNextTransportKey,
            boolean lastDecodedUsedNextKeyTrailer,
            Instant lastDecodedAt) {
        static NativeSyncState started(YsmNativeSyncPrototype.BootstrapPacket packet, String trigger) {
            return new NativeSyncState(
                    packet.mode(),
                    packet.variant(),
                    packet.paddingBytes(),
                    trigger,
                    packet.bootstrapKey(),
                    packet.advertisedKey(),
                    packet.nextTransportKey(),
                    packet.createdAt(),
                    null,
                    -1,
                    null,
                    null,
                    null,
                    false,
                    null);
        }

        NativeSyncState withDecoded(YsmNativeSyncPrototype.DecodedPacket decoded, Instant decodedAt) {
            return new NativeSyncState(
                    mode,
                    variant,
                    paddingBytes,
                    trigger,
                    bootstrapTransportKey,
                    advertisedKey,
                    serverNextTransportKey,
                    bootstrapSentAt,
                    decoded.keyName(),
                    decoded.packet().type(),
                    decoded.packet().summary(),
                    decoded.packet().selectedKey().orElse(null),
                    decoded.nextTransportKey() == null ? clientNextTransportKey : decoded.nextTransportKey(),
                    decoded.usedNextKeyTrailer(),
                    decodedAt);
        }

        String describe() {
            String decoded = lastDecodedAt == null
                    ? "none"
                    : "type=" + lastDecodedType
                    + ", key=" + lastDecodedKeyName
                    + ", trailer=" + lastDecodedUsedNextKeyTrailer
                    + ", selected=" + YsmNativeSyncPrototype.keyPreview(lastSelectedKey)
                    + ", clientNext=" + YsmNativeSyncPrototype.keyPreview(clientNextTransportKey)
                    + ", summary=" + lastDecodedSummary;
            return "nativeBootstrap=mode=" + mode
                    + ", variant=" + variant
                    + ", padding=" + paddingBytes
                    + ", trigger=" + trigger
                    + ", bootstrapKey=" + YsmNativeSyncPrototype.keyPreview(bootstrapTransportKey)
                    + ", advertisedKey=" + YsmNativeSyncPrototype.keyPreview(advertisedKey)
                    + ", serverNext=" + YsmNativeSyncPrototype.keyPreview(serverNextTransportKey)
                    + ", lastClientRaw=" + decoded;
        }
    }

    private record StreamProbeSpec(String mode, String chainMode) {
    }

    private record ModelSelectionApplyResult(
            boolean applied,
            int compatibleViewers,
            int onlineViewers,
            boolean modelFound,
            boolean distributionPrepared,
            String message) {
    }

    private record AnimationResolution(
            String name,
            String source,
            String selectedModelId,
            String repositoryModelId,
            String textureId,
            YsmModelProfile profile) {
    }

    private record AppliedModelState(String modelId, String textureId, boolean disabled, Instant updatedAt) {
    }

    private record SavedModelState(
            String playerName,
            String modelId,
            String textureId,
            boolean disabled,
            String animationName,
            Map<Integer, Map<String, Float>> molangStorage,
            Instant updatedAt) {
        private SavedModelState {
            animationName = animationName == null ? "" : animationName;
            molangStorage = deepCopyMolangStorage(molangStorage);
        }
    }

    private record ReplayPacket(Path file, byte[] body) {
    }
}
