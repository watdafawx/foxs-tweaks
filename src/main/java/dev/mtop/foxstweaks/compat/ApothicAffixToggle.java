package dev.mtop.foxstweaks.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.mtop.foxstweaks.FoxsTweaksComponents;
import dev.mtop.foxstweaks.apotheosis.DisabledAffixes;
import dev.shadowsoffire.apotheosis.Apoth.Components;
import dev.shadowsoffire.apotheosis.affix.Affix;
import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.affix.AffixRegistry;
import dev.shadowsoffire.apotheosis.affix.ItemAffixes;
import dev.shadowsoffire.apotheosis.loot.LootRarity;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Lets a player pull an individual affix off an item and put it back, for the tooltip-as-controls
 * overlay ({@code client.compat.AffixToggleScreen}). Confines every Apotheosis type used for it to
 * this one class, alongside {@code client.compat.ApothicTooltip} and
 * {@code client.compat.ApothicTestGear} - see AGENTS.md.
 *
 * <p>Disabling actually removes the affix from Apotheosis' own {@link ItemAffixes} component, not
 * just a cosmetic flag - most affixes (Vein Mining, Sculk Affinity, ...) are Apotheosis' own
 * behaviour, never routed through this mod, so nothing short of removal stops them from firing.
 * The removed id and level are kept in {@link FoxsTweaksComponents#DISABLED_AFFIXES} so re-enabling
 * restores the exact level rather than re-rolling it.
 */
public final class ApothicAffixToggle {
    private ApothicAffixToggle() {
    }

    /** One row for the toggle GUI/tooltip: the affix as it would apply, plus whether it currently does. */
    public record Row(AffixInstance instance, boolean enabled) {
    }

    public static DisabledAffixes disabledOf(ItemStack stack) {
        return stack.getOrDefault(FoxsTweaksComponents.DISABLED_AFFIXES.get(), DisabledAffixes.EMPTY);
    }

    public static boolean hasAnyAffixes(ItemStack stack) {
        return AffixHelper.hasAffixes(stack) || !disabledOf(stack).entries().isEmpty();
    }

    /**
     * Every affix the item has, active or disabled, in Apotheosis tooltip order
     * ({@code AffixType} ordinal: STAT, BASIC_EFFECT, ABILITY). Disabled ones stay in that
     * sequence rather than being dumped at the end - the overlay injects their ❌ line where
     * the live description sat.
     */
    public static List<Row> list(ItemStack stack) {
        List<Row> rows = new ArrayList<>();

        AffixHelper.streamAffixes(stack).forEach(instance -> rows.add(new Row(instance, true)));

        DynamicHolder<LootRarity> rarity = AffixHelper.getRarity(stack);

        disabledOf(stack).entries().forEach((id, level) -> {
            DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(id);

            if (holder.isBound())
                rows.add(new Row(new AffixInstance(holder, level, rarity, stack), false));
        });

        rows.sort(Comparator.comparingInt(row -> row.instance().getAffix().definition().type().ordinal()));

        return rows;
    }

    /** Moves one affix between Apotheosis' live component and ours. No-op if it is already in that state. */
    public static void setEnabled(ItemStack stack, ResourceLocation affixId, boolean enabled) {
        DynamicHolder<Affix> holder = AffixRegistry.INSTANCE.holder(affixId);

        if (!holder.isBound())
            return;

        ItemAffixes affixes = stack.getOrDefault(Components.AFFIXES, ItemAffixes.EMPTY);
        Map<ResourceLocation, Float> disabled = new HashMap<>(disabledOf(stack).entries());

        if (enabled) {
            Float level = disabled.remove(affixId);

            if (level == null)
                return;

            AffixHelper.setAffixes(stack, affixes.toBuilder().put(holder, level).build());
        } else {
            float level = affixes.getLevel(holder);

            if (level <= 0)
                return;

            AffixHelper.setAffixes(stack, affixes.toBuilder().remove(holder).build());
            disabled.put(affixId, level);
        }

        stack.set(FoxsTweaksComponents.DISABLED_AFFIXES.get(), new DisabledAffixes(disabled));
    }
}
