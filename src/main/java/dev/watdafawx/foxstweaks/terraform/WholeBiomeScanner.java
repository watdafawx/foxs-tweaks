package dev.watdafawx.foxstweaks.terraform;

import it.unimi.dsi.fastutil.longs.Long2ShortMap;
import it.unimi.dsi.fastutil.longs.Long2ShortOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-math 4x4 quart flood-fill scanner for {@link BiomeWandConfig.Mode#WHOLE_BIOME}.
 *
 * <p>Uses loaded chunks where available and {@link BiomeSource#getNoiseBiome} for unloaded chunks,
 * so it can trace an entire huge biome across thousands of blocks without loading or generating
 * a single unloaded chunk.
 */
public final class WholeBiomeScanner {
    public static final int HUGE_WARNING_CHUNKS = 500;

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

    public static Result scan(ServerLevel level, BlockPos seed, int maxChunksCap) {
        int seedQX = QuartPos.fromBlock(seed.getX());
        int seedQY = QuartPos.fromBlock(seed.getY());
        int seedQZ = QuartPos.fromBlock(seed.getZ());

        BiomeSource biomeSource = level.getChunkSource().getGenerator().getBiomeSource();
        Climate.Sampler sampler = level.getChunkSource().randomState().sampler();

        Holder<Biome> seedHolder = sampleQuart(level, biomeSource, sampler, seedQX, seedQY, seedQZ);
        ResourceKey<Biome> seedKey = seedHolder.unwrapKey().orElse(null);
        String sourceName = seedKey != null ? seedKey.location().getPath() : "unknown";

        Long2ShortOpenHashMap chunkMasks = new Long2ShortOpenHashMap();
        chunkMasks.defaultReturnValue((short) 0);

        if (seedKey == null) {
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

                Holder<Biome> nb = sampleQuart(level, biomeSource, sampler, nqx, seedQY, nqz);
                if (nb.is(seedKey)) {
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

    private static Holder<Biome> sampleQuart(ServerLevel level, BiomeSource source, Climate.Sampler sampler,
                                             int qx, int qy, int qz) {
        int cx = qx >> 2;
        int cz = qz >> 2;
        if (level.hasChunk(cx, cz)) {
            return level.getChunk(cx, cz).getNoiseBiome(qx, qy, qz);
        }
        return source.getNoiseBiome(qx, qy, qz, sampler);
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
