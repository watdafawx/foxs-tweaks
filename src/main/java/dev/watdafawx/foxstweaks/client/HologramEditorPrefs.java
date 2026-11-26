package dev.watdafawx.foxstweaks.client;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.neoforged.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side hologram editor preferences: which editor opens first (Creator or Studio) and the player's own
 * saved looks. Kept in {@code config/foxstweaks-hologram-editor.nbt}, outside the mod config on purpose: it is
 * per-player taste, not a setting anyone would edit by hand, and a look is an effect list, which NBT already
 * round-trips through {@link HologramEffect#listToTag}.
 */
public final class HologramEditorPrefs {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_LOOKS = 12;

    /** A saved look: a name and the full effect list (FX and motion) it applies. */
    public record Look(String name, List<HologramEffect> effects) {
    }

    private static boolean loaded;
    private static boolean startInCreator = true;
    private static final List<Look> looks = new ArrayList<>();

    private HologramEditorPrefs() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foxstweaks-hologram-editor.nbt");
    }

    /**
     * The full editor's dev checks drive {@link HologramScreen} directly; opening the Creator first would put a
     * different screen in front of them. Their system properties pin the old behaviour.
     */
    private static boolean devPinsStudio() {
        return Boolean.getBoolean("foxstweaks.hologramCheck")
                || Boolean.getBoolean("foxstweaks.hologramPickerCheck")
                || Boolean.getBoolean("foxstweaks.hologramGrabCheck")
                || Boolean.getBoolean("foxstweaks.hologramStructureCheck");
    }

    public static boolean startInCreator() {
        load();
        return startInCreator && !devPinsStudio();
    }

    public static void setStartInCreator(boolean creator) {
        load();
        if (startInCreator != creator) {
            startInCreator = creator;
            save();
        }
    }

    public static List<Look> looks() {
        load();
        return List.copyOf(looks);
    }

    public static void addLook(List<HologramEffect> effects) {
        load();
        if (looks.size() >= MAX_LOOKS)
            looks.remove(0);
        int n = 1;
        for (Look l : looks) {
            if (l.name().startsWith("Look "))
                n = Math.max(n, parseOr(l.name().substring(5), 0) + 1);
        }
        looks.add(new Look("Look " + n, List.copyOf(effects)));
        save();
    }

    public static void removeLook(int index) {
        load();
        if (index >= 0 && index < looks.size()) {
            looks.remove(index);
            save();
        }
    }

    private static int parseOr(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static void load() {
        if (loaded)
            return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f))
            return;
        try {
            CompoundTag root = NbtIo.read(f);
            if (root == null)
                return;
            if (root.contains("startInCreator"))
                startInCreator = root.getBoolean("startInCreator");
            ListTag list = root.getList("looks", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size() && looks.size() < MAX_LOOKS; i++) {
                CompoundTag t = list.getCompound(i);
                looks.add(new Look(t.getString("name"),
                        HologramEffect.listFromTag(t.getList("effects", Tag.TAG_COMPOUND))));
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not read {}: {}", f, e.toString());
        }
    }

    private static void save() {
        CompoundTag root = new CompoundTag();
        root.putBoolean("startInCreator", startInCreator);
        ListTag list = new ListTag();
        for (Look l : looks) {
            CompoundTag t = new CompoundTag();
            t.putString("name", l.name());
            t.put("effects", HologramEffect.listToTag(l.effects()));
            list.add(t);
        }
        root.put("looks", list);
        try {
            NbtIo.write(root, file());
        } catch (IOException e) {
            LOGGER.warn("Could not write {}: {}", file(), e.toString());
        }
    }
}
