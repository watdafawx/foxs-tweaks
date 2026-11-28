package dev.watdafawx.foxstweaks.terraform;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores lazy biome stamps for unloaded chunks selected by {@link BiomeWandConfig.Mode#WHOLE_BIOME}.
 *
 * <p>Zero chunks are force-loaded or generated when confirming a huge biome; instead, each unloaded
 * chunk's 16-bit quart mask is stored here and applied seamlessly on {@code ChunkEvent.Load} before
 * the chunk is ever sent to clients.
 */
public final class PendingBiomeChanges extends SavedData {
    private static final String DATA_NAME = "foxstweaks_pending_biomes";

    public record Stamp(
            long batchId,
            short quartMask,
            ResourceLocation targetBiome,
            BiomeWandConfig.HeightMode heightMode,
            int sliceMinY,
            int sliceMaxY
    ) {
    }

    private final Map<Long, List<Stamp>> pending = new HashMap<>();

    public static PendingBiomeChanges get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(
                new Factory<>(PendingBiomeChanges::new, PendingBiomeChanges::load, null), DATA_NAME);
    }

    public void add(long chunkKey, Stamp stamp) {
        pending.computeIfAbsent(chunkKey, k -> new ArrayList<>(1)).add(stamp);
        setDirty();
    }

    public int cancelBatch(long batchId) {
        int removed = 0;
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            List<Stamp> list = it.next().getValue();
            int before = list.size();
            list.removeIf(s -> s.batchId == batchId);
            removed += before - list.size();
            if (list.isEmpty()) it.remove();
        }
        if (removed > 0) setDirty();
        return removed;
    }

    /** Called on {@code ChunkEvent.Load} to apply any pending stamps to a newly loaded chunk. */
    public void applyIfPending(ServerLevel level, LevelChunk chunk) {
        long chunkKey = chunk.getPos().toLong();
        List<Stamp> stamps = pending.remove(chunkKey);
        if (stamps == null || stamps.isEmpty()) return;
        setDirty();

        Registry<Biome> biomeReg = level.registryAccess().registryOrThrow(Registries.BIOME);
        int chunkQX = chunk.getPos().x << 2;
        int chunkQZ = chunk.getPos().z << 2;
        int minBuild = level.getMinBuildHeight();
        int maxBuild = level.getMaxBuildHeight() - 1;

        for (Stamp s : stamps) {
            Optional<Holder.Reference<Biome>> targetOpt = biomeReg.getHolder(ResourceKey.create(Registries.BIOME, s.targetBiome));
            if (targetOpt.isEmpty()) continue;
            Holder<Biome> targetHolder = targetOpt.get();

            int[] quartMinQY = new int[16];
            int fullMinQY = s.heightMode == BiomeWandConfig.HeightMode.SLICE
                    ? QuartPos.fromBlock(Math.max(minBuild, Math.min(s.sliceMinY, s.sliceMaxY)))
                    : QuartPos.fromBlock(minBuild);
            int fullMaxQY = s.heightMode == BiomeWandConfig.HeightMode.SLICE
                    ? QuartPos.fromBlock(Math.min(maxBuild, Math.max(s.sliceMinY, s.sliceMaxY)))
                    : QuartPos.fromBlock(maxBuild);

            if (s.heightMode == BiomeWandConfig.HeightMode.SURFACE) {
                for (int lqz = 0; lqz < 4; lqz++) {
                    for (int lqx = 0; lqx < 4; lqx++) {
                        int qIdx = (lqz << 2) | lqx;
                        if ((s.quartMask & (1 << qIdx)) == 0) continue;
                        int minSurf = Integer.MAX_VALUE;
                        for (int dz = 0; dz < 4; dz++) {
                            for (int dx = 0; dx < 4; dx++) {
                                int sy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (lqx << 2) + dx, (lqz << 2) + dz);
                                if (sy < minSurf) minSurf = sy;
                            }
                        }
                        quartMinQY[qIdx] = QuartPos.fromBlock(Math.max(minBuild, minSurf - 8));
                    }
                }
            }

            chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
                int lqx = qx - chunkQX;
                int lqz = qz - chunkQZ;
                if (lqx >= 0 && lqx < 4 && lqz >= 0 && lqz < 4) {
                    int qIdx = (lqz << 2) | lqx;
                    if ((s.quartMask & (1 << qIdx)) != 0) {
                        int effMin = s.heightMode == BiomeWandConfig.HeightMode.SURFACE ? quartMinQY[qIdx] : fullMinQY;
                        if (qy >= effMin && qy <= fullMaxQY) {
                            return targetHolder;
                        }
                    }
                }
                return chunk.getNoiseBiome(qx, qy, qz);
            }, level.getChunkSource().randomState().sampler());
            chunk.setUnsaved(true);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, List<Stamp>> e : pending.entrySet()) {
            for (Stamp s : e.getValue()) {
                CompoundTag st = new CompoundTag();
                st.putLong("c", e.getKey());
                st.putLong("id", s.batchId);
                st.putShort("m", s.quartMask);
                st.putString("b", s.targetBiome.toString());
                st.putByte("h", (byte) s.heightMode.ordinal());
                st.putInt("y0", s.sliceMinY);
                st.putInt("y1", s.sliceMaxY);
                list.add(st);
            }
        }
        tag.put("stamps", list);
        return tag;
    }

    private static PendingBiomeChanges load(CompoundTag tag, HolderLookup.Provider registries) {
        PendingBiomeChanges data = new PendingBiomeChanges();
        ListTag list = tag.getList("stamps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag st = list.getCompound(i);
            long cKey = st.getLong("c");
            Stamp s = new Stamp(
                    st.getLong("id"),
                    st.getShort("m"),
                    ResourceLocation.parse(st.getString("b")),
                    BiomeWandConfig.HeightMode.byOrdinal(st.getByte("h")),
                    st.getInt("y0"),
                    st.getInt("y1")
            );
            data.pending.computeIfAbsent(cKey, k -> new ArrayList<>(1)).add(s);
        }
        return data;
    }
}
