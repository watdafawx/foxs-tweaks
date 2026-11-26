package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramGrab;
import dev.watdafawx.foxstweaks.client.HologramRenderer;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.HologramStructureMesh;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import dev.watdafawx.foxstweaks.hologram.HologramStructure;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Dev-only end-to-end check and benchmark for Hologram Structure Grab.
 *
 * <p>Run with {@code build.bat runHologramStructureCheck} on rig.
 * Report: {@code run-hologram-structure/foxstweaks-hologram-structure-check.txt}.
 */
public final class HologramStructureCheck {
    private static final Logger LOG = LogUtils.getLogger();

    // Small 5x5x5 house sitting on the flat world floor (y = -60 .. -56, x = -2..2, z = -2..2).
    private static final BlockPos HOUSE_MIN = new BlockPos(-2, -60, -2);
    private static final BlockPos HOUSE_MAX = new BlockPos(2, -56, 2);
    private static final BlockPos CHEST_POS = new BlockPos(0, -60, -1);
    private static final BlockPos SIGN_POS = new BlockPos(1, -60, 2);

    // Oversized box corners (35 blocks along X, exceeds default maxDimension=32).
    private static final BlockPos OVER_C1 = new BlockPos(-16, -59, -10);
    private static final BlockPos OVER_C2 = new BlockPos(18, -59, -10);

    // 9x5x5 fluid showcase (water pool, lava pool, flowing waterfall, waterlogged stairs/slab, kelp).
    // Placed at x = -15..-7, y = -60..-56, z = 14..18; its grabbed hologram sits at (0, -60, 16)
    // which spans x = -4..4, z = 14..18 (exact +11 X shift for 1-to-1 pixel comparison).
    private static final BlockPos FLUID_MIN = new BlockPos(-15, -60, 14);
    private static final BlockPos FLUID_MAX = new BlockPos(-7, -56, 18);
    private static final BlockPos FLUID_HOLO_POS = new BlockPos(0, -60, 16);
    private static final Vec3 FLUID_CAM_REAL = new Vec3(-10.5, -52.5, 24.5);
    private static final Vec3 FLUID_LOOK_REAL = new Vec3(-10.5, -58.5, 16.5);
    private static final Vec3 FLUID_CAM_HOLO = new Vec3(0.5, -52.5, 24.5);
    private static final Vec3 FLUID_LOOK_HOLO = new Vec3(0.5, -58.5, 16.5);

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    private static String hologramId = "";
    private static String fluidHoloId = "";
    private static String benchHoloId = "";
    private static String waterBenchHoloId = "";
    private static HologramScreen editorRef;
    private static int expectedHouseBlocks;

    private static volatile boolean sampling;
    private static final List<Long> SAMPLES = new ArrayList<>();
    private static double cachedMedianUs;
    private static double cachedMeanUs;
    private static int buildsBeforeCached;
    private static int buildsAfterCached;
    private static double naiveMedianUs;
    private static double naiveMeanUs;
    private static int buildsBeforeWater;
    private static int buildsAfterWater;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramStructureCheck::onTick);
        NeoForge.EVENT_BUS.addListener(HologramStructureCheck::onRenderStage);
        scenarios();
    }

    private static void onRenderStage(RenderLevelStageEvent event) {
        if (!sampling || event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL)
            return;
        long nanos = HologramRenderer.lastPassNanos();
        if (nanos > 0L)
            SAMPLES.add(nanos);
    }

    private static void scenarios() {
        // ---- 1. Build a small 5x5x5 house + oversized marker blocks ----
        add(40, () -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable ignored) {
            }
            mc.options.guiScale().set(3);
            mc.resizeDisplay();

            expectedHouseBlocks = onServer(player -> {
                var level = player.serverLevel();
                level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
                level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
                level.setDayTime(6000L);

                // Clear house footprint first
                for (int y = HOUSE_MIN.getY(); y <= HOUSE_MAX.getY(); y++) {
                    for (int z = HOUSE_MIN.getZ(); z <= HOUSE_MAX.getZ(); z++) {
                        for (int x = HOUSE_MIN.getX(); x <= HOUSE_MAX.getX(); x++)
                            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }

                // Floor (y = -60): cobblestone border + oak planks interior
                for (int x = -2; x <= 2; x++) {
                    for (int z = -2; z <= 2; z++) {
                        boolean edge = x == -2 || x == 2 || z == -2 || z == 2;
                        level.setBlockAndUpdate(new BlockPos(x, -60, z),
                                (edge ? Blocks.COBBLESTONE : Blocks.OAK_PLANKS).defaultBlockState());
                    }
                }

                // Walls (y = -59 .. -58): oak logs at corners, planks walls, glass windows, doorway on south (z=2, x=0)
                for (int y = -59; y <= -58; y++) {
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            boolean onX = x == -2 || x == 2;
                            boolean onZ = z == -2 || z == 2;
                            if (!onX && !onZ)
                                continue;
                            if (z == 2 && x == 0)
                                continue; // open doorway (air)
                            BlockState st;
                            if (onX && onZ) {
                                st = Blocks.OAK_LOG.defaultBlockState();
                            } else if (y == -58 && (x == 0 || z == 0)) {
                                st = Blocks.GLASS.defaultBlockState();
                            } else {
                                st = Blocks.OAK_PLANKS.defaultBlockState();
                            }
                            level.setBlockAndUpdate(new BlockPos(x, y, z), st);
                        }
                    }
                }

                // Roof lower ring (y = -57): oak stairs facing inward around the perimeter
                for (int x = -2; x <= 2; x++) {
                    level.setBlockAndUpdate(new BlockPos(x, -57, -2),
                            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
                    level.setBlockAndUpdate(new BlockPos(x, -57, 2),
                            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
                }
                for (int z = -1; z <= 1; z++) {
                    level.setBlockAndUpdate(new BlockPos(-2, -57, z),
                            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                    level.setBlockAndUpdate(new BlockPos(2, -57, z),
                            Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
                }

                // Roof cap (y = -56): oak slabs + peak corner block at HOUSE_MAX so HOUSE_MAX is solid & clickable!
                for (int x = -1; x <= 1; x++) {
                    for (int z = -1; z <= 1; z++) {
                        level.setBlockAndUpdate(new BlockPos(x, -56, z),
                                Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
                    }
                }
                // Chimney / finial block at HOUSE_MAX (2, -56, 2) so both corners of the 5x5x5 box are solid blocks.
                level.setBlockAndUpdate(HOUSE_MAX, Blocks.LANTERN.defaultBlockState());

                // Chest with items inside the house (y = -59 on top of floor, or at CHEST_POS)
                level.setBlockAndUpdate(CHEST_POS.above(),
                        Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
                if (level.getBlockEntity(CHEST_POS.above()) instanceof ChestBlockEntity chest) {
                    chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
                    ItemStack emeralds = new ItemStack(Items.EMERALD, 16);
                    CompoundTag probe = new CompoundTag();
                    probe.putString("structure_probe", "x".repeat(40_000));
                    emeralds.set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,
                            net.minecraft.world.item.component.CustomData.of(probe));
                    chest.setItem(1, emeralds);
                    chest.setChanged();
                }

                // Oak sign outside front-right corner (SIGN_POS at (1, -60, 2) replaced with floor below, sign at y=-59? Let's place standing sign at (1, -59, 1) inside doorway or at (1, -60, 2))
                // Put sign on top of floor at (1, -59, 1) facing south, visible through open doorway, AND keep floor solid.
                BlockPos signPos = new BlockPos(1, -59, 1);
                level.setBlockAndUpdate(signPos,
                        Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 0));
                if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                    SignText front = sign.getFrontText()
                            .setMessage(0, Component.literal("Fox House"))
                            .setMessage(1, Component.literal("Holo Test"));
                    sign.setText(front, true);
                    sign.setChanged();
                }

                // Oversized test marker blocks (35 blocks apart along X, raised 1 block for unobstructed aim)
                level.setBlockAndUpdate(OVER_C1.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(OVER_C1, Blocks.RED_CONCRETE.defaultBlockState());
                level.setBlockAndUpdate(OVER_C2.below(), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(OVER_C2, Blocks.RED_CONCRETE.defaultBlockState());

                // Count exact non-air blocks in HOUSE_MIN..HOUSE_MAX
                int nonAir = 0;
                for (int y = HOUSE_MIN.getY(); y <= HOUSE_MAX.getY(); y++) {
                    for (int z = HOUSE_MIN.getZ(); z <= HOUSE_MAX.getZ(); z++) {
                        for (int x = HOUSE_MIN.getX(); x <= HOUSE_MAX.getX(); x++) {
                            if (!level.getBlockState(new BlockPos(x, y, z)).isAir())
                                nonAir++;
                        }
                    }
                }

                // Stand south-east looking toward the house
                player.teleportTo(level, 6.5, -60.0, 9.5, 145.0F, 8.0F);
                return nonAir;
            });
            check("house built with non-air blocks and interior air",
                    expectedHouseBlocks > 40 && expectedHouseBlocks < 125,
                    "nonAir=" + expectedHouseBlocks + " (volume=125)");
        });

        // ---- 2. Create base hologram and open Studio ----
        add(15, () -> {
            hologramId = onServer(player -> {
                try {
                    player.server.getCommands().getDispatcher()
                            .execute("foxstweaks hologram add minecraft:quartz_block",
                                    player.createCommandSourceStack().withPermission(4));
                } catch (Exception e) {
                    LOG.error("add command failed", e);
                }
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("base hologram created", !hologramId.isEmpty(), "id=" + hologramId);
        });

        add(10, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Studio opened", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (!(mc.screen instanceof HologramScreen screen))
                return;
            editorRef = screen;
            check("Grab enters grab mode", screen.devClick("Grab") && HologramGrab.isActive(),
                    "active=" + HologramGrab.isActive());
            HologramGrab.handleKey(GLFW.GLFW_KEY_4);
            check("key 4 switches GrabMode to STRUCTURE", HologramGrab.mode() == HologramGrab.GrabMode.STRUCTURE,
                    "mode=" + HologramGrab.mode());
        });

        // ---- 3. Mark corner 1 (HOUSE_MIN) and corner 2 (HOUSE_MAX) through real HologramGrab paths ----
        add(6, () -> moveAndAim(new Vec3(-5.5, -59.5, -5.5), Vec3.atCenterOf(HOUSE_MIN)));

        add(8, () -> {
            check("aiming at corner 1 hits HOUSE_MIN",
                    HologramGrab.currentHit() instanceof BlockHitResult bhr && bhr.getBlockPos().equals(HOUSE_MIN),
                    String.valueOf(HologramGrab.currentHit()));
            check("click 1 sets corner1", HologramGrab.triggerGrab(false) && HOUSE_MIN.equals(HologramGrab.corner1()),
                    "c1=" + HologramGrab.corner1());
            moveAndAim(new Vec3(6.5, -55.0, 6.5), Vec3.atCenterOf(HOUSE_MAX));
        });

        add(8, () -> {
            check("aiming at corner 2 hits HOUSE_MAX",
                    HologramGrab.currentHit() instanceof BlockHitResult bhr && bhr.getBlockPos().equals(HOUSE_MAX),
                    String.valueOf(HologramGrab.currentHit()));
            check("click 2 locks corner2", HologramGrab.triggerGrab(false) && HOUSE_MAX.equals(HologramGrab.corner2()),
                    "c2=" + HologramGrab.corner2());
            // Step back slightly for a clean screenshot of the full cyan box selection HUD
            moveAndAim(new Vec3(5.5, -56.5, 6.8), new Vec3(0.5, -58.0, 0.5));
        });

        add(8, () -> {
            shoot("structure-hud-box.png");
            onServer(player -> {
                dev.watdafawx.foxstweaks.ServerConfig.HOLOGRAM_STRUCTURE_MAX_BYTES.set(16384);
                return null;
            });
            // Confirm with real Enter key!
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(14, () -> {
            check("server rejects structure over byte budget without dropping contents",
                    HologramGrab.isActive() && HologramGrab.lastStatus().contains("data too large"),
                    HologramGrab.lastStatus());
            onServer(player -> {
                dev.watdafawx.foxstweaks.ServerConfig.HOLOGRAM_STRUCTURE_MAX_BYTES.set(262144);
                dev.watdafawx.foxstweaks.ServerConfig.HOLOGRAM_STRUCTURE_MAX_BLOCKS.set(10);
                return null;
            });
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(14, () -> {
            check("server rejects structure over non-air block budget",
                    HologramGrab.isActive() && HologramGrab.lastStatus().contains("Too many blocks"),
                    HologramGrab.lastStatus());
            onServer(player -> {
                dev.watdafawx.foxstweaks.ServerConfig.HOLOGRAM_STRUCTURE_MAX_BLOCKS.set(4096);
                return null;
            });
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        // ---- 4. Verify grabbed STRUCTURE layer in Studio, disable replace, screenshot inspector, save ----
        add(14, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Enter returned to Studio with structure layer",
                    !HologramGrab.isActive() && mc.screen == editorRef,
                    "active=" + HologramGrab.isActive() + " screen=" + mc.screen);
            if (editorRef == null)
                return;

            List<HologramLayer> layers = editorRef.current().layers();
            check("one STRUCTURE layer added (total 2)", layers.size() == 2, "count=" + layers.size());
            if (layers.size() < 2)
                return;

            HologramLayer structLayer = layers.get(1);
            check("layer kind is STRUCTURE and named Structure 5x5x5",
                    structLayer.kind() == HologramLayer.Kind.STRUCTURE
                            && "Structure 5x5x5".equals(structLayer.shortName())
                            && "[S] Structure 5x5x5".equals(structLayer.label()),
                    "kind=" + structLayer.kind() + " name=" + structLayer.shortName() + " label=" + structLayer.label());

            HologramStructure parsed = HologramStructure.fromTag(structLayer.data());
            check("structure parsed with size 5x5x5 and exact non-air count (air skipped)",
                    parsed != null && parsed.sizeX() == 5 && parsed.sizeY() == 5 && parsed.sizeZ() == 5
                            && parsed.blockCount() == expectedHouseBlocks,
                    parsed == null ? "null" : (parsed.sizeX() + "x" + parsed.sizeY() + "x" + parsed.sizeZ()
                            + " count=" + parsed.blockCount() + " expected=" + expectedHouseBlocks));

            if (parsed != null) {
                int chestIdx = parsed.packPos(
                        CHEST_POS.getX() - HOUSE_MIN.getX(),
                        CHEST_POS.above().getY() - HOUSE_MIN.getY(),
                        CHEST_POS.getZ() - HOUSE_MIN.getZ());
                CompoundTag chestBe = parsed.blockEntityAt(chestIdx);
                boolean hasItems = chestBe != null && chestBe.contains("Items", Tag.TAG_LIST)
                        && chestBe.getList("Items", Tag.TAG_COMPOUND).size() == 2;
                check("chest BE items preserved in structure", hasItems,
                        chestBe == null ? "null" : "keys=" + chestBe.getAllKeys());
                check("BE data above legacy 64 KB cap retained",
                        chestBe != null && chestBe.sizeInBytes() > Holograms.MAX_DATA_BYTES
                                && chestBe.toString().contains("structure_probe"), "large item data missing");

                int signIdx = parsed.packPos(
                        1 - HOUSE_MIN.getX(),
                        -59 - HOUSE_MIN.getY(),
                        1 - HOUSE_MIN.getZ());
                CompoundTag signBe = parsed.blockEntityAt(signIdx);
                check("sign BE data preserved in structure",
                        signBe != null && signBe.toString().contains("Fox House"),
                        signBe == null ? "null" : signBe.toString());

                // Verify stair facing property preserved in palette
                boolean foundEastStair = false;
                for (var pe : parsed.palette()) {
                    if ("minecraft:oak_stairs".equals(pe.id()) && "east".equals(pe.props().get("facing")))
                        foundEastStair = true;
                }
                check("blockstate properties preserved (oak_stairs facing=east)", foundEastStair,
                        "palette=" + parsed.palette());
            }

            // Picker/replace on a STRUCTURE layer must be disabled gracefully
            editorRef.pickBlock(Blocks.STONE);
            check("picker replace on STRUCTURE layer is disabled gracefully",
                    editorRef.current().layers().get(1).kind() == HologramLayer.Kind.STRUCTURE,
                    "kind=" + editorRef.current().layers().get(1).kind());

            // Ensure Studio Main tab is shown and camera is framed nicely on the structure
            editorRef.devTab(HologramScreen.TAB_MAIN);
            editorRef.devKey(GLFW.GLFW_KEY_F);
        });

        add(6, () -> {
            shoot("structure-studio-inspector.png");
            if (editorRef != null)
                check("Done button saves hologram", editorRef.devClick("Done"), "no Done click");
        });

        // ---- 5. Verify server persistence, NBT round-trip equality, and real house untouched ----
        add(12, () -> {
            HologramSpec saved = onServer(player -> Holograms.get(player.serverLevel(), hologramId));
            check("server persisted hologram with STRUCTURE layer",
                    saved != null && saved.layers().size() == 2
                            && saved.layers().get(1).kind() == HologramLayer.Kind.STRUCTURE,
                    saved == null ? "null" : "layers=" + saved.layers().size());
            if (saved != null) {
                CompoundTag outTag = saved.toTag();
                HologramSpec roundTripped = HologramSpec.fromTag(outTag);
                check("world-independent HologramSpec NBT round-trip equal",
                        saved.equals(roundTripped),
                        roundTripped == null ? "null" : "mismatch");

                HologramStructure s1 = HologramStructure.fromTag(saved.layers().get(1).data());
                HologramStructure s2 = s1 == null ? null : HologramStructure.fromTag(s1.toTag());
                check("HologramStructure NBT round-trip equal",
                        s1 != null && s1.equals(s2),
                        "s1=" + s1 + " s2=" + s2);
                if (s1 != null) {
                    CompoundTag malformed = s1.toTag();
                    malformed.putInt("count", s1.blockCount() + 1);
                    check("malformed structure count rejected", HologramStructure.fromTag(malformed) == null, "accepted");
                    malformed = s1.toTag();
                    malformed.putByteArray("grid", new byte[1]);
                    check("truncated structure grid rejected", HologramStructure.fromTag(malformed) == null, "accepted");
                }

                HologramLayer legacy = HologramLayer.block("minecraft:stone", Map.of(), new CompoundTag());
                check("legacy block layer NBT unchanged",
                        HologramLayer.listFromTag(HologramLayer.listToTag(List.of(legacy))).equals(List.of(legacy)),
                        "legacy layer changed");
            }

            boolean houseIntact = onServer(player -> {
                var level = player.serverLevel();
                int count = 0;
                for (int y = HOUSE_MIN.getY(); y <= HOUSE_MAX.getY(); y++) {
                    for (int z = HOUSE_MIN.getZ(); z <= HOUSE_MAX.getZ(); z++) {
                        for (int x = HOUSE_MIN.getX(); x <= HOUSE_MAX.getX(); x++) {
                            if (!level.getBlockState(new BlockPos(x, y, z)).isAir())
                                count++;
                        }
                    }
                }
                boolean chestOk = level.getBlockEntity(CHEST_POS.above()) instanceof ChestBlockEntity c
                        && c.getItem(0).is(Items.DIAMOND) && c.getItem(0).getCount() == 7;
                return count == expectedHouseBlocks && chestOk;
            });
            check("real house in world was NOT broken or modified", houseIntact, "world changed");

            // Place the grabbed house hologram right next to the real house (at x=7, y=-60, z=0) for comparison!
            onServer(player -> {
                HologramSpec cur = Holograms.get(player.serverLevel(), hologramId);
                if (cur == null || cur.layers().size() < 2)
                    return null;
                HologramLayer houseOnly = cur.layers().get(1).withOffset(0.0F, 0.0F, 0.0F);
                HologramSpec sideBySide = cur.at(cur.id(), new BlockPos(7, -60, 0), cur.owner(), cur.ownerName())
                        .withLayers(List.of(houseOnly));
                Holograms.save(player.serverLevel(), sideBySide);
                return null;
            });
        });

        add(10, () -> moveAndAim(new Vec3(3.5, -56.5, 11.5), new Vec3(3.5, -58.2, 0.0)));

        add(10, () -> {
            check("cached structure VBO built and rendered",
                    HologramRenderer.lastDrawnCount() >= 1 && HologramStructureMesh.cacheSize() >= 1,
                    "drawn=" + HologramRenderer.lastDrawnCount() + " cache=" + HologramStructureMesh.cacheSize());
            check("structure BEs drawn via HologramBlockEntities",
                    HologramRenderer.lastDrawnBlockEntityCount() >= 2,
                    "drawnBEs=" + HologramRenderer.lastDrawnBlockEntityCount());
            shoot("structure-world-compare.png");
        });

        // ---- 6. Caps reject an oversized box (35x1x1 > max 32) with clear status message ----
        add(6, () -> onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4),
                    "foxstweaks hologram edit " + hologramId);
            return null;
        }));

        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof HologramScreen screen)) {
                check("re-opened editor for oversized cap test", false, String.valueOf(mc.screen));
                return;
            }
            editorRef = screen;
            screen.devClick("Grab");
            HologramGrab.handleKey(GLFW.GLFW_KEY_4);
            moveAndAim(new Vec3(1.0, -56.0, -4.0), Vec3.atCenterOf(OVER_C1));
        });

        add(8, () -> {
            check("oversized corner 1 set", HologramGrab.triggerGrab(false) && OVER_C1.equals(HologramGrab.corner1()),
                    "c1=" + HologramGrab.corner1() + " hit=" + HologramGrab.currentHit());
            moveAndAim(new Vec3(1.0, -56.0, -4.0), Vec3.atCenterOf(OVER_C2));
        });

        add(8, () -> {
            check("oversized corner 2 set", HologramGrab.triggerGrab(false) && OVER_C2.equals(HologramGrab.corner2()),
                    "c2=" + HologramGrab.corner2() + " hit=" + HologramGrab.currentHit());
            // Confirm — server must reject with clear status message!
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(12, () -> {
            String status = HologramGrab.lastStatus();
            check("server rejected oversized box and stayed in grab mode with clear status",
                    HologramGrab.isActive() && status.toLowerCase(Locale.ROOT).contains("max 32"),
                    "active=" + HologramGrab.isActive() + " status=" + status);
            HologramGrab.handleKey(GLFW.GLFW_KEY_ESCAPE);
        });

        add(6, () -> {
            if (Minecraft.getInstance().screen instanceof HologramScreen screen)
                screen.cancelAndClose();
        });

        // ---- 7. Fluid showcase: water pool, lava pool, waterfall, waterlogged stairs/slab, kelp ----
        add(6, () -> {
            onServer(player -> {
                var level = player.serverLevel();
                // Remove the house hologram so the scene stays clean for fluid comparison.
                Holograms.remove(level, hologramId);

                // Clear the 9x5x5 showcase footprint plus a 1-block margin.
                for (int y = FLUID_MIN.getY(); y <= FLUID_MAX.getY() + 1; y++) {
                    for (int z = FLUID_MIN.getZ() - 1; z <= FLUID_MAX.getZ() + 1; z++) {
                        for (int x = FLUID_MIN.getX() - 1; x <= FLUID_MAX.getX() + 1; x++)
                            level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
                    }
                }

                int ox = FLUID_MIN.getX();
                int oy = FLUID_MIN.getY();
                int oz = FLUID_MIN.getZ();

                // Floor (by = 0): neutral grey stone across the full 9x5 footprint.
                for (int bx = 0; bx <= 8; bx++) {
                    for (int bz = 0; bz <= 4; bz++)
                        level.setBlockAndUpdate(new BlockPos(ox + bx, oy, oz + bz), Blocks.STONE.defaultBlockState());
                }

                // Basin walls (by = 1..2): left (bx=0), divider (bx=4), right (bx=8), back (bz=0), front (bz=4).
                for (int by = 1; by <= 2; by++) {
                    for (int bx = 0; bx <= 8; bx++) {
                        level.setBlockAndUpdate(new BlockPos(ox + bx, oy + by, oz), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(ox + bx, oy + by, oz + 4), Blocks.STONE.defaultBlockState());
                    }
                    for (int bz = 1; bz <= 3; bz++) {
                        level.setBlockAndUpdate(new BlockPos(ox, oy + by, oz + bz), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(ox + 4, oy + by, oz + bz), Blocks.STONE.defaultBlockState());
                        level.setBlockAndUpdate(new BlockPos(ox + 8, oy + by, oz + bz), Blocks.STONE.defaultBlockState());
                    }
                }

                // Water pool (bx = 1..3, bz = 1..3, by = 1..2) with kelp + waterlogged stairs & slab.
                for (int by = 1; by <= 2; by++) {
                    for (int bx = 1; bx <= 3; bx++) {
                        for (int bz = 1; bz <= 3; bz++)
                            level.setBlockAndUpdate(new BlockPos(ox + bx, oy + by, oz + bz), Blocks.WATER.defaultBlockState());
                    }
                }
                level.setBlockAndUpdate(new BlockPos(ox + 2, oy + 1, oz + 2), Blocks.KELP_PLANT.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(ox + 2, oy + 2, oz + 2), Blocks.KELP.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(ox + 1, oy + 2, oz + 3),
                        Blocks.OAK_STAIRS.defaultBlockState()
                                .setValue(StairBlock.FACING, Direction.NORTH)
                                .setValue(StairBlock.WATERLOGGED, true));
                level.setBlockAndUpdate(new BlockPos(ox + 3, oy + 2, oz + 2),
                        Blocks.OAK_SLAB.defaultBlockState()
                                .setValue(SlabBlock.TYPE, SlabType.BOTTOM)
                                .setValue(SlabBlock.WATERLOGGED, true));

                // Lava pool (bx = 5..7, bz = 1..3, by = 1..2).
                for (int by = 1; by <= 2; by++) {
                    for (int bx = 5; bx <= 7; bx++) {
                        for (int bz = 1; bz <= 3; bz++)
                            level.setBlockAndUpdate(new BlockPos(ox + bx, oy + by, oz + bz), Blocks.LAVA.defaultBlockState());
                    }
                }

                // Waterfall ledge at back-left of the water pool (by = 3..4): source at (1, 4, 1) spills
                // south over the stone ledge at (1, 3, 1) into flowing/falling water at (1, 4, 2) -> (1, 3, 2).
                for (int by = 3; by <= 4; by++) {
                    for (int bx = 0; bx <= 2; bx++)
                        level.setBlockAndUpdate(new BlockPos(ox + bx, oy + by, oz), Blocks.STONE.defaultBlockState());
                    for (int bz = 1; bz <= 2; bz++)
                        level.setBlockAndUpdate(new BlockPos(ox, oy + by, oz + bz), Blocks.STONE.defaultBlockState());
                }
                level.setBlockAndUpdate(new BlockPos(ox + 1, oy + 3, oz + 1), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(ox + 1, oy + 4, oz + 1), Blocks.WATER.defaultBlockState());

                // Solid stone corner finial at FLUID_MAX (8, 4, 4) so both corners of 9x5x5 are solid for grab.
                level.setBlockAndUpdate(FLUID_MAX, Blocks.STONE.defaultBlockState());
                return null;
            });
        });

        // Wait for the waterfall to settle into sloped + falling flowing water, then create/edit the fluid hologram.
        add(30, () -> {
            boolean flowSettled = onServer(player -> {
                var level = player.serverLevel();
                var slope = level.getBlockState(FLUID_MIN.offset(1, 4, 2)).getFluidState();
                var fall = level.getBlockState(FLUID_MIN.offset(1, 3, 2)).getFluidState();
                return !slope.isEmpty() && !slope.isSource() && !fall.isEmpty() && !fall.isSource();
            });
            check("waterfall settled with flowing and falling water", flowSettled, "no flowing water");

            fluidHoloId = onServer(player -> {
                var level = player.serverLevel();
                HologramSpec spec = Holograms.create(level, FLUID_HOLO_POS,
                        Blocks.AIR.defaultBlockState(), FLUID_HOLO_POS, player);
                return spec.id();
            });
            check("fluid hologram anchor created", !fluidHoloId.isEmpty(), "id=" + fluidHoloId);
            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4),
                        "foxstweaks hologram edit " + fluidHoloId);
                return null;
            });
        });

        // Grab the 9x5x5 fluid showcase through the real HologramGrab box path.
        add(10, () -> {
            Minecraft mc = Minecraft.getInstance();
            check("Studio opened for fluid grab", mc.screen instanceof HologramScreen, String.valueOf(mc.screen));
            if (!(mc.screen instanceof HologramScreen screen))
                return;
            editorRef = screen;
            screen.devClick("Grab");
            HologramGrab.handleKey(GLFW.GLFW_KEY_4);
            moveAndAim(new Vec3(-18.5, -59.5, 11.5), Vec3.atCenterOf(FLUID_MIN));
        });

        add(8, () -> {
            check("fluid grab corner 1 hits FLUID_MIN",
                    HologramGrab.triggerGrab(false) && FLUID_MIN.equals(HologramGrab.corner1()),
                    "c1=" + HologramGrab.corner1() + " hit=" + HologramGrab.currentHit());
            moveAndAim(new Vec3(-4.0, -55.0, 21.5), Vec3.atCenterOf(FLUID_MAX));
        });

        add(8, () -> {
            check("fluid grab corner 2 hits FLUID_MAX",
                    HologramGrab.triggerGrab(false) && FLUID_MAX.equals(HologramGrab.corner2()),
                    "c2=" + HologramGrab.corner2() + " hit=" + HologramGrab.currentHit());
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });

        add(14, () -> {
            if (editorRef == null)
                return;
            List<HologramLayer> layers = editorRef.current().layers();
            HologramLayer fluidLayer = null;
            for (HologramLayer l : layers) {
                if (l.kind() == HologramLayer.Kind.STRUCTURE)
                    fluidLayer = l;
            }
            check("grabbed fluid showcase into STRUCTURE layer", fluidLayer != null, "layers=" + layers.size());
            if (fluidLayer != null) {
                HologramStructure fs = HologramStructure.fromTag(fluidLayer.data());
                boolean hasWater = false;
                boolean hasFlowingWater = false;
                boolean hasLava = false;
                boolean hasWaterloggedStairs = false;
                boolean hasWaterloggedSlab = false;
                boolean hasKelp = false;
                if (fs != null) {
                    for (var pe : fs.palette()) {
                        if ("minecraft:water".equals(pe.id())) {
                            hasWater = true;
                            if (pe.props().containsKey("level"))
                                hasFlowingWater = true;
                        } else if ("minecraft:lava".equals(pe.id())) {
                            hasLava = true;
                        } else if ("minecraft:oak_stairs".equals(pe.id()) && "true".equals(pe.props().get("waterlogged"))) {
                            hasWaterloggedStairs = true;
                        } else if ("minecraft:oak_slab".equals(pe.id()) && "true".equals(pe.props().get("waterlogged"))) {
                            hasWaterloggedSlab = true;
                        } else if ("minecraft:kelp".equals(pe.id()) || "minecraft:kelp_plant".equals(pe.id())) {
                            hasKelp = true;
                        }
                    }
                }
                check("fluid structure palette contains water, flowing water, lava, waterlogged stairs/slab, kelp",
                        fs != null && fs.sizeX() == 9 && fs.sizeY() == 5 && fs.sizeZ() == 5
                                && hasWater && hasFlowingWater && hasLava
                                && hasWaterloggedStairs && hasWaterloggedSlab && hasKelp,
                        "water=" + hasWater + " flow=" + hasFlowingWater + " lava=" + hasLava
                                + " stairs=" + hasWaterloggedStairs + " slab=" + hasWaterloggedSlab + " kelp=" + hasKelp);
            }
            editorRef.devClick("Done");
        });

        // Position the grabbed fluid layer cleanly at FLUID_HOLO_POS (exact +11 X shift from real build).
        add(10, () -> {
            onServer(player -> {
                HologramSpec cur = Holograms.get(player.serverLevel(), fluidHoloId);
                if (cur == null)
                    return null;
                HologramLayer structLayer = null;
                for (HologramLayer l : cur.layers()) {
                    if (l.kind() == HologramLayer.Kind.STRUCTURE)
                        structLayer = l;
                }
                if (structLayer == null)
                    return null;
                HologramSpec clean = cur.at(cur.id(), FLUID_HOLO_POS, cur.owner(), cur.ownerName())
                        .withLayers(List.of(structLayer.withOffset(0.0F, 0.0F, 0.0F)));
                Holograms.save(player.serverLevel(), clean);
                return null;
            });
            // Aim at the REAL fluid build first.
            moveAndAim(FLUID_CAM_REAL, FLUID_LOOK_REAL);
        });

        add(10, () -> {
            shoot("structure-fluids-real.png");
            // Move to exact +11 X offset to aim at the CACHED fluid hologram from the identical relative angle.
            HologramRenderer.forceUncachedStructure = false;
            moveAndAim(FLUID_CAM_HOLO, FLUID_LOOK_HOLO);
        });

        add(10, () -> {
            shoot("structure-fluids-holo-cached.png");
            // Now render the same fluid hologram through the UNCACHED immediate path (SCANLINE/GRADIENT fallback).
            HologramRenderer.forceUncachedStructure = true;
        });

        add(8, () -> {
            shoot("structure-fluids-holo-uncached.png");
            HologramRenderer.forceUncachedStructure = false;
            // Apply a strong green TINT effect on the cached fluid hologram to confirm tint multiplies into fluids.
            onServer(player -> {
                HologramSpec cur = Holograms.get(player.serverLevel(), fluidHoloId);
                if (cur == null || cur.layers().isEmpty())
                    return null;
                HologramEffect greenTint = new HologramEffect(HologramEffect.Type.TINT, 1.0F, 0.0F,
                        0x22FF44, 0, 0.0F, HologramEffect.Trigger.ALWAYS, 0xFFFFFF);
                HologramLayer tinted = cur.layers().get(0).withEffects(List.of(greenTint));
                Holograms.save(player.serverLevel(), cur.withLayers(List.of(tinted)));
                return null;
            });
        });

        add(10, () -> {
            shoot("structure-fluids-holo-tinted.png");
            // Restore untinted hologram and take a wide side-by-side shot (real left, hologram right).
            onServer(player -> {
                HologramSpec cur = Holograms.get(player.serverLevel(), fluidHoloId);
                if (cur == null || cur.layers().isEmpty())
                    return null;
                HologramLayer plain = cur.layers().get(0).withEffects(List.of());
                Holograms.save(player.serverLevel(), cur.withLayers(List.of(plain)));
                return null;
            });
            moveAndAim(new Vec3(-5.0, -49.5, 29.0), new Vec3(-5.0, -58.5, 16.5));
        });

        add(10, () -> {
            shoot("structure-fluids-side-by-side.png");
            verifyFluidScreenshots();
            onServer(player -> {
                Holograms.remove(player.serverLevel(), fluidHoloId);
                return null;
            });
        });

        // ---- 8. Benchmark 16x16x16 mixed-block structure: cached VBO vs naive renderSingleBlock ----
        add(10, () -> {
            HologramStructure big = buildMixed16Structure();
            log("benchmark structure: " + big.displayName() + " nonAir=" + big.blockCount()
                    + " palette=" + big.palette().size()
                    + " nbtBytes=" + big.toTag().sizeInBytes());

            benchHoloId = onServer(player -> {
                var level = player.serverLevel();
                HologramLayer layer = HologramLayer.structure(big);
                HologramSpec spec = new HologramSpec("bench_16", new BlockPos(0, -60, -24),
                        0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                        true, 128, player.getUUID(), player.getGameProfile().getName(),
                        0.0F, 0.0F, HologramSpec.REVEAL_RISE, List.of(), List.of(layer));
                Holograms.save(level, spec);
                return spec.id();
            });
            moveAndAim(new Vec3(0.5, -52.0, 2.0), new Vec3(0.5, -52.0, -24.0));
        });

        // Warmup cached VBO (let it build once)
        add(12, () -> {
            HologramRenderer.forceUncachedStructure = false;
            buildsBeforeCached = HologramStructureMesh.buildCount();
            SAMPLES.clear();
            sampling = true;
        });

        // Sample cached frames (~30 ticks -> 40-100+ frames)
        add(30, () -> {
            sampling = false;
            buildsAfterCached = HologramStructureMesh.buildCount();
            double[] stats = summarizeMicros(SAMPLES);
            cachedMedianUs = stats[0];
            cachedMeanUs = stats[1];
            log(String.format(Locale.ROOT,
                    "benchmark CACHED: median=%.1f us/frame, mean=%.1f us/frame (%d frames, builds during sample=%d)",
                    cachedMedianUs, cachedMeanUs, SAMPLES.size(), buildsAfterCached - buildsBeforeCached));
            check("cached benchmark collected frames and did not rebuild per frame",
                    SAMPLES.size() >= 10 && buildsAfterCached == buildsBeforeCached,
                    "frames=" + SAMPLES.size() + " rebuilds=" + (buildsAfterCached - buildsBeforeCached));

            // Now switch to naive per-block renderSingleBlock
            HologramRenderer.forceUncachedStructure = true;
        });

        // Brief warmup for naive path
        add(5, () -> {
            SAMPLES.clear();
            sampling = true;
        });

        // Sample naive frames
        add(25, () -> {
            sampling = false;
            HologramRenderer.forceUncachedStructure = false;
            double[] stats = summarizeMicros(SAMPLES);
            naiveMedianUs = stats[0];
            naiveMeanUs = stats[1];
            double speedup = cachedMedianUs > 0.0 ? naiveMedianUs / cachedMedianUs : 0.0;
            log(String.format(Locale.ROOT,
                    "benchmark NAIVE:  median=%.1f us/frame, mean=%.1f us/frame (%d frames) -> %.1fx speedup",
                    naiveMedianUs, naiveMeanUs, SAMPLES.size(), speedup));
            check("cached VBO path is clearly cheaper than naive renderSingleBlock",
                    SAMPLES.size() >= 5 && cachedMedianUs > 0.0 && naiveMedianUs > cachedMedianUs * 3.0,
                    String.format(Locale.ROOT, "cached=%.1f us naive=%.1f us", cachedMedianUs, naiveMedianUs));

            // Swap to a 16x16x16 half-water structure and verify it stays cached & fast.
            HologramStructure halfWater = buildHalfWater16Structure();
            log("benchmark half-water structure: " + halfWater.displayName() + " nonAir=" + halfWater.blockCount()
                    + " palette=" + halfWater.palette().size()
                    + " nbtBytes=" + halfWater.toTag().sizeInBytes());
            waterBenchHoloId = onServer(player -> {
                var level = player.serverLevel();
                Holograms.remove(level, benchHoloId);
                HologramLayer layer = HologramLayer.structure(halfWater);
                HologramSpec spec = new HologramSpec("bench_16_water", new BlockPos(0, -60, -24),
                        0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                        true, 128, player.getUUID(), player.getGameProfile().getName(),
                        0.0F, 0.0F, HologramSpec.REVEAL_RISE, List.of(), List.of(layer));
                Holograms.save(level, spec);
                return spec.id();
            });
        });

        // Warmup half-water cached VBO (let it build & perform initial translucent sort once)
        add(12, () -> {
            HologramRenderer.forceUncachedStructure = false;
            buildsBeforeWater = HologramStructureMesh.buildCount();
            SAMPLES.clear();
            sampling = true;
        });

        // Sample half-water cached frames
        add(30, () -> {
            sampling = false;
            buildsAfterWater = HologramStructureMesh.buildCount();
            double[] stats = summarizeMicros(SAMPLES);
            log(String.format(Locale.ROOT,
                    "benchmark HALF-WATER CACHED: median=%.1f us/frame, mean=%.1f us/frame (%d frames, builds during sample=%d)",
                    stats[0], stats[1], SAMPLES.size(), buildsAfterWater - buildsBeforeWater));
            check("half-water 16^3 structure stays cached with zero per-frame rebuilds",
                    SAMPLES.size() >= 10 && buildsAfterWater == buildsBeforeWater && stats[0] < 1000.0,
                    String.format(Locale.ROOT, "frames=%d rebuilds=%d median=%.1f us",
                            SAMPLES.size(), buildsAfterWater - buildsBeforeWater, stats[0]));

            onServer(player -> {
                Holograms.remove(player.serverLevel(), waterBenchHoloId);
                return null;
            });
        });

        add(10, () -> {
            check("VBO cache freed after hologram removal", HologramStructureMesh.cacheSize() == 0,
                    "cacheSize=" + HologramStructureMesh.cacheSize());
        });

        // ---- 9. Write report and exit ----
        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-structure-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    /** Builds a 16x16x16 structure of mixed blocks (~2600 non-air blocks, multiple render types & states). */
    private static HologramStructure buildMixed16Structure() {
        int w = 16, h = 16, d = 16;
        List<HologramStructure.PaletteEntry> palette = List.of(
                new HologramStructure.PaletteEntry("minecraft:stone_bricks", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:oak_planks", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:glass", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:oak_stairs", Map.of("facing", "north")),
                new HologramStructure.PaletteEntry("minecraft:oak_stairs", Map.of("facing", "east")),
                new HologramStructure.PaletteEntry("minecraft:bookshelf", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:sea_lantern", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:oak_leaves", Map.of("persistent", "true"))
        );
        int[] positions = new int[w * h * d];
        int[] palIdx = new int[w * h * d];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    // Mix of solid floors, checkered interior rooms, glass windows, leaves & stairs (~2500 blocks)
                    boolean shell = x == 0 || x == w - 1 || z == 0 || z == d - 1 || y == 0 || y == h - 1;
                    boolean interiorFloor = (y % 4 == 0);
                    boolean pillar = (x % 4 == 0 && z % 4 == 0);
                    boolean detail = ((x + y + z) % 3 == 0) && (x > 2 && x < w - 3 && z > 2 && z < d - 3);
                    if (!shell && !interiorFloor && !pillar && !detail)
                        continue;
                    int p;
                    if (shell && (y % 4 == 2) && (x % 2 == 0 || z % 2 == 0))
                        p = 2; // glass windows
                    else if (y == h - 1 && (x == 0 || x == w - 1))
                        p = 3; // stairs
                    else if (y == h - 1 && (z == 0 || z == d - 1))
                        p = 4; // stairs
                    else if (pillar && y % 4 == 2)
                        p = 6; // sea lantern
                    else if (detail)
                        p = ((x + y) % 2 == 0) ? 5 : 7; // bookshelf / leaves
                    else
                        p = ((x + z) % 2 == 0) ? 0 : 1; // stone bricks / oak planks
                    positions[count] = (y * d + z) * w + x;
                    palIdx[count] = p;
                    count++;
                }
            }
        }
        return new HologramStructure(w, h, d, palette,
                Arrays.copyOf(positions, count), Arrays.copyOf(palIdx, count), Map.of());
    }

    /** Builds a 16x16x16 structure whose lower half (y=0..7) is a stone basin filled with water (~2048 water blocks). */
    private static HologramStructure buildHalfWater16Structure() {
        int w = 16, h = 16, d = 16;
        List<HologramStructure.PaletteEntry> palette = List.of(
                new HologramStructure.PaletteEntry("minecraft:stone_bricks", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:glass", Map.of()),
                new HologramStructure.PaletteEntry("minecraft:water", Map.of())
        );
        int[] positions = new int[w * h * d];
        int[] palIdx = new int[w * h * d];
        int count = 0;
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < d; z++) {
                for (int x = 0; x < w; x++) {
                    boolean rim = x == 0 || x == w - 1 || z == 0 || z == d - 1;
                    int p = -1;
                    if (y == 0) {
                        p = 0; // stone brick floor
                    } else if (y < 8) {
                        p = rim ? ((x + z) % 2 == 0 ? 1 : 0) : 2; // glass/stone walls + solid 14x7x14 water interior
                    } else if (y == 8 && !rim) {
                        p = 2; // top water layer (brings water count to ~1568 blocks, ~half the filled structure)
                    } else if (rim && (y == 8 || x == 0 && z == 0 || x == w - 1 && z == d - 1)) {
                        p = 0; // top basin rim + corner pillars
                    }
                    if (p < 0)
                        continue;
                    positions[count] = (y * d + z) * w + x;
                    palIdx[count] = p;
                    count++;
                }
            }
        }
        return new HologramStructure(w, h, d, palette,
                Arrays.copyOf(positions, count), Arrays.copyOf(palIdx, count), Map.of());
    }

    private static void verifyFluidScreenshots() {
        Path shotsDir = Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
        Path realPath = shotsDir.resolve("structure-fluids-real.png");
        Path cachedPath = shotsDir.resolve("structure-fluids-holo-cached.png");
        Path uncachedPath = shotsDir.resolve("structure-fluids-holo-uncached.png");
        Path tintedPath = shotsDir.resolve("structure-fluids-holo-tinted.png");

        boolean filesExist = Files.exists(realPath) && Files.exists(cachedPath)
                && Files.exists(uncachedPath) && Files.exists(tintedPath);
        check("fluid comparison screenshots written", filesExist,
                "real=" + Files.exists(realPath) + " cached=" + Files.exists(cachedPath)
                        + " uncached=" + Files.exists(uncachedPath) + " tinted=" + Files.exists(tintedPath));
        if (!filesExist)
            return;

        try (NativeImage real = NativeImage.read(Files.newInputStream(realPath));
             NativeImage cached = NativeImage.read(Files.newInputStream(cachedPath));
             NativeImage uncached = NativeImage.read(Files.newInputStream(uncachedPath));
             NativeImage tinted = NativeImage.read(Files.newInputStream(tintedPath))) {

            int w = real.getWidth();
            int h = real.getHeight();
            // Center crop covering only the aimed-at build (excludes the neighbouring comparison build at screen edges).
            int x0 = (w * 3) / 10;
            int x1 = (w * 7) / 10;
            int y0 = h / 5;
            int y1 = (h * 9) / 10;

            int realWater = 0;
            int realLava = 0;
            int cachedWater = 0;
            int cachedLava = 0;
            int cachedWaterHit = 0;
            int cachedLavaHit = 0;
            int uncachedWaterHit = 0;
            int uncachedLavaHit = 0;

            long waterRgbDiffSum = 0L;
            long cachedLavaRSum = 0L;
            long tintedLavaRSum = 0L;
            long tintedWaterGSum = 0L;
            long tintedWaterBSum = 0L;

            for (int y = y0; y < y1; y++) {
                for (int x = x0; x < x1; x++) {
                    int rp = real.getPixelRGBA(x, y);
                    int rr = rp & 0xFF, rg = (rp >> 8) & 0xFF, rb = (rp >> 16) & 0xFF;

                    int cp = cached.getPixelRGBA(x, y);
                    int cr = cp & 0xFF, cg = (cp >> 8) & 0xFF, cb = (cp >> 16) & 0xFF;

                    int up = uncached.getPixelRGBA(x, y);
                    int ur = up & 0xFF, ug = (up >> 8) & 0xFF, ub = (up >> 16) & 0xFF;

                    int tp = tinted.getPixelRGBA(x, y);
                    int tr = tp & 0xFF, tg = (tp >> 8) & 0xFF, tb = (tp >> 16) & 0xFF;

                    boolean rIsWater = isWaterPixel(rr, rg, rb);
                    boolean rIsLava = isLavaPixel(rr, rg, rb);
                    boolean cIsWater = isWaterPixel(cr, cg, cb);
                    boolean cIsLava = isLavaPixel(cr, cg, cb);
                    boolean uIsWater = isWaterPixel(ur, ug, ub);
                    boolean uIsLava = isLavaPixel(ur, ug, ub);

                    if (cIsWater)
                        cachedWater++;
                    if (cIsLava)
                        cachedLava++;

                    if (rIsWater) {
                        realWater++;
                        if (cIsWater) {
                            cachedWaterHit++;
                            waterRgbDiffSum += Math.abs(rr - cr) + Math.abs(rg - cg) + Math.abs(rb - cb);
                        }
                        if (uIsWater)
                            uncachedWaterHit++;
                        tintedWaterGSum += tg;
                        tintedWaterBSum += tb;
                    }
                    if (rIsLava) {
                        realLava++;
                        if (cIsLava)
                            cachedLavaHit++;
                        if (uIsLava)
                            uncachedLavaHit++;
                        cachedLavaRSum += cr;
                        tintedLavaRSum += tr;
                    }
                }
            }

            double cachedWaterOverlap = realWater > 0 ? (double) cachedWaterHit / realWater : 0.0;
            double cachedLavaOverlap = realLava > 0 ? (double) cachedLavaHit / realLava : 0.0;
            double uncachedWaterOverlap = realWater > 0 ? (double) uncachedWaterHit / realWater : 0.0;
            double uncachedLavaOverlap = realLava > 0 ? (double) uncachedLavaHit / realLava : 0.0;
            double waterMeanDiff = cachedWaterHit > 0 ? (double) waterRgbDiffSum / (cachedWaterHit * 3.0) : 999.0;
            double tintedWaterG = realWater > 0 ? (double) tintedWaterGSum / realWater : 0.0;
            double tintedWaterB = realWater > 0 ? (double) tintedWaterBSum / realWater : 0.0;
            double cachedLavaR = realLava > 0 ? (double) cachedLavaRSum / realLava : 0.0;
            double tintedLavaR = realLava > 0 ? (double) tintedLavaRSum / realLava : 0.0;

            log(String.format(Locale.ROOT,
                    "fluid pixels: realWater=%d cachedWater=%d (overlap=%.2f, meanRgbDiff=%.1f) uncachedWaterOverlap=%.2f",
                    realWater, cachedWater, cachedWaterOverlap, waterMeanDiff, uncachedWaterOverlap));
            log(String.format(Locale.ROOT,
                    "fluid pixels: realLava=%d cachedLava=%d (overlap=%.2f) uncachedLavaOverlap=%.2f",
                    realLava, cachedLava, cachedLavaOverlap, uncachedLavaOverlap));
            log(String.format(Locale.ROOT,
                    "fluid tint:   tintedWaterG=%.1f tintedWaterB=%.1f cachedLavaR=%.1f tintedLavaR=%.1f",
                    tintedWaterG, tintedWaterB, cachedLavaR, tintedLavaR));

            check("real build screenshot shows clear water and lava pools",
                    realWater >= 2000 && realLava >= 2000,
                    "realWater=" + realWater + " realLava=" + realLava);
            check("cached hologram draws water where world has water",
                    cachedWaterOverlap >= 0.70 && cachedWater >= realWater * 0.65 && cachedWater <= realWater * 1.40,
                    String.format(Locale.ROOT, "overlap=%.2f cached=%d real=%d", cachedWaterOverlap, cachedWater, realWater));
            check("cached hologram draws lava where world has lava",
                    cachedLavaOverlap >= 0.75 && cachedLava >= realLava * 0.70 && cachedLava <= realLava * 1.35,
                    String.format(Locale.ROOT, "overlap=%.2f cached=%d real=%d", cachedLavaOverlap, cachedLava, realLava));
            check("cached water pool matches world colour without internal-face darkening",
                    waterMeanDiff < 28.0,
                    String.format(Locale.ROOT, "meanRgbDiff=%.1f", waterMeanDiff));
            check("uncached hologram path draws water and lava",
                    uncachedWaterOverlap >= 0.70 && uncachedLavaOverlap >= 0.75,
                    String.format(Locale.ROOT, "water=%.2f lava=%.2f", uncachedWaterOverlap, uncachedLavaOverlap));
            check("hologram tint multiplies into fluids (water turns green, lava red drops)",
                    tintedWaterG > tintedWaterB + 15.0 && cachedLavaR > 120.0 && tintedLavaR < cachedLavaR * 0.60,
                    String.format(Locale.ROOT, "waterG=%.1f waterB=%.1f lavaR=%.1f->%.1f",
                            tintedWaterG, tintedWaterB, cachedLavaR, tintedLavaR));
        } catch (IOException e) {
            check("read fluid screenshots", false, e.toString());
        }
    }

    private static boolean isWaterPixel(int r, int g, int b) {
        return b > r + 25 && b > 90 && b >= g;
    }

    private static boolean isLavaPixel(int r, int g, int b) {
        return r > b + 50 && r > 150 && g > 40;
    }

    private static double[] summarizeMicros(List<Long> nanosList) {
        if (nanosList.isEmpty())
            return new double[] { 0.0, 0.0 };
        long[] arr = new long[nanosList.size()];
        double sum = 0.0;
        for (int i = 0; i < arr.length; i++) {
            arr[i] = nanosList.get(i);
            sum += arr[i];
        }
        Arrays.sort(arr);
        double median = arr[arr.length / 2] / 1000.0;
        double mean = (sum / arr.length) / 1000.0;
        return new double[] { median, mean };
    }

    private static void moveAndAim(Vec3 standPos, Vec3 lookTarget) {
        Vec3 eye = standPos.add(0.0, 1.62, 0.0);
        Vec3 d = lookTarget.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        float pitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(d.y, horiz))), -89.0F, 89.0F);
        boolean fly = standPos.y > -59.5;
        onServer(player -> {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = fly;
            player.onUpdateAbilities();
            player.teleportTo(player.serverLevel(), standPos.x, standPos.y, standPos.z, yaw, pitch);
            return null;
        });
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = fly;
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setPos(standPos.x, standPos.y, standPos.z);
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
    }

    private static void shoot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramStructureCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

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
            LOG.error("HologramStructureCheck crashed", t);
            check("no exception", false, t.toString());
            HologramRenderer.forceUncachedStructure = false;
            sampling = false;
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-hologramstructurecheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.NORMAL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramStructureCheck() {
    }
}
