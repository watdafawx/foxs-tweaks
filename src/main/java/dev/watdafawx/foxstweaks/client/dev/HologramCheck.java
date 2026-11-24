package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramBlockEntities;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramEntities;
import dev.watdafawx.foxstweaks.client.HologramRenderer;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
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
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
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
    private static String berId;
    private static String driveId = "";

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

            // ---- two panels, 4 inspector tabs (Layers/Main/Anim/FX), collapsible picker ----
            screen.devTab(HologramScreen.TAB_MAIN);
            check("Main tab exposes Pos/Rot/Scale controls for layer", screen.devNumControlCount() == 7,
                    "controls=" + screen.devNumControlCount());
            check("the middle of the screen stays free", screen.devFreeWidth() >= 90,
                    "free=" + screen.devFreeWidth() + "px, gridCols=" + screen.devGridCols());

            int freeExpanded = screen.devFreeWidth();
            screen.devCollapsePicker(true);
            check("collapsing Content Picker widens viewport", screen.devFreeWidth() > freeExpanded,
                    freeExpanded + " -> " + screen.devFreeWidth());
            screen.devCollapsePicker(false);

            boolean anchoredBefore = HologramClient.previewAnchored();
            HologramClient.setPreviewAnchored(false);
            check("studio camera active when editor open", dev.watdafawx.foxstweaks.client.HologramCamera.isActive(),
                    "active=" + dev.watdafawx.foxstweaks.client.HologramCamera.isActive());
            var camStart = dev.watdafawx.foxstweaks.client.HologramCamera.pos();
            dev.watdafawx.foxstweaks.client.HologramCamera.dolly(-2.0);
            check("studio camera dollies back and forward",
                    dev.watdafawx.foxstweaks.client.HologramCamera.pos().distanceTo(camStart) > 1.5,
                    "moved=" + dev.watdafawx.foxstweaks.client.HologramCamera.pos().distanceTo(camStart));
            dev.watdafawx.foxstweaks.client.HologramCamera.resetToPlayer();
            HologramClient.setPreviewAnchored(!anchoredBefore);
            check("in-front preview toggles",
                    HologramClient.previewAnchored() != anchoredBefore,
                    "was " + anchoredBefore + ", now " + HologramClient.previewAnchored());
            HologramClient.setPreviewAnchored(anchoredBefore);

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
            screen.devSetOffset(0.5F, 1.0F, -0.25F);
            // Verify Undo & Redo history
            screen.undo();
            check("undo reverts last offset edit",
                    Math.abs(screen.current().layers().get(0).offsetX()) < 0.001F,
                    "ox=" + screen.current().layers().get(0).offsetX());
            screen.redo();
            check("redo reapplies offset edit",
                    Math.abs(screen.current().layers().get(0).offsetX() - 0.5F) < 0.001F,
                    "ox=" + screen.current().layers().get(0).offsetX());

            check("editor preview follows the edit immediately",
                    HologramClient.specs().stream().anyMatch(spec -> spec.id().equals(hologramId)
                            && spec.layers().get(0).scale() == 2.5F),
                    "preview=" + HologramClient.specs());

            // Scrollbar drag + Entity/Particle pickers + JEI/AE2 search prefixes (@mod #tip $tag -neg | or)
            screen.devLeftMode(0);
            screen.devDragScrollbar(0.5);
            check("block picker scrollbar drags", screen.devScroll() > 0, "scroll=" + screen.devScroll());
            int mcBlocks = screen.devSearch("@minecraft stairs -oak");
            int allStairs = screen.devSearch("@minecraft stairs");
            check("search supports @mod and -negation", mcBlocks > 0 && mcBlocks < allStairs,
                    "filtered=" + mcBlocks + " vs allStairs=" + allStairs);
            check("search supports $tag", screen.devSearch("$logs") > 0, "logs=" + screen.devSearch("$logs"));
            check("search supports #tooltip", screen.devSearch("#oak") > 0, "tip=" + screen.devSearch("#oak"));
            check("search supports | OR", screen.devSearch("oak_stairs | birch_stairs") >= 2,
                    "or=" + screen.devSearch("oak_stairs | birch_stairs"));
            screen.devSearch("");
            screen.devLeftMode(1);
            screen.devDragScrollbar(0.5);
            check("entity picker scrollbar drags", screen.devScroll() > 0, "scroll=" + screen.devScroll());
            screen.devLeftMode(2);
            screen.devDragScrollbar(0.5);
            check("particle picker scrollbar drags", screen.devScroll() > 0, "scroll=" + screen.devScroll());
            screen.devLeftMode(0);

            screen.saveAndClose();
        });

        add(10, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            if (saved == null) {
                check("save reached the server", false, "hologram gone");
                return;
            }
            HologramLayer savedL0 = saved.layers().get(0);
            check("save kept the cycled property", !savedL0.props().isEmpty(), "props=" + savedL0.props());
            check("save kept the new scale", Math.abs(savedL0.scale() - 2.5F) < 0.001F, "scale=" + savedL0.scale());
            check("save kept the spin", Math.abs(savedL0.spin() - 45.0F) < 0.001F, "spin=" + savedL0.spin());
            check("save kept the layer offset",
                    Math.abs(savedL0.offsetX() - 0.5F) < 0.001F
                            && Math.abs(savedL0.offsetY() - 1.0F) < 0.001F
                            && Math.abs(savedL0.offsetZ() + 0.25F) < 0.001F,
                    "offset=(" + savedL0.offsetX() + "," + savedL0.offsetY() + "," + savedL0.offsetZ() + ")");
            // Reset offset back to 0 so downstream screenshots stay centred in frame.
            onServer(player -> {
                var cur = Holograms.get(player.serverLevel(), hologramId);
                if (cur != null) {
                    var zeroed = cur.withLayer(0, cur.layers().get(0).withOffset(0.0F, 0.0F, 0.0F));
                    Holograms.save(player.serverLevel(), zeroed);
                }
                return null;
            });
            log("saved spec: primary=" + saved.primaryId() + " props=" + savedL0.props() + " scale=" + savedL0.scale()
                    + " spin=" + savedL0.spin() + " range=" + saved.renderDistance());
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
            check("ae2 state persisted", saved != null && !saved.layers().get(0).props().getOrDefault("state", "").isEmpty(),
                    saved == null ? "gone" : saved.layers().get(0).props().toString());

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

            screen.devSelectLayer(0);
            screen.devTab(HologramScreen.TAB_FX);
            int before = screen.current().layers().get(0).effects().size();
            screen.devToggleEffect(HologramEffect.Type.TINT);
            screen.devToggleEffect(HologramEffect.Type.FLICKER);
            check("FX pills toggle layer effect list", screen.current().layers().get(0).effects().size() == before + 2,
                    "effects=" + screen.current().layers().get(0).effects().size());

            float strengthBefore = screen.current().layers().get(0).effects().get(0).strength();
            screen.devSetEffectStrength(0, strengthBefore + 0.1F);
            float strengthAfter = screen.current().layers().get(0).effects().get(0).strength();
            check("effect strength adjusts", strengthAfter > strengthBefore,
                    strengthBefore + " -> " + strengthAfter);

            int colorBefore = screen.current().layers().get(0).effects().get(0).color();
            screen.devSetEffectColor(0, 0xFF4D5A);
            check("tint color updates", screen.current().layers().get(0).effects().get(0).color() != colorBefore,
                    Integer.toHexString(colorBefore) + " -> " + Integer.toHexString(screen.current().layers().get(0).effects().get(0).color()));
            screen.devSetEffectSpeed(1, 4.5F);
            check("effect speed adjusts", Math.abs(screen.current().layers().get(0).effects().get(1).speed() - 4.5F) < 0.01F,
                    "speed=" + screen.current().layers().get(0).effects().get(1).speed());

            screen.devSetAppearRadius(10.0F);
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
            List<HologramEffect> savedFx = saved.layers().get(0).effects();
            check("effects round-trip through save and load", savedFx.size() == 2,
                    "effects=" + savedFx);
            check("effect types are TINT + FLICKER",
                    savedFx.stream().map(HologramEffect::type).toList()
                            .equals(List.of(HologramEffect.Type.TINT, HologramEffect.Type.FLICKER)),
                    "effects=" + savedFx);
            check("appear radius persisted", saved.appearRadius() > 0.0F, "radius=" + saved.appearRadius());
            log("saved effects: " + savedFx + " radius=" + saved.appearRadius()
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
            screen.devSelectLayer(0);
            screen.devSetScale(1.0F);

            // Drop flicker on layer 0 first so screenshots don't land on hidden frames.
            screen.devTab(HologramScreen.TAB_FX);
            while (!screen.current().layers().get(0).effects().isEmpty())
                screen.devRemoveEffect(0);

            // Verify picking an entity replaces the selected layer cleanly without air-base hack
            screen.devPickEntity("minecraft:creeper");
            check("picking an entity updates selected layer cleanly",
                    screen.current().layers().size() == 1
                            && screen.current().layers().get(0).kind() == HologramLayer.Kind.ENTITY
                            && screen.current().layers().get(0).id().equals("minecraft:creeper"),
                    "layers=" + screen.current().layers());
            screen.pickBlock(Blocks.OAK_STAIRS);
            check("picking a block back updates layer 0 to oak_stairs",
                    screen.current().layers().size() == 1
                            && screen.current().layers().get(0).id().equals("minecraft:oak_stairs"),
                    "layers=" + screen.current().layers());

            // Add entity + independent particle layer, plus a group linking block + entity only
            screen.devTab(HologramScreen.TAB_LAYERS);
            screen.devAddLayer(HologramLayer.Kind.ENTITY, "minecraft:zombie");
            screen.devAddLayer(HologramLayer.Kind.PARTICLE, "minecraft:end_rod");
            check("block + entity + particles live as 3 unified layers", screen.devLayerCount() == 3,
                    "layers=" + screen.current().layers());

            String gid = screen.devAddGroup();
            screen.devAssignLayerGroup(0, gid);
            screen.devAssignLayerGroup(1, gid);
            // Layer 2 (particle) stays ungrouped outside the group with independent FX/anim
            screen.devSelectGroup(gid);
            screen.devSetSpin(30.0F);
            screen.devToggleEffect(HologramEffect.Type.TINT);
            check("group holds linked spin & FX while ungrouped particle stays clean",
                    screen.current().groups().size() == 1
                            && Math.abs(screen.current().groups().get(0).spin() - 30.0F) < 0.01F
                            && screen.current().groups().get(0).effects().size() == 1
                            && screen.current().layers().get(2).groupId().isEmpty()
                            && screen.current().layers().get(2).effects().isEmpty(),
                    "groups=" + screen.current().groups() + " l2=" + screen.current().layers().get(2));

            // Solo toggle hides other layers locally
            screen.devToggleSoloLayer(1);
            check("solo layer filters other layers",
                    HologramClient.passesSolo(hologramId, 1, screen.current().layers().get(1))
                            && !HologramClient.passesSolo(hologramId, 0, screen.current().layers().get(0)),
                    "solo check");
            screen.devToggleSoloLayer(1);

            List<HologramLayer> layers = screen.current().layers();
            check("entity layer resolves to a drawable entity",
                    HologramEntities.get(layers.get(1), 0.0, 0.0, 0.0, 0.0F, 1.0F) != null, "null entity");
            screen.devSelectLayer(1);
            screen.devSetOffset(0.0F, 1.25F, 0.0F);
            check("layers get fractional offsets",
                    Math.abs(screen.current().layers().get(1).offsetY() - 1.25F) < 1.0E-4F,
                    "offsetY=" + screen.current().layers().get(1).offsetY());
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
            check("layers & groups round-trip through save and load",
                    saved != null && saved.layers().size() == 3 && saved.groups().size() == 1,
                    saved == null ? "gone" : "layers=" + saved.layers() + " groups=" + saved.groups());
            if (saved != null && saved.layers().size() == 3) {
                check("saved layers keep their kind and id",
                        saved.layers().get(1).id().equals("minecraft:zombie")
                                && saved.layers().get(2).id().equals("minecraft:end_rod"),
                        "layers=" + saved.layers());
            }
        });

        // ---- block entities: a chest hologram that carries real contents ----
        add(10, () -> {
            berId = onServer(player -> {
                var level = player.serverLevel();
                var chestPos = new BlockPos(4, -60, 2);
                level.setBlockAndUpdate(chestPos, Blocks.CHEST.defaultBlockState());
                if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                    chest.setItem(0, new ItemStack(Items.DIAMOND, 3));
                    chest.setChanged();
                }
                player.teleportTo(level, 6.5, -60.0, 8.5, 180.0F, 0.0F);
                // The chest is the data source; the hologram stands beside it for comparison.
                var spec = Holograms.create(level, new BlockPos(6, -60, 2), Blocks.CHEST.defaultBlockState(),
                        chestPos, player);
                return spec.id();
            });

            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), berId));
            check("chest hologram captured block entity data", saved != null && !saved.layers().get(0).data().isEmpty(),
                    saved == null ? "gone" : "data=" + saved.layers().get(0).data().size() + " keys");
        });

        add(10, () -> {
            HologramSpec spec = HologramClient.specs().stream()
                    .filter(candidate -> candidate.id().equals(berId)).findFirst().orElse(null);
            check("client has the chest hologram", spec != null, "count=" + HologramClient.specs().size());
            if (spec == null)
                return;

            var blockEntity = HologramBlockEntities.get(spec);
            check("block entity was built from the captured data", blockEntity != null,
                    "null entity for " + spec.primaryId());
            if (blockEntity == null)
                return;
            check("block entity has a renderer", HologramBlockEntities.renderer(blockEntity) != null,
                    "no renderer");

            // Also verify an EntityBlock with empty NBT (picked fresh from picker) still builds a BER.
            var emptyChest = HologramSpec.create("dev_empty_chest", spec.pos(), "minecraft:chest", null, "");
            var emptyBe = HologramBlockEntities.get(emptyChest);
            check("empty-data EntityBlock still builds a block entity for BER",
                    emptyBe != null && HologramBlockEntities.renderer(emptyBe) != null,
                    "emptyBe=" + emptyBe);
        });

        add(10, () -> {
            check("the render pass drew a block entity", HologramRenderer.lastDrawnBlockEntityCount() > 0,
                    "count=" + HologramRenderer.lastDrawnBlockEntityCount());
            shoot("hologram-block-entity.png");
        });

        // ---- AE2 drive: powered with a creative energy cell + storage cell -> LEDs & ModelData ----
        add(10, () -> {
            if (!AE2) {
                log("SKIP ae2 drive (AE2 not installed)");
                return;
            }
            onServer(player -> {
                var level = player.serverLevel();
                var drivePos = new BlockPos(10, -60, 2);
                var energyPos = new BlockPos(9, -60, 2);
                var drive = HologramStates.blockOf("ae2:drive");
                var energy = HologramStates.blockOf("ae2:creative_energy_cell");
                if (drive == null)
                    return null;
                if (energy != null)
                    level.setBlockAndUpdate(energyPos, energy.defaultBlockState());
                level.setBlockAndUpdate(drivePos, drive.defaultBlockState());

                // A cell, so the drive has something to report. Generic capability call: no AE2 type here.
                var handler = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,
                        drivePos, null);
                var cell = BuiltInRegistries.ITEM.get(ResourceLocation.parse("ae2:item_storage_cell_1k"));
                if (handler != null && cell != null)
                    handler.insertItem(0, new ItemStack(cell), false);

                player.teleportTo(level, 11.0, -60.0, 6.5, 180.0F, 0.0F);
                return null;
            });
        });

        // Give AE2's grid a few ticks to boot and power the drive before capturing its visual state.
        add(20, () -> {
            if (!AE2)
                return;
            driveId = onServer(player -> {
                var level = player.serverLevel();
                var drivePos = new BlockPos(10, -60, 2);
                var state = level.getBlockState(drivePos);
                if (HologramStates.nameOf(state).isEmpty())
                    return "";
                var spec = Holograms.create(level, new BlockPos(12, -60, 2), state, drivePos, player);
                return spec.id();
            });
            check("ae2 drive hologram created", !driveId.isEmpty(), "id=" + driveId);
        });

        add(10, () -> {
            if (!AE2 || driveId.isEmpty())
                return;
            HologramSpec spec = HologramClient.specs().stream()
                    .filter(candidate -> candidate.id().equals(driveId)).findFirst().orElse(null);
            if (spec == null) {
                check("client has the ae2 drive hologram", false, "missing");
                return;
            }
            log("ae2 drive captured keys: " + spec.layers().get(0).data().getAllKeys());
            check("ae2 drive captured update tag (_ft_update)", spec.layers().get(0).data().contains("_ft_update"),
                    "keys=" + spec.layers().get(0).data().getAllKeys());
            var blockEntity = HologramBlockEntities.get(spec);
            check("ae2 drive block entity was built", blockEntity != null, "null entity");
            check("ae2 drive has a renderer",
                    blockEntity != null && HologramBlockEntities.renderer(blockEntity) != null, "no renderer");
            check("ae2 drive provides non-empty ModelData (cells)",
                    blockEntity != null
                            && blockEntity.getModelData() != net.neoforged.neoforge.client.model.data.ModelData.EMPTY,
                    "modelData=" + (blockEntity == null ? "null" : blockEntity.getModelData()));
            // Verify Paste-as-new (at()) preserves BER data
            var pasted = spec.at("dev_pasted_drive", spec.pos().east(), spec.owner(), spec.ownerName());
            check("Paste-as-new keeps BER data",
                    !pasted.layers().get(0).data().isEmpty() && pasted.layers().get(0).data().contains("_ft_update"),
                    "data=" + pasted.layers().get(0).data().getAllKeys());
            shoot("hologram-ae2-drive.png");
        });

        add(6, () -> {
            if (!AE2 || driveId.isEmpty())
                return;
            check("ae2 drive drew through its own renderer", HologramRenderer.lastDrawnBlockEntityCount() > 0,
                    "count=" + HologramRenderer.lastDrawnBlockEntityCount());
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
                    if (spec.layers().isEmpty())
                        continue;
                    HologramLayer l0 = spec.layers().get(0).withEffects(List.of(
                            HologramEffect.of(HologramEffect.Type.TINT),
                            HologramEffect.of(HologramEffect.Type.FLICKER),
                            HologramEffect.of(HologramEffect.Type.TREMOR),
                            HologramEffect.of(HologramEffect.Type.WOBBLE)));
                    Holograms.save(player.serverLevel(), spec.withLayer(0, l0));
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
