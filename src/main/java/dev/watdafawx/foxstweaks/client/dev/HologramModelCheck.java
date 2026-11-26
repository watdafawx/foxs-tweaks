package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramModels;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.hologram.HologramImage;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramModelSettings;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork;
import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;
import dev.watdafawx.foxstweaks.hologram.asset.AssetStore;
import dev.watdafawx.foxstweaks.hologram.model.ModelData;
import dev.watdafawx.foxstweaks.hologram.model.dev.ModelSelfTest;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Dev-only check of MODEL hologram layers, end to end through the real asset channel: a Blockbench cube and a
 * skinned glb go up as {@link AssetPayload#MODE_MODEL}, garbage is refused, the server stores them as MODEL assets,
 * a layer drawn from the cube is decoded on the client, emits its 12 triangles, and its pixels move between two
 * animation times. Run with {@code build.bat runHologramModelCheck} on rig. Report:
 * {@code run-hologram-model/foxstweaks-hologram-model-check.txt}, screenshots in {@code run-hologram-model/screenshots}.
 */
public final class HologramModelCheck {
    private static final Logger LOG = LogUtils.getLogger();

    private record Step(int waitTicks, int timeoutTicks, @Nullable BooleanSupplier ready, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int deadline;
    private static int index;

    private static final BlockPos POS = new BlockPos(0, -60, 0);
    private static String hologramId = "";
    private static String cube = "";
    private static String glb = "";

    private static volatile boolean uploadDone;
    private static volatile boolean uploadOk;
    private static volatile String uploadMessage = "";

    private static int orangeEarly;
    private static int orangeLater;
    private static int widthEarly;
    private static int widthLater;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramModelCheck::onTick);
        scenario();
    }

    private static void scenario() {
        add(40, () -> onServer(player -> {
            player.serverLevel().setDayTime(6000);
            player.serverLevel().getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
            look(player, 0.5, 5.5, 180.0F);
            HologramSpec spec = Holograms.create(player.serverLevel(), POS, Blocks.AMETHYST_BLOCK.defaultBlockState(), player);
            hologramId = spec == null ? "" : spec.id();
            return null;
        }));
        add(4, () -> {
            check("hologram created", !hologramId.isEmpty(), "none");
            check("asset channel available", HologramAssets.available(), "no channel");
            upload("cube.bbmodel", ModelSelfTest.bbmodelBytes());
        });
        waitUpload(() -> {
            check("Blockbench cube accepted", uploadOk && AssetFrames.validId(uploadMessage), uploadMessage);
            cube = uploadMessage;
            upload("tri.glb", ModelSelfTest.glbBytes());
        });
        waitUpload(() -> {
            check("skinned glb accepted", uploadOk && AssetFrames.validId(uploadMessage), uploadMessage);
            glb = uploadMessage;
            upload("junk.glb", "definitely not a model".getBytes(StandardCharsets.UTF_8));
        });
        waitUpload(() -> {
            check("garbage refused as a model", !uploadOk, uploadMessage);
            AssetStore.Metadata c = metadata(cube);
            AssetStore.Metadata g = metadata(glb);
            check("cube stored as MODEL with 1 animation", c != null && "MODEL".equals(c.type()) && c.frameCount() == 1, String.valueOf(c));
            check("glb stored as MODEL with 1 animation", g != null && "MODEL".equals(g.type()) && g.frameCount() == 1, String.valueOf(g));
            // A cube layer two blocks tall, frozen at t = 0.
            addLayer(cube, 0.0F);
        });
        add(40, 200, () -> HologramAssets.model(cube) != null, () -> {
            ModelData m = HologramAssets.model(cube);
            check("client downloaded and decoded the cube", m != null && m.triangleCount() == 12 && m.animations.size() == 1,
                    String.valueOf(m));
            check("client lists the animation by name", HologramModels.animations(cube).contains("turn"), String.valueOf(HologramModels.animations(cube)));
            shot("model-t0.png");
        });
        add(12, () -> {
            check("cube drew 12 triangles", HologramModels.lastTriangles == 12, String.valueOf(HologramModels.lastTriangles));
            int[] c = orange("model-t0.png");
            orangeEarly = c[0];
            widthEarly = c[1];
            check("cube is visible (orange pixels)", orangeEarly > 400, String.valueOf(orangeEarly));
            addLayer(cube, 0.5F);
        });
        add(20, () -> shot("model-t05.png"));
        add(12, () -> {
            int[] c = orange("model-t05.png");
            orangeLater = c[0];
            widthLater = c[1];
            // Turned 45 degrees about Y the cube's silhouette is wider (diagonal) than face-on.
            check("animation changed the picture between t=0 and t=0.5", widthLater > widthEarly + 5,
                    "width " + widthEarly + " -> " + widthLater + ", pixels " + orangeEarly + " -> " + orangeLater);
            // The glb layer: skinned triangle, must at least draw.
            addLayer(glb, 1.0F);
        });
        add(60, 200, () -> HologramAssets.model(glb) != null, () -> {
            ModelData m = HologramAssets.model(glb);
            check("client decoded the skinned glb", m != null && m.triangleCount() == 1, String.valueOf(m));
        });
        add(20, () -> {
            check("glb layer drew its triangle", HologramModels.lastTriangles >= 1, String.valueOf(HologramModels.lastTriangles));
            check("unknown hash is not a model", HologramAssets.model("ab".repeat(32)) == null, "model returned");
            onServer(player -> {
                Holograms.remove(player.serverLevel(), hologramId);
                return null;
            });
        });
        add(10, HologramModelCheck::finish);
    }

    private static void addLayer(String hash, float start) {
        onServer(player -> {
            HologramSpec spec = Holograms.get(player.serverLevel(), hologramId);
            List<HologramLayer> layers = new ArrayList<>();
            HologramLayer layer = HologramLayer.model(hash, 1.0F)
                    .withModel(new HologramModelSettings(1.5F, "turn", HologramImage.Playback.LOOP, 0.0F, start));
            layers.add(layer);
            Holograms.save(player.serverLevel(), spec.withLayers(layers));
            Holograms.sync(player.serverLevel());
            return null;
        });
    }

    private static void finish() {
        check("no step crashed", true, "");
        log(failures == 0 ? "ALL PASS" : failures + " FAILED");
        try {
            Path out = Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-hologram-model-check.txt");
            Files.write(out, REPORT, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.error("report write failed", e);
        }
        Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
    }

    // ---------------------------------------------------------------- helpers

    private static void upload(String name, byte[] bytes) {
        uploadDone = false;
        uploadOk = false;
        uploadMessage = "";
        try {
            HologramAssets.uploadBytes(name, bytes, AssetPayload.MODE_MODEL, new HologramAssets.UploadListener() {
                @Override
                public void progress(int percent, String stage) {
                }

                @Override
                public void done(boolean ok, String message) {
                    uploadOk = ok;
                    uploadMessage = message == null ? "" : message;
                    uploadDone = true;
                }
            });
        } catch (IOException e) {
            uploadMessage = e.toString();
            uploadDone = true;
        }
    }

    private static void waitUpload(Runnable then) {
        add(2, 400, () -> uploadDone, then);
    }

    @Nullable
    private static AssetStore.Metadata metadata(String id) {
        return onServer(player -> {
            try {
                return AssetNetwork.store(player.server).info(id);
            } catch (IOException e) {
                return null;
            }
        });
    }

    /** {orange pixels, width of their bounding box} in a saved screenshot. */
    private static int[] orange(String name) {
        int count = 0, minX = Integer.MAX_VALUE, maxX = -1;
        try (InputStream in = Files.newInputStream(Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots").resolve(name));
             NativeImage img = NativeImage.read(in)) {
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getPixelRGBA(x, y);
                    int r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
                    if (r > 110 && g > r / 4 && g < r * 4 / 5 && b < r / 3) {
                        count++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                    }
                }
            }
        } catch (IOException e) {
            log("info screenshot read failed: " + e);
        }
        return new int[] { count, maxX < 0 ? 0 : maxX - minX + 1 };
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void look(ServerPlayer player, double x, double z, float yaw) {
        double dx = POS.getX() + 0.5 - x, dz = POS.getZ() + 0.5 - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) Math.toDegrees(Math.atan2(-60.0 + 1.62 - (POS.getY() + 1.2), dist));
        player.teleportTo(player.serverLevel(), x, -60.0, z, yaw, pitch);
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, 0, null, run));
    }

    private static void add(int waitTicks, int timeoutTicks, BooleanSupplier ready, Runnable run) {
        STEPS.add(new Step(waitTicks, timeoutTicks, ready, run));
    }

    private static void log(String line) {
        LOG.info("HologramModelCheck {}", line);
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
            arm();
        if (tick < nextAt)
            return;

        Step step = STEPS.get(index);
        if (step.ready != null && !step.ready.getAsBoolean()) {
            if (tick < deadline)
                return;
            check("step " + index + " became ready in time", false, "timed out");
        }
        index++;
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("HologramModelCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            arm();
    }

    private static void arm() {
        Step next = STEPS.get(index);
        nextAt = tick + next.waitTicks;
        deadline = nextAt + next.timeoutTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-modelcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramModelCheck() {
    }
}
