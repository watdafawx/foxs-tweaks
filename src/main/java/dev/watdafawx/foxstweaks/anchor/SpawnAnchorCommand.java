package dev.watdafawx.foxstweaks.anchor;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /foxstweaks anchor add|remove|list|tp} — manage persistent virtual spawn anchors.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class SpawnAnchorCommand {

    /** Apotheosis' tier names, plus {@code owner} to follow the placer and {@code off} to carry none. */
    private static final List<String> TIER_NAMES = List.of("haven", "frontier", "ascent", "summit", "pinnacle", "owner", "off");

    private SpawnAnchorCommand() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .then(Commands.literal("anchor")
                                .requires(s -> s.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(ctx -> add(ctx.getSource(), StringArgumentType.getString(ctx, "name"), 2))
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, 8))
                                                        .executes(ctx -> add(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                IntegerArgumentType.getInteger(ctx, "radius"))))))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(allNames(ctx.getSource()), builder))
                                                .executes(ctx -> remove(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))
                                .then(Commands.literal("list")
                                        .executes(ctx -> list(ctx.getSource())))
                                .then(Commands.literal("tier")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(allNames(ctx.getSource()), builder))
                                                .then(Commands.argument("tier", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(TIER_NAMES, builder))
                                                        .executes(ctx -> setTier(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "name"),
                                                                StringArgumentType.getString(ctx, "tier"))))))
                                .then(Commands.literal("tp")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(allNames(ctx.getSource()), builder))
                                                .executes(ctx -> tp(ctx.getSource(), StringArgumentType.getString(ctx, "name")))))));
    }

    private static int add(CommandSourceStack source, String name, int radius) {
        if (!ServerConfig.spawnAnchorsEnabled()) {
            source.sendFailure(Component.literal("Spawn anchors are disabled in server config (spawnAnchorsEnabled = false)."));
            return 0;
        }
        int maxR = ServerConfig.spawnAnchorsMaxRadius();
        if (radius > maxR) {
            source.sendFailure(Component.literal("Radius " + radius + " exceeds max allowed (" + maxR + ")."));
            return 0;
        }

        ServerLevel level = source.getLevel();
        Vec3 pos = source.getPosition();
        ServerPlayer placer = source.getPlayer();
        SpawnAnchorData.Anchor anchor = new SpawnAnchorData.Anchor(name, pos.x, pos.y, pos.z, radius,
                placer == null ? null : placer.getUUID(),
                placer == null ? "" : placer.getGameProfile().getName(),
                AnchorTier.tierNameOf(placer), placer != null);
        SpawnAnchors.add(level, anchor);

        int side = radius * 2 + 1;
        source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                "Spawn anchor '%s' active in %s at (%.1f, %.1f, %.1f) — loading %dx%d chunks (%d).",
                name, level.dimension().location(), pos.x, pos.y, pos.z, side, side, side * side)), true);
        return 1;
    }

    /** {@code /foxstweaks anchor tier <name> <haven|frontier|ascent|summit|pinnacle|owner|off>}. */
    private static int setTier(CommandSourceStack source, String name, String tierName) {
        if (!AnchorTier.loaded()) {
            source.sendFailure(Component.literal("Apotheosis is not installed, so anchors have no world tier to carry."));
            return 0;
        }

        boolean off = tierName.equalsIgnoreCase("off");
        boolean followOwner = tierName.equalsIgnoreCase("owner");
        String tier = off || followOwner ? "" : AnchorTier.normaliseTier(tierName);
        if (!off && !followOwner && tier.isEmpty()) {
            source.sendFailure(Component.literal("Unknown tier '" + tierName + "'. Use one of: " + String.join(", ", TIER_NAMES)));
            return 0;
        }

        for (ServerLevel level : source.getServer().getAllLevels()) {
            SpawnAnchorData.Anchor current = SpawnAnchorData.get(level).get(name);
            if (current == null)
                continue;

            String newTier = tier;
            if (followOwner) {
                ServerPlayer owner = current.owner() == null ? null
                        : source.getServer().getPlayerList().getPlayer(current.owner());
                newTier = AnchorTier.tierNameOf(owner);
                if (newTier.isEmpty()) {
                    newTier = current.tier();
                    if (newTier.isEmpty()) {
                        source.sendFailure(Component.literal("Anchor '" + name + "' has no owner online and no tier to keep; set a tier by name instead."));
                        return 0;
                    }
                }
            }

            SpawnAnchorData.Anchor updated = new SpawnAnchorData.Anchor(current.name(), current.x(), current.y(),
                    current.z(), current.chunkRadius(), current.owner(), current.ownerName(), newTier,
                    followOwner && !current.ownerName().isEmpty());
            SpawnAnchors.add(level, updated);

            source.sendSuccess(() -> Component.literal(off
                    ? "Spawn anchor '" + name + "' no longer carries a world tier."
                    : "Spawn anchor '" + name + "' now carries the " + updated.tier() + " world tier"
                    + (updated.followOwner() ? " and follows " + updated.ownerName() + "." : " (fixed).")), true);
            return 1;
        }

        source.sendFailure(Component.literal("No spawn anchor named '" + name + "'."));
        return 0;
    }

    private static int remove(CommandSourceStack source, String name) {
        for (ServerLevel level : source.getServer().getAllLevels()) {
            SpawnAnchorData.Anchor removed = SpawnAnchors.remove(level, name);
            if (removed != null) {
                source.sendSuccess(() -> Component.literal(
                        "Removed spawn anchor '" + name + "' from " + level.dimension().location() + "."), true);
                return 1;
            }
        }
        source.sendFailure(Component.literal("No spawn anchor named '" + name + "'."));
        return 0;
    }

    private static int list(CommandSourceStack source) {
        int count = 0;
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (SpawnAnchorData.Anchor a : SpawnAnchors.list(level)) {
                int side = a.chunkRadius() * 2 + 1;
                String tier = a.carriesTier()
                        ? a.tier() + (a.followOwner() && !a.ownerName().isEmpty() ? " (follows " + a.ownerName() + ")" : " (fixed)")
                        : "no tier";
                source.sendSuccess(() -> Component.literal(String.format(Locale.ROOT,
                        "- %s [%s] (%.1f, %.1f, %.1f) %dx%d chunks, %s",
                        a.name(), level.dimension().location(), a.x(), a.y(), a.z(), side, side, tier)), false);
                count++;
            }
        }
        if (count == 0) {
            source.sendSuccess(() -> Component.literal("No active spawn anchors."), false);
        }
        return count;
    }

    private static int tp(CommandSourceStack source, String name) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can teleport to an anchor."));
            return 0;
        }
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (SpawnAnchorData.Anchor a : SpawnAnchors.list(level)) {
                if (a.name().equals(name)) {
                    player.teleportTo(level, a.x(), a.y(), a.z(), player.getYRot(), player.getXRot());
                    source.sendSuccess(() -> Component.literal("Teleported to spawn anchor '" + name + "'."), false);
                    return 1;
                }
            }
        }
        source.sendFailure(Component.literal("No spawn anchor named '" + name + "'."));
        return 0;
    }

    private static List<String> allNames(CommandSourceStack source) {
        List<String> names = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (SpawnAnchorData.Anchor a : SpawnAnchors.list(level)) {
                names.add(a.name());
            }
        }
        return names;
    }
}
