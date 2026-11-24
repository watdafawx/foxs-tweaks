package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-dimension persistent list of holograms ({@code data/foxstweaks_holograms.dat}), keyed by id.
 * Same shape as {@code anchor/SpawnAnchorData}: a flat record list, hand-written NBT, nothing ticked.
 */
public final class HologramData extends SavedData {
    private static final String FILE_NAME = "foxstweaks_holograms";

    private final Map<String, HologramSpec> holograms = new LinkedHashMap<>();

    public static HologramData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(HologramData::new, HologramData::load),
                FILE_NAME);
    }

    public Collection<HologramSpec> all() {
        return Collections.unmodifiableCollection(this.holograms.values());
    }

    public HologramSpec get(String id) {
        return this.holograms.get(id);
    }

    public HologramSpec put(HologramSpec spec) {
        HologramSpec prev = this.holograms.put(spec.id(), spec);
        setDirty();
        return prev;
    }

    public HologramSpec remove(String id) {
        HologramSpec removed = this.holograms.remove(id);
        if (removed != null)
            setDirty();
        return removed;
    }

    public boolean isEmpty() {
        return this.holograms.isEmpty();
    }

    public int size() {
        return this.holograms.size();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (HologramSpec spec : this.holograms.values())
            list.add(spec.toTag());
        tag.put("holograms", list);
        return tag;
    }

    private static HologramData load(CompoundTag tag, HolderLookup.Provider registries) {
        HologramData data = new HologramData();
        ListTag list = tag.getList("holograms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            HologramSpec spec = HologramSpec.fromTag(entry);
            if (spec != null)
                data.holograms.put(spec.id(), spec);
        }
        return data;
    }

    /** A snapshot list, safe to hand to the network layer while the map may still change. */
    public List<HologramSpec> snapshot() {
        return new ArrayList<>(this.holograms.values());
    }
}
