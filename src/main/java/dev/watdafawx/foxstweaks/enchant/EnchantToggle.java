package dev.watdafawx.foxstweaks.enchant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Pulls a vanilla (or modded) enchantment off an item and puts it back. Vanilla only, so unlike the
 * affix toggle this has no optional dependency and works on any pack.
 *
 * <p>Disabling really removes the enchantment from the item's enchantment component - a cosmetic
 * flag would do nothing for enchantments whose effects run in vanilla or other mods. The removed
 * id and level are kept in {@link FoxsTweaksComponents#DISABLED_ENCHANTS}, so re-enabling restores
 * the exact level.
 */
public final class EnchantToggle {
    private EnchantToggle() {
    }

    /** One row for the overlay: the enchantment as it would apply, plus whether it currently does. */
    public record Row(Holder<Enchantment> enchantment, ResourceLocation id, int level, boolean enabled) {
    }

    public static DisabledEnchants disabledOf(ItemStack stack) {
        return stack.getOrDefault(FoxsTweaksComponents.DISABLED_ENCHANTS.get(), DisabledEnchants.EMPTY);
    }

    /** True when the item has at least one enchantment the overlay can act on, active or disabled. */
    public static boolean hasAny(ItemStack stack, HolderLookup.Provider registries) {
        return !list(stack, registries).isEmpty();
    }

    public static boolean isToggleable(ResourceLocation id) {
        return !Config.ENCHANT_TOGGLE_BLACKLIST.get().contains(id.toString());
    }

    /** Every toggleable enchantment the item has: active ones in the item's own order, then disabled ones by id. */
    public static List<Row> list(ItemStack stack, HolderLookup.Provider registries) {
        List<Row> rows = new ArrayList<>();
        ItemEnchantments live = EnchantmentHelper.getEnchantmentsForCrafting(stack);

        for (var entry : live.entrySet()) {
            Holder<Enchantment> holder = entry.getKey();
            ResourceLocation id = holder.unwrapKey().map(key -> key.location()).orElse(null);

            if (id != null && isToggleable(id))
                rows.add(new Row(holder, id, entry.getIntValue(), true));
        }

        var lookup = registries.lookupOrThrow(Registries.ENCHANTMENT);
        List<Row> disabled = new ArrayList<>();

        disabledOf(stack).entries().forEach((id, level) -> {
            Optional<Holder.Reference<Enchantment>> holder = lookup.get(net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id));

            holder.ifPresent(h -> disabled.add(new Row(h, id, level, false)));
        });

        rows.addAll(disabled);

        // Fixed order, so a row does not jump to the end of the list when it is switched off.
        rows.sort(java.util.Comparator.comparing(row -> row.id().toString()));

        return rows;
    }

    /** Moves one enchantment between the item's live component and ours. No-op if it is already in that state. */
    public static void setEnabled(ItemStack stack, HolderLookup.Provider registries, ResourceLocation id, boolean enabled) {
        if (!isToggleable(id))
            return;

        var key = net.minecraft.resources.ResourceKey.create(Registries.ENCHANTMENT, id);
        Optional<Holder.Reference<Enchantment>> holder = registries.lookupOrThrow(Registries.ENCHANTMENT).get(key);

        if (holder.isEmpty())
            return;

        Map<ResourceLocation, Integer> disabled = new HashMap<>(disabledOf(stack).entries());

        if (enabled) {
            Integer level = disabled.remove(id);

            if (level == null)
                return;

            // Putting it back must not create a combination vanilla forbids (Silk Touch + Fortune).
            if (!EnchantmentHelper.isEnchantmentCompatible(EnchantmentHelper.getEnchantmentsForCrafting(stack).keySet(), holder.get()))
                return;

            EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.set(holder.get(), level));
        } else {
            int level = EnchantmentHelper.getEnchantmentsForCrafting(stack).getLevel(holder.get());

            if (level <= 0)
                return;

            EnchantmentHelper.updateEnchantments(stack, mutable -> mutable.removeIf(h -> h.is(id)));
            disabled.put(id, level);
        }

        if (disabled.isEmpty())
            stack.remove(FoxsTweaksComponents.DISABLED_ENCHANTS.get());
        else
            stack.set(FoxsTweaksComponents.DISABLED_ENCHANTS.get(), new DisabledEnchants(disabled));
    }
}
