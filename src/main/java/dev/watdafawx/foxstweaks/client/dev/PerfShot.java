package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.perf.ClientPerfTracker;
import dev.watdafawx.foxstweaks.perf.PerfOverlayRenderer;
import dev.watdafawx.foxstweaks.perf.PerfSnapshotPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * Dev-only check + screenshots of Option A (Hero + Sparkline &amp; Gauges) flanking a crowded
 * 28-player Tab list (scale 2 &amp; scale 3), plus solo Tab and pinned mini-HUD.
 * Run with {@code build.bat runPerfShot} (on rig).
 */
public final class PerfShot {
    private static final Logger LOG = LogUtils.getLogger();

    private static final String[] FAKE_NAMES = {
            "Alex_Builds", "BlazeRunner", "CopperFox", "DeepslateDan", "EnderMina",
            "FrostByte", "GildedOak", "HopperKing", "IronWarden", "JadeCrafter",
            "KoiPond", "LapisLazuli", "MossyCobble", "NetherNomad", "ObsidianOwl",
            "PrismarineP", "QuartzQueen", "RedstoneRay", "SculkScout", "TuffTinker",
            "UmberWolf", "VoidWalker", "WitherWisp", "XenoMiner", "YewArcher",
            "ZephyrSky", "AetherKite"
    };

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
        NeoForge.EVENT_BUS.addListener(PerfShot::onTick);
        scenarios();
    }

    private static void scenarios() {
        // Wait 60 ticks (3s) so server + client samplers accumulate ticks, frames, CPU and GC data.
        add(60, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable t) {
                log("could not resize window: " + t);
            }
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            ClientPerfTracker.forceTabForTest = true;
        });

        add(30, () -> {
            PerfSnapshotPayload srv = ClientPerfTracker.serverSnapshot();
            ClientPerfTracker.ClientStats cli = ClientPerfTracker.clientStats();

            check("server snapshot received", srv != null, "snapshot null");
            if (srv != null) {
                check("server TPS > 0", srv.tps()[0] > 0f && srv.tps()[0] <= 20.01f, "tps=" + srv.tps()[0]);
                check("server sparkline populated", srv.recentTicksMs().length >= 10, "len=" + srv.recentTicksMs().length);
                check("server process CPU >= 0", srv.cpuProcess()[0] >= 0f, "cpuProc=" + srv.cpuProcess()[0]);
                check("server heap > 0", srv.heapUsedMb() > 0 && srv.heapMaxMb() >= srv.heapUsedMb(),
                        srv.heapUsedMb() + "/" + srv.heapMaxMb());
                check("server chunks > 0", srv.loadedChunks() > 0, "chunks=" + srv.loadedChunks());
                check("server dims non-empty", !srv.dims().isEmpty(), "dims empty");
            }
            check("client FPS > 0", cli.fps() > 0, "fps=" + cli.fps());
            check("client frame sparkline populated", ClientPerfTracker.recentFrameTimesMs(32).length >= 10, "too few frames");
        });

        add(4, () -> shot("perf-1-tab-solo-scale2.png"));

        // Simulate a crowded 28-player SMP server in vanilla Tab list (2 columns + header + footer)
        add(6, () -> {
            Minecraft mc = Minecraft.getInstance();
            var conn = mc.getConnection();
            if (conn != null) {
                var actions = EnumSet.of(
                        ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
                        ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);
                RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
                        Unpooled.buffer(), mc.level.registryAccess(), ConnectionType.NEOFORGE);
                buf.writeEnumSet(actions, ClientboundPlayerInfoUpdatePacket.Action.class);
                buf.writeVarInt(FAKE_NAMES.length);
                for (int i = 0; i < FAKE_NAMES.length; i++) {
                    UUID id = UUID.nameUUIDFromBytes(("perf-fake-" + i).getBytes());
                    int ping = 14 + (i * 11) % 140;
                    buf.writeUUID(id);
                    // ADD_PLAYER
                    buf.writeUtf(FAKE_NAMES[i], 16);
                    buf.writeVarInt(0);
                    // UPDATE_GAME_MODE
                    buf.writeVarInt(GameType.SURVIVAL.getId());
                    // UPDATE_LISTED
                    buf.writeBoolean(true);
                    // UPDATE_LATENCY
                    buf.writeVarInt(ping);
                }
                conn.handlePlayerInfoUpdate(ClientboundPlayerInfoUpdatePacket.STREAM_CODEC.decode(buf));
            }
            mc.gui.getTabList().setHeader(Component.literal("Fox's SMP Server  (28 / 50 online)"));
            mc.gui.getTabList().setFooter(Component.literal("play.foxsmp.net"));
            mc.options.keyPlayerList.setDown(true);

            // Augment the snapshot with realistic big-pack dimensions so the screenshot shows how
            // top active dims + "+N idle" read on a heavy modpack.
            PerfSnapshotPayload real = ClientPerfTracker.serverSnapshot();
            if (real != null) {
                var moddedDims = List.of(
                        new PerfSnapshotPayload.DimStat("overworld", Math.max(real.dims().isEmpty() ? 9.4f : real.dims().get(0).mspt(), 12.4f)),
                        new PerfSnapshotPayload.DimStat("twilight_forest", 6.8f),
                        new PerfSnapshotPayload.DimStat("mining_dimension", 2.3f),
                        new PerfSnapshotPayload.DimStat("nether", 0.9f));
                ClientPerfTracker.onSnapshot(new PerfSnapshotPayload(
                        real.tps(), real.mspt10s(), real.mspt1m(), real.cpuSystem(), real.cpuProcess(),
                        real.heapUsedMb(), real.heapMaxMb(), real.gcCount1m(), real.gcTimeMs1m(),
                        real.loadedChunks(), real.loadedEntities(), 16, moddedDims, real.recentTicksMs()));
            }
        });

        add(10, () -> {
            check("vanilla Tab rendered", PerfOverlayRenderer.lastVanillaTabBottomY > 0,
                    "lastVanillaTabBottomY=" + PerfOverlayRenderer.lastVanillaTabBottomY);
            check("cards flanked crowded Tab at scale 2", PerfOverlayRenderer.lastFlankedVanillaTab,
                    "fell back below instead of flanking");
            log("crowded Tab scale 2: bottomY=" + PerfOverlayRenderer.lastVanillaTabBottomY
                    + " flanked=" + PerfOverlayRenderer.lastFlankedVanillaTab);
            shot("perf-2-crowded-28p-scale2.png");
        });

        // Scale 3 with 28 players (tests tight flanking or graceful fallback)
        add(6, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });
        add(6, () -> {
            log("crowded Tab scale 3: bottomY=" + PerfOverlayRenderer.lastVanillaTabBottomY
                    + " flanked=" + PerfOverlayRenderer.lastFlankedVanillaTab);
            shot("perf-3-crowded-28p-scale3.png");
        });

        // Pinned placement: the cards must leave the player list alone and sit in the corner.
        add(6, () -> {
            dev.watdafawx.foxstweaks.Config.setPerfOverlayPosition("bottomRight", 0, 0);
            Minecraft mc = Minecraft.getInstance();
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        });
        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            var window = mc.getWindow();
            int w = window.getGuiScaledWidth();
            int h = window.getGuiScaledHeight();
            int x = PerfOverlayRenderer.lastBlockX;
            int y = PerfOverlayRenderer.lastBlockY;
            check("pinned placement ignores the player list", !PerfOverlayRenderer.lastFlankedVanillaTab, "still flanked");
            // Compare the block's centre, not its top: a stacked pair is taller than half the GUI at scale 2.
            int centreX = x + PerfOverlayRenderer.lastBlockW / 2;
            int centreY = y + PerfOverlayRenderer.lastBlockH / 2;
            check("pinned placement sits in the bottom-right", centreX > w / 2 && centreY > h / 2,
                    "centre=" + centreX + "," + centreY + " screen=" + w + "x" + h);
            log("pinned bottomRight at " + x + "," + y + " on " + w + "x" + h
                    + " block " + PerfOverlayRenderer.lastBlockW + "x" + PerfOverlayRenderer.lastBlockH);
            shot("perf-5-placement-bottomright.png");
        });

        // The key path, minus the keypress: PerfOverlayMover.cyclePlacement is exactly what it calls.
        add(6, () -> {
            String before = dev.watdafawx.foxstweaks.Config.perfOverlayPlacement();
            dev.watdafawx.foxstweaks.client.PerfOverlayMover.cyclePlacement();
            String after = dev.watdafawx.foxstweaks.Config.perfOverlayPlacement();
            check("cycling moves the cards to the next preset", !before.equals(after), before + " -> " + after);
            log("placement " + before + " -> " + after);
        });
        add(10, () -> shot("perf-6-placement-cycled.png"));
        add(4, () -> dev.watdafawx.foxstweaks.Config.setPerfOverlayPosition("flank", 0, 0));

        // Release Tab and test pinned mini-HUD
        add(6, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.keyPlayerList.setDown(false);
            ClientPerfTracker.forceTabForTest = false;
            ClientPerfTracker.setMiniHudPinned(true);
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
        });
        add(10, () -> {
            check("mini-HUD visible when pinned", ClientPerfTracker.isMiniHudVisible(), "not visible");
            shot("perf-4-minihud-scale2.png");
        });

        add(6, () -> {
            ClientPerfTracker.setMiniHudPinned(false);
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-perf-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    @SuppressWarnings("unused")
    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("PerfCheck {}", line);
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
            LOG.error("PerfCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }

        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-perfshot";
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

    private PerfShot() {
    }
}
