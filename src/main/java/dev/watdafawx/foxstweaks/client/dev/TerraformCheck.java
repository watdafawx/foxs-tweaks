package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaksItems;
import dev.watdafawx.foxstweaks.client.terraform.BiomeWandScreen;
import dev.watdafawx.foxstweaks.client.terraform.FlattenWandScreen;
import dev.watdafawx.foxstweaks.terraform.BiomeChanger;
import dev.watdafawx.foxstweaks.terraform.BiomeWandConfig;
import dev.watdafawx.foxstweaks.terraform.BiomeWandItem;
import dev.watdafawx.foxstweaks.terraform.FlattenWandConfig;
import dev.watdafawx.foxstweaks.terraform.FlattenWandItem;
import dev.watdafawx.foxstweaks.terraform.TerrainFlattener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Dev-only end-to-end in-game check of the Biome Changer Wand (1x1x1 exact edges, live client
 * grass/sky tint refresh, snow clear, Y-slice, undo, GUI) and Terrain Flattener (gradual flatten,
 * strata preservation, tree replant, undo, GUI).
 *
 * <p>Run on rig via {@code build.bat runTerraformCheck}. Writes {@code foxstweaks-terraform-check.txt}
 * + screenshots and quits.
 */
public final class TerraformCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ResourceKey<Biome> CHERRY_GROVE =
            ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("cherry_grove"));
    private static final ResourceKey<Biome> DESERT =
            ResourceKey.create(Registries.BIOME, ResourceLocation.withDefaultNamespace("desert"));

    private static final List<String> REPORT = new ArrayList<>();
    private static final List<Consumer<Minecraft>> STEPS = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int index;
    private static int wait = 60;

    private static int grassColorBefore;
    private static BlockPos basePos;

    public static void install() {
        // Step 1: Position player, equip Biome Wand, record baseline grass color, place snow inside misaligned 5x5 box
        STEPS.add(mc -> {
            basePos = new BlockPos(0, -60, 0); // Flat world grass surface is at y = -61, standing at -60
            grassColorBefore = mc.level.getBiome(new BlockPos(3, -60, 3)).value().getGrassColor(3.0, 3.0);

            server(mc, p -> {
                ServerLevel sl = p.serverLevel();
                p.teleportTo(sl, 3.5, -60.0, -4.0, 0.0F, 15.0F);
                // Place snow layer inside (1..-60..1 .. 5..-60..5)
                sl.setBlockAndUpdate(new BlockPos(3, -60, 3), Blocks.SNOW.defaultBlockState());

                ItemStack wand = new ItemStack(FoxsTweaksItems.BIOME_WAND.get());
                BiomeWandConfig cfg = BiomeWandConfig.DEFAULT
                        .withMode(BiomeWandConfig.Mode.BOX)
                        .withTargetBiome(CHERRY_GROVE.location())
                        .withFullColumn(true)
                        .withExactEdges(true)
                        .withClearSnow(true)
                        .withPositions(Optional.of(new BlockPos(1, -61, 1)), Optional.of(new BlockPos(5, -61, 5)));
                BiomeWandItem.setConfig(wand, cfg);
                p.setItemInHand(InteractionHand.MAIN_HAND, wand);
                p.getInventory().setItem(1, new ItemStack(FoxsTweaksItems.TERRAIN_FLATTENER.get()));
                p.getInventory().setItem(2, new ItemStack(FoxsTweaksItems.HOLOGRAM_WAND.get()));
            });
        });

        // Step 2: Screenshot 3D preview & apply biome change
        STEPS.add(mc -> {
            shot("terraform-1-biome-preview");
            server(mc, p -> {
                BiomeWandConfig cfg = BiomeWandItem.getConfig(p.getMainHandItem());
                BiomeChanger.apply(p, cfg);
            });
        });

        // Step 3: Verify 1x1x1 exact edges on BOTH client & server, grass color change, snow cleared, open GUI
        STEPS.add(mc -> {
            shot("terraform-2-biome-applied");
            boolean clientInside1 = mc.level.getBiome(new BlockPos(1, -60, 1)).is(CHERRY_GROVE);
            boolean clientInside5 = mc.level.getBiome(new BlockPos(5, -60, 5)).is(CHERRY_GROVE);
            boolean clientOutside0 = !mc.level.getBiome(new BlockPos(0, -60, 1)).is(CHERRY_GROVE);
            boolean clientOutside6 = !mc.level.getBiome(new BlockPos(6, -60, 5)).is(CHERRY_GROVE);
            int grassAfter = mc.level.getBiome(new BlockPos(3, -60, 3)).value().getGrassColor(3.0, 3.0);
            boolean snowCleared = mc.level.getBlockState(new BlockPos(3, -60, 3)).isAir();

            check("client 1x1 inside (1,-60,1) is cherry_grove", clientInside1,
                    "got " + mc.level.getBiome(new BlockPos(1, -60, 1)).unwrapKey());
            check("client 1x1 inside (5,-60,5) is cherry_grove", clientInside5,
                    "got " + mc.level.getBiome(new BlockPos(5, -60, 5)).unwrapKey());
            check("client 1x1 outside (0,-60,1) untouched (no 4x4 bleed)", clientOutside0,
                    "bled into (0,-60,1)");
            check("client 1x1 outside (6,-60,5) untouched (no 4x4 bleed)", clientOutside6,
                    "bled into (6,-60,5)");
            check("grass color updated live", grassAfter != grassColorBefore,
                    "before=" + Integer.toHexString(grassColorBefore) + " after=" + Integer.toHexString(grassAfter));
            check("snow cleared on biome change", snowCleared,
                    "block=" + mc.level.getBlockState(new BlockPos(3, -60, 3)));

            mc.setScreen(new BiomeWandScreen(InteractionHand.MAIN_HAND, mc.player.getMainHandItem()));
        });

        // Step 4: Screenshot Biome GUI, close it, test Undo
        STEPS.add(mc -> {
            check("BiomeWandScreen opened", mc.screen instanceof BiomeWandScreen, "screen=" + mc.screen);
            shot("terraform-3-biome-gui");
            mc.setScreen(null);
            server(mc, BiomeChanger::undo);
        });

        // Step 5: Verify Biome Undo restored biome & snow, then test Y-Slice mode (keep underground untouched)
        STEPS.add(mc -> {
            boolean reverted = !mc.level.getBiome(new BlockPos(1, -60, 1)).is(CHERRY_GROVE);
            boolean snowBack = mc.level.getBlockState(new BlockPos(3, -60, 3)).is(Blocks.SNOW);
            check("biome undo reverted (1,-60,1)", reverted,
                    "still " + mc.level.getBiome(new BlockPos(1, -60, 1)).unwrapKey());
            check("biome undo restored snow", snowBack,
                    "block=" + mc.level.getBlockState(new BlockPos(3, -60, 3)));

            server(mc, p -> {
                BiomeWandConfig sliceCfg = BiomeWandConfig.DEFAULT
                        .withMode(BiomeWandConfig.Mode.BRUSH)
                        .withTargetBiome(DESERT.location())
                        .withBrushRadius(6)
                        .withFullColumn(false)
                        .withSliceY(-55, -45)
                        .withExactEdges(true)
                        .withPositions(Optional.of(new BlockPos(3, -50, 3)), Optional.empty());
                BiomeChanger.apply(p, sliceCfg);
            });
        });

        // Step 6: Verify Y-Slice changed Y=-50 to desert while leaving Y=-60 untouched; build hill for Flattener test
        STEPS.add(mc -> {
            boolean sliceChanged = mc.level.getBiome(new BlockPos(3, -50, 3)).is(DESERT);
            boolean belowUntouched = !mc.level.getBiome(new BlockPos(3, -60, 3)).is(DESERT);
            check("Y-slice changed Y=-50 to desert", sliceChanged,
                    "got " + mc.level.getBiome(new BlockPos(3, -50, 3)).unwrapKey());
            check("Y-slice preserved Y=-60 below", belowUntouched,
                    "got " + mc.level.getBiome(new BlockPos(3, -60, 3)).unwrapKey());

            // Build a 17x17 natural hill centered at (30, -61, 30) peaking at Y=-53 (+8 blocks) with oak tree
            server(mc, p -> {
                ServerLevel sl = p.serverLevel();
                p.teleportTo(sl, 30.5, -52.0, 14.0, 0.0F, 18.0F);
                int cx = 30, cz = 30;
                for (int dz = -8; dz <= 8; dz++) {
                    for (int dx = -8; dx <= 8; dx++) {
                        double dist = Math.sqrt(dx * dx + dz * dz);
                        if (dist > 8.0) continue;
                        int h = (int) Math.round(8.0 * Math.cos((dist / 8.0) * (Math.PI * 0.5)));
                        int topY = -61 + h;
                        for (int y = -61; y <= topY; y++) {
                            BlockPos bp = new BlockPos(cx + dx, y, cz + dz);
                            if (y == topY) sl.setBlockAndUpdate(bp, Blocks.GRASS_BLOCK.defaultBlockState());
                            else if (y >= topY - 2) sl.setBlockAndUpdate(bp, Blocks.DIRT.defaultBlockState());
                            else sl.setBlockAndUpdate(bp, Blocks.STONE.defaultBlockState());
                        }
                    }
                }
                // Mini oak tree on peak (30, -52.. -49, 30)
                for (int y = -52; y <= -49; y++) {
                    sl.setBlockAndUpdate(new BlockPos(30, y, 30), Blocks.OAK_LOG.defaultBlockState());
                }
                sl.setBlockAndUpdate(new BlockPos(30, -48, 30), Blocks.OAK_LEAVES.defaultBlockState());

                ItemStack flat = new ItemStack(FoxsTweaksItems.TERRAIN_FLATTENER.get());
                FlattenWandConfig fcfg = FlattenWandConfig.DEFAULT
                        .withMode(FlattenWandConfig.Mode.BRUSH)
                        .withRadius(10)
                        .withTargetYMode(FlattenWandConfig.TargetYMode.CUSTOM)
                        .withCustomY(-61)
                        .withFlatness(0.50F)
                        .withSmoothness(0.50F)
                        .withFeather(0.35F)
                        .withTreeMode(FlattenWandConfig.TreeMode.REPLANT)
                        .withNaturalOnly(true)
                        .withPositions(Optional.of(new BlockPos(30, -61, 30)), Optional.empty());
                FlattenWandItem.setConfig(flat, fcfg);
                p.setItemInHand(InteractionHand.MAIN_HAND, flat);
            });
        });

        // Step 7: Screenshot Flattener 3D wireframe preview & enqueue flatten
        STEPS.add(mc -> {
            shot("terraform-4-flatten-preview");
            server(mc, p -> {
                FlattenWandConfig fcfg = FlattenWandItem.getConfig(p.getMainHandItem());
                TerrainFlattener.enqueue(p, fcfg);
            });
            wait = 40; // Give tick-budgeted job time to complete
        });

        // Step 8: Verify gradual flatten height, grass_block strata preserved, sapling replanted, open GUI
        STEPS.add(mc -> {
            shot("terraform-5-flatten-applied");
            // Find new solid surface at center (30, z=30)
            int peakY = -64;
            for (int y = -45; y >= -62; y--) {
                var st = mc.level.getBlockState(new BlockPos(30, y, 30));
                if (!st.isAir() && !st.is(Blocks.OAK_SAPLING)) {
                    peakY = y;
                    break;
                }
            }
            var topBlock = mc.level.getBlockState(new BlockPos(30, peakY, 30));
            var aboveBlock = mc.level.getBlockState(new BlockPos(30, peakY + 1, 30));

            check("gradual flatten lowered peak partway (-61 < peak < -53)", peakY > -61 && peakY < -53,
                    "peakY=" + peakY);
            check("strata preserved (surface is still grass_block, not stone)", topBlock.is(Blocks.GRASS_BLOCK),
                    "topBlock=" + topBlock);
            check("REPLANT tree mode placed oak_sapling on new surface", aboveBlock.is(Blocks.OAK_SAPLING),
                    "aboveBlock=" + aboveBlock);

            mc.setScreen(new FlattenWandScreen(InteractionHand.MAIN_HAND, mc.player.getMainHandItem()));
        });

        // Step 9: Screenshot Flatten GUI, close, test Undo
        STEPS.add(mc -> {
            check("FlattenWandScreen opened", mc.screen instanceof FlattenWandScreen, "screen=" + mc.screen);
            shot("terraform-6-flatten-gui");
            mc.setScreen(null);
            server(mc, TerrainFlattener::undo);
        });

        // Step 10: Verify Flatten Undo restored original peak and finish
        STEPS.add(mc -> {
            boolean peakRestored = mc.level.getBlockState(new BlockPos(30, -53, 30)).is(Blocks.GRASS_BLOCK);
            boolean treeRestored = mc.level.getBlockState(new BlockPos(30, -52, 30)).is(Blocks.OAK_LOG);
            check("flatten undo restored hill peak", peakRestored,
                    "block=" + mc.level.getBlockState(new BlockPos(30, -53, 30)));
            check("flatten undo restored oak log", treeRestored,
                    "block=" + mc.level.getBlockState(new BlockPos(30, -52, 30)));
            finish(mc);
        });

        NeoForge.EVENT_BUS.addListener(TerraformCheck::onTick);
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var srv = mc.getSingleplayerServer();
        srv.execute(() -> action.accept(srv.getPlayerList().getPlayer(mc.player.getUUID())));
    }

    private static void shot(String name) {
        var mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name + ".png", mc.getMainRenderTarget(), msg -> {
        });
    }

    private static void finish(Minecraft mc) {
        log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
        try {
            Files.write(mc.gameDirectory.toPath().resolve("foxstweaks-terraform-check.txt"), REPORT);
        } catch (IOException ex) {
            LOG.error("Terraform check report", ex);
        }
        mc.stop();
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
        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size() || --wait > 0) {
            return;
        }
        wait = 20;
        try {
            STEPS.get(index++).accept(mc);
        } catch (Throwable t) {
            check("step " + index + " no exception", false, t.toString());
            LOG.error("Terraform check crashed", t);
            finish(mc);
            index = STEPS.size();
        }
    }

    private static void check(String name, boolean ok, String why) {
        if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : ": " + why));
    }

    private static void log(String s) {
        REPORT.add(s);
        LOG.info("Terraform check: {}", s);
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-terraform-check";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    private TerraformCheck() {
    }
}
