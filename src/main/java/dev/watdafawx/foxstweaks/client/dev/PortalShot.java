package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.dimension.ClientPortalColors;
import dev.watdafawx.foxstweaks.dimension.CopyPortals;
import dev.watdafawx.foxstweaks.dimension.OverworldCopy;
import dev.watdafawx.foxstweaks.dimension.PortalColors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;

/**
 * Dev-only: flat world, builds a frame of the configured block, lights it through the real right-click
 * event, rides the portal into the overworld copy and back, dyes it, and checks that Nether portals are
 * left alone. Writes {@code run-portal/foxstweaks-portal-check.txt}. {@code build.bat runPortalShot}.
 */
public final class PortalShot {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int Y = -60;

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(PortalShot::onTick);
        scenarios();
    }

    // ---------------------------------------------------------------------------------------------

    private static void scenarios() {
        add(80, () -> onServer(p -> {
            p.setGameMode(GameType.CREATIVE);
            p.getAbilities().flying = false;
            ServerLevel level = p.serverLevel();
            // Frame: x 5..8, interior x 6..7, y -60..-58, in the plane z=0.
            for (int x = 5; x <= 8; x++) {
                for (int y = Y - 1; y <= Y + 3; y++) {
                    boolean edge = x == 5 || x == 8 || y == Y - 1 || y == Y + 3;
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), edge ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
            p.teleportTo(6.5, Y, -3.5);
            return null;
        }));

        add(10, () -> {
            boolean cancelled = onServer(p -> {
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
                BlockPos click = new BlockPos(6, Y - 1, 0);
                var ev = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, click,
                        new BlockHitResult(Vec3.atCenterOf(click), Direction.UP, click, false)));
                return ev.isCanceled();
            });
            check("1 flint and steel on a crying obsidian frame is consumed by the mod", cancelled, "");
            check("1 interior is portal blocks", onServer(p -> portalCount(p.serverLevel(), 5, 8, 0) == 6), "");
        });

        add(20, () -> {
            onServer(p -> {
                p.serverLevel().setBlockAndUpdate(new BlockPos(6, Y + 1, 1), Blocks.STONE.defaultBlockState());
                p.serverLevel().setBlockAndUpdate(new BlockPos(6, Y + 1, 1), Blocks.AIR.defaultBlockState());
                p.serverLevel().setBlockAndUpdate(new BlockPos(6, Y + 1, -1), Blocks.STONE.defaultBlockState());
                p.serverLevel().setBlockAndUpdate(new BlockPos(6, Y + 1, -1), Blocks.AIR.defaultBlockState());
                return null;
            });
            check("2 portal survives neighbour updates", onServer(p -> portalCount(p.serverLevel(), 5, 8, 0) == 6), "");
        });

        add(10, () -> {
            boolean ok = onServer(p -> {
                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BLUE_DYE));
                BlockPos portal = new BlockPos(6, Y, 0);
                var ev = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, portal,
                        new BlockHitResult(Vec3.atCenterOf(portal), Direction.NORTH, portal, false)));
                return ev.isCanceled() && PortalColors.get(p.serverLevel()).dyeAt(new BlockPos(7, Y + 2, 0)) == 11;
            });
            check("3 dye recolours the whole portal (blue, id 11)", ok, "");
            p(() -> p0().teleportTo(6.5, Y, -4.5));
        });

        add(30, () -> {
            var mc = Minecraft.getInstance();
            check("4 client received the colour", ClientPortalColors.tint(new BlockPos(7, Y + 2, 0)) == (net.minecraft.world.item.DyeColor.BLUE.getTextureDiffuseColor() & 0xFFFFFF),
                    Integer.toHexString(ClientPortalColors.tint(new BlockPos(7, Y + 2, 0))));
            mc.options.hideGui = true;
            shot("portalshot-1-blue-overworld.png");
        });

        // Walk into the portal.
        add(30, () -> onServer(p -> {
            p.teleportTo(6.5, Y, 0.5);
            return null;
        }));

        add(260, () -> {
            var state = onServer(p -> p.level().dimension().location() + " @ " + p.blockPosition());
            check("5 stepping into the portal lands in foxstweaks:overworld_copy", state.startsWith("foxstweaks:overworld_copy"), state);
            check("5 an arrival portal of ours exists in the copy", onServer(p -> countOurPortals(p.serverLevel(), p.blockPosition()) > 0), "");
            check("5 the arrival portal is on the surface, not in a cave", onServer(p -> {
                BlockPos portal = nearestPortal(p.serverLevel(), p.blockPosition());
                int height = p.serverLevel().getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, portal.getX(), portal.getZ());
                return height - portal.getY() <= 6;
            }), "");
            onServer(p -> {
                ServerLevel lv = p.serverLevel();
                BlockPos portal = nearestPortal(lv, p.blockPosition());
                LOG.info("PortalCheck arrival: player {} portal {}", p.position(), portal);

                if (portal != null) {
                    var sb = new StringBuilder();

                    for (int dz = -6; dz <= 6; dz++) {
                        for (int dx = -6; dx <= 6; dx++) {
                            sb.append(String.format("%4d", lv.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, portal.getX() + dx, portal.getZ() + dz) - portal.getY()));
                        }

                        sb.append((char) 10);
                    }

                    LOG.info("PortalCheck heights relative to portal y: {}", sb);
                }

                return null;
            });
            check("5 the copy is a real overworld type", onServer(p -> p.level().dimensionType().natural() && p.level().dimensionType().hasSkyLight()), "");
        });

        add(20, () -> {
            boolean ok = onServer(p -> {
                BlockPos portal = nearestPortal(p.serverLevel(), p.blockPosition());

                if (portal == null) {
                    return false;
                }

                p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.RED_DYE));
                var ev = NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, portal,
                        new BlockHitResult(Vec3.atCenterOf(portal), Direction.NORTH, portal, false)));
                return ev.isCanceled() && PortalColors.get(p.serverLevel()).dyeAt(portal) == 14;
            });
            check("6 dye works inside the copy too", ok, "");
            shot("portalshot-2-copy.png");
        });

        // Step out, then back in, to ride home.
        add(10, () -> onServer(p -> {
            BlockPos portal = nearestPortal(p.serverLevel(), p.blockPosition());
            p.teleportTo(portal.getX() + 0.5, portal.getY(), portal.getZ() + 4.5);
            return null;
        }));

        add(40, () -> onServer(p -> {
            BlockPos portal = nearestPortal(p.serverLevel(), p.blockPosition());
            p.teleportTo(portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
            return null;
        }));

        add(120, () -> {
            var state = onServer(p -> p.level().dimension().location() + " @ " + p.blockPosition());
            check("7 riding the copy portal returns to the overworld", state.startsWith("minecraft:overworld"), state);
            check("7 it reused the original portal (arrived beside x 6..7)", onServer(p -> Math.abs(p.getX() - 6.5) < 3 && Math.abs(p.getZ()) < 3), state);
            check("7 still exactly one portal there", onServer(p -> portalCount(p.serverLevel(), 5, 8, 0) == 6), "");
        });

        // Nether portal must be untouched.
        add(10, () -> onServer(p -> {
            ServerLevel level = p.serverLevel();
            for (int x = 20; x <= 23; x++) {
                for (int y = Y - 1; y <= Y + 3; y++) {
                    boolean edge = x == 20 || x == 23 || y == Y - 1 || y == Y + 3;
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), edge ? Blocks.OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            level.setBlockAndUpdate(new BlockPos(21, Y, 0), Blocks.FIRE.defaultBlockState());
            return null;
        }));

        add(20, () -> {
            check("8 vanilla obsidian frame still makes a Nether portal", onServer(p -> portalCount(p.serverLevel(), 20, 23, 0) == 6), "");
            check("8 and the mod does not claim it", onServer(p -> CopyPortals.shapeAround(p.serverLevel(), new BlockPos(21, Y, 0),
                    p.serverLevel().getBlockState(new BlockPos(21, Y, 0))).isEmpty()), "");
        });

        add(10, () -> {
            // Our portal (x 5..8) is closer to x=6 than the Nether one (x 20..23); the Nether search must skip it.
            BlockPos found = onServer(p -> p.serverLevel().getPortalForcer()
                    .findClosestPortalPosition(new BlockPos(6, Y, 0), false, p.serverLevel().getWorldBorder()).orElse(null));
            check("8a Nether portal search skips our portal", found != null && found.getX() >= 20, String.valueOf(found));
            var portal = (net.minecraft.world.level.block.NetherPortalBlock) Blocks.NETHER_PORTAL;

            check("8b instant teleport: wait is 0 in our portal", onServer(p -> {
                p.setGameMode(GameType.SURVIVAL);
                p.teleportTo(6.5, Y, 0.5);
                return portal.getPortalTransitionTime(p.serverLevel(), p) == 0;
            }), "");
            check("8b Nether portal keeps the vanilla wait", onServer(p -> {
                p.teleportTo(21.5, Y, 0.5);
                return portal.getPortalTransitionTime(p.serverLevel(), p) >= 1;
            }), "");
            Config.OVERWORLD_COPY_INSTANT.set(false);
            check("8b instant off: vanilla wait in our portal", onServer(p -> {
                p.teleportTo(6.5, Y, 0.5);
                return portal.getPortalTransitionTime(p.serverLevel(), p) >= 1;
            }), "");
            Config.OVERWORLD_COPY_INSTANT.set(true);
        });

        // Toggle off.
        add(10, () -> {
            Config.OVERWORLD_COPY.set(false);
            check("9 config off: the portal is no longer ours", onServer(p -> CopyPortals.shapeAround(p.serverLevel(), new BlockPos(6, Y, 0),
                    p.serverLevel().getBlockState(new BlockPos(6, Y, 0))).isEmpty()), "");
            Config.OVERWORLD_COPY.set(true);
            check("9 config on again: it is", onServer(p -> CopyPortals.shapeAround(p.serverLevel(), new BlockPos(6, Y, 0),
                    p.serverLevel().getBlockState(new BlockPos(6, Y, 0))).isPresent()), "");
            check("9 dimension is registered", onServer(p -> p.getServer().getLevel(OverworldCopy.COPY) != null), "");
        });

        // Flat surface option: a second portal, built with the pad switched on.
        add(10, () -> onServer(p -> {
            Config.OVERWORLD_COPY_FLAT.set(true);
            p.setGameMode(GameType.CREATIVE);
            ServerLevel level = p.serverLevel();
            for (int x = 400; x <= 403; x++) {
                for (int y = Y - 1; y <= Y + 3; y++) {
                    boolean edge = x == 400 || x == 403 || y == Y - 1 || y == Y + 3;
                    level.setBlockAndUpdate(new BlockPos(x, y, 0), edge ? Blocks.CRYING_OBSIDIAN.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
            p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
            BlockPos click = new BlockPos(401, Y - 1, 0);
            NeoForge.EVENT_BUS.post(new PlayerInteractEvent.RightClickBlock(p, InteractionHand.MAIN_HAND, click,
                    new BlockHitResult(Vec3.atCenterOf(click), Direction.UP, click, false)));
            p.setPortalCooldown(0);
            p.teleportTo(401.5, Y, 0.5);
            return null;
        }));

        add(300, () -> {
            check("10 second portal also lands in the copy", onServer(p -> p.level().dimension() == OverworldCopy.COPY), "");
            check("10 flat pad around the new portal", onServer(p -> {
                ServerLevel level = p.serverLevel();
                BlockPos any = nearestPortal(level, p.blockPosition());

                if (any == null) {
                    return false;
                }

                int x0 = any.getX(), y0 = any.getY(), z0 = any.getZ();

                for (BlockPos pos : BlockPos.betweenClosed(any.offset(-6, -3, -6), any.offset(6, 3, 6))) {
                    if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL)) {
                        x0 = Math.min(x0, pos.getX());
                        y0 = Math.min(y0, pos.getY());
                        z0 = Math.min(z0, pos.getZ());
                    }
                }

                int wrong = 0;

                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (dz == 0) {
                            continue; // the frame's own plane
                        }

                        int h = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x0 + dx, z0 + dz);

                        if (h != y0 || !level.getBlockState(new BlockPos(x0 + dx, y0 - 1, z0 + dz)).isSolid()) {
                            wrong++;
                        }
                    }
                }

                LOG.info("PortalCheck flat pad origin {} {} {} wrong columns {}", x0, y0, z0, wrong);
                return wrong == 0;
            }), "");
            shot("portalshot-3-flat-pad.png");
            Config.OVERWORLD_COPY_FLAT.set(false);
        });

        add(10, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-portal-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------------------------------------

    private static int portalCount(ServerLevel level, int x0, int x1, int z) {
        int n = 0;

        for (int x = x0; x <= x1; x++) {
            for (int y = Y - 1; y <= Y + 3; y++) {
                if (level.getBlockState(new BlockPos(x, y, z)).is(Blocks.NETHER_PORTAL)) {
                    n++;
                }
            }
        }

        return n;
    }

    private static BlockPos nearestPortal(ServerLevel level, BlockPos around) {
        BlockPos best = null;

        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-8, -6, -8), around.offset(8, 6, 8))) {
            if (level.getBlockState(pos).is(Blocks.NETHER_PORTAL) && (best == null || pos.distSqr(around) < best.distSqr(around))) {
                best = pos.immutable();
            }
        }

        if (best == null) {
            LOG.info("PortalCheck no portal near {} in {}", around, level.dimension().location());
            int portals = 0, frames = 0;
            BlockPos firstPortal = null, firstFrame = null;

            for (BlockPos pos : BlockPos.betweenClosed(around.offset(-30, -40, -30), around.offset(30, 40, 30))) {
                var st = level.getBlockState(pos);

                if (st.is(Blocks.NETHER_PORTAL)) {
                    portals++;
                    firstPortal = firstPortal == null ? pos.immutable() : firstPortal;
                } else if (st.is(Blocks.CRYING_OBSIDIAN)) {
                    frames++;
                    firstFrame = firstFrame == null ? pos.immutable() : firstFrame;
                }
            }

            LOG.info("PortalCheck wide scan: portal blocks {} (first {}), frame blocks {} (first {})", portals, firstPortal, frames, firstFrame);
        }

        return best;
    }

    private static int countOurPortals(ServerLevel level, BlockPos around) {
        int n = 0;

        for (BlockPos pos : BlockPos.betweenClosed(around.offset(-8, -6, -8), around.offset(8, 6, 8))) {
            if (CopyPortals.shapeAround(level, pos, level.getBlockState(pos)).isPresent()) {
                n++;
            }
        }

        return n;
    }

    private static ServerPlayer p0() {
        var mc = Minecraft.getInstance();
        return mc.getSingleplayerServer().getPlayerList().getPlayer(mc.player.getUUID());
    }

    private static void p(Runnable r) {
        Minecraft.getInstance().getSingleplayerServer().submit(r).join();
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
        LOG.info("PortalCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) {
            failures++;
        }

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

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size()) {
            return;
        }

        tick++;

        if (nextAt == 0) {
            nextAt = tick + STEPS.get(0).waitTicks;
        }

        if (tick < nextAt) {
            return;
        }

        var step = STEPS.get(index++);
        LOG.info("PortalCheck step {} player {} {}", index, mc.player.level().dimension().location(), mc.player.position());

        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("PortalCheck crashed", t);
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
        var world = "foxstweaks-portalshot";
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

    private PortalShot() {
    }
}
