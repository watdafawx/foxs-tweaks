package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.perf.FpsActions;
import dev.watdafawx.foxstweaks.client.perf.FpsProfiler;
import dev.watdafawx.foxstweaks.client.perf.FpsReportScreen;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
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
 * Dev-only end-to-end check of the FPS hotspot tools (client/perf/Fps*): builds 39 chests and 10 armor stands in
 * front of the player, scans in third person (so the player is drawn), then checks that the player is not a
 * hotspot, that chests show up as one group, that shares/FPS numbers are sane, that the report screen opens and
 * that look / teleport / show / ignore work through the real client commands. Screenshots included.
 * Run with {@code gradlew runFpsCheck} (on rig). Report: {@code foxstweaks-fps-check.txt}.
 */
public final class FpsCheck {
    private static final Logger LOG = LogUtils.getLogger();

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static double yawBefore;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(FpsCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        add(60, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable t) {
                log("could not resize window: " + t);
            }
            mc.options.guiScale().set(2);
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            mc.resizeDisplay();

            // 3 rows of 13 chests (spaced so they never merge into doubles) + 10 armor stands, south of the player.
            onServer(p -> {
                var level = p.serverLevel();
                BlockPos base = p.blockPosition();
                for (int row = 0; row < 3; row++) {
                    for (int i = -6; i <= 6; i++) {
                        level.setBlock(base.offset(i * 2, 0, 6 + row * 3), Blocks.CHEST.defaultBlockState(), 3);
                    }
                }
                for (int i = 0; i < 10; i++) {
                    var stand = EntityType.ARMOR_STAND.create(level);
                    if (stand != null) {
                        stand.setPos(base.getX() + i * 2 - 9 + 0.5, base.getY(), base.getZ() + 16.5);
                        level.addFreshEntity(stand);
                    }
                }
                return null;
            });
        });

        add(40, () -> {
            FpsProfiler.start(3, false);
            check("scan started", FpsProfiler.sampling, "not sampling");
        });

        // 3 s scan = 60 ticks; the tick handler stops it and prints the chat summary.
        add(90, () -> {
            check("scan stopped itself", !FpsProfiler.sampling, "still sampling");
            check("frames counted", FpsProfiler.getFrameCount() > 10, "frames=" + FpsProfiler.getFrameCount());
            check("whole-frame time measured", FpsProfiler.frameMicros() > 0.0, "frameMicros=" + FpsProfiler.frameMicros());
            log(String.format("frames=%d avgFrame=%.2f ms player=%.0f us", FpsProfiler.getFrameCount(),
                    FpsProfiler.frameMicros() / 1000.0, FpsProfiler.playerMicros()));

            check("player cost reported separately", FpsProfiler.playerMicros() > 0.0,
                    "playerMicros=" + FpsProfiler.playerMicros() + " (third person should draw the player)");

            boolean playerListed = false;
            boolean originGhost = false;
            for (FpsProfiler.Hotspot h : FpsProfiler.getInstances()) {
                if (h.typeId().equals("minecraft:player"))
                    playerListed = true;
                if (h.isEntity() && h.fallbackBox() != null && h.fallbackBox().getCenter().lengthSqr() < 4.0)
                    originGhost = true;
            }
            check("player is not a hotspot", !playerListed, "minecraft:player is in the instance list");
            check("no ghost entity at the origin", !originGhost, "entity box near (0,0,0)");

            FpsProfiler.Group chests = null;
            for (FpsProfiler.Group g : FpsProfiler.getGroups()) {
                log(String.format("group %s x%d = %.0f us (%.1f%% of frame)", g.typeId(), g.count(), g.microsPerFrame(),
                        FpsProfiler.share(g.microsPerFrame()) * 100.0));
                if (g.typeId().equals("minecraft:chest"))
                    chests = g;
            }
            check("chests form one group", chests != null, "no minecraft:chest group");
            if (chests != null) {
                check("group counts the chests", chests.count() >= 30, "count=" + chests.count());
                double share = FpsProfiler.share(chests.microsPerFrame());
                check("chest share is a fraction of the frame", share > 0.0 && share < 1.0, "share=" + share);
                check("fps gain is positive", FpsProfiler.fpsGain(chests.microsPerFrame()) > 0.0,
                        "gain=" + FpsProfiler.fpsGain(chests.microsPerFrame()));
            }
            check("overlay stays up after the scan", FpsProfiler.isShowingOverlay(), "overlay hidden");
            check("top hotspots non-empty", !FpsProfiler.getHotspots().isEmpty(), "hotspots empty");
        });

        add(6, () -> shot("fps-1-world-overlay.png"));

        // Report screen, both tabs.
        add(4, FpsProfiler::openReport);
        add(8, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("report screen opened", mc.screen instanceof FpsReportScreen, "screen=" + mc.screen);
        });
        add(6, () -> shot("fps-2-report-by-type.png"));
        add(2, () -> {
            if (Minecraft.getInstance().screen instanceof FpsReportScreen s) {
                s.devSetTab(1);
                s.devSelect(0);
            }
        });
        add(6, () -> shot("fps-3-report-individual.png"));
        add(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });
        add(8, () -> shot("fps-4-report-scale3.png"));
        add(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            mc.setScreen(null);
        });

        // Show every chest, then a single one (tall beam).
        add(4, () -> FpsProfiler.focusType("minecraft:chest"));
        add(6, () -> {
            check("focus on a type lists its members", FpsProfiler.worldList().size() >= 30,
                    "size=" + FpsProfiler.worldList().size());
            shot("fps-5-show-type.png");
        });
        add(2, () -> {
            var inst = FpsProfiler.getInstances();
            if (!inst.isEmpty())
                FpsProfiler.focusItem(inst.get(0));
        });
        add(6, () -> {
            check("focus on one item lists just it", FpsProfiler.worldList().size() == 1,
                    "size=" + FpsProfiler.worldList().size());
            shot("fps-6-show-item-beam.png");
        });

        // Real client commands, the same path a chat click takes.
        add(2, () -> {
            command("foxstweaks fps show off");
        });
        add(4, () -> check("/foxstweaks fps show off clears the focus",
                FpsProfiler.getFocusItem() == null && FpsProfiler.getFocusType() == null, "focus still set"));

        add(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.player.setYRot(180.0f); // facing north, away from the chests
            yawBefore = mc.player.getYRot();
            command("foxstweaks fps look 1");
        });
        add(4, () -> {
            double yaw = Minecraft.getInstance().player.getYRot();
            check("/foxstweaks fps look turns the view toward the hotspot", Math.abs(yaw - yawBefore) > 20.0,
                    "yaw " + yawBefore + " -> " + yaw);
        });

        add(2, () -> {
            check("teleport allowed (cheats world)", FpsActions.canTeleport(), "hasPermissions(2) false");
            var inst = FpsProfiler.getInstances();
            if (!inst.isEmpty()) {
                // Walk away first so the teleport has something to do.
                onServer(p -> {
                    p.teleportTo(p.getX(), p.getY(), p.getZ() - 40.0);
                    return null;
                });
            }
        });
        add(10, () -> command("foxstweaks fps tp 1"));
        add(14, () -> {
            var inst = FpsProfiler.getInstances();
            Minecraft mc = Minecraft.getInstance();
            if (inst.isEmpty()) {
                check("teleport lands next to the hotspot", false, "no instances");
                return;
            }
            Vec3 c = FpsActions.centerOf(inst.get(0));
            double d = c == null ? -1 : mc.player.position().distanceTo(c);
            check("teleport lands next to the hotspot (1.5 to 8 blocks)", d > 1.5 && d < 8.0, "distance=" + d);
            shot("fps-7-after-teleport.png");
        });

        // Ignore a type: gone from every list.
        add(2, () -> FpsProfiler.ignoreType("minecraft:chest"));
        add(4, () -> {
            boolean still = false;
            for (FpsProfiler.Group g : FpsProfiler.getGroups())
                if (g.typeId().equals("minecraft:chest"))
                    still = true;
            check("ignored type leaves the group list", !still, "chest group still present");
        });

        add(2, () -> command("foxstweaks fps clear"));
        add(4, () -> {
            check("clear empties everything", FpsProfiler.getInstances().isEmpty() && FpsProfiler.getGroups().isEmpty()
                    && !FpsProfiler.isShowingOverlay(), "data left after clear");
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-fps-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static void command(String cmd) {
        Minecraft.getInstance().player.connection.sendUnsignedCommand(cmd);
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
        LOG.info("FpsCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {
        });
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
            LOG.error("FpsCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }

        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-fpscheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);

        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }

        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);

        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private FpsCheck() {
    }
}
