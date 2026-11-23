package dev.watdafawx.foxstweaks.anchor;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-dimension persistent list of virtual spawn anchors ({@code data/foxstweaks_spawn_anchors.dat}).
 * Each anchor keeps its chunk square force-ticking and satisfies player-proximity checks for
 * spawners, Dreadful/Delightful Dirt random ticks, natural spawns, and mob despawn prevention.
 *
 * <p>An anchor may also carry an Apotheosis world tier: {@code owner} is the player whose tier it
 * follows while they are online, and {@code tier} is the last known (or fixed) tier used when they
 * are not. Both are empty on anchors made before this existed, which leaves their behaviour
 * unchanged. See {@link AnchorTier}.
 */
public final class SpawnAnchorData extends SavedData {
    private static final String FILE_NAME = "foxstweaks_spawn_anchors";

    public record Anchor(String name, double x, double y, double z, int chunkRadius,
                         @Nullable UUID owner, String ownerName, String tier, boolean followOwner) {

        /** Radius in blocks this anchor answers for: its chunk square plus a chunk of slack. */
        public double coverage() {
            return this.chunkRadius * 16.0 + 16.0;
        }

        public boolean carriesTier() {
            return !this.tier.isEmpty();
        }

        public ChunkPos chunkPos() {
            return new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
        }

        public double distSq(double px, double py, double pz) {
            double dx = px - x;
            double dy = py - y;
            double dz = pz - z;
            return dx * dx + dy * dy + dz * dz;
        }

        public double horizontalDistSq(double px, double pz) {
            double dx = px - x;
            double dz = pz - z;
            return dx * dx + dz * dz;
        }
    }

    private final Map<String, Anchor> anchors = new LinkedHashMap<>();

    public static SpawnAnchorData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(SpawnAnchorData::new, SpawnAnchorData::load),
                FILE_NAME);
    }

    public Collection<Anchor> all() {
        return Collections.unmodifiableCollection(anchors.values());
    }

    public Anchor get(String name) {
        return anchors.get(name);
    }

    public Anchor put(Anchor anchor) {
        Anchor prev = anchors.put(anchor.name(), anchor);
        setDirty();
        return prev;
    }

    public Anchor remove(String name) {
        Anchor removed = anchors.remove(name);
        if (removed != null)
            setDirty();
        return removed;
    }

    public boolean isEmpty() {
        return anchors.isEmpty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Anchor a : anchors.values()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("name", a.name());
            entry.putDouble("x", a.x());
            entry.putDouble("y", a.y());
            entry.putDouble("z", a.z());
            entry.putInt("radius", a.chunkRadius());
            if (a.owner() != null)
                entry.putUUID("owner", a.owner());
            if (!a.ownerName().isEmpty())
                entry.putString("ownerName", a.ownerName());
            if (!a.tier().isEmpty())
                entry.putString("tier", a.tier());
            if (a.followOwner())
                entry.putBoolean("followOwner", true);
            list.add(entry);
        }
        tag.put("anchors", list);
        return tag;
    }

    private static SpawnAnchorData load(CompoundTag tag, HolderLookup.Provider registries) {
        SpawnAnchorData data = new SpawnAnchorData();
        ListTag list = tag.getList("anchors", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag e = list.getCompound(i);
            String name = e.getString("name");
            if (name.isEmpty())
                continue;
            int radius = Math.max(1, Math.min(8, e.getInt("radius")));
            UUID owner = e.hasUUID("owner") ? e.getUUID("owner") : null;
            data.anchors.put(name, new Anchor(name, e.getDouble("x"), e.getDouble("y"), e.getDouble("z"),
                    radius, owner, e.getString("ownerName"), e.getString("tier"), e.getBoolean("followOwner")));
        }
        return data;
    }
}
