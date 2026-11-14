package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check for the Kaleidoscope Grilling skewer cache ({@code GrillingDataManagerMixin},
 * {@code SkewerRecipesMixin}). Run by {@code build.bat runGrillingCheck} (game dir {@code run-grilling/}, put
 * kaleidoscope_grilling and kaleidoscope_cookery in its {@code mods/}). Creates a flat world, checks that the caches
 * are live, give the same answers as the original code for every item, rebuild when the skewer data is replaced,
 * times the per-item lookup both ways, writes {@code run-grilling/foxstweaks-grilling-check.txt} and quits. Reaches
 * Grilling only through reflection, so it is no compile dependency. Never installed in a shipped jar.
 */
public final class GrillingCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String REPORT_FILE = "foxstweaks-grilling-check.txt";
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static boolean done;
    private static int loadedTicks;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(GrillingCheck::onTick);
    }

    private static void onTick(ClientTickEvent.Post e) {
        if (done) {
            return;
        }

        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || ++loadedTicks < 40) {
            return;
        }

        done = true;
        try {
            run();
        } catch (Throwable t) {
            check("no exception", false, t.toString());
            LOG.error("Grilling check crashed", t);
        } finally {
            Config.GRILLING_SKEWER_CACHE.set(true);
        }

        log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
        try {
            Files.write(mc.gameDirectory.toPath().resolve(REPORT_FILE), REPORT);
        } catch (IOException ex) {
            LOG.error("Grilling check report", ex);
        }

        mc.stop();
    }

    private static void run() throws Exception {
        Class<?> data = Class.forName("cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager");
        Class<?> recipesClass = Class.forName("cn.breezeth.kaleidoscope_grilling.skewer.SkewerRecipes");
        Method display = data.getMethod("skewersForDisplay");
        Method recipes = recipesClass.getDeclaredMethod("recipes");
        recipes.setAccessible(true);
        Method generated = recipesClass.getMethod("usesGeneratedModel", ItemStack.class);
        Method rawSkewer = recipesClass.getMethod("isRawSkewer", ItemStack.class);
        Field skewers = data.getDeclaredField("SKEWERS");
        skewers.setAccessible(true);

        // 1. caches are live: the original builds a new map and list on every call
        Config.GRILLING_SKEWER_CACHE.set(true);
        Object map1 = display.invoke(null);
        Object list1 = recipes.invoke(null);
        check("display map cached", display.invoke(null) == map1, "new instance per call");
        check("recipe list cached", recipes.invoke(null) == list1, "new instance per call");
        check("recipes found", !((List<?>) list1).isEmpty(), "empty recipe list - is Grilling's data loaded?");
        log("info: " + ((Map<?, ?>) map1).size() + " skewers, " + ((List<?>) list1).size() + " recipes");

        Config.GRILLING_SKEWER_CACHE.set(false);
        check("uncached display map is fresh", display.invoke(null) != display.invoke(null), "toggle off still cached");
        check("uncached map equal", display.invoke(null).equals(map1), "contents differ");

        // 2. same answers as the original for every item, twice cached (second pass hits the per-item map)
        List<ItemStack> stacks = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            stacks.add(new ItemStack(item));
        }
        Map<Item, Boolean> expectedGenerated = new HashMap<>();
        Map<Item, Boolean> expectedRaw = new HashMap<>();
        for (ItemStack s : stacks) {
            expectedGenerated.put(s.getItem(), (Boolean) generated.invoke(null, s));
            expectedRaw.put(s.getItem(), (Boolean) rawSkewer.invoke(null, s));
        }
        Config.GRILLING_SKEWER_CACHE.set(true);
        int mismatches = 0;
        int generatedCount = 0;
        int rawCount = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (ItemStack s : stacks) {
                boolean g = (Boolean) generated.invoke(null, s);
                boolean r = (Boolean) rawSkewer.invoke(null, s);
                if (g != expectedGenerated.get(s.getItem()) || r != expectedRaw.get(s.getItem())) {
                    mismatches++;
                }
                if (pass == 0 && g) {
                    generatedCount++;
                }
                if (pass == 0 && r) {
                    rawCount++;
                }
            }
        }
        check("same answers for all " + stacks.size() + " items", mismatches == 0, mismatches + " mismatches");
        check("raw skewers recognised", rawCount > 0, "no item is a raw skewer");
        log("info: " + generatedCount + " items use the generated model, " + rawCount + " raw skewers");

        // 3. replacing the datapack map (what a reload does) rebuilds both caches
        Object old = skewers.get(null);
        try {
            skewers.set(null, Map.copyOf(new HashMap<>((Map<?, ?>) old)));
            Object map2 = display.invoke(null);
            Object list2 = recipes.invoke(null);
            check("reload rebuilds display map", map2 != map1 && map2.equals(map1), "same instance after replace");
            check("reload rebuilds recipe list", list2 != list1, "same instance after replace");
        } finally {
            skewers.set(null, old);
        }

        // 4. cost of one lookup, as ItemRenderer.getModel pays it for every item drawn
        ItemStack probe = stacks.stream().filter(s -> expectedRaw.get(s.getItem())).findFirst().orElse(stacks.get(0));
        double off = time(generated, probe, false);
        double on = time(generated, probe, true);
        log(String.format("info: usesGeneratedModel %.0f ns uncached, %.0f ns cached (%.0fx)", off, on, off / on));
        check("cached lookup faster", on < off, "cached " + on + " ns vs " + off + " ns");
    }

    private static double time(Method m, ItemStack stack, boolean cached) throws Exception {
        Config.GRILLING_SKEWER_CACHE.set(cached);
        int n = 20_000;
        for (int i = 0; i < n; i++) {
            m.invoke(null, stack);
        }
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            m.invoke(null, stack);
        }
        return (System.nanoTime() - start) / (double) n;
    }

    private static void check(String name, boolean ok, String why) {
        if (ok) {
            log("PASS " + name);
        } else {
            failures++;
            log("FAIL " + name + ": " + why);
        }
    }

    private static void log(String s) {
        REPORT.add(s);
        LOG.info("Grilling check: {}", s);
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-grilling-check";
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

    private GrillingCheck() {
    }
}
