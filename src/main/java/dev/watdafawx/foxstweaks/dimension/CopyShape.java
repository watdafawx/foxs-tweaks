package dev.watdafawx.foxstweaks.dimension;

import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Vanilla's {@code PortalShape} rules (2-21 wide, 3-21 tall, corners optional, frame block below every
 * interior column) with the frame block taken from the config. {@link #corner} is the interior's
 * lowest-coordinate block, so the interior runs {@code width} blocks in the positive direction of the axis.
 */
public final class CopyShape {
    private static final int MAX = 21;

    public final LevelReader level;
    public final Direction.Axis axis;
    private final Direction pos;
    public BlockPos corner;
    public int width;
    public int height;
    public int portalBlocks;

    public CopyShape(LevelReader level, BlockPos start, Direction.Axis axis) {
        this.level = level;
        this.axis = axis;
        this.pos = axis == Direction.Axis.X ? Direction.EAST : Direction.SOUTH;

        BlockPos p = start;
        int floor = Math.max(level.getMinBuildHeight(), p.getY() - MAX);

        while (p.getY() > floor && isEmpty(level.getBlockState(p.below()))) {
            p = p.below();
        }

        int left = distanceToFrame(p, pos.getOpposite()) - 1;

        if (left < 0) {
            return;
        }

        corner = p.relative(pos.getOpposite(), left);
        width = distanceToFrame(corner, pos);

        if (width < 2 || width > MAX) {
            width = 0;
            return;
        }

        height = height();
    }

    public boolean isValid() {
        return corner != null && width >= 2 && width <= MAX && height >= 3 && height <= MAX;
    }

    public boolean isComplete() {
        return isValid() && portalBlocks == width * height;
    }

    public boolean isEmptyInside() {
        return isValid() && portalBlocks == 0;
    }

    /** Both orientations, like vanilla; the first that matches {@code wantEmpty}. */
    public static Optional<CopyShape> find(LevelReader level, BlockPos start, Direction.Axis preferred) {
        CopyShape a = new CopyShape(level, start, preferred);

        if (a.isEmptyInside()) {
            return Optional.of(a);
        }

        CopyShape b = new CopyShape(level, start, preferred == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X);
        return b.isEmptyInside() ? Optional.of(b) : Optional.empty();
    }

    public boolean contains(BlockPos p) {
        if (!isValid()) {
            return false;
        }

        int along = axis == Direction.Axis.X ? p.getX() - corner.getX() : p.getZ() - corner.getZ();
        int across = axis == Direction.Axis.X ? p.getZ() - corner.getZ() : p.getX() - corner.getX();

        return across == 0 && along >= 0 && along < width && p.getY() >= corner.getY() && p.getY() < corner.getY() + height;
    }

    public BlockPos at(int along, int up) {
        return corner.relative(pos, along).above(up);
    }

    private int distanceToFrame(BlockPos from, Direction dir) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int i = 0; i <= MAX; i++) {
            m.set(from).move(dir, i);
            BlockState state = level.getBlockState(m);

            if (!isEmpty(state)) {
                if (OverworldCopy.isFrame(state)) {
                    return i;
                }

                break;
            }

            if (!OverworldCopy.isFrame(level.getBlockState(m.move(Direction.DOWN)))) {
                break;
            }
        }

        return 0;
    }

    private int height() {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int top = MAX;

        scan:
        for (int i = 0; i < MAX; i++) {
            if (!OverworldCopy.isFrame(level.getBlockState(m.set(corner).move(Direction.UP, i).move(pos, -1)))
                    || !OverworldCopy.isFrame(level.getBlockState(m.set(corner).move(Direction.UP, i).move(pos, width)))) {
                top = i;
                break;
            }

            for (int j = 0; j < width; j++) {
                BlockState state = level.getBlockState(m.set(corner).move(Direction.UP, i).move(pos, j));

                if (!isEmpty(state)) {
                    top = i;
                    break scan;
                }

                if (state.is(Blocks.NETHER_PORTAL)) {
                    portalBlocks++;
                }
            }
        }

        if (top < 3 || top > MAX) {
            return 0;
        }

        for (int j = 0; j < width; j++) {
            if (!OverworldCopy.isFrame(level.getBlockState(m.set(corner).move(Direction.UP, top).move(pos, j)))) {
                return 0;
            }
        }

        return top;
    }

    private static boolean isEmpty(BlockState state) {
        return state.isAir() || state.is(BlockTags.FIRE) || state.is(Blocks.NETHER_PORTAL);
    }
}
