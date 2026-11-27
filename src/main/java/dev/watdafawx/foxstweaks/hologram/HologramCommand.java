package dev.watdafawx.foxstweaks.hologram;

import com.mojang.brigadier.arguments.StringArgumentType;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
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
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("attach")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .executes(ctx -> attachSelf(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id"), HologramAttach.Follow.BODY_YAW))
                                                .then(Commands.argument("player", EntityArgument.player())
                                                        .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                                        .executes(ctx -> attach(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "id"),
                                                                EntityArgument.getPlayer(ctx, "player"),
                                                                HologramAttach.Follow.BODY_YAW))
                                                        .then(Commands.argument("follow", StringArgumentType.word())
                                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                                        List.of("position", "body_yaw", "head_look"), builder))
                                                                .executes(ctx -> attach(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "id"),
                                                                        EntityArgument.getPlayer(ctx, "player"),
                                                                        HologramAttach.Follow.parse(StringArgumentType.getString(ctx, "follow"))))))))
                                .then(Commands.literal("detach")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .executes(ctx -> detach(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "id")))))
                                .then(Commands.literal("url")
                                        .then(Commands.argument("id", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(ids(ctx.getSource()), builder))
                                                .then(Commands.argument("kind", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(List.of("image", "model"), builder))
                                                        .then(Commands.argument("url", StringArgumentType.greedyString())
                                                                .executes(ctx -> addUrl(ctx.getSource(),
                                                                        StringArgumentType.getString(ctx, "id"),
                                                                        StringArgumentType.getString(ctx, "kind"),
                                                                        StringArgumentType.getString(ctx, "url")))))))));
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

    private static int attachSelf(CommandSourceStack source, String id, HologramAttach.Follow follow) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Run this as a player, or specify a target player."));
            return 0;
        }
        return attach(source, id, player, follow);
    }

    private static int attach(CommandSourceStack source, String id, ServerPlayer target, HologramAttach.Follow follow) {
        ServerLevel level = source.getLevel();
        HologramSpec spec = Holograms.get(level, id);
        if (spec == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "'."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayer caller && !caller.hasPermissions(Commands.LEVEL_GAMEMASTERS)) {
            if (spec.owner() != null && !spec.owner().equals(caller.getUUID())) {
                source.sendFailure(Component.literal("You do not own hologram '" + id + "'."));
                return 0;
            }
            if (!target.getUUID().equals(caller.getUUID())) {
                source.sendFailure(Component.literal("Only operators can attach holograms to other players."));
                return 0;
            }
        }
        HologramAttach prev = spec.attach();
        boolean hideFp = prev != null ? prev.hideFirstPerson() : true;
        HologramSpec updated = spec.withAttach(new HologramAttach(
                target.getUUID(), target.getGameProfile().getName(), follow, hideFp));
        Holograms.save(level, updated);
        source.sendSuccess(() -> Component.literal("Attached hologram '" + id + "' to "
                + target.getGameProfile().getName() + " (follow: " + follow.label + ")."), true);
        return 1;
    }

    private static int detach(CommandSourceStack source, String id) {
        ServerLevel level = source.getLevel();
        HologramSpec spec = Holograms.get(level, id);
        if (spec == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "'."));
            return 0;
        }
        if (!spec.isAttached()) {
            source.sendFailure(Component.literal("Hologram '" + id + "' is not attached to a player."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayer caller && !caller.hasPermissions(Commands.LEVEL_GAMEMASTERS)
                && spec.owner() != null && !spec.owner().equals(caller.getUUID())) {
            source.sendFailure(Component.literal("You do not own hologram '" + id + "'."));
            return 0;
        }
        ServerPlayer target = source.getServer().getPlayerList().getPlayer(spec.attach().playerUuid());
        ServerLevel dropLevel = target != null ? target.serverLevel() : level;
        BlockPos dropPos = target != null ? target.blockPosition()
                : (source.getEntity() != null ? source.getEntity().blockPosition() : spec.pos());
        HologramSpec updated = spec.at(spec.id(), dropPos, spec.owner(), spec.ownerName()).withAttach(null);
        Holograms.save(dropLevel, updated);
        source.sendSuccess(() -> Component.literal("Detached hologram '" + id + "' at " + dropPos.toShortString() + "."), true);
        return 1;
    }

    private static int addUrl(CommandSourceStack source, String id, String kindRaw, String rawUrl) {
        ServerLevel level = source.getLevel();
        if (!dev.watdafawx.foxstweaks.ServerConfig.hologramAllowExternalUrls()) {
            source.sendFailure(Component.literal("External URLs are disabled on this server."));
            return 0;
        }
        String url = rawUrl.trim();
        if (!HologramNetwork.isExternalUrl(url)) {
            source.sendFailure(Component.literal("URL must start with http:// or https:// (max 512 chars)."));
            return 0;
        }
        HologramSpec spec = Holograms.get(level, id);
        if (spec == null) {
            source.sendFailure(Component.literal("No hologram '" + id + "'."));
            return 0;
        }
        if (source.getEntity() instanceof ServerPlayer caller && !caller.hasPermissions(Commands.LEVEL_GAMEMASTERS)
                && spec.owner() != null && !spec.owner().equals(caller.getUUID())) {
            source.sendFailure(Component.literal("You do not own hologram '" + id + "'."));
            return 0;
        }
        boolean model = "model".equalsIgnoreCase(kindRaw);
        if (!model && !"image".equalsIgnoreCase(kindRaw)) {
            source.sendFailure(Component.literal("Kind must be 'image' or 'model'."));
            return 0;
        }
        List<HologramLayer> next = new ArrayList<>(spec.layers());
        next.add(model ? HologramLayer.model(url, 0.0F) : HologramLayer.image(url, 0.0F));
        HologramSpec updated = spec.withLayers(next);
        String problem = HologramNetwork.imageProblem(source.getServer(), updated);
        if (problem != null) {
            source.sendFailure(Component.literal("Refused: " + problem));
            return 0;
        }
        Holograms.save(level, updated);
        source.sendSuccess(() -> Component.literal("Added URL " + (model ? "3D model" : "stream") + " layer to '" + id + "'."), true);
        return 1;
    }

    private static int list(CommandSourceStack source) {
        List<HologramSpec> dimSpecs = HologramData.get(source.getLevel()).snapshot();
        List<HologramSpec> attachedSpecs = Holograms.listAttached(source.getServer());
        if (dimSpecs.isEmpty() && attachedSpecs.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No holograms in this dimension."), false);
            return 0;
        }
        for (HologramSpec spec : dimSpecs) {
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "- %s  %s  %s  %d layer(s)  %d group(s)  range %d",
                    spec.id(), spec.primaryId(), spec.pos().toShortString(),
                    spec.layers().size(), spec.groups().size(), spec.renderDistance())), false);
        }
        for (HologramSpec spec : attachedSpecs) {
            HologramAttach a = spec.attach();
            String who = a == null ? "?" : (a.playerName().isEmpty() ? a.playerUuid().toString() : a.playerName());
            String mode = a == null ? "" : a.follow().label;
            source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                    "- %s  %s  [attached: %s, %s]  %d layer(s)  range %d",
                    spec.id(), spec.primaryId(), who, mode,
                    spec.layers().size(), spec.renderDistance())), false);
        }
        return dimSpecs.size() + attachedSpecs.size();
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
        if (spec.isAttached()) {
            ServerPlayer target = source.getServer().getPlayerList().getPlayer(spec.attach().playerUuid());
            if (target == null) {
                source.sendFailure(Component.literal("Attached player is offline."));
                return 0;
            }
            Vec3 at = target.position().add(0.0, 0.5, 3.0);
            player.teleportTo(target.serverLevel(), at.x, at.y, at.z, player.getYRot(), player.getXRot());
            return 1;
        }
        Vec3 at = spec.box().getCenter().add(0.0, 0.5, 3.0);
        player.teleportTo(player.serverLevel(), at.x, at.y, at.z, player.getYRot(), player.getXRot());
        return 1;
    }

    private static List<String> ids(CommandSourceStack source) {
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        for (HologramSpec spec : HologramData.get(source.getLevel()).all())
            ids.add(spec.id());
        for (HologramSpec spec : Holograms.listAttached(source.getServer()))
            ids.add(spec.id());
        return new ArrayList<>(ids);
    }

    private static List<String> blockNames() {
        List<String> names = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK)
            names.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        return names;
    }
}
