package dev.watdafawx.foxstweaks.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.client.HologramEditorPrefs;
import dev.watdafawx.foxstweaks.client.HologramRenderer;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.HologramTimelinePlayer;
import dev.watdafawx.foxstweaks.client.web.HologramWebSession;
import dev.watdafawx.foxstweaks.hologram.HologramImage;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.asset.AssetNetwork;
import dev.watdafawx.foxstweaks.hologram.asset.AssetStore;
import dev.watdafawx.foxstweaks.media.FfmpegLocator;
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
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.metadata.IIOMetadataNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Dev-only check of IMAGE hologram layers, end to end: a PNG with alpha and an animated GIF go up through the
 * real web protocol (chunks, acks, the asset payload, the server's sanitiser), become image layers, are saved and
 * read back from the server and through NBT, and are then screenshot. The clock is moved with the same playhead
 * the web editor's timeline uses, so the GIF must change between two times while the PNG stays put; a billboard
 * must still face the camera after the camera moves; a timeline SCALE track must grow an image layer. Unknown
 * hashes and the per-hologram cap must be refused by the server; deleting a used asset must be refused too.
 * A browser-built FTAS bundle is uploaded the way the WebCodecs fallback sends it, and an mp4 is converted end to
 * end when ffmpeg is on PATH (skipped, and said so, when it is not). Last: 48 image holograms are benchmarked.
 *
 * <p>Run with {@code build.bat runHologramImageCheck} on rig. Report:
 * {@code run-hologram-image/foxstweaks-hologram-image-check.txt}, screenshots in {@code run-hologram-image/screenshots}.
 */
public final class HologramImageCheck {
    private static final Logger LOG = LogUtils.getLogger();

    /** {@code ready} is polled every tick until true (or {@code timeout} ticks pass), then {@code run} runs. */
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
    private static final String UNKNOWN = "ab".repeat(32);
    private static final int BENCH_COUNT = 48;
    private static final int BENCH_TICKS = 100;

    private static String hologramId = "";
    private static HologramScreen editor;
    private static int port;
    private static String token = "";
    private static Client ws;
    private static long seq;

    private static CompletableFuture<String> pending;
    private static String png = "";
    private static String gif = "";
    private static String bundle = "";
    @Nullable
    private static FfmpegLocator.Ffmpeg ffmpeg;

    private static int imageTimeEarly;
    private static int magentaFront;
    private static int gifFront;
    private static final List<Long> PASSES = new ArrayList<>();
    private static boolean recording;

    public static void install() {
        HologramWebSession.devNoBrowser = true;
        NeoForge.EVENT_BUS.addListener(HologramImageCheck::onTick);
        NeoForge.EVENT_BUS.addListener(HologramImageCheck::onFrame);
        scenario();
    }

    // ---------------------------------------------------------------- the scenario

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
            HologramSpec plain = serverSpec();
            check("a save without image layers round-trips unchanged (old saves)",
                    plain != null && plain.equals(HologramSpec.fromTag(plain.toTag())), String.valueOf(plain));
            openEditor();
        });
        add(10, () -> startSession("first"));

        // ---- hello and the upload path ----
        add(10, () -> {
            JsonObject hello = ws.last("hello");
            check("hello received", hello != null, ws.types());
            if (hello == null)
                return;
            check("hello speaks protocol 5", hello.get("protocol").getAsInt() == 5 && HologramWebSession.PROTOCOL == 5,
                    hello.get("protocol").toString());
            JsonObject caps = hello.getAsJsonObject("assetCaps");
            check("hello carries asset capabilities", caps != null && caps.get("available").getAsBoolean()
                    && caps.get("chunkBytes").getAsInt() > 0 && caps.get("maxImageLayers").getAsInt() == 8, String.valueOf(caps));
            pending = CompletableFuture.supplyAsync(() -> upload("dot.png", pngWithAlpha(), "image"));
        });
        waitUpload(() -> {
            png = takeUpload();
            check("PNG with alpha uploaded through the web protocol", AssetFrames.validId(png), png);
            pending = CompletableFuture.supplyAsync(() -> upload("flip.gif", gifTwoFrames(), "image"));
        });
        waitUpload(() -> {
            gif = takeUpload();
            check("animated GIF uploaded through the web protocol", AssetFrames.validId(gif), gif);
            AssetStore.Metadata meta = metadata(gif);
            check("GIF kept both frames", meta != null && meta.frameCount() == 2, String.valueOf(meta));
            check("chunks were acknowledged one by one", ws.count("uploadAck") >= 2, ws.types());
            pending = CompletableFuture.supplyAsync(() -> upload("browser.ftas", ftasBundle(), "bundle"));
        });
        waitUpload(() -> {
            bundle = takeUpload();
            check("browser-built FTAS bundle accepted (WebCodecs fallback path)", AssetFrames.validId(bundle), bundle);
            AssetStore.Metadata meta = metadata(bundle);
            check("bundle frames stay JPEG-sized and keep their count", meta != null && meta.frameCount() == 3, String.valueOf(meta));
            ffmpeg = FfmpegLocator.server().find().orElse(null);
            Boolean serverVideo = AssetNetwork.ffmpegAvailable();
            log("info ffmpeg: " + (ffmpeg == null ? "not found" : ffmpeg) + ", server says video=" + serverVideo);
            if (ffmpeg != null && Boolean.TRUE.equals(serverVideo)) {
                pending = CompletableFuture.supplyAsync(() -> upload("clip.mp4", makeMp4(), "video"));
            } else {
                log("SKIP mp4 converted end to end: ffmpeg is not on PATH here (browsers convert videos themselves)");
                // Without ffmpeg the server must refuse a raw video instead of storing it.
                pending = CompletableFuture.supplyAsync(() -> upload("clip.mp4", new byte[4096], "video"));
            }
        });
        waitUpload(() -> {
            String result = takeUpload();
            if (ffmpeg != null && Boolean.TRUE.equals(AssetNetwork.ffmpegAvailable())) {
                check("mp4 converted by ffmpeg end to end", AssetFrames.validId(result), result);
                AssetStore.Metadata meta = metadata(result);
                check("converted video has frames", meta != null && meta.frameCount() > 1, String.valueOf(meta));
            } else {
                check("raw video refused when the server cannot convert it", !AssetFrames.validId(result), result);
            }
            send("{\"type\":\"assets\",\"offset\":0,\"seq\":" + (++seq) + "}");
            send("{\"type\":\"assetThumb\",\"id\":\"" + png + "\",\"seq\":" + (++seq) + "}");
        });

        // ---- the library listing and thumbnails ----
        add(20, 100, () -> ws.last("assets") != null && ws.last("assetThumb") != null, () -> {
            JsonObject page = ws.last("assets");
            boolean both = false;
            int used = -1;
            String owner = "";
            if (page != null && page.has("entries")) {
                int found = 0;
                for (JsonElement e : page.getAsJsonArray("entries")) {
                    String id = e.getAsJsonObject().get("id").getAsString();
                    if (id.equals(png) || id.equals(gif))
                        found++;
                    if (id.equals(png)) {
                        used = e.getAsJsonObject().get("used").getAsInt();
                        owner = e.getAsJsonObject().get("owner").getAsString();
                    }
                }
                both = found == 2;
            }
            check("library lists both uploads", both, String.valueOf(page));
            check("library entry says who uploaded it and that nothing uses it yet", used == 0 && !owner.isEmpty(),
                    "used " + used + " owner " + owner);
            check("library says this player may upload", page != null && page.get("canUpload").getAsBoolean(), String.valueOf(page));
            JsonObject thumb = ws.last("assetThumb");
            check("thumbnail arrives as a PNG data URL", thumb != null && thumb.get("data").getAsString()
                    .startsWith("data:image/png;base64,"), String.valueOf(thumb));
        });

        // ---- refused input ----
        add(4, () -> {
            long errors = ws.count("error");
            JsonObject spec = imageSpec("not-a-hash", null, false);
            sendEdit("gBadId", spec);
            pendingErrors = errors;
        });
        add(8, () -> {
            check("malformed asset id refused by the page protocol", ws.count("error") > pendingErrors, ws.types());
            boolean none = editor.current().layers().stream().noneMatch(l -> l.kind() == HologramLayer.Kind.IMAGE);
            check("refused edit changes nothing", none, String.valueOf(editor.current().layers()));
            sendEdit("gUnknown", imageSpec(UNKNOWN, null, false));
        });
        add(8, () -> {
            boolean inEditor = editor.current().layers().stream().anyMatch(l -> UNKNOWN.equals(l.id()));
            check("well-formed unknown hash reaches the editor (only the server knows the library)", inEditor,
                    String.valueOf(editor.current().layers()));
            String problem = onServer(p -> HologramNetwork.imageProblem(p.server, editor.current()));
            check("server names the unknown asset", problem != null && problem.contains("library"), String.valueOf(problem));
            send("{\"type\":\"save\",\"seq\":" + (++seq) + "}");
        });
        add(20, () -> {
            HologramSpec stored = serverSpec();
            check("save with an unknown hash refused by the server", stored != null
                    && stored.layers().stream().noneMatch(l -> l.kind() == HologramLayer.Kind.IMAGE), String.valueOf(stored));
            check("save ends the first session", HologramWebSession.current() == null, "still running");
            openEditor();
        });
        add(10, () -> startSession("second"));

        // ---- real image layers, a timeline, the in-game editor ----
        add(10, () -> sendEdit("gImages", imageSpec(png, gif, true)));
        add(8, () -> {
            List<HologramLayer> ls = editor.current().layers();
            check("two image layers reach the editor", ls.size() == 2 && ls.stream().allMatch(l -> l.kind() == HologramLayer.Kind.IMAGE),
                    String.valueOf(ls));
            HologramImage gifImage = ls.size() == 2 ? ls.get(1).image() : null;
            check("image settings arrive (width, billboard, playback)", gifImage != null && Math.abs(gifImage.width() - 1.25F) < 1e-4
                    && gifImage.billboard() == HologramImage.Billboard.FACE && gifImage.playback() == HologramImage.Playback.LOOP,
                    String.valueOf(gifImage));
            JsonObject spec = spec(ws.last("state")).deepCopy();
            JsonArray layers = spec.getAsJsonArray("layers");
            String gifUid = layers.size() == 2 ? layers.get(1).getAsJsonObject().get("uid").getAsString() : "";
            spec.add("timeline", scaleTimeline(gifUid));
            sendEdit("gTimeline", spec);
        });
        add(8, () -> {
            check("timeline on an image layer reaches the editor", editor.current().timeline() != null
                    && editor.current().timeline().tracks().size() == 1, String.valueOf(editor.current().timeline()));
            send("{\"type\":\"playhead\",\"mode\":\"pause\",\"time\":0.1,\"seq\":" + (++seq) + "}");
        });
        add(10, () -> {
            imageTimeEarly = HologramRenderer.lastImageTimeMillis();
            send("{\"type\":\"playhead\",\"mode\":\"pause\",\"time\":0.6,\"seq\":" + (++seq) + "}");
        });
        add(10, () -> {
            int late = HologramRenderer.lastImageTimeMillis();
            check("the web playhead drives the animation (paused at 0.1 s, then 0.6 s)",
                    Math.abs(imageTimeEarly - 100) <= 20 && Math.abs(late - 600) <= 20, imageTimeEarly + " ms, then " + late + " ms");
            check("image texture loaded in the editor view", HologramRenderer.lastImageLoaded(), "placeholder only");
            guiScale(2);
        });
        add(10, () -> shot("image-studio.png"));
        add(2, () -> send("{\"type\":\"save\",\"seq\":" + (++seq) + "}"));
        add(20, () -> {
            HologramSpec stored = serverSpec();
            boolean images = stored != null && stored.layers().size() == 2
                    && stored.layers().get(0).id().equals(png) && stored.layers().get(1).id().equals(gif);
            check("server stored both image layers", images, String.valueOf(stored));
            check("server kept the image settings", stored != null && stored.layers().size() == 2
                    && stored.layers().get(1).image().billboard() == HologramImage.Billboard.FACE
                    && Math.abs(stored.layers().get(0).image().width() - 1.5F) < 1e-4
                    && !stored.layers().get(0).image().doubleSided(), String.valueOf(stored));
            check("image layers survive the NBT round trip", stored != null && stored.equals(HologramSpec.fromTag(stored.toTag())),
                    "differs");
            AssetStore.Metadata pngMeta = metadata(png);
            check("saving retains both assets", pngMeta != null && pngMeta.references() == 1
                    && metadata(gif) != null && metadata(gif).references() == 1, String.valueOf(pngMeta));
            check("session ended with the save", HologramWebSession.current() == null, "still running");
        });

        // ---- server rules ----
        add(4, () -> {
            command("foxstweaks asset delete " + png);
            AssetStore.Metadata meta = metadata(png);
            check("deleting an asset in use is refused", meta != null, "deleted");
            HologramSpec stored = serverSpec();
            List<HologramLayer> many = new ArrayList<>();
            for (int i = 0; i < 9; i++)
                many.add(HologramLayer.image(png, i));
            String problem = stored == null ? null : onServer(p -> HologramNetwork.imageProblem(p.server, stored.withLayers(many)));
            check("nine image layers refused (cap 8)", problem != null && problem.contains("too many"), String.valueOf(problem));
            ServerConfig.HOLOGRAM_IMAGE_MAX_LAYERS.set(12);
            String allowed = stored == null ? "?" : onServer(p -> HologramNetwork.imageProblem(p.server, stored.withLayers(many)));
            check("raising hologramImageMaxLayers lets nine through", allowed == null, String.valueOf(allowed));
            ServerConfig.HOLOGRAM_IMAGE_MAX_LAYERS.set(8);
        });

        // ---- screenshots: the clock, the billboard, the timeline scale ----
        add(4, () -> {
            Minecraft mc = Minecraft.getInstance();
            mc.options.hideGui = true;
            HologramTimelinePlayer.setPlayhead(hologramId, HologramTimelinePlayer.PlayheadMode.PAUSE, 0.1);
        });
        add(20, () -> shot("image-front-t0.1.png"));
        add(6, () -> HologramTimelinePlayer.setPlayhead(hologramId, HologramTimelinePlayer.PlayheadMode.PAUSE, 0.6));
        add(10, () -> shot("image-front-t0.6.png"));
        add(6, () -> {
            int[] a = colours("image-front-t0.1.png");
            int[] b = colours("image-front-t0.6.png");
            log(String.format(Locale.ROOT, "info front t=0.1 magenta %d red %d blue %d; t=0.6 magenta %d red %d blue %d",
                    a[0], a[1], a[2], b[0], b[1], b[2]));
            check("GIF shows its first frame at 0.1 s and its second at 0.6 s",
                    a[1] > 300 && a[2] < a[1] / 10 && b[2] > 300 && b[1] < b[2] / 10, "see info line");
            check("PNG is the same picture at both times", a[0] > 300 && Math.abs(a[0] - b[0]) <= a[0] / 20, a[0] + " vs " + b[0]);
            magentaFront = a[0];
            gifFront = a[1];
            check("PNG alpha is honoured (round dot, transparent corners)", alphaRespected("image-front-t0.1.png"), "square");
            onServer(player -> {
                look(player, 5.5, 0.5, 90.0F);
                return null;
            });
            HologramTimelinePlayer.setPlayhead(hologramId, HologramTimelinePlayer.PlayheadMode.PAUSE, 0.1);
        });
        add(30, () -> shot("image-side-t0.1.png"));
        add(6, () -> {
            int[] side = colours("image-side-t0.1.png");
            log(String.format(Locale.ROOT, "info side magenta %d red %d blue %d", side[0], side[1], side[2]));
            check("fixed PNG is edge-on from the side", side[0] < magentaFront / 5, side[0] + " vs front " + magentaFront);
            check("billboard GIF still faces the camera after it moved", side[1] > gifFront / 2, side[1] + " vs front " + gifFront);
            onServer(player -> {
                look(player, 0.5, 5.5, 180.0F);
                return null;
            });
        });
        add(30, () -> HologramTimelinePlayer.setPlayhead(hologramId, HologramTimelinePlayer.PlayheadMode.PAUSE, 2.0));
        add(10, () -> shot("image-front-t2.png"));
        add(6, () -> {
            int[] grown = colours("image-front-t2.png");
            int gifPixels = grown[1] + grown[2];
            log(String.format(Locale.ROOT, "info t=2 (scale 2) magenta %d red %d blue %d", grown[0], grown[1], grown[2]));
            check("timeline SCALE track grows the image layer (2x at 2 s)", gifPixels > gifFront * 2.5, gifPixels + " vs " + gifFront);
            check("PNG without a track keeps its size", Math.abs(grown[0] - magentaFront) <= magentaFront / 10,
                    grown[0] + " vs " + magentaFront);
            HologramTimelinePlayer.clearPlayhead();
        });

        // ---- benchmark: 48 image holograms ----
        add(4, () -> onServer(player -> {
            look(player, 0.5, 13.5, 180.0F);
            for (int i = 0; i < BENCH_COUNT; i++) {
                BlockPos pos = new BlockPos(-14 + (i % 8) * 4, -59, 6 - (i / 8) * 3);
                HologramSpec spec = Holograms.create(player.serverLevel(), pos, Blocks.STONE.defaultBlockState(), player);
                if (spec != null)
                    Holograms.save(player.serverLevel(), spec.withLayers(List.of(HologramLayer.image(gif, 0.0F)
                            .withImage(HologramImage.DEFAULT.withBillboard(HologramImage.Billboard.FACE)))));
            }
            return null;
        }));
        add(60, () -> {
            shot("image-bench.png");
            PASSES.clear();
            recording = true;
        });
        add(BENCH_TICKS, () -> {
            recording = false;
            List<Long> sorted = new ArrayList<>(PASSES);
            sorted.sort(null);
            double avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
            double median = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2) / 1000.0;
            log(String.format(Locale.ROOT, "perf %d image holograms: pass %.0f us/frame avg, %.0f us median, %d frames (%.1f us each)",
                    BENCH_COUNT, avg, median, sorted.size(), avg / BENCH_COUNT));
            check("48 image holograms draw in under 2 ms per frame", !sorted.isEmpty() && avg < 2000.0,
                    String.format(Locale.ROOT, "%.0f us", avg));
            AssetStore.Metadata meta = metadata(gif);
            check("48 more layers retain the GIF", meta != null && meta.references() == 1 + BENCH_COUNT, String.valueOf(meta));
            Minecraft.getInstance().options.hideGui = false;
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath().resolve("foxstweaks-hologram-image-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    private static long pendingErrors;

    private static void openEditor() {
        HologramEditorPrefs.setStartInCreator(false);
        Minecraft.getInstance().options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
        Minecraft.getInstance().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
        command("foxstweaks hologram edit " + hologramId);
    }

    private static void startSession(String which) {
        editor = Minecraft.getInstance().screen instanceof HologramScreen s ? s : null;
        check(which + ": edit opens the Studio", editor != null, String.valueOf(Minecraft.getInstance().screen));
        boolean clicked = editor != null && editor.devClick("Web");
        HologramWebSession s = HologramWebSession.current();
        check(which + ": Web button starts a session", clicked && s != null && s.running(), String.valueOf(s));
        if (s == null) {
            index = STEPS.size() - 1;
            return;
        }
        port = s.port();
        token = s.token();
        ws = connect("ws://127.0.0.1:" + port + "/" + token + "/ws", "http://127.0.0.1:" + port);
        check(which + ": WebSocket connected", ws != null, "refused");
        if (ws == null)
            index = STEPS.size() - 1;
    }

    /** Waits for the background upload, then runs {@code then}. */
    private static void waitUpload(Runnable then) {
        add(2, 1800, () -> pending != null && pending.isDone(), then);
    }

    private static String takeUpload() {
        try {
            return pending.get();
        } catch (Exception e) {
            return "exception " + e;
        } finally {
            pending = null;
        }
    }

    // ---------------------------------------------------------------- upload over the protocol (background thread)

    /** Sends {@code bytes} the way upload.ts does; returns the hash, or the reason it failed. */
    private static String upload(String name, byte[] bytes, String mode) {
        // The server allows one upload per player per second; a person never notices, a script does.
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            return "interrupted";
        }
        int from = ws.msgs.size();
        long startSeq = ++seq;
        JsonObject begin = new JsonObject();
        begin.addProperty("type", "upload");
        begin.addProperty("seq", startSeq);
        begin.addProperty("name", name);
        begin.addProperty("size", bytes.length);
        begin.addProperty("mode", mode);
        send(begin.toString());
        JsonObject started = await(from, m -> ("uploadStarted".equals(type(m)) || "error".equals(type(m)))
                && m.has("seq") && m.get("seq").getAsLong() == startSeq, 10_000);
        if (started == null || !"uploadStarted".equals(type(started)))
            return "refused: " + started;
        int upload = started.get("upload").getAsInt();
        int chunk = started.get("chunkBytes").getAsInt();

        int at = 0;
        for (int i = 0; at < bytes.length; i++) {
            int end = Math.min(bytes.length, at + chunk);
            byte[] part = java.util.Arrays.copyOfRange(bytes, at, end);
            int index = i;
            int mark = ws.msgs.size();
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "uploadChunk");
            msg.addProperty("seq", ++seq);
            msg.addProperty("upload", upload);
            msg.addProperty("index", i);
            msg.addProperty("data", Base64.getEncoder().encodeToString(part));
            send(msg.toString());
            JsonObject ack = await(mark, m -> ("uploadAck".equals(type(m)) && m.get("index").getAsInt() == index)
                    || "uploadDone".equals(type(m)) || "error".equals(type(m)), 10_000);
            if (ack == null || !"uploadAck".equals(type(ack)))
                return "chunk " + i + " failed: " + ack;
            at = end;
        }
        JsonObject done = await(from, m -> "uploadDone".equals(type(m)) && m.get("upload").getAsInt() == upload, 120_000);
        if (done == null)
            return "no uploadDone";
        return done.get("ok").getAsBoolean() ? done.get("hash").getAsString() : "failed: " + done.get("message").getAsString();
    }

    @Nullable
    private static JsonObject await(int from, Predicate<JsonObject> wanted, long millis) {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            for (int i = from; i < ws.msgs.size(); i++) {
                if (wanted.test(ws.msgs.get(i)))
                    return ws.msgs.get(i);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- fixtures

    /** 64x64, a magenta dot on a transparent background. */
    private static byte[] pngWithAlpha() {
        BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                double dx = x - 31.5, dy = y - 31.5;
                if (dx * dx + dy * dy <= 30 * 30)
                    img.setRGB(x, y, 0xFFFF00FF);
            }
        }
        return encode(img, "PNG");
    }

    /** Two 64x64 frames, red then blue, half a second each. */
    private static byte[] gifTwoFrames() {
        var writer = ImageIO.getImageWritersByFormatName("gif").next();
        var out = new ByteArrayOutputStream();
        try (var stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.prepareWriteSequence(null);
            for (int color : new int[] { 0xFFFF0000, 0xFF0000FF }) {
                BufferedImage pic = solid(64, 64, color);
                var meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(pic), null);
                var root = (IIOMetadataNode) meta.getAsTree("javax_imageio_gif_image_1.0");
                var control = (IIOMetadataNode) root.getElementsByTagName("GraphicControlExtension").item(0);
                control.setAttribute("delayTime", "50");
                meta.setFromTree("javax_imageio_gif_image_1.0", root);
                writer.writeToSequence(new IIOImage(pic, null, meta), null);
            }
            writer.endWriteSequence();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    /** What the browser's WebCodecs fallback builds: FTAS with three JPEG frames. */
    private static byte[] ftasBundle() {
        List<byte[]> frames = new ArrayList<>();
        for (int color : new int[] { 0xFF20C040, 0xFFE0C020, 0xFF2040E0 }) {
            BufferedImage rgb = new BufferedImage(64, 36, BufferedImage.TYPE_INT_RGB);
            var g = rgb.createGraphics();
            g.setColor(new java.awt.Color(color, true));
            g.fillRect(0, 0, 64, 36);
            g.dispose();
            frames.add(encode(rgb, "JPEG"));
        }
        try {
            return new AssetFrames(frames, List.of(100, 100, 100), 64, 36).encode();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static byte[] makeMp4() {
        try {
            Path dir = Files.createTempDirectory("foxstweaks-image-check");
            Path out = dir.resolve("clip.mp4");
            Process p = new ProcessBuilder(ffmpeg.path().toString(), "-hide_banner", "-loglevel", "error", "-f", "lavfi",
                    "-i", "testsrc=size=160x90:rate=10", "-t", "2", "-pix_fmt", "yuv420p", "-y", out.toString())
                    .redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0)
                throw new IOException("ffmpeg could not make the test clip");
            byte[] bytes = Files.readAllBytes(out);
            Files.deleteIfExists(out);
            Files.deleteIfExists(dir);
            return bytes;
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static BufferedImage solid(int w, int h, int color) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        var g = img.createGraphics();
        g.setColor(new java.awt.Color(color, true));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private static byte[] encode(BufferedImage img, String format) {
        var out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, format, out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- specs over the protocol

    /**
     * The current state's spec with its layers replaced by image layers: {@code first} fixed, single-sided, 1.5
     * wide at the left; {@code second} (if any) a 1.25-wide billboard to the right and nearer the south side.
     */
    private static JsonObject imageSpec(String first, @Nullable String second, boolean sized) {
        JsonObject spec = spec(ws.last("state")).deepCopy();
        JsonObject template = spec.getAsJsonArray("layers").get(0).getAsJsonObject();
        JsonArray layers = new JsonArray();
        JsonObject props = new JsonObject();
        if (sized) {
            props.addProperty("w", "1.5");
            props.addProperty("sides", "1");
        }
        layers.add(imageLayer(template, first, props, -1.2, 0.3, 0.0));
        if (second != null) {
            JsonObject p2 = new JsonObject();
            p2.addProperty("w", "1.25");
            p2.addProperty("billboard", "FACE");
            layers.add(imageLayer(template, second, p2, 1.4, 0.3, 2.0));
        }
        spec.add("layers", layers);
        spec.remove("timeline");
        return spec;
    }

    private static JsonObject imageLayer(JsonObject template, String id, JsonObject props, double x, double y, double z) {
        JsonObject l = template.deepCopy();
        l.remove("uid");
        l.remove("data");
        l.addProperty("kind", "IMAGE");
        l.addProperty("id", id);
        l.add("props", props);
        l.addProperty("fullbright", true);
        l.addProperty("scale", 1.0);
        JsonArray offset = new JsonArray();
        offset.add(x);
        offset.add(y);
        offset.add(z);
        l.add("offset", offset);
        JsonArray rotation = new JsonArray();
        rotation.add(0);
        rotation.add(0);
        rotation.add(0);
        l.add("rotation", rotation);
        return l;
    }

    /** One SCALE track on the GIF layer: 1 until 1 s, then up to 2 at 2 s. */
    private static JsonObject scaleTimeline(String uid) {
        JsonArray keys = new JsonArray();
        keys.add(key(0, 1));
        keys.add(key(1, 1));
        keys.add(key(2, 2));
        JsonObject track = new JsonObject();
        track.addProperty("target", "LAYER");
        track.addProperty("id", uid);
        track.addProperty("prop", "SCALE");
        track.add("keys", keys);
        JsonArray tracks = new JsonArray();
        tracks.add(track);
        JsonObject tl = new JsonObject();
        tl.addProperty("length", 2.0);
        tl.addProperty("mode", "ONCE");
        tl.addProperty("start", "ALWAYS");
        tl.add("tracks", tracks);
        return tl;
    }

    private static JsonObject key(double t, double v) {
        JsonObject k = new JsonObject();
        k.addProperty("t", t);
        k.addProperty("v", v);
        k.addProperty("ease", "LINEAR");
        return k;
    }

    private static JsonObject spec(@Nullable JsonObject state) {
        return state == null ? new JsonObject() : state.getAsJsonObject("spec");
    }

    private static void sendEdit(String gesture, JsonObject spec) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "edit");
        msg.addProperty("seq", ++seq);
        msg.addProperty("gesture", gesture);
        msg.add("spec", spec);
        send(msg.toString());
    }

    // ---------------------------------------------------------------- screenshots

    /** Pixel counts of {magenta, red, blue}; emissive quads still get the entity shader's face shading. */
    private static int[] colours(String name) {
        int[] counts = new int[3];
        try (InputStream in = Files.newInputStream(screenshots().resolve(name)); NativeImage img = NativeImage.read(in)) {
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getPixelRGBA(x, y);
                    int r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
                    if (r > 120 && b > 120 && g < 50)
                        counts[0]++;
                    else if (r > 120 && g < 50 && b < 50)
                        counts[1]++;
                    else if (b > 120 && r < 50 && g < 50)
                        counts[2]++;
                }
            }
        } catch (IOException e) {
            log("info screenshot read failed: " + e);
        }
        return counts;
    }

    /** The dot's bounding box must be about pi/4 full: a square would mean alpha was dropped. */
    private static boolean alphaRespected(String name) {
        try (InputStream in = Files.newInputStream(screenshots().resolve(name)); NativeImage img = NativeImage.read(in)) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1, count = 0;
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int p = img.getPixelRGBA(x, y);
                    int r = p & 0xFF, g = (p >> 8) & 0xFF, b = (p >> 16) & 0xFF;
                    if (r > 120 && b > 120 && g < 50) {
                        count++;
                        minX = Math.min(minX, x);
                        maxX = Math.max(maxX, x);
                        minY = Math.min(minY, y);
                        maxY = Math.max(maxY, y);
                    }
                }
            }
            if (count < 100)
                return false;
            double fill = count / (double) ((maxX - minX + 1) * (maxY - minY + 1));
            log(String.format(Locale.ROOT, "info PNG dot fills %.2f of its box (circle 0.79, square 1.00)", fill));
            return fill > 0.6 && fill < 0.9;
        } catch (IOException e) {
            return false;
        }
    }

    private static Path screenshots() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void guiScale(int scale) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    private static void onFrame(RenderLevelStageEvent event) {
        if (recording && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
            PASSES.add(HologramRenderer.lastPassNanos());
    }

    // ---------------------------------------------------------------- server helpers

    /** Stands the player at (x, -60, z) facing {@code yaw}, looking down at the hologram's middle. */
    private static void look(ServerPlayer player, double x, double z, float yaw) {
        double dx = POS.getX() + 0.5 - x, dz = POS.getZ() + 0.5 - z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) Math.toDegrees(Math.atan2(-60.0 + 1.62 - (POS.getY() + 0.8), dist));
        player.teleportTo(player.serverLevel(), x, -60.0, z, yaw, pitch);
    }

    @Nullable
    private static HologramSpec serverSpec() {
        return onServer(player -> Holograms.get(player.serverLevel(), hologramId));
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

    // ---------------------------------------------------------------- WebSocket client

    private static void send(String text) {
        if (ws == null) {
            check("client connected", false, "no client");
            return;
        }
        try {
            ws.ws.sendText(text, true).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            check("send " + text.substring(0, Math.min(40, text.length())), false, e.toString());
        }
    }

    @Nullable
    private static String type(JsonObject m) {
        return m.has("type") ? m.get("type").getAsString() : null;
    }

    /** A java.net.http WebSocket that keeps every message it receives. */
    private static final class Client implements WebSocket.Listener {
        final List<JsonObject> msgs = new CopyOnWriteArrayList<>();
        private final StringBuilder buf = new StringBuilder();
        WebSocket ws;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            this.buf.append(data);
            if (last) {
                try {
                    this.msgs.add(JsonParser.parseString(this.buf.toString()).getAsJsonObject());
                } catch (RuntimeException e) {
                    LOG.warn("HologramImageCheck: unparsable message");
                }
                this.buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Nullable
        JsonObject last(String type) {
            for (int i = this.msgs.size() - 1; i >= 0; i--) {
                if (type.equals(type(this.msgs.get(i))))
                    return this.msgs.get(i);
            }
            return null;
        }

        long count(String type) {
            return this.msgs.stream().filter(m -> type.equals(type(m))).count();
        }

        String types() {
            var counts = new java.util.LinkedHashMap<String, Integer>();
            for (JsonObject m : this.msgs)
                counts.merge(String.valueOf(type(m)), 1, Integer::sum);
            return counts.toString();
        }
    }

    @Nullable
    private static Client connect(String url, String originHeader) {
        Client c = new Client();
        try {
            c.ws = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().newWebSocketBuilder()
                    .header("Origin", originHeader).buildAsync(URI.create(url), c).get(5, TimeUnit.SECONDS);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- harness

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, 0, null, run));
    }

    private static void add(int waitTicks, int timeoutTicks, BooleanSupplier ready, Runnable run) {
        STEPS.add(new Step(waitTicks, timeoutTicks, ready, run));
    }

    private static void log(String line) {
        LOG.info("HologramImageCheck {}", line);
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
            LOG.error("HologramImageCheck crashed", t);
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
        var world = "foxstweaks-imagecheck";
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

    private HologramImageCheck() {
    }
}
