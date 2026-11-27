package dev.watdafawx.foxstweaks.client.asset;

import dev.watdafawx.foxstweaks.hologram.asset.AssetLimits;
import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads a picture or video from a local path or an http(s) address and hands it to {@link HologramAssets}. This is the
 * in-game way to add an asset (the web editor has its own, with a file picker and size fitting): the bytes go through the
 * same upload path, so the server validates, downsizes and re-encodes them exactly as for any other upload.
 *
 * <p>File and network reads run on a worker thread; every listener call happens on the client thread.
 */
public final class HologramAssetImport {
    private static final AtomicBoolean BUSY = new AtomicBoolean();

    /** Largest source we open to shrink; the upload itself is still capped at 16 MiB after fitting. */
    private static final int MAX_SOURCE = 64 * 1024 * 1024;
    private static volatile AssetLimits learned;

    private HologramAssetImport() {
    }

    /** The server's real caps, from the library listing; until one arrives the synced config is used. */
    public static void serverLimits(int fileBytes, int dimension, int fps, int seconds) {
        AssetLimits base = AssetLimits.current();
        learned = new AssetLimits(fileBytes, base.worldBytes(), dimension, fps, seconds, base.perPlayer());
    }

    private static AssetLimits limits() {
        AssetLimits l = learned;
        return l != null ? l : AssetLimits.current();
    }

    public static boolean busy() {
        return BUSY.get() || HologramAssets.uploading();
    }

    /**
     * Starts importing {@code source}: a file path (a quoted path from "Copy as path" is fine), a {@code file:} address
     * dropped from a browser, or an {@code http(s)} address. Returns false when something is already in progress.
     */
    public static boolean start(String source, HologramAssets.UploadListener listener) {
        return start(source, "", listener);
    }

    public static boolean start(String source, String folder, HologramAssets.UploadListener listener) {
        String text = source.trim();
        if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
            text = text.substring(1, text.length() - 1).trim();
        if (text.isEmpty()) {
            listener.done(false, "Type a file path or an address first");
            return true;
        }
        if (busy())
            return false;

        String what = text;
        String cleanFolder = HologramPackZip.cleanFolderName(folder);
        BUSY.set(true);
        Thread t = new Thread(() -> {
            Loaded loaded;
            try {
                loaded = load(what, listener);
            } catch (IOException | RuntimeException e) {
                String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Minecraft.getInstance().execute(() -> {
                    BUSY.set(false);
                    listener.done(false, message);
                });
                return;
            }
            byte[] data = loaded.bytes;
            String note = "";
            if (!loaded.video && !loaded.model) {
                if (isWebp(data)) {
                    String msg = "WebP is not supported in game. Convert to PNG or GIF, or use the web editor.";
                    Minecraft.getInstance().execute(() -> {
                        BUSY.set(false);
                        listener.done(false, msg);
                    });
                    return;
                }
                try {
                    HologramAssetFit.Result fitted = HologramAssetFit.fit(data, limits());
                    data = fitted.bytes();
                    note = fitted.note();
                } catch (IOException | RuntimeException | OutOfMemoryError e) {
                    // Unreadable here (WebP, odd formats): let the server decide on the original bytes.
                    data = loaded.bytes;
                }
            }
            byte[] sendBytes = data;
            String fitNote = note;
            boolean bundle = sendBytes.length >= 4 && sendBytes[0] == 'F' && sendBytes[1] == 'T' && sendBytes[2] == 'A'
                    && sendBytes[3] == '1';
            String finalName = cleanFolder.isEmpty() ? loaded.name : cleanFolder + "/" + loaded.name;
            Minecraft.getInstance().execute(() -> {
                BUSY.set(false);
                try {
                    if (!fitNote.isEmpty())
                        listener.progress(0, fitNote);
                    HologramAssets.uploadBytes(finalName, sendBytes,
                            loaded.model ? AssetPayload.MODE_MODEL : loaded.video ? AssetPayload.MODE_VIDEO
                                    : bundle ? AssetPayload.MODE_BUNDLE : AssetPayload.MODE_IMAGE,
                            listener);
                } catch (IOException e) {
                    listener.done(false, e.getMessage());
                }
            });
        }, "FoxsTweaks asset import");
        t.setDaemon(true);
        t.start();
        return true;
    }

    private record Loaded(String name, byte[] bytes, boolean video, boolean model) {
        Loaded(String name, byte[] bytes, boolean video) {
            this(name, bytes, video, !video && isModel(name, bytes));
        }
    }

    /** A glTF binary, a Blockbench project, or a file with one of those extensions. */
    static boolean isModel(String name, byte[] b) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".glb") || lower.endsWith(".gltf") || lower.endsWith(".bbmodel")
                || lower.endsWith(".json") || lower.endsWith(".obj") || lower.endsWith(".fbx"))
            return true;
        return dev.watdafawx.foxstweaks.hologram.model.GlbReader.looksLikeGlb(b)
                || dev.watdafawx.foxstweaks.hologram.model.BlockbenchReader.looksLikeBbmodel(b);
    }

    private static Loaded load(String source, HologramAssets.UploadListener listener) throws IOException {
        String lower = source.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://"))
            return download(source, listener);

        Path path;
        try {
            path = lower.startsWith("file:") ? Path.of(URI.create(source)) : Path.of(source);
        } catch (RuntimeException e) {
            throw new IOException("That is not a file path or an address");
        }
        if (!Files.isRegularFile(path))
            throw new IOException("No such file: " + path.getFileName());
        if (Files.size(path) > MAX_SOURCE)
            throw new IOException("File is over the 64 MiB limit");
        String name = path.getFileName().toString();
        String nameLower = name.toLowerCase(Locale.ROOT);
        if (nameLower.endsWith(".gltf")) {
            Minecraft.getInstance().execute(() -> listener.progress(0, "bundling glTF"));
            return new Loaded(name, GltfBundler.bundleFile(path), false, true);
        }
        if (nameLower.endsWith(".obj")) {
            Minecraft.getInstance().execute(() -> listener.progress(0, "converting OBJ"));
            String glbName = name.substring(0, name.length() - 4) + ".glb";
            return new Loaded(glbName, ObjToGlb.convertFile(path), false, true);
        }
        if (nameLower.endsWith(".fbx")) {
            Minecraft.getInstance().execute(() -> listener.progress(0, "converting FBX"));
            String glbName = name.substring(0, name.length() - 4) + ".glb";
            return new Loaded(glbName, FbxConverter.convertFile(path), false, true);
        }
        byte[] bytes = Files.readAllBytes(path);
        boolean video = isVideo(name, bytes);
        if (video && bytes.length > AssetLimits.MAX_BYTES)
            throw new IOException("Video is over the 16 MiB upload limit");
        return new Loaded(name, bytes, video);
    }

    private static Loaded download(String address, HologramAssets.UploadListener listener) throws IOException {
        URI uri;
        try {
            uri = URI.create(address);
        } catch (IllegalArgumentException e) {
            throw new IOException("That address is not valid");
        }
        Minecraft.getInstance().execute(() -> listener.progress(0, "downloading"));
        try {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10)).build();
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("User-Agent", "FoxsTweaks/1.54 (https://github.com/watdafawx/foxs-tweaks)")
                    .timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2)
                throw new IOException("The address answered " + response.statusCode());
            try (InputStream in = response.body()) {
                // One byte more than the ceiling is enough to know it is too big, without reading a huge file.
                byte[] bytes = in.readNBytes(MAX_SOURCE + 1);
                if (bytes.length > MAX_SOURCE)
                    throw new IOException("Download is over the 64 MiB limit");
                String name = uri.getPath() == null || uri.getPath().isEmpty() ? "download" : uri.getPath();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (name.isEmpty())
                    name = "download";
                boolean video = isVideo(name, bytes);
                if (video && bytes.length > AssetLimits.MAX_BYTES)
                    throw new IOException("Video is over the 16 MiB upload limit");
                return new Loaded(name, bytes, video);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted");
        }
    }

    private static boolean isWebp(byte[] b) {
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W'
                && b[9] == 'E' && b[10] == 'B' && b[11] == 'P';
    }

    /** By extension, else by the file's own first bytes (a URL often has no extension). */
    static boolean isVideo(String name, byte[] b) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp4") || lower.endsWith(".webm") || lower.endsWith(".mov") || lower.endsWith(".avi")
                || lower.endsWith(".mkv"))
            return true;
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p')
            return true;
        if (b.length >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3)
            return true;
        return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'A' && b[9] == 'V'
                && b[10] == 'I';
    }
}
