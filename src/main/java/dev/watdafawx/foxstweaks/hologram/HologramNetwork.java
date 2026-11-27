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
        dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork.register(event);
        var registrar = event.registrar("4").optional();

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
            HologramEditSessions.close(player);
            Holograms.clearLivePreview(level, id);
            return;
        }

        // The client can send any position it likes; only the stored one (anchor or offset box) is trusted.
        double distSq;
        if (stored.isAttached()) {
            var liveBox = stored.box(level, 1.0F);
            if (liveBox != null) {
                distSq = player.distanceToSqr(liveBox.getCenter());
            } else if (player.hasPermissions(2)) {
                distSq = 0.0;
            } else {
                return;
            }
        } else {
            distSq = Math.min(
                    player.distanceToSqr(Vec3.atCenterOf(stored.pos())),
                    player.distanceToSqr(stored.box().getCenter()));
        }

        // An open editor widens the gate: the studio camera, Grab mode and big structures all take the player
        // away from the hologram, and Done must still land. A first preview from within the base limit proves
        // the player was beside it, which covers editors the server did not open itself.
        if (payload.action() == HologramActionPayload.Action.PREVIEW
                && !HologramEditSessions.isOpen(player, id)
                && distSq <= square(HologramEditSessions.BASE_LIMIT))
            HologramEditSessions.open(player, id);

        double limit = HologramEditSessions.limit(player, id);
        boolean ending = payload.action() == HologramActionPayload.Action.SAVE
                || payload.action() == HologramActionPayload.Action.DELETE;
        // Done and Delete close the editor on the client whether or not the server accepts them.
        if (ending)
            HologramEditSessions.close(player);

        if (distSq > square(limit)) {
            // Live previews stream constantly while editing; only a real save/delete is worth saying out loud.
            if (payload.action() != HologramActionPayload.Action.PREVIEW) {
                int away = (int) Math.sqrt(distSq);
                LOG.warn("Hologram {} from {} refused ({}): player is {} blocks away, limit {}", stored.id(),
                        player.getGameProfile().getName(), payload.action(), away, (int) limit);
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Hologram change not saved: you were " + away + " blocks away (limit " + (int) limit + ")"),
                        false);
            }
            return;
        }

        if (!ending)
            HologramEditSessions.touch(player, id);

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
        String timelineProblem = incoming == null ? null : timelineProblem(incoming);
        String imageProblem = incoming == null ? null : imageProblem(player.server, incoming);
        if (incoming == null || !validStructures(incoming) || timelineProblem != null || imageProblem != null) {
            // Silent before: a refused save looked like Done working while the change vanished.
            String reason;
            if (incoming == null)
                reason = "spec could not be read";
            else if (timelineProblem != null)
                reason = timelineProblem;
            else if (imageProblem != null)
                reason = imageProblem;
            else
                reason = "structure layer over the configured limits";
            if (payload.action() != HologramActionPayload.Action.PREVIEW) {
                LOG.warn("Hologram {} from {} refused: {}", stored.id(), player.getGameProfile().getName(), reason);
                String shown = imageProblem != null && timelineProblem == null ? imageProblem
                        : "the data was refused by the server limits";
                player.displayClientMessage(net.minecraft.network.chat.Component.literal(
                        "Hologram change not saved: " + shown), false);
            }
            return;
        }

        HologramAttach sanitizedAttach = sanitizeAttach(player, stored.attach(), incoming.attach());
        BlockPos savePos = stored.pos();
        if (payload.action() == HologramActionPayload.Action.SAVE && stored.isAttached() && sanitizedAttach == null) {
            ServerPlayer prevTarget = player.server.getPlayerList().getPlayer(stored.attach().playerUuid());
            savePos = (prevTarget != null && prevTarget.serverLevel() == level)
                    ? prevTarget.blockPosition()
                    : player.blockPosition();
        }
        HologramSpec sanitized = incoming.at(stored.id(), savePos, stored.owner(), stored.ownerName())
                .withAttach(sanitizedAttach)
                .withRenderDistance(Math.min(incoming.renderDistance(), ServerConfig.hologramsMaxRenderDistance()));

        if (payload.action() == HologramActionPayload.Action.PREVIEW) {
            Holograms.setLivePreview(level, player.getUUID(), sanitized);
            return;
        }

        Holograms.save(level, sanitized);
    }

    @javax.annotation.Nullable
    private static HologramAttach sanitizeAttach(ServerPlayer editor, @javax.annotation.Nullable HologramAttach previous,
                                                 @javax.annotation.Nullable HologramAttach incoming) {
        if (incoming == null)
            return null;
        if (!editor.hasPermissions(2) && !incoming.playerUuid().equals(editor.getUUID())) {
            // Non-ops may only attach to themselves (or keep an existing op-set attachment target).
            if (previous != null && previous.playerUuid().equals(incoming.playerUuid()))
                return incoming.withPlayer(previous.playerUuid(), previous.playerName());
            return incoming.withPlayer(editor.getUUID(), editor.getGameProfile().getName());
        }
        ServerPlayer target = editor.server.getPlayerList().getPlayer(incoming.playerUuid());
        if (target != null)
            return incoming.withPlayer(target.getUUID(), target.getGameProfile().getName());
        return incoming;
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
        if (style == null || style.layers().isEmpty() || !validStructures(style) || timelineProblem(style) != null
                || imageProblem(player.server, style) != null)
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
                .withAttach(null)
                .withRenderDistance(Math.min(style.renderDistance(), ServerConfig.hologramsMaxRenderDistance()));

        Holograms.save(level, created);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.2F);
        openEditor(player, created);
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();

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

    /**
     * Null when every image layer names an asset this world's store holds and the hologram is within
     * {@code hologramImageMaxLayers}; otherwise why not. Placing an existing asset needs no upload permission:
     * anyone who may edit the hologram may use what is in the library.
     */
    /** A model is drawn from CPU-skinned vertices every frame, so a hologram may hold only a few. */
    public static final int MAX_MODEL_LAYERS = 4;

    @javax.annotation.Nullable
    public static String imageProblem(net.minecraft.server.MinecraftServer server, HologramSpec spec) {
        int images = 0;
        int models = 0;
        for (HologramLayer layer : spec.layers()) {
            if (layer.kind() == HologramLayer.Kind.MODEL) {
                if (++models > MAX_MODEL_LAYERS)
                    return "too many model layers (limit " + MAX_MODEL_LAYERS + ")";
                if (!dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork.known(server, layer.id()))
                    return "model layer uses an asset that is not in this world's library";
                continue;
            }
            if (layer.kind() != HologramLayer.Kind.IMAGE)
                continue;
            images++;
            if (!dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork.known(server, layer.id()))
                return "image layer uses an asset that is not in this world's library";
        }
        int max = ServerConfig.hologramImageMaxLayers();
        if (images > max)
            return "too many image layers (" + images + ", limit " + max + ")";
        return null;
    }

    /** Null when the spec's timeline (if any) fits the server's caps; otherwise why not. */
    @javax.annotation.Nullable
    public static String timelineProblem(HologramSpec spec) {
        HologramTimeline tl = spec.timeline();
        String problem = tl == null ? null
                : tl.checkCaps(ServerConfig.hologramTimelineMaxTracks(), ServerConfig.hologramTimelineMaxKeys());
        return problem != null ? problem : HologramAudioMod.checkCaps(spec, ServerConfig.hologramAudioMaxPerValue(),
                ServerConfig.hologramAudioMaxPerHologram());
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
        HologramEditSessions.open(player, spec.id());
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
