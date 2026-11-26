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

    private HologramAssetImport() {
    }

    public static boolean busy() {
        return BUSY.get() || HologramAssets.uploading();
    }

    /**
     * Starts importing {@code source}: a file path (a quoted path from "Copy as path" is fine), a {@code file:} address
     * dropped from a browser, or an {@code http(s)} address. Returns false when something is already in progress.
     */
    public static boolean start(String source, HologramAssets.UploadListener listener) {
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
            Minecraft.getInstance().execute(() -> {
                BUSY.set(false);
                try {
                    HologramAssets.uploadBytes(loaded.name, loaded.bytes, loaded.video ? AssetPayload.MODE_VIDEO : AssetPayload.MODE_IMAGE,
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

    private record Loaded(String name, byte[] bytes, boolean video) {
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
        if (Files.size(path) > AssetLimits.MAX_BYTES)
            throw new IOException("File is over the 16 MiB upload limit");
        byte[] bytes = Files.readAllBytes(path);
        String name = path.getFileName().toString();
        return new Loaded(name, bytes, isVideo(name, bytes));
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
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2)
                throw new IOException("The address answered " + response.statusCode());
            try (InputStream in = response.body()) {
                // One byte more than the ceiling is enough to know it is too big, without reading a huge file.
                byte[] bytes = in.readNBytes(AssetLimits.MAX_BYTES + 1);
                if (bytes.length > AssetLimits.MAX_BYTES)
                    throw new IOException("Download is over the 16 MiB upload limit");
                String name = uri.getPath() == null || uri.getPath().isEmpty() ? "download" : uri.getPath();
                name = name.substring(name.lastIndexOf('/') + 1);
                if (name.isEmpty())
                    name = "download";
                return new Loaded(name, bytes, isVideo(name, bytes));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download was interrupted");
        }
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
