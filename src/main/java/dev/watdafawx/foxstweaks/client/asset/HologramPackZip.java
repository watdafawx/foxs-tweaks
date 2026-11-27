package dev.watdafawx.foxstweaks.client.asset;

import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;
import net.minecraft.client.Minecraft;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads itch.io / Quaternius / Kenney-style 3D asset pack {@code .zip} archives directly without unzipping:
 * scans for models, deduplicates by model name preferring {@code .glb > .gltf > .obj > .fbx}, indexes all
 * textures inside the zip, and packs any selected model on demand into a self-contained upload.
 */
public final class HologramPackZip {

    public enum Format {
        GLB("GLB", 4),
        GLTF("glTF", 3),
        OBJ("OBJ", 2),
        FBX("FBX", 1);

        public final String label;
        final int priority;

        Format(String label, int priority) {
            this.label = label;
            this.priority = priority;
        }
    }

    public record Entry(String name, String zipPath, Format format) {
    }

    public record Pack(Path zipFile, String packName, List<Entry> entries) {
    }

    private HologramPackZip() {
    }

    public static Pack scan(Path zipPath) throws IOException {
        Map<String, Entry> bestByKey = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(zipPath.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry ze = en.nextElement();
                if (ze.isDirectory())
                    continue;
                String path = ze.getName().replace('\\', '/');
                if (path.startsWith("__MACOSX/") || path.contains("/._"))
                    continue;
                String lower = path.toLowerCase(Locale.ROOT);
                Format fmt = formatOf(lower);
                if (fmt == null)
                    continue;
                String fileName = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
                int dot = fileName.lastIndexOf('.');
                String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
                String key = baseName.toLowerCase(Locale.ROOT);
                Entry prev = bestByKey.get(key);
                if (prev == null || fmt.priority > prev.format.priority) {
                    bestByKey.put(key, new Entry(baseName, path, fmt));
                }
            }
        }
        List<Entry> list = new ArrayList<>(bestByKey.values());
        list.sort(Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)));
        String pName = zipPath.getFileName() != null ? zipPath.getFileName().toString() : "pack.zip";
        return new Pack(zipPath, pName, List.copyOf(list));
    }

    /** Extracts and packs a single model entry from {@code pack} on a worker thread, then uploads it. */
    public static boolean importEntry(Pack pack, Entry entry, HologramAssets.UploadListener listener) {
        if (HologramAssetImport.busy())
            return false;
        Thread t = new Thread(() -> {
            try {
                Minecraft.getInstance().execute(() -> listener.progress(0, "packing " + entry.format().label));
                byte[] packed;
                String uploadName;
                try (ZipFile zf = new ZipFile(pack.zipFile().toFile())) {
                    Map<String, ZipEntry> byExact = new HashMap<>();
                    Map<String, ZipEntry> byFileName = new HashMap<>();
                    Enumeration<? extends ZipEntry> en = zf.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        if (ze.isDirectory())
                            continue;
                        String norm = ze.getName().replace('\\', '/');
                        byExact.put(norm.toLowerCase(Locale.ROOT), ze);
                        String fn = norm.contains("/") ? norm.substring(norm.lastIndexOf('/') + 1) : norm;
                        byFileName.putIfAbsent(fn.toLowerCase(Locale.ROOT), ze);
                    }
                    ZipEntry modelEntry = zf.getEntry(entry.zipPath());
                    if (modelEntry == null)
                        throw new IOException("Entry not found in zip: " + entry.zipPath());
                    String parentDir = entry.zipPath().contains("/")
                            ? entry.zipPath().substring(0, entry.zipPath().lastIndexOf('/') + 1) : "";

                    GltfBundler.Lookup lookup = uri -> resolveInZip(zf, parentDir, uri, byExact, byFileName);

                    switch (entry.format()) {
                        case GLB -> {
                            packed = readEntry(zf, modelEntry);
                            uploadName = entry.name() + ".glb";
                        }
                        case GLTF -> {
                            packed = GltfBundler.bundle(readEntry(zf, modelEntry), lookup);
                            uploadName = entry.name() + ".gltf";
                        }
                        case OBJ -> {
                            packed = ObjToGlb.convert(readEntry(zf, modelEntry), lookup);
                            uploadName = entry.name() + ".glb";
                        }
                        case FBX -> {
                            Path tmpDir = Files.createTempDirectory("foxstweaks-fbxpack-");
                            try {
                                Path fbxFile = tmpDir.resolve(entry.name() + ".fbx");
                                Files.write(fbxFile, readEntry(zf, modelEntry));
                                // Extract Textures/ alongside so Blender can find external textures.
                                Path texDir = tmpDir.resolve("Textures");
                                Files.createDirectories(texDir);
                                for (var te : byFileName.entrySet()) {
                                    String fn = te.getKey();
                                    if (fn.endsWith(".png") || fn.endsWith(".jpg") || fn.endsWith(".jpeg")) {
                                        Files.write(texDir.resolve(fn), readEntry(zf, te.getValue()));
                                    }
                                }
                                packed = FbxConverter.convertFile(fbxFile);
                                uploadName = entry.name() + ".glb";
                            } finally {
                                deleteTree(tmpDir);
                            }
                        }
                        default -> throw new IOException("Unsupported format");
                    }
                }
                byte[] sendBytes = packed;
                String folder = cleanFolderName(pack.packName());
                String name = folder.isEmpty() ? uploadName : folder + "/" + uploadName;
                Minecraft.getInstance().execute(() -> {
                    try {
                        HologramAssets.uploadBytes(name, sendBytes, AssetPayload.MODE_MODEL, listener);
                    } catch (IOException e) {
                        listener.done(false, e.getMessage());
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                Minecraft.getInstance().execute(() -> listener.done(false, msg));
            }
        }, "FoxsTweaks zip model import");
        t.setDaemon(true);
        t.start();
        return true;
    }

    @Nullable
    private static byte[] resolveInZip(ZipFile zf, String parentDir, String relativeUri,
                                       Map<String, ZipEntry> byExact, Map<String, ZipEntry> byFileName) throws IOException {
        if (relativeUri == null || relativeUri.isEmpty())
            return null;
        String clean = relativeUri.replace('\\', '/');
        String combined = normalizePath(parentDir + clean).toLowerCase(Locale.ROOT);
        ZipEntry ze = byExact.get(combined);
        if (ze == null) {
            String fn = clean.contains("/") ? clean.substring(clean.lastIndexOf('/') + 1) : clean;
            ze = byFileName.get(fn.toLowerCase(Locale.ROOT));
        }
        return ze == null ? null : readEntry(zf, ze);
    }

    private static String normalizePath(String path) {
        String[] parts = path.split("/");
        List<String> stack = new ArrayList<>();
        for (String p : parts) {
            if (p.isEmpty() || ".".equals(p))
                continue;
            if ("..".equals(p)) {
                if (!stack.isEmpty())
                    stack.remove(stack.size() - 1);
            } else {
                stack.add(p);
            }
        }
        return String.join("/", stack);
    }

    private static byte[] readEntry(ZipFile zf, ZipEntry ze) throws IOException {
        try (InputStream in = zf.getInputStream(ze)) {
            byte[] data = in.readNBytes(64 * 1024 * 1024 + 1);
            if (data.length > 64 * 1024 * 1024)
                throw new IOException("Entry exceeds 64 MiB limit");
            return data;
        }
    }

    private static void deleteTree(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }

    public static String cleanFolderName(String raw) {
        if (raw == null)
            return "";
        String s = raw.trim();
        if (s.toLowerCase(Locale.ROOT).endsWith(".zip"))
            s = s.substring(0, s.length() - 4);
        s = s.replace('/', '-').replace('\\', '-').replaceAll("[\\p{Cntrl}]", "").trim();
        return s.length() > 48 ? s.substring(0, 48).trim() : s;
    }

    @Nullable
    private static Format formatOf(String lower) {
        if (lower.endsWith(".glb"))
            return Format.GLB;
        if (lower.endsWith(".gltf"))
            return Format.GLTF;
        if (lower.endsWith(".obj"))
            return Format.OBJ;
        if (lower.endsWith(".fbx"))
            return Format.FBX;
        return null;
    }
}
