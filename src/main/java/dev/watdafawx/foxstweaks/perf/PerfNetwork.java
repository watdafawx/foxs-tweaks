package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers the optional performance overlay channel. Clients and servers without Fox's Tweaks
 * connect normally; clients on vanilla servers still show the client-side performance card.
 */
public final class PerfNetwork {
    private PerfNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("2").optional();

        registrar.playToServer(PerfSubscribePayload.TYPE, PerfSubscribePayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player))
                        return;

                    if (payload.subscribed()) {
                        if (!ServerConfig.perfOverlayEnabled())
                            return;
                        if (ServerConfig.perfOverlayOpOnly() && !player.hasPermissions(2))
                            return;
                    }

                    ServerPerfTracker.setSubscribed(player, payload.subscribed());
                }));

        registrar.playToClient(PerfSnapshotPayload.TYPE, PerfSnapshotPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> ClientPerfTracker.onSnapshot(payload)));
    }
}
