package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Block entities for block layers whose look comes from a {@link BlockEntityRenderer} or custom
 * {@code ModelData} (chests, signs, banners, AE2 drives, modded machines). Cached per
 * {@code hologramId#layerN} and ticked locally so animations advance without entering the world.
 */
public final class HologramBlockEntities {
    private static final Map<String, Cached> CACHE = new HashMap<>();

    private record Cached(@Nullable BlockEntity entity, BlockState state, int dataHash) {
    }

    /** Convenience lookup for a hologram's first block layer (layer 0). */
    @Nullable
    public static BlockEntity get(HologramSpec spec) {
        HologramLayer first = spec.primaryLayer();
        if (first == null || first.kind() != HologramLayer.Kind.BLOCK)
            return null;
        BlockState state = HologramStates.resolve(first.id(), first.props());
        return getForLayer(spec.id(), 0, spec.pos(), state, first.data());
    }

    /** Block entity for a specific block layer, cached under {@code specId#layerIndex}. */
    @Nullable
    public static BlockEntity getForLayer(String specId, int layerIndex, BlockPos pos,
                                          @Nullable BlockState state, @Nullable CompoundTag data) {
        return getOrCreate(specId + "#layer" + layerIndex, pos, state, data);
    }

    /** Block entity for one block inside a structure layer, cached under {@code specId#layerIndex#pPosIdx}. */
    @Nullable
    public static BlockEntity getForStructureBlock(String specId, int layerIndex, int posIdx, BlockPos pos,
                                                   @Nullable BlockState state, @Nullable CompoundTag data) {
        return getOrCreate(specId + "#layer" + layerIndex + "#p" + posIdx, pos, state, data);
    }

    @Nullable
    private static BlockEntity getOrCreate(String key, BlockPos pos, @Nullable BlockState state,
                                           @Nullable CompoundTag data) {
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
                CompoundTag copy = data.copy();
                CompoundTag update = copy.contains("_ft_update", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        ? copy.getCompound("_ft_update").copy()
                        : null;
                copy.remove("_ft_update");
                if (!copy.isEmpty())
                    created.loadWithComponents(copy, mc.level.registryAccess());
                if (update != null && !update.isEmpty())
                    created.handleUpdateTag(update, mc.level.registryAccess());
            }
        } catch (Throwable t) {
            // A tag from a build that no longer fits its block; keep default instance if possible.
        }

        boolean hasRenderer = renderer(created) != null;
        boolean hasModelData = created.getModelData() != net.neoforged.neoforge.client.model.data.ModelData.EMPTY;
        if (!hasRenderer && !hasModelData) {
            CACHE.put(key, new Cached(null, state, hash));
            return null;
        }

        CACHE.put(key, new Cached(created, state, hash));
        return created;
    }

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
            }
        }
    }

    @Nullable
    public static BlockEntityRenderer<?> renderer(BlockEntity entity) {
        BlockEntityRenderDispatcher dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        return dispatcher.getRenderer(entity);
    }

    public static void prune(Set<String> activeIds) {
        if (CACHE.isEmpty())
            return;
        CACHE.keySet().removeIf(key -> {
            int sep = key.indexOf("#layer");
            String ownerId = sep >= 0 ? key.substring(0, sep) : key;
            return !activeIds.contains(ownerId);
        });
    }

    public static void clear() {
        CACHE.clear();
    }

    static void removeStructureLayer(String key) {
        CACHE.keySet().removeIf(entry -> entry.startsWith(key + "#p"));
    }

    private HologramBlockEntities() {
    }
}
