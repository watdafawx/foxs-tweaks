package dev.watdafawx.foxstweaks.terraform;

import com.mojang.serialization.Codec;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ShortMap;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerRO;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Whole-biome 4x4 quart flood-fill scanner for {@link BiomeWandConfig.Mode#WHOLE_BIOME}.
 *
 * <p>Resolution order per chunk (never loads or generates chunks):
 * <ol>
 *   <li>Loaded chunk in memory ({@code level.getChunk}).</li>
 *   <li>Saved chunk NBT on disk ({@code chunkMap.read(ChunkPos)}), decoded once per chunk so
 *       already-generated chunks match what is actually on the map even when TerraBlender or
 *       biome mods shifted live worldgen noise.</li>
 *   <li>Live {@link BiomeSource#getNoiseBiome} for truly ungenerated chunks.</li>
 * </ol>
 */
public final class WholeBiomeScanner {
    public static final int HUGE_WARNING_CHUNKS = 500;
    private static final String[] UNGENERATED_SENTINEL = new String[0];

    public record Result(
            BlockPos seed,
            String sourceBiomeName,
            Long2ShortMap chunkQuartMasks,
            int totalChunks,
            int loadedChunks,
            int unloadedChunks,
            boolean hitCap,
            boolean riskyBiome,
            String warningText,
            List<BlockPos> mapPolygon
    ) {
    }

    public static CompletableFuture<Result> scanAsync(ServerLevel level, BlockPos seed, int maxChunksCap) {
        return CompletableFuture.supplyAsync(() -> scan(level, seed, maxChunksCap), Util.backgroundExecutor());
    }

    public static Result scan(ServerLevel level, BlockPos seed, int maxChunksCap) {
        int seedQX = QuartPos.fromBlock(seed.getX());
        int seedQY = QuartPos.fromBlock(seed.getY());
        int seedQZ = QuartPos.fromBlock(seed.getZ());
        int targetSectionY = seedQY >> 2;
        int targetLocalQY = seedQY & 3;

        Registry<Biome> biomeReg = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> defaultBiome = biomeReg.getHolderOrThrow(Biomes.PLAINS);
        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec = PalettedContainer.codecRO(
                biomeReg.asHolderIdMap(),
                biomeReg.holderByNameCodec(),
                PalettedContainer.Strategy.SECTION_BIOMES,
                defaultBiome
        );

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();
        Long2ObjectOpenHashMap<String[]> diskCache = new Long2ObjectOpenHashMap<>();

        Holder<Biome> seedHolder = sampleQuartHolder(level, biomeSource, sampler, seedQX, seedQY, seedQZ);
        ResourceKey<Biome> seedKey = seedHolder.unwrapKey().orElse(null);
        String seedFullId = seedKey != null ? seedKey.location().toString() : null;
        String sourceName = seedKey != null ? seedKey.location().getPath() : "unknown";

        Long2ShortOpenHashMap chunkMasks = new Long2ShortOpenHashMap();
        chunkMasks.defaultReturnValue((short) 0);

        if (seedFullId == null) {
            return new Result(seed, sourceName, chunkMasks, 0, 0, 0, false, false, "", List.of());
        }

        int maxQuarts = Math.max(64, maxChunksCap * 16);
        LongOpenHashSet visited = new LongOpenHashSet(4096);
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue(1024);

        long seedPack = packQuart(seedQX, seedQZ);
        visited.add(seedPack);
        queue.enqueue(seedPack);

        int minQX = seedQX, maxQX = seedQX, minQZ = seedQZ, maxQZ = seedQZ;
        int matchedQuarts = 0;
        boolean hitCap = false;

        final int[] dx = {1, -1, 0, 0};
        final int[] dz = {0, 0, 1, -1};

        while (!queue.isEmpty()) {
            long packed = queue.dequeueLong();
            int qx = unpackQX(packed);
            int qz = unpackQZ(packed);

            int cx = qx >> 2;
            int cz = qz >> 2;
            long cKey = ChunkPos.asLong(cx, cz);

            if (!chunkMasks.containsKey(cKey) && chunkMasks.size() >= maxChunksCap) {
                hitCap = true;
                continue;
            }

            int bit = ((qz & 3) << 2) | (qx & 3);
            chunkMasks.put(cKey, (short) (chunkMasks.get(cKey) | (1 << bit)));
            matchedQuarts++;
            if (qx < minQX) minQX = qx;
            if (qx > maxQX) maxQX = qx;
            if (qz < minQZ) minQZ = qz;
            if (qz > maxQZ) maxQZ = qz;

            if (matchedQuarts >= maxQuarts) {
                hitCap = true;
                break;
            }

            for (int dir = 0; dir < 4; dir++) {
                int nqx = qx + dx[dir];
                int nqz = qz + dz[dir];
                long nPack = packQuart(nqx, nqz);
                if (!visited.add(nPack)) continue;

                String nbId = sampleQuartId(level, biomeSource, sampler, biomeCodec, diskCache,
                        nqx, seedQY, nqz, targetSectionY, targetLocalQY);
                if (seedFullId.equals(nbId)) {
                    queue.enqueue(nPack);
                }
            }
        }

        int loaded = 0;
        int unloaded = 0;
        for (long cKey : chunkMasks.keySet()) {
            if (level.hasChunk(ChunkPos.getX(cKey), ChunkPos.getZ(cKey))) {
                loaded++;
            } else {
                unloaded++;
            }
        }

        boolean isOceanOrNether = seedHolder.is(BiomeTags.IS_OCEAN)
                || seedHolder.is(BiomeTags.IS_DEEP_OCEAN)
                || seedHolder.is(Biomes.NETHER_WASTES)
                || seedHolder.is(Biomes.THE_END);
        boolean risky = isOceanOrNether || hitCap || chunkMasks.size() >= HUGE_WARNING_CHUNKS;

        String warning = "";
        if (isOceanOrNether) {
            warning = "Warning: " + sourceName + " is huge (" + chunkMasks.size() + " chunks" + (hitCap ? ", capped" : "") + ")!";
        } else if (hitCap) {
            warning = "Warning: Hit " + maxChunksCap + "-chunk cap (" + sourceName + ")!";
        } else if (chunkMasks.size() >= HUGE_WARNING_CHUNKS) {
            warning = "Note: Large biome (" + chunkMasks.size() + " chunks, " + unloaded + " unloaded).";
        }

        List<BlockPos> polygon = buildMapPolygon(chunkMasks, minQX, maxQX, minQZ, maxQZ, seed.getY());
        return new Result(seed, sourceName, chunkMasks, chunkMasks.size(), loaded, unloaded, hitCap, risky, warning, polygon);
    }

    private static Holder<Biome> sampleQuartHolder(ServerLevel level, BiomeSource source, Climate.Sampler sampler,
                                                   int qx, int qy, int qz) {
        int cx = qx >> 2;
        int cz = qz >> 2;
        if (level.hasChunk(cx, cz)) {
            return level.getChunk(cx, cz).getNoiseBiome(qx, qy, qz);
        }
        return source.getNoiseBiome(qx, qy, qz, sampler);
    }

    @Nullable
    private static String sampleQuartId(ServerLevel level, BiomeSource source, Climate.Sampler sampler,
                                        Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                        Long2ObjectOpenHashMap<String[]> diskCache,
                                        int qx, int qy, int qz, int targetSectionY, int targetLocalQY) {
        int cx = qx >> 2;
        int cz = qz >> 2;
        if (level.hasChunk(cx, cz)) {
            return level.getChunk(cx, cz).getNoiseBiome(qx, qy, qz)
                    .unwrapKey().map(k -> k.location().toString()).orElse(null);
        }

        long cKey = ChunkPos.asLong(cx, cz);
        String[] saved = diskCache.get(cKey);
        if (saved == null) {
            saved = readChunkBiomesFromDisk(level, new ChunkPos(cx, cz), biomeCodec, targetSectionY, targetLocalQY);
            diskCache.put(cKey, saved);
        }
        if (saved != UNGENERATED_SENTINEL) {
            return saved[((qz & 3) << 2) | (qx & 3)];
        }

        return source.getNoiseBiome(qx, qy, qz, sampler)
                .unwrapKey().map(k -> k.location().toString()).orElse(null);
    }

    /**
     * Reads the 16 horizontal quart biome IDs at {@code targetSectionY / targetLocalQY} directly
     * from an unloaded chunk's Anvil NBT without loading or generating the chunk.
     */
    private static String[] readChunkBiomesFromDisk(ServerLevel level, ChunkPos cp,
                                                    Codec<PalettedContainerRO<Holder<Biome>>> biomeCodec,
                                                    int targetSectionY, int targetLocalQY) {
        try {
            Optional<CompoundTag> opt = level.getChunkSource().chunkMap.read(cp).join();
            if (opt.isEmpty()) return UNGENERATED_SENTINEL;
            CompoundTag root = opt.get();
            ListTag sections = root.getList("sections", Tag.TAG_COMPOUND);
            CompoundTag chosen = null;
            int bestDist = Integer.MAX_VALUE;

            for (int i = 0; i < sections.size(); i++) {
                CompoundTag sec = sections.getCompound(i);
                if (!sec.contains("biomes", Tag.TAG_COMPOUND)) continue;
                int sy = sec.getByte("Y");
                int dist = Math.abs(sy - targetSectionY);
                if (dist < bestDist) {
                    bestDist = dist;
                    chosen = sec;
                    if (dist == 0) break;
                }
            }
            if (chosen == null) return UNGENERATED_SENTINEL;

            CompoundTag biomesTag = chosen.getCompound("biomes");
            ListTag palette = biomesTag.getList("palette", Tag.TAG_STRING);
            if (palette.isEmpty()) return UNGENERATED_SENTINEL;

            // Fast path: single-biome section (90%+ of chunks)
            if (palette.size() == 1) {
                String id = palette.getString(0);
                String[] out = new String[16];
                java.util.Arrays.fill(out, id);
                return out;
            }

            // Multi-biome section: decode with vanilla's exact PalettedContainer codec
            var parsed = biomeCodec.parse(NbtOps.INSTANCE, biomesTag).result();
            if (parsed.isEmpty()) return UNGENERATED_SENTINEL;
            PalettedContainerRO<Holder<Biome>> container = parsed.get();
            String[] out = new String[16];
            for (int lqz = 0; lqz < 4; lqz++) {
                for (int lqx = 0; lqx < 4; lqx++) {
                    Holder<Biome> h = container.get(lqx, targetLocalQY, lqz);
                    out[(lqz << 2) | lqx] = h.unwrapKey().map(k -> k.location().toString()).orElse(null);
                }
            }
            return out;
        } catch (Exception ignored) {
            return UNGENERATED_SENTINEL;
        }
    }

    private static List<BlockPos> buildMapPolygon(Long2ShortOpenHashMap chunkMasks,
                                                  int minQX, int maxQX, int minQZ, int maxQZ, int y) {
        int qDepth = maxQZ - minQZ + 1;
        int step = Math.max(1, qDepth / 36);
        List<BlockPos> left = new ArrayList<>();
        List<BlockPos> right = new ArrayList<>();

        for (int qz = minQZ; qz <= maxQZ; qz += step) {
            addRowSilhouette(chunkMasks, minQX, maxQX, qz, y, left, right);
        }
        if ((maxQZ - minQZ) % step != 0) {
            addRowSilhouette(chunkMasks, minQX, maxQX, maxQZ, y, left, right);
        }

        List<BlockPos> poly = new ArrayList<>(left.size() + right.size());
        poly.addAll(left);
        for (int i = right.size() - 1; i >= 0; i--) {
            poly.add(right.get(i));
        }
        return poly;
    }

    private static void addRowSilhouette(Long2ShortOpenHashMap chunkMasks, int minQX, int maxQX, int qz, int y,
                                         List<BlockPos> left, List<BlockPos> right) {
        int cz = qz >> 2;
        int lqz = qz & 3;
        int firstQX = Integer.MAX_VALUE;
        int lastQX = Integer.MIN_VALUE;

        int minCX = minQX >> 2;
        int maxCX = maxQX >> 2;
        for (int cx = minCX; cx <= maxCX; cx++) {
            short mask = chunkMasks.get(ChunkPos.asLong(cx, cz));
            if (mask == 0) continue;
            for (int lqx = 0; lqx < 4; lqx++) {
                if ((mask & (1 << ((lqz << 2) | lqx))) != 0) {
                    int qx = (cx << 2) + lqx;
                    if (qx < firstQX) firstQX = qx;
                    if (qx > lastQX) lastQX = qx;
                }
            }
        }
        if (firstQX != Integer.MAX_VALUE) {
            left.add(new BlockPos(firstQX << 2, y, qz << 2));
            right.add(new BlockPos((lastQX + 1) << 2, y, (qz + 1) << 2));
        }
    }

    private static long packQuart(int qx, int qz) {
        return (((long) qx) << 32) | (qz & 0xFFFFFFFFL);
    }

    private static int unpackQX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackQZ(long packed) {
        return (int) packed;
    }

    private WholeBiomeScanner() {
    }
}
