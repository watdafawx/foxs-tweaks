package dev.watdafawx.foxstweaks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Favourites and recent picks of the hologram asset browser, kept client-side in
 * {@code config/foxstweaks-hologram-picker.json}.
 *
 * <p>Keys are {@code kind:registry id} (for example {@code block:minecraft:glass}), so one file serves blocks,
 * mobs and particles, and a pick whose mod was removed simply never matches an entry.
 */
final class HologramPickerFavorites {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_RECENT = 24;

    private static final Set<String> FAVOURITES = new LinkedHashSet<>();
    private static final List<String> RECENT = new ArrayList<>();
    private static boolean loaded;

    static String key(HologramLayer.Kind kind, String id) {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foxstweaks-hologram-picker.json");
    }

    static boolean isFavourite(HologramLayer.Kind kind, String id) {
        ensureLoaded();
        return FAVOURITES.contains(key(kind, id));
    }

    /** Flips the star and writes the file. Returns whether the entry is now a favourite. */
    static boolean toggleFavourite(HologramLayer.Kind kind, String id) {
        ensureLoaded();
        String key = key(kind, id);
        boolean now = !FAVOURITES.remove(key);
        if (now)
            FAVOURITES.add(key);
        save();
        return now;
    }

    static void addRecent(HologramLayer.Kind kind, String id) {
        ensureLoaded();
        String key = key(kind, id);
        RECENT.remove(key);
        RECENT.add(0, key);
        while (RECENT.size() > MAX_RECENT)
            RECENT.remove(RECENT.size() - 1);
        save();
    }

    /** Favourite ids of one kind, oldest first. */
    static List<String> favourites(HologramLayer.Kind kind) {
        ensureLoaded();
        return idsOf(FAVOURITES, kind);
    }

    /** Recent ids of one kind, newest first. */
    static List<String> recent(HologramLayer.Kind kind) {
        ensureLoaded();
        return idsOf(RECENT, kind);
    }

    /** Drops the in-memory copy so the next read comes from disk. Dev check only. */
    static void forgetMemory() {
        FAVOURITES.clear();
        RECENT.clear();
        loaded = false;
    }

    private static List<String> idsOf(Iterable<String> keys, HologramLayer.Kind kind) {
        String prefix = kind.name().toLowerCase(Locale.ROOT) + ":";
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (key.startsWith(prefix))
                out.add(key.substring(prefix.length()));
        }
        return out;
    }

    private static void ensureLoaded() {
        if (loaded)
            return;
        loaded = true;
        Path path = file();
        if (!Files.isRegularFile(path))
            return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            readInto(root.getAsJsonArray("favourites"), FAVOURITES);
            Set<String> recent = new LinkedHashSet<>();
            readInto(root.getAsJsonArray("recent"), recent);
            RECENT.addAll(recent);
        } catch (Exception e) {
            LOG.warn("Could not read {}: {}", path, e.toString());
        }
    }

    private static void readInto(JsonArray array, Set<String> out) {
        if (array == null)
            return;
        for (JsonElement element : array) {
            if (element.isJsonPrimitive())
                out.add(element.getAsString());
        }
    }

    private static void save() {
        JsonObject root = new JsonObject();
        JsonArray favourites = new JsonArray();
        FAVOURITES.forEach(favourites::add);
        JsonArray recent = new JsonArray();
        RECENT.forEach(recent::add);
        root.add("favourites", favourites);
        root.add("recent", recent);
        try {
            Path path = file();
            Files.createDirectories(path.getParent());
            Files.writeString(path, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("Could not write the hologram picker file: {}", e.toString());
        }
    }

    private HologramPickerFavorites() {
    }
}
