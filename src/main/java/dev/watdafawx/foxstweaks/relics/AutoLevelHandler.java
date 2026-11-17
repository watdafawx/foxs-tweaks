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

import java.util.ArrayList;
import java.util.List;

/**
 * Levels up the abilities a relic was marked with ({@link AutoLevelSetting}, stored in
 * {@link FoxsTweaksComponents#AUTO_LEVEL}), one after another or round robin, and ranks the relic
 * up once all of them are done. Uses Relics' own {@code upgrade()} and
 * {@code rankup()}, so cost and limits are exactly the manual ones: player XP, upgrade points,
 * the ability's max level. Server-side; relics with no mark are never touched.
 */
public class AutoLevelHandler {
    private static final int SWEEP_INTERVAL = 20;

    /** Safety cap on upgrades per relic per sweep. */
    private static final int MAX_UPGRADES = 64;

    /** Menu-slot based, like Relics' own packets, so Curios slots in the open menu work too. */
    public static void choose(ServerPlayer player, int container, int slot, String ability, int action) {
        var menu = player.containerMenu;

        if (menu.containerId != container || !menu.isValidSlotIndex(slot))
            return;

        ItemStack stack = menu.getSlot(slot).getItem();

        if (!(stack.getItem() instanceof IRelicItem relic))
            return;

        AutoLevelSetting current = AutoLevelSetting.of(stack);
        AutoLevelSetting next;

        switch (action) {
            case AutoLevelPayload.CLEAR -> next = AutoLevelSetting.EMPTY;
            case AutoLevelPayload.CYCLE_MODE -> {
                if (current.abilities().isEmpty())
                    return;

                next = current.withRoundRobin(!current.roundRobin());
            }
            case AutoLevelPayload.TOGGLE -> {
                // Unmarking needs no checks; marking only accepts an ability that can be upgraded.
                if (!current.has(ability)) {
                    var data = relic.getRelicData(player, stack);

                    if (data.getTemplate() == null)
                        return;

                    var abilityData = data.getAbilitiesData().getAbilityData(ability);

                    if (abilityData == null || abilityData.getTemplate() == null || !abilityData.canBeUpgraded())
                        return;
                }

                next = current.toggled(ability);
            }
            default -> {
                return;
            }
        }

        next.applyTo(stack);
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

        var setting = AutoLevelSetting.of(stack);

        if (setting.abilities().isEmpty() || !Config.RELIC_AUTO_LEVEL.get())
            return changed;

        // Ids the relic no longer has (removed addon, renamed ability) are ignored, not an error.
        List<String> ids = new ArrayList<>();

        for (String id : setting.abilities()) {
            var ability = data.getAbilitiesData().getAbilityData(id);

            if (ability != null && ability.getTemplate() != null)
                ids.add(id);
        }

        if (ids.isEmpty())
            return changed;

        int minXp = Config.RELIC_AUTO_LEVEL_MIN_XP.get();

        if (setting.roundRobin()) {
            int cursor = setting.cursor() % ids.size();
            int misses = 0;

            // One upgrade per ability in turn; stops after a full lap where nobody could upgrade.
            for (int done = 0; done < MAX_UPGRADES && misses < ids.size(); ) {
                String id = ids.get(cursor);

                cursor = (cursor + 1) % ids.size();

                if (upgradeOnce(player, data, id, minXp)) {
                    changed = true;
                    misses = 0;
                    done++;
                } else {
                    misses++;
                }
            }

            var moved = setting.withCursor(cursor);

            if (moved != setting) {
                moved.applyTo(stack);
                changed = true;
            }
        } else {
            int done = 0;

            // In list order: the first ability gets everything until it is done, then the next.
            for (String id : ids) {
                var ability = data.getAbilitiesData().getAbilityData(id);

                while (done < MAX_UPGRADES && upgradeOnce(player, data, id, minXp)) {
                    changed = true;
                    done++;
                }

                // Still wants an upgrade but lacks experience (or the XP floor): wait for it, don't
                // spend on the abilities behind it.
                if (ability.mayUpgrade())
                    break;
            }
        }

        // Ranking up wipes ability levels and unspent points, so wait until every marked ability
        // could take no more upgrades even with all the experience in the world.
        if (Config.RELIC_AUTO_RANKUP.get() && allDone(data, ids)) {
            var leveling = data.getLevelingData();

            if (leveling.mayPlayerRankup() && leveling.rankup())
                changed = true;
        }

        return changed;
    }

    private static boolean allDone(it.hurts.sskirillss.relics.api.relics.data.RelicData data, List<String> ids) {
        for (String id : ids) {
            var ability = data.getAbilitiesData().getAbilityData(id);

            if (ability.mayUpgrade() || !ability.canBeUpgraded())
                return false;
        }

        return true;
    }

    /** One upgrade of the ability, at exactly the manual cost, keeping {@code minXp} experience back. */
    private static boolean upgradeOnce(ServerPlayer player, it.hurts.sskirillss.relics.api.relics.data.RelicData data,
                                       String id, int minXp) {
        var ability = data.getAbilitiesData().getAbilityData(id);

        if (ability == null || !ability.mayPlayerUpgrade())
            return false;

        // upgrade() spends exactly this much player XP.
        if (minXp > 0 && EntityUtils.getPlayerTotalExperience(player) - ability.getUpgradePlayerExperienceCost() < minXp)
            return false;

        return ability.upgrade();
    }
}
