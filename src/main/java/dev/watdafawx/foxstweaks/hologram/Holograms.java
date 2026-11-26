package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side manager for holograms: create/update/delete, transient live-edit previews shared
 * with all players in the dimension, and syncing the dimension's list.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class Holograms {

    /** How far a player may be from a hologram and still edit or delete it with the wand. */
    public static final double REACH = 6.0;

    private record LivePreview(UUID editor, HologramSpec spec) {
    }

    /** Transient live previews keyed by dimension -> hologram id. Never persisted to SavedData. */
    private static final Map<ResourceLocation, Map<String, LivePreview>> LIVE_PREVIEWS = new HashMap<>();

    private Holograms() {
    }

    public static List<HologramSpec> list(ServerLevel level) {
        return HologramData.get(level).snapshot();
    }

    /** Saved holograms with any active live-editor previews overlaid for broadcasting to players. */
    public static List<HologramSpec> listForSync(ServerLevel level) {
        List<HologramSpec> saved = HologramData.get(level).snapshot();
        Map<String, LivePreview> dimPreviews = LIVE_PREVIEWS.get(level.dimension().location());
        if (dimPreviews == null || dimPreviews.isEmpty())
            return saved;

        List<HologramSpec> merged = new ArrayList<>(saved.size());
        for (HologramSpec spec : saved) {
            LivePreview live = dimPreviews.get(spec.id());
            merged.add(live != null ? live.spec() : spec);
        }
        return merged;
    }

    @Nullable
    public static HologramSpec get(ServerLevel level, String id) {
        return HologramData.get(level).get(id);
    }

    /** A short unique id for a new hologram, readable enough to use from a command. */
    public static String newId(ServerLevel level, String block) {
        String base = block.substring(block.indexOf(':') + 1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
        if (base.isEmpty())
            base = "hologram";
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

    public static final int MAX_DATA_BYTES = 64 * 1024;
    public static final int DEFAULT_STRUCTURE_DATA_BYTES = 256 * 1024;

    public static int maxStructureDataBytes() {
        return ServerConfig.hologramStructureMaxBytes();
    }

    public static int maxDataBytes(HologramLayer.Kind kind) {
        return kind == HologramLayer.Kind.STRUCTURE
                ? Math.max(DEFAULT_STRUCTURE_DATA_BYTES, maxStructureDataBytes())
                : MAX_DATA_BYTES;
    }

    @Nullable
    public static CompoundTag capture(ServerLevel level, BlockPos source) {
        return capture(level, source, MAX_DATA_BYTES);
    }

    @Nullable
    static CompoundTag capture(ServerLevel level, BlockPos source, int maxBytes) {
        BlockEntity blockEntity = level.getBlockEntity(source);
        if (blockEntity == null)
            return null;
        CompoundTag tag = blockEntity.saveWithoutMetadata(level.registryAccess());
        CompoundTag update = blockEntity.getUpdateTag(level.registryAccess());
        if (!update.isEmpty())
            tag.put("_ft_update", update);
        if (tag.isEmpty() || tag.sizeInBytes() > maxBytes)
            return null;
        return tag;
    }

    public static HologramSpec create(ServerLevel level, BlockPos pos, BlockState state, @Nullable Player placer) {
        return create(level, pos, state, null, placer);
    }

    public static HologramSpec create(ServerLevel level, BlockPos pos, BlockState state, @Nullable BlockPos dataSource,
                                      @Nullable Player placer) {
        String block = HologramStates.blockName(state);
        Map<String, String> props = new LinkedHashMap<>();
        if (!state.getProperties().isEmpty()) {
            for (var property : HologramStates.properties(state))
                props.put(property.getName(), HologramStates.valueOf(state, property));
        }

        CompoundTag beData = dataSource != null ? capture(level, dataSource) : null;
        HologramLayer first = HologramLayer.block(block, props, beData == null ? new CompoundTag() : beData);

        HologramSpec spec = new HologramSpec(newId(level, block), pos,
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                true, Math.min(48, ServerConfig.hologramsMaxRenderDistance()),
                placer == null ? null : placer.getUUID(),
                placer == null ? "" : placer.getGameProfile().getName(),
                0.0F, 0.6F, HologramSpec.REVEAL_RISE, List.of(), List.of(first));

        save(level, spec);
        return get(level, spec.id());
    }

    public static void setLivePreview(ServerLevel level, UUID editor, HologramSpec spec) {
        LIVE_PREVIEWS.computeIfAbsent(level.dimension().location(), k -> new HashMap<>())
                .put(spec.id(), new LivePreview(editor, spec));
        sync(level);
    }

    public static void clearLivePreview(ServerLevel level, String id) {
        Map<String, LivePreview> dimPreviews = LIVE_PREVIEWS.get(level.dimension().location());
        if (dimPreviews != null && dimPreviews.remove(id) != null) {
            if (dimPreviews.isEmpty())
                LIVE_PREVIEWS.remove(level.dimension().location());
            sync(level);
        }
    }

    private static void clearLivePreviewsForPlayer(ServerPlayer player) {
        UUID uuid = player.getUUID();
        var server = player.getServer();
        if (server == null)
            return;

        List<ResourceLocation> changedDims = new ArrayList<>();
        Iterator<Map.Entry<ResourceLocation, Map<String, LivePreview>>> it = LIVE_PREVIEWS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceLocation, Map<String, LivePreview>> entry = it.next();
            boolean removed = entry.getValue().values().removeIf(p -> p.editor().equals(uuid));
            if (removed) {
                changedDims.add(entry.getKey());
                if (entry.getValue().isEmpty())
                    it.remove();
            }
        }
        for (ServerLevel level : server.getAllLevels()) {
            if (changedDims.contains(level.dimension().location()))
                sync(level);
        }
    }

    public static void save(ServerLevel level, HologramSpec spec) {
        Map<String, LivePreview> dimPreviews = LIVE_PREVIEWS.get(level.dimension().location());
        if (dimPreviews != null) {
            dimPreviews.remove(spec.id());
            if (dimPreviews.isEmpty())
                LIVE_PREVIEWS.remove(level.dimension().location());
        }
        HologramSpec previous = HologramData.get(level).put(spec);
        // Reference counts follow saved holograms only, so an asset in use cannot be deleted by accident.
        dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork.track(level.getServer(), previous, spec);
        sync(level);
    }

    @Nullable
    public static HologramSpec remove(ServerLevel level, String id) {
        Map<String, LivePreview> dimPreviews = LIVE_PREVIEWS.get(level.dimension().location());
        if (dimPreviews != null) {
            dimPreviews.remove(id);
            if (dimPreviews.isEmpty())
                LIVE_PREVIEWS.remove(level.dimension().location());
        }
        HologramSpec removed = HologramData.get(level).remove(id);
        if (removed != null) {
            dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork.track(level.getServer(), removed, null);
            sync(level);
        }
        return removed;
    }

    public static int capacityLeft(ServerLevel level) {
        int max = ServerConfig.hologramsMaxPerDimension();
        if (max <= 0)
            return Integer.MAX_VALUE;
        return max - HologramData.get(level).size();
    }

    @Nullable
    public static BlockState stateOf(HologramLayer layer) {
        if (layer.kind() != HologramLayer.Kind.BLOCK)
            return null;
        return HologramStates.resolve(layer.id(), layer.props());
    }

    public static void sync(ServerLevel level) {
        HologramSyncPayload payload = new HologramSyncPayload(level.dimension().location(), listForSync(level));
        for (ServerPlayer player : level.players())
            PacketDistributor.sendToPlayer(player, payload);
    }

    public static void syncTo(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        PacketDistributor.sendToPlayer(player,
                new HologramSyncPayload(level.dimension().location(), listForSync(level)));
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            syncTo(player);
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            clearLivePreviewsForPlayer(player);
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            clearLivePreviewsForPlayer(player);
            syncTo(player);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        LIVE_PREVIEWS.clear();
    }
}
