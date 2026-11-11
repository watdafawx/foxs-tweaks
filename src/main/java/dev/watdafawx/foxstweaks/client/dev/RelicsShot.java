package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.relics.AutoLevelHandler;
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
            AutoLevelHandler.choose(p, p.containerMenu.containerId, SLOT, chosen);

            return BuiltInRegistries.ITEM.getKey(item) + "#" + chosen;
        }

        throw new IllegalStateException("no upgradable relic ability found");
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
