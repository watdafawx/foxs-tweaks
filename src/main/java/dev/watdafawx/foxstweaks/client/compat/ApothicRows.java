package dev.watdafawx.foxstweaks.client.compat;

import java.util.ArrayList;
import java.util.List;

import dev.shadowsoffire.apotheosis.client.AdventureModuleClient;
import dev.shadowsoffire.apotheosis.socket.SocketHelper;
import dev.watdafawx.foxstweaks.client.ToggleScreen;
import dev.watdafawx.foxstweaks.compat.ApothicAffixToggle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Apotheosis half of the toggle list: one row per affix, built from Apotheosis' own data rather than
 * by reading tooltip text back. The label is the same live description the item tooltip shows
 * ("+7.75 Fire Damage"), via {@link ApothicTooltip#tooltipBody}, which already handles attribute
 * affixes and Stoneforming's marker line. The only door into Apotheosis for {@link ToggleScreen}.
 */
public final class ApothicRows {
    private ApothicRows() {
    }

    public static boolean hasAny(ItemStack stack) {
        return ApothicAffixToggle.hasAnyAffixes(stack);
    }

    public static void setLocal(ItemStack stack, ResourceLocation id, boolean enable) {
        ApothicAffixToggle.setEnabled(stack, id, enable);
    }

    /** One entry per socket, in order: the gem item, or empty for an unfilled (or invalid) socket. Read-only. */
    public static List<ItemStack> gems(ItemStack stack) {
        int sockets = SocketHelper.getSockets(stack);
        List<ItemStack> out = new ArrayList<>();

        if (sockets <= 0)
            return out;

        var socketed = SocketHelper.getGems(stack);

        for (int i = 0; i < sockets; i++) {
            ItemStack gem = i < socketed.size() && socketed.get(i).isValid() ? socketed.get(i).gemStack() : ItemStack.EMPTY;

            out.add(gem);
        }

        return out;
    }

    public static List<ToggleScreen.Row> rows(ItemStack stack) {
        var ctx = AdventureModuleClient.tooltipCtx();
        List<ToggleScreen.Row> rows = new ArrayList<>();

        for (ApothicAffixToggle.Row row : ApothicAffixToggle.list(stack)) {
            ResourceLocation id = row.instance().getAffix().id();
            Component label = ApothicTooltip.tooltipBody(row.instance(), ctx);

            rows.add(new ToggleScreen.Row(true, id, label, List.of(row.instance().getName(true)), row.enabled(), false));
        }

        // Fixed order, so a row does not jump when it is switched off.
        rows.sort(java.util.Comparator.comparing(row -> row.label().getString()));

        return rows;
    }
}
