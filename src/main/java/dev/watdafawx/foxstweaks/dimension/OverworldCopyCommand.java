package dev.watdafawx.foxstweaks.dimension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

/**
 * {@code /foxstweaks portal ...}: change the overworld-copy portal frame on a running server, and queue a
 * regeneration of the copy.
 *
 * <p>Regeneration cannot happen live: the dimension's region files stay open while the server runs, so
 * deleting them under it corrupts the world or fails outright on Windows. The command writes a marker file
 * in the world folder instead, and the dimension's folder is deleted at the next start, before any level
 * loads. The copy then generates again from the world seed (same terrain, builds gone).
 */
public final class OverworldCopyCommand {
    private static final String MARKER = "foxstweaks_regenerate_overworld_copy";

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        var frameId = Commands.argument("block", ResourceLocationArgument.id())
                .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder));

        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .then(Commands.literal("portal")
                                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .then(Commands.literal("frame")
                                        .executes(ctx -> showFrame(ctx.getSource()))
                                        .then(Commands.literal("set")
                                                .then(frameId.executes(ctx -> setFrame(ctx, false))))
                                        .then(Commands.literal("add")
                                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), builder))
                                                        .executes(ctx -> setFrame(ctx, true))))
                                        .then(Commands.literal("remove")
                                                .then(Commands.argument("block", ResourceLocationArgument.id())
                                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(currentFrame(), builder))
                                                        .executes(ctx -> removeFrame(ctx))))
                                        .then(Commands.literal("reset")
                                                .executes(ctx -> save(ctx.getSource(), List.of(ServerConfig.DEFAULT_FRAME)))))
                                .then(Commands.literal("regenerate")
                                        .requires(source -> source.hasPermission(Commands.LEVEL_OWNERS))
                                        .executes(ctx -> queueRegenerate(ctx.getSource()))
                                        .then(Commands.literal("cancel")
                                                .executes(ctx -> cancelRegenerate(ctx.getSource()))))));
    }

    // ---------------------------------------------------------------------------------------------
    // Frame

    private static List<String> currentFrame() {
        return ServerConfig.loaded() ? new ArrayList<>(ServerConfig.OVERWORLD_COPY_FRAME.get()) : new ArrayList<>();
    }

    private static int showFrame(CommandSourceStack source) {
        List<String> frame = currentFrame();

        source.sendSuccess(() -> Component.literal("Overworld copy portal frame: "
                + (frame.isEmpty() ? "(none, falls back to stone bricks)" : String.join(", ", frame))), false);
        return frame.size();
    }

    private static int setFrame(CommandContext<CommandSourceStack> ctx, boolean add) {
        String entry = ResourceLocationArgument.getId(ctx, "block").toString();
        String problem = OverworldCopy.frameProblem(entry);

        if (problem != null) {
            ctx.getSource().sendFailure(Component.literal("Not a usable frame: " + problem));
            return 0;
        }

        List<String> frame = add ? currentFrame() : new ArrayList<>();

        if (!frame.contains(entry)) {
            frame.add(entry);
        }

        return save(ctx.getSource(), frame);
    }

    private static int removeFrame(CommandContext<CommandSourceStack> ctx) {
        String entry = ResourceLocationArgument.getId(ctx, "block").toString();
        List<String> frame = currentFrame();

        if (!frame.remove(entry)) {
            ctx.getSource().sendFailure(Component.literal(entry + " is not in the frame list."));
            return 0;
        }

        return save(ctx.getSource(), frame);
    }

    private static int save(CommandSourceStack source, List<String> frame) {
        if (!ServerConfig.loaded()) {
            source.sendFailure(Component.literal("The server config is not loaded yet."));
            return 0;
        }

        ServerConfig.OVERWORLD_COPY_FRAME.set(List.copyOf(frame));
        ServerConfig.SPEC.save();

        source.sendSuccess(() -> Component.literal("Overworld copy portal frame is now: "
                + (frame.isEmpty() ? "(none, falls back to stone bricks)" : String.join(", ", frame))
                + ". Portals built from the old frame stop working; light new ones."), true);
        return frame.size();
    }

    // ---------------------------------------------------------------------------------------------
    // Regenerate

    private static int queueRegenerate(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel copy = server.getLevel(OverworldCopy.COPY);

        if (copy != null && !copy.players().isEmpty()) {
            String names = copy.players().stream().map(p -> p.getGameProfile().getName()).collect(Collectors.joining(", "));

            source.sendFailure(Component.literal("Players are in the overworld copy: " + names + ". Get them out first."));
            return 0;
        }

        try {
            Files.writeString(marker(server), "Delete the overworld copy dimension folder on the next start.\n");
        } catch (IOException e) {
            LogUtils.getLogger().error("Could not write {}", marker(server), e);
            source.sendFailure(Component.literal("Could not write the marker file: " + e.getMessage()));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Overworld copy will be regenerated on the next server start (same seed: "
                + "terrain comes back, everything built or changed there is gone). Undo before then with "
                + "/foxstweaks portal regenerate cancel."), true);
        return 1;
    }

    private static int cancelRegenerate(CommandSourceStack source) {
        try {
            boolean had = Files.deleteIfExists(marker(source.getServer()));

            source.sendSuccess(() -> Component.literal(had ? "Regeneration cancelled." : "No regeneration was queued."), true);
            return had ? 1 : 0;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not remove the marker file: " + e.getMessage()));
            return 0;
        }
    }

    private static Path marker(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(MARKER);
    }

    /** Runs before any level is created, so nothing holds the region files and no player can be inside. */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        MinecraftServer server = event.getServer();
        Path marker = marker(server);

        if (!Files.exists(marker)) {
            return;
        }

        Path folder = DimensionType.getStorageFolder(OverworldCopy.COPY, server.getWorldPath(LevelResource.ROOT));

        try {
            if (Files.isDirectory(folder)) {
                try (Stream<Path> walk = Files.walk(folder)) {
                    for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }

                LogUtils.getLogger().info("Overworld copy regenerated: deleted {}", folder);
            }

            Files.delete(marker);
        } catch (IOException e) {
            // Leave the marker so the next start tries again.
            LogUtils.getLogger().error("Could not delete the overworld copy at {}; will retry next start", folder, e);
        }
    }

    private OverworldCopyCommand() {
    }
}
