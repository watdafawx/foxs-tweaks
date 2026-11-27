package dev.watdafawx.foxstweaks.hologram.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.hologram.HologramData;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.media.FfmpegLocator;
import dev.watdafawx.foxstweaks.media.MediaConfig;
import dev.watdafawx.foxstweaks.media.VideoConverter;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Server half of the asset payload: uploads, downloads, the library listing and reference counts.
 *
 * <p>Everything here runs on the server thread except the two slow steps of an upload, which go to
 * {@link #WORKER}: sanitising (decode, rescale, re-encode every frame) and, for a video, the ffmpeg conversion.
 * Their result is handed back with {@code server.execute}, where the quota checks and the index write happen,
 * so the store itself stays single-threaded.
 */
@EventBusSubscriber(modid = "foxstweaks")
public final class AssetNetwork {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long TIMEOUT = 30000;
    private static final int LISTING_BYTES = 28000;
    /** A video's input may be larger than the asset limit: the canonical frames are what that limit is for. */
    private static final int VIDEO_INPUT_BYTES = AssetLimits.MAX_BYTES;
    /** A .bbmodel carries its textures as base64, so the input is larger than the stored model. */
    private static final int MODEL_INPUT_BYTES = AssetLimits.MAX_BYTES;

    /** One worker, a short queue: uploads are rare and a full queue is answered with a refusal, not a backlog. */
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), r -> {
                Thread t = new Thread(r, "Hologram asset import");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        WORKER.allowCoreThreadTimeOut(true);
    }

    private static AssetStore store;
    private static MinecraftServer server;
    private static final Map<UUID, Upload> UPLOADS = new HashMap<>();
    private static final Map<UUID, Budget> BUDGETS = new HashMap<>();
    private static final Map<UUID, Download> DOWNLOADS = new HashMap<>();
    /** Players whose finished upload is being processed on the worker; one at a time each. */
    private static final Map<UUID, Long> PROCESSING = new HashMap<>();
    /** null = not looked up yet; looked up once, off the server thread, since it starts a process. */
    @Nullable
    private static volatile Boolean ffmpegFound;

    private static final class Upload {
        final String name;
        final int total;
        final int mode;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        int sequence;
        long touched = System.currentTimeMillis();

        Upload(String name, int total, int mode) {
            this.name = name;
            this.total = total;
            this.mode = mode;
        }
    }

    private record Download(String id, byte[] bytes, long touched) {
    }

    private static final class Budget {
        long second;
        int packets;
        long begin;
    }

    private AssetNetwork() {
    }

    public static AssetStore store(MinecraftServer s) throws IOException {
        if (server != s || store == null) {
            store = new AssetStore(AssetStore.path(s));
            server = s;
        }
        return store;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        // 2: upload modes (video), progress, the library listing.
        event.registrar("2").optional().playBidirectional(AssetPayload.TYPE, AssetPayload.CODEC, (p, c) -> c.enqueueWork(() -> {
            if (c.player() instanceof ServerPlayer sp)
                handle(sp, p);
            else if (FMLEnvironment.dist.isClient())
                dev.watdafawx.foxstweaks.client.asset.HologramAssets.accept(p);
        }));
    }

    public static boolean mayUpload(ServerPlayer player) {
        return ServerConfig.assetUploadEveryone() || player.hasPermissions(2);
    }

    /** Operators may delete anything; anyone else only what they uploaded, and only while they may still upload. */
    public static boolean mayDelete(ServerPlayer player, AssetStore.Metadata m) {
        return player.hasPermissions(2) || (m.owner().equals(player.getUUID()) && mayUpload(player));
    }

    /**
     * The web library's Delete. Refused while saved holograms use the asset unless {@code force}: a deleted asset
     * turns every layer showing it into an empty frame on every client, so the page asks first.
     */
    private static void delete(ServerPlayer player, String id, boolean force) throws IOException {
        if (!AssetFrames.validId(id))
            throw new IOException("Invalid asset id");
        AssetStore s = store(player.server);
        AssetStore.Metadata m = s.info(id);
        if (m == null)
            throw new IOException("That picture is already gone");
        if (!mayDelete(player, m))
            throw new IOException("Only operators or the player who uploaded it may delete this picture");
        if (m.references() > 0 && !force)
            throw new IOException("Used by " + m.references() + " hologram layer(s)");
        s.delete(id);
        LOG.info("Hologram asset {} ({}) deleted by {}{}", id.substring(0, 8), m.name(), player.getGameProfile().getName(),
                m.references() > 0 ? " while " + m.references() + " layer(s) used it" : "");
        reply(player, true, id, AssetPayload.DELETE);
    }

    private static void rename(ServerPlayer player, String payload) throws IOException {
        int sep = payload.indexOf('\n');
        if (sep <= 0)
            throw new IOException("Invalid rename request");
        String id = payload.substring(0, sep);
        String newName = payload.substring(sep + 1);
        if (!AssetFrames.validId(id))
            throw new IOException("Invalid asset id");
        AssetStore s = store(player.server);
        AssetStore.Metadata m = s.info(id);
        if (m == null)
            throw new IOException("That asset does not exist");
        if (!mayDelete(player, m))
            throw new IOException("Only operators or the uploader may move this asset");
        s.rename(id, newName);
        reply(player, true, id, AssetPayload.RENAME);
    }

    private static void reply(ServerPlayer p, boolean ok, String message, int operation) {
        if (p.connection.hasChannel(AssetPayload.TYPE))
            PacketDistributor.sendToPlayer(p, AssetPayload.request(AssetPayload.RESULT, ok ? 1 : 0, operation, clip(message)));
    }

    private static void progress(ServerPlayer p, int percent, String stage) {
        if (p.connection.hasChannel(AssetPayload.TYPE))
            PacketDistributor.sendToPlayer(p, AssetPayload.request(AssetPayload.PROGRESS, percent, 0, clip(stage)));
    }

    private static String clip(String message) {
        return message.length() > 256 ? message.substring(0, 256) : message;
    }

    public static void handle(ServerPlayer player, AssetPayload p) {
        if (!player.connection.hasChannel(AssetPayload.TYPE))
            return;
        long now = System.currentTimeMillis();
        UUID id = player.getUUID();
        Budget budget = BUDGETS.computeIfAbsent(id, k -> new Budget());
        if (now / 1000 != budget.second) {
            budget.second = now / 1000;
            budget.packets = 0;
        }
        // 400 chunks/sec (~11 MB/s) allows fast LAN/local transfers of large 3D models without stalling.
        if (++budget.packets > 400) {
            if (p.kind() == AssetPayload.CHUNK || p.kind() == AssetPayload.BEGIN)
                UPLOADS.remove(id);
            return;
        }

        try {
            if (p.kind() == AssetPayload.ABORT) {
                UPLOADS.remove(id);
                DOWNLOADS.remove(id);
                return;
            }
            if (p.kind() == AssetPayload.GET) {
                download(player, p, now);
                return;
            }
            if (p.kind() == AssetPayload.LIST) {
                listing(player, Math.max(0, p.number()));
                return;
            }
            if (p.kind() == AssetPayload.DELETE) {
                delete(player, p.text(), p.number() == 1);
                return;
            }
            if (p.kind() == AssetPayload.RENAME) {
                rename(player, p.text());
                return;
            }

            if (!mayUpload(player))
                throw new IOException("Asset uploads require operator permission");
            if (p.kind() == AssetPayload.BEGIN) {
                begin(player, p, budget, now);
                return;
            }
            if (p.kind() != AssetPayload.CHUNK)
                throw new IOException("Invalid asset operation");

            Upload u = UPLOADS.get(id);
            if (u == null || now - u.touched > TIMEOUT)
                throw new IOException("Upload expired or not started");
            if (p.number() != u.sequence++ || p.bytes().length == 0 || p.bytes().length > AssetPayload.CHUNK_BYTES
                    || u.data.size() + p.bytes().length > u.total)
                throw new IOException("Invalid upload sequence or size");
            u.touched = now;
            u.data.write(p.bytes());
            if (u.data.size() == u.total) {
                UPLOADS.remove(id);
                process(player, u);
            }
        } catch (IOException | RuntimeException e) {
            UPLOADS.remove(id);
            reply(player, false, safeMessage(player, e), p.kind());
        }
    }

    private static void download(ServerPlayer player, AssetPayload p, long now) throws IOException {
        UUID id = player.getUUID();
        if (!AssetFrames.validId(p.text()) || p.number() < 0 || p.number() % AssetPayload.CHUNK_BYTES != 0)
            throw new IOException("Invalid asset request");
        Download d = DOWNLOADS.get(id);
        if (d == null || !d.id.equals(p.text())) {
            if (p.number() != 0)
                throw new IOException("Download must begin at zero");
            d = new Download(p.text(), store(player.server).read(p.text()), now);
            DOWNLOADS.put(id, d);
        }
        if (p.number() >= d.bytes.length)
            throw new IOException("Invalid download offset");
        int end = Math.min(d.bytes.length, p.number() + AssetPayload.CHUNK_BYTES);
        PacketDistributor.sendToPlayer(player, new AssetPayload(AssetPayload.DATA, p.number(), d.bytes.length, d.id,
                Arrays.copyOfRange(d.bytes, p.number(), end)));
        if (end == d.bytes.length)
            DOWNLOADS.remove(id);
        else
            DOWNLOADS.put(id, new Download(d.id, d.bytes, now));
    }

    private static void begin(ServerPlayer player, AssetPayload p, Budget budget, long now) throws IOException {
        UUID id = player.getUUID();
        if (now - budget.begin < 150)
            throw new IOException("Upload rate limit: wait a moment");
        budget.begin = now;
        if (UPLOADS.containsKey(id) || PROCESSING.containsKey(id))
            throw new IOException("An upload is already in progress");
        int mode = p.number();
        if (mode != AssetPayload.MODE_IMAGE && mode != AssetPayload.MODE_VIDEO && mode != AssetPayload.MODE_BUNDLE
                && mode != AssetPayload.MODE_MODEL)
            throw new IOException("Unknown upload mode");
        if (mode == AssetPayload.MODE_VIDEO && !ServerConfig.mediaFfmpegEnabled())
            throw new IOException("Video import is switched off on this server (mediaFfmpegEnabled)");
        if (mode == AssetPayload.MODE_VIDEO && Boolean.FALSE.equals(ffmpegFound))
            throw new IOException("This server has no ffmpeg; convert the video in the browser instead");
        int cap = mode == AssetPayload.MODE_IMAGE ? AssetLimits.current().fileBytes()
                : mode == AssetPayload.MODE_MODEL ? MODEL_INPUT_BYTES : VIDEO_INPUT_BYTES;
        if (p.total() < 8 || p.total() > cap)
            throw new IOException("File byte limit exceeded (" + cap + " bytes)");
        UPLOADS.put(id, new Upload(p.text(), p.total(), mode));
    }

    /** The upload is complete: sanitise (and convert) on the worker, commit back on the server thread. */
    private static void process(ServerPlayer player, Upload upload) {
        UUID id = player.getUUID();
        MinecraftServer srv = player.server;
        AssetLimits limits = AssetLimits.current();
        byte[] bytes = upload.data.toByteArray();
        boolean video = upload.mode == AssetPayload.MODE_VIDEO;
        var settings = video ? MediaConfig.read() : null;
        PROCESSING.put(id, System.currentTimeMillis());
        progress(player, 0, video ? "converting video" : upload.mode == AssetPayload.MODE_MODEL ? "reading model" : "processing image");
        try {
            WORKER.execute(() -> {
                AssetStore.Prepared prepared = null;
                String error = null;
                try {
                    if (video) {
                        byte[] bundle = convertVideo(bytes, upload.name, limits, settings, percent ->
                                srv.execute(() -> withPlayer(srv, id, p -> progress(p, percent, "converting video"))));
                        srv.execute(() -> withPlayer(srv, id, p -> progress(p, 90, "processing frames")));
                        prepared = AssetStore.prepare(bundle, limits, VIDEO_INPUT_BYTES);
                    } else if (upload.mode == AssetPayload.MODE_BUNDLE) {
                        if (bytes.length < 4 || java.nio.ByteBuffer.wrap(bytes).getInt() != AssetFrames.MAGIC)
                            throw new IOException("A frame bundle upload must be an FTAS bundle");
                        // Re-encoding at the server's size limits usually shrinks the browser's frames.
                        prepared = AssetStore.prepare(bytes, limits, VIDEO_INPUT_BYTES);
                    } else if (upload.mode == AssetPayload.MODE_MODEL) {
                        prepared = AssetStore.prepareModel(bytes);
                    } else {
                        prepared = AssetStore.prepare(bytes, limits);
                    }
                } catch (IOException | RuntimeException e) {
                    error = Objects.toString(e.getMessage(), "Asset import failed");
                }
                AssetStore.Prepared done = prepared;
                String failure = error;
                srv.execute(() -> finish(srv, id, upload.name, done, failure, limits));
            });
        } catch (RejectedExecutionException e) {
            PROCESSING.remove(id);
            reply(player, false, "The server is busy importing other assets; try again in a moment", AssetPayload.CHUNK);
        }
    }

    private static void finish(MinecraftServer srv, UUID id, String name, @Nullable AssetStore.Prepared prepared,
                               @Nullable String error, AssetLimits limits) {
        PROCESSING.remove(id);
        ServerPlayer player = srv.getPlayerList().getPlayer(id);
        if (player == null)
            return; // logged out meanwhile: nothing is stored, so nobody owns a half-finished upload
        if (prepared == null) {
            reply(player, false, error == null ? "Asset import failed" : error, AssetPayload.CHUNK);
            return;
        }
        try {
            var meta = store(srv).commit(id, name, prepared, limits);
            reply(player, true, meta.id(), AssetPayload.CHUNK);
        } catch (IOException | RuntimeException e) {
            reply(player, false, safeMessage(player, e), AssetPayload.CHUNK);
        }
    }

    private static void withPlayer(MinecraftServer srv, UUID id, java.util.function.Consumer<ServerPlayer> action) {
        ServerPlayer p = srv.getPlayerList().getPlayer(id);
        if (p != null)
            action.accept(p);
    }

    /**
     * Worker thread. Video bytes -> ffmpeg JPEG frames -> one FTAS bundle (the format the store already accepts).
     * Everything is written under a private temp folder that is always deleted afterwards.
     */
    private static byte[] convertVideo(byte[] bytes, String name, AssetLimits limits,
                                       dev.watdafawx.foxstweaks.media.MediaSettings settings,
                                       java.util.function.IntConsumer progress) throws IOException {
        Path dir = Files.createTempDirectory("foxstweaks-video");
        try {
            Path input = dir.resolve("input.bin");
            Files.write(input, bytes);
            Path out = Files.createDirectories(dir.resolve("frames"));
            progress.accept(10);
            var converter = new VideoConverter(new FfmpegLocator(settings.ffmpegPath()), settings);
            int dimension = Math.max(8, Math.min(limits.dimension(), 4096));
            var conversion = converter.convert(input, out, new VideoConverter.Limits(dimension, limits.fps(),
                    limits.seconds(), 64L * 1024 * 1024, limits.fps() * limits.seconds()));
            if (!conversion.ok())
                throw new IOException(conversion.error());
            ffmpegFound = Boolean.TRUE;
            progress.accept(70);
            List<byte[]> frames = new ArrayList<>();
            List<Integer> delays = new ArrayList<>();
            int delay = (int) Math.max(1, Math.round(1000.0 / Math.max(1.0, conversion.fps())));
            long total = 8;
            for (Path frame : conversion.frames()) {
                byte[] data = Files.readAllBytes(frame);
                total += data.length + 8L;
                if (total > VIDEO_INPUT_BYTES)
                    throw new IOException("The converted video is too large; use a shorter or smaller clip");
                frames.add(data);
                delays.add(delay);
            }
            if (frames.isEmpty())
                throw new IOException("ffmpeg produced no frames for " + name);
            return new AssetFrames(frames, delays, conversion.width(), conversion.height()).encode();
        } finally {
            deleteTree(dir);
        }
    }

    private static void deleteTree(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    /** Looks ffmpeg up once per server, off the server thread. */
    private static void probeFfmpeg() {
        if (ffmpegFound != null || !ServerConfig.mediaFfmpegEnabled())
            return;
        String path = MediaConfig.read().ffmpegPath();
        Thread.ofVirtual().name("foxstweaks-ffmpeg-probe").start(() -> {
            boolean found = new FfmpegLocator(path).find().isPresent();
            ffmpegFound = found;
            LOG.info("Hologram video import: ffmpeg {}", found ? "found" : "not found (browsers convert videos themselves)");
        });
    }

    /** null while unknown. */
    @Nullable
    public static Boolean ffmpegAvailable() {
        return ServerConfig.mediaFfmpegEnabled() ? ffmpegFound : Boolean.FALSE;
    }

    private static String safeMessage(ServerPlayer player, Throwable e) {
        // File system errors carry absolute server paths in their message; never send those to a client.
        if (e instanceof java.nio.file.FileSystemException) {
            LOG.warn("Asset operation failed for {}", player.getName().getString(), e);
            return "Asset storage error";
        }
        return Objects.toString(e.getMessage(), "Asset operation failed");
    }

    // ---------------------------------------------------------------- library listing

    /**
     * One page of the library as UTF-8 JSON in the payload's bytes: {@code {canUpload, video, maxBytes, count,
     * entries: [...]}}. Pages are cut by size, so a client asks again from {@code offset + entries.length}.
     */
    private static void listing(ServerPlayer player, int offset) throws IOException {
        AssetStore s = store(player.server);
        List<AssetStore.Metadata> all = new ArrayList<>(s.list());
        all.sort(Comparator.comparingLong(AssetStore.Metadata::created).reversed());
        JsonObject page = new JsonObject();
        page.addProperty("canUpload", mayUpload(player));
        Boolean video = ffmpegAvailable();
        if (video != null)
            page.addProperty("video", video);
        AssetLimits lim = AssetLimits.current();
        page.addProperty("maxBytes", lim.fileBytes());
        page.addProperty("dimension", lim.dimension());
        page.addProperty("fps", lim.fps());
        page.addProperty("seconds", lim.seconds());
        page.addProperty("count", all.size());
        page.addProperty("offset", offset);
        JsonArray entries = new JsonArray();
        int used = 200;
        for (int i = offset; i < all.size(); i++) {
            JsonObject e = entry(player, all.get(i));
            int size = e.toString().length() + 1;
            if (used + size > LISTING_BYTES && !entries.isEmpty())
                break;
            used += size;
            entries.add(e);
        }
        page.add("entries", entries);
        byte[] json = page.toString().getBytes(StandardCharsets.UTF_8);
        PacketDistributor.sendToPlayer(player, new AssetPayload(AssetPayload.LISTING, offset, all.size(), "",
                json.length > AssetPayload.CHUNK_BYTES ? Arrays.copyOf(json, 0) : json));
    }

    private static JsonObject entry(ServerPlayer player, AssetStore.Metadata m) {
        MinecraftServer srv = player.server;
        UUID viewer = player.getUUID();
        JsonObject e = new JsonObject();
        e.addProperty("id", m.id());
        e.addProperty("name", m.name().length() > 64 ? m.name().substring(0, 64) : m.name());
        e.addProperty("type", m.type());
        e.addProperty("width", m.width());
        e.addProperty("height", m.height());
        e.addProperty("frames", m.frameCount());
        long duration = 0;
        for (int d : m.delays())
            duration += d;
        e.addProperty("durationMs", duration);
        e.addProperty("bytes", m.byteSize());
        e.addProperty("used", m.references());
        e.addProperty("mine", m.owner().equals(viewer));
        e.addProperty("canDelete", mayDelete(player, m));
        e.addProperty("owner", ownerName(srv, m.owner()));
        e.addProperty("created", m.created());
        return e;
    }

    private static String ownerName(MinecraftServer srv, UUID owner) {
        var cache = srv.getProfileCache();
        if (cache == null)
            return "";
        return cache.get(owner).map(com.mojang.authlib.GameProfile::getName).orElse("");
    }

    // ---------------------------------------------------------------- references from hologram layers

    /** Server thread. True when {@code id} is in this world's store. */
    public static boolean known(MinecraftServer srv, String id) {
        if (!AssetFrames.validId(id))
            return false;
        try {
            return store(srv).info(id) != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** How many times each asset is used by {@code spec}'s image layers. */
    public static Map<String, Integer> imageUses(@Nullable HologramSpec spec) {
        Map<String, Integer> uses = new HashMap<>();
        if (spec == null)
            return uses;
        for (HologramLayer layer : spec.layers()) {
            if ((layer.kind() == HologramLayer.Kind.IMAGE || layer.kind() == HologramLayer.Kind.MODEL) && AssetFrames.validId(layer.id()))
                uses.merge(layer.id(), 1, Integer::sum);
        }
        return uses;
    }

    /**
     * A hologram was saved ({@code next}) over {@code previous}, or removed ({@code next == null}): retain what it
     * newly uses, release what it stopped using. Live previews never get here, so editing does not churn counts.
     */
    public static void track(MinecraftServer srv, @Nullable HologramSpec previous, @Nullable HologramSpec next) {
        Map<String, Integer> before = imageUses(previous);
        Map<String, Integer> after = imageUses(next);
        if (before.isEmpty() && after.isEmpty())
            return;
        try {
            AssetStore s = store(srv);
            for (var e : after.entrySet()) {
                int delta = e.getValue() - before.getOrDefault(e.getKey(), 0);
                for (int i = 0; i < delta && s.info(e.getKey()) != null; i++)
                    s.retain(e.getKey());
            }
            for (var e : before.entrySet()) {
                int delta = e.getValue() - after.getOrDefault(e.getKey(), 0);
                for (int i = 0; i < delta && s.info(e.getKey()) != null; i++)
                    s.release(e.getKey());
            }
        } catch (IOException e) {
            LOG.warn("Hologram assets: could not update reference counts", e);
        }
    }

    @SubscribeEvent
    public static void started(ServerStartedEvent e) {
        probeFfmpeg();
        Map<String, Integer> used = new HashMap<>();
        for (ServerLevel level : e.getServer().getAllLevels()) {
            for (HologramSpec spec : HologramData.get(level).all())
                imageUses(spec).forEach((k, v) -> used.merge(k, v, Integer::sum));
        }
        for (HologramSpec spec : HologramData.attached(e.getServer()).all())
            imageUses(spec).forEach((k, v) -> used.merge(k, v, Integer::sum));
        try {
            store(e.getServer()).reconcile(used);
        } catch (IOException ex) {
            LOG.warn("Hologram assets: could not reconcile reference counts", ex);
        }
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post e) {
        long now = System.currentTimeMillis();
        UPLOADS.values().removeIf(u -> now - u.touched > TIMEOUT);
        DOWNLOADS.values().removeIf(d -> now - d.touched > TIMEOUT);
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        var id = e.getEntity().getUUID();
        UPLOADS.remove(id);
        DOWNLOADS.remove(id);
        BUDGETS.remove(id);
    }

    @SubscribeEvent
    public static void stop(ServerStoppedEvent e) {
        UPLOADS.clear();
        DOWNLOADS.clear();
        BUDGETS.clear();
        PROCESSING.clear();
        store = null;
        server = null;
        ffmpegFound = null;
    }

    // ---------------------------------------------------------------- commands

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("foxstweaks").then(Commands.literal("asset")
                .then(Commands.literal("list").executes(c -> command(c.getSource(), "list", "", false)))
                .then(Commands.literal("info").then(Commands.argument("id", StringArgumentType.word())
                        .executes(c -> command(c.getSource(), "info", StringArgumentType.getString(c, "id"), false))))
                .then(Commands.literal("delete").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(c -> command(c.getSource(), "delete", StringArgumentType.getString(c, "id"), false))
                                .then(Commands.literal("force")
                                        .executes(c -> command(c.getSource(), "delete", StringArgumentType.getString(c, "id"), true)))))));
    }

    private static int command(CommandSourceStack source, String action, String id, boolean force) {
        try {
            var s = store(source.getServer());
            if (action.equals("list")) {
                source.sendSuccess(() -> Component.literal("Assets: " + s.list().size() + "; world " + s.bytes() + "/"
                        + AssetLimits.current().worldBytes() + " bytes"), false);
                if (source.getEntity() instanceof ServerPlayer p)
                    source.sendSuccess(() -> Component.literal("Your assets: " + s.count(p.getUUID()) + "/"
                            + AssetLimits.current().perPlayer()), false);
                for (var m : s.list())
                    source.sendSuccess(() -> Component.literal(m.id() + " " + m.name() + " " + m.byteSize()
                            + " bytes, used by " + m.references()), false);
            } else if (action.equals("info")) {
                var m = s.info(id);
                if (m == null)
                    throw new IOException("Unknown asset");
                source.sendSuccess(() -> Component.literal(m.toString()), false);
            } else {
                var m = s.info(id);
                if (m == null)
                    throw new IOException("Unknown asset");
                // A deleted asset turns every layer that shows it into an empty frame, on every client.
                if (m.references() > 0 && !force)
                    throw new IOException("Asset is used by " + m.references()
                            + " hologram layer(s); add 'force' to delete it anyway");
                s.delete(id);
                source.sendSuccess(() -> Component.literal("Deleted " + id), true);
            }
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }
}
