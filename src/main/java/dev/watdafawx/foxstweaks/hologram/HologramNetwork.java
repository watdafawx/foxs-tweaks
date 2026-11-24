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
 * Payload registration and the server half of the editor.
 *
 * <p>Registrar version "1": holograms are new, so a client on an older build simply cannot open the
 * editor (the payloads are {@code optional()}, so nothing else breaks on a mixed pack).
 */
public final class HologramNetwork {
    private HologramNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();

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

        // The client can send any position it likes; only the stored one is ever trusted.
        if (player.distanceToSqr(Vec3.atCenterOf(stored.pos())) > square(Holograms.REACH + 8.0))
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
            HologramSpec updated = stored.withData(captured);
            Holograms.save(level, updated);
            openEditor(player, updated);
            return;
        }

        HologramSpec incoming = HologramSpec.fromTag(payload.spec());
        if (incoming == null)
            return;

        // Identity, position and owner come from the server; only the presentation is the client's.
        Holograms.save(level, incoming.at(stored.id(), stored.pos(), stored.owner(), stored.ownerName())
                .withRenderDistance(Math.min(incoming.renderDistance(), ServerConfig.hologramsMaxRenderDistance())));
    }

    private static boolean canEdit(ServerPlayer player, HologramSpec spec) {
        if (player.hasPermissions(2))
            return true;
        UUID owner = spec.owner();
        return owner == null || owner.equals(player.getUUID());
    }

    /**
     * The clipboard's paste: a new hologram at the requested spot, built from the presentation fields
     * the client sent. Identity and owner are always the server's - a pasted hologram belongs to
     * whoever pasted it, and its block has to exist on this side.
     */
    private static void create(ServerPlayer player, CompoundTag tag) {
        if (!ServerConfig.hologramsEnabled() || !tag.contains("pos"))
            return;

        ServerLevel level = player.serverLevel();
        HologramSpec style = HologramSpec.fromTag(tag);
        if (style == null || HologramStates.blockOf(style.block()) == null)
            return;
        if (Holograms.capacityLeft(level) <= 0)
            return;

        BlockPos pos = BlockPos.of(tag.getLong("pos"));
        if (!level.isLoaded(pos))
            return;
        if (player.distanceToSqr(Vec3.atCenterOf(pos)) > square(Holograms.REACH + 8.0))
            return;

        HologramSpec created = style.at(Holograms.newId(level, style.block()), pos,
                        player.getUUID(), player.getGameProfile().getName())
                .withRenderDistance(Math.min(style.renderDistance(), ServerConfig.hologramsMaxRenderDistance()));

        Holograms.save(level, created);
        level.playSound(null, pos, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.2F);
        openEditor(player, created);
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

    public static void sendDelete(String id) {
        PacketDistributor.sendToServer(HologramActionPayload.delete(id));
    }

    public static void sendCreate(BlockPos pos, HologramSpec style) {
        PacketDistributor.sendToServer(HologramActionPayload.create(pos, style));
    }

    public static void sendRecapture(String id) {
        PacketDistributor.sendToServer(HologramActionPayload.recapture(id));
    }
}
