package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Queue;

import com.mojang.logging.LogUtils;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check of the search box assist inside real recipe viewers / terminals. Run by
 * {@code build.bat runSearchEmiCheck | runSearchJeiCheck | runSearchAe2Check} (one mod per game dir
 * {@code run-search-emi|jei|ae2}). Makes a flat creative world, opens the mod's screen, types into the mod's
 * own search box and records what the mod's results did, with a screenshot at each step. EMI, JEI and AE2 are
 * reached by reflection only. Report: {@code <gameDir>/foxstweaks-search-<mode>.txt}; shots in {@code screenshots/}.
 */
public final class SearchAssistModsCheck {
    private static final org.slf4j.Logger LOG = LogUtils.getLogger();
    private static final List<String> REPORT = new ArrayList<>();
    private static final Queue<Step> STEPS = new ArrayDeque<>();
    private static String mode;
    private static boolean worldRequested;
    private static boolean planned;
    private static int wait;
    private static int loadedTicks;
    private static int failures;
    private static BlockPos chest;

    private record Step(int delay, Runnable run) {
    }

    public static void install(String m) {
        mode = m;
        NeoForge.EVENT_BUS.addListener(SearchAssistModsCheck::onTick);
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }
        if (mc.player == null || mc.level == null || !viewerReady() || ++loadedTicks < 60) {
            return;
        }
        if (!planned) {
            planned = true;
            plan(mc);
        }
        if (wait-- > 0) {
            return;
        }
        Step s = STEPS.poll();
        if (s == null) {
            return;
        }
        wait = s.delay();
        try {
            s.run().run();
        } catch (Throwable t) {
            failures++;
            REPORT.add("FAIL step threw " + t);
            LOG.error("search check step failed", t);
        }
    }

    private static boolean viewerReady() {
        try {
            return switch (mode) {
                case "emi" -> (boolean) Class.forName("dev.emi.emi.runtime.EmiReloadManager").getMethod("isLoaded").invoke(null);
                case "jei" -> ((Optional<?>) Class.forName("mezz.jei.common.Internal").getMethod("getOptionalJeiRuntime").invoke(null)).isPresent();
                default -> true;
            };
        } catch (ReflectiveOperationException ex) {
            return false;
        }
    }

    private static void plan(Minecraft mc) {
        if (mode.equals("ae2")) {
            // An ME Terminal part: give it, place it on the floor under the player (creates the cable bus), then open it empty-handed.
            chest = mc.player.blockPosition().below().east(2);
            STEPS.add(new Step(20, () -> mc.player.connection.sendCommand("give @s ae2:terminal")));
            STEPS.add(new Step(20, () -> {
                mc.player.getInventory().selected = 0;
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(chest).add(0, 0.5, 0), Direction.UP, chest, false));
            }));
            STEPS.add(new Step(20, () -> {
                mc.player.getInventory().selected = 5;
                REPORT.add("terminal part placed? host=" + mc.level.getBlockState(chest.above()).getBlock());
                BlockPos host = chest.above();
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, new BlockHitResult(new Vec3(host.getX() + 0.5, host.getY() + 0.1, host.getZ() + 0.5), Direction.UP, host, false));
            }));
        } else {
            STEPS.add(new Step(40, () -> mc.setScreen(new InventoryScreen(mc.player))));
        }
        STEPS.add(new Step(30, () -> {
            REPORT.add("screen: " + (mc.screen == null ? null : mc.screen.getClass().getName()));
            EditBox box = box(mc);
            REPORT.add("box: " + (box == null ? "NOT FOUND" : box.getClass().getName()));
            if (box == null) {
                failures++;
                return;
            }
            box.setFocused(true);
            box.setValue("diamnod");
        }));
        STEPS.add(new Step(60, () -> {
            shot(mc, mode + "-1-misspelled");
            String seen = observe(mc);
            REPORT.add("typed 'diamnod' -> " + seen + " did=" + field(box(mc), "foxstweaks$didYouMean") + " suggestion=" + field(box(mc), "suggestion"));
            // A bare AE2 terminal has no network, so it never has results: there the proof is the text its repo was given.
            check("misspelled search reaches the mod corrected", mode.equals("ae2") ? seen.contains("repoSearch='diamond'") : seen.contains("RESULTS"), seen);
        }));
        STEPS.add(new Step(10, () -> box(mc).setValue("iron ing")));
        STEPS.add(new Step(30, () -> {
            EditBox box = box(mc);
            REPORT.add("typed 'iron ing' -> suggestion=" + field(box, "suggestion") + " ours=" + field(box, "foxstweaks$ours")
                    + " did=" + field(box, "foxstweaks$didYouMean"));
            shot(mc, mode + "-2-autocomplete");
        }));
        STEPS.add(new Step(10, () -> {
            EditBox box = box(mc);
            boolean handled = box.keyPressed(258, 0, 0);
            REPORT.add("Tab -> handled=" + handled + " value='" + box.getValue() + "'");
            check("Tab accepts completion", box.getValue().equals("iron ingot"), box.getValue());
        }));
        STEPS.add(new Step(5, () -> {
            REPORT.add(failures == 0 ? "ALL PASS" : failures + " FAILED");
            try {
                Files.write(Path.of(mc.gameDirectory.getPath(), "foxstweaks-search-" + mode + ".txt"), REPORT);
            } catch (IOException ignored) {
            }
            mc.stop();
        }));
    }

    // ---- per-mod access (reflection only) ----

    private static EditBox box(Minecraft mc) {
        try {
            switch (mode) {
                case "emi": {
                    Field f = Class.forName("dev.emi.emi.screen.EmiScreenManager").getField("search");
                    return (EditBox) f.get(null);
                }
                case "jei": {
                    Object runtime = Class.forName("mezz.jei.common.Internal").getMethod("getJeiRuntime").invoke(null);
                    Object overlay = runtime.getClass().getMethod("getIngredientListOverlay").invoke(runtime);
                    return (EditBox) find(overlay, EditBox.class, 4);
                }
                default:
                    return (EditBox) find(mc.screen, EditBox.class, 3);
            }
        } catch (ReflectiveOperationException ex) {
            REPORT.add("box lookup failed: " + ex);
            return null;
        }
    }

    /** What the mod's own results look like after the typing: "RESULTS n (...)" or "no results". */
    private static String observe(Minecraft mc) {
        try {
            switch (mode) {
                case "emi": {
                    Class<?> search = Class.forName("dev.emi.emi.search.EmiSearch");
                    Collection<?> stacks = (Collection<?>) search.getField("stacks").get(null);
                    String text = (String) Class.forName("dev.emi.emi.api.EmiApi").getMethod("getSearchText").invoke(null);
                    return (stacks.isEmpty() ? "no results" : "RESULTS " + stacks.size()) + " box='" + text + "'";
                }
                case "jei": {
                    Object runtime = Class.forName("mezz.jei.common.Internal").getMethod("getJeiRuntime").invoke(null);
                    Object filter = runtime.getClass().getMethod("getIngredientFilter").invoke(runtime);
                    String text = (String) filter.getClass().getMethod("getFilterText").invoke(filter);
                    List<?> items = (List<?>) filter.getClass().getMethod("getFilteredItemStacks").invoke(filter);
                    return (items.isEmpty() ? "no results" : "RESULTS " + items.size()) + " jeiFilterText='" + text + "'";
                }
                default: {
                    Field rf = null;
                    for (Class<?> c = mc.screen.getClass(); c != null && rf == null; c = c.getSuperclass()) {
                        try {
                            rf = c.getDeclaredField("repo");
                        } catch (NoSuchFieldException ignored) {
                        }
                    }
                    if (rf == null) {
                        return "no repo field on " + mc.screen.getClass().getName();
                    }
                    rf.setAccessible(true);
                    Object repo = rf.get(mc.screen);
                    Method size = repo.getClass().getMethod("size");
                    Method str = repo.getClass().getMethod("getSearchString");
                    int n = (int) size.invoke(repo);
                    return (n == 0 ? "no results" : "RESULTS " + n) + " repoSearch='" + str.invoke(repo) + "'";
                }
            }
        } catch (ReflectiveOperationException ex) {
            return "observe failed: " + ex;
        }
    }

    // ---- helpers ----

    private static Object find(Object root, Class<?> type, int depth) {
        var seen = new IdentityHashMap<Object, Boolean>();
        Queue<Object[]> q = new ArrayDeque<>();
        q.add(new Object[] {root, 0});
        while (!q.isEmpty()) {
            Object[] cur = q.poll();
            Object o = cur[0];
            int d = (int) cur[1];
            if (o == null || seen.put(o, true) != null) {
                continue;
            }
            if (type.isInstance(o)) {
                return o;
            }
            if (d >= depth || o.getClass().getName().startsWith("java.")) {
                continue;
            }
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().isPrimitive() || java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof Collection<?> col) {
                            for (Object x : col) {
                                q.add(new Object[] {x, d + 1});
                            }
                        } else if (v != null) {
                            q.add(new Object[] {v, d + 1});
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return null;
    }

    private static Object field(EditBox box, String name) {
        try {
            Field f = EditBox.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(box);
        } catch (ReflectiveOperationException ex) {
            return "?" + ex;
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), c -> LOG.info("shot {}", name));
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }
        REPORT.add((ok ? "PASS " : "FAIL ") + name + (ok ? "" : " - " + detail));
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-search-" + mode;
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

    private SearchAssistModsCheck() {
    }
}
