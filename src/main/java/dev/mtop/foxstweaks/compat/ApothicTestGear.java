package dev.mtop.foxstweaks.compat;

import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.loot.LootCategory;
import dev.shadowsoffire.apotheosis.loot.LootController;
import dev.shadowsoffire.apotheosis.loot.LootRarity;
import dev.shadowsoffire.apotheosis.loot.RarityRegistry;
import dev.shadowsoffire.apotheosis.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.socket.gem.GemItem;
import dev.shadowsoffire.apotheosis.socket.gem.GemRegistry;
import dev.shadowsoffire.apotheosis.socket.gem.Purity;
import dev.shadowsoffire.apotheosis.tiers.GenContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;

/**
 * Loads an item up with the best affixes and a full set of gems, for testing the tooltip panel.
 *
 * <p>Isolated like {@code client.compat.ApothicTooltip}: nothing here is touched unless the caller
 * has already confirmed Apotheosis is installed.
 */
public final class ApothicTestGear {
    /** {@link SocketHelper#setSockets} clamps here, so this is the real ceiling. */
    public static final int MAX_SOCKETS = 16;

    private ApothicTestGear() {
    }

    /** The decorated stack, plus a short human-readable note on what actually happened. */
    public record Result(ItemStack stack, String note) {
    }

    public static Result decorate(ServerPlayer player, ItemStack stack, int sockets) {
        GenContext ctx = GenContext.forPlayer(player);

        // Apotheosis refuses to affix or socket anything whose item does not map to a loot category.
        // Surfacing that is the point: it is exactly what silently stops relics getting affixes.
        LootCategory category = LootCategory.forItem(stack);

        if (category.isNone())
            return new Result(stack, "no loot category - cannot be affixed or socketed");

        LootRarity rarity = RarityRegistry.getSortedRarities().stream()
                .max(Comparator.comparingInt(LootRarity::sortIndex))
                .orElse(null);

        if (rarity == null)
            return new Result(stack, "no loot rarities are loaded");

        // Affixes first: some rarity rules add sockets of their own, and doing this afterwards
        // would overwrite the socket count set below.
        LootController.createLootItem(stack, category, rarity, ctx);

        SocketHelper.setSockets(stack, sockets);

        ItemStack result = stack;
        int filled = 0;

        for (int i = 0; i < sockets; i++) {
            ItemStack gem = GemRegistry.createRandomGemStack(ctx);

            if (gem.isEmpty())
                break;

            // Random gem, but always at the best purity, so the numbers shown are the maximum.
            GemItem.setPurity(gem, Purity.PERFECT);

            // Returns a modified copy, or EMPTY when the gem is not valid for this item.
            ItemStack socketed = SocketHelper.socketGemInItem(result, gem);

            if (socketed.isEmpty())
                break;

            result = socketed;
            filled++;
        }

        return new Result(result, String.format("%s, %d affixes, %d/%d gems",
                category.getKey(), AffixHelper.streamAffixes(result).count(), filled, sockets));
    }
}
