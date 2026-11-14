package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check of the Shrink hotkey ({@code shrink/ShrinkNetwork}, {@code client/ShrinkKeyHandler}) and the settings
 * screen key ({@code client/ConfigScreenKeys}). Run by {@code build.bat runShrinkCheck} (game dir {@code run-shrink/},
 * put shrink, architectury and polylib in its {@code mods/}). Flat creative world; a Shrinking Device set to -0.5 goes
 * in a backpack slot (not the hand), then the real keys are pressed: shrink on, off, survival with no power, no
 * device, then the settings screen (screenshot). Writes {@code run-shrink/foxstweaks-shrink-check.txt} and quits.
 */
public final class ShrinkCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation DEVICE = ResourceLocation.fromNamespaceAndPath("shrink", "shrinking_device");
    private static final ResourceLocation MODIFIER = ResourceLocation.fromNamespaceAndPath("shrink", "e4388c41-4cf8-4631-98b4-b26eeaedcbdc");
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<Consumer<Minecraft>> STEPS = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int index;
    private static int wait = 60;

    public static void install() {
        STEPS.add(ShrinkCheck::setup);
        STEPS.add(mc -> {
            check("shrunk by key", hasModifier(mc, -0.5), "modifier " + modifier(mc));
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("unshrunk by second press", modifier(mc) == null, "modifier " + modifier(mc));
            server(mc, p -> p.setGameMode(GameType.SURVIVAL));
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("survival without power does nothing", modifier(mc) == null, "modifier " + modifier(mc));
            server(mc, p -> {
                p.setGameMode(GameType.CREATIVE);
                p.getInventory().clearContent();
            });
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("no device does nothing", modifier(mc) == null, "modifier " + modifier(mc));
            press(FoxsTweaksKeys.OPEN_CONFIG);
        });
        STEPS.add(mc -> {
            // One config file: NeoForge skips the file list and opens its section screen directly.
            check("settings key opens config screen", mc.screen instanceof ConfigurationScreen
                    || mc.screen instanceof ConfigurationScreen.ConfigurationSectionScreen, "screen " + mc.screen);
            check("labels translated", mc.screen != null && !mc.screen.getTitle().getString().contains("foxstweaks."),
                    "title " + (mc.screen == null ? null : mc.screen.getTitle().getString()));
            shot("foxstweaks-config-screen.png");
        });
        STEPS.add(ShrinkCheck::finish);
        NeoForge.EVENT_BUS.addListener(ShrinkCheck::onTick);
    }

    private static void setup(Minecraft mc) {
        check("shrink device registered", BuiltInRegistries.ITEM.containsKey(DEVICE), "no " + DEVICE);
        check("server has the channel", mc.getConnection() != null
                && mc.getConnection().hasChannel(dev.watdafawx.foxstweaks.shrink.ShrinkNetwork.ToggleShrinkPayload.TYPE), "no channel");
        server(mc, p -> {
            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(DEVICE));
            try {
                stack.getItem().getClass().getMethod("writeScale", ItemStack.class, double.class).invoke(stack.getItem(), stack, -0.5);
            } catch (ReflectiveOperationException e) {
                throw new RuntimeException(e);
            }
            p.getInventory().setItem(20, stack);
        });
        press(FoxsTweaksKeys.TOGGLE_SHRINK);
    }

    private static void finish(Minecraft mc) {
        log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
        try {
            Files.write(mc.gameDirectory.toPath().resolve("foxstweaks-shrink-check.txt"), REPORT);
        } catch (IOException ex) {
            LOG.error("Shrink check report", ex);
        }
        mc.stop();
    }

    /** Binds the key to F9 for one simulated press, through the same consumeClick path a real press takes. */
    private static void press(KeyMapping key) {
        var mc = Minecraft.getInstance();
        if (mc.screen != null)
            mc.setScreen(null);
        InputConstants.Key k = InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_F9);
        for (KeyMapping other : mc.options.keyMappings)
            if (other != key && other.getKey().equals(k))
                other.setKey(InputConstants.UNKNOWN);
        key.setKey(k);
        KeyMapping.resetMapping();
        KeyMapping.click(k);
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(mc.player.getUUID())));
    }

    private static Double modifier(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        ServerPlayer p = server.getPlayerList().getPlayer(mc.player.getUUID());
        var m = p.getAttribute(Attributes.SCALE).getModifier(MODIFIER);
        return m == null ? null : m.amount();
    }

    private static boolean hasModifier(Minecraft mc, double amount) {
        Double m = modifier(mc);
        return m != null && Math.abs(m - amount) < 1e-9;
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), msg -> {
        });
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }
        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size() || --wait > 0)
            return;
        wait = 15;
        try {
            STEPS.get(index++).accept(mc);
        } catch (Throwable t) {
            check("step " + index + " no exception", false, t.toString());
            LOG.error("Shrink check crashed", t);
            index = STEPS.size() - 1;
        }
    }

    private static void check(String name, boolean ok, String why) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : ": " + why));
    }

    private static void log(String s) {
        REPORT.add(s);
        LOG.info("Shrink check: {}", s);
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-shrink-check";
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

    private ShrinkCheck() {
    }
}
