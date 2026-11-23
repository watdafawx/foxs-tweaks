package dev.watdafawx.foxstweaks.anchor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers optional S2C/C2S payloads for the Spawn Anchor dummy GUI. */
public final class AnchorNetwork {
    private AnchorNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();

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
                        SpawnDummyEvents.applyState(level, dummy, payload.enabled(), payload.radius());
                    }
                }));
    }

    public static void sendUpdate(int entityId, boolean enabled, int radius, boolean pickup) {
        PacketDistributor.sendToServer(new UpdateAnchorPayload(entityId, enabled, radius, pickup));
    }
}
