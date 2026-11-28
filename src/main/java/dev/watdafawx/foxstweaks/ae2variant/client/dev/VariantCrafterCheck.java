package dev.watdafawx.foxstweaks.ae2variant.client.dev;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.CalculationStrategy;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.crafting.ICraftingSimulationRequester;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.GenericStack;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.menu.MenuOpener;
import appeng.menu.locator.MenuLocators;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.ae2variant.Ae2VariantIntegration;
import dev.watdafawx.foxstweaks.ae2variant.VariantCrafterBlockEntity;
import dev.watdafawx.foxstweaks.ae2variant.VariantRegistry;
import dev.watdafawx.foxstweaks.ae2variant.VariantSource;
import dev.watdafawx.foxstweaks.ae2variant.client.VariantCrafterScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Item;
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
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Dev-only end-to-end verification + screenshots of the ME Variant Crafter.
 *
 * <p>Builds a real AE2 network (Creative Energy Cell + Controller + Drive w/ 64k cell + 64k Crafting
 * Storage + Crafting Accelerator + ME Variant Crafter), seeds Stone, Oak Planks, and Factory Block,
 * verifies variant discovery and real AE2 autocrafting execution for Chipped, Rechiseled, Chisel,
 * and Factory Blocks, and screenshots the AE2-styled GUI (in Filter, In-Stock, and All modes) plus
 * the in-world block.
 *
 * <p>Run with {@code build.bat runVariantCrafterCheck} on rig. Report:
 * {@code run-variant/foxstweaks-variant-check.txt}.
 */
public final class VariantCrafterCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final BlockPos ORIGIN = new BlockPos(0, -60, 0);
    private static final BlockPos CRAFTER_POS = ORIGIN.offset(0, 0, 0);
    private static final BlockPos CONTROLLER_POS = ORIGIN.offset(1, 0, 0);
    private static final BlockPos ENERGY_POS = ORIGIN.offset(1, 1, 0);
    private static final BlockPos DRIVE_POS = ORIGIN.offset(2, 0, 0);
    private static final BlockPos CPU_STORAGE_POS = ORIGIN.offset(-1, 0, 0);
    private static final BlockPos CPU_ACCEL_POS = ORIGIN.offset(-1, 1, 0);

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static final Map<VariantSource, Item> CRAFT_TARGETS = new EnumMap<>(VariantSource.class);
    private static final Map<VariantSource, Future<ICraftingPlan>> PLAN_FUTURES = new EnumMap<>(VariantSource.class);
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(VariantCrafterCheck::onTick);
        scenarios();
    }

    private static void scenarios() {
        // 1. Build a real AE2 ME network with Creative Energy, Controller, Drive + 64k Cell, 64k Crafting CPU, and ME Variant Crafter.
        add(40, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            level.setDayTime(6000);
            p.teleportTo(level, 0.5, -60.0, 4.2, 180f, 8f);

            level.setBlockAndUpdate(CRAFTER_POS, Ae2VariantIntegration.BLOCK.get().defaultBlockState());
            level.setBlockAndUpdate(CONTROLLER_POS, AEBlocks.CONTROLLER.block().defaultBlockState());
            level.setBlockAndUpdate(ENERGY_POS, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
            level.setBlockAndUpdate(DRIVE_POS, AEBlocks.DRIVE.block().defaultBlockState());
            level.setBlockAndUpdate(CPU_STORAGE_POS, AEBlocks.CRAFTING_STORAGE_64K.block().defaultBlockState());
            level.setBlockAndUpdate(CPU_ACCEL_POS, AEBlocks.CRAFTING_ACCELERATOR.block().defaultBlockState());

            if (level.getBlockEntity(DRIVE_POS) instanceof DriveBlockEntity drive) {
                drive.getInternalInventory().addItems(AEItems.ITEM_CELL_64K.stack());
            }
            return null;
        }));

        // 2. Wait for AE2 grid boot, seed base blocks into ME storage, configure Family Filter slots, and verify indexing.
        add(60, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            VariantCrafterBlockEntity be = (VariantCrafterBlockEntity) level.getBlockEntity(CRAFTER_POS);
            check("ME Variant Crafter block entity exists", be != null, "null");
            if (be == null) return null;

            IGrid grid = be.getMainNode().getGrid();
            check("ME Variant Crafter joined AE2 grid and is active", grid != null && be.getMainNode().isActive(),
                    "grid=" + grid + " active=" + be.getMainNode().isActive());
            if (grid == null) return null;

            // Seed ME storage with base blocks
            var inv = grid.getStorageService().getInventory();
            var src = IActionSource.ofPlayer(p);
            inv.insert(AEItemKey.of(Items.STONE), 512, Actionable.MODULATE, src);
            inv.insert(AEItemKey.of(Items.OAK_PLANKS), 512, Actionable.MODULATE, src);
            Item factoryBase = findItem("factory_blocks:factory");
            if (factoryBase != null) {
                inv.insert(AEItemKey.of(factoryBase), 512, Actionable.MODULATE, src);
            }

            // Configure Family Filter with Stone, Oak Planks, and Factory Block
            be.getFilterInventory().setStack(0, new GenericStack(AEItemKey.of(Items.STONE), 1));
            be.getFilterInventory().setStack(1, new GenericStack(AEItemKey.of(Items.OAK_PLANKS), 1));
            if (factoryBase != null) {
                be.getFilterInventory().setStack(2, new GenericStack(AEItemKey.of(factoryBase), 1));
            }
            be.invalidatePatterns();

            for (VariantSource s : new VariantSource[]{
                    VariantSource.CHIPPED, VariantSource.RECHISELED, VariantSource.CHISEL, VariantSource.FACTORY_BLOCKS}) {
                int count = VariantRegistry.getSourceCount(level, s);
                log("indexed " + s.displayName + " = " + count + " (loaded=" + s.isModLoaded() + ")");
                if (s.isModLoaded()) {
                    check(s.displayName + " variants indexed (>0)", count > 0, "count=" + count);
                }
            }
            return null;
        }));

        // 3. Screenshot the in-world ME Variant Crafter in the AE2 network!
        add(15, () -> snap("variant-01-world-block.png"));

        // 4. Verify craftable targets & submit real AE2 autocrafting jobs for each loaded variant mod!
        add(15, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            VariantCrafterBlockEntity be = (VariantCrafterBlockEntity) level.getBlockEntity(CRAFTER_POS);
            if (be == null || be.getMainNode().getGrid() == null) return null;
            IGrid grid = be.getMainNode().getGrid();
            ICraftingService crafting = grid.getCraftingService();

            Set<Item> filterSet = new java.util.HashSet<>();
            filterSet.add(Items.STONE);
            filterSet.add(Items.OAK_PLANKS);
            Item factoryBase = findItem("factory_blocks:factory");
            if (factoryBase != null) filterSet.add(factoryBase);

            var conversions = VariantRegistry.query(
                    level, be.getEnabledSources(), VariantRegistry.FilterMode.FAMILY_FILTER, filterSet, Set.of());
            check("Family Filter exposed patterns (>0)", !conversions.isEmpty(), "count=0");

            // Pick one target item from each loaded source (preferring items owned by that mod) and start AE2 crafting plans!
            for (VariantRegistry.VariantConversion conv : conversions) {
                if (CRAFT_TARGETS.containsKey(conv.source())) continue;
                String ns = BuiltInRegistries.ITEM.getKey(conv.output()).getNamespace();
                if ((conv.source() == VariantSource.CHIPPED && "chipped".equals(ns))
                        || (conv.source() == VariantSource.RECHISELED && "rechiseled".equals(ns))
                        || (conv.source() == VariantSource.CHISEL && "chisel".equals(ns))
                        || (conv.source() == VariantSource.FACTORY_BLOCKS && "factory_blocks".equals(ns))) {
                    CRAFT_TARGETS.put(conv.source(), conv.output());
                }
            }
            // Fallback for any source whose items live in another namespace (e.g. factory_blocks inside chisel).
            for (VariantRegistry.VariantConversion conv : conversions) {
                if (CRAFT_TARGETS.containsKey(conv.source())) continue;
                if (conv.source() == VariantSource.CHIPPED
                        || conv.source() == VariantSource.RECHISELED
                        || conv.source() == VariantSource.CHISEL
                        || conv.source() == VariantSource.FACTORY_BLOCKS) {
                    CRAFT_TARGETS.put(conv.source(), conv.output());
                }
            }

            ICraftingSimulationRequester simReq = () -> IActionSource.ofPlayer(p, be);
            for (var entry : CRAFT_TARGETS.entrySet()) {
                VariantSource source = entry.getKey();
                Item target = entry.getValue();
                AEItemKey key = AEItemKey.of(target);
                boolean craftable = crafting.isCraftable(key);
                check("AE2 sees " + source.displayName + " target craftable (" + BuiltInRegistries.ITEM.getKey(target) + ")",
                        craftable, "isCraftable=false");
                if (!craftable) continue;

                PLAN_FUTURES.put(source, crafting.beginCraftingCalculation(
                        level, simReq, key, 16, CalculationStrategy.REPORT_MISSING_ITEMS));
            }
            return null;
        }));

        // 4b. Submit each computed AE2 crafting plan sequentially to our single Crafting CPU.
        for (VariantSource src : new VariantSource[]{
                VariantSource.CHIPPED, VariantSource.RECHISELED, VariantSource.CHISEL, VariantSource.FACTORY_BLOCKS}) {
            add(50, () -> onServer(p -> {
                Future<ICraftingPlan> fut = PLAN_FUTURES.get(src);
                if (fut == null) return null;
                VariantCrafterBlockEntity be = (VariantCrafterBlockEntity) p.serverLevel().getBlockEntity(CRAFTER_POS);
                if (be == null || be.getMainNode().getGrid() == null) return null;
                ICraftingService crafting = be.getMainNode().getGrid().getCraftingService();
                try {
                    ICraftingPlan plan = fut.get(2, TimeUnit.SECONDS);
                    StringBuilder missStr = new StringBuilder();
                    for (var m : plan.missingItems()) {
                        missStr.append(m.getKey()).append("x").append(m.getLongValue()).append(",");
                    }
                    check("AE2 crafting plan valid for " + src.displayName,
                            !plan.simulation() && plan.missingItems().isEmpty(),
                            "sim=" + plan.simulation() + " bytes=" + plan.bytes() + " missing=[" + missStr + "]");
                    var submit = crafting.submitJob(plan, null, null, false, IActionSource.ofPlayer(p));
                    check("AE2 submitted job for " + src.displayName, submit.successful(),
                            "error=" + submit.errorCode());
                } catch (Exception ex) {
                    check("AE2 crafting job for " + src.displayName, false, ex.toString());
                }
                return null;
            }));
        }

        // 5. Wait for Crafting CPU + ME Variant Crafter to finish all 4 jobs and verify outputs in ME Storage!
        add(30, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            VariantCrafterBlockEntity be = (VariantCrafterBlockEntity) level.getBlockEntity(CRAFTER_POS);
            if (be == null || be.getMainNode().getGrid() == null) return null;
            var cached = be.getMainNode().getGrid().getStorageService().getCachedInventory();
            for (var entry : CRAFT_TARGETS.entrySet()) {
                long stored = cached.get(AEItemKey.of(entry.getValue()));
                check("AE2 autocrafted 16x " + entry.getKey().displayName + " ("
                        + BuiltInRegistries.ITEM.getKey(entry.getValue()) + ")",
                        stored == 16, "stored=" + stored);
            }
            long stoneLeft = cached.get(AEItemKey.of(Items.STONE));
            long planksLeft = cached.get(AEItemKey.of(Items.OAK_PLANKS));
            Item factoryBase = findItem("factory_blocks:factory");
            long factoryLeft = factoryBase != null ? cached.get(AEItemKey.of(factoryBase)) : 512;
            long totalConsumed = (512 - stoneLeft) + (512 - planksLeft) + (512 - factoryLeft);
            log("Base blocks remaining: stone=" + stoneLeft + " oak_planks=" + planksLeft
                    + " factory=" + factoryLeft + " (consumed=" + totalConsumed + ")");
            check("Exact base blocks consumed (" + (16L * CRAFT_TARGETS.size()) + ")",
                    totalConsumed == 16L * CRAFT_TARGETS.size(),
                    "consumed=" + totalConsumed + " expected=" + (16L * CRAFT_TARGETS.size()));
            return null;
        }));

        // 6. Open the real AE2-styled ME Variant Crafter screen & capture screenshots of all 3 modes!
        add(10, () -> onServer(p -> {
            VariantCrafterBlockEntity be = (VariantCrafterBlockEntity) p.serverLevel().getBlockEntity(CRAFTER_POS);
            if (be != null) {
                MenuOpener.open(Ae2VariantIntegration.MENU_TYPE.get(), p, MenuLocators.forBlockEntity(be));
            }
            return null;
        }));

        add(20, () -> {
            var mc = Minecraft.getInstance();
            check("VariantCrafterScreen opened", mc.screen instanceof VariantCrafterScreen, String.valueOf(mc.screen));
            snap("variant-02-gui-filter.png");
        });

        // Switch to In-Stock mode and screenshot
        add(15, () -> {
            if (Minecraft.getInstance().screen instanceof VariantCrafterScreen screen) {
                screen.getMenu().cycleMode();
            }
        });
        add(15, () -> snap("variant-03-gui-instock.png"));

        // Switch to All Variants mode and screenshot
        add(15, () -> {
            if (Minecraft.getInstance().screen instanceof VariantCrafterScreen screen) {
                screen.getMenu().cycleMode();
            }
        });
        add(15, () -> snap("variant-04-gui-all.png"));

        // 7. Write report and exit cleanly.
        add(10, VariantCrafterCheck::finish);
    }

    private static Item findItem(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
            Item it = BuiltInRegistries.ITEM.get(rl);
            if (it != Items.AIR) return it;
        }
        return null;
    }

    private static void snap(String filename) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, filename, mc.getMainRenderTarget(), msg -> {
        });
        log("screenshot " + filename);
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        return server.submit(() -> {
            ServerPlayer player = server.getPlayerList().getPlayers().get(0);
            return fn.apply(player);
        }).join();
    }

    private static void log(String line) {
        LOG.info("VariantCrafterCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void finish() {
        REPORT.add(failures == 0 ? "done: ALL PASS" : "done: " + failures + " FAILURE(S)");
        Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-variant-check.txt");
        try {
            Files.write(out, REPORT);
        } catch (IOException ex) {
            LOG.error("Failed writing report", ex);
        }
        Minecraft.getInstance().stop();
    }

    private static void onTick(ClientTickEvent.Post e) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size()) {
            return;
        }

        tick++;
        if (nextAt == 0) nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt) return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("VariantCrafterCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size()) {
            nextAt = tick + STEPS.get(index).waitTicks;
        }
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-variantcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(
                world, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(
                world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private VariantCrafterCheck() {
    }
}
