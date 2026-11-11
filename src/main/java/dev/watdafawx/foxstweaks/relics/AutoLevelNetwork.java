package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Registers {@link AutoLevelPayload}. Unconditional, like the other channels; the handler is guarded. */
public final class AutoLevelNetwork {
    private AutoLevelNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(AutoLevelPayload.TYPE, AutoLevelPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (!Config.RELIC_AUTO_LEVEL.get() || !ModList.get().isLoaded(FoxsTweaks.RELICS)
                            || !(context.player() instanceof ServerPlayer player))
                        return;

                    AutoLevelHandler.choose(player, payload.container(), payload.slot(), payload.ability());
                }));
    }
}
