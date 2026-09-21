package dev.mtop.foxstweaks.client.compat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Item id -> display name, persisted across launches for {@code IconPickerScreenMixin}. EZActions
 * resolves every item's name from scratch each session, which on a large pack is the bulk of the
 * "Preparing names" wait.
 *
 * <p>A name is trusted only while its own mod is still at the version it was resolved under, so
 * updating, adding or removing mods only re-resolves those mods' items. Language and resource packs
 * are deliberately ignored: a name is whatever the item's own mod declared when it was cached, so a
 * language switch keeps showing the old names until the cache file is deleted.
 *
 * <p>Touches only Minecraft and FML, never EZActions, so it is safe to load without it.
 */
public final class IconNameCache {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Gson GSON = new Gson();

    private static final Map<String, String> NAMES = new ConcurrentHashMap<>();
    private static boolean loaded;
    private static volatile boolean dirty;
    private static volatile boolean saving;

    private IconNameCache() {
    }

    /** The cached name for {@code item}, or {@code null} when it has to be resolved the slow way. */
    public static String get(Item item) {
        ensureLoaded();

        return NAMES.get(BuiltInRegistries.ITEM.getKey(item).toString());
    }

    public static void put(Item item, String name) {
        if (name == null || name.isBlank()) {
            return;
        }

        if (!name.equals(NAMES.put(BuiltInRegistries.ITEM.getKey(item).toString(), name))) {
            dirty = true;
        }
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("cache").resolve("foxstweaks_icon_names.json");
    }

    /** Loaded mod id -> version. Item namespaces are mod ids, including {@code minecraft} itself. */
    private static Map<String, String> modVersions() {
        var mods = new TreeMap<String, String>();
        for (var mod : ModList.get().getMods()) {
            mods.put(mod.getModId(), mod.getVersion().toString());
        }

        return mods;
    }

    private static String namespace(String id) {
        return id.substring(0, id.indexOf(':'));
    }

    private static synchronized void ensureLoaded() {
        if (loaded) {
            return;
        }
        loaded = true;

        try {
            var path = file();
            if (!Files.isRegularFile(path)) {
                return;
            }

            var root = GSON.fromJson(Files.readString(path), JsonObject.class);
            var saved = root.getAsJsonObject("mods");
            var current = modVersions();
            int stale = 0;
            for (var e : root.getAsJsonObject("names").entrySet()) {
                var ns = namespace(e.getKey());
                var version = saved.get(ns);
                if (version != null && version.getAsString().equals(current.get(ns))) {
                    NAMES.put(e.getKey(), e.getValue().getAsString());
                } else {
                    stale++;
                }
            }
            LOG.info("[Fox's Tweaks] Loaded {} cached icon picker names ({} outdated by mod changes).", NAMES.size(), stale);
        } catch (Exception e) {
            // A corrupt cache is just a cold start; it is rewritten on the next save.
            LOG.warn("[Fox's Tweaks] Ignoring unreadable icon name cache: {}", e.toString());
            NAMES.clear();
        }
    }

    /** Writes the cache if anything new was resolved. Off-thread, so closing the screen never stalls. */
    public static void saveIfDirty() {
        if (!dirty || saving) {
            return;
        }
        dirty = false;
        saving = true;

        var mods = modVersions();
        var snapshot = new HashMap<>(NAMES);
        var thread = new Thread(() -> {
            try {
                var root = new JsonObject();
                root.add("mods", GSON.toJsonTree(mods));
                root.add("names", GSON.toJsonTree(snapshot));

                var path = file();
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(root));
            } catch (IOException e) {
                LOG.warn("[Fox's Tweaks] Could not save the icon name cache: {}", e.toString());
            } finally {
                saving = false;
            }
        }, "foxstweaks-icon-names");
        thread.setDaemon(true);
        thread.start();
    }
}
