package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramCreatorScreen;
import dev.watdafawx.foxstweaks.client.HologramEditorPrefs;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
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
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Dev-only check of the simple hologram editor ({@link HologramCreatorScreen}): opens it through the real edit
 * command, presses its real controls (looks, colour modes, the HSV wheel, FX and motion tiles, sliders, undo),
 * switches to the Studio and back without losing anything, saves, and asserts the server got the result.
 * Screenshots at GUI scale 2, 3 and 4.
 *
 * <p>Run with {@code build.bat runHologramCreatorCheck} on rig. Report:
 * {@code run-hologram-creator/foxstweaks-hologram-creator-check.txt}.
 */
public final class HologramCreatorCheck {
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
    private static HologramScreen editor;
    private static int looksBefore;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramCreatorCheck::onTick);
        scenarios();
    }

    private static HologramCreatorScreen creator() {
        return Minecraft.getInstance().screen instanceof HologramCreatorScreen c ? c : null;
    }

    private static List<HologramEffect> effects() {
        return editor == null ? List.of() : editor.current().layers().get(0).effects();
    }

    private static long count(HologramEffect.Type type) {
        return effects().stream().filter(e -> e.type() == type).count();
    }

    private static void click(String tooltip, int button) {
        var c = creator();
        boolean ok = c != null && c.devClick(tooltip, button);
        if (!ok)
            check("control clickable: " + tooltip, false, "screen=" + Minecraft.getInstance().screen);
    }

    private static void reveal(String tooltip) {
        var c = creator();
        if (c == null || !c.devReveal(tooltip))
            check("control exists: " + tooltip, false, "screen=" + Minecraft.getInstance().screen);
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void guiScale(int scale) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    private static void scenarios() {
        add(40, () -> onServer(player -> {
            player.serverLevel().setDayTime(6000);
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            return null;
        }));

        add(20, () -> {
            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4),
                        "foxstweaks hologram add minecraft:amethyst_block");
                return null;
            });
            hologramId = onServer(player -> {
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("hologram created", !hologramId.isEmpty(), "none");
        });

        // ---- the edit command opens the Creator when that is the saved preference ----
        add(10, () -> {
            HologramEditorPrefs.setStartInCreator(true);
            looksBefore = HologramEditorPrefs.looks().size();
            guiScale(2);
            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4), "foxstweaks hologram edit " + hologramId);
                return null;
            });
        });

        add(10, () -> {
            var c = creator();
            check("edit opens the Creator", c != null, String.valueOf(Minecraft.getInstance().screen));
            if (c == null)
                return;
            editor = c.editor();
            check("middle of the screen stays free (scale 2)", c.devFreeWidth() >= 90, "free=" + c.devFreeWidth());
            check("starts with no effects", effects().isEmpty(), effects().toString());
        });

        // ---- built-in look ----
        add(4, () -> {
            click("Apply Holo", 0);
            check("Holo look adds tint + scanline", count(HologramEffect.Type.TINT) == 1
                    && count(HologramEffect.Type.SCANLINE) == 1, effects().toString());
        });

        // ---- colour: gradient, pick the top stop, drag the wheel to red ----
        add(4, () -> {
            click("Colour: Gradient", 0);
            check("gradient mode swaps tint for gradient", count(HologramEffect.Type.GRADIENT) == 1
                    && count(HologramEffect.Type.TINT) == 0, effects().toString());
            check("gradient sits first in the stack", !effects().isEmpty()
                    && effects().get(0).type() == HologramEffect.Type.GRADIENT, effects().toString());
        });
        add(4, () -> click("Top colour", 0));
        add(4, () -> {
            var c = creator();
            // Right edge of the disc at mid-height is hue 0, full saturation: pure red at the current value.
            boolean dragged = c != null && c.devDrag("Hue and saturation", 1.0);
            check("wheel is draggable", dragged, "no wheel hit");
            HologramEffect grad = effects().stream().filter(e -> e.type() == HologramEffect.Type.GRADIENT)
                    .findFirst().orElse(null);
            int top = grad == null ? -1 : grad.color2();
            int r = top >> 16 & 0xFF, g = top >> 8 & 0xFF, b = top & 0xFF;
            check("wheel sets the top stop to red", grad != null && r > 200 && g < 60 && b < 60,
                    String.format("color2=%06X", top));
            check("bottom stop untouched", grad != null && grad.color() == HologramEffect.DEFAULT_COLOR,
                    grad == null ? "none" : String.format("color=%06X", grad.color()));
        });
        add(6, () -> shot("creator-colour-s2.png"));

        // ---- motion tiles: click twice = two spins on different axes, right-click removes all ----
        add(2, () -> reveal("Turns forever"));
        add(4, () -> {
            click("Turns forever", 0);
            click("Turns forever", 0);
            check("two spin clicks = two spins", count(HologramEffect.Type.SPIN) == 2, effects().toString());
            var axes = effects().stream().filter(e -> e.type() == HologramEffect.Type.SPIN)
                    .map(HologramEffect::axis).distinct().count();
            check("second spin takes the next axis", axes == 2, effects().toString());
            click("Floats up and down", 0);
            check("bob added", count(HologramEffect.Type.BOB) == 1, effects().toString());
        });
        add(6, () -> shot("creator-motion-s2.png"));
        add(4, () -> {
            click("Turns forever", 1);
            check("right-click removes every spin", count(HologramEffect.Type.SPIN) == 0, effects().toString());
            var c = creator();
            if (c != null)
                c.keyPressed(GLFW.GLFW_KEY_Z, 0, GLFW.GLFW_MOD_CONTROL);
            // hasControlDown reads the real keyboard, which a dev run cannot press; use the editor's undo too.
            if (count(HologramEffect.Type.SPIN) == 0)
                editor.undo();
            check("undo brings the spins back", count(HologramEffect.Type.SPIN) == 2, effects().toString());
        });

        // ---- FX tile toggles, size slider ----
        add(2, () -> reveal("Glitch bursts"));
        add(4, () -> {
            click("Glitch bursts", 0);
            check("glitch tile adds", count(HologramEffect.Type.GLITCH) == 1, effects().toString());
            click("Glitch bursts", 0);
            check("glitch tile toggles off", count(HologramEffect.Type.GLITCH) == 0, effects().toString());
        });
        add(2, () -> reveal("Drag or scroll"));
        add(4, () -> {
            var c = creator();
            boolean dragged = c != null && c.devDrag("Drag or scroll", 0.5);
            float scale = editor.current().layers().get(0).scale();
            check("size slider sets about 2x", dragged && Math.abs(scale - 2.05F) < 0.1F, "scale=" + scale);
        });

        // ---- save a look ----
        add(4, () -> {
            click("Save this layer", 0);
            check("look saved", HologramEditorPrefs.looks().size() == Math.min(looksBefore + 1,
                    HologramEditorPrefs.MAX_LOOKS), "looks=" + HologramEditorPrefs.looks().size());
        });

        // ---- screenshots at three GUI scales, lower sections scrolled into view ----
        add(6, () -> shot("creator-s2.png"));
        add(2, () -> guiScale(3));
        add(8, () -> {
            var c = creator();
            check("still the Creator after resize", c != null, String.valueOf(Minecraft.getInstance().screen));
            if (c != null)
                check("middle stays free (scale 3)", c.devFreeWidth() >= 90, "free=" + c.devFreeWidth());
            shot("creator-s3.png");
        });
        add(2, () -> {
            var c = creator();
            if (c != null)
                c.devScrollRight(10_000);
        });
        add(6, () -> shot("creator-s3-stack.png"));
        add(2, () -> guiScale(4));
        add(8, () -> {
            var c = creator();
            if (c != null)
                check("middle stays free (scale 4)", c.devFreeWidth() >= 60, "free=" + c.devFreeWidth());
            shot("creator-s4.png");
        });
        add(2, () -> guiScale(2));

        // ---- Studio and back: same instance, same edits ----
        add(6, () -> {
            List<HologramEffect> before = effects();
            click("The full editor", 0);
            Screen s = Minecraft.getInstance().screen;
            check("Studio button opens the full editor", s == editor, String.valueOf(s));
            check("Studio keeps every edit", effects().equals(before), effects() + " vs " + before);
            check("choice remembered", !HologramEditorPrefs.startInCreator(), "still creator");
        });
        add(8, () -> {
            shot("creator-studio.png");
            boolean ok = editor.devClick("< Simple");
            check("< Simple returns to the Creator", ok && creator() != null,
                    String.valueOf(Minecraft.getInstance().screen));
            check("back in Creator remembered", HologramEditorPrefs.startInCreator(), "still studio");
        });

        // ---- studio camera stays on under the Creator; + opens the browser, Esc comes back ----
        add(4, () -> {
            check("studio camera active under the Creator", dev.watdafawx.foxstweaks.client.HologramCamera.isActive(),
                    "inactive");
            click("Browse blocks", 0);
            check("+ opens the asset browser",
                    Minecraft.getInstance().screen instanceof dev.watdafawx.foxstweaks.client.HologramPickerScreen,
                    String.valueOf(Minecraft.getInstance().screen));
        });
        add(8, () -> {
            var s = Minecraft.getInstance().screen;
            if (s != null)
                s.onClose();
            check("Esc in the browser returns to the Creator", creator() != null,
                    String.valueOf(Minecraft.getInstance().screen));
        });

        // ---- Grab from world starts from the Creator and comes back to it ----
        add(6, () -> {
            click("Grab from world", 0);
            check("Grab hides the Creator", dev.watdafawx.foxstweaks.client.HologramGrab.isActive()
                    && Minecraft.getInstance().screen == null, String.valueOf(Minecraft.getInstance().screen));
        });
        add(6, () -> {
            dev.watdafawx.foxstweaks.client.HologramGrab.cancelAndReturn();
            check("Esc from grab returns to the Creator", creator() != null && creator().editor() == editor,
                    String.valueOf(Minecraft.getInstance().screen));
        });

        // ---- Done: the server stores the gradient with both colours ----
        add(6, () -> {
            HologramEffect local = effects().stream().filter(e -> e.type() == HologramEffect.Type.GRADIENT)
                    .findFirst().orElse(null);
            click("Save and close", 0);
            check("Done closes the editor", Minecraft.getInstance().screen == null,
                    String.valueOf(Minecraft.getInstance().screen));
            if (local == null)
                check("gradient present before save", false, effects().toString());
        });
        add(10, () -> {
            HologramSpec saved = onServer(p -> Holograms.get(p.serverLevel(), hologramId));
            check("server has the hologram", saved != null, "null");
            if (saved == null)
                return;
            var eff = saved.layers().get(0).effects();
            HologramEffect grad = eff.stream().filter(e -> e.type() == HologramEffect.Type.GRADIENT).findFirst()
                    .orElse(null);
            check("server keeps the gradient top colour", grad != null && (grad.color2() >> 16 & 0xFF) > 200,
                    eff.toString());
            check("server keeps the spins and bob", eff.stream().filter(e -> e.type() == HologramEffect.Type.SPIN).count() == 2
                    && eff.stream().anyMatch(e -> e.type() == HologramEffect.Type.BOB), eff.toString());
            check("server keeps the size", Math.abs(saved.layers().get(0).scale() - 2.05F) < 0.1F,
                    "scale=" + saved.layers().get(0).scale());
        });
        add(30, () -> shot("creator-world.png"));

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-creator-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------- harness

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramCreatorCheck {}", line);
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
            LOG.error("HologramCreatorCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-creatorcheck";
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

    private HologramCreatorCheck() {
    }
}
