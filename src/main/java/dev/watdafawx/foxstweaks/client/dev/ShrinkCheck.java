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
 * put shrink, architectury, polylib and curios in its {@code mods/}). Flat creative world; a Shrinking Device set to -0.5 goes
 * in a backpack slot (not the hand), then the real keys are pressed: shrink on, off, survival with no power, no
 * device, the device in its Curios slot (on, off), then the settings screen (screenshot). Writes {@code run-shrink/foxstweaks-shrink-check.txt} and quits.
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
            // Device only in our Curios slot, inventory empty.
            server(mc, p -> {
                ItemStack stack = device(-0.25);
                var handler = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p)
                        .flatMap(inv -> inv.getStacksHandler("shrinking_device"));
                check("player has shrinking_device curio slot", handler.isPresent(), "no slot");
                check("device valid in that slot", handler.isPresent() && top.theillusivec4.curios.api.CuriosApi.isStackValid(
                        new top.theillusivec4.curios.api.SlotContext("shrinking_device", p, 0, false, true), stack), "tag/validator rejects it");
                handler.ifPresent(h -> h.getStacks().setStackInSlot(0, stack));
            });
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("shrunk from curio slot", hasModifier(mc, -0.25), "modifier " + modifier(mc));
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("unshrunk from curio slot", modifier(mc) == null, "modifier " + modifier(mc));
            // A pig in reach to look at, and one far away.
            server(mc, p -> {
                near = spawnPig(p, 3);
                far = spawnPig(p, 24);
            });
        });
        STEPS.add(mc -> {
            var pig = mc.level.getEntity(near);
            if (pig != null)
                mc.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, pig.getBoundingBox().getCenter());
        });
        STEPS.add(mc -> {
            check("crosshair on the pig", mc.hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity().getId() == near,
                    "hit " + mc.hitResult);
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("looked-at pig shrunk", Double.valueOf(-0.25).equals(entityModifier(mc, near)), "pig modifier " + entityModifier(mc, near));
            check("player not shrunk when targeting pig", modifier(mc) == null, "modifier " + modifier(mc));
            press(FoxsTweaksKeys.TOGGLE_SHRINK);
        });
        STEPS.add(mc -> {
            check("looked-at pig unshrunk", entityModifier(mc, near) == null, "pig modifier " + entityModifier(mc, near));
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(dev.watdafawx.foxstweaks.shrink.ShrinkNetwork.ShrinkPayload.toggle(far));
        });
        STEPS.add(mc -> {
            check("pig out of reach ignored", entityModifier(mc, far) == null, "far pig modifier " + entityModifier(mc, far));
            check("out-of-reach target not turned into self", modifier(mc) == null, "modifier " + modifier(mc));
            // Shift + key: Shrink's own menu, with a stick in the main hand and the device only in the Curios slot.
            server(mc, p -> p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(net.minecraft.world.item.Items.STICK)));
            mc.player.setXRot(-90);
        });
        STEPS.add(mc -> dev.watdafawx.foxstweaks.client.ShrinkKeyHandler.press(mc, true));
        STEPS.add(mc -> {
            ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            check("shift opens Shrink's menu on the server", p.containerMenu.getClass().getSimpleName().equals("ShrinkingDeviceContainer"),
                    "menu " + p.containerMenu.getClass().getName());
            check("Shrink's screen open on the client", mc.screen != null && mc.player.containerMenu.getClass().getSimpleName().equals("ShrinkingDeviceContainer"),
                    "screen " + mc.screen);
            check("Shrink's screen shows the curio device's scale", Double.valueOf(-0.25).equals(screenScale(mc.screen)), "SCALE " + screenScale(mc.screen));
            shot("foxstweaks-shrink-menu.png");
            // What Shrink's screen sends on a button press: its own packet, always for MAIN_HAND.
            try {
                Object packet = Class.forName("net.gigabit101.shrink.network.packets.PacketShrinkDevice")
                        .getConstructor(net.minecraft.world.InteractionHand.class, double.class)
                        .newInstance(net.minecraft.world.InteractionHand.MAIN_HAND, 0.75);
                packet.getClass().getMethod("sendToServer").invoke(packet);
            } catch (ReflectiveOperationException e) {
                check("send Shrink's packet", false, e.toString());
            }
        });
        STEPS.add(mc -> {
            check("Shrink's save lands on the curio device", Double.valueOf(0.75).equals(curioScale(mc)), "scale " + curioScale(mc));
            ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
            check("main-hand stick untouched", p.getMainHandItem().is(net.minecraft.world.item.Items.STICK) && p.getMainHandItem().getComponentsPatch().isEmpty(),
                    "main hand " + p.getMainHandItem() + " " + p.getMainHandItem().getComponentsPatch());
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
                && mc.getConnection().hasChannel(dev.watdafawx.foxstweaks.shrink.ShrinkNetwork.ShrinkPayload.TYPE), "no channel");
        server(mc, p -> {
            p.getInventory().setItem(20, device(-0.5));
        });
        press(FoxsTweaksKeys.TOGGLE_SHRINK);
    }

    private static int near;
    private static int far;

    private static int spawnPig(ServerPlayer p, int distance) {
        var pig = net.minecraft.world.entity.EntityType.PIG.create(p.serverLevel());
        pig.setNoAi(true);
        pig.moveTo(p.getX() + distance, p.getY(), p.getZ(), 0, 0);
        p.serverLevel().addFreshEntity(pig);
        return pig.getId();
    }

    private static Double entityModifier(Minecraft mc, int id) {
        var e = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID()).serverLevel().getEntity(id);
        if (!(e instanceof net.minecraft.world.entity.LivingEntity living))
            return Double.NaN;
        var m = living.getAttribute(Attributes.SCALE).getModifier(MODIFIER);
        return m == null ? null : m.amount();
    }

    /** ShrinkScreen#SCALE, found by walking the open screen's fields two levels deep. */
    private static Double screenScale(Object screen) {
        Object provider = find(screen, 0);
        if (provider == null)
            return null;
        try {
            var f = provider.getClass().getDeclaredField("SCALE");
            f.setAccessible(true);
            return f.getDouble(provider);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object find(Object o, int depth) {
        if (o == null || depth > 2)
            return null;
        if (o.getClass().getName().equals("net.gigabit101.shrink.client.ShrinkScreen"))
            return o;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (var f : c.getDeclaredFields()) {
                if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers()))
                    continue;
                try {
                    f.setAccessible(true);
                    Object hit = find(f.get(o), depth + 1);
                    if (hit != null)
                        return hit;
                } catch (RuntimeException | IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    private static Double curioScale(Minecraft mc) {
        ServerPlayer p = mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
        ItemStack stack = top.theillusivec4.curios.api.CuriosApi.getCuriosInventory(p).flatMap(inv -> inv.getStacksHandler("shrinking_device"))
                .map(h -> h.getStacks().getStackInSlot(0)).orElse(ItemStack.EMPTY);
        return stack.isEmpty() ? null : dev.watdafawx.foxstweaks.shrink.ShrinkNetwork.readScale(stack);
    }

    private static ItemStack device(double scale) {
        ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(DEVICE));
        try {
            stack.getItem().getClass().getMethod("writeScale", ItemStack.class, double.class).invoke(stack.getItem(), stack, scale);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return stack;
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
