package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Server-side manager for holograms: create/update/delete, and syncing the current dimension's list
 * to whoever is looking at it.
 *
 * <p>No chunk ticket, no block entity, no entity. A hologram is a row of saved data and a packet;
 * that is what makes "show only, no collision" free rather than a special case.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class Holograms {

    /** How far a player may be from a hologram and still edit or delete it with the wand. */
    public static final double REACH = 6.0;

    private Holograms() {
    }

    public static List<HologramSpec> list(ServerLevel level) {
        return HologramData.get(level).snapshot();
    }

    @Nullable
    public static HologramSpec get(ServerLevel level, String id) {
        return HologramData.get(level).get(id);
    }

    /** A short unique id for a new hologram, readable enough to use from a command. */
    public static String newId(ServerLevel level, String block) {
        String base = block.substring(block.indexOf(':') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.length() > 20)
            base = base.substring(0, 20);

        HologramData data = HologramData.get(level);
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "_" + i;
            if (data.get(candidate) == null)
                return candidate;
        }
        return base + "_" + UUID.randomUUID().toString().substring(0, 6);
    }

    /**
     * Captured block-entity tags larger than this are dropped: a chest hologram of a chest full of
     * shulker boxes would otherwise be synced to every client and saved in the world for a decoration.
     */
    public static final int MAX_DATA_BYTES = 64 * 1024;

    /**
     * The block entity's own data, so a hologram of a chest/sign/machine can render its real state
     * instead of nothing (those blocks have no model at all).
     */
    @Nullable
    public static CompoundTag capture(ServerLevel level, BlockPos source) {
        BlockEntity blockEntity = level.getBlockEntity(source);
        if (blockEntity == null)
            return null;
        CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
        if (tag.isEmpty() || tag.sizeInBytes() > MAX_DATA_BYTES)
            return null;
        return tag;
    }

    /** Creates a hologram at {@code pos} showing {@code state}. Does not enforce limits - see {@link #capacityLeft}. */
    public static HologramSpec create(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player placer) {
        return create(level, pos, state, null, placer);
    }

    /**
     * @param dataSource the block whose block entity data should be copied, or null. The wand passes the
     *                   block it clicked; a chest is copied with its contents and open state, which is
     *                   what makes it draw at all.
     */
    public static HologramSpec create(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos dataSource,
                                      @Nullable Player placer) {
        String block = HologramStates.blockName(state);
        HologramSpec spec = HologramSpec.create(newId(level, block), pos, block,
                placer == null ? null : placer.getUUID(),
                placer == null ? "" : placer.getGameProfile().getName());

        if (dataSource != null) {
            CompoundTag data = capture(level, dataSource);
            if (data != null)
                spec = spec.withData(data);
        }

        save(level, spec.withRenderDistance(Math.min(spec.renderDistance(), ServerConfig.hologramsMaxRenderDistance())));
        return get(level, spec.id());
    }

    public static void save(ServerLevel level, HologramSpec spec) {
        HologramData.get(level).put(spec);
        sync(level);
    }

    @Nullable
    public static HologramSpec remove(ServerLevel level, String id) {
        HologramSpec removed = HologramData.get(level).remove(id);
        if (removed != null)
            sync(level);
        return removed;
    }

    /** How many more holograms this dimension may hold; negative means unlimited. */
    public static int capacityLeft(ServerLevel level) {
        int max = ServerConfig.hologramsMaxPerDimension();
        if (max <= 0)
            return Integer.MAX_VALUE;
        return max - HologramData.get(level).size();
    }

    /** The block a hologram should draw, or null when the block is not installed or has no model. */
    @Nullable
    public static BlockState stateOf(HologramSpec spec) {
        return HologramStates.resolve(spec.block(), spec.props());
    }

    public static void sync(ServerLevel level) {
        HologramSyncPayload payload = new HologramSyncPayload(level.dimension().location(), list(level));
        for (ServerPlayer player : level.players())
            PacketDistributor.sendToPlayer(player, payload);
    }

    public static void syncTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player,
                new HologramSyncPayload(level.dimension().location(), list(level)));
    }

    /** Re-sends the list when a player arrives somewhere, so the client cache starts out complete. */
    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            syncTo(player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            syncTo(player);
    }
}
