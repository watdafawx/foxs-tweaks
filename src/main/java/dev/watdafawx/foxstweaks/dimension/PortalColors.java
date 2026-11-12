package dev.watdafawx.foxstweaks.dimension;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Dye colour of each portal, per dimension. Portals are vanilla nether_portal blocks, which carry no
 * data, so each portal is remembered as its interior rectangle. Stale entries (portal gone) are pruned
 * whenever the list is sent.
 */
public final class PortalColors extends SavedData {
    private static final String NAME = "foxstweaks_portal_colors";

    /** {@code dye} is a {@link DyeColor} id; the portal's corner/size follow {@link CopyShape}. */
    public record Entry(BlockPos corner, int axisId, int width, int height, int dye) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::corner,
                ByteBufCodecs.VAR_INT, Entry::axisId,
                ByteBufCodecs.VAR_INT, Entry::width,
                ByteBufCodecs.VAR_INT, Entry::height,
                ByteBufCodecs.VAR_INT, Entry::dye,
                Entry::new);

        public boolean contains(BlockPos p) {
            boolean x = axisId == 0;
            int along = x ? p.getX() - corner.getX() : p.getZ() - corner.getZ();
            int across = x ? p.getZ() - corner.getZ() : p.getX() - corner.getX();

            return across == 0 && along >= 0 && along < width && p.getY() >= corner.getY() && p.getY() < corner.getY() + height;
        }

        public int rgb() {
            return DyeColor.byId(dye).getTextureDiffuseColor() & 0xFFFFFF;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public static PortalColors get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(PortalColors::new, PortalColors::load, null), NAME);
    }

    public List<Entry> entries() {
        return entries;
    }

    /** @return the dye id at {@code pos}, or -1 for an undyed portal. */
    public int dyeAt(BlockPos pos) {
        for (Entry e : entries) {
            if (e.contains(pos)) {
                return e.dye;
            }
        }

        return -1;
    }

    public void set(CopyShape shape, int dye) {
        int axisId = shape.axis == Direction.Axis.X ? 0 : 1;

        entries.removeIf(e -> e.axisId == axisId && shape.contains(e.corner));

        if (dye >= 0) {
            entries.add(new Entry(shape.corner, axisId, shape.width, shape.height, dye));
        }

        setDirty();
    }

    /** Drops entries whose portal is gone (only where the chunk is loaded). */
    public void prune(ServerLevel level) {
        if (entries.removeIf(e -> level.hasChunkAt(e.corner) && !level.getBlockState(e.corner).is(Blocks.NETHER_PORTAL))) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();

        for (Entry e : entries) {
            CompoundTag t = new CompoundTag();
            t.putLong("pos", e.corner.asLong());
            t.putByte("axis", (byte) e.axisId);
            t.putInt("w", e.width);
            t.putInt("h", e.height);
            t.putInt("dye", e.dye);
            list.add(t);
        }

        tag.put("portals", list);
        return tag;
    }

    private static PortalColors load(CompoundTag tag, HolderLookup.Provider registries) {
        PortalColors colors = new PortalColors();

        for (var raw : tag.getList("portals", 10)) {
            CompoundTag t = (CompoundTag) raw;
            colors.entries.add(new Entry(BlockPos.of(t.getLong("pos")), t.getByte("axis"), t.getInt("w"), t.getInt("h"), t.getInt("dye")));
        }

        return colors;
    }
}
