package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.relics.AutoLevelHandler;
import dev.watdafawx.foxstweaks.relics.AutoLevelPayload;
import dev.watdafawx.foxstweaks.relics.AutoLevelSetting;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.utils.EntityUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
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
import java.util.List;
import java.util.function.Function;

/**
 * Dev-only: flat world, then runs the Relics auto-level scenarios against the real server tick and
 * writes {@code run-relics/foxstweaks-relics-check.txt}. {@code build.bat runRelicsShot}, game dir
 * {@code run-relics/} (Relics, Curios, OctoLib, Architectury in {@code mods/}).
 */
public final class RelicsShot {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int SLOT = 9;

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static String ability;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(RelicsShot::onTick);
        scenarios();
    }

    // ---------------------------------------------------------------------------------------------

    private static void scenarios() {
        // A: max an ability, then the relic ranks up.
        add(60, () -> {
            Config.RELIC_AUTO_RANKUP.set(false);
            Config.RELIC_AUTO_LEVEL_MIN_XP.set(0);
            ability = onServer(p -> give(p, 100_000, true));
            log("A: relic gets ability '" + ability + "' chosen, max level, 100000 xp, rank-up OFF");
        });
        add(150, () -> {
            var s = onServer(RelicsShot::state);
            check("A1 ability reached max level", s.abilityMax, s.toString());
            check("A1 rank-up held back while off", s.rank == 0, s.toString());
            Config.RELIC_AUTO_RANKUP.set(true);
        });
        add(100, () -> {
            var s = onServer(RelicsShot::state);
            check("A2 relic ranked up to 1", s.rank == 1, s.toString());
            check("A2 ability level reset by rank-up", s.abilityLevel == 0, s.toString());
            check("A2 relic level reset by rank-up", s.relicLevel == 0, s.toString());
            shot("relicsshot-A-after-rankup.png");
        });

        // B: min xp. Costs are 50, 100, 150... so 500 xp with a 400 floor allows exactly one upgrade.
        add(20, () -> {
            Config.RELIC_AUTO_RANKUP.set(false);
            Config.RELIC_AUTO_LEVEL_MIN_XP.set(400);
            ability = onServer(p -> give(p, 500, false));
            log("B: 500 xp, floor 400, points 10, level 10");
        });
        add(120, () -> {
            var s = onServer(RelicsShot::state);
            check("B1 floor respected (xp >= 400)", s.xp >= 400, s.toString());
            check("B1 one upgrade bought (level 1)", s.abilityLevel == 1, s.toString());
            check("B1 points left to spend", s.points > 0, s.toString());
            Config.RELIC_AUTO_LEVEL_MIN_XP.set(0);
        });
        add(120, () -> {
            var s = onServer(RelicsShot::state);
            check("B2 floor off: keeps upgrading", s.abilityLevel >= 2, s.toString());
            check("B2 floor off: xp spent below 400", s.xp < 400, s.toString());
        });
        // D: two marked abilities, in order. Points for 3 upgrades: the first gets all 3, the second none.
        add(20, () -> {
            Config.RELIC_AUTO_RANKUP.set(false);
            Config.RELIC_AUTO_LEVEL_MIN_XP.set(0);
            log("D: " + onServer(p -> giveMulti(p, 3, false, true)));
        });
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("D1 in order: first ability took all 3 points", m.la == 3 && m.lb == 0, m.toString());
            check("D1 in order: all 3 points spent", m.points == 0, m.toString());
            // Plenty of points now: the first runs to max, then the second gets the rest.
            onServer(p -> {
                setPoints(p, req * 200);
                return null;
            });
        });
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("D2 first ability reached max", m.aMax, m.toString());
            check("D2 second ability only starts after that", m.lb > 0, m.toString());
        });

        // E: two marked abilities, round robin. Points for 4 upgrades: 2 each.
        add(20, () -> log("E: " + onServer(p -> giveMulti(p, 4, true, true))));
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("E1 round robin: 2 and 2", m.la == 2 && m.lb == 2, m.toString());
            check("E1 round robin: all 4 points spent", m.points == 0, m.toString());
            onServer(p -> {
                var s = AutoLevelSetting.of(p.getInventory().getItem(SLOT));
                check("E2 mode saved on the relic", s.roundRobin() && s.abilities().size() == 2, s.toString());
                // 3 more points: 2/1 split, never 3/0.
                setPoints(p, req * 3);
                return null;
            });
        });
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("E3 three more upgrades stay even (|a-b| <= 1)", m.la + m.lb == 7 && Math.abs(m.la - m.lb) <= 1, m.toString());
            // Toggle the first ability off: the second keeps leveling alone.
            onServer(p -> {
                AutoLevelHandler.choose(p, p.containerMenu.containerId, SLOT, pairA, AutoLevelPayload.TOGGLE);
                setPoints(p, req);
                return null;
            });
        });
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("E4 unmarked ability stopped (still 3 or 4)", m.la + m.lb == 8 && m.lb == 4, m.toString());
        });

        // F: both marked and maxed, rank-up on: the relic ranks up.
        add(20, () -> {
            Config.RELIC_AUTO_RANKUP.set(true);
            log("F: " + onServer(p -> giveMulti(p, 5000, false, true)));
        });
        add(200, () -> {
            var m = onServer(RelicsShot::multi);
            check("F1 both abilities maxed then relic ranked up", m.rank == 1, m.toString());
            Config.RELIC_AUTO_RANKUP.set(false);
        });

        // G: a relic marked before this feature (old single-id component) keeps leveling.
        add(20, () -> log("G: " + onServer(p -> {
            giveMulti(p, 3, false, true);
            var stack = p.getInventory().getItem(SLOT);

            AutoLevelSetting.EMPTY.applyTo(stack);
            stack.set(dev.watdafawx.foxstweaks.FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get(), pairA);
            setPoints(p, req * 2);
            return "legacy id " + pairA + " set, 2 points";
        })));
        add(100, () -> {
            var m = onServer(RelicsShot::multi);
            check("G1 legacy single id levels up", m.la == 2 && m.lb == 0, m.toString());
        });

        // H: the real ability screen. Clicks go through Screen#mouseClicked like a mouse press would.
        add(20, () -> log("H: " + onServer(p -> giveMulti(p, 0, false, false))));
        add(20, () -> {
            var mc = Minecraft.getInstance();
            var screen = new it.hurts.sskirillss.relics.client.screen.description.ability.AbilityDescriptionScreen(
                    mc.player, mc.player.containerMenu.containerId, SLOT, null);

            mc.setScreen(screen);
            screen.setSelectedAbility(pairA);
        });
        add(20, () -> {
            var w = starWidget();

            check("H0 star widget is on the ability screen", w != null, "no AutoLevelWidget in screen children");
            check("H0 star is clickable for an upgradable ability", w != null && !w.isLocked(), "locked");
            shot("relicsshot-H0-unmarked.png");
            click(0);
        });
        add(15, () -> {
            var s = setting();
            check("H1 left click marks the selected ability", s.abilities().equals(List.of(pairA)) && !s.roundRobin(), s.toString());
            shot("relicsshot-H1-marked.png");
            ((it.hurts.sskirillss.relics.client.screen.description.ability.AbilityDescriptionScreen) Minecraft.getInstance().screen)
                    .setSelectedAbility(pairB);
        });
        add(5, () -> click(0));
        add(15, () -> {
            var s = setting();
            check("H2 second ability marked too, order kept", s.abilities().equals(List.of(pairA, pairB)), s.toString());
            click(1);
        });
        add(15, () -> {
            var s = setting();
            check("H3 right click switches to round robin", s.roundRobin() && s.abilities().size() == 2, s.toString());
            shot("relicsshot-H3-round-robin.png");
            hover();
        });
        add(20, () -> {
            shot("relicsshot-H3-tooltip-round-robin.png");
            click(1);
        });
        add(15, () -> {
            var s = setting();
            check("H4 right click again switches back", !s.roundRobin() && s.abilities().size() == 2, s.toString());
            hover();
        });
        add(20, () -> {
            shot("relicsshot-H4-tooltip-in-order.png");
            click(0);
        });
        add(15, () -> {
            var s = setting();
            check("H5 left click on a marked ability unmarks it", s.abilities().equals(List.of(pairA)), s.toString());
            Minecraft.getInstance().setScreen(null);
            onServer(p -> {
                AutoLevelHandler.choose(p, p.containerMenu.containerId, SLOT, "", AutoLevelPayload.CLEAR);
                var c = AutoLevelSetting.of(p.getInventory().getItem(SLOT));

                check("H6 clear empties the setting", c.abilities().isEmpty(), c.toString());
                return null;
            });
        });

        // C: tooltip hint. Inventory open, cursor on the relic, screenshot.
        add(20, () -> {
            Config.RELIC_AUTO_LEVEL_MIN_XP.set(0);
            onServer(p -> give(p, 0, false));
        });
        add(20, () -> {
            var mc = Minecraft.getInstance();
            mc.setScreen(new net.minecraft.client.gui.screens.inventory.InventoryScreen(mc.player));
        });
        add(10, () -> {
            var mc = Minecraft.getInstance();
            var w = mc.getWindow();
            var slot = mc.player.containerMenu.slots.get(SLOT);
            double gx = (w.getGuiScaledWidth() - 176) / 2.0 + slot.x + 8;
            double gy = (w.getGuiScaledHeight() - 166) / 2.0 + slot.y + 8;
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(w.getWindow(), gx * w.getGuiScale(), gy * w.getGuiScale());
        });
        add(20, () -> shot("tooltip-hint.png"));
        add(20, () -> {
            var mc = Minecraft.getInstance();
            var hint = net.minecraft.network.chat.Component.translatable("foxstweaks.relic.right_click_open").getString();
            var lines = mc.player.containerMenu.slots.get(SLOT).getItem()
                    .getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), mc.player, net.minecraft.world.item.TooltipFlag.NORMAL)
                    .stream().map(c -> c.getString()).toList();
            log("C tooltip lines: " + lines + " (event hook adds hint only via the screen path)");
            mc.setScreen(null);
        });
        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-relics-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------------------------------------

    private record State(int rank, int relicLevel, int points, int abilityLevel, boolean abilityMax, long xp) {
    }

    /** Clears the player, gives a relic with its first upgradable ability chosen. Returns that ability. */
    private static String give(ServerPlayer p, int xp, boolean maxRelicLevel) {
        p.getInventory().clearContent();
        p.giveExperiencePoints(-(int) EntityUtils.getPlayerTotalExperience(p));
        p.giveExperiencePoints(xp);

        for (var item : BuiltInRegistries.ITEM) {
            if (!(item instanceof IRelicItem relic))
                continue;

            var stack = new ItemStack(item);
            var data = relic.getRelicData(p, stack);

            if (data.getTemplate() == null)
                continue;

            String chosen = null;

            for (String id : data.getAbilitiesData().getAbilityIDs()) {
                var a = data.getAbilitiesData().getAbilityData(id);

                if (a != null && a.getTemplate() != null && a.canBeUpgraded()) {
                    chosen = id;
                    break;
                }
            }

            if (chosen == null)
                continue;

            var leveling = data.getLevelingData();

            leveling.setLevel(maxRelicLevel ? data.calculateMaxLevel() : 10);
            leveling.setPoints(maxRelicLevel ? 5000 : 10);
            p.getInventory().setItem(SLOT, stack);

            // The real packet path: marks the ability, then the normal sweep does the rest.
            AutoLevelHandler.choose(p, p.containerMenu.containerId, SLOT, chosen, dev.watdafawx.foxstweaks.relics.AutoLevelPayload.TOGGLE);

            return BuiltInRegistries.ITEM.getKey(item) + "#" + chosen;
        }

        throw new IllegalStateException("no upgradable relic ability found");
    }

    private static String pairA;
    private static String pairB;
    private static int req;

    private record Multi(int rank, int points, int la, int lb, boolean aMax, long xp) {
    }

    /**
     * Gives a relic with two upgradable abilities, marks both through the real packet path (no XP yet,
     * so nothing is bought while marking), sets points for {@code upgrades} upgrades, then adds XP.
     */
    private static String giveMulti(ServerPlayer p, int upgrades, boolean roundRobin, boolean mark) {
        p.getInventory().clearContent();
        p.giveExperiencePoints(-(int) EntityUtils.getPlayerTotalExperience(p));

        for (var item : BuiltInRegistries.ITEM) {
            if (!(item instanceof IRelicItem relic))
                continue;

            var stack = new ItemStack(item);
            var data = relic.getRelicData(p, stack);

            if (data.getTemplate() == null)
                continue;

            data.getLevelingData().setLevel(data.calculateMaxLevel());
            data.getLevelingData().setPoints(5000);

            List<String> ids = new ArrayList<>();
            int most = 1;

            for (String id : data.getAbilitiesData().getAbilityIDs()) {
                var a = data.getAbilitiesData().getAbilityData(id);

                if (a == null || a.getTemplate() == null || !a.canBeUpgraded())
                    continue;

                // Later abilities start locked; unlock like the five card clicks would.
                a.getLockData().setUnlocks(a.getLockData().getMaxUnlocks());

                if (a.mayUpgrade()) {
                    ids.add(id);
                    most = Math.max(most, a.getTemplate().getRequiredPoints());
                }
            }

            if (ids.size() < 2)
                continue;

            pairA = ids.get(0);
            pairB = ids.get(1);
            req = most;

            data.getLevelingData().setPoints(req * upgrades);
            p.getInventory().setItem(SLOT, stack);

            int menu = p.containerMenu.containerId;

            if (mark) {
                AutoLevelHandler.choose(p, menu, SLOT, pairA, AutoLevelPayload.TOGGLE);
                AutoLevelHandler.choose(p, menu, SLOT, pairB, AutoLevelPayload.TOGGLE);
            }

            if (mark && roundRobin)
                AutoLevelHandler.choose(p, menu, SLOT, "", AutoLevelPayload.CYCLE_MODE);

            p.giveExperiencePoints(1_000_000);

            return BuiltInRegistries.ITEM.getKey(item) + " " + pairA + "+" + pairB + ", " + req + " point(s) per upgrade, "
                    + upgrades + " upgrade(s), " + (roundRobin ? "round robin" : "in order");
        }

        throw new IllegalStateException("no relic with two upgradable abilities found");
    }

    private static void setPoints(ServerPlayer p, int points) {
        var stack = p.getInventory().getItem(SLOT);

        ((IRelicItem) stack.getItem()).getRelicData(p, stack).getLevelingData().setPoints(points);
    }

    private static Multi multi(ServerPlayer p) {
        var stack = p.getInventory().getItem(SLOT);
        var data = ((IRelicItem) stack.getItem()).getRelicData(p, stack);
        var a = data.getAbilitiesData().getAbilityData(pairA);
        var b = data.getAbilitiesData().getAbilityData(pairB);

        return new Multi(data.getLevelingData().getRank(), data.getLevelingData().getPoints(), a.getLevel(), b.getLevel(),
                a.isMaxLevel(), EntityUtils.getPlayerTotalExperience(p));
    }

    private static dev.watdafawx.foxstweaks.relics.client.AutoLevelWidget starWidget() {
        for (var child : Minecraft.getInstance().screen.children())
            if (child instanceof dev.watdafawx.foxstweaks.relics.client.AutoLevelWidget w)
                return w;

        return null;
    }

    /** A mouse press on the star, through the screen like the real thing. */
    private static void click(int button) {
        var w = starWidget();

        if (w != null)
            Minecraft.getInstance().screen.mouseClicked(w.getX() + 7, w.getY() + 7, button);
    }

    /** Puts the real cursor on the star so the screen draws its hover tooltip. */
    private static void hover() {
        var w = starWidget();
        var window = Minecraft.getInstance().getWindow();

        if (w != null)
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(window.getWindow(), (w.getX() + 7) * window.getGuiScale(), (w.getY() + 7) * window.getGuiScale());
    }

    private static AutoLevelSetting setting() {
        return onServer(p -> AutoLevelSetting.of(p.getInventory().getItem(SLOT)));
    }

    private static State state(ServerPlayer p) {
        var stack = p.getInventory().getItem(SLOT);
        var relic = (IRelicItem) stack.getItem();
        var data = relic.getRelicData(p, stack);
        var a = data.getAbilitiesData().getAbilityData(ability.substring(ability.indexOf('#') + 1));

        return new State(data.getLevelingData().getRank(), data.getLevelingData().getLevel(), data.getLevelingData().getPoints(),
                a.getLevel(), a.isMaxLevel(), EntityUtils.getPlayerTotalExperience(p));
    }

    // ---------------------------------------------------------------------------------------------

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();

        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("RelicsCheck {}", line);
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
            LOG.error("RelicsCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }

        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-relicsshot";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);

        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }

        var settings = new LevelSettings(world, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT);

        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private RelicsShot() {
    }
}
