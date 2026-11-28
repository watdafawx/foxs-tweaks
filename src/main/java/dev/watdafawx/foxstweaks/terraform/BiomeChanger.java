package dev.watdafawx.foxstweaks.terraform;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeResolver;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side executor for the Biome Changer Wand.
 *
 * <p>Interior 4x4x4 quart cells are written straight into vanilla's {@link LevelChunkSection}
 * biome palette via {@link ChunkAccess#fillBiomesFromNoise}. When {@code exactEdges} is enabled,
 * partial border quart cells are left untouched in vanilla storage and their exact 1x1x1 inside
 * blocks are stored in {@link ExactBiomes}, giving true per-block biome boundaries with zero
 * overhead on interior or untouched chunks.
 */
public final class BiomeChanger {
    public static void apply(ServerPlayer player, BiomeWandConfig cfg) {
        if (!ServerConfig.terraformEnabled()) {
            player.displayClientMessage(Component.literal("Terraform tools are disabled on this server.").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        Registry<Biome> biomeReg = level.registryAccess().registryOrThrow(Registries.BIOME);
        Optional<Holder.Reference<Biome>> targetOpt = biomeReg.getHolder(ResourceKey.create(Registries.BIOME, cfg.targetBiome()));
        if (targetOpt.isEmpty()) {
            player.displayClientMessage(Component.literal("Unknown biome: " + cfg.targetBiome()).withStyle(ChatFormatting.RED), true);
            return;
        }
        Holder<Biome> targetHolder = targetOpt.get();
        ResourceLocation targetId = cfg.targetBiome();

        BiomeSelection sel = BiomeSelection.compute(level, cfg, ServerConfig.biomeWandMaxRadius());
        if (sel == null || sel.selectedColumns == 0) {
            player.displayClientMessage(Component.literal("Select an area first.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }

        int minCX = sel.minX >> 4;
        int maxCX = sel.maxX() >> 4;
        int minCZ = sel.minZ >> 4;
        int maxCZ = sel.maxZ() >> 4;

        ExactBiomes exact = ExactBiomes.get(level);
        Map<Long, List<TerraformUndoStore.QuartEntry>> quartBackups = new HashMap<>();
        Map<Long, ExactBiomes.ChunkSnapshot> prevExact = new HashMap<>();
        Map<BlockPos, BlockState> prevSnow = new HashMap<>();

        List<ChunkAccess> vanillaModified = new ArrayList<>();
        List<ExactBiomes.ChunkPacketData> exactPackets = new ArrayList<>();

        int minQY = QuartPos.fromBlock(sel.minY);
        int maxQY = QuartPos.fromBlock(sel.maxY);

        for (int cz = minCZ; cz <= maxCZ; cz++) {
            for (int cx = minCX; cx <= maxCX; cx++) {
                if (!level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = level.getChunk(cx, cz);
                long chunkKey = ChunkPos.asLong(cx, cz);

                ExactBiomes.ChunkSnapshot beforeSnap = exact.getChunk(chunkKey);
                ExactBiomes.MutableChunk mutExact = ExactBiomes.MutableChunk.from(beforeSnap);
                boolean exactTouched = false;
                boolean vanillaTouched = false;
                List<TerraformUndoStore.QuartEntry> chunkBackups = new ArrayList<>();

                // Inspect each 4x4 horizontal quart column inside this chunk
                int chunkMinX = cx << 4;
                int chunkMinZ = cz << 4;
                boolean[] fullInsideQuart = new boolean[16]; // (lqz << 2) | lqx
                int[] quartMinQY = new int[16]; // per-quart minimum quart-Y for SURFACE mode
                BiomeWandConfig.HeightMode hMode = cfg.heightMode();

                for (int lqz = 0; lqz < 4; lqz++) {
                    for (int lqx = 0; lqx < 4; lqx++) {
                        int baseX = chunkMinX + (lqx << 2);
                        int baseZ = chunkMinZ + (lqz << 2);
                        int insideCount = 0;
                        int minSurfY = Integer.MAX_VALUE;
                        for (int dz = 0; dz < 4; dz++) {
                            for (int dx = 0; dx < 4; dx++) {
                                if (sel.contains(baseX + dx, baseZ + dz)) {
                                    insideCount++;
                                    if (hMode == BiomeWandConfig.HeightMode.SURFACE) {
                                        int sy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (lqx << 2) + dx, (lqz << 2) + dz);
                                        if (sy < minSurfY) minSurfY = sy;
                                    }
                                }
                            }
                        }
                        if (insideCount == 0) continue;

                        if (insideCount == 16 || !cfg.exactEdges()) {
                            // Full quart column (or 4x4 mode): write to vanilla quart storage
                            int qIdx = (lqz << 2) | lqx;
                            fullInsideQuart[qIdx] = true;
                            if (hMode == BiomeWandConfig.HeightMode.SURFACE) {
                                int floorY = Math.max(level.getMinBuildHeight(), minSurfY - 8);
                                quartMinQY[qIdx] = QuartPos.fromBlock(floorY);
                            } else {
                                quartMinQY[qIdx] = minQY;
                            }
                            if (hMode != BiomeWandConfig.HeightMode.SLICE) {
                                // Clear any stale exact overrides in this interior quart
                                for (int dz = 0; dz < 4; dz++) {
                                    for (int dx = 0; dx < 4; dx++) {
                                        mutExact.clearColumn((lqx << 2) + dx, (lqz << 2) + dz);
                                    }
                                }
                                exactTouched = true;
                            }
                        } else {
                            // Partial border quart cell with 1x1x1 exact edges enabled!
                            // Leave vanilla quart cell untouched so outside blocks keep their biome,
                            // and record exact overrides for the inside blocks.
                            for (int dz = 0; dz < 4; dz++) {
                                for (int dx = 0; dx < 4; dx++) {
                                    int wx = baseX + dx;
                                    int wz = baseZ + dz;
                                    if (!sel.contains(wx, wz)) continue;
                                    int lx = (lqx << 2) + dx;
                                    int lz = (lqz << 2) + dz;
                                    if (hMode == BiomeWandConfig.HeightMode.FULL) {
                                        mutExact.setColumn(lx, lz, targetId);
                                    } else if (hMode == BiomeWandConfig.HeightMode.SURFACE) {
                                        int sy = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, lx, lz);
                                        mutExact.setColumnAbove(lx, lz, Math.max(level.getMinBuildHeight(), sy - 8), targetId);
                                    } else {
                                        for (int y = sel.minY; y <= sel.maxY; y++) {
                                            mutExact.setBlock(lx, y, lz, targetId);
                                        }
                                    }
                                    exactTouched = true;
                                }
                            }
                        }
                    }
                }

                // Apply vanilla quart changes via fillBiomesFromNoise (safe & standard in 1.21.1)
                boolean anyFullQuart = false;
                for (boolean b : fullInsideQuart) {
                    if (b) {
                        anyFullQuart = true;
                        break;
                    }
                }

                if (anyFullQuart) {
                    // Snapshot & mutate quart cells
                    final int chunkQX = cx << 2;
                    final int chunkQZ = cz << 2;
                    int fullMinQY = hMode == BiomeWandConfig.HeightMode.SLICE ? QuartPos.fromBlock(sel.minY + 3) : minQY;
                    int fullMaxQY = hMode == BiomeWandConfig.HeightMode.SLICE ? QuartPos.fromBlock(sel.maxY - 3) : maxQY;

                    BiomeResolver resolver = (qx, qy, qz, sampler) -> {
                        int lqx = qx - chunkQX;
                        int lqz = qz - chunkQZ;
                        Holder<Biome> existing = chunk.getNoiseBiome(qx, qy, qz);
                        if (lqx >= 0 && lqx < 4 && lqz >= 0 && lqz < 4) {
                            int qIdx = (lqz << 2) | lqx;
                            int effMinQY = hMode == BiomeWandConfig.HeightMode.SURFACE ? quartMinQY[qIdx] : fullMinQY;
                            if (fullInsideQuart[qIdx] && qy >= effMinQY && qy <= fullMaxQY) {
                                if (!existing.equals(targetHolder)) {
                                    chunkBackups.add(new TerraformUndoStore.QuartEntry(qx, qy, qz, existing));
                                }
                                return targetHolder;
                            }
                        }
                        return existing;
                    };
                    chunk.fillBiomesFromNoise(resolver, level.getChunkSource().randomState().sampler());
                    chunk.setUnsaved(true);
                    vanillaTouched = true;

                    // If Y-slice mode with exactEdges, also handle top/bottom partial Y quart bands
                    if (hMode == BiomeWandConfig.HeightMode.SLICE && cfg.exactEdges()) {
                        for (int lqz = 0; lqz < 4; lqz++) {
                            for (int lqx = 0; lqx < 4; lqx++) {
                                if (!fullInsideQuart[(lqz << 2) | lqx]) continue;
                                int topEdgeStart = (fullMaxQY + 1) << 2;
                                int botEdgeEnd = (fullMinQY << 2) - 1;
                                for (int dz = 0; dz < 4; dz++) {
                                    for (int dx = 0; dx < 4; dx++) {
                                        int lx = (lqx << 2) + dx;
                                        int lz = (lqz << 2) + dz;
                                        for (int y = sel.minY; y <= Math.min(sel.maxY, botEdgeEnd); y++) {
                                            mutExact.setBlock(lx, y, lz, targetId);
                                            exactTouched = true;
                                        }
                                        for (int y = Math.max(sel.minY, topEdgeStart); y <= sel.maxY; y++) {
                                            mutExact.setBlock(lx, y, lz, targetId);
                                            exactTouched = true;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (exactTouched) {
                    prevExact.put(chunkKey, beforeSnap);
                    ExactBiomes.ChunkSnapshot afterSnap = mutExact.freeze();
                    exact.setChunk(level, chunkKey, afterSnap);
                    exactPackets.add(new ExactBiomes.ChunkPacketData(chunkKey, afterSnap));
                }

                if (vanillaTouched) {
                    vanillaModified.add(chunk);
                    if (!chunkBackups.isEmpty()) {
                        quartBackups.put(chunkKey, chunkBackups);
                    }
                } else if (exactTouched) {
                    // Still resend/refresh chunk on client
                    vanillaModified.add(chunk);
                }

                // Optional: clear snow layers & surface ice in selected columns
                if (cfg.clearSnow()) {
                    BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
                    for (int lz = 0; lz < 16; lz++) {
                        int wz = chunkMinZ + lz;
                        for (int lx = 0; lx < 16; lx++) {
                            int wx = chunkMinX + lx;
                            if (!sel.contains(wx, wz)) continue;
                            int surfY = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz);
                            for (int y = Math.max(sel.minY, surfY - 2); y <= Math.min(sel.maxY, surfY + 1); y++) {
                                mp.set(wx, y, wz);
                                BlockState st = level.getBlockState(mp);
                                if (st.is(Blocks.SNOW)) {
                                    prevSnow.put(mp.immutable(), st);
                                    level.setBlock(mp, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                                } else if (st.is(Blocks.ICE)) {
                                    prevSnow.put(mp.immutable(), st);
                                    level.setBlock(mp, Blocks.WATER.defaultBlockState(), Block.UPDATE_CLIENTS);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Persist undo snapshot compressed to disk (0 bytes retained in server RAM)
        TerraformUndoStore.saveBiome(level, player.getUUID(), quartBackups, prevExact, prevSnow);

        // Sync vanilla biomes + exact 1x1x1 overrides to all tracking players
        if (!vanillaModified.isEmpty()) {
            level.getChunkSource().chunkMap.resendBiomesForChunks(vanillaModified);
        }
        if (!exactPackets.isEmpty()) {
            TerraformNetwork.broadcastExactBiomes(level, false, exactPackets);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.EXPERIENCE_ORB_PICKUP, SoundSource.PLAYERS, 0.8F, 1.15F);
        player.displayClientMessage(
                Component.literal("Changed " + sel.selectedColumns + " columns to " + targetId.getPath()
                                + (cfg.exactEdges() ? " (1x1 exact)" : " (4x4)"))
                        .withStyle(ChatFormatting.GREEN),
                true);
    }

    public static boolean undo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TerraformUndoStore.BiomeUndo entry = TerraformUndoStore.popBiome(level, player.getUUID());
        if (entry == null) {
            player.displayClientMessage(Component.literal("Nothing to undo.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (!level.dimension().equals(entry.dimension())) {
            player.displayClientMessage(Component.literal("Last biome change was in another dimension.").withStyle(ChatFormatting.YELLOW), true);
            return false;
        }

        ExactBiomes exact = ExactBiomes.get(level);
        List<ChunkAccess> modified = new ArrayList<>();
        List<ExactBiomes.ChunkPacketData> exactPackets = new ArrayList<>();

        for (Map.Entry<Long, List<TerraformUndoStore.QuartEntry>> e : entry.vanillaQuarts().entrySet()) {
            ChunkPos cp = new ChunkPos(e.getKey());
            if (!level.hasChunk(cp.x, cp.z)) continue;
            LevelChunk chunk = level.getChunk(cp.x, cp.z);
            Map<Long, Holder<Biome>> lookup = new HashMap<>(e.getValue().size());
            for (TerraformUndoStore.QuartEntry b : e.getValue()) {
                lookup.put(BlockPos.asLong(b.qx(), b.qy(), b.qz()), b.biome());
            }
            chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
                Holder<Biome> old = lookup.get(BlockPos.asLong(qx, qy, qz));
                return old != null ? old : chunk.getNoiseBiome(qx, qy, qz);
            }, level.getChunkSource().randomState().sampler());
            chunk.setUnsaved(true);
            modified.add(chunk);
        }

        for (Map.Entry<Long, ExactBiomes.ChunkSnapshot> e : entry.prevExact().entrySet()) {
            ExactBiomes.ChunkSnapshot snap = e.getValue();
            if (snap == null) snap = new ExactBiomes.ChunkSnapshot(new ResourceLocation[0], null, null);
            exact.setChunk(level, e.getKey(), snap);
            exactPackets.add(new ExactBiomes.ChunkPacketData(e.getKey(), snap));
            ChunkPos cp = new ChunkPos(e.getKey());
            if (level.hasChunk(cp.x, cp.z)) {
                LevelChunk chunk = level.getChunk(cp.x, cp.z);
                if (!modified.contains(chunk)) modified.add(chunk);
            }
        }

        for (Map.Entry<BlockPos, BlockState> e : entry.prevSnow().entrySet()) {
            level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS);
        }

        if (!modified.isEmpty()) {
            level.getChunkSource().chunkMap.resendBiomesForChunks(modified);
        }
        if (!exactPackets.isEmpty()) {
            TerraformNetwork.broadcastExactBiomes(level, false, exactPackets);
        }

        player.displayClientMessage(Component.literal("Undid last biome change.").withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    private BiomeChanger() {
    }
}
