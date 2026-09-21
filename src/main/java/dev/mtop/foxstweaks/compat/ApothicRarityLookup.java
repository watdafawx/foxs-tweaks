package dev.mtop.foxstweaks.compat;

import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Confines the Apotheosis type to one class that is only loaded once Apotheosis is known to be present. */
public final class ApothicRarityLookup {
    private ApothicRarityLookup() {
    }

    /** The stack's rarity id, or {@code null} when it has none (or it is not bound to a loaded rarity). */
    public static ResourceLocation rarityOf(ItemStack stack) {
        var holder = AffixHelper.getRarity(stack);

        return holder.isBound() ? holder.getId() : null;
    }
}
