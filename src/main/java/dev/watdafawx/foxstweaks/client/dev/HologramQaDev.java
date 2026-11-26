package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramCreatorScreen;
import dev.watdafawx.foxstweaks.client.HologramEditorPrefs;
import dev.watdafawx.foxstweaks.client.HologramGrab;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.web.HologramWebSession;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.function.Function;

/**
 * Dev-only interactive QA driver (not a pass/fail check). It makes a flat creative world, then stays open and
 * executes one-line commands that something outside the game appends to {@code qa-cmd.txt} in the game
 * directory; results go to {@code qa-out.txt}, screenshots to the game dir. A human or an agent on the same
 * machine drives the real browser / the real window around it.
 *
 * <p>Enable with {@code -Dfoxstweaks.hologramQa=true} ({@code build.bat runHologramQa}).
 * Commands: {@code house}, {@code tp x y z yaw pitch}, {@code cmd <server command>}, {@code holo <block>},
 * {@code edit}, {@code web}, {@code creator}, {@code studio}, {@code grabhouse}, {@code shot <name>},
 * {@code gui <n>}, {@code time <ticks>}, {@code aim x y z}, {@code state}, {@code quit}.
 */
public final class HologramQaDev {
    private static final Logger LOG = LogUtils.getLogger();

    // 13 x 6 x 7 box: house on the west side, water yard, stone divider, lava yard on the east side.
    public static final BlockPos BOX_MIN = new BlockPos(0, -60, 0);
    public static final BlockPos BOX_MAX = new BlockPos(12, -55, 6);

    private static boolean worldRequested;
    private static int tick;
    private static long cmdSize = -1;
    private record Later(int ticks, Runnable run) {
    }

    private static final Queue<Later> DELAYED = new ArrayDeque<>();
    private static int delay = -1;
    private static String hologramId = "";
    private static HologramScreen editor;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramQaDev::onTick);
    }

    private static Path gameDir() {
        return Minecraft.getInstance().gameDirectory.toPath();
    }

    private static void out(String line) {
        LOG.info("HologramQa {}", line);
        try {
            Files.writeString(gameDir().resolve("qa-out.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }
        if (mc.player == null || mc.getSingleplayerServer() == null)
            return;
        tick++;
        if (tick == 40) {
            out("ready");
        }
        if (!DELAYED.isEmpty()) {
            if (delay < 0)
                delay = DELAYED.peek().ticks;
            if (delay > 0) {
                delay--;
                return;
            }
            Later l = DELAYED.poll();
            delay = -1;
            try {
                l.run.run();
            } catch (Throwable t) {
                LOG.error("qa step failed", t);
                out("ERROR " + t);
            }
            return;
        }
        if (tick > 40 && tick % 5 == 0)
            poll();
    }

    private static void poll() {
        Path file = gameDir().resolve("qa-cmd.txt");
        try {
            if (!Files.exists(file))
                return;
            long size = Files.size(file);
            if (size == cmdSize)
                return;
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            cmdSize = size;
            int handled = handledLines;
            handledLines = lines.size();
            for (int i = handled; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (!line.isEmpty())
                    run(line);
            }
        } catch (IOException e) {
            LOG.warn("qa poll", e);
        }
    }

    private static int handledLines;

    private static void later(int ticks, Runnable r) {
        DELAYED.add(new Later(ticks, r));
    }

    private static void run(String line) {
        out("> " + line);
        String[] p = line.split("\\s+", 2);
        String arg = p.length > 1 ? p[1] : "";
        Minecraft mc = Minecraft.getInstance();
        try {
            switch (p[0]) {
                case "house" -> buildHouse();
                case "tp" -> {
                    String[] a = arg.split("\\s+");
                    double x = Double.parseDouble(a[0]), y = Double.parseDouble(a[1]), z = Double.parseDouble(a[2]);
                    float yaw = a.length > 3 ? Float.parseFloat(a[3]) : 0F;
                    float pitch = a.length > 4 ? Float.parseFloat(a[4]) : 0F;
                    place(new Vec3(x, y, z), yaw, pitch);
                }
                case "aim" -> {
                    String[] a = arg.split("\\s+");
                    aimAt(new Vec3(Double.parseDouble(a[0]), Double.parseDouble(a[1]), Double.parseDouble(a[2])));
                }
                case "cmd" -> command(arg);
                case "holo" -> {
                    command("foxstweaks hologram add " + (arg.isEmpty() ? "minecraft:amethyst_block" : arg));
                    later(5, () -> {
                        hologramId = onServer(pl -> {
                            List<HologramSpec> all = Holograms.list(pl.serverLevel());
                            return all.isEmpty() ? "" : all.get(all.size() - 1).id();
                        });
                        out("hologram " + hologramId);
                    });
                }
                case "edit" -> {
                    HologramEditorPrefs.setStartInCreator(false);
                    command("foxstweaks hologram edit " + (arg.isEmpty() ? hologramId : arg));
                    later(10, () -> {
                        editor = mc.screen instanceof HologramScreen s ? s : null;
                        out("editor " + editor + " screen=" + mc.screen);
                    });
                }
                case "creator" -> {
                    HologramEditorPrefs.setStartInCreator(true);
                    command("foxstweaks hologram edit " + (arg.isEmpty() ? hologramId : arg));
                    later(10, () -> out("screen=" + mc.screen));
                }
                case "web" -> {
                    // Real path: the Web button (opens the system browser, copies the link).
                    HologramWebSession.devNoBrowser = false;
                    boolean clicked = false;
                    if (mc.screen instanceof HologramScreen s)
                        clicked = s.devClick("Web");
                    else if (mc.screen instanceof HologramCreatorScreen c)
                        clicked = c.devClick("Web", 0);
                    final boolean ok = clicked;
                    later(10, () -> {
                        HologramWebSession cur = HologramWebSession.current();
                        out("web clicked=" + ok + " url=" + (cur == null ? "none" : cur.url()));
                    });
                }
                case "weburl" -> {
                    HologramWebSession cur = HologramWebSession.current();
                    out("url=" + (cur == null ? "none" : cur.url()));
                }
                case "grabhouse" -> grabHouse();
                case "shot" -> {
                    Screenshot.grab(mc.gameDirectory, arg.isEmpty() ? "qa.png" : arg, mc.getMainRenderTarget(),
                            m -> {
                            });
                    out("shot " + arg);
                }
                case "gui" -> {
                    mc.options.guiScale().set(Integer.parseInt(arg));
                    mc.resizeDisplay();
                }
                case "size" -> {
                    String[] a = arg.split("x");
                    mc.getWindow().setWindowed(Integer.parseInt(a[0]), Integer.parseInt(a[1]));
                }
                case "time" -> onServer(pl -> {
                    pl.serverLevel().setDayTime(Long.parseLong(arg));
                    return null;
                });
                case "state" -> out("screen=" + mc.screen + " holo=" + hologramId + " web="
                        + (HologramWebSession.current() == null ? "none" : HologramWebSession.current().url())
                        + " gui=" + mc.options.guiScale().get() + " win=" + mc.getWindow().getWidth() + "x"
                        + mc.getWindow().getHeight());
                case "holos" -> {
                    String all = onServer(pl -> {
                        StringBuilder sb = new StringBuilder();
                        for (HologramSpec h : Holograms.list(pl.serverLevel()))
                            sb.append(h.id()).append('@').append(h.pos().toShortString()).append(' ');
                        return sb.toString();
                    });
                    out("holograms " + all);
                }
                case "nearholo" -> {
                    // Stand next to the current hologram (a save is refused beyond Holograms.REACH + 8 blocks).
                    String at = onServer(pl -> {
                        for (HologramSpec h : Holograms.list(pl.serverLevel()))
                            if (h.id().equals(hologramId))
                                return h.pos().getX() + " " + h.pos().getY() + " " + h.pos().getZ();
                        return "";
                    });
                    if (!at.isEmpty()) {
                        String[] a = at.split(" ");
                        place(new Vec3(Integer.parseInt(a[0]) + 0.5, Integer.parseInt(a[1]) - 1.0, Integer.parseInt(a[2]) + 8.5),
                                180F, 10F);
                    }
                    out("nearholo " + at);
                }
                case "close" -> mc.setScreen(null); // like Esc: closes the editor without Done
                case "click" -> {
                    // Press a Studio/Creator button by label prefix (Done, Cancel, Grab, ...).
                    boolean hit = false;
                    if (mc.screen instanceof HologramScreen s)
                        hit = s.devClick(arg);
                    else if (mc.screen instanceof HologramCreatorScreen c)
                        hit = c.devClick(arg, 0);
                    out("click " + arg + " -> " + hit);
                }
                case "quit" -> mc.stop();
                default -> out("unknown command " + p[0]);
            }
        } catch (Throwable t) {
            LOG.error("qa command failed", t);
            out("ERROR " + t);
        }
    }

    // ---------------------------------------------------------------- scenery

    private static void buildHouse() {
        Integer n = onServer(player -> {
            var level = player.serverLevel();
            level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, player.server);
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
            level.setDayTime(6000L);
            for (int y = BOX_MIN.getY(); y <= BOX_MAX.getY(); y++)
                for (int z = BOX_MIN.getZ(); z <= BOX_MAX.getZ(); z++)
                    for (int x = BOX_MIN.getX(); x <= BOX_MAX.getX(); x++)
                        level.setBlockAndUpdate(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());

            // House: x 0..4, z 0..4. Floor + walls + glass windows + stair roof + slab cap.
            for (int x = 0; x <= 4; x++)
                for (int z = 0; z <= 4; z++) {
                    boolean edge = x == 0 || x == 4 || z == 0 || z == 4;
                    level.setBlockAndUpdate(new BlockPos(x, -60, z),
                            (edge ? Blocks.COBBLESTONE : Blocks.OAK_PLANKS).defaultBlockState());
                }
            for (int y = -59; y <= -58; y++)
                for (int x = 0; x <= 4; x++)
                    for (int z = 0; z <= 4; z++) {
                        boolean onX = x == 0 || x == 4, onZ = z == 0 || z == 4;
                        if (!onX && !onZ)
                            continue;
                        if (z == 4 && x == 2 && y == -59)
                            continue; // doorway (low)
                        if (z == 4 && x == 2 && y == -58)
                            continue; // doorway (high)
                        BlockState st;
                        if (onX && onZ)
                            st = Blocks.OAK_LOG.defaultBlockState();
                        else if (y == -58 && (x == 2 || z == 2))
                            st = Blocks.GLASS.defaultBlockState();
                        else
                            st = Blocks.OAK_PLANKS.defaultBlockState();
                        level.setBlockAndUpdate(new BlockPos(x, y, z), st);
                    }
            for (int x = 0; x <= 4; x++) {
                level.setBlockAndUpdate(new BlockPos(x, -57, 0),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH));
                level.setBlockAndUpdate(new BlockPos(x, -57, 4),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.NORTH));
            }
            for (int z = 1; z <= 3; z++) {
                level.setBlockAndUpdate(new BlockPos(0, -57, z),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
                level.setBlockAndUpdate(new BlockPos(4, -57, z),
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            }
            for (int x = 1; x <= 3; x++)
                for (int z = 1; z <= 3; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -57, z),
                            Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            level.setBlockAndUpdate(new BlockPos(2, -56, 2), Blocks.GLASS.defaultBlockState());

            // Chest with contents, sign
            BlockPos chestPos = new BlockPos(1, -59, 1);
            level.setBlockAndUpdate(chestPos,
                    Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH));
            if (level.getBlockEntity(chestPos) instanceof ChestBlockEntity chest) {
                chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
                chest.setItem(1, new ItemStack(Items.EMERALD, 16));
                chest.setChanged();
            }
            BlockPos signPos = new BlockPos(3, -59, 1);
            level.setBlockAndUpdate(signPos, Blocks.OAK_SIGN.defaultBlockState().setValue(StandingSignBlock.ROTATION, 8));
            if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
                sign.setText(sign.getFrontText().setMessage(0, Component.literal("Fox House"))
                        .setMessage(1, Component.literal("Holo Test")), true);
                sign.setChanged();
            }

            // Water yard (x 6..8): stone bricks floor, 2-block pillar with a water source on top.
            for (int x = 5; x <= 8; x++)
                for (int z = 0; z <= 6; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -60, z), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(7, -59, 3), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(7, -58, 3), Blocks.WATER.defaultBlockState());

            // Divider wall x = 9
            for (int y = -60; y <= -55; y++)
                for (int z = 0; z <= 6; z++)
                    level.setBlockAndUpdate(new BlockPos(9, y, z), Blocks.STONE_BRICKS.defaultBlockState());

            // Lava yard (x 10..12)
            for (int x = 10; x <= 12; x++)
                for (int z = 0; z <= 6; z++)
                    level.setBlockAndUpdate(new BlockPos(x, -60, z), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(11, -59, 3), Blocks.STONE_BRICKS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(11, -58, 3), Blocks.LAVA.defaultBlockState());

            // Fence line in front of the yards + a slab step
            for (int x = 5; x <= 8; x++)
                level.setBlockAndUpdate(new BlockPos(x, -59, 6), Blocks.OAK_FENCE.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(5, -59, 1), Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.TOP));
            level.setBlockAndUpdate(new BlockPos(5, -59, 2), Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                    .setValue(SlabBlock.TYPE, SlabType.BOTTOM));
            // A stained glass panel against the divider for transparency next to opaque
            level.setBlockAndUpdate(new BlockPos(6, -59, 0), Blocks.BLUE_STAINED_GLASS.defaultBlockState());

            // Box corners must be solid: MIN is cobblestone floor; MAX is a wall pillar.
            for (int y = -60; y <= -55; y++)
                level.setBlockAndUpdate(new BlockPos(12, y, 6), Blocks.COBBLESTONE_WALL.defaultBlockState());

            int nonAir = 0;
            for (int y = BOX_MIN.getY(); y <= BOX_MAX.getY(); y++)
                for (int z = BOX_MIN.getZ(); z <= BOX_MAX.getZ(); z++)
                    for (int x = BOX_MIN.getX(); x <= BOX_MAX.getX(); x++)
                        if (!level.getBlockState(new BlockPos(x, y, z)).isAir())
                            nonAir++;
            player.teleportTo(level, 8.5, -60.0, 14.5, 180.0F, 5.0F);
            return nonAir;
        });
        out("house built, non-air=" + n);
    }

    /** Drive grab mode with the real paths: Grab button, key 4 (structure), two corner clicks, Enter. */
    private static void grabHouse() {
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof HologramScreen screen)) {
            out("grabhouse needs the Studio open (cmd 'edit')");
            return;
        }
        editor = screen;
        out("grab " + screen.devClick("Grab") + " active=" + HologramGrab.isActive());
        HologramGrab.handleKey(GLFW.GLFW_KEY_4);
        later(6, () -> moveAndAim(new Vec3(-3.5, -59.5, -3.5), Vec3.atCenterOf(BOX_MIN)));
        later(8, () -> {
            out("corner1 click " + HologramGrab.triggerGrab(false) + " c1=" + HologramGrab.corner1());
            moveAndAim(new Vec3(15.5, -52.0, 10.5), Vec3.atCenterOf(BOX_MAX));
        });
        later(8, () -> {
            out("corner2 click " + HologramGrab.triggerGrab(false) + " c2=" + HologramGrab.corner2());
            HologramGrab.handleKey(GLFW.GLFW_KEY_ENTER);
        });
        later(20, () -> out("grab done active=" + HologramGrab.isActive() + " screen=" + mc.screen
                + " status=" + HologramGrab.lastStatus()));
    }

    // ---------------------------------------------------------------- helpers

    private static void place(Vec3 pos, float yaw, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        boolean fly = pos.y > -59.5;
        onServer(player -> {
            player.getAbilities().mayfly = true;
            player.getAbilities().flying = fly;
            player.onUpdateAbilities();
            player.teleportTo(player.serverLevel(), pos.x, pos.y, pos.z, yaw, pitch);
            return null;
        });
        if (mc.player != null) {
            mc.player.getAbilities().mayfly = true;
            mc.player.getAbilities().flying = fly;
            mc.player.setDeltaMovement(Vec3.ZERO);
            mc.player.setPos(pos.x, pos.y, pos.z);
            mc.player.setYRot(yaw);
            mc.player.yRotO = yaw;
            mc.player.setXRot(pitch);
            mc.player.xRotO = pitch;
        }
    }

    private static void aimAt(Vec3 target) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        moveAndAim(mc.player.position(), target);
    }

    private static void moveAndAim(Vec3 standPos, Vec3 lookTarget) {
        Vec3 eye = standPos.add(0.0, 1.62, 0.0);
        Vec3 d = lookTarget.subtract(eye);
        double horiz = Math.sqrt(d.x * d.x + d.z * d.z);
        float yaw = Mth.wrapDegrees((float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0));
        float pitch = Mth.clamp((float) (-Math.toDegrees(Math.atan2(d.y, horiz))), -89.0F, 89.0F);
        place(standPos, yaw, pitch);
    }

    private static void command(String cmd) {
        onServer(player -> {
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), cmd);
            return null;
        });
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-qa";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        // Fresh command file each launch.
        try {
            Files.deleteIfExists(mc.gameDirectory.toPath().resolve("qa-cmd.txt"));
            Files.deleteIfExists(mc.gameDirectory.toPath().resolve("qa-out.txt"));
        } catch (IOException ignored) {
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramQaDev() {
    }
}
