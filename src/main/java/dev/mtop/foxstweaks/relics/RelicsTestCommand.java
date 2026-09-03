package dev.mtop.foxstweaks.relics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.compat.ApothicTestGear;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * {@code /foxstweaks testhotbar [sockets]} - fills the hotbar with relics carrying the best affixes
 * Apotheosis will roll and a full set of random gems, for exercising the affix tooltip without
 * hunting for affixed gear.
 *
 * <p>It doubles as a diagnostic: each line reports the {@code LootCategory} the relic resolved to.
 * Apotheosis refuses to affix or socket anything that maps to no category, so a hotbar of
 * "no loot category" lines is the direct explanation for relics not getting affixes.
 */
public class RelicsTestCommand {
    private static final int HOTBAR_SIZE = 9;
    private static final int DEFAULT_SOCKETS = 4;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("testhotbar")
                                .executes(ctx -> fillHotbar(ctx.getSource(), DEFAULT_SOCKETS))
                                .then(Commands.argument("sockets", IntegerArgumentType.integer(0, ApothicTestGear.MAX_SOCKETS))
                                        .executes(ctx -> fillHotbar(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "sockets"))))));
    }

    private static int fillHotbar(CommandSourceStack source, int sockets) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();

        List<Item> relics = BuiltInRegistries.ITEM.stream()
                .filter(item -> item instanceof IRelicItem)
                .collect(Collectors.toCollection(ArrayList::new));

        if (relics.isEmpty()) {
            source.sendFailure(Component.literal("No relic items are registered."));

            return 0;
        }

        Collections.shuffle(relics, new Random(player.getRandom().nextLong()));

        boolean apotheosis = ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS);
        int count = Math.min(HOTBAR_SIZE, relics.size());

        for (int slot = 0; slot < count; slot++) {
            ItemStack rolled = new ItemStack(relics.get(slot));
            String result = "Apotheosis not installed - plain relic";

            if (apotheosis) {
                ApothicTestGear.Result decorated = ApothicTestGear.decorate(player, rolled, sockets);

                rolled = decorated.stack();
                result = decorated.note();
            }

            player.getInventory().items.set(slot, rolled);

            ItemStack placedStack = rolled;
            String note = result;

            source.sendSuccess(() -> Component.empty()
                    .append(placedStack.getHoverName())
                    .append(Component.literal(" - " + note).withStyle(ChatFormatting.GRAY)), false);
        }

        player.containerMenu.broadcastChanges();

        int placed = count;

        source.sendSuccess(() -> Component.literal("Placed " + placed + " relic(s) with " + sockets + " socket(s) each.")
                .withStyle(ChatFormatting.GREEN), true);

        return placed;
    }
}
