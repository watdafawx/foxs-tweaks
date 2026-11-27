package dev.watdafawx.foxstweaks.client.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns a {@code .gltf} JSON with external {@code .bin} and texture URIs into a self-contained {@code .gltf}
 * with inline {@code data:} URIs so {@link dev.watdafawx.foxstweaks.hologram.model.GlbReader} can decode it
 * on the server without needing any sibling files.
 */
public final class GltfBundler {

    @FunctionalInterface
    public interface Lookup {
        @Nullable
        byte[] resolve(String relativeUri) throws IOException;
    }

    private GltfBundler() {
    }

    /** Bundles a local {@code .gltf} file, searching its folder and sibling {@code Textures/} folders. */
    public static byte[] bundleFile(Path gltfPath) throws IOException {
        Path dir = gltfPath.toAbsolutePath().getParent();
        byte[] raw = Files.readAllBytes(gltfPath);
        return bundle(raw, uri -> resolveLocal(dir, uri));
    }

    public static byte[] bundle(byte[] gltfBytes, Lookup lookup) throws IOException {
        JsonObject root;
        try {
            root = JsonParser.parseString(new String(gltfBytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid .gltf JSON: " + e.getMessage(), e);
        }

        boolean changed = false;

        // Inline external binary buffers
        if (root.has("buffers") && root.get("buffers").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("buffers")) {
                if (!el.isJsonObject())
                    continue;
                JsonObject b = el.getAsJsonObject();
                String uri = str(b, "uri");
                if (uri.isEmpty() || uri.startsWith("data:"))
                    continue;
                byte[] data = lookup.resolve(decodeUri(uri));
                if (data == null)
                    throw new IOException("Missing .gltf buffer file: " + uri);
                b.addProperty("uri", "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(data));
                changed = true;
            }
        }

        // Collect baseColorTexture image indices so we skip heavy normal/roughness/metallic maps.
        Set<Integer> neededImages = new HashSet<>();
        if (root.has("materials") && root.get("materials").isJsonArray()
                && root.has("textures") && root.get("textures").isJsonArray()) {
            JsonArray textures = root.getAsJsonArray("textures");
            for (JsonElement mel : root.getAsJsonArray("materials")) {
                if (!mel.isJsonObject())
                    continue;
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

        // Inline external images
        if (root.has("images") && root.get("images").isJsonArray()) {
            JsonArray imgs = root.getAsJsonArray("images");
            for (int i = 0; i < imgs.size(); i++) {
                if (!imgs.get(i).isJsonObject())
                    continue;
                JsonObject img = imgs.get(i).getAsJsonObject();
                String uri = str(img, "uri");
                if (uri.isEmpty() || uri.startsWith("data:"))
                    continue;
                if (!neededImages.isEmpty() && !neededImages.contains(i)) {
                    img.remove("uri");
                    changed = true;
                    continue;
                }
                byte[] data = lookup.resolve(decodeUri(uri));
                if (data != null) {
                    String lower = uri.toLowerCase(Locale.ROOT);
                    String mime = lower.endsWith(".jpg") || lower.endsWith(".jpeg") ? "image/jpeg" : "image/png";
                    img.addProperty("uri", "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(data));
                    changed = true;
                } else {
                    img.remove("uri");
                    changed = true;
                }
            }
        }

        return changed ? root.toString().getBytes(StandardCharsets.UTF_8) : gltfBytes;
    }

    /**
     * Resolves {@code relativeUri} next to {@code dir}, then falls back to common pack texture folders
     * ({@code Textures/}, {@code textures/}, {@code ../Textures/}, {@code ../textures/}) by filename.
     */
    @Nullable
    public static byte[] resolveLocal(@Nullable Path dir, String relativeUri) throws IOException {
        if (dir == null || relativeUri == null || relativeUri.isEmpty())
            return null;
        String norm = relativeUri.replace('\\', '/');
        Path direct = dir.resolve(norm).normalize();
        if (Files.isRegularFile(direct))
            return Files.readAllBytes(direct);

        String fileName = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
        if (fileName.isEmpty())
            return null;
        Path[] candidates = {
                dir.resolve(fileName),
                dir.resolve("Textures").resolve(fileName),
                dir.resolve("textures").resolve(fileName),
                dir.getParent() != null ? dir.getParent().resolve("Textures").resolve(fileName) : null,
                dir.getParent() != null ? dir.getParent().resolve("textures").resolve(fileName) : null,
                dir.getParent() != null ? dir.getParent().resolve(fileName) : null
        };
        for (Path p : candidates) {
            if (p != null && Files.isRegularFile(p))
                return Files.readAllBytes(p);
        }
        return null;
    }

    private static String decodeUri(String uri) {
        try {
            return URLDecoder.decode(uri, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return uri;
        }
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }
}
