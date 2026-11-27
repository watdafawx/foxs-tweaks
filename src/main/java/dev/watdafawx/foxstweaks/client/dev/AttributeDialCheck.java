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

import dev.watdafawx.foxstweaks.attribute.AttributeDial;
import dev.watdafawx.foxstweaks.client.AttributeDialClient;
import dev.watdafawx.foxstweaks.client.AttributeDialScreen;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check of the attribute dial ({@code attribute/*}, {@code client/AttributeDialScreen}). Run by
 * {@code build.bat runAttributeDialCheck} on rig (game dir {@code run-attribute-dial/}, no extra mods needed).
 *
 * <p>Flat creative world. Gear is faked with transient +100 modifiers on block interaction range (raw 104.5, capped at
 * 64: the clamp case) and attack damage. Everything is then done through the real screen handlers (key press, typing in
 * the search box, clicking rows, dragging the slider, clicking presets and buttons) and proven on the <em>server</em>
 * player's attribute values: halving, Off, preset, gear swapped while dialled, reset, respawn, the inventory button.
 * Writes {@code foxstweaks-attributedial-check.txt} and screenshots, then quits.
 */
public final class AttributeDialCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceLocation REACH = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(Attributes.BLOCK_INTERACTION_RANGE.value());
    private static final ResourceLocation DAMAGE = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(Attributes.ATTACK_DAMAGE.value());
    private static final ResourceLocation LUCK = net.minecraft.core.registries.BuiltInRegistries.ATTRIBUTE.getKey(Attributes.LUCK.value());
    private static final ResourceLocation GEAR1 = ResourceLocation.fromNamespaceAndPath("foxstweaks", "dialcheck/gear1");
    private static final ResourceLocation GEAR2 = ResourceLocation.fromNamespaceAndPath("foxstweaks", "dialcheck/gear2");
    private static final ResourceLocation GEAR3 = ResourceLocation.fromNamespaceAndPath("foxstweaks", "dialcheck/gear3");
    private static final List<String> REPORT = new ArrayList<>();
    private static final List<Consumer<Minecraft>> STEPS = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int index;
    private static int wait = 60;

    public static void install() {
        STEPS.add(mc -> {
            server(mc, p -> {
                p.setGameMode(GameType.CREATIVE);
                p.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).addOrUpdateTransientModifier(
                        new AttributeModifier(GEAR1, 100.0, AttributeModifier.Operation.ADD_VALUE));
                p.getAttribute(Attributes.ATTACK_DAMAGE).addOrUpdateTransientModifier(
                        new AttributeModifier(GEAR2, 100.0, AttributeModifier.Operation.ADD_VALUE));
            });
            press(FoxsTweaksKeys.OPEN_ATTRIBUTE_DIAL);
        });
        STEPS.add(mc -> {
            check("key opens the dial screen", mc.screen instanceof AttributeDialScreen, "screen " + mc.screen);
            check("server table reached the client", !AttributeDialClient.entries().isEmpty(), "no entries");
            var reach = entry(REACH);
            var damage = entry(DAMAGE);
            check("reach gear is the clamped 64 (raw 104.5)", reach != null && near(reach.gear(), 64.0), "gear " + (reach == null ? null : reach.gear()));
            check("damage gear is 101", damage != null && near(damage.gear(), 101.0), "gear " + (damage == null ? null : damage.gear()));
            check("untouched ratio is 1", reach != null && reach.ratio() == 1.0, "ratio " + (reach == null ? null : reach.ratio()));
            shot("dial-1-open");
            var s = dial(mc);
            int all = s.devShown().size();
            s.devSearch("interaction");
            var shown = s.devShown();
            check("search narrows the list", shown.size() > 0 && shown.size() < all, shown.size() + " of " + all);
            check("search finds block interaction range", shown.contains(REACH), shown.toString());
            check("search hides the rest", shown.stream().allMatch(id -> id.toString().contains("interaction") || id.toString().contains("range")),
                    shown.toString());
            s.devSearch("blkrng");
            check("letters-in-order search finds reach", s.devShown().contains(REACH), s.devShown().toString());
            s.devSearch("zzzzqq");
            check("no match -> empty list", s.devShown().isEmpty(), s.devShown().toString());
            s.devSearch("interaction");
        });
        STEPS.add(mc -> {
            var s = dial(mc);
            shot("dial-2-search");
            var at = s.devRowPoint(REACH);
            check("reach row is on screen", at != null, "scrolled out");
            if (at != null)
                click(s, at[0], at[1]);
            check("click selects the row", REACH.equals(s.devSelected()), "selected " + s.devSelected());
            // Drag the real slider: press at the right end (100%), move to the middle, release.
            var from = s.devSliderPoint(1.0);
            var to = s.devSliderPoint(0.5);
            s.mouseClicked(from[0], from[1], 0);
            check("slider press starts a drag", s.devDragging(), "not dragging");
            s.mouseDragged(to[0], to[1], 0, to[0] - from[0], 0);
            s.mouseReleased(to[0], to[1], 0);
        });
        STEPS.add(mc -> {
            check("dial 50% -> server reach 32 (clamp case)", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 32.0, 0.05),
                    "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            check("other attributes untouched", near(serverValue(mc, Attributes.ATTACK_DAMAGE), 101.0), "damage " + serverValue(mc, Attributes.ATTACK_DAMAGE));
            check("ratio stored 0.50", near(ratio(mc, REACH), 0.5), "ratio " + ratio(mc, REACH));
            check("gear modifier itself untouched", sp(mc).getAttribute(Attributes.BLOCK_INTERACTION_RANGE).getModifier(GEAR1) != null, "gone");
            check("dial modifier is transient (nothing permanent to leave behind)",
                    !sp(mc).getAttribute(Attributes.BLOCK_INTERACTION_RANGE).save().toString().contains("dial/"), "saved in the attribute's NBT");
            check("client table shows the new ratio", near(entry(REACH).ratio(), 0.5), "ratio " + entry(REACH).ratio());
            shot("dial-3-halved");
            clickChip(mc, 0);
        });
        STEPS.add(mc -> {
            check("Off chip -> server reach 0", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 0.0), "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            clickChip(mc, 1);
        });
        STEPS.add(mc -> {
            check("25% chip -> server reach 16", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 16.0, 0.05), "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            var s = dial(mc);
            s.devSearch("");
            int before = s.devShown().size();
            check("unchanged attributes are listed by default", s.devShown().contains(LUCK), "luck missing");
            click(s, s.devFilterButton().getX() + 5, s.devFilterButton().getY() + 5);
            var shown = s.devShown();
            check("hide unchanged drops luck (gear == base)", !shown.contains(LUCK) && shown.size() < before, shown.size() + " of " + before);
            check("hide unchanged keeps dialled reach", shown.contains(REACH), "reach missing");
            check("hide unchanged keeps damage (gear 101 != base 1)", shown.contains(DAMAGE), "damage missing");
            shot("dial-4-hide-unchanged");
            click(s, s.devFilterButton().getX() + 5, s.devFilterButton().getY() + 5);
            check("filter off again restores luck", s.devShown().contains(LUCK), "luck missing");
            // Dial damage via search + row + 25% chip.
            s.devSearch("attack damage");
            var at = s.devRowPoint(DAMAGE);
            check("damage row visible after search", at != null, "scrolled out");
            if (at != null)
                click(s, at[0], at[1]);
            clickChip(mc, 1);
        });
        STEPS.add(mc -> {
            check("damage 25% of 101 = 25.25", near(serverValue(mc, Attributes.ATTACK_DAMAGE), 25.25, 0.01), "damage " + serverValue(mc, Attributes.ATTACK_DAMAGE));
            // New gear while dialled: the percentage must follow it.
            server(mc, p -> p.getAttribute(Attributes.ATTACK_DAMAGE).addOrUpdateTransientModifier(
                    new AttributeModifier(GEAR3, 50.0, AttributeModifier.Operation.ADD_VALUE)));
        });
        STEPS.add(mc -> {
            check("gear swapped while dialled: 25% of 151 = 37.75", near(serverValue(mc, Attributes.ATTACK_DAMAGE), 37.75, 0.01),
                    "damage " + serverValue(mc, Attributes.ATTACK_DAMAGE));
            var s = dial(mc);
            click(s, s.devResetButton().getX() + 5, s.devResetButton().getY() + 5);
        });
        STEPS.add(mc -> {
            check("reset all: reach back to the clamped 64", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 64.0), "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            check("reset all: damage back to 151", near(serverValue(mc, Attributes.ATTACK_DAMAGE), 151.0), "damage " + serverValue(mc, Attributes.ATTACK_DAMAGE));
            check("reset all: nothing stored", AttributeDial.ratios(sp(mc)).isEmpty(), AttributeDial.ratios(sp(mc)).toString());
            // Persistence across death: the fake gear is gone with the old body, the dial must not be.
            server(mc, p -> {
                AttributeDial.set(p, REACH, 0.5);
                p.server.getPlayerList().respawn(p, false, Entity.RemovalReason.KILLED);
            });
        });
        STEPS.add(mc -> {
            check("dial survives death/respawn: ratio kept", near(ratio(mc), 0.5), "ratios " + AttributeDial.ratios(sp(mc)));
            check("dial survives death/respawn: reach = creative 5.0 * 0.5", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 2.5, 0.01),
                    "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            server(mc, p -> {
                AttributeDial.resetAll(p);
                p.setGameMode(GameType.SURVIVAL); // the survival inventory is the screen the button lives on
            });
        });
        STEPS.add(mc -> {
            check("reach back to survival 4.5 after reset", near(serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE), 4.5), "reach " + serverValue(mc, Attributes.BLOCK_INTERACTION_RANGE));
            mc.setScreen(new InventoryScreen(mc.player));
        });
        STEPS.add(mc -> {
            check("inventory screen is open", mc.screen instanceof InventoryScreen, "screen " + mc.screen);
            shot("dial-5-inventory");
            var button = mc.screen == null ? null : mc.screen.children().stream()
                    .filter(c -> c instanceof AttributeDialClient.DialButton).map(c -> (AttributeDialClient.DialButton) c).findFirst().orElse(null);
            check("inventory shows the dial button", button != null, "no DialButton among children");
            if (button != null)
                click(mc.screen, button.getX() + 6, button.getY() + 6);
        });
        STEPS.add(mc -> {
            check("button opens the dial screen", mc.screen instanceof AttributeDialScreen, "screen " + mc.screen);
            server(mc, p -> {
                AttributeDial.set(p, REACH, 0.25);
                AttributeDial.set(p, ResourceLocation.withDefaultNamespace("generic.max_health"), 0.5);
            });
        });
        STEPS.add(mc -> {
            shot("dial-6-from-inventory");
            if (mc.screen instanceof AttributeDialScreen s)
                s.onClose();
        });
        STEPS.add(mc -> {
            check("closing returns to the inventory", mc.screen instanceof InventoryScreen, "screen " + mc.screen);
            finish(mc);
        });
        NeoForge.EVENT_BUS.addListener(AttributeDialCheck::onTick);
    }

    // ---- helpers ----

    private static boolean near(double a, double b) {
        return near(a, b, 1e-6);
    }

    private static boolean near(double a, double b, double eps) {
        return Math.abs(a - b) <= eps;
    }

    private static Double ratio(Minecraft mc, ResourceLocation id) {
        return AttributeDial.ratios(sp(mc)).get(id);
    }

    private static Double ratio(Minecraft mc) {
        return ratio(mc, REACH);
    }

    private static boolean near(Double a, double b) {
        return a != null && Math.abs(a - b) < 1e-6;
    }

    private static AttributeDial.Entry entry(ResourceLocation id) {
        return AttributeDialClient.entries().stream().filter(e -> e.id().equals(id)).findFirst().orElse(null);
    }

    private static AttributeDialScreen dial(Minecraft mc) {
        if (!(mc.screen instanceof AttributeDialScreen s))
            throw new IllegalStateException("dial screen not open: " + mc.screen);
        return s;
    }

    private static void click(Screen screen, double x, double y) {
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
    }

    private static void clickChip(Minecraft mc, int chip) {
        Button b = dial(mc).devChips().get(chip);
        click(mc.screen, b.getX() + b.getWidth() / 2.0, b.getY() + b.getHeight() / 2.0);
    }

    private static ServerPlayer sp(Minecraft mc) {
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static double serverValue(Minecraft mc, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) {
        AttributeInstance inst = sp(mc).getAttribute(attribute);
        return inst.getValue();
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        server.execute(() -> action.accept(server.getPlayerList().getPlayer(mc.player.getUUID())));
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

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> {
        });
    }

    private static void finish(Minecraft mc) {
        log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
        try {
            Files.write(mc.gameDirectory.toPath().resolve("foxstweaks-attributedial-check.txt"), REPORT);
        } catch (IOException ex) {
            LOG.error("Attribute dial check report", ex);
        }
        mc.stop();
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(net.minecraft.sounds.SoundSource.MASTER).set(0.0);
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
            LOG.error("Attribute dial check crashed", t);
            finish(mc);
            index = STEPS.size();
        }
    }

    private static void check(String name, boolean ok, String why) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : ": " + why));
    }

    private static void log(String s) {
        REPORT.add(s);
        LOG.info("Attribute dial check: {}", s);
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-attributedial-check";
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

    private AttributeDialCheck() {
    }
}
