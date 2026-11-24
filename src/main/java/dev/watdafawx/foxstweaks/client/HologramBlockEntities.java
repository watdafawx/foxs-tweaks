package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Block entities for holograms whose look does not come from a model at all.
 *
 * <p>Chests, signs, beds, banners, shulker boxes and most modded machines with status lights render only
 * through a {@link BlockEntityRenderer}, and that renderer reads the block entity's fields. So a hologram
 * of one has to be given an actual block entity with actual data - that is what {@code HologramSpec.data}
 * carries, captured from the real block when the hologram was placed.
 *
 * <p>The instance never enters the level: it is created from the block itself, given the client level so
 * renderers that ask for one keep working, loaded from the captured tag, and ticked by hand so LEDs blink
 * and chest lids move. Cached per hologram id and rebuilt when the data changes.
 */
public final class HologramBlockEntities {
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private record Cached(BlockEntity entity, int dataHash, long ticks) {
    }

    /**
     * The block entity for this hologram, or null when the block has none, the data is empty, or the
     * type refuses to exist at this position.
     */
    @Nullable
    public static BlockEntity get(HologramSpec spec) {
        if (spec.data().isEmpty())
            return null;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;

        BlockState state = HologramStates.resolve(spec.block(), spec.props());
        if (state == null || !(state.getBlock() instanceof EntityBlock entityBlock))
            return null;

        int hash = spec.data().hashCode();
        Cached cached = CACHE.get(spec.id());
        if (cached != null && cached.dataHash() == hash && cached.entity().getType().isValid(state))
            return cached.entity();

        BlockPos pos = spec.pos();
        BlockEntity created = entityBlock.newBlockEntity(pos, state);
        if (created == null)
            return null;

        try {
            created.setLevel(mc.level);
            created.loadWithComponents(spec.data().copy(), mc.level.registryAccess());
        } catch (Throwable t) {
            // A tag from a build that no longer fits its block; draw nothing rather than crash the frame.
            return null;
        }

        CACHE.put(spec.id(), new Cached(created, hash, 0L));
        return created;
    }

    /** Runs the block's own ticker, so animation state advances without the entity being in the level. */
    public static void tick(Minecraft mc) {
        if (CACHE.isEmpty() || mc.level == null)
            return;

        for (var entry : CACHE.entrySet()) {
            Cached cached = entry.getValue();
            BlockEntity entity = cached.entity();
            BlockState state = entity.getBlockState();
            if (!(state.getBlock() instanceof EntityBlock entityBlock))
                continue;

            @SuppressWarnings("unchecked")
            BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>) entityBlock
                    .getTicker(mc.level, state, entity.getType());
            if (ticker != null)
                ticker.tick(mc.level, entity.getBlockPos(), state, entity);
        }
    }

    @Nullable
    public static BlockEntityRenderer<?> renderer(BlockEntity entity) {
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        return dispatcher.getRenderer(entity);
    }

    public static void clear() {
        CACHE.clear();
    }

    private HologramBlockEntities() {
    }
}
