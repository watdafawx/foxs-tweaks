package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers the optional {@link RelicParticlesPayload} channel. */
public final class RelicParticlesNetwork {
    private RelicParticlesNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(RelicParticlesPayload.TYPE, RelicParticlesPayload.CODEC, (payload, context) ->
                        context.enqueueWork(() -> {
                            if (!Config.enabled(Config.RELIC_PARTICLE_TOGGLE)
                                    || !(context.player() instanceof ServerPlayer player))
                                return;

                            RelicParticles.applyFromClient(player, payload.container(), payload.slot(), payload.hidden());
                        }));
    }

    public static void send(int container, int slot, boolean hidden) {
        var conn = Minecraft.getInstance().getConnection();
        if (conn != null && conn.hasChannel(RelicParticlesPayload.TYPE)) {
            PacketDistributor.sendToServer(new RelicParticlesPayload(container, slot, hidden));
        }
    }
}
