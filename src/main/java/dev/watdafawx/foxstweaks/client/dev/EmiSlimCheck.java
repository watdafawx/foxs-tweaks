package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.emi.emi.api.EmiApi;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.emi.emi.runtime.EmiReloadManager;
import dev.watdafawx.foxstweaks.emi.EmiSlim;
import dev.watdafawx.foxstweaks.emi.SharedTagList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only check for EMI Slim (the {@code EmiSlim*Mixin}s and {@code emi/}), ported from the standalone mod's
 * self-test. Run by {@code build.bat runEmiSlimCheck} (game dir {@code run-emi/}, EMI jar in its {@code mods/}).
 * Creates a flat world, waits for EMI to load, checks sharing and that changes stay with their own recipe, writes
 * {@code run-emi/foxstweaks-emislim-check.txt} and quits. Never installed in a shipped jar.
 */
public final class EmiSlimCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String REPORT_FILE = "foxstweaks-emislim-check.txt";
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static boolean done;
    private static int loadedTicks;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(EmiSlimCheck::onTick);
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

        if (mc.player == null || !EmiReloadManager.isLoaded() || ++loadedTicks < 40) {
            return;
        }

        done = true;
        try {
            run();
        } catch (Throwable t) {
            check("no exception", false, t.toString());
            LOG.error("EMI Slim check crashed", t);
        }

        log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
        try {
            Files.write(mc.gameDirectory.toPath().resolve(REPORT_FILE), REPORT);
        } catch (IOException ex) {
            LOG.error("EMI Slim check report", ex);
        }

        mc.stop();
    }

    private static void run() {
        // 1. same tag -> same list
        var a = new TagEmiIngredient(ItemTags.PLANKS, 1);
        var b = new TagEmiIngredient(ItemTags.PLANKS, 4);
        check("tag list shared", a.getEmiStacks() == b.getEmiStacks() && a.getEmiStacks() instanceof SharedTagList,
                "a=" + a.getEmiStacks().getClass().getSimpleName() + " same=" + (a.getEmiStacks() == b.getEmiStacks()));
        check("copy() shares too", a.copy().getEmiStacks() == a.getEmiStacks(), "copy has own list");
        check("planks list non-empty", a.getEmiStacks().size() > 1, "size " + a.getEmiStacks().size());

        // 2. change through owner: only that ingredient sees it (same pattern as EmiShapedRecipe.setRemainders)
        var stick = EmiStack.of(Items.STICK);
        var as = a.getEmiStacks();
        for (var i = 0; i < 2; i++) {
            as.get(i).setRemainder(stick);
        }

        check("owner sees remainder", a.getEmiStacks().get(0).getRemainder() == stick, "got " + a.getEmiStacks().get(0).getRemainder());
        check("second change reaches owner", a.getEmiStacks().get(1).getRemainder() == stick, "slot1 " + a.getEmiStacks().get(1).getRemainder());
        check("other ingredient unaffected", b.getEmiStacks().get(0).getRemainder().isEmpty() && b.getEmiStacks().get(1).getRemainder().isEmpty(),
                "b remainder " + b.getEmiStacks().get(0).getRemainder());
        var c = new TagEmiIngredient(ItemTags.PLANKS, 1);
        check("new ingredient unaffected", c.getEmiStacks().get(0).getRemainder().isEmpty(), "c remainder " + c.getEmiStacks().get(0).getRemainder());
        check("other slots of owner still equal", a.getEmiStacks().get(2).isEqual(c.getEmiStacks().get(2)), "slot2 differs");
        check("b and c still share", b.getEmiStacks() == c.getEmiStacks(), "split");

        // 3. stack taken out and changed by its holder: holder sees it, tag list does not
        var d = new TagEmiIngredient(ItemTags.LOGS, 1);
        var picked = d.getEmiStacks().get(2);
        new TagEmiIngredient(ItemTags.WOOL, 1).getEmiStacks(); // owner tracking now points elsewhere
        picked.setAmount(7);
        check("picked stack changed", picked.getAmount() == 7, "amount " + picked.getAmount());
        check("tag list unchanged", new TagEmiIngredient(ItemTags.LOGS, 1).getEmiStacks().get(2).getAmount() == 1, "shared slot changed");

        // 4. ListEmiIngredient: change via list reaches the right child, other lists rebuilt
        var w1 = new TagEmiIngredient(ItemTags.WOOL, 1);
        var w2 = new TagEmiIngredient(ItemTags.WOOL, 1);
        var l1 = new ListEmiIngredient(List.of(w1, EmiStack.of(Items.DIRT)), 1);
        var l2 = new ListEmiIngredient(List.of(w2, EmiStack.of(Items.DIRT)), 1);
        l1.getEmiStacks().get(0).setRemainder(stick);
        check("list owner sees change", l1.getEmiStacks().get(0).getRemainder() == stick && w1.getEmiStacks().get(0).getRemainder() == stick,
                "l1 " + l1.getEmiStacks().get(0).getRemainder() + " w1 " + w1.getEmiStacks().get(0).getRemainder());
        check("other list unaffected", l2.getEmiStacks().get(0).getRemainder().isEmpty(), "l2 " + l2.getEmiStacks().get(0).getRemainder());
        check("other list matches its child", l2.getEmiStacks().get(0) == w2.getEmiStacks().get(0), "fullList stale");

        // 5. patch interning
        var n1 = new ItemStack(Items.DIAMOND_SWORD);
        n1.set(DataComponents.CUSTOM_NAME, Component.literal("emislim"));
        var n2 = new ItemStack(Items.DIAMOND_SWORD);
        n2.set(DataComponents.CUSTOM_NAME, Component.literal("emislim"));
        DataComponentPatch p1 = EmiStack.of(n1).getComponentChanges();
        DataComponentPatch p2 = EmiStack.of(n2).getComponentChanges();
        check("equal patches shared", p1 == p2, "different objects");
        var n3 = new ItemStack(Items.DIAMOND_SWORD);
        n3.set(DataComponents.CUSTOM_NAME, Component.literal("other"));
        check("different patches kept apart", EmiStack.of(n3).getComponentChanges() != p1 && !EmiStack.of(n3).getComponentChanges().equals(p1), "merged");
        check("stacks not shared", EmiStack.of(n1) != EmiStack.of(n1), "same stack object");

        // 6. real recipes: how many distinct tag lists
        Set<List<EmiStack>> lists = Collections.newSetFromMap(new IdentityHashMap<>());
        var tags = 0;
        for (EmiRecipe r : EmiApi.getRecipeManager().getRecipes()) {
            for (EmiIngredient in : r.getInputs()) {
                if (in instanceof TagEmiIngredient t) {
                    tags++;
                    lists.add(t.getEmiStacks());
                }
            }
        }

        log("recipes: " + EmiApi.getRecipeManager().getRecipes().size() + ", tag inputs " + tags + ", distinct lists " + lists.size());
        check("recipe tag lists mostly shared", tags == 0 || lists.size() * 2 < tags, tags + " inputs, " + lists.size() + " lists");
        EmiSlim.logStats();
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
        LOG.info("EMI Slim check: {}", s);
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-emislim-check";
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

    private EmiSlimCheck() {
    }
}
