package dev.watdafawx.foxstweaks.anchor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers optional S2C/C2S payloads for the Spawn Anchor dummy GUI.
 *
 * <p>Registrar version "2": the payloads grew the Apotheosis tier and the name-visibility flag, so a client
 * still on version 1 simply cannot open the GUI (the payloads are {@code optional()}, nothing breaks).
 */
public final class AnchorNetwork {
    private AnchorNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();

        registrar.playToClient(OpenAnchorGuiPayload.TYPE, OpenAnchorGuiPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (FMLEnvironment.dist.isClient()) {
                        dev.watdafawx.foxstweaks.client.SpawnAnchorScreen.open(payload);
                    }
                }));

        registrar.playToServer(UpdateAnchorPayload.TYPE, UpdateAnchorPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player))
                        return;

                    ServerLevel level = player.serverLevel();
                    Entity entity = level.getEntity(payload.entityId());
                    if (!(entity instanceof ArmorStand dummy) || !SpawnDummyEvents.isDummy(dummy))
                        return;
                    if (player.distanceToSqr(dummy) > 12.0 * 12.0)
                        return;

                    if (payload.pickup()) {
                        SpawnDummyEvents.pickUpDummy(player, dummy);
                    } else {
                        // The client is never trusted for the tier string. An unscoreable tier (no Apotheosis,
                        // or a name this build doesn't know) keeps the anchor's existing one instead of
                        // rejecting the update, so radius/name/status changes still apply.
                        String tier = payload.tier();
                        if (!tier.isEmpty()) {
                            String normalised = AnchorTier.normaliseTier(tier);
                            tier = normalised.isEmpty() ? SpawnDummyEvents.getTier(dummy) : normalised;
                        }

                        boolean followOwner = payload.followOwner() && SpawnDummyEvents.hasOwner(dummy);
                        if (followOwner && tier.isEmpty()) {
                            // Following an owner who is online right now: seed the offline snapshot.
                            ServerPlayer owner = SpawnDummyEvents.ownerOf(dummy) == null ? null
                                    : player.getServer().getPlayerList().getPlayer(SpawnDummyEvents.ownerOf(dummy));
                            if (owner != null)
                                tier = AnchorTier.tierNameOf(owner);
                        }

                        SpawnDummyEvents.applyState(level, dummy, payload.enabled(), payload.radius(),
                                tier, followOwner, payload.hideName());
                    }
                }));
    }

    public static void sendUpdate(int entityId, boolean enabled, int radius, String tier, boolean followOwner,
                                  boolean hideName, boolean pickup) {
        PacketDistributor.sendToServer(new UpdateAnchorPayload(entityId, enabled, radius, tier, followOwner, hideName, pickup));
    }
}
