package dev.watdafawx.foxstweaks.client.asset;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.asset.AssetLimits;
import dev.watdafawx.foxstweaks.hologram.asset.AssetStore;
import dev.watdafawx.foxstweaks.hologram.model.ModelData;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Direct client-side HTTP/HTTPS loader & disk cache for URL-streamed 3D model layers
 * (.glb, .gltf, .bbmodel, .json). Zero model bytes ever cross the Minecraft server socket.
 */
public final class HologramUrlModels {
    private static final Logger LOG = LogUtils.getLogger();
    private static final long RETRY_MS = 60_000L;
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Hologram URL Model");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, ModelData> LOADED = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();

    private HologramUrlModels() {
    }

    @Nullable
    public static ModelData model(String url) {
        ModelData m = LOADED.get(url);
        if (m != null)
            return m;
        Long failedAt = FAILED.get(url);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_MS)
            return null;
        if (!LOADING.add(url))
            return null;

        WORKER.execute(() -> {
            try {
                ModelData decoded = fetchOrReadCache(url);
                LOADED.put(url, decoded);
                FAILED.remove(url);
            } catch (Throwable t) {
                FAILED.put(url, System.currentTimeMillis());
                LOG.warn("Hologram URL model failed ({}): {}", url, t.getMessage());
            } finally {
                LOADING.remove(url);
            }
        });
        return null;
    }

    private static ModelData fetchOrReadCache(String url) throws IOException, InterruptedException {
        String key = AssetFrames.hash(url.getBytes(StandardCharsets.UTF_8));
        Path cacheDir = Minecraft.getInstance().gameDirectory.toPath()
                .resolve("config").resolve("foxstweaks_cache").resolve("url_models");
        Path cached = cacheDir.resolve(key + ".ftm1");
        if (Files.isRegularFile(cached)) {
            try {
                long size = Files.size(cached);
                if (size >= 8 && size <= AssetLimits.MAX_BYTES)
                    return ModelData.decode(Files.readAllBytes(cached));
            } catch (IOException ignored) {
                Files.deleteIfExists(cached);
            }
        }

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("User-Agent", "FoxsTweaks/1.21.1")
                .GET()
                .build();
        HttpResponse<InputStream> res = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() < 200 || res.statusCode() >= 300)
            throw new IOException("HTTP " + res.statusCode());

        byte[] raw;
        try (InputStream in = res.body()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) >= 0) {
                if (out.size() + n > AssetLimits.MAX_BYTES)
                    throw new IOException("URL model exceeds " + (AssetLimits.MAX_BYTES >> 20) + " MB limit");
                out.write(buf, 0, n);
            }
            raw = out.toByteArray();
        }

        AssetStore.Prepared prepared = AssetStore.prepareModel(raw);
        if (prepared.model() == null)
            throw new IOException("Could not decode 3D model from URL");

        try {
            Files.createDirectories(cacheDir);
            Files.write(cached, prepared.blob());
        } catch (IOException ignored) {
        }
        return prepared.model();
    }

    public static void clear() {
        LOADED.clear();
        FAILED.clear();
    }
}
