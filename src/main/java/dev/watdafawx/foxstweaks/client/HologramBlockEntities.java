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

    private record Cached(@Nullable BlockEntity entity, BlockState state, int dataHash) {
    }

    /**
     * The block entity for this hologram, or null when the block has none, has no renderer, or
     * refuses to exist at this position.
     *
     * <p>Works even when {@code spec.data()} is empty: blocks picked from the block picker (like
     * Ad Astra machines with spinning BER tops, globes, flags, chests, signs) still need a default
     * block entity instance so their {@link BlockEntityRenderer} draws instead of half-rendering.
     */
    @Nullable
    public static BlockEntity get(HologramSpec spec) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;

        BlockState state = HologramStates.resolve(spec.block(), spec.props());
        return getOrCreate(spec.id(), spec.pos(), state, spec.data());
    }

    /** Block entity for a block part, cached under a composite key. */
    @Nullable
    public static BlockEntity getForPart(String parentId, int partIndex, BlockPos pos, @Nullable BlockState state) {
        return getOrCreate(parentId + "#part" + partIndex, pos, state, null);
    }

    @Nullable
    private static BlockEntity getOrCreate(String key, BlockPos pos, @Nullable BlockState state,
                                           @Nullable net.minecraft.nbt.CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || state == null || !(state.getBlock() instanceof EntityBlock entityBlock))
            return null;

        int hash = data == null || data.isEmpty() ? 0 : data.hashCode();
        Cached cached = CACHE.get(key);
        if (cached != null && cached.state() == state && cached.dataHash() == hash)
            return cached.entity();

        BlockEntity created;
        try {
            created = entityBlock.newBlockEntity(pos, state);
        } catch (Throwable t) {
            CACHE.put(key, new Cached(null, state, hash));
            return null;
        }
        if (created == null) {
            CACHE.put(key, new Cached(null, state, hash));
            return null;
        }

        try {
            created.setLevel(mc.level);
            if (data != null && !data.isEmpty()) {
                net.minecraft.nbt.CompoundTag copy = data.copy();
                net.minecraft.nbt.CompoundTag update = copy.contains("_ft_update", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? copy.getCompound("_ft_update").copy()
                        : null;
                copy.remove("_ft_update");
                if (!copy.isEmpty())
                    created.loadWithComponents(copy, mc.level.registryAccess());
                if (update != null && !update.isEmpty())
                    created.handleUpdateTag(update, mc.level.registryAccess());
            }
        } catch (Throwable t) {
            // A tag from a build that no longer fits its block; keep the default instance if possible.
        }

        // Only keep block entities that actually have a renderer or provide custom ModelData.
        boolean hasRenderer = renderer(created) != null;
        boolean hasModelData = created.getModelData() != net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
        if (!hasRenderer && !hasModelData) {
            CACHE.put(key, new Cached(null, state, hash));
            return null;
        }

        CACHE.put(key, new Cached(created, state, hash));
        return created;
    }

    /** Runs the block's own ticker, so animation state advances without the entity being in the level. */
    public static void tick(Minecraft mc) {
        if (CACHE.isEmpty() || mc.level == null)
            return;

        for (var entry : CACHE.entrySet()) {
            BlockEntity entity = entry.getValue().entity();
            if (entity == null)
                continue;
            BlockState state = entity.getBlockState();
            if (!(state.getBlock() instanceof EntityBlock entityBlock))
                continue;

            try {
                @SuppressWarnings("unchecked")
                BlockEntityTicker<BlockEntity> ticker = (BlockEntityTicker<BlockEntity>) entityBlock
                        .getTicker(mc.level, state, entity.getType());
                if (ticker != null)
                    ticker.tick(mc.level, entity.getBlockPos(), state, entity);
            } catch (Throwable ignored) {
                // Some modded tickers assume the block exists in the world; skip rather than crash.
            }
        }
    }

    @Nullable
    public static BlockEntityRenderer<?> renderer(BlockEntity entity) {
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        return dispatcher.getRenderer(entity);
    }

    /** Removes cached entries for holograms that are no longer in the active client list. */
    public static void prune(java.util.Set<String> activeIds) {
        if (CACHE.isEmpty())
            return;
        CACHE.keySet().removeIf(key -> {
            int sep = key.indexOf("#part");
            String ownerId = sep >= 0 ? key.substring(0, sep) : key;
            return !activeIds.contains(ownerId);
        });
    }

    public static void clear() {
        CACHE.clear();
    }

    private HologramBlockEntities() {
    }
}
