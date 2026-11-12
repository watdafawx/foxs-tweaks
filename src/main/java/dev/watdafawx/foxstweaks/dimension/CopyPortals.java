package dev.watdafawx.foxstweaks.dimension;

import java.util.Comparator;
import java.util.Optional;

import javax.annotation.Nullable;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;

/**
 * Portals to the overworld copy are ordinary vanilla nether_portal blocks inside a frame of the
 * configured block, so they need nothing registered. A portal block belongs to this feature exactly when
 * a complete {@link CopyShape} surrounds it; {@code NetherPortalBlockMixin} uses that to keep such a portal
 * alive and to send what steps into it to the other overworld instead of the Nether.
 */
public final class CopyPortals {
    private static final int SEARCH_RADIUS = 128;

    /** The frame around this portal block, if it is one of ours. */
    public static Optional<CopyShape> shapeAround(LevelReader level, BlockPos pos, BlockState state) {
        if (!OverworldCopy.enabled() || !state.is(Blocks.NETHER_PORTAL) || !state.hasProperty(NetherPortalBlock.AXIS)) {
            return Optional.empty();
        }

        CopyShape shape = new CopyShape(level, pos, state.getValue(NetherPortalBlock.AXIS));
        return shape.isComplete() && shape.contains(pos) ? Optional.of(shape) : Optional.empty();
    }

    public static void fill(LevelAccessor level, CopyShape shape) {
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, shape.axis);

        for (int i = 0; i < shape.width; i++) {
            for (int j = 0; j < shape.height; j++) {
                level.setBlock(shape.at(i, j), portal, 18);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------

    @Nullable
    public static DimensionTransition destination(ServerLevel from, Entity entity, BlockPos portalPos, CopyShape source) {
        var key = from.dimension() == OverworldCopy.COPY ? Level.OVERWORLD : OverworldCopy.COPY;
        ServerLevel to = from.getServer().getLevel(key);

        if (to == null) {
            return null;
        }

        WorldBorder border = to.getWorldBorder();
        double scale = DimensionType.getTeleportationScale(from.dimensionType(), to.dimensionType());
        BlockPos target = border.clampToBounds(entity.getX() * scale, entity.getY(), entity.getZ() * scale);
        int dye = PortalColors.get(from).dyeAt(portalPos);

        Optional<BlockPos> found = findExisting(to, target, border);
        BlockUtil.FoundRectangle rect;
        DimensionTransition.PostDimensionTransition post;

        if (found.isPresent()) {
            BlockState state = to.getBlockState(found.get());
            rect = BlockUtil.getLargestRectangleAround(found.get(), state.getValue(BlockStateProperties.HORIZONTAL_AXIS), 21,
                    Direction.Axis.Y, 21, p -> to.getBlockState(p) == state);
            post = DimensionTransition.PLAY_PORTAL_SOUND.then(e -> e.placePortalTicket(found.get()));
        } else {
            Optional<BlockUtil.FoundRectangle> created = createPortal(to, target, source.axis, dye);

            if (created.isEmpty()) {
                LogUtils.getLogger().error("Unable to create a portal in {}, likely target out of worldborder", key.location());
                return null;
            }

            rect = created.get();
            post = DimensionTransition.PLAY_PORTAL_SOUND.then(DimensionTransition.PLACE_PORTAL_TICKET);
        }

        return transition(from, entity, portalPos, source, to, rect, post);
    }

    private static Optional<BlockPos> findExisting(ServerLevel level, BlockPos center, WorldBorder border) {
        PoiManager poi = level.getPoiManager();
        poi.ensureLoadedAndValid(level, center, SEARCH_RADIUS);

        return poi.getInSquare(t -> t.is(PoiTypes.NETHER_PORTAL), center, SEARCH_RADIUS, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(border::isWithinBounds)
                .filter(p -> shapeAround(level, p, level.getBlockState(p)).isPresent())
                .min(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(center)).thenComparingInt(BlockPos::getY));
    }

    private static DimensionTransition transition(ServerLevel from, Entity entity, BlockPos portalPos, CopyShape source,
            ServerLevel to, BlockUtil.FoundRectangle rect, DimensionTransition.PostDimensionTransition post) {
        BlockUtil.FoundRectangle here = BlockUtil.getLargestRectangleAround(portalPos, source.axis, 21, Direction.Axis.Y, 21,
                p -> from.getBlockState(p) == from.getBlockState(portalPos));
        Vec3 relative = entity.getRelativePortalPosition(source.axis, here);

        BlockState exit = to.getBlockState(rect.minCorner);
        Direction.Axis exitAxis = exit.getOptionalValue(BlockStateProperties.HORIZONTAL_AXIS).orElse(Direction.Axis.X);
        EntityDimensions dims = entity.getDimensions(entity.getPose());
        int turn = source.axis == exitAxis ? 0 : 90;
        Vec3 motion = entity.getDeltaMovement();
        Vec3 speed = source.axis == exitAxis ? motion : new Vec3(motion.z, motion.y, -motion.x);

        double along = dims.width() / 2.0 + (rect.axis1Size - dims.width()) * relative.x();
        double up = (rect.axis2Size - dims.height()) * relative.y();
        double across = 0.5 + relative.z();
        boolean x = exitAxis == Direction.Axis.X;
        Vec3 spot = new Vec3(rect.minCorner.getX() + (x ? along : across), rect.minCorner.getY() + up,
                rect.minCorner.getZ() + (x ? across : along));

        spot = net.minecraft.world.level.portal.PortalShape.findCollisionFreePosition(spot, to, entity, dims);
        return new DimensionTransition(to, spot, speed, entity.getYRot() + turn, entity.getXRot(), post);
    }

    // ---------------------------------------------------------------------------------------------

    /** Vanilla's PortalForcer#createPortal, with the configured frame block. The interior is 2x3. */
    private static Optional<BlockUtil.FoundRectangle> createPortal(ServerLevel level, BlockPos center, Direction.Axis axis, int dye) {
        Direction dir = Direction.get(Direction.AxisDirection.POSITIVE, axis);
        double best = -1.0;
        BlockPos bestPos = null;
        double fallback = -1.0;
        BlockPos fallbackPos = null;
        WorldBorder border = level.getWorldBorder();
        int top = Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.getLogicalHeight()) - 1;
        BlockPos.MutableBlockPos probe = center.mutable();

        // Overworld-like dimensions: build on the surface. Vanilla ranks candidates by 3D distance and scans every
        // cave under each column, which with a source portal at a different height picks a hole in the ground.
        boolean surface = !level.dimensionType().hasCeiling();

        for (BlockPos.MutableBlockPos spot : BlockPos.spiralAround(center, surface ? 32 : 16, Direction.EAST, Direction.SOUTH)) {
            int start = Math.min(top, level.getHeight(surface ? Heightmap.Types.MOTION_BLOCKING_NO_LEAVES : Heightmap.Types.MOTION_BLOCKING,
                    spot.getX(), spot.getZ()));

            if (!border.isWithinBounds(spot) || !border.isWithinBounds(spot.move(dir, 1))) {
                continue;
            }

            spot.move(dir.getOpposite(), 1);

            for (int y = start; y >= level.getMinBuildHeight(); y--) {
                spot.setY(y);

                if (!replaceable(level, spot)) {
                    continue;
                }

                int from = y;

                while (y > level.getMinBuildHeight() && replaceable(level, spot.move(Direction.DOWN))) {
                    y--;
                }

                int drop = from - y;

                if (y + 4 <= top && (drop <= 0 || drop >= 3)) {
                    spot.setY(y);

                    if (canHost(level, spot, probe, dir, 0)) {
                        double dx = spot.getX() - center.getX();
                        double dz = spot.getZ() - center.getZ();
                        double dist = surface ? dx * dx + dz * dz + 2.0 * roughness(level, spot) * roughness(level, spot) : center.distSqr(spot);

                        if (canHost(level, spot, probe, dir, -1) && canHost(level, spot, probe, dir, 1) && (best == -1.0 || best > dist)) {
                            best = dist;
                            bestPos = spot.immutable();
                        }

                        if (best == -1.0 && (fallback == -1.0 || fallback > dist)) {
                            fallback = dist;
                            fallbackPos = spot.immutable();
                        }
                    }
                }

                if (surface) {
                    break;
                }
            }
        }

        if (best == -1.0 && fallback != -1.0) {
            bestPos = fallbackPos;
            best = fallback;
        }

        BlockState frame = OverworldCopy.frameForGeneration();

        if (best == -1.0) {
            int low = surface ? level.getMinBuildHeight() + 1 : Math.max(level.getMinBuildHeight() + 1, 70);
            int high = top - 9;

            if (high < low) {
                return Optional.empty();
            }

            bestPos = border.clampToBounds(new BlockPos(center.getX() - dir.getStepX(), Mth.clamp(surface ? level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, center.getX(), center.getZ()) : center.getY(), low, high),
                    center.getZ() - dir.getStepZ()));
            Direction side = dir.getClockWise();

            for (int s = -1; s < 2; s++) {
                for (int d = 0; d < 2; d++) {
                    for (int y = -1; y < 3; y++) {
                        probe.setWithOffset(bestPos, d * dir.getStepX() + s * side.getStepX(), y, d * dir.getStepZ() + s * side.getStepZ());
                        level.setBlockAndUpdate(probe, y < 0 ? frame : Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }

        if (surface && Config.OVERWORLD_COPY_FLAT.get()) {
            flatten(level, bestPos, dir);
        }

        for (int w = -1; w < 3; w++) {
            for (int h = -1; h < 4; h++) {
                if (w == -1 || w == 2 || h == -1 || h == 3) {
                    probe.setWithOffset(bestPos, w * dir.getStepX(), h, w * dir.getStepZ());
                    level.setBlock(probe, frame, 3);
                }
            }
        }

        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS, axis);

        for (int w = 0; w < 2; w++) {
            for (int h = 0; h < 3; h++) {
                probe.setWithOffset(bestPos, w * dir.getStepX(), h, w * dir.getStepZ());
                level.setBlock(probe, portal, 18);
            }
        }

        CopyShape made = new CopyShape(level, bestPos, axis);

        if (made.isComplete() && dye >= 0) {
            PortalColors.get(level).set(made, dye);
            OverworldCopyEvents.sync(level);
        }

        return Optional.of(new BlockUtil.FoundRectangle(bestPos.immutable(), 2, 3));
    }

    /**
     * Levels a pad around a new portal to the portal's floor ({@code origin.y - 1}): ground raised with the
     * column's own surface block, or cut down to it, and the space above cleared. Surface only, nothing deeper.
     */
    public static void flatten(ServerLevel level, BlockPos origin, Direction dir) {
        int ground = origin.getY() - 1;
        int radius = 4;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = origin.getX() + dx;
                int z = origin.getZ() + dz;

                if (!level.getWorldBorder().isWithinBounds(m.set(x, ground, z))) {
                    continue;
                }

                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;

                while (top > level.getMinBuildHeight() && (!level.getBlockState(m.set(x, top, z)).isSolid()
                        || level.getBlockState(m).canBeReplaced())) {
                    top--;
                }

                BlockState surface = level.getBlockState(m.set(x, top, z));
                BlockState under = level.getBlockState(m.set(x, top - 1, z));

                if (surface.isAir() || !level.getFluidState(m.set(x, top, z)).isEmpty()) {
                    surface = Blocks.STONE.defaultBlockState();
                }

                if (under.isAir() || !under.isSolid()) {
                    under = Blocks.DIRT.defaultBlockState();
                }

                for (int y = Math.max(top, ground) + 6; y > ground; y--) {
                    m.set(x, y, z);

                    if (!level.getBlockState(m).isAir() && !level.getBlockState(m).is(Blocks.NETHER_PORTAL)) {
                        level.setBlock(m, Blocks.AIR.defaultBlockState(), 2);
                    }
                }

                for (int y = top + 1; y < ground; y++) {
                    level.setBlock(m.set(x, y, z), under, 2);
                }

                level.setBlock(m.set(x, ground, z), surface, 2);
            }
        }
    }

    /** Sum of how far the surface within 2 blocks of {@code spot} sits above or below it; 0 on a flat site. */
    private static int roughness(ServerLevel level, BlockPos spot) {
        int total = 0;

        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                total += Math.abs(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spot.getX() + dx, spot.getZ() + dz) - spot.getY());
            }
        }

        return total;
    }

    private static boolean replaceable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.canBeReplaced() && state.getFluidState().isEmpty();
    }

    private static boolean canHost(ServerLevel level, BlockPos origin, BlockPos.MutableBlockPos probe, Direction dir, int shift) {
        Direction side = dir.getClockWise();

        for (int i = -1; i < 3; i++) {
            for (int j = -1; j < 4; j++) {
                probe.setWithOffset(origin, dir.getStepX() * i + side.getStepX() * shift, j, dir.getStepZ() * i + side.getStepZ() * shift);

                if (j < 0 && !level.getBlockState(probe).isSolid()) {
                    return false;
                }

                if (j >= 0 && !replaceable(level, probe)) {
                    return false;
                }
            }
        }

        return true;
    }

    private CopyPortals() {
    }
}
