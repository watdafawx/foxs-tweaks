package dev.mtop.foxstweaks.apotheosis;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.compat.ApothicAffixToggle;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers {@link ToggleAffixPayload}. Registered unconditionally like {@code storage.NearbyItemsNetwork}
 * - this class references no Apotheosis type, only {@code compat.ApothicAffixToggle}, which is guarded
 * below and only classloads once the handler actually runs.
 */
public final class AffixToggleNetwork {
    private AffixToggleNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(ToggleAffixPayload.TYPE, ToggleAffixPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (!Config.AFFIX_TOGGLE_GUI.get() || !ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS) || !(context.player() instanceof ServerPlayer player))
                        return;

                    ItemStack stack = player.getMainHandItem();

                    if (!stack.isEmpty())
                        ApothicAffixToggle.setEnabled(stack, payload.affixId(), payload.enable());
                }));
    }
}
