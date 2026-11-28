package dev.watdafawx.foxstweaks.terraform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Shared 2D footprint solver for {@link BiomeWandConfig} (Box, Radius Brush, Flood-Fill Biome).
 * Used identically on the server when applying biome changes and on the client when building the
 * cached 3D perimeter and map preview overlays.
 */
public final class BiomeSelection {
    public final int minX;
    public final int minZ;
    public final int width;
    public final int depth;
    public final int minY;
    public final int maxY;
    public final int selectedColumns;
    private final BitSet mask;

    public record Edge(int x1, int z1, int x2, int z2) {
    }

    private BiomeSelection(int minX, int minZ, int width, int depth, int minY, int maxY, BitSet mask, int count) {
        this.minX = minX;
        this.minZ = minZ;
        this.width = width;
        this.depth = depth;
        this.minY = minY;
        this.maxY = maxY;
        this.mask = mask;
        this.selectedColumns = count;
    }

    public int maxX() {
        return minX + width - 1;
    }

    public int maxZ() {
        return minZ + depth - 1;
    }

    public boolean contains(int x, int z) {
        int lx = x - minX;
        int lz = z - minZ;
        if (lx < 0 || lx >= width || lz < 0 || lz >= depth) return false;
        return mask.get(lz * width + lx);
    }

    @Nullable
    public static BiomeSelection compute(Level level, BiomeWandConfig cfg, int maxRadiusCap) {
        if (!cfg.hasSelection()) return null;

        int worldMinY = level.getMinBuildHeight();
        int worldMaxY = level.getMaxBuildHeight() - 1;
        int minY = cfg.heightMode() == BiomeWandConfig.HeightMode.SLICE
                ? Math.max(worldMinY, Math.min(cfg.sliceMinY(), cfg.sliceMaxY()))
                : worldMinY;
        int maxY = cfg.heightMode() == BiomeWandConfig.HeightMode.SLICE
                ? Math.min(worldMaxY, Math.max(cfg.sliceMinY(), cfg.sliceMaxY()))
                : worldMaxY;

        return switch (cfg.mode()) {
            case BOX -> computeBox(cfg.pos1().orElseThrow(), cfg.pos2().orElseThrow(), minY, maxY, maxRadiusCap);
            case BRUSH -> computeBrush(cfg.pos1().orElseThrow(), Math.min(cfg.brushRadius(), maxRadiusCap), minY, maxY);
            case FLOOD_FILL -> computeFloodFill(level, cfg.pos1().orElseThrow(), Math.min(cfg.floodRadius(), maxRadiusCap), minY, maxY);
            case WHOLE_BIOME -> computeFloodFill(level, cfg.pos1().orElseThrow(), Math.min(256, maxRadiusCap), minY, maxY);
        };
    }

    private static BiomeSelection computeBox(BlockPos p1, BlockPos p2, int minY, int maxY, int maxRadiusCap) {
        int x1 = Math.min(p1.getX(), p2.getX());
        int x2 = Math.max(p1.getX(), p2.getX());
        int z1 = Math.min(p1.getZ(), p2.getZ());
        int z2 = Math.max(p1.getZ(), p2.getZ());
        int maxSpan = maxRadiusCap * 2 + 1;
        if (x2 - x1 + 1 > maxSpan) x2 = x1 + maxSpan - 1;
        if (z2 - z1 + 1 > maxSpan) z2 = z1 + maxSpan - 1;
        int w = x2 - x1 + 1;
        int d = z2 - z1 + 1;
        BitSet mask = new BitSet(w * d);
        mask.set(0, w * d);
        return new BiomeSelection(x1, z1, w, d, minY, maxY, mask, w * d);
    }

    private static BiomeSelection computeBrush(BlockPos center, int radius, int minY, int maxY) {
        int r = Math.max(1, radius);
        int x1 = center.getX() - r;
        int z1 = center.getZ() - r;
        int w = r * 2 + 1;
        int d = r * 2 + 1;
        BitSet mask = new BitSet(w * d);
        double rSq = (r + 0.5) * (r + 0.5);
        int count = 0;
        for (int lz = 0; lz < d; lz++) {
            double dz = lz - r;
            for (int lx = 0; lx < w; lx++) {
                double dx = lx - r;
                if (dx * dx + dz * dz <= rSq) {
                    mask.set(lz * w + lx);
                    count++;
                }
            }
        }
        return new BiomeSelection(x1, z1, w, d, minY, maxY, mask, count);
    }

    /**
     * Flood-fills connected columns matching the seed block's biome within {@code maxRadius}.
     * Runs on a 4x4 grid first when large, or exact 1x1 when radius <= 64, staying well under a
     * millisecond even at 128-block radius.
     */
    private static BiomeSelection computeFloodFill(Level level, BlockPos seed, int maxRadius, int minY, int maxY) {
        int r = Math.max(4, maxRadius);
        int step = r > 128 ? 4 : (r > 48 ? 2 : 1);
        int seedY = seed.getY();
        Holder<Biome> seedBiome = level.getBiome(seed);
        var seedKey = seedBiome.unwrapKey().orElse(null);
        if (seedKey == null) return computeBrush(seed, 4, minY, maxY);

        int gridR = r / step;
        int gw = gridR * 2 + 1;
        BitSet visited = new BitSet(gw * gw);
        BitSet matched = new BitSet(gw * gw);
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        int centerIdx = gridR * gw + gridR;
        visited.set(centerIdx);
        matched.set(centerIdx);
        queue.add(centerIdx);

        int minGX = gridR, maxGX = gridR, minGZ = gridR, maxGZ = gridR;
        int rSq = gridR * gridR;
        final int[] dx = {1, -1, 0, 0};
        final int[] dz = {0, 0, 1, -1};

        while (!queue.isEmpty()) {
            int idx = queue.poll();
            int gx = idx % gw;
            int gz = idx / gw;
            if (gx < minGX) minGX = gx;
            if (gx > maxGX) maxGX = gx;
            if (gz < minGZ) minGZ = gz;
            if (gz > maxGZ) maxGZ = gz;

            for (int dir = 0; dir < 4; dir++) {
                int nx = gx + dx[dir];
                int nz = gz + dz[dir];
                if (nx < 0 || nx >= gw || nz < 0 || nz >= gw) continue;
                int offX = nx - gridR;
                int offZ = nz - gridR;
                if (offX * offX + offZ * offZ > rSq) continue;
                int nIdx = nz * gw + nx;
                if (visited.get(nIdx)) continue;
                visited.set(nIdx);

                int wx = seed.getX() + offX * step;
                int wz = seed.getZ() + offZ * step;
                if (!level.hasChunk(wx >> 4, wz >> 4)) continue;

                cursor.set(wx, seedY, wz);
                if (level.getBiome(cursor).is(seedKey)) {
                    matched.set(nIdx);
                    queue.add(nIdx);
                }
            }
        }

        int outMinX = seed.getX() + (minGX - gridR) * step;
        int outMinZ = seed.getZ() + (minGZ - gridR) * step;
        int outW = (maxGX - minGX + 1) * step;
        int outD = (maxGZ - minGZ + 1) * step;
        BitSet mask = new BitSet(outW * outD);
        int count = 0;
        for (int gz = minGZ; gz <= maxGZ; gz++) {
            for (int gx = minGX; gx <= maxGX; gx++) {
                if (!matched.get(gz * gw + gx)) continue;
                int baseX = (gx - minGX) * step;
                int baseZ = (gz - minGZ) * step;
                for (int sz = 0; sz < step; sz++) {
                    for (int sx = 0; sx < step; sx++) {
                        mask.set((baseZ + sz) * outW + (baseX + sx));
                        count++;
                    }
                }
            }
        }
        return new BiomeSelection(outMinX, outMinZ, outW, outD, minY, maxY, mask, count);
    }

    /**
     * Extracts merged outer boundary edges (horizontal & vertical segments) in world coordinates.
     * Used by the 3D perimeter renderer so even a 20,000-block flood-fill draws in a few hundred
     * line segments instead of per-block boxes.
     */
    public List<Edge> buildPerimeterEdges() {
        List<Edge> edges = new ArrayList<>();
        // Horizontal edges (along X, facing north or south)
        for (int lz = 0; lz < depth; lz++) {
            int wz = minZ + lz;
            // North edge of row lz
            int runStart = -1;
            for (int lx = 0; lx <= width; lx++) {
                boolean hasEdge = lx < width && mask.get(lz * width + lx)
                        && (lz == 0 || !mask.get((lz - 1) * width + lx));
                if (hasEdge && runStart < 0) runStart = lx;
                else if (!hasEdge && runStart >= 0) {
                    edges.add(new Edge(minX + runStart, wz, minX + lx, wz));
                    runStart = -1;
                }
            }
            // South edge of row lz
            runStart = -1;
            for (int lx = 0; lx <= width; lx++) {
                boolean hasEdge = lx < width && mask.get(lz * width + lx)
                        && (lz == depth - 1 || !mask.get((lz + 1) * width + lx));
                if (hasEdge && runStart < 0) runStart = lx;
                else if (!hasEdge && runStart >= 0) {
                    edges.add(new Edge(minX + runStart, wz + 1, minX + lx, wz + 1));
                    runStart = -1;
                }
            }
        }
        // Vertical edges (along Z, facing west or east)
        for (int lx = 0; lx < width; lx++) {
            int wx = minX + lx;
            // West edge of col lx
            int runStart = -1;
            for (int lz = 0; lz <= depth; lz++) {
                boolean hasEdge = lz < depth && mask.get(lz * width + lx)
                        && (lx == 0 || !mask.get(lz * width + (lx - 1)));
                if (hasEdge && runStart < 0) runStart = lz;
                else if (!hasEdge && runStart >= 0) {
                    edges.add(new Edge(wx, minZ + runStart, wx, minZ + lz));
                    runStart = -1;
                }
            }
            // East edge of col lx
            runStart = -1;
            for (int lz = 0; lz <= depth; lz++) {
                boolean hasEdge = lz < depth && mask.get(lz * width + lx)
                        && (lx == width - 1 || !mask.get(lz * width + (lx + 1)));
                if (hasEdge && runStart < 0) runStart = lz;
                else if (!hasEdge && runStart >= 0) {
                    edges.add(new Edge(wx + 1, minZ + runStart, wx + 1, minZ + lz));
                    runStart = -1;
                }
            }
        }
        return edges;
    }

    /**
     * Builds a simplified 2D polygon outline (ordered BlockPos points) suitable for JourneyMap's
     * {@code MapPolygon} and FTB Chunks' map overlay.
     */
    public List<BlockPos> buildMapOutlinePolygon(int y) {
        // For Box mode, exact 4 corners
        if (selectedColumns == width * depth) {
            return List.of(
                    new BlockPos(minX, y, minZ),
                    new BlockPos(minX + width, y, minZ),
                    new BlockPos(minX + width, y, minZ + depth),
                    new BlockPos(minX, y, minZ + depth)
            );
        }
        // For arbitrary footprints (Brush / Flood-fill), trace outer left/right silhouette per row
        // sampled at <= 48 vertices so map polygons stay crisp and fast.
        int step = Math.max(1, depth / 24);
        List<BlockPos> left = new ArrayList<>();
        List<BlockPos> right = new ArrayList<>();
        for (int lz = 0; lz < depth; lz += step) {
            int firstX = -1, lastX = -1;
            for (int lx = 0; lx < width; lx++) {
                if (mask.get(lz * width + lx)) {
                    if (firstX < 0) firstX = lx;
                    lastX = lx;
                }
            }
            if (firstX >= 0) {
                left.add(new BlockPos(minX + firstX, y, minZ + lz));
                right.add(new BlockPos(minX + lastX + 1, y, minZ + lz));
            }
        }
        // Always include last row
        int lastRow = depth - 1;
        if (lastRow > 0 && lastRow % step != 0) {
            int firstX = -1, lastX = -1;
            for (int lx = 0; lx < width; lx++) {
                if (mask.get(lastRow * width + lx)) {
                    if (firstX < 0) firstX = lx;
                    lastX = lx;
                }
            }
            if (firstX >= 0) {
                left.add(new BlockPos(minX + firstX, y, minZ + lastRow + 1));
                right.add(new BlockPos(minX + lastX + 1, y, minZ + lastRow + 1));
            }
        }
        List<BlockPos> poly = new ArrayList<>(left.size() + right.size());
        poly.addAll(left);
        for (int i = right.size() - 1; i >= 0; i--) {
            poly.add(right.get(i));
        }
        return poly;
    }
}
