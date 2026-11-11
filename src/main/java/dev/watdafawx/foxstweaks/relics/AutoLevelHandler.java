package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.utils.EntityUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import top.theillusivec4.curios.api.CuriosApi;

/**
 * Levels up the ability a relic was marked with ({@link FoxsTweaksComponents#AUTO_LEVEL_ABILITY}),
 * and ranks the relic up once that ability is done. Uses Relics' own {@code upgrade()} and
 * {@code rankup()}, so cost and limits are exactly the manual ones: player XP, upgrade points,
 * the ability's max level. Server-side; relics with no mark are never touched.
 */
public class AutoLevelHandler {
    private static final int SWEEP_INTERVAL = 20;

    /** Safety cap on upgrades per relic per sweep. */
    private static final int MAX_UPGRADES = 64;

    /** Menu-slot based, like Relics' own packets, so Curios slots in the open menu work too. */
    public static void choose(ServerPlayer player, int container, int slot, String ability) {
        var menu = player.containerMenu;

        if (menu.containerId != container || !menu.isValidSlotIndex(slot))
            return;

        ItemStack stack = menu.getSlot(slot).getItem();

        if (!(stack.getItem() instanceof IRelicItem relic))
            return;

        var component = FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get();

        if (ability.isEmpty() || ability.equals(stack.get(component))) {
            stack.remove(component);
        } else {
            var data = relic.getRelicData(player, stack);

            if (data.getTemplate() == null)
                return;

            var abilityData = data.getAbilitiesData().getAbilityData(ability);

            if (abilityData == null || abilityData.getTemplate() == null || !abilityData.canBeUpgraded())
                return;

            stack.set(component, ability);
        }

        process(player, stack);
        menu.broadcastChanges();
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(Config.RELIC_AUTO_LEVEL.get() || Config.RELIC_AUTO_UNLOCK.get())
                || !(event.getEntity() instanceof ServerPlayer player)
                || player.tickCount % SWEEP_INTERVAL != 0)
            return;

        var inventory = player.getInventory();

        for (ItemStack stack : inventory.items)
            process(player, stack);

        for (ItemStack stack : inventory.armor)
            process(player, stack);

        for (ItemStack stack : inventory.offhand)
            process(player, stack);

        CuriosApi.getCuriosInventory(player).ifPresent(curios -> {
            var slots = curios.getEquippedCurios();

            for (int i = 0; i < slots.getSlots(); i++) {
                ItemStack stack = slots.getStackInSlot(i);

                if (process(player, stack))
                    slots.setStackInSlot(i, stack);
            }
        });
    }

    /**
     * Relics' unlock step is five clicks on a ready ability card, which the client reports with a
     * plain "unlocks = N" packet. Ready = researched, relic level and rank high enough.
     */
    private static boolean unlockReady(it.hurts.sskirillss.relics.api.relics.data.RelicData data) {
        boolean changed = false;
        var abilities = data.getAbilitiesData();

        for (String id : abilities.getAbilityIDs()) {
            var ability = abilities.getAbilityData(id);

            if (ability == null || ability.getTemplate() == null)
                continue;

            var lock = ability.getLockData();

            if (lock.isUnlocked() || !ability.isEnoughLevel() || !ability.isEnoughRank()
                    || !ability.getResearchData().isResearched())
                continue;

            lock.setUnlocks(lock.getMaxUnlocks());
            changed = true;
        }

        return changed;
    }

    /** @return whether the relic changed. */
    private static boolean process(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof IRelicItem relic))
            return false;

        var data = relic.getRelicData(player, stack);

        // Same guard as AutoResearchHandler: addon relics may have no template.
        if (data.getTemplate() == null)
            return false;

        boolean changed = Config.RELIC_AUTO_UNLOCK.get() && unlockReady(data);

        String id = stack.get(FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get());

        if (id == null || !Config.RELIC_AUTO_LEVEL.get())
            return changed;

        var ability = data.getAbilitiesData().getAbilityData(id);

        if (ability == null || ability.getTemplate() == null)
            return changed;

        int minXp = Config.RELIC_AUTO_LEVEL_MIN_XP.get();

        for (int i = 0; i < MAX_UPGRADES && ability.mayPlayerUpgrade(); i++) {
            // upgrade() spends exactly this much player XP.
            if (minXp > 0 && EntityUtils.getPlayerTotalExperience(player) - ability.getUpgradePlayerExperienceCost() < minXp)
                break;

            if (!ability.upgrade())
                break;

            changed = true;
        }

        // Ranking up wipes ability levels and unspent points, so wait until the ability could take
        // another upgrade if only there were experience for it.
        if (Config.RELIC_AUTO_RANKUP.get() && !ability.mayUpgrade() && ability.canBeUpgraded()) {
            var leveling = data.getLevelingData();

            if (leveling.mayPlayerRankup() && leveling.rankup())
                changed = true;
        }

        return changed;
    }
}
