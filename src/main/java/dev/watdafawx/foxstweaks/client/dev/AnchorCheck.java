package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dev-only end-to-end verification of virtual spawn anchors ({@code /foxstweaks anchor}):
 * places a Zombie spawner + anchor at (0, -59, 0), teleports the real player 600 blocks away,
 * verifies {@code hasNearbyAlivePlayer}, spawner mob spawning, despawn prevention, and clean
 * removal. Run with {@code build.bat runAnchorCheck} (on rig).
 */
public final class AnchorCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos SPAWNER_POS = new BlockPos(0, -59, 0);
    private static final AABB FARM_BOX = new AABB(-16, -64, -16, 16, -40, 16);

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(AnchorCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        add(40, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            // Place a dark box or night time so zombies spawn and don't burn
            level.setDayTime(18000);
            p.server.setDifficulty(Difficulty.NORMAL, true);

            // Place spawner at (0, -59, 0)
            level.setBlockAndUpdate(SPAWNER_POS, Blocks.SPAWNER.defaultBlockState());
            if (level.getBlockEntity(SPAWNER_POS) instanceof SpawnerBlockEntity sbe) {
                sbe.setEntityId(EntityType.ZOMBIE, level.random);
                sbe.setChanged();
            }

            // Place anchor at (0, -59, 0) via real command
            p.teleportTo(level, 0.5, -59.0, 0.5, 0f, 0f);
            p.server.getCommands().performPrefixedCommand(
                    p.createCommandSourceStack().withPermission(4),
                    "foxstweaks anchor add farm 2");

            // Teleport player 600 blocks away (well past 128-block despawn & 16-block spawner range)
            p.teleportTo(level, 600.5, -59.0, 600.5, 0f, 0f);
            return null;
        }));

        add(20, () -> {
            boolean nearPlayer = onServer(p -> p.serverLevel().hasNearbyAlivePlayer(0.5, -59.5, 0.5, 16.0));
            boolean chunkLoaded = onServer(p -> p.serverLevel().isLoaded(SPAWNER_POS));
            check("hasNearbyAlivePlayer true at anchor while player 600 blocks away", nearPlayer, "false");
            check("anchor chunk remains loaded while player 600 blocks away", chunkLoaded, "unloaded");
        });

        // Wait 260 ticks (~13s, vanilla spawner initial delay is 20..200 ticks) for real zombies to spawn
        add(260, () -> {
            int zombies = onServer(p -> p.serverLevel().getEntitiesOfClass(Zombie.class, FARM_BOX).size());
            check("spawner spawned zombies at anchor while player 600 blocks away", zombies > 0, "zombies=" + zombies);
            log("zombies spawned at anchor: " + zombies);
        });

        // Wait another 60 ticks (3s) — without anchor despawn protection, mobs >128 blocks from real player despawn immediately!
        add(60, () -> {
            int zombies = onServer(p -> p.serverLevel().getEntitiesOfClass(Zombie.class, FARM_BOX).size());
            check("zombies did NOT despawn while anchor active", zombies > 0, "zombies=" + zombies);
            log("zombies still alive after 3s: " + zombies);

            // Remove anchor via command
            onServer(p -> {
                p.server.getCommands().performPrefixedCommand(
                        p.createCommandSourceStack().withPermission(4),
                        "foxstweaks anchor remove farm");
                return null;
            });
        });

        add(10, () -> {
            boolean nearAfterRemove = onServer(p -> p.serverLevel().hasNearbyAlivePlayer(0.5, -59.5, 0.5, 16.0));
            check("hasNearbyAlivePlayer false after anchor removed", !nearAfterRemove, "still true");

            // Now test the craftable Spawn Anchor dummy + GUI screenshot!
            int dummyId = onServer(p -> {
                ServerLevel level = p.serverLevel();
                level.setDayTime(6000);
                p.teleportTo(level, 10.5, -60.0, 14.5, 180f, 10f);
                var dummy = EntityType.ARMOR_STAND.create(level);
                dummy.moveTo(10.5, -60.0, 10.5, 0f, 0f);
                dummy.setShowArms(true);
                dummy.setInvulnerable(true);
                dev.watdafawx.foxstweaks.anchor.SpawnDummyEvents.initDummy(level, dummy, "dummy_test", true, 2);
                level.addFreshEntity(dummy);
                return dummy.getId();
            });
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable ignored) {
            }
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
            dev.watdafawx.foxstweaks.client.SpawnAnchorScreen.open(
                    new dev.watdafawx.foxstweaks.anchor.OpenAnchorGuiPayload(
                            dummyId, true, 2, 4, 10, -60, 10, "minecraft:overworld"));
        });

        add(10, () -> {
            var mc = Minecraft.getInstance();
            net.minecraft.client.Screenshot.grab(mc.gameDirectory, "anchor-gui-5x5.png", mc.getMainRenderTarget(), msg -> {
            });
            check("SpawnAnchorScreen opened", mc.screen instanceof dev.watdafawx.foxstweaks.client.SpawnAnchorScreen, String.valueOf(mc.screen));
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-anchor-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("AnchorCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size())
            return;

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("AnchorCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-anchorcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.NORMAL, true, new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private AnchorCheck() {
    }
}
