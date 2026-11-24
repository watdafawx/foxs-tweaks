package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramEntities;
import dev.watdafawx.foxstweaks.client.HologramRenderer;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Dev-only end-to-end check of holograms: creates one through the real command, opens the real editor,
 * cycles a real blockstate property, saves, asserts the server has the change, re-opens and deletes.
 * With AE2 in {@code run-hologram/mods} it also covers {@code ae2:controller}, which is the case the
 * feature was designed around (two enum properties, animated texture, no block entity renderer).
 *
 * <p>Run with {@code build.bat runHologramCheck} on rig. Report: {@code foxstweaks-hologram-check.txt}.
 */
public final class HologramCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final boolean AE2 = ModList.get().isLoaded("ae2");
    private static final int BENCH_COUNT = 48;
    private static final int BENCH_TICKS = 100;

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<Long> FRAMES = new ArrayList<>();
    private static final List<Long> PASSES = new ArrayList<>();
    private static final Map<String, Double> ROUNDS = new LinkedHashMap<>();
    private static final Map<String, Double> ROUND_PASS = new LinkedHashMap<>();
    private static final StringBuilder SUMMARY = new StringBuilder();
    private static boolean recording;
    private static long lastFrameNanos;
    private static int drawnMax;
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static String hologramId;
    private static String ae2Id;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramCheck::onTick);
        NeoForge.EVENT_BUS.addListener(HologramCheck::onFrame);
        scenarios();
    }

    // ---------------------------------------------------------------- frame timing

    /**
     * Frame time, measured off {@code AFTER_LEVEL} (once per frame, after the world pass) rather than
     * off tick time: ticks are a fixed 50 ms and say nothing about rendering cost.
     */
    private static void onFrame(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL)
            return;

        long now = System.nanoTime();
        if (recording) {
            if (lastFrameNanos != 0L) {
                FRAMES.add(now - lastFrameNanos);
                PASSES.add(HologramRenderer.lastPassNanos());
                drawnMax = Math.max(drawnMax, HologramRenderer.lastDrawnCount());
            }
            lastFrameNanos = now;
        }
    }

    private static void recordStart() {
        FRAMES.clear();
        PASSES.clear();
        drawnMax = 0;
        lastFrameNanos = 0L;
        recording = true;
    }

    private static void reportRound(String label) {
        recording = false;
        if (FRAMES.size() < 10) {
            log("perf " + label + ": only " + FRAMES.size() + " frames recorded");
            ROUNDS.put(label, 99.0);
            ROUND_PASS.put(label, 9999.0);
            return;
        }

        List<Double> millis = new ArrayList<>(FRAMES.size());
        for (long nanos : FRAMES)
            millis.add(nanos / 1.0E6);
        millis.sort(Double::compare);

        double total = 0.0;
        for (double value : millis)
            total += value;
        double average = total / millis.size();
        double p95 = millis.get(Math.min(millis.size() - 1, (int) (millis.size() * 0.95)));

        double passTotal = 0.0;
        for (long nanos : PASSES)
            passTotal += nanos / 1.0E3;
        double passAverage = passTotal / Math.max(1, PASSES.size());

        ROUNDS.put(label, average);
        ROUND_PASS.put(label, passAverage);
        String line = String.format(java.util.Locale.ROOT,
                "perf %s: hologram pass %.1f us/frame, frame %.2f ms (p95 %.2f), %d drawn/frame, %d frames",
                label, passAverage, average, p95, drawnMax, millis.size());
        log(line);
        SUMMARY.append(String.format(java.util.Locale.ROOT, "%s %.0f us (%d drawn); ", label, passAverage, drawnMax));
    }

    private static double round(String label) {
        return ROUNDS.getOrDefault(label, 99.0);
    }

    private static double passMicros(String label) {
        return ROUND_PASS.getOrDefault(label, 9999.0);
    }

    private static void scenarios() {
        add(40, () -> onServer(player -> {
            player.serverLevel().setDayTime(6000);
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            return null;
        }));

        // ---- vanilla block: does the command create one? ----
        add(20, () -> {
            // Dispatcher#execute, not performPrefixedCommand: only this one reports the result and
            // throws on a parse failure, which is what a broken command needs to show up as.
            String[] outcome = { "" };
            onServer(player -> {
                try {
                    int code = player.server.getCommands().getDispatcher()
                            .execute("foxstweaks hologram add minecraft:oak_stairs",
                                    player.createCommandSourceStack().withPermission(4));
                    outcome[0] = "result=" + code;
                } catch (Exception e) {
                    outcome[0] = e.toString();
                }
                return null;
            });
            check("command accepted", outcome[0].startsWith("result="), outcome[0]);

            hologramId = onServer(player -> {
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("command created a hologram", !hologramId.isEmpty(), "id=" + hologramId);
        });

        // Separate step: the sync packet is queued, so it lands a tick or two after the command returns.
        add(10, () -> {
            int clientCount = HologramClient.specs().size();
            check("client received the sync", clientCount == 1, "clientCount=" + clientCount);
        });

        // ---- open the real editor through the real command ----
        add(6, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("editor opened", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (!(mc.screen instanceof HologramScreen screen))
                return;

            // ---- two panels, tabs, and every value takes the wheel ----
            screen.devTab(0);
            check("Look tab lists 6 controls", screen.devRowCount() == 6, "rows=" + screen.devRowCount());
            check("the middle of the screen stays free", screen.devFreeWidth() >= 90,
                    "free=" + screen.devFreeWidth() + "px, gridCols=" + screen.devGridCols());

            screen.devStepIndex(1); // Step x2
            float before = screen.current().scale();
            screen.devScrollRow(0, 1);
            float after = screen.current().scale();
            check("wheel steps Size by the row step times Step",
                    Math.abs((after - before) - 0.5F) < 1.0E-4F,
                    "scale " + before + " -> " + after + " (expected +0.5)");

            boolean anchoredBefore = HologramClient.previewAnchored();
            screen.devTab(4); // Share
            screen.devScrollRow(3, 1); // Preview in front
            check("Share tab toggles the in-front preview",
                    HologramClient.previewAnchored() != anchoredBefore,
                    "was " + anchoredBefore + ", now " + HologramClient.previewAnchored());

            List<Property<?>> properties = screen.currentProperties();
            check("oak_stairs lists 4 properties", properties.size() == 4, "properties=" + names(properties));

            int facing = indexOf(properties, "facing");
            check("facing property present", facing >= 0, "properties=" + names(properties));
            if (facing >= 0) {
                String beforeFacing = valueOf(screen.current(), "facing");
                screen.devCycleProperty(facing, 1);
                String afterFacing = valueOf(screen.current(), "facing");
                check("clicking a property row cycles its value", !beforeFacing.equals(afterFacing),
                        beforeFacing + " -> " + afterFacing);
            }

            screen.devSetScale(2.5F);
            screen.devSetSpin(45.0F);
            check("editor preview follows the edit immediately",
                    HologramClient.specs().stream().anyMatch(spec -> spec.id().equals(hologramId) && spec.scale() == 2.5F),
                    "preview=" + HologramClient.specs());
            screen.saveAndClose();
        });

        add(10, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            if (saved == null) {
                check("save reached the server", false, "hologram gone");
                return;
            }
            check("save kept the cycled property", !saved.props().isEmpty(), "props=" + saved.props());
            check("save kept the new scale", Math.abs(saved.scale() - 2.5F) < 0.001F, "scale=" + saved.scale());
            check("save kept the spin", Math.abs(saved.spin() - 45.0F) < 0.001F, "spin=" + saved.spin());
            log("saved spec: block=" + saved.block() + " props=" + saved.props() + " scale=" + saved.scale()
                    + " spin=" + saved.spin() + " range=" + saved.renderDistance());
        });

        // ---- screenshot of the editor over a live hologram ----
        add(6, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(20, () -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable ignored) {
            }
            mc.options.guiScale().set(3);
            mc.resizeDisplay();
        });

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("editor re-opened for the screenshot", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (!(mc.screen instanceof HologramScreen screen))
                return;
            screen.devTab(0);
            if (!HologramClient.previewAnchored())
                HologramClient.setPreviewAnchored(true);
        });

        // A separate step: the screenshot copies the last rendered frame, so a tab switch and a grab
        // in the same tick would photograph the tab before it.
        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "hologram-editor.png", mc.getMainRenderTarget(), message -> {
            });
            if (mc.screen instanceof HologramScreen screen)
                screen.devTab(1);
        });

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "hologram-editor-block.png", mc.getMainRenderTarget(), message -> {
            });
        });

        add(6, () -> {
            if (Minecraft.getInstance().screen instanceof HologramScreen screen)
                screen.onClose();
        });

        // ---- AE2 controller: the case this feature exists for ----
        add(6, () -> {
            if (!AE2) {
                log("SKIP ae2:controller (AE2 not installed in run-hologram/mods)");
                return;
            }
            String[] outcome = { "" };
            ae2Id = onServer(player -> {
                player.teleportTo(player.serverLevel(), 12.5, -60.0, 5.5, 180.0F, 0.0F);
                try {
                    player.server.getCommands().getDispatcher()
                            .execute("foxstweaks hologram add ae2:controller",
                                    player.createCommandSourceStack().withPermission(4));
                    outcome[0] = "ok";
                } catch (Exception e) {
                    outcome[0] = e.toString();
                }
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(all.size() - 1).id();
            });
            check("ae2:controller hologram created", outcome[0].equals("ok") && !ae2Id.isEmpty(),
                    outcome[0] + " id=" + ae2Id);
        });

        add(10, () -> {
            if (!AE2 || ae2Id == null || ae2Id.isEmpty())
                return;
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "hologram-ae2-world.png", mc.getMainRenderTarget(), message -> {
            });
            boolean seen = HologramClient.specs().stream().anyMatch(spec -> spec.id().equals(ae2Id));
            check("ae2 hologram is in the client list", seen, "count=" + HologramClient.specs().size());
        });

        add(6, () -> {
            if (!AE2 || ae2Id == null)
                return;
            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4),
                        "foxstweaks hologram edit " + ae2Id);
                return null;
            });
        });

        add(10, () -> {
            if (!AE2 || ae2Id == null)
                return;
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof HologramScreen screen)) {
                check("ae2 editor opened", false, String.valueOf(mc.screen));
                return;
            }
            List<Property<?>> properties = screen.currentProperties();
            check("ae2:controller exposes state + type",
                    indexOf(properties, "state") >= 0 && indexOf(properties, "type") >= 0,
                    "properties=" + names(properties));

            int stateIndex = indexOf(properties, "state");
            if (stateIndex >= 0) {
                String before = valueOf(screen.current(), "state");
                screen.devCycleProperty(stateIndex, 1);
                String after = valueOf(screen.current(), "state");
                check("ae2 state cycles within offline/online/conflicted",
                        !before.equals(after)
                                && List.of("offline", "online", "conflicted").containsAll(List.of(before, after)),
                        before + " -> " + after);
            }

            Screenshot.grab(mc.gameDirectory, "hologram-ae2-editor.png", mc.getMainRenderTarget(), message -> {
            });
            screen.saveAndClose();
        });

        add(10, () -> {
            if (!AE2 || ae2Id == null)
                return;
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), ae2Id));
            check("ae2 state persisted", saved != null && !saved.props().getOrDefault("state", "").isEmpty(),
                    saved == null ? "gone" : saved.props().toString());

            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4),
                        "foxstweaks hologram remove " + ae2Id);
                return null;
            });
            check("ae2 hologram removed",
                    onServer(player -> Holograms.get(player.serverLevel(), ae2Id)) == null, "still there");
        });

        // ---- effects + appear radius ----
        add(6, () -> onServer(player -> {
            // The AE2 step left the player 12 blocks away, outside the 10-block radius this is about
            // to set, so put them back in front of the hologram first.
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            // Pure appear maths first: a radius that can never reach 1.0 is invisible in a screenshot.
            check("appearTarget: inside the radius is 1",
                    HologramAnim.appearTarget(4.0, 8.0F) == 1.0F, "inside");
            check("appearTarget: outside the radius is 0",
                    HologramAnim.appearTarget(400.0, 8.0F) == 0.0F, "outside");
            check("appearTarget: radius off is always 1",
                    HologramAnim.appearTarget(1.0E6, 0.0F) == 1.0F, "off");

            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof HologramScreen screen)) {
                check("editor opened for the effects step", false, String.valueOf(mc.screen));
                return;
            }

            screen.devTab(2); // FX
            int before = screen.current().effects().size();
            screen.devScrollRow(0, 1); // Add effect
            screen.devScrollRow(0, 1); // Add again: FLICKER
            check("Add effect adds layers", screen.current().effects().size() == before + 2,
                    "effects=" + screen.current().effects().size());

            // Row 1 is the first layer; the wheel moves its strength.
            float strengthBefore = screen.current().effects().get(0).strength();
            screen.devScrollRow(1, 1);
            float strengthAfter = screen.current().effects().get(0).strength();
            check("wheel changes an effect's strength", strengthAfter > strengthBefore,
                    strengthBefore + " -> " + strengthAfter);

            // Last row on the FX tab is the appear radius. Several notches, so the radius covers the
            // spot the player stands on when they come back - a 2-block radius does not.
            for (int i = 0; i < 5; i++)
                screen.devScrollRow(screen.devRowCount() - 1, 1);
            check("appear radius can be raised", screen.current().appearRadius() >= 8.0F,
                    "radius=" + screen.current().appearRadius());
        });

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            // The screenshot copies the last rendered frame, so this has to be its own step.
            Screenshot.grab(mc.gameDirectory, "hologram-effects-tab.png", mc.getMainRenderTarget(), message -> {
            });
            if (mc.screen instanceof HologramScreen screen)
                screen.saveAndClose();
        });

        // Three shots a few ticks apart: the FLICKER layer hides the hologram for part of every cycle,
        // so a single shot has a real chance of photographing an empty patch of grass.
        add(6, () -> shoot("hologram-effects-world-1.png"));
        add(3, () -> shoot("hologram-effects-world-2.png"));
        add(3, () -> shoot("hologram-effects-world-3.png"));

        add(10, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            if (saved == null) {
                check("effects reached the server", false, "hologram gone");
                return;
            }
            check("effects round-trip through save and load", saved.effects().size() == 2,
                    "effects=" + saved.effects());
            check("effect types are TINT + FLICKER",
                    saved.effects().stream().map(HologramEffect::type).toList()
                            .equals(List.of(HologramEffect.Type.TINT, HologramEffect.Type.FLICKER)),
                    "effects=" + saved.effects());
            check("appear radius persisted", saved.appearRadius() > 0.0F, "radius=" + saved.appearRadius());            log("saved effects: " + saved.effects() + " radius=" + saved.appearRadius()
                    + " fade=" + saved.appearSeconds());
        });

        // Walk out of range: the client should animate it away, then bring it back.
        add(6, () -> onServer(player -> {
            player.teleportTo(player.serverLevel(), 200.5, -60.0, 200.5, 180.0F, 0.0F);
            return null;
        }));

        add(20, () -> check("leaving the radius grows the hologram away",
                HologramClient.appearProgress(hologramId) < 0.1F,
                "progress=" + HologramClient.appearProgress(hologramId)));

        add(6, () -> onServer(player -> {
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            return null;
        }));

        add(20, () -> check("coming back grows it in again",
                HologramClient.appearProgress(hologramId) > 0.9F,
                "progress=" + HologramClient.appearProgress(hologramId)));

        // ---- content parts: block + entity + particles in one hologram ----
        add(6, () -> onServer(player -> {
            // Stand back and shrink it: a 2.5x hologram at 3 blocks fills the screen, which hides the
            // parts standing on top of it.
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 10.5, 180.0F, 0.0F);
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof HologramScreen screen)) {
                check("editor opened for the parts step", false, String.valueOf(mc.screen));
                return;
            }
            screen.devSetScale(1.0F);

            // Drop the flicker layers first: they hide the whole hologram for part of every cycle, so
            // screenshots of the parts would land on empty grass about a third of the time.
            screen.devTab(2);
            while (!screen.current().effects().isEmpty())
                screen.devRemoveEffect(0);

            screen.devTab(3); // Parts
            check("Parts tab offers the Add row", screen.devRowCount() == 1, "rows=" + screen.devRowCount());

            screen.devAddPart(1, "minecraft:zombie");
            screen.devAddPart(2, "minecraft:end_rod");
            check("block + entity + particles live in one hologram", screen.devPartCount() == 2,
                    "parts=" + screen.current().parts());

            List<HologramPart> parts = screen.current().parts();
            check("entity part is an entity", parts.get(0).kind() == HologramPart.Kind.ENTITY,
                    "kind=" + parts.get(0).kind());
            check("particle part is a particle", parts.get(1).kind() == HologramPart.Kind.PARTICLE,
                    "kind=" + parts.get(1).kind());

            // The renderer's entity cache must hand back a real instance, or entity parts draw nothing.
            check("entity part resolves to a drawable entity",
                    HologramEntities.get(parts.get(0), 0.0, 0.0, 0.0, 0.0F, 1.0F) != null, "null entity");
            // New parts start on top of the base; the wheel nudges them in quarter blocks.
            screen.devScrollRow(1, 1);
            check("parts get fractional offsets",
                    Math.abs(screen.current().parts().get(0).offsetY() - 1.25F) < 1.0E-4F,
                    "offsetY=" + screen.current().parts().get(0).offsetY());
        });

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            Screenshot.grab(mc.gameDirectory, "hologram-parts-tab.png", mc.getMainRenderTarget(), message -> {
            });
            if (mc.screen instanceof HologramScreen screen)
                screen.saveAndClose();
        });

        add(6, () -> shoot("hologram-parts-world-1.png"));
        add(5, () -> shoot("hologram-parts-world-2.png"));
        add(5, () -> shoot("hologram-parts-world-3.png"));

        add(10, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            check("parts round-trip through save and load", saved != null && saved.parts().size() == 2,
                    saved == null ? "gone" : "parts=" + saved.parts());
            if (saved != null && saved.parts().size() == 2) {
                check("saved parts keep their kind and id",
                        saved.parts().get(0).id().equals("minecraft:zombie")
                                && saved.parts().get(1).id().equals("minecraft:end_rod"),
                        "parts=" + saved.parts());
            }
        });

        // ---- performance: 48 in view, then with four effect layers, then removed ----
        // Measured as CPU time of the hologram pass itself, not whole-frame time: a vsynced or
        // GPU-bound frame read ~8 ms no matter what the feature did, which told us nothing.
        add(10, () -> {
            onServer(player -> {
                player.teleportTo(player.serverLevel(), 0.5, -60.0, 13.5, 180.0F, 0.0F);
                for (int i = 0; i < BENCH_COUNT; i++) {
                    var pos = new BlockPos(-14 + (i % 8) * 4, -59, 6 - (i / 8) * 3);
                    Holograms.create(player.serverLevel(), pos, Blocks.SEA_LANTERN.defaultBlockState(), player);
                }
                return null;
            });
        });

        add(20, () -> {
            int onClient = HologramClient.specs().size();
            check("48 benchmark holograms reached the client", onClient >= BENCH_COUNT, "count=" + onClient);
            check("appear progress is tracked per hologram",
                    HologramClient.appearTracked() >= BENCH_COUNT, "tracked=" + HologramClient.appearTracked());
            recordStart();
        });

        add(BENCH_TICKS, () -> {
            reportRound("48 plain");
            onServer(player -> {
                for (HologramSpec spec : Holograms.list(player.serverLevel())) {
                    Holograms.save(player.serverLevel(), spec.withEffects(List.of(
                            HologramEffect.of(HologramEffect.Type.TINT),
                            HologramEffect.of(HologramEffect.Type.FLICKER),
                            HologramEffect.of(HologramEffect.Type.TREMOR),
                            HologramEffect.of(HologramEffect.Type.WOBBLE))));
                }
                return null;
            });
        });

        add(20, HologramCheck::recordStart);

        add(BENCH_TICKS, () -> {
            reportRound("48 with 4 effect layers");
            onServer(player -> {
                for (HologramSpec spec : Holograms.list(player.serverLevel())) {
                    if (!spec.id().equals(hologramId))
                        Holograms.remove(player.serverLevel(), spec.id());
                }
                return null;
            });
        });

        add(20, HologramCheck::recordStart);

        add(BENCH_TICKS, () -> {
            reportRound("after removal");
            check("48 holograms stay under 3 ms of CPU per frame", passMicros("48 plain") < 3000.0,
                    String.format(java.util.Locale.ROOT, "%.0f us", passMicros("48 plain")));
            check("four effect layers each add under 1.5 ms",
                    passMicros("48 with 4 effect layers") - passMicros("48 plain") < 1500.0,
                    String.format(java.util.Locale.ROOT, "%.0f us added",
                            passMicros("48 with 4 effect layers") - passMicros("48 plain")));
            check("the pass costs nothing once they are gone", passMicros("after removal") < 200.0,
                    String.format(java.util.Locale.ROOT, "%.0f us", passMicros("after removal")));
        });

        // ---- is batching worth it? Same count, 48 distinct models instead of 48 identical ones ----
        add(10, () -> onServer(player -> {
            List<Block> distinct = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.asItem() != Items.AIR)
                    .filter(block -> block.defaultBlockState().getRenderShape() == RenderShape.MODEL)
                    .limit(BENCH_COUNT)
                    .toList();
            for (int i = 0; i < BENCH_COUNT; i++) {
                var pos = new BlockPos(-14 + (i % 8) * 4, -59, 6 - (i / 8) * 3);
                Holograms.create(player.serverLevel(), pos,
                        distinct.get(i % distinct.size()).defaultBlockState(), player);
            }
            return null;
        }));

        add(20, HologramCheck::recordStart);

        add(BENCH_TICKS, () -> {
            reportRound("48 distinct blocks");
            double delta = passMicros("48 distinct blocks") - passMicros("48 plain");
            // If 48 distinct models cost about the same as 48 repeats of one, the per-draw cost is vertex
            // emission rather than model lookup - so batching by state has nothing to give back. This is
            // the measurement that decides it; the +30% seen here is more quads per model, not lookup.
            check("state batching would not pay off (distinct models cost ~the same as repeats)",
                    Math.abs(delta) < 80.0,
                    String.format(java.util.Locale.ROOT, "%.0f us difference", delta));
        });

        // ---- frustum culling: the same 48, now behind the player ----
        add(6, () -> onServer(player -> {
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 13.5, 0.0F, 0.0F);
            return null;
        }));

        add(20, HologramCheck::recordStart);

        add(BENCH_TICKS, () -> {
            reportRound("48 behind you (culled)");
            check("off-screen holograms cost nothing", passMicros("48 behind you (culled)") < 30.0,
                    String.format(java.util.Locale.ROOT, "%.0f us", passMicros("48 behind you (culled)")));
            log("perf summary: " + SUMMARY);
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-hologram-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static void shoot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static String valueOf(HologramSpec spec, String property) {
        return HologramStates.valueOf(spec, property);
    }

    private static int indexOf(List<Property<?>> properties, String name) {
        for (int i = 0; i < properties.size(); i++) {
            if (properties.get(i).getName().equals(name))
                return i;
        }
        return -1;
    }

    private static List<String> names(List<Property<?>> properties) {
        List<String> names = new ArrayList<>();
        for (Property<?> property : properties)
            names.add(property.getName());
        return names;
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
        LOG.info("HologramCheck {}", line);
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
            LOG.error("HologramCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-hologramcheck";
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

    private HologramCheck() {
    }
}
