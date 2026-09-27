package dev.mtop.foxstweaks.gag;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.SlotResult;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

/**
 * Curios side of the Temporal Pouch slot. The slot itself is data ({@code curios/slots/temporal_pouch.json}, gated on
 * GAG being loaded); this makes the pouch keep filling while it sits there and finds it for the hotkey.
 *
 * <p>GAG only adds stored time in {@code inventoryTick}, which Curios never calls, so {@link #curioTick} runs it. GAG
 * itself stops two pouches in the main inventory stacking time by zeroing the smaller one; that check can't see a
 * curio slot, so the equipped pouch simply doesn't tick while another pouch is in the inventory (that one fills
 * instead). Only reached behind {@link TemporalPouchNetwork#isAvailable()}, since it imports Curios types.
 */
public final class TemporalPouchCurio implements ICurioItem {
    public static final ResourceLocation POUCH_ID = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.GAG, "time_sand_pouch");

    private TemporalPouchCurio() {
    }

    public static void register() {
        Item pouch = BuiltInRegistries.ITEM.get(POUCH_ID);

        if (pouch != Items.AIR)
            CuriosApi.registerCurio(pouch, new TemporalPouchCurio());
    }

    public static ItemStack findEquipped(Player player) {
        Item pouch = BuiltInRegistries.ITEM.get(POUCH_ID);
        return CuriosApi.getCuriosInventory(player)
                .flatMap(inventory -> inventory.findFirstCurio(pouch))
                .map(SlotResult::stack)
                .orElse(ItemStack.EMPTY);
    }

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        // GAG's inventoryTick only acts every 20 ticks and ignores the client and fake players itself.
        if (!Config.TEMPORAL_POUCH_CURIO.get() || !(slotContext.entity() instanceof Player player) || player.level().getGameTime() % 20 != 0)
            return;

        if (player.getInventory().hasAnyMatching(other -> other.is(stack.getItem())))
            return;

        stack.inventoryTick(player.level(), player, -1, false);
    }
}
