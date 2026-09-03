package dev.mtop.foxstweaks.relics;

import dev.mtop.foxstweaks.Config;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Completes relic research automatically when {@link Config#AUTO_SOLVE_ON_PICKUP} is on.
 *
 * <p>Runs entirely on the server, because research lives in a server-authoritative data attachment
 * and {@code ResearchData} refuses to write unless it is handed a {@link ServerPlayer}.
 */
public class AutoResearchHandler {
    /**
     * Picking a relic off the ground is only one of the ways to get one - creative mode, crafting,
     * trading and pulling one out of a chest all fire nothing useful. A slow sweep of what the
     * player is carrying covers every route without needing an event for each.
     */
    private static final int SWEEP_INTERVAL = 40;

    @SubscribeEvent
    public static void onItemPickup(ItemEntityPickupEvent.Post event) {
        if (!Config.AUTO_SOLVE_ON_PICKUP.get())
            return;

        if (event.getPlayer() instanceof ServerPlayer player)
            solve(player, event.getOriginalStack());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!Config.AUTO_SOLVE_ON_PICKUP.get()
                || !(event.getEntity() instanceof ServerPlayer player)
                || player.tickCount % SWEEP_INTERVAL != 0)
            return;

        var inventory = player.getInventory();

        for (ItemStack stack : inventory.items)
            solve(player, stack);

        for (ItemStack stack : inventory.armor)
            solve(player, stack);

        for (ItemStack stack : inventory.offhand)
            solve(player, stack);
    }

    /**
     * Research is stored per player against {@code <item id>#<ability id>} rather than against an
     * individual stack, so this is idempotent and costs nothing once a relic type has been solved:
     * every later sweep short-circuits on {@code isResearched}.
     */
    private static void solve(ServerPlayer player, ItemStack stack) {
        if (!(stack.getItem() instanceof IRelicItem relic))
            return;

        var relicData = relic.getRelicData(player, stack);

        // RelicData#getTemplate is @Nullable and AbilitiesData dereferences it without checking.
        // This sweep runs every couple of seconds over everything the player carries, so an addon
        // relic without a template would otherwise throw on the server tick over and over.
        if (relicData.getTemplate() == null)
            return;

        var abilities = relicData.getAbilitiesData();

        for (String ability : abilities.getAbilityIDs()) {
            var abilityData = abilities.getAbilityData(ability);

            if (abilityData == null)
                continue;

            var template = abilityData.getTemplate();

            // An ability with no stars has no puzzle to solve and already counts as researched.
            if (template == null || template.getResearchTemplate().getStars().isEmpty())
                continue;

            var research = abilityData.getResearchData();

            if (research.isResearched())
                continue;

            // Fills in the correct links from the relic's own template and marks it done.
            research.complete();
        }
    }
}
