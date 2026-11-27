package dev.watdafawx.foxstweaks.client.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Keyless public CDN asset lookup for the hologram asset screen:
 * <ul>
 *   <li>Images &amp; GIFs via Openverse ({@code api.openverse.org})</li>
 *   <li>3D models via Khronos glTF-Sample-Assets (animated {@code .glb}) and Poly Haven CC0 ({@code api.polyhaven.com})</li>
 * </ul>
 * All HTTP calls run off the render thread; thumbnails decode via {@link ImageIO} and register as temporary
 * {@link DynamicTexture}s released when the screen closes.
 */
public final class HologramAssetSearch {
    private static final String USER_AGENT = "FoxsTweaks/1.54 (https://github.com/watdafawx/foxs-tweaks)";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    private static final ExecutorService THUMB_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "FoxsTweaks asset thumb");
        t.setDaemon(true);
        return t;
    });

    private static final Map<String, ResourceLocation> THUMBS = new ConcurrentHashMap<>();
    private static final Set<String> THUMB_PENDING = ConcurrentHashMap.newKeySet();
    private static final AtomicInteger THUMB_SEQ = new AtomicInteger();

    // Session-cached 3D model catalogs so searching models is instant after the first load.
    private static volatile List<ModelEntry> khronosCache;
    private static volatile List<ModelEntry> polyHavenCache;

    public enum Kind {
        IMAGE("Images"),
        GIF("GIFs"),
        MODEL("3D Models");

        public final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    public record Result(
            String id,
            String title,
            String subtitle,
            String downloadUrl,
            String thumbUrl,
            Kind kind,
            boolean polyHaven
    ) {
    }

    private record ModelEntry(String id, String title, String searchHaystack, String downloadUrl,
                              String thumbUrl, String subtitle, boolean polyHaven) {
    }

    private HologramAssetSearch() {
    }

    public static void search(Kind kind, String query, Consumer<List<Result>> onResults, Consumer<String> onError) {
        String q = query == null ? "" : query.trim();
        Thread t = new Thread(() -> {
            try {
                List<Result> results = switch (kind) {
                    case IMAGE -> searchImages(q);
                    case GIF -> searchGifs(q);
                    case MODEL -> searchModels(q);
                };
                Minecraft.getInstance().execute(() -> onResults.accept(results));
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Minecraft.getInstance().execute(() -> onError.accept(msg));
            }
        }, "FoxsTweaks asset search");
        t.setDaemon(true);
        t.start();
    }

    private static List<Result> searchImages(String query) throws IOException, InterruptedException {
        try {
            List<Result> wh = searchWallhaven(query);
            if (!wh.isEmpty())
                return wh;
        } catch (Exception ignored) {
        }
        return searchOpenverse(query, "png,jpg", Kind.IMAGE);
    }

    private static List<Result> searchGifs(String query) throws IOException, InterruptedException {
        try {
            List<Result> wiki = searchWikimediaGifs(query);
            if (!wiki.isEmpty())
                return wiki;
        } catch (Exception ignored) {
        }
        return searchOpenverse(query, "gif", Kind.GIF);
    }

    private static List<Result> searchWallhaven(String query) throws IOException, InterruptedException {
        String url = "https://wallhaven.cc/api/v1/search?purity=100"
                + (query.isEmpty() ? "&sorting=toplist" : "&q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
        String body = getString(url, 10);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.has("data") && root.get("data").isJsonArray()
                ? root.getAsJsonArray("data") : new JsonArray();
        List<Result> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject())
                continue;
            JsonObject o = el.getAsJsonObject();
            String dl = str(o, "path");
            if (dl.isEmpty())
                continue;
            String id = str(o, "id");
            String thumb = dl;
            if (o.has("thumbs") && o.get("thumbs").isJsonObject()) {
                String sm = str(o.getAsJsonObject("thumbs"), "small");
                if (!sm.isEmpty())
                    thumb = sm;
            }
            String res = str(o, "resolution");
            String sub = res.isEmpty() ? "wallhaven" : res;
            out.add(new Result(id, id.isEmpty() ? "image" : "wh-" + id, sub, dl, thumb, Kind.IMAGE, false));
        }
        return out;
    }

    private static List<Result> searchWikimediaGifs(String query) throws IOException, InterruptedException {
        String q = query.isEmpty() ? "cat" : query;
        String url = "https://commons.wikimedia.org/w/api.php?action=query&generator=search&gsrsearch="
                + URLEncoder.encode(q + " filemime:image/gif", StandardCharsets.UTF_8)
                + "&gsrnamespace=6&gsrlimit=20&prop=imageinfo&iiprop=url%7Cmime&iiurlwidth=128&format=json";
        String body = getString(url, 10);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        if (!root.has("query") || !root.get("query").isJsonObject())
            return List.of();
        JsonObject qObj = root.getAsJsonObject("query");
        if (!qObj.has("pages") || !qObj.get("pages").isJsonObject())
            return List.of();
        List<Result> out = new ArrayList<>();
        for (var entry : qObj.getAsJsonObject("pages").entrySet()) {
            if (!entry.getValue().isJsonObject())
                continue;
            JsonObject page = entry.getValue().getAsJsonObject();
            if (!page.has("imageinfo") || !page.get("imageinfo").isJsonArray())
                continue;
            JsonArray infos = page.getAsJsonArray("imageinfo");
            if (infos.isEmpty())
                continue;
            JsonObject info = infos.get(0).getAsJsonObject();
            String dl = str(info, "url");
            if (dl.isEmpty())
                continue;
            int qIdx = dl.indexOf('?');
            if (qIdx > 0)
                dl = dl.substring(0, qIdx);
            String thumb = str(info, "thumburl");
            if (thumb.isEmpty())
                thumb = dl;
            String title = str(page, "title");
            if (title.startsWith("File:"))
                title = title.substring(5);
            if (title.toLowerCase(Locale.ROOT).endsWith(".gif"))
                title = title.substring(0, title.length() - 4);
            out.add(new Result(entry.getKey(), title, "gif", dl, thumb, Kind.GIF, false));
        }
        return out;
    }

    private static List<Result> searchOpenverse(String query, String ext, Kind kind) throws IOException, InterruptedException {
        String q = query.isEmpty() ? (kind == Kind.GIF ? "cat" : "art") : query;
        // Openverse anonymous requests cap page_size at 20 (>20 returns HTTP 401).
        String url = "https://api.openverse.org/v1/images/?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8)
                + "&extension=" + ext + "&page_size=20";
        String body = getString(url, 12);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray arr = root.has("results") && root.get("results").isJsonArray()
                ? root.getAsJsonArray("results") : new JsonArray();
        List<Result> out = new ArrayList<>(arr.size());
        for (JsonElement el : arr) {
            if (!el.isJsonObject())
                continue;
            JsonObject o = el.getAsJsonObject();
            String dl = str(o, "url");
            if (dl.isEmpty())
                continue;
            String id = str(o, "id");
            String title = str(o, "title");
            if (title.isEmpty())
                title = id.isEmpty() ? "asset" : id.substring(0, Math.min(8, id.length()));
            String thumb = str(o, "thumbnail");
            if (thumb.isEmpty())
                thumb = dl;
            int w = o.has("width") && !o.get("width").isJsonNull() ? o.get("width").getAsInt() : 0;
            int h = o.has("height") && !o.get("height").isJsonNull() ? o.get("height").getAsInt() : 0;
            String sub = kind == Kind.GIF ? "gif" : (w > 0 && h > 0 ? w + "x" + h : "img");
            out.add(new Result(id, title, sub, dl, thumb, kind, false));
        }
        return out;
    }

    private static List<Result> searchModels(String query) {
        List<ModelEntry> all = new ArrayList<>();
        try {
            all.addAll(loadKhronos());
        } catch (Exception ignored) {
        }
        try {
            all.addAll(loadPolyHaven());
        } catch (Exception ignored) {
        }

        String q = query.toLowerCase(Locale.ROOT);
        String[] terms = q.isEmpty() ? new String[0] : q.split("\\s+");
        List<Result> out = new ArrayList<>();
        for (ModelEntry e : all) {
            boolean match = true;
            for (String term : terms) {
                if (!e.searchHaystack.contains(term)) {
                    match = false;
                    break;
                }
            }
            if (match) {
                out.add(new Result(e.id, e.title, e.subtitle, e.downloadUrl, e.thumbUrl, Kind.MODEL, e.polyHaven));
                if (out.size() >= 36)
                    break;
            }
        }
        return out;
    }

    private static List<ModelEntry> loadKhronos() throws IOException, InterruptedException {
        List<ModelEntry> cached = khronosCache;
        if (cached != null)
            return cached;
        String base = "https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/";
        String body = getString(base + "model-index.json", 12);
        JsonArray arr = JsonParser.parseString(body).getAsJsonArray();
        List<ModelEntry> out = new ArrayList<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject())
                continue;
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name");
            if (name.isEmpty() || !o.has("variants") || !o.get("variants").isJsonObject())
                continue;
            JsonObject vars = o.getAsJsonObject("variants");
            String glb = str(vars, "glTF-Binary");
            if (glb.isEmpty())
                continue;
            String label = str(o, "label");
            if (label.isEmpty())
                label = name;
            String screenshot = str(o, "screenshot");
            String dl = base + urlSeg(name) + "/glTF-Binary/" + urlSeg(glb);
            String thumb = screenshot.isEmpty() ? "" : base + urlSeg(name) + "/" + screenshot;
            StringBuilder hay = new StringBuilder((name + " " + label + " glb sample").toLowerCase(Locale.ROOT));
            if (o.has("tags") && o.get("tags").isJsonArray()) {
                for (JsonElement t : o.getAsJsonArray("tags"))
                    hay.append(' ').append(t.getAsString().toLowerCase(Locale.ROOT));
            }
            out.add(new ModelEntry(name, label, hay.toString(), dl, thumb, "glTF .glb", false));
        }
        khronosCache = List.copyOf(out);
        return khronosCache;
    }

    private static List<ModelEntry> loadPolyHaven() throws IOException, InterruptedException {
        List<ModelEntry> cached = polyHavenCache;
        if (cached != null)
            return cached;
        String body = getString("https://api.polyhaven.com/assets?t=models", 12);
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        List<ModelEntry> out = new ArrayList<>(root.size());
        for (var entry : root.entrySet()) {
            if (!entry.getValue().isJsonObject())
                continue;
            String id = entry.getKey();
            JsonObject o = entry.getValue().getAsJsonObject();
            String name = str(o, "name");
            if (name.isEmpty())
                name = id;
            StringBuilder hay = new StringBuilder((id + " " + name + " polyhaven").toLowerCase(Locale.ROOT));
            if (o.has("categories") && o.get("categories").isJsonArray()) {
                for (JsonElement c : o.getAsJsonArray("categories"))
                    hay.append(' ').append(c.getAsString().toLowerCase(Locale.ROOT));
            }
            if (o.has("tags") && o.get("tags").isJsonArray()) {
                for (JsonElement tg : o.getAsJsonArray("tags"))
                    hay.append(' ').append(tg.getAsString().toLowerCase(Locale.ROOT));
            }
            String thumb = "https://cdn.polyhaven.com/asset_img/thumbs/" + urlSeg(id) + ".png?width=128&height=128";
            out.add(new ModelEntry(id, name, hay.toString(),
                    "https://api.polyhaven.com/files/" + urlSeg(id), thumb, "PolyHaven", true));
        }
        polyHavenCache = List.copyOf(out);
        return polyHavenCache;
    }

    /**
     * Downloads a Poly Haven 1k glTF model plus its {@code .bin} and textures, inlines them as {@code data:} URIs
     * into a self-contained {@code .gltf}, and uploads it through the normal model asset pipeline.
     */
    public static boolean importPolyHaven(Result r, HologramAssets.UploadListener listener) {
        if (HologramAssetImport.busy())
            return false;
        Thread t = new Thread(() -> {
            try {
                Minecraft.getInstance().execute(() -> listener.progress(0, "downloading"));
                String body = getString(r.downloadUrl(), 15);
                JsonObject root = JsonParser.parseString(body).getAsJsonObject();
                JsonObject gltfRoot = root.has("gltf") && root.get("gltf").isJsonObject()
                        ? root.getAsJsonObject("gltf") : null;
                if (gltfRoot == null)
                    throw new IOException("No glTF download available for " + r.title());
                JsonObject resObj = gltfRoot.has("1k") ? gltfRoot.getAsJsonObject("1k")
                        : gltfRoot.has("2k") ? gltfRoot.getAsJsonObject("2k") : null;
                if (resObj == null || !resObj.has("gltf"))
                    throw new IOException("No 1k/2k glTF variant for " + r.title());
                JsonObject gltfEntry = resObj.getAsJsonObject("gltf");
                String gltfUrl = str(gltfEntry, "url");
                if (gltfUrl.isEmpty())
                    throw new IOException("Missing glTF URL");

                String gltfText = getString(gltfUrl, 20);
                JsonObject gltfJson = JsonParser.parseString(gltfText).getAsJsonObject();

                Map<String, String> includeUrls = new HashMap<>();
                if (gltfEntry.has("include") && gltfEntry.get("include").isJsonObject()) {
                    for (var e : gltfEntry.getAsJsonObject("include").entrySet()) {
                        if (e.getValue().isJsonObject()) {
                            String u = str(e.getValue().getAsJsonObject(), "url");
                            if (!u.isEmpty())
                                includeUrls.put(e.getKey(), u);
                        }
                    }
                }

                // Inline binary buffers
                if (gltfJson.has("buffers") && gltfJson.get("buffers").isJsonArray()) {
                    for (JsonElement el : gltfJson.getAsJsonArray("buffers")) {
                        JsonObject b = el.getAsJsonObject();
                        String uri = str(b, "uri");
                        String dl = includeUrls.get(uri);
                        if (dl != null) {
                            byte[] raw = getBytes(dl, 20, 16 * 1024 * 1024);
                            b.addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(raw));
                        }
                    }
                }

                // Find which image indices are actually used as baseColorTexture so we skip heavy normal/ARM maps.
                Set<Integer> neededImages = new HashSet<>();
                if (gltfJson.has("materials") && gltfJson.get("materials").isJsonArray()
                        && gltfJson.has("textures") && gltfJson.get("textures").isJsonArray()) {
                    JsonArray textures = gltfJson.getAsJsonArray("textures");
                    for (JsonElement mel : gltfJson.getAsJsonArray("materials")) {
                        JsonObject mat = mel.getAsJsonObject();
                        if (mat.has("pbrMetallicRoughness") && mat.get("pbrMetallicRoughness").isJsonObject()) {
                            JsonObject pbr = mat.getAsJsonObject("pbrMetallicRoughness");
                            if (pbr.has("baseColorTexture") && pbr.get("baseColorTexture").isJsonObject()) {
                                int texIdx = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                                if (texIdx >= 0 && texIdx < textures.size()) {
                                    JsonObject tex = textures.get(texIdx).getAsJsonObject();
                                    if (tex.has("source"))
                                        neededImages.add(tex.get("source").getAsInt());
                                }
                            }
                        }
                    }
                }

                if (gltfJson.has("images") && gltfJson.get("images").isJsonArray()) {
                    JsonArray imgs = gltfJson.getAsJsonArray("images");
                    for (int i = 0; i < imgs.size(); i++) {
                        JsonObject img = imgs.get(i).getAsJsonObject();
                        String uri = str(img, "uri");
                        if (!neededImages.isEmpty() && !neededImages.contains(i)) {
                            img.remove("uri");
                            continue;
                        }
                        String dl = includeUrls.get(uri);
                        if (dl != null) {
                            byte[] raw = getBytes(dl, 20, 16 * 1024 * 1024);
                            String mime = uri.toLowerCase(Locale.ROOT).endsWith(".png") ? "image/png" : "image/jpeg";
                            img.addProperty("uri", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(raw));
                        }
                    }
                }

                byte[] packed = gltfJson.toString().getBytes(StandardCharsets.UTF_8);
                String name = r.id() + ".gltf";
                Minecraft.getInstance().execute(() -> {
                    try {
                        HologramAssets.uploadBytes(name, packed, AssetPayload.MODE_MODEL, listener);
                    } catch (IOException e) {
                        listener.done(false, e.getMessage());
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Minecraft.getInstance().execute(() -> listener.done(false, msg));
            }
        }, "FoxsTweaks polyhaven import");
        t.setDaemon(true);
        t.start();
        return true;
    }

    // ---------------------------------------------------------------- thumbnails

    @Nullable
    public static ResourceLocation thumbnail(String url) {
        if (url == null || url.isEmpty())
            return null;
        ResourceLocation ready = THUMBS.get(url);
        if (ready != null)
            return ready;
        if (!THUMB_PENDING.add(url))
            return null;
        THUMB_POOL.submit(() -> {
            try {
                byte[] raw = getBytes(url, 8, 2 * 1024 * 1024);
                BufferedImage bi = ImageIO.read(new ByteArrayInputStream(raw));
                if (bi == null)
                    return;
                int w = Math.min(bi.getWidth(), 128);
                int h = Math.min(bi.getHeight(), 128);
                BufferedImage scaled = (bi.getWidth() == w && bi.getHeight() == h) ? bi : resize(bi, w, h);
                NativeImage ni = new NativeImage(w, h, false);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = scaled.getRGB(x, y);
                        int a = (argb >>> 24) & 0xFF;
                        int r = (argb >>> 16) & 0xFF;
                        int g = (argb >>> 8) & 0xFF;
                        int b = argb & 0xFF;
                        ni.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r);
                    }
                }
                Minecraft.getInstance().execute(() -> {
                    ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                            FoxsTweaks.MODID, "asset_search_thumb/" + THUMB_SEQ.incrementAndGet());
                    Minecraft.getInstance().getTextureManager().register(loc, new DynamicTexture(ni));
                    THUMBS.put(url, loc);
                });
            } catch (Exception ignored) {
            }
        });
        return null;
    }

    public static void clearThumbnails() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation loc : THUMBS.values())
            tm.release(loc);
        THUMBS.clear();
        THUMB_PENDING.clear();
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = dst.createGraphics();
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return dst;
    }

    // ---------------------------------------------------------------- http helpers

    private static String getString(String url, int timeoutSec) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET().build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2)
            throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static byte[] getBytes(String url, int timeoutSec, int maxBytes) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(timeoutSec))
                .GET().build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2)
            throw new IOException("HTTP " + resp.statusCode());
        try (var in = resp.body()) {
            byte[] data = in.readNBytes(maxBytes + 1);
            if (data.length > maxBytes)
                throw new IOException("Asset too large");
            return data;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String urlSeg(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
