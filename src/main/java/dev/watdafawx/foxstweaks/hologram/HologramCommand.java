package dev.watdafawx.foxstweaks.hologram;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /foxstweaks hologram add|remove|list|tp|edit} - the admin path, and what the dev check
 * drives so it exercises the same code the wand does.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class HologramCommand {

    private HologramCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .then(Commands.literal("hologram")
                                .then(Commands.literal("list")
                                        .executes(ctx -> list(ctx.getSource())))
                                .then(Commands.literal("add")
                                        .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        // greedyString, not string(): brigadier's unquoted string parser rejects
                                        // ':' so "minecraft:oak_stairs" would not parse as anything else.
                                        .then(Commands.argument("block", StringArgumentType.greedyString())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(blockNames(), builder))
                                                .executes(ctx -> add(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "block")))))
                                .then(Commands.literal("remove")
                                        .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .executes(ctx -> remove(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("edit")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .executes(ctx -> edit(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("tp")
                                        .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .executes(ctx -> tp(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id")))))));
    }

    private static int add(CommandSourceStack source, String blockName) {
        ServerLevel level = source.getLevel();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, or use the wand."));
            return 0;
        }

        Block block = HologramStates.blockOf(blockName);
        if (block == null) {
            source.sendFailure(Component.literal("Unknown block '" + blockName + "'."));
            return 0;
        }
        if (Holograms.capacityLeft(level) <= 0) {
            source.sendFailure(Component.literal("This dimension has reached its hologram limit."));
            return 0;
        }

        // One block in front of the player's eyes, so the hologram lands where it can be seen.
        Vec3 target = player.getEyePosition().add(player.getViewVector(1.0F).scale(3.0));
        BlockPos pos = BlockPos.containing(target);
        HologramSpec spec = Holograms.create(level, pos, block.defaultBlockState(), pos, player);
        source.sendSuccess(() -> Component.literal("Hologram '" + spec.id() + "' at " + pos.toShortString()
                + ", showing " + spec.primaryId() + "."), false);
        return 1;
    }

    private static int remove(CommandSourceStack source, String id) {
        ServerLevel level = source.getLevel();
        HologramSpec removed = Holograms.remove(level, id);
        if (removed == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "' in " + level.dimension().location() + "."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Removed hologram '" + id + "'."), true);
        return 1;
    }

    private static int edit(CommandSourceStack source, String id) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only a player can open the editor."));
            return 0;
        }
        HologramSpec spec = Holograms.get(player.serverLevel(), id);
        if (spec == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "'."));
            return 0;
        }
        if (!player.hasPermissions(Commands.LEVEL_GAMEMASTERS)
                && spec.owner() != null && !spec.owner().equals(player.getUUID())) {
            source.sendFailure(Component.literal("You do not own hologram '" + id + "'."));
            return 0;
        }
        HologramNetwork.openEditor(player, spec);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<HologramSpec> specs = Holograms.list(source.getLevel());
        if (specs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No holograms in this dimension."), false);
            return 0;
        }
        for (HologramSpec spec : specs) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "- %s  %s  %s  %d layer(s)  %d group(s)  range %d",
                    spec.id(), spec.primaryId(), spec.pos().toShortString(),
                    spec.layers().size(), spec.groups().size(), spec.renderDistance())), false);
        }
        return specs.size();
    }

    private static int tp(CommandSourceStack source, String id) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can teleport."));
            return 0;
        }
        HologramSpec spec = Holograms.get(player.serverLevel(), id);
        if (spec == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "'."));
            return 0;
        }
        Vec3 at = spec.box().getCenter().add(0.0, 0.5, 3.0);
        player.teleportTo(player.serverLevel(), at.x, at.y, at.z, player.getYRot(), player.getXRot());
        return 1;
    }

    private static List<String> ids(CommandSourceStack source) {
        List<String> ids = new ArrayList<>();
        for (HologramSpec spec : Holograms.list(source.getLevel()))
            ids.add(spec.id());
        return ids;
    }

    private static List<String> blockNames() {
        List<String> names = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK)
            names.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        return names;
    }
}
