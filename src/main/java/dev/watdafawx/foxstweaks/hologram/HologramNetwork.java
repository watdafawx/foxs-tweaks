package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;

/**
 * Payload registration and the server half of the hologram editor, including live multi-player
 * preview streaming and revert/cancel.
 */
public final class HologramNetwork {
    private HologramNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("3").optional();

        registrar.playToClient(HologramSyncPayload.TYPE, HologramSyncPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient())
                        dev.watdafawx.foxstweaks.client.HologramClient.acceptSync(payload);
                }));

        registrar.playToClient(HologramOpenPayload.TYPE, HologramOpenPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient())
                        dev.watdafawx.foxstweaks.client.HologramScreen.open(payload);
                }));

        registrar.playToServer(HologramActionPayload.TYPE, HologramActionPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        handle(player, payload);
                }));

        registrar.playToServer(HologramGrabPayload.TYPE, HologramGrabPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        HologramGrabber.handle(player, payload);
                }));

        registrar.playToClient(HologramGrabReplyPayload.TYPE, HologramGrabReplyPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient())
                        dev.watdafawx.foxstweaks.client.HologramGrab.acceptReply(payload);
                }));
    }

    private static void handle(ServerPlayer player, HologramActionPayload payload) {
        if (payload.action() == HologramActionPayload.Action.CREATE) {
            create(player, payload.spec());
            return;
        }

        ServerLevel level = player.serverLevel();
        String id = payload.spec().getString("id");
        HologramSpec stored = Holograms.get(level, id);
        if (stored == null || !canEdit(player, stored))
            return;

        if (payload.action() == HologramActionPayload.Action.CANCEL) {
            Holograms.clearLivePreview(level, id);
            return;
        }

        // The client can send any position it likes; only the stored one (anchor or offset box) is trusted.
        double distSq = Math.min(
                player.distanceToSqr(Vec3.atCenterOf(stored.pos())),
                player.distanceToSqr(stored.box().getCenter()));
        if (distSq > square(Holograms.REACH + 8.0))
            return;

        if (payload.action() == HologramActionPayload.Action.DELETE) {
            Holograms.remove(level, id);
            level.playSound(null, stored.pos(), SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.6F, 1.4F);
            return;
        }

        if (payload.action() == HologramActionPayload.Action.RECAPTURE) {
            BlockPos source = findBlockEntityNear(level, stored.pos());
            if (source == null)
                return;
            CompoundTag captured = Holograms.capture(level, source);
            if (captured == null)
                return;
            int layerIdx = payload.spec().getInt("layerIndex");
            if (layerIdx < 0 || layerIdx >= stored.layers().size())
                layerIdx = 0;
            HologramLayer target = stored.layers().get(layerIdx);
            if (target.kind() != HologramLayer.Kind.BLOCK)
                return;
            HologramSpec updated = stored.withLayer(layerIdx, target.withData(captured));
            Holograms.save(level, updated);
            openEditor(player, updated);
            return;
        }

        HologramSpec incoming = HologramSpec.fromTag(payload.spec());
        if (incoming == null || !validStructures(incoming))
            return;

        HologramSpec sanitized = incoming.at(stored.id(), stored.pos(), stored.owner(), stored.ownerName())
                .withRenderDistance(Math.min(incoming.renderDistance(), ServerConfig.hologramsMaxRenderDistance()));

        if (payload.action() == HologramActionPayload.Action.PREVIEW) {
            Holograms.setLivePreview(level, player.getUUID(), sanitized);
            return;
        }

        Holograms.save(level, sanitized);
    }

    private static boolean canEdit(ServerPlayer player, HologramSpec spec) {
        if (player.hasPermissions(2))
            return true;
        UUID owner = spec.owner();
        return owner == null || owner.equals(player.getUUID());
    }

    private static void create(ServerPlayer player, CompoundTag tag) {
        if (!ServerConfig.hologramsEnabled() || !tag.contains("pos"))
            return;

        ServerLevel level = player.serverLevel();
        HologramSpec style = HologramSpec.fromTag(tag);
        if (style == null || style.layers().isEmpty() || !validStructures(style))
            return;
        if (Holograms.capacityLeft(level) <= 0)
            return;

        BlockPos pos = BlockPos.of(tag.getLong("pos"));
        if (!level.isLoaded(pos))
            return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > square(Holograms.REACH + 8.0))
            return;

        HologramSpec created = style.at(Holograms.newId(level, style.primaryId()), pos,
                        player.getUUID(), player.getGameProfile().getName())
                .withRenderDistance(Math.min(style.renderDistance(), ServerConfig.hologramsMaxRenderDistance()));

        Holograms.save(level, created);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.2F);
        openEditor(player, created);
    }

    /** Editor packets are untrusted too: grabbing isn't the only way to submit layer data. */
    private static boolean validStructures(HologramSpec spec) {
        for (HologramLayer layer : spec.layers()) {
            if (layer.kind() != HologramLayer.Kind.STRUCTURE)
                continue;
            CompoundTag data = layer.data();
            int max = ServerConfig.hologramStructureMaxDimension();
            if (data.sizeInBytes() > Holograms.maxStructureDataBytes()
                    || data.getInt("w") > max || data.getInt("h") > max || data.getInt("d") > max)
                return false;
            HologramStructure structure = HologramStructure.fromTag(data);
            if (structure == null || structure.blockCount() > ServerConfig.hologramStructureMaxBlocks())
                return false;
        }
        return true;
    }

    @javax.annotation.Nullable
    private static BlockPos findBlockEntityNear(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) != null)
            return pos;
        for (var dir : new net.minecraft.core.Direction[] {
                net.minecraft.core.Direction.DOWN, net.minecraft.core.Direction.UP,
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST }) {
            BlockPos adj = pos.relative(dir);
            if (level.getBlockEntity(adj) != null)
                return adj;
        }
        return null;
    }

    private static double square(double value) {
        return value * value;
    }

    public static void openEditor(ServerPlayer player, HologramSpec spec) {
        PacketDistributor.sendToPlayer(player,
                new HologramOpenPayload(spec, ServerConfig.hologramsMaxRenderDistance()));
    }

    public static void sendSave(HologramSpec spec) {
        PacketDistributor.sendToServer(HologramActionPayload.save(spec));
    }

    public static void sendPreview(HologramSpec spec) {
        PacketDistributor.sendToServer(HologramActionPayload.preview(spec));
    }

    public static void sendCancel(String id) {
        PacketDistributor.sendToServer(HologramActionPayload.cancel(id));
    }

    public static void sendDelete(String id) {
        PacketDistributor.sendToServer(HologramActionPayload.delete(id));
    }

    public static void sendCreate(BlockPos pos, HologramSpec style) {
        PacketDistributor.sendToServer(HologramActionPayload.create(pos, style));
    }

    public static void sendRecapture(String id, int layerIndex) {
        PacketDistributor.sendToServer(HologramActionPayload.recapture(id, layerIndex));
    }

    public static void sendGrab(HologramGrabPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
