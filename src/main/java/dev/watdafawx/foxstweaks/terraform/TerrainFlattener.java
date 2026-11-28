package dev.watdafawx.foxstweaks.terraform;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Natural Terrain Flattener: computes a smooth, configurable, edge-feathered heightmap and applies
 * column edits while preserving topsoil/subsoil strata so the result looks naturally generated.
 *
 * <p>{@link #computePlan(Level, FlattenWandConfig, int)} is pure and runs identically on the client
 * (for the 3D wireframe surface preview) and on the server (when applying).
 */
public final class TerrainFlattener {
    private static final int COLUMNS_PER_TICK = 192;

    public record Plan(
            int minX,
            int minZ,
            int width,
            int depth,
            int targetY,
            int[] oldH,
            int[] newH,
            int[] waterTop,
            boolean[] inMask,
            boolean[] hasTree
    ) {
        public int index(int lx, int lz) {
            return lz * width + lx;
        }
    }

    private static final class ActiveJob {
        final ServerLevel level;
        final UUID playerId;
        final Plan plan;
        final FlattenWandConfig cfg;
        final Map<BlockPos, BlockState> backup = new HashMap<>();
        int cursor = 0;
        int changedColumns = 0;

        ActiveJob(ServerLevel level, UUID playerId, Plan plan, FlattenWandConfig cfg) {
            this.level = level;
            this.playerId = playerId;
            this.plan = plan;
            this.cfg = cfg;
        }
    }

    private static final Queue<ActiveJob> JOBS = new ArrayDeque<>();

    @Nullable
    public static Plan computePlan(Level level, FlattenWandConfig cfg, int maxRadiusCap) {
        if (!cfg.hasSelection()) return null;

        int minX, maxX, minZ, maxZ;
        int centerX = 0, centerZ = 0;
        int r = Math.max(1, Math.min(cfg.radius(), maxRadiusCap));
        boolean isBrush = cfg.mode() == FlattenWandConfig.Mode.BRUSH;

        if (isBrush) {
            BlockPos c = cfg.pos1().orElseThrow();
            centerX = c.getX();
            centerZ = c.getZ();
            minX = centerX - r;
            maxX = centerX + r;
            minZ = centerZ - r;
            maxZ = centerZ + r;
        } else {
            BlockPos p1 = cfg.pos1().orElseThrow();
            BlockPos p2 = cfg.pos2().orElseThrow();
            minX = Math.min(p1.getX(), p2.getX());
            maxX = Math.max(p1.getX(), p2.getX());
            minZ = Math.min(p1.getZ(), p2.getZ());
            maxZ = Math.max(p1.getZ(), p2.getZ());
            int maxSpan = maxRadiusCap * 2 + 1;
            if (maxX - minX + 1 > maxSpan) maxX = minX + maxSpan - 1;
            if (maxZ - minZ + 1 > maxSpan) maxZ = minZ + maxSpan - 1;
        }

        int width = maxX - minX + 1;
        int depth = maxZ - minZ + 1;
        int total = width * depth;

        int[] oldH = new int[total];
        int[] waterTop = new int[total];
        java.util.Arrays.fill(waterTop, Integer.MIN_VALUE);
        boolean[] inMask = new boolean[total];
        boolean[] hasTree = new boolean[total];
        float[] weight = new float[total];

        double rSq = (r + 0.5) * (r + 0.5);
        long sumH = 0;
        int countH = 0;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        for (int lz = 0; lz < depth; lz++) {
            int wz = minZ + lz;
            for (int lx = 0; lx < width; lx++) {
                int wx = minX + lx;
                int idx = lz * width + lx;

                float edgeFactor;
                if (isBrush) {
                    double dx = wx - centerX;
                    double dz = wz - centerZ;
                    double distSq = dx * dx + dz * dz;
                    if (distSq > rSq) continue;
                    double normDist = Math.sqrt(distSq) / Math.max(1.0, r);
                    edgeFactor = computeFeatherWeight((float) (1.0 - normDist), cfg.feather());
                } else {
                    float distToEdge = Math.min(
                            Math.min(lx, width - 1 - lx),
                            Math.min(lz, depth - 1 - lz)
                    );
                    float maxDist = Math.max(1.0F, Math.min(width, depth) * 0.5F);
                    edgeFactor = computeFeatherWeight(distToEdge / maxDist, cfg.feather());
                }

                inMask[idx] = true;
                weight[idx] = edgeFactor;

                if (!level.hasChunk(wx >> 4, wz >> 4)) {
                    oldH[idx] = cfg.pos1().map(BlockPos::getY).orElse(64);
                    continue;
                }

                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz) - 1;
                int minBuild = level.getMinBuildHeight();
                boolean foundTree = false;
                int groundY = minBuild;
                int highestWater = Integer.MIN_VALUE;

                for (int y = Math.min(top, level.getMaxBuildHeight() - 1); y >= minBuild; y--) {
                    mp.set(wx, y, wz);
                    BlockState st = level.getBlockState(mp);
                    if (st.isAir() || isVegetation(st) || isWaterOrAquatic(st)) {
                        if (!st.getFluidState().isEmpty() && highestWater == Integer.MIN_VALUE) {
                            highestWater = y;
                        }
                        if (st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES)) {
                            foundTree = true;
                        }
                        continue;
                    }
                    groundY = y;
                    break;
                }

                oldH[idx] = groundY;
                waterTop[idx] = highestWater;
                hasTree[idx] = foundTree;
                sumH += groundY;
                countH++;
            }
        }

        int targetY = switch (cfg.targetYMode()) {
            case CLICKED -> cfg.pos1().map(BlockPos::getY).orElse(64);
            case AVERAGE -> countH > 0 ? (int) Math.round((double) sumH / countH) : 64;
            case CUSTOM -> cfg.customY();
        };

        // Optional separable Gaussian-like blur pass for smoothness (removes jagged spikes & cliffs)
        float[] smoothed = new float[total];
        for (int i = 0; i < total; i++) smoothed[i] = oldH[i];

        int blurR = Math.round(cfg.smoothness() * 4.0F);
        if (blurR > 0) {
            float[] temp = new float[total];
            // Horizontal pass
            for (int lz = 0; lz < depth; lz++) {
                for (int lx = 0; lx < width; lx++) {
                    int idx = lz * width + lx;
                    if (!inMask[idx]) continue;
                    float acc = 0, wSum = 0;
                    for (int k = -blurR; k <= blurR; k++) {
                        int nx = Mth.clamp(lx + k, 0, width - 1);
                        int nIdx = lz * width + nx;
                        if (!inMask[nIdx]) continue;
                        float kw = blurR + 1 - Math.abs(k);
                        acc += smoothed[nIdx] * kw;
                        wSum += kw;
                    }
                    temp[idx] = wSum > 0 ? acc / wSum : smoothed[idx];
                }
            }
            // Vertical pass
            for (int lz = 0; lz < depth; lz++) {
                for (int lx = 0; lx < width; lx++) {
                    int idx = lz * width + lx;
                    if (!inMask[idx]) continue;
                    float acc = 0, wSum = 0;
                    for (int k = -blurR; k <= blurR; k++) {
                        int nz = Mth.clamp(lz + k, 0, depth - 1);
                        int nIdx = nz * width + lx;
                        if (!inMask[nIdx]) continue;
                        float kw = blurR + 1 - Math.abs(k);
                        acc += temp[nIdx] * kw;
                        wSum += kw;
                    }
                    smoothed[idx] = wSum > 0 ? acc / wSum : temp[idx];
                }
            }
        }

        // Compute final target heights: blend between original -> smoothed -> targetY using flatness & feather
        int[] newH = new int[total];
        float flatness = Mth.clamp(cfg.flatness(), 0.0F, 1.0F);
        float smoothMix = Mth.clamp(cfg.smoothness(), 0.0F, 1.0F);

        for (int i = 0; i < total; i++) {
            if (!inMask[i]) {
                newH[i] = oldH[i];
                continue;
            }
            if (cfg.treeMode() == FlattenWandConfig.TreeMode.KEEP && hasTree[i]) {
                newH[i] = oldH[i];
                continue;
            }
            if (cfg.waterMode() == FlattenWandConfig.WaterMode.KEEP && waterTop[i] != Integer.MIN_VALUE) {
                newH[i] = oldH[i];
                continue;
            }
            float base = Mth.lerp(smoothMix, (float) oldH[i], smoothed[i]);
            float flattened = Mth.lerp(flatness, base, (float) targetY);
            float blended = Mth.lerp(weight[i], (float) oldH[i], flattened);
            newH[i] = Mth.clamp(Math.round(blended), level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2);
        }

        return new Plan(minX, minZ, width, depth, targetY, oldH, newH, waterTop, inMask, hasTree);
    }

    private static float computeFeatherWeight(float distFromEdgeNorm, float feather) {
        if (feather <= 0.001F) return 1.0F;
        float t = Mth.clamp(distFromEdgeNorm / feather, 0.0F, 1.0F);
        // Smoothstep: 3t^2 - 2t^3
        return t * t * (3.0F - 2.0F * t);
    }

    public static void enqueue(ServerPlayer player, FlattenWandConfig cfg) {
        if (!ServerConfig.terraformEnabled()) {
            player.displayClientMessage(Component.literal("Terraform tools are disabled on this server.").withStyle(ChatFormatting.RED), true);
            return;
        }
        ServerLevel level = player.serverLevel();
        Plan plan = computePlan(level, cfg, ServerConfig.flattenWandMaxRadius());
        if (plan == null) {
            player.displayClientMessage(Component.literal("Select an area first.").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        JOBS.add(new ActiveJob(level, player.getUUID(), plan, cfg));
        player.displayClientMessage(
                Component.literal("Flattening terrain (" + Math.round(cfg.flatness() * 100) + "% flat, "
                                + Math.round(cfg.smoothness() * 100) + "% smooth)...")
                        .withStyle(ChatFormatting.GREEN), true);
    }

    /** Called every server tick to apply queued flattening jobs without spiking TPS. */
    public static void onServerTick() {
        ActiveJob job = JOBS.peek();
        if (job == null) return;

        int total = job.plan.width * job.plan.depth;
        int processed = 0;

        while (job.cursor < total && processed < COLUMNS_PER_TICK) {
            int idx = job.cursor++;
            if (!job.plan.inMask[idx]) continue;

            int lx = idx % job.plan.width;
            int lz = idx / job.plan.width;
            int wx = job.plan.minX + lx;
            int wz = job.plan.minZ + lz;
            if (!job.level.hasChunk(wx >> 4, wz >> 4)) continue;

            int oldY = job.plan.oldH[idx];
            int newY = job.plan.newH[idx];
            int wTop = job.plan.waterTop[idx];
            boolean treeCol = job.plan.hasTree[idx];
            boolean hasWater = wTop != Integer.MIN_VALUE;

            if (hasWater && job.cfg.waterMode() == FlattenWandConfig.WaterMode.KEEP) {
                continue;
            }
            if (oldY == newY
                    && !(treeCol && job.cfg.treeMode() == FlattenWandConfig.TreeMode.REMOVE)
                    && !(hasWater && job.cfg.waterMode() != FlattenWandConfig.WaterMode.KEEP)) {
                continue;
            }

            if (applyColumn(job, wx, wz, oldY, newY, wTop, treeCol)) {
                job.changedColumns++;
            }
            processed++;
        }

        if (job.cursor >= total) {
            JOBS.poll();
            if (!job.backup.isEmpty()) {
                // Flush undo snapshot compressed to disk and immediately free RAM
                TerraformUndoStore.saveFlatten(job.level, job.playerId, job.backup);
                job.backup.clear();
            }
            ServerPlayer player = job.level.getServer().getPlayerList().getPlayer(job.playerId);
            if (player != null) {
                job.level.playSound(null, player.blockPosition(), SoundEvents.GRASS_PLACE, SoundSource.PLAYERS, 0.9F, 1.0F);
                player.displayClientMessage(
                        Component.literal("Flattened " + job.changedColumns + " columns cleanly.")
                                .withStyle(ChatFormatting.GREEN), true);
            }
        }
    }

    private static boolean applyColumn(ActiveJob job, int wx, int wz, int oldY, int newY, int waterTop, boolean treeCol) {
        ServerLevel level = job.level;
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        // Safety: skip column if protected block entity or non-natural block is in the edited range
        if (job.cfg.protectBlockEntities() || job.cfg.naturalOnly()) {
            int checkLow = Math.min(oldY, newY) - 4;
            int checkHigh = Math.max(Math.max(oldY, newY), waterTop == Integer.MIN_VALUE ? oldY : waterTop) + 1;
            for (int y = checkLow; y <= checkHigh; y++) {
                mp.set(wx, y, wz);
                if (job.cfg.protectBlockEntities() && level.getBlockEntity(mp) != null) return false;
                if (job.cfg.naturalOnly()) {
                    BlockState st = level.getBlockState(mp);
                    if (!st.isAir() && !isNaturalOrVegetation(st)) return false;
                }
            }
        }

        // Capture surface strata (top block + 3 subsurface blocks) so the result looks naturally generated
        BlockState surface = level.getBlockState(mp.set(wx, oldY, wz));
        if (surface.isAir() || isVegetation(surface) || isWaterOrAquatic(surface)
                || (waterTop != Integer.MIN_VALUE && job.cfg.waterMode() == FlattenWandConfig.WaterMode.FILL && (surface.is(Blocks.DIRT) || surface.is(Blocks.GRAVEL) || surface.is(Blocks.CLAY)))) {
            surface = Blocks.GRASS_BLOCK.defaultBlockState();
        }
        BlockState sub1 = pickSubsurface(level.getBlockState(mp.set(wx, oldY - 1, wz)), surface);
        BlockState sub2 = pickSubsurface(level.getBlockState(mp.set(wx, oldY - 2, wz)), sub1);
        BlockState sub3 = pickSubsurface(level.getBlockState(mp.set(wx, oldY - 3, wz)), sub2);
        BlockState deep = newY < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();

        // Detect tree sapling if REPLANT mode is active
        BlockState saplingToPlant = null;
        int topScan = Math.max(level.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz), waterTop == Integer.MIN_VALUE ? oldY : waterTop) + 2;
        if (treeCol && job.cfg.treeMode() != FlattenWandConfig.TreeMode.KEEP) {
            for (int y = oldY + 1; y <= Math.min(topScan, level.getMaxBuildHeight() - 1); y++) {
                mp.set(wx, y, wz);
                BlockState st = level.getBlockState(mp);
                if (st.is(BlockTags.LOGS) && saplingToPlant == null && job.cfg.treeMode() == FlattenWandConfig.TreeMode.REPLANT) {
                    saplingToPlant = saplingForLog(st);
                }
                if (isVegetation(st)) {
                    setTracked(job, mp, Blocks.AIR.defaultBlockState());
                }
            }
        } else if (newY != oldY || waterTop != Integer.MIN_VALUE) {
            // Clear small surface grass/flowers/snow/sugarcane/water above oldY
            for (int y = oldY + 1; y <= Math.min(Math.max(oldY + 3, topScan), level.getMaxBuildHeight() - 1); y++) {
                mp.set(wx, y, wz);
                BlockState st = level.getBlockState(mp);
                if (isGroundCover(st) || (isWaterOrAquatic(st) && job.cfg.waterMode() != FlattenWandConfig.WaterMode.KEEP)) {
                    setTracked(job, mp, Blocks.AIR.defaultBlockState());
                }
            }
        }

        if (newY < oldY) {
            // Lowering: clear above newY, then shift strata down to newY
            for (int y = oldY; y > newY; y--) {
                setTracked(job, mp.set(wx, y, wz), Blocks.AIR.defaultBlockState());
            }
            setTracked(job, mp.set(wx, newY, wz), surface);
            setTracked(job, mp.set(wx, newY - 1, wz), sub1);
            setTracked(job, mp.set(wx, newY - 2, wz), sub2);
            setTracked(job, mp.set(wx, newY - 3, wz), sub3);
        } else if (newY > oldY) {
            // Raising: fill stone underneath, subsurface below cap, surface on top
            for (int y = oldY; y <= newY - 4; y++) {
                setTracked(job, mp.set(wx, y, wz), y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : deep);
            }
            if (newY - 3 >= oldY) setTracked(job, mp.set(wx, newY - 3, wz), sub3);
            if (newY - 2 >= oldY) setTracked(job, mp.set(wx, newY - 2, wz), sub2);
            if (newY - 1 >= oldY) setTracked(job, mp.set(wx, newY - 1, wz), sub1);
            setTracked(job, mp.set(wx, newY, wz), surface);
        } else if (waterTop != Integer.MIN_VALUE && job.cfg.waterMode() == FlattenWandConfig.WaterMode.FILL) {
            setTracked(job, mp.set(wx, newY, wz), surface);
        }

        // Smooth Pond mode: refill still source water up to original waterTop (eliminating flowing currents)
        if (waterTop != Integer.MIN_VALUE && job.cfg.waterMode() == FlattenWandConfig.WaterMode.LEVEL && newY < waterTop) {
            for (int y = newY + 1; y <= waterTop; y++) {
                setTracked(job, mp.set(wx, y, wz), Blocks.WATER.defaultBlockState());
            }
        }

        if (saplingToPlant != null && (surface.is(BlockTags.DIRT) || surface.is(Blocks.GRASS_BLOCK))) {
            setTracked(job, mp.set(wx, newY + 1, wz), saplingToPlant);
        }

        return true;
    }

    private static void setTracked(ActiveJob job, BlockPos pos, BlockState newState) {
        BlockPos imm = pos.immutable();
        BlockState prev = job.level.getBlockState(imm);
        if (prev.equals(newState)) return;
        job.backup.putIfAbsent(imm, prev);
        job.level.setBlock(imm, newState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
    }

    public static boolean undo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        TerraformUndoStore.FlattenUndo entry = TerraformUndoStore.popFlatten(level, player.getUUID());
        if (entry == null) {
            player.displayClientMessage(Component.literal("Nothing to undo.").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        if (!level.dimension().equals(entry.dimension())) {
            player.displayClientMessage(Component.literal("Last terrain flatten was in another dimension.").withStyle(ChatFormatting.YELLOW), true);
            return false;
        }
        for (Map.Entry<BlockPos, BlockState> e : entry.previous().entrySet()) {
            if (level.hasChunk(e.getKey().getX() >> 4, e.getKey().getZ() >> 4)) {
                level.setBlock(e.getKey(), e.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            }
        }
        player.displayClientMessage(Component.literal("Undid last terrain flatten (" + entry.previous().size() + " blocks).").withStyle(ChatFormatting.AQUA), true);
        return true;
    }

    private static BlockState pickSubsurface(BlockState candidate, BlockState fallbackSurface) {
        if (!candidate.isAir() && !isVegetation(candidate) && !candidate.is(Blocks.WATER)) {
            if (candidate.is(Blocks.GRASS_BLOCK) || candidate.is(Blocks.MYCELIUM) || candidate.is(Blocks.PODZOL)) {
                return Blocks.DIRT.defaultBlockState();
            }
            return candidate;
        }
        if (fallbackSurface.is(Blocks.SAND)) return Blocks.SANDSTONE.defaultBlockState();
        if (fallbackSurface.is(Blocks.RED_SAND)) return Blocks.RED_SANDSTONE.defaultBlockState();
        return Blocks.DIRT.defaultBlockState();
    }

    private static boolean isVegetation(BlockState st) {
        return st.is(BlockTags.LOGS) || st.is(BlockTags.LEAVES) || isGroundCover(st);
    }

    private static boolean isWaterOrAquatic(BlockState st) {
        return !st.getFluidState().isEmpty()
                || st.is(Blocks.WATER)
                || st.is(Blocks.SEAGRASS) || st.is(Blocks.TALL_SEAGRASS)
                || st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT)
                || st.is(Blocks.LILY_PAD);
    }

    private static boolean isGroundCover(BlockState st) {
        return st.is(BlockTags.FLOWERS) || st.is(BlockTags.REPLACEABLE)
                || st.is(BlockTags.SAPLINGS) || st.is(Blocks.SNOW)
                || st.is(Blocks.SHORT_GRASS) || st.is(Blocks.TALL_GRASS)
                || st.is(Blocks.FERN) || st.is(Blocks.LARGE_FERN)
                || st.is(Blocks.DEAD_BUSH) || st.is(Blocks.VINE)
                || st.is(Blocks.PINK_PETALS) || st.is(Blocks.SUGAR_CANE);
    }

    private static boolean isNaturalOrVegetation(BlockState st) {
        return isVegetation(st)
                || isWaterOrAquatic(st)
                || st.is(BlockTags.DIRT)
                || st.is(BlockTags.BASE_STONE_OVERWORLD)
                || st.is(BlockTags.SAND)
                || st.is(BlockTags.TERRACOTTA)
                || st.is(BlockTags.SNOW)
                || st.is(BlockTags.ICE)
                || st.is(BlockTags.OVERWORLD_CARVER_REPLACEABLES)
                || st.is(Blocks.SANDSTONE) || st.is(Blocks.RED_SANDSTONE)
                || st.is(Blocks.GRAVEL) || st.is(Blocks.CLAY)
                || st.is(Blocks.WATER) || st.is(Blocks.MUD)
                || st.is(Blocks.PACKED_MUD) || st.is(Blocks.CALCITE)
                || st.is(Blocks.TUFF) || st.is(Blocks.DRIPSTONE_BLOCK);
    }

    private static BlockState saplingForLog(BlockState log) {
        if (log.is(BlockTags.BIRCH_LOGS)) return Blocks.BIRCH_SAPLING.defaultBlockState();
        if (log.is(BlockTags.SPRUCE_LOGS)) return Blocks.SPRUCE_SAPLING.defaultBlockState();
        if (log.is(BlockTags.JUNGLE_LOGS)) return Blocks.JUNGLE_SAPLING.defaultBlockState();
        if (log.is(BlockTags.ACACIA_LOGS)) return Blocks.ACACIA_SAPLING.defaultBlockState();
        if (log.is(BlockTags.DARK_OAK_LOGS)) return Blocks.DARK_OAK_SAPLING.defaultBlockState();
        if (log.is(BlockTags.CHERRY_LOGS)) return Blocks.CHERRY_SAPLING.defaultBlockState();
        if (log.is(BlockTags.MANGROVE_LOGS)) return Blocks.MANGROVE_PROPAGULE.defaultBlockState();
        return Blocks.OAK_SAPLING.defaultBlockState();
    }

    private TerrainFlattener() {
    }
}
