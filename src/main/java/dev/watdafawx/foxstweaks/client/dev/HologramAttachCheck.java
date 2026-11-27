package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramRenderer;
import dev.watdafawx.foxstweaks.hologram.HologramAttach;
import dev.watdafawx.foxstweaks.hologram.HologramData;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.AABB;
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
 * Dev-only end-to-end check of player-attached holograms: attach command, 1st-person hide,
 * 3rd-person render + movement tracking, follow modes (body/head), cross-dimension travel
 * (Overworld -> Nether), and detach back into the world.
 *
 * <p>Run with {@code build.bat runHologramAttachCheck} on rig. Report:
 * {@code run-hologram-attach/foxstweaks-hologram-attach-check.txt}.
 */
public final class HologramAttachCheck {
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
    private static String hologramId = "";
    private static Vec3 beforeMoveCenter = Vec3.ZERO;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramAttachCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        // 1. Create a beacon hologram and attach it to the player above their head.
        add(30, () -> {
            runCmd("foxstweaks hologram add minecraft:beacon");
        });

        add(10, () -> {
            hologramId = onServer(p -> {
                var list = Holograms.list(p.serverLevel());
                if (list.isEmpty())
                    return "";
                HologramSpec s = list.get(0);
                // Put it right above head (y=2.2) so it's clearly visible in 3rd person.
                HologramSpec raised = new HologramSpec(s.id(), s.pos(), 0.0F, 2.2F, 0.0F,
                        0.0F, 0.0F, 0.0F, 0.85F, true, s.renderDistance(), s.owner(), s.ownerName(),
                        0.0F, 0.0F, s.appearStyle(), s.groups(), s.layers(), s.timeline(), null);
                Holograms.save(p.serverLevel(), raised);
                return s.id();
            });
            check("created hologram", !hologramId.isEmpty(), "id=" + hologramId);
            runCmd("foxstweaks hologram attach " + hologramId);
        });

        // 2. Verify server storage moved to attached store + client synced + hidden in 1st person.
        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.FIRST_PERSON);
            Boolean serverOk = onServer(p -> {
                boolean inAttached = HologramData.attached(p.server).get(hologramId) != null;
                boolean inDim = HologramData.get(p.serverLevel()).get(hologramId) != null;
                return inAttached && !inDim;
            });
            check("moved from dimension store to attached store", Boolean.TRUE.equals(serverOk), "ok=" + serverOk);
            HologramSpec clientSpec = clientSpec(hologramId);
            check("client synced attached spec", clientSpec != null && clientSpec.isAttached()
                    && clientSpec.attach().follow() == HologramAttach.Follow.BODY_YAW
                    && clientSpec.attach().hideFirstPerson(),
                    String.valueOf(clientSpec == null ? null : clientSpec.attach()));
        });

        add(6, () -> {
            check("hidden in 1st person when hideFirstPerson=true", HologramRenderer.lastDrawnCount() == 0,
                    "drawn=" + HologramRenderer.lastDrawnCount());
            Minecraft.getInstance().options.setCameraType(CameraType.THIRD_PERSON_BACK);
        });

        // 3. Verify visible in 3rd person, screenshot, then teleport +20 on X and verify tracking.
        add(8, () -> {
            check("drawn in 3rd person", HologramRenderer.lastDrawnCount() >= 1,
                    "drawn=" + HologramRenderer.lastDrawnCount());
            shoot("attach-overworld-3p.png");
            HologramSpec cs = clientSpec(hologramId);
            AABB box = cs == null ? null : cs.box(Minecraft.getInstance().level, 1.0F);
            beforeMoveCenter = box == null ? Vec3.ZERO : box.getCenter();
            check("has live bounding box in overworld", box != null, "box=" + box);

            onServer(p -> {
                p.teleportTo(p.getX() + 20.0, p.getY(), p.getZ());
                return null;
            });
        });

        add(8, () -> {
            HologramSpec cs = clientSpec(hologramId);
            AABB box = cs == null ? null : cs.box(Minecraft.getInstance().level, 1.0F);
            double dx = box == null ? 0.0 : box.getCenter().x - beforeMoveCenter.x;
            check("attached hologram moves +20 blocks with player", Math.abs(dx - 20.0) < 0.75,
                    "dx=" + dx);

            // Switch to HEAD_LOOK via command and verify follow mode updates.
            String name = Minecraft.getInstance().player.getGameProfile().getName();
            runCmd("foxstweaks hologram attach " + hologramId + " " + name + " head_look");
        });

        // 4. Verify HEAD_LOOK mode + cross-dimension travel to Nether.
        add(8, () -> {
            HologramSpec cs = clientSpec(hologramId);
            check("follow mode switched to HEAD_LOOK",
                    cs != null && cs.isAttached() && cs.attach().follow() == HologramAttach.Follow.HEAD_LOOK,
                    String.valueOf(cs == null ? null : cs.attach()));

            onServer(p -> {
                ServerLevel nether = p.server.getLevel(Level.NETHER);
                if (nether != null)
                    p.teleportTo(nether, 0.5, 70.0, 0.5, 0.0F, 0.0F);
                return null;
            });
        });

        add(25, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            boolean inNether = mc.level != null && mc.level.dimension().equals(Level.NETHER);
            HologramSpec cs = clientSpec(hologramId);
            check("arrived in Nether", inNether, String.valueOf(mc.level == null ? null : mc.level.dimension()));
            check("attached hologram followed to Nether", cs != null && cs.isAttached(), "spec=" + cs);
        });

        add(8, () -> {
            check("attached hologram draws in Nether", HologramRenderer.lastDrawnCount() >= 1,
                    "drawn=" + HologramRenderer.lastDrawnCount());
            shoot("attach-nether-3p.png");

            // 5. Detach in Nether -> drops back into Nether's dimension store.
            runCmd("foxstweaks hologram detach " + hologramId);
        });

        add(10, () -> {
            Boolean detachedOk = onServer(p -> {
                boolean inAttached = HologramData.attached(p.server).get(hologramId) != null;
                HologramSpec dimSpec = HologramData.get(p.serverLevel()).get(hologramId);
                return !inAttached && dimSpec != null && !dimSpec.isAttached();
            });
            check("detach moves hologram back to current dimension store", Boolean.TRUE.equals(detachedOk),
                    "ok=" + detachedOk);
            HologramSpec cs = clientSpec(hologramId);
            check("client sees detached hologram", cs != null && !cs.isAttached(),
                    String.valueOf(cs == null ? null : cs.attach()));
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-attach-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static HologramSpec clientSpec(String id) {
        for (HologramSpec s : HologramClient.specs()) {
            if (s.id().equals(id))
                return s;
        }
        return null;
    }

    private static void runCmd(String command) {
        var mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null)
            mc.player.connection.sendCommand(command);
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        if (server == null || mc.player == null)
            return null;
        var uuid = mc.player.getUUID();
        return server.submit(() -> {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            return p == null ? null : fn.apply(p);
        }).join();
    }

    private static void shoot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {
        });
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static void log(String line) {
        LOG.info("HologramAttachCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post event) {
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
            LOG.error("HologramAttachCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-attachcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramAttachCheck() {
    }
}
