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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
 * hunting for affixed gear. Also decorates a full set of vanilla diamond gear the same way and
 * equips it, as a control group that is guaranteed to have a {@code LootCategory} regardless of
 * whether relics do.
 *
 * <p>It doubles as a diagnostic: each line reports the {@code LootCategory} the item resolved to.
 * Apotheosis refuses to affix or socket anything that maps to no category, so a hotbar of
 * "no loot category" lines is the direct explanation for relics not getting affixes - vanilla gear
 * getting affixes alongside them confirms Apotheosis itself is working fine.
 */
public class RelicsTestCommand {
    private static final int HOTBAR_SIZE = 9;
    private static final int DEFAULT_SOCKETS = 4;

    /** One vanilla item per equipment slot, decorated the same way as the relics. */
    private static final EquipmentSlot[] NORMAL_GEAR_SLOTS = {
            EquipmentSlot.MAINHAND, EquipmentSlot.HEAD, EquipmentSlot.CHEST,
            EquipmentSlot.LEGS, EquipmentSlot.FEET
    };
    private static final Item[] NORMAL_GEAR_ITEMS = {
            Items.DIAMOND_SWORD, Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE,
            Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS
    };

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

        // Reserve hotbar slot 0 for the normal-gear sword, so it never collides with a relic slot.
        player.getInventory().selected = 0;

        int count = Math.min(HOTBAR_SIZE - 1, relics.size());

        for (int i = 0; i < count; i++) {
            int slot = i + 1;
            ItemStack rolled = new ItemStack(relics.get(i));
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

        int normalGeared = 0;

        // A control group with a guaranteed LootCategory, so "no loot category" on the relics above
        // reads as a relics-specific gap rather than Apotheosis itself not working.
        if (apotheosis) {
            for (int i = 0; i < NORMAL_GEAR_ITEMS.length; i++) {
                ItemStack rolled = new ItemStack(NORMAL_GEAR_ITEMS[i]);
                ApothicTestGear.Result decorated = ApothicTestGear.decorate(player, rolled, sockets);

                player.setItemSlot(NORMAL_GEAR_SLOTS[i], decorated.stack());
                normalGeared++;

                ItemStack placedStack = decorated.stack();
                String note = decorated.note();

                source.sendSuccess(() -> Component.empty()
                        .append(placedStack.getHoverName())
                        .append(Component.literal(" - " + note).withStyle(ChatFormatting.GRAY)), false);
            }
        }

        player.containerMenu.broadcastChanges();

        int placed = count;
        int normal = normalGeared;

        source.sendSuccess(() -> Component.literal("Placed " + placed + " relic(s) and " + normal
                        + " normal item(s) with " + sockets + " socket(s) each.")
                .withStyle(ChatFormatting.GREEN), true);

        return placed;
    }
}
