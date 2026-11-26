package dev.watdafawx.foxstweaks.client.asset;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.watdafawx.foxstweaks.hologram.HologramImage;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.model.ModelData;
import dev.watdafawx.foxstweaks.hologram.asset.AssetLimits;
import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;
import dev.watdafawx.foxstweaks.hologram.asset.AssetStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import com.mojang.logging.LogUtils;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Render-thread API for asset textures, plus the client half of uploads and the library listing.
 *
 * <p>Compressed frames stay in RAM; one RGBA native image + GPU texture per drawn asset. Decoding runs on a
 * bounded worker and the texture upload on the client thread. 30 s idle eviction; 32 MiB compressed RAM,
 * 200 MiB disk LRU. At 256²: 256 KiB native + 256 KiB VRAM per asset, plus one transient 256 KiB decode, not
 * 37.5 MiB for 150 frames.
 */
@EventBusSubscriber(modid = "foxstweaks", value = Dist.CLIENT)
public final class HologramAssets {
    private static final long DISK_LIMIT = 200L * 1024 * 1024;
    private static final long RAM_LIMIT = 32L * 1024 * 1024;
    /** Wait for the result once everything is sent: a video conversion may take up to the server's timeout. */
    private static final long PROCESS_TIMEOUT = 11 * 60 * 1000;
    private static final int THUMB_SIZE = 96;

    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), r -> {
                Thread t = new Thread(r, "Hologram assets");
                t.setDaemon(true);
                return t;
            }, new ThreadPoolExecutor.AbortPolicy());

    static {
        WORKER.allowCoreThreadTimeOut(true);
        HologramImage.aspectLookup = HologramAssets::aspect;
    }

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> LOGGED = new HashSet<>();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, Long> RETRY = new HashMap<>();
    private static final ArrayDeque<String> REQUESTS = new ArrayDeque<>();
    /** Aspect ratios stay known after an entry is evicted, so an image's height does not jump. */
    private static final Map<String, Double> ASPECTS = new HashMap<>();
    private static final Map<String, List<Consumer<AssetFrames>>> WAITING = new HashMap<>();
    private static String downloading = "";
    private static ByteArrayOutputStream incoming;
    private static int expected;
    private static long touched;
    private static long generation;

    @Nullable
    private static Upload upload;
    @Nullable
    private static Consumer<JsonObject> listingCallback;
    /** Gets null when the delete went through, else the server's reason. */
    @Nullable
    private static Consumer<String> deleteCallback;
    public static String lastResult = "";
    public static boolean lastSuccess;

    /** Callbacks of an upload; always called on the client thread. */
    public interface UploadListener {
        void progress(int percent, String stage);

        void done(boolean ok, String message);
    }

    private static final class Upload {
        final byte[] bytes;
        final UploadListener listener;
        int offset;
        int sequence;
        long touched = System.currentTimeMillis();

        Upload(byte[] bytes, UploadListener listener) {
            this.bytes = bytes;
            this.listener = listener;
        }

        boolean sent() {
            return this.offset >= this.bytes.length;
        }
    }

    private static final class Entry {
        AssetFrames frames;
        /** Set instead of {@link #frames} when the asset is a 3D model. */
        ModelData model;
        DynamicTexture texture;
        ResourceLocation location;
        int frame = -1;
        boolean busy;
        long last = System.currentTimeMillis();
        long bytes;
        long diskTouched;
    }

    private HologramAssets() {
    }

    public static Path cachePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks/asset-cache");
    }

    public static boolean available() {
        var c = Minecraft.getInstance().getConnection();
        return c != null && c.hasChannel(AssetPayload.TYPE);
    }

    // ---------------------------------------------------------------- textures

    /** The texture showing {@code hash} at {@code timeSeconds} into it, or null while it loads. */
    @Nullable
    public static ResourceLocation texture(String hash, double timeSeconds) {
        Entry e = entry(hash);
        if (e == null || e.frames == null)
            return null;

        if (e.last - e.diskTouched > 10000) {
            e.diskTouched = e.last;
            submit(() -> {
                try {
                    Files.setLastModifiedTime(cachePath().resolve(hash), FileTime.fromMillis(System.currentTimeMillis()));
                } catch (IOException ignored) {
                }
            }, () -> {
            });
        }
        int frame = e.frames.frame(timeSeconds);
        if (frame != e.frame && !e.busy)
            decode(hash, e, frame);
        return e.location;
    }

    /** The frames of {@code hash}, starting a load if needed; null while it loads. */
    @Nullable
    public static AssetFrames frames(String hash) {
        Entry e = entry(hash);
        return e == null ? null : e.frames;
    }

    /** Animation length in seconds, 0 for a still image or while unknown. */
    public static double duration(String hash) {
        AssetFrames frames = frames(hash);
        return frames == null || frames.frames().size() < 2 ? 0.0 : frames.durationSeconds();
    }

    /** Height / width, NaN while unknown. Safe from any thread (the integrated server asks for reach boxes). */
    public static double aspect(String hash) {
        synchronized (ASPECTS) {
            Double known = ASPECTS.get(hash);
            return known == null ? Double.NaN : known;
        }
    }

    /** Calls {@code action} on the client thread once {@code hash} is loaded (at once when it already is). */
    public static void whenLoaded(String hash, Consumer<AssetFrames> action) {
        Entry e = entry(hash);
        if (e == null)
            return;
        if (e.frames != null) {
            action.accept(e.frames);
            return;
        }
        WAITING.computeIfAbsent(hash, k -> new ArrayList<>()).add(action);
    }

    @Nullable
    private static Entry entry(String hash) {
        if (!AssetFrames.validId(hash))
            return null;
        Entry e = ENTRIES.get(hash);
        if (e == null) {
            if (ENTRIES.size() >= 64 || RETRY.getOrDefault(hash, 0L) > System.currentTimeMillis())
                return null;
            e = new Entry();
            ENTRIES.put(hash, e);
            load(hash, e);
        }
        e.last = System.currentTimeMillis();
        return e;
    }

    /**
     * The decoded 3D model of {@code hash}, starting a load if needed; null while it loads (or if the asset is
     * not a model). The caller owns nothing: the entry is evicted like any other.
     */
    @Nullable
    public static ModelData model(String hash) {
        Entry e = entry(hash);
        return e == null ? null : e.model;
    }

    private static void loadedModel(Entry entry, ModelData model, long bytes) {
        entry.model = model;
        entry.bytes = bytes;
    }

    private static void loaded(String hash, Entry entry, AssetFrames frames, long bytes) {
        entry.frames = frames;
        entry.bytes = bytes;
        if (frames.width() > 0) {
            synchronized (ASPECTS) {
                ASPECTS.put(hash, frames.height() / (double) frames.width());
            }
        }
        List<Consumer<AssetFrames>> waiting = WAITING.remove(hash);
        if (waiting != null)
            waiting.forEach(action -> action.accept(frames));
    }

    private static void decode(String hash, Entry entry, int frame) {
        entry.busy = true;
        long gen = generation;
        submit(() -> {
            NativeImage image = null;
            try {
                image = read(entry.frames.frames().get(frame));
                NativeImage decoded = image;
                Minecraft.getInstance().execute(() -> {
                    if (gen != generation || ENTRIES.get(hash) != entry) {
                        decoded.close();
                        return;
                    }
                    try {
                        boolean resized = entry.texture != null && (entry.texture.getPixels() == null
                                || entry.texture.getPixels().getWidth() != decoded.getWidth()
                                || entry.texture.getPixels().getHeight() != decoded.getHeight());
                        if (entry.texture == null || resized) {
                            if (entry.location != null)
                                Minecraft.getInstance().getTextureManager().release(entry.location);
                            entry.texture = new DynamicTexture(new NativeImage(decoded.getWidth(), decoded.getHeight(), false));
                            entry.location = Minecraft.getInstance().getTextureManager().register("hologram_asset", entry.texture);
                        }
                        entry.texture.getPixels().copyFrom(decoded);
                        entry.texture.upload();
                        entry.frame = frame;
                    } finally {
                        decoded.close();
                        entry.busy = false;
                    }
                });
            } catch (Throwable t) {
                if (image != null)
                    image.close();
                failed(hash, "frame " + frame, t);
                Minecraft.getInstance().execute(() -> entry.busy = false);
            }
        }, () -> entry.busy = false);
    }

    /**
     * Worker thread. PNG frames go straight to NativeImage; JPEG frames (video imports keep JPEG so a clip fits the
     * size limit) are decoded with Minecraft's own STB, since NativeImage.read only takes PNG. Not ImageIO: its JPEG
     * decoder failed inside the game (an Error, so the frame and the thumbnail just never appeared) while working in
     * a plain JVM.
     */
    private static NativeImage read(byte[] bytes) throws IOException {
        if (!AssetFrames.isJpeg(bytes))
            return NativeImage.read(new ByteArrayInputStream(bytes));

        ByteBuffer input = MemoryUtil.memAlloc(bytes.length);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(bytes).flip();
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);
            ByteBuffer rgba = STBImage.stbi_load_from_memory(input, w, h, channels, 4);
            if (rgba == null)
                throw new IOException("Unreadable JPEG frame: " + STBImage.stbi_failure_reason());
            try {
                int width = w.get(0);
                int height = h.get(0);
                NativeImage image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
                // STB gives R,G,B,A bytes; read little-endian that is the ABGR int NativeImage stores.
                IntBuffer pixels = rgba.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++)
                        image.setPixelRGBA(x, y, pixels.get(y * width + x) | 0xFF000000);
                }
                return image;
            } finally {
                STBImage.stbi_image_free(rgba);
            }
        } finally {
            MemoryUtil.memFree(input);
        }
    }

    /** Logs the first decode failure of each asset, so a broken asset shows up in the log instead of just blank. */
    private static void failed(String hash, String what, Throwable t) {
        synchronized (LOGGED) {
            if (LOGGED.add(hash))
                LOGGER.warn("Hologram asset {}: {} failed", hash.substring(0, 8), what, t);
        }
    }

    /**
     * A {@value #THUMB_SIZE} px PNG of the first frame for the web library, or {@code null} when it cannot be made.
     * Worker thread, then client. Same decoder as the in-world texture, so a thumbnail shows exactly when the
     * hologram can.
     */
    public static void thumbnail(String hash, Consumer<byte[]> done) {
        whenLoaded(hash, frames -> submit(() -> {
            byte[] png = null;
            try (NativeImage source = read(frames.frames().getFirst())) {
                double scale = Math.min(1.0, THUMB_SIZE / (double) Math.max(source.getWidth(), source.getHeight()));
                int w = Math.max(1, (int) Math.round(source.getWidth() * scale));
                int h = Math.max(1, (int) Math.round(source.getHeight() * scale));
                try (NativeImage thumb = new NativeImage(NativeImage.Format.RGBA, w, h, false)) {
                    source.resizeSubRectTo(0, 0, source.getWidth(), source.getHeight(), thumb);
                    png = thumb.asByteArray();
                }
            } catch (Throwable t) {
                failed(hash, "thumbnail", t);
            }
            byte[] result = png;
            Minecraft.getInstance().execute(() -> done.accept(result));
        }, () -> Minecraft.getInstance().execute(() -> done.accept(null))));
    }

    private static void submit(Runnable work, Runnable rejected) {
        try {
            WORKER.execute(work);
        } catch (RejectedExecutionException e) {
            rejected.run();
        }
    }

    private static void load(String hash, Entry entry) {
        long gen = generation;
        submit(() -> {
            try {
                Path path = cachePath().resolve(hash);
                if (!Files.exists(path))
                    throw new IOException("Not cached");
                if (Files.size(path) > AssetLimits.MAX_BYTES) {
                    Files.delete(path);
                    throw new IOException("Cache oversized");
                }
                byte[] bytes = Files.readAllBytes(path);
                if (!AssetFrames.hash(bytes).equals(hash)) {
                    Files.delete(path);
                    throw new IOException("Cache hash mismatch");
                }
                ModelData model = ModelData.isModel(bytes) ? ModelData.decode(bytes) : null;
                AssetFrames frames = model == null ? AssetFrames.bundle(bytes) : null;
                Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));
                Minecraft.getInstance().execute(() -> {
                    if (gen == generation && ENTRIES.get(hash) == entry) {
                        if (model != null)
                            loadedModel(entry, model, bytes.length);
                        else
                            loaded(hash, entry, frames, bytes.length);
                    }
                });
            } catch (IOException ex) {
                Minecraft.getInstance().execute(() -> {
                    if (gen == generation && ENTRIES.get(hash) == entry && !REQUESTS.contains(hash))
                        REQUESTS.add(hash);
                });
            }
        }, () -> ENTRIES.remove(hash));
    }

    // ---------------------------------------------------------------- incoming payloads

    public static void accept(AssetPayload p) {
        switch (p.kind()) {
            case AssetPayload.RESULT -> result(p);
            case AssetPayload.PROGRESS -> {
                if (upload != null) {
                    upload.touched = System.currentTimeMillis();
                    upload.listener.progress(p.number(), p.text());
                }
            }
            case AssetPayload.LISTING -> listing(p);
            case AssetPayload.DATA -> data(p);
            default -> {
            }
        }
    }

    private static void result(AssetPayload p) {
        if (p.total() == AssetPayload.GET) {
            failDownload();
            return;
        }
        if (p.total() == AssetPayload.DELETE) {
            Consumer<String> callback = deleteCallback;
            deleteCallback = null;
            if (callback != null)
                callback.accept(p.number() == 1 ? null : p.text());
            return;
        }
        if (p.total() == AssetPayload.LIST) {
            Consumer<JsonObject> callback = listingCallback;
            listingCallback = null;
            if (callback != null) {
                JsonObject error = new JsonObject();
                error.addProperty("error", p.text());
                callback.accept(error);
            }
            return;
        }

        lastSuccess = p.number() == 1;
        lastResult = p.text();
        Upload done = upload;
        upload = null;
        if (done != null)
            done.listener.done(lastSuccess, p.text());
    }

    private static void listing(AssetPayload p) {
        Consumer<JsonObject> callback = listingCallback;
        listingCallback = null;
        if (callback == null)
            return;
        try {
            callback.accept(JsonParser.parseString(new String(p.bytes(), StandardCharsets.UTF_8)).getAsJsonObject());
        } catch (RuntimeException e) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Unreadable asset listing");
            callback.accept(error);
        }
    }

    private static void data(AssetPayload p) {
        if (!p.text().equals(downloading))
            return;
        try {
            if (p.total() < 8 || p.total() > AssetLimits.MAX_BYTES || p.number() != (incoming == null ? 0 : incoming.size())
                    || p.bytes().length == 0)
                throw new IOException("Invalid asset download sequence");
            if (incoming == null) {
                incoming = new ByteArrayOutputStream();
                expected = p.total();
            }
            if (p.total() != expected || incoming.size() + p.bytes().length > expected)
                throw new IOException("Invalid asset download size");
            incoming.write(p.bytes());
            touched = System.currentTimeMillis();
            if (incoming.size() < expected) {
                PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.GET, incoming.size(), 0, downloading));
                return;
            }

            byte[] bytes = incoming.toByteArray();
            String hash = downloading;
            if (!AssetFrames.hash(bytes).equals(hash))
                throw new IOException("Downloaded asset hash mismatch");
            Entry entry = ENTRIES.get(hash);
            long gen = generation;
            downloading = "";
            incoming = null;
            submit(() -> {
                try {
                    ModelData model = ModelData.isModel(bytes) ? ModelData.decode(bytes) : null;
                    AssetFrames frames = model == null ? AssetFrames.bundle(bytes) : null;
                    Files.createDirectories(cachePath());
                    AssetStore.atomic(cachePath().resolve(hash), bytes);
                    trimDisk();
                    Minecraft.getInstance().execute(() -> {
                        if (gen == generation && entry != null && ENTRIES.get(hash) == entry) {
                            if (model != null)
                                loadedModel(entry, model, bytes.length);
                            else
                                loaded(hash, entry, frames, bytes.length);
                        }
                    });
                } catch (IOException e) {
                    Minecraft.getInstance().execute(() -> {
                        ENTRIES.remove(hash);
                        RETRY.put(hash, System.currentTimeMillis() + 30000);
                    });
                }
            }, () -> ENTRIES.remove(hash));
        } catch (IOException e) {
            failDownload();
        }
    }

    private static void failDownload() {
        ENTRIES.remove(downloading);
        WAITING.remove(downloading);
        RETRY.put(downloading, System.currentTimeMillis() + 30000);
        downloading = "";
        incoming = null;
    }

    private static void trimDisk() throws IOException {
        try (var files = Files.list(cachePath())) {
            var paths = files.filter(p -> AssetFrames.validId(p.getFileName().toString()))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();
            long bytes = 0;
            for (Path p : paths)
                bytes += Files.size(p);
            for (Path p : paths) {
                if (bytes <= DISK_LIMIT)
                    break;
                long size = Files.size(p);
                Files.deleteIfExists(p);
                bytes -= size;
            }
        }
    }

    // ---------------------------------------------------------------- uploads and the listing

    public static boolean uploading() {
        return upload != null;
    }

    /**
     * Sends {@code bytes} to the server's asset store. {@code mode} is {@link AssetPayload#MODE_IMAGE} (PNG, JPEG,
     * GIF or an FTAS bundle) or {@link AssetPayload#MODE_VIDEO} (converted by the server's ffmpeg).
     */
    public static void uploadBytes(String name, byte[] bytes, int mode, UploadListener listener) throws IOException {
        if (!available())
            throw new IOException("Server has no asset channel");
        if (upload != null)
            throw new IOException("Upload already in progress");
        if (bytes.length > AssetLimits.MAX_BYTES)
            throw new IOException("Upload exceeds the 16 MiB ceiling");

        upload = new Upload(bytes, listener);
        lastResult = "";
        String safeName = name.length() > 128 ? name.substring(0, 128) : name;
        PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.BEGIN, mode, bytes.length, safeName));
    }

    /** Asks for one library page from {@code offset}; the callback gets the page JSON or {@code {error}}. */
    public static void requestListing(int offset, Consumer<JsonObject> callback) {
        if (!available()) {
            JsonObject error = new JsonObject();
            error.addProperty("error", "Server has no asset channel");
            callback.accept(error);
            return;
        }
        listingCallback = callback;
        PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.LIST, offset, 0, ""));
    }

    /** Asks the server to delete {@code hash}; the callback gets null on success, else the reason. */
    public static void requestDelete(String hash, boolean force, Consumer<String> callback) {
        if (!available()) {
            callback.accept("Server has no asset channel");
            return;
        }
        if (deleteCallback != null) {
            callback.accept("Another delete is still running");
            return;
        }
        deleteCallback = callback;
        PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.DELETE, force ? 1 : 0, 0, hash));
    }

    /** {@code /foxstweaksc asset upload <path>}: a file under {@code foxstweaks/upload}. */
    public static void upload(String relative) throws IOException {
        Path root = Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks/upload");
        Files.createDirectories(root);
        root = root.toRealPath();
        Path supplied = Path.of(relative);
        if (supplied.isAbsolute())
            throw new IOException("Upload path must be relative");
        Path path = root.resolve(supplied).normalize();
        if (!path.startsWith(root) || !path.toRealPath().startsWith(root) || !Files.isRegularFile(path))
            throw new IOException("Upload must stay under foxstweaks/upload");
        if (Files.size(path) > AssetLimits.MAX_BYTES)
            throw new IOException("Upload exceeds the 16 MiB ceiling");

        String name = path.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        boolean video = lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".avi");
        uploadBytes(name, Files.readAllBytes(path), video ? AssetPayload.MODE_VIDEO : AssetPayload.MODE_IMAGE, new UploadListener() {
            @Override
            public void progress(int percent, String stage) {
            }

            @Override
            public void done(boolean ok, String message) {
                notifyPlayer((ok ? "Asset uploaded: " : "Asset refused: ") + message);
            }
        });
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post e) {
        if (!available())
            return;
        long now = System.currentTimeMillis();
        tickUpload(now);

        if (!downloading.isEmpty() && now - touched > 30000)
            failDownload();
        if (downloading.isEmpty() && !REQUESTS.isEmpty()) {
            downloading = REQUESTS.removeFirst();
            touched = now;
            PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.GET, 0, 0, downloading));
        }

        long total = ENTRIES.values().stream().mapToLong(v -> v.bytes).sum();
        var iterator = ENTRIES.entrySet().iterator();
        while (iterator.hasNext()) {
            var item = iterator.next();
            var entry = item.getValue();
            if (now - entry.last > 30000 || total > RAM_LIMIT) {
                total -= entry.bytes;
                if (entry.location != null)
                    Minecraft.getInstance().getTextureManager().release(entry.location);
                iterator.remove();
            }
        }
        RETRY.values().removeIf(t -> t < now);
    }

    private static void tickUpload(long now) {
        Upload u = upload;
        if (u == null)
            return;

        if (!u.sent()) {
            // One chunk per tick: 20 a second, half the server's packet budget.
            int end = Math.min(u.bytes.length, u.offset + AssetPayload.CHUNK_BYTES);
            PacketDistributor.sendToServer(new AssetPayload(AssetPayload.CHUNK, u.sequence++, 0, "",
                    Arrays.copyOfRange(u.bytes, u.offset, end)));
            u.offset = end;
            u.touched = now;
            u.listener.progress((int) (u.offset * 100L / Math.max(1, u.bytes.length)), "uploading");
            return;
        }

        // Sending never stalls (one chunk per tick), so only the wait for the server's result can time out.
        if (now - u.touched > PROCESS_TIMEOUT) {
            upload = null;
            lastSuccess = false;
            lastResult = "Upload timed out";
            PacketDistributor.sendToServer(AssetPayload.request(AssetPayload.ABORT, 0, 0, ""));
            u.listener.done(false, lastResult);
        }
    }

    public static void clearMemory() {
        generation++;
        dev.watdafawx.foxstweaks.client.HologramModels.clear();
        for (var e : ENTRIES.values()) {
            if (e.location != null)
                Minecraft.getInstance().getTextureManager().release(e.location);
        }
        ENTRIES.clear();
        REQUESTS.clear();
        RETRY.clear();
        WAITING.clear();
        downloading = "";
        incoming = null;
        Upload u = upload;
        upload = null;
        if (u != null)
            u.listener.done(false, "Disconnected");
        listingCallback = null;
        Consumer<String> deleting = deleteCallback;
        deleteCallback = null;
        if (deleting != null)
            deleting.accept("Disconnected");
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut e) {
        clearMemory();
    }

    @SubscribeEvent
    public static void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("foxstweaksc").then(Commands.literal("asset")
                .then(Commands.literal("upload").then(Commands.argument("path", StringArgumentType.greedyString())
                        .executes(c -> {
                            try {
                                upload(StringArgumentType.getString(c, "path"));
                                return 1;
                            } catch (IOException | RuntimeException ex) {
                                notifyPlayer(ex.getMessage());
                                return 0;
                            }
                        })))));
    }

    private static void notifyPlayer(String text) {
        var p = Minecraft.getInstance().player;
        if (p != null)
            p.displayClientMessage(Component.literal(text), false);
    }
}
