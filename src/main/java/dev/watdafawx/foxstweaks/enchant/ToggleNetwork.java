package dev.watdafawx.foxstweaks.enchant;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.compat.ApothicAffixToggle;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers {@link ToggleEntryPayload}. Optional channel, so a client without a matching server is
 * unaffected. The Apotheosis branch only classloads {@link ApothicAffixToggle} once it actually runs.
 */
public final class ToggleNetwork {
    private ToggleNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("3")
                .optional()
                .playToServer(ToggleEntryPayload.TYPE, ToggleEntryPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (!(context.player() instanceof ServerPlayer player))
                        return;

                    ItemStack stack = player.getItemBySlot(payload.slot());

                    if (stack.isEmpty())
                        return;

                    if (!payload.affix()) {
                        if (Config.ENCHANT_TOGGLE.get())
                            EnchantToggle.setEnabled(stack, player.registryAccess(), payload.id(), payload.enable());
                    } else if (Config.AFFIX_TOGGLE_GUI.get() && ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS)) {
                        ApothicAffixToggle.setEnabled(stack, payload.id(), payload.enable());
                    }
                }));
    }
}
