package dev.watdafawx.foxstweaks.client.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Client-side FPS hotspot profiler (Observable-style for render time).
 *
 * <p>When active, times every {@code BlockEntityRenderDispatcher#render} and
 * {@code EntityRenderDispatcher#render} call with {@code System.nanoTime()} (zero overhead when
 * inactive), keeps a live ranked list of the most expensive block entities and entities <em>and</em> the same
 * data grouped by type (two hundred cheap drawers are a bigger problem than one pricey chest), and feeds
 * {@link FpsOverlayRenderer} (in-world boxes + HUD list) and {@link FpsReportScreen} (details, teleport).
 *
 * <p>Triggered either via {@code /foxstweaks fps [seconds|stop|clear|report]} or automatically when the
 * player runs {@code /sparkc profiler}.
 *
 * <p>What is deliberately <b>not</b> counted as a hotspot:
 * <ul>
 *   <li>the local player (and the camera entity): in first person it is only drawn by shader packs' shadow
 *       pass, and a box around yourself is noise. Its cost is reported separately as {@link #playerMicros()};</li>
 *   <li>entities that are not in the client level (GUI paper-dolls, mod preview entities, spawn-egg icons): they
 *       share ids with real entities and used to show up at {@code (0, 1, 0)};</li>
 *   <li>time spent in nested dispatcher calls: each entry is its <em>self</em> time.</li>
 * </ul>
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class FpsProfiler {
    private static final int MAX_HIGHLIGHTS = 35;
    private static final int MAX_INSTANCES = 100;
    private static final int MAX_GROUPS = 60;
    private static final int MAX_MEMBERS = 200;
    private static final double MIN_MICROS_TO_SHOW = 15.0;
    private static final double MIN_GROUP_MICROS = 20.0;
    private static final Pattern SPARK_URL = Pattern.compile("https://spark\\.lucko\\.me/\\S+");

    /** Fast-path flag checked by render mixins — single volatile read when off. */
    public static volatile boolean sampling;

    private static volatile boolean showingOverlay;
    private static boolean linkedToSpark;
    private static long startTimeMs;
    private static long endTimeMs;
    private static long scanMillis;
    private static int frameCount;
    private static int tickCounter;
    private static boolean pendingReport;

    // Whole-frame timing, so a hotspot can be shown as a share of the frame and as "+FPS if removed".
    private static long lastFrameNanos;
    private static long totalFrameNanos;
    private static int timedFrames;

    // Local player cost, kept apart from the hotspot lists.
    private static long playerNanos;

    private static volatile String sparkUrl;

    // Primitive timing stacks (handle nested BE/Entity renders without allocations). *_CHILD holds the time
    // already spent in nested calls at each depth, so a parent can report self time.
    private static final long[] BE_START_STACK = new long[32];
    private static final long[] BE_CHILD_STACK = new long[32];
    private static int beDepth;
    private static final long[] ENT_START_STACK = new long[32];
    private static final long[] ENT_CHILD_STACK = new long[32];
    private static int entDepth;

    private static final Map<BlockPos, Accum> BLOCK_ENTITIES = new HashMap<>();
    private static final Map<Integer, Accum> ENTITIES = new HashMap<>();
    private static final Set<String> IGNORED_TYPES = new HashSet<>();

    private static volatile List<Hotspot> hotspots = List.of();
    private static volatile List<Hotspot> instances = List.of();
    private static volatile List<Group> groups = List.of();

    // What the in-world overlay shows: nothing set = the top hotspots; a type = every instance of it; an item = just it.
    private static volatile Hotspot focusItem;
    private static volatile String focusType;

    private FpsProfiler() {
    }

    public static final class Accum {
        long totalNanos;
        int calls;
        String label;
        String typeId;
        BlockPos blockPos;
        int entityId = -1;
        AABB lastBox;
    }

    /** One block entity or entity: self render time per frame (µs). */
    public record Hotspot(
            boolean isEntity,
            int entityId,
            BlockPos blockPos,
            AABB fallbackBox,
            String label,
            String typeId,
            double microsPerFrame,
            int callsPerFrame
    ) {
    }

    /** Every instance of one block entity / entity type added together. {@code members} is hottest first, capped. */
    public record Group(
            boolean isEntity,
            String typeId,
            String label,
            int count,
            double microsPerFrame,
            double callsPerFrame,
            List<Hotspot> members
    ) {
    }

    // ---- Commands ----

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(
                Commands.literal(FoxsTweaks.MODID)
                        .then(Commands.literal("fps")
                                .executes(ctx -> {
                                    start(5, false);
                                    return 1;
                                })
                                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 60))
                                        .executes(ctx -> {
                                            start(IntegerArgumentType.getInteger(ctx, "seconds"), false);
                                            return 1;
                                        }))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> {
                                            stop(true);
                                            return 1;
                                        }))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> {
                                            clear();
                                            return 1;
                                        }))
                                .then(Commands.literal("report")
                                        .executes(ctx -> {
                                            openReport();
                                            return 1;
                                        }))
                                .then(Commands.literal("show")
                                        .then(Commands.literal("off")
                                                .executes(ctx -> {
                                                    clearFocus();
                                                    return 1;
                                                }))
                                        .then(Commands.literal("item")
                                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            Hotspot h = instance(IntegerArgumentType.getInteger(ctx, "n"));
                                                            if (h != null)
                                                                focusItem(h);
                                                            return 1;
                                                        })))
                                        .then(Commands.literal("group")
                                                .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                                        .executes(ctx -> {
                                                            Group g = group(IntegerArgumentType.getInteger(ctx, "n"));
                                                            if (g != null)
                                                                focusType(g.typeId());
                                                            return 1;
                                                        }))))
                                .then(Commands.literal("look")
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    Hotspot h = instance(IntegerArgumentType.getInteger(ctx, "n"));
                                                    if (h != null)
                                                        FpsActions.look(h);
                                                    return 1;
                                                })))
                                .then(Commands.literal("tp")
                                        .then(Commands.argument("n", IntegerArgumentType.integer(1))
                                                .executes(ctx -> {
                                                    Hotspot h = instance(IntegerArgumentType.getInteger(ctx, "n"));
                                                    if (h != null)
                                                        FpsActions.teleport(h);
                                                    return 1;
                                                })))));
    }

    /** Called from {@code SparkCommandHookMixin} when the player submits a chat/command line. */
    public static void onChatInput(String rawInput) {
        if (rawInput == null || !Config.enabled(Config.FPS_HIGHLIGHTER_SPARK_HOOK))
            return;

        String trimmed = rawInput.trim().toLowerCase(Locale.ROOT);
        if (!trimmed.startsWith("/sparkc "))
            return;

        String[] parts = trimmed.split("\\s+");
        if (parts.length < 2 || !parts[1].equals("profiler"))
            return;

        boolean hasStop = false;
        boolean hasCancel = false;
        boolean hasInfo = false;
        int timeoutSec = 0;

        for (int i = 2; i < parts.length; i++) {
            switch (parts[i]) {
                case "stop" -> hasStop = true;
                case "cancel" -> hasCancel = true;
                case "info", "open" -> hasInfo = true;
                case "--timeout" -> {
                    if (i + 1 < parts.length) {
                        try {
                            timeoutSec = Math.max(1, Math.min(300, Integer.parseInt(parts[i + 1])));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
        }

        if (hasCancel) {
            if (sampling && linkedToSpark)
                clear();
        } else if (hasStop) {
            if (sampling && linkedToSpark)
                stop(true);
        } else if (!hasInfo) {
            // /sparkc profiler [start] [--timeout N] ...
            start(timeoutSec, true);
        }
    }

    public static void start(int seconds, boolean fromSpark) {
        resetData();
        linkedToSpark = fromSpark;
        startTimeMs = System.currentTimeMillis();
        // Safety cap of 120s when linked to sparkc without --timeout so it never runs forever.
        int effectiveSec = seconds > 0 ? seconds : 120;
        endTimeMs = startTimeMs + effectiveSec * 1000L;
        sampling = true;
        showingOverlay = true;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            String msg = fromSpark
                    ? "[Fox's Tweaks] Highlighting FPS hotspots alongside sparkc profiler..."
                    : "[Fox's Tweaks] Scanning FPS hotspots for " + seconds + "s...";
            mc.player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), false);
        }
    }

    public static void stop(boolean printSummary) {
        if (!sampling) {
            if (printSummary) {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(
                            Component.literal("[Fox's Tweaks] No FPS scan running.").withStyle(ChatFormatting.GRAY), false);
                }
            }
            return;
        }
        sampling = false;
        linkedToSpark = false;
        scanMillis = System.currentTimeMillis() - startTimeMs;
        lastFrameNanos = 0L;
        rebuildHotspots();

        if (printSummary) {
            printChatSummary();
        }
    }

    public static void clear() {
        sampling = false;
        showingOverlay = false;
        linkedToSpark = false;
        resetData();
        IGNORED_TYPES.clear();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Fox's Tweaks] Cleared FPS highlights.").withStyle(ChatFormatting.GRAY), false);
        }
    }

    private static void resetData() {
        BLOCK_ENTITIES.clear();
        ENTITIES.clear();
        hotspots = List.of();
        instances = List.of();
        groups = List.of();
        focusItem = null;
        focusType = null;
        beDepth = 0;
        entDepth = 0;
        frameCount = 0;
        tickCounter = 0;
        lastFrameNanos = 0L;
        totalFrameNanos = 0L;
        timedFrames = 0;
        playerNanos = 0L;
        scanMillis = 0L;
        sparkUrl = null;
    }

    // ---- Read API (overlay, report screen) ----

    public static boolean isShowingOverlay() {
        return showingOverlay;
    }

    /** Top individual hotspots (at most {@value #MAX_HIGHLIGHTS}), hottest first. */
    public static List<Hotspot> getHotspots() {
        return hotspots;
    }

    /** More individual entries for the report (at most {@value #MAX_INSTANCES}), hottest first. */
    public static List<Hotspot> getInstances() {
        return instances;
    }

    /** Per-type totals, most expensive first. */
    public static List<Group> getGroups() {
        return groups;
    }

    public static Hotspot instance(int oneBased) {
        List<Hotspot> l = instances;
        if (oneBased < 1 || oneBased > l.size()) {
            say("[Fox's Tweaks] No hotspot #" + oneBased + " (run /foxstweaks fps first).", ChatFormatting.GRAY);
            return null;
        }
        return l.get(oneBased - 1);
    }

    public static Group group(int oneBased) {
        List<Group> l = groups;
        if (oneBased < 1 || oneBased > l.size()) {
            say("[Fox's Tweaks] No group #" + oneBased + " (run /foxstweaks fps first).", ChatFormatting.GRAY);
            return null;
        }
        return l.get(oneBased - 1);
    }

    public static int getFrameCount() {
        return frameCount;
    }

    public static double getRemainingSeconds() {
        if (!sampling)
            return 0.0;
        return Math.max(0.0, (endTimeMs - System.currentTimeMillis()) / 1000.0);
    }

    public static double getScanSeconds() {
        return (sampling ? System.currentTimeMillis() - startTimeMs : scanMillis) / 1000.0;
    }

    public static boolean isLinkedToSpark() {
        return linkedToSpark;
    }

    public static String getSparkUrl() {
        return sparkUrl;
    }

    /** Average whole-frame time during the scan, µs (0 until a few frames were seen). */
    public static double frameMicros() {
        return timedFrames > 0 ? totalFrameNanos / 1000.0 / timedFrames : 0.0;
    }

    /** What the local player costs per frame (µs), kept out of the hotspot lists. */
    public static double playerMicros() {
        return playerNanos / 1000.0 / Math.max(1, frameCount);
    }

    /** Fraction of the average frame (0..1) a cost of {@code micros} takes. */
    public static double share(double micros) {
        double f = frameMicros();
        return f > 0.0 ? micros / f : 0.0;
    }

    /** FPS gained if {@code micros} per frame were free (0 when unknown). */
    public static double fpsGain(double micros) {
        double f = frameMicros();
        if (f <= 0.0 || micros <= 0.0 || micros >= f)
            return 0.0;
        return 1_000_000.0 / (f - micros) - 1_000_000.0 / f;
    }

    // ---- Heat colours: by share of the frame (so a 30 FPS and a 300 FPS pack read the same) ----

    private static int heatLevel(double micros) {
        double s = frameMicros() > 0.0 ? share(micros) : micros / 4000.0; // no frame data: 4 ms stand-in
        if (s >= 0.15)
            return 3;
        if (s >= 0.07)
            return 2;
        if (s >= 0.025)
            return 1;
        return 0;
    }

    public static float[] heatRgb(double micros) {
        return switch (heatLevel(micros)) {
            case 3 -> new float[] { 1.0f, 0.18f, 0.18f };
            case 2 -> new float[] { 1.0f, 0.50f, 0.12f };
            case 1 -> new float[] { 1.0f, 0.85f, 0.15f };
            default -> new float[] { 0.35f, 0.95f, 0.35f };
        };
    }

    public static int heatArgb(double micros) {
        float[] c = heatRgb(micros);
        return 0xFF000000 | ((int) (c[0] * 255) << 16) | ((int) (c[1] * 255) << 8) | (int) (c[2] * 255);
    }

    public static ChatFormatting heatChat(double micros) {
        return switch (heatLevel(micros)) {
            case 3 -> ChatFormatting.RED;
            case 2 -> ChatFormatting.GOLD;
            case 1 -> ChatFormatting.YELLOW;
            default -> ChatFormatting.GREEN;
        };
    }

    // ---- Focus (what the in-world overlay draws) ----

    public static void focusItem(Hotspot h) {
        focusType = null;
        focusItem = h;
        showingOverlay = true;
    }

    public static void focusType(String typeId) {
        focusItem = null;
        focusType = typeId;
        showingOverlay = true;
    }

    public static void clearFocus() {
        focusItem = null;
        focusType = null;
    }

    public static Hotspot getFocusItem() {
        return focusItem;
    }

    public static String getFocusType() {
        return focusType;
    }

    /** The entries the in-world overlay should draw right now, hottest first. */
    public static List<Hotspot> worldList() {
        Hotspot item = focusItem;
        if (item != null)
            return List.of(item);
        String type = focusType;
        if (type != null) {
            for (Group g : groups) {
                if (g.typeId().equals(type))
                    return g.members();
            }
            return List.of();
        }
        return hotspots;
    }

    public static boolean isIgnored(String typeId) {
        return IGNORED_TYPES.contains(typeId);
    }

    /** Hide a type from every list and from the world (cleared by {@code /foxstweaks fps clear}). */
    public static void ignoreType(String typeId) {
        IGNORED_TYPES.add(typeId);
        if (typeId.equals(focusType))
            focusType = null;
        Hotspot f = focusItem;
        if (f != null && f.typeId().equals(typeId))
            focusItem = null;
        rebuildHotspots();
    }

    public static void openReport() {
        if (instances.isEmpty() && groups.isEmpty() && !sampling) {
            say("[Fox's Tweaks] Nothing to report yet - run /foxstweaks fps first.", ChatFormatting.GRAY);
            return;
        }
        // Commands run inside ChatScreen, which closes itself right after: open the screen on the next tick.
        pendingReport = true;
    }

    /** The live box to draw for a hotspot: the entity's current box, else the last one seen, else the block. */
    public static AABB resolveBox(Minecraft mc, Hotspot h) {
        if (h.isEntity()) {
            Entity e = mc.level != null ? mc.level.getEntity(h.entityId()) : null;
            if (e != null)
                return e.getBoundingBox().inflate(0.03);
            return h.fallbackBox() != null ? h.fallbackBox().inflate(0.03) : null;
        }
        return h.blockPos() != null ? new AABB(h.blockPos()).inflate(0.01) : h.fallbackBox();
    }

    /** Mod display name for a registry id ("create:chest" -> "Create"). */
    public static String modName(String typeId) {
        int colon = typeId.indexOf(':');
        String ns = colon >= 0 ? typeId.substring(0, colon) : "minecraft";
        if (ns.equals("minecraft"))
            return "Minecraft";
        return ModList.get().getModContainerById(ns)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(ns);
    }

    // ---- Mixin hooks (render thread only) ----

    public static void beginBlockEntity() {
        if (beDepth < BE_START_STACK.length) {
            BE_START_STACK[beDepth] = System.nanoTime();
            BE_CHILD_STACK[beDepth] = 0L;
        }
        beDepth++;
    }

    public static void endBlockEntity(BlockEntity be) {
        if (beDepth <= 0)
            return;
        beDepth--;
        if (!sampling || be == null || beDepth >= BE_START_STACK.length)
            return;

        long total = System.nanoTime() - BE_START_STACK[beDepth];
        if (beDepth > 0 && beDepth - 1 < BE_CHILD_STACK.length)
            BE_CHILD_STACK[beDepth - 1] += Math.max(0L, total);
        long elapsed = total - BE_CHILD_STACK[beDepth];
        if (elapsed <= 0L)
            return;

        BlockPos pos = be.getBlockPos();
        Accum a = BLOCK_ENTITIES.get(pos);
        if (a == null) {
            a = new Accum();
            a.blockPos = pos.immutable();
            ResourceLocation id = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
            a.typeId = id != null ? id.toString() : be.getClass().getSimpleName();
            a.label = shortName(a.typeId);
            a.lastBox = new AABB(a.blockPos);
            BLOCK_ENTITIES.put(a.blockPos, a);
        }
        a.totalNanos += elapsed;
        a.calls++;
    }

    public static void beginEntity() {
        if (entDepth < ENT_START_STACK.length) {
            ENT_START_STACK[entDepth] = System.nanoTime();
            ENT_CHILD_STACK[entDepth] = 0L;
        }
        entDepth++;
    }

    public static void endEntity(Entity entity) {
        if (entDepth <= 0)
            return;
        entDepth--;
        if (!sampling || entity == null || entDepth >= ENT_START_STACK.length)
            return;

        long total = System.nanoTime() - ENT_START_STACK[entDepth];
        if (entDepth > 0 && entDepth - 1 < ENT_CHILD_STACK.length)
            ENT_CHILD_STACK[entDepth - 1] += Math.max(0L, total);
        long elapsed = total - ENT_CHILD_STACK[entDepth];
        if (elapsed <= 0L)
            return;

        Minecraft mc = Minecraft.getInstance();
        if (entity == mc.player || entity == mc.getCameraEntity()) {
            playerNanos += elapsed;
            return;
        }
        // Not in the world: a GUI paper-doll, a spawn-egg preview, a mod's fake entity. Its id may even
        // belong to a real entity.
        if (mc.level == null || mc.level.getEntity(entity.getId()) != entity)
            return;

        int id = entity.getId();
        Accum a = ENTITIES.get(id);
        if (a == null) {
            a = new Accum();
            a.entityId = id;
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            a.typeId = key != null ? key.toString() : entity.getClass().getSimpleName();
            a.label = shortName(a.typeId);
            ENTITIES.put(id, a);
        }
        a.totalNanos += elapsed;
        a.calls++;
        a.lastBox = entity.getBoundingBox();
    }

    // ---- Frame & tick lifecycle ----

    @SubscribeEvent
    public static void onRenderFramePre(RenderFrameEvent.Pre event) {
        if (sampling) {
            frameCount++;
            beDepth = 0;
            entDepth = 0;

            long now = System.nanoTime();
            if (lastFrameNanos != 0L) {
                long delta = now - lastFrameNanos;
                // A pause, an alt-tab or a world load is not a frame: ignore anything over a second.
                if (delta > 0L && delta < 1_000_000_000L) {
                    totalFrameNanos += delta;
                    timedFrames++;
                }
            }
            lastFrameNanos = now;
        }
    }

    @SubscribeEvent
    public static void onChatReceived(ClientChatReceivedEvent event) {
        if (!sampling && instances.isEmpty())
            return;
        Matcher m = SPARK_URL.matcher(event.getMessage().getString());
        if (m.find())
            sparkUrl = m.group();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        // Still the chat screen that ran the command: wait for it to close, or it would close ours too.
        if (pendingReport && !(mc.screen instanceof net.minecraft.client.gui.screens.ChatScreen)) {
            pendingReport = false;
            mc.setScreen(new FpsReportScreen());
        }

        while (FoxsTweaksKeys.OPEN_FPS_REPORT.consumeClick()) {
            if (mc.screen == null && mc.player != null)
                openReport();
        }

        if (mc.level == null) {
            if (sampling || showingOverlay) {
                sampling = false;
                showingOverlay = false;
                resetData();
            }
            return;
        }

        if (!sampling)
            return;

        if (System.currentTimeMillis() >= endTimeMs) {
            stop(true);
            return;
        }

        tickCounter++;
        if (tickCounter % 5 == 0) {
            rebuildHotspots();
        }
    }

    private static Hotspot toHotspot(Accum a, boolean isEntity, int frames) {
        double us = (a.totalNanos / 1000.0) / frames;
        return new Hotspot(isEntity, isEntity ? a.entityId : -1, isEntity ? null : a.blockPos, a.lastBox, a.label,
                a.typeId, us, Math.max(1, Math.round((float) a.calls / frames)));
    }

    private static void rebuildHotspots() {
        int frames = Math.max(1, frameCount);
        List<Hotspot> all = new ArrayList<>(BLOCK_ENTITIES.size() + ENTITIES.size());

        for (Accum a : BLOCK_ENTITIES.values()) {
            if (!IGNORED_TYPES.contains(a.typeId))
                all.add(toHotspot(a, false, frames));
        }
        for (Accum a : ENTITIES.values()) {
            if (!IGNORED_TYPES.contains(a.typeId))
                all.add(toHotspot(a, true, frames));
        }

        // Per type, every instance counts: this is what finds "200 cheap drawers".
        Map<String, List<Hotspot>> byType = new HashMap<>();
        for (Hotspot h : all) {
            byType.computeIfAbsent((h.isEntity() ? "e:" : "b:") + h.typeId(), k -> new ArrayList<>()).add(h);
        }
        List<Group> gl = new ArrayList<>();
        for (List<Hotspot> members : byType.values()) {
            double sum = 0.0;
            double calls = 0.0;
            for (Hotspot h : members) {
                sum += h.microsPerFrame();
                calls += h.callsPerFrame();
            }
            if (sum < MIN_GROUP_MICROS)
                continue;
            members.sort(Comparator.comparingDouble(Hotspot::microsPerFrame).reversed());
            Hotspot first = members.get(0);
            List<Hotspot> kept = members.size() > MAX_MEMBERS
                    ? List.copyOf(members.subList(0, MAX_MEMBERS)) : List.copyOf(members);
            gl.add(new Group(first.isEntity(), first.typeId(), first.label(), members.size(), sum, calls, kept));
        }
        gl.sort(Comparator.comparingDouble(Group::microsPerFrame).reversed());
        if (gl.size() > MAX_GROUPS)
            gl = new ArrayList<>(gl.subList(0, MAX_GROUPS));

        List<Hotspot> shown = new ArrayList<>();
        for (Hotspot h : all) {
            if (h.microsPerFrame() >= MIN_MICROS_TO_SHOW)
                shown.add(h);
        }
        shown.sort(Comparator.comparingDouble(Hotspot::microsPerFrame).reversed());
        if (shown.size() > MAX_INSTANCES)
            shown = new ArrayList<>(shown.subList(0, MAX_INSTANCES));

        groups = List.copyOf(gl);
        instances = List.copyOf(shown);
        hotspots = List.copyOf(shown.size() > MAX_HIGHLIGHTS ? shown.subList(0, MAX_HIGHLIGHTS) : shown);
    }

    // ---- Chat summary ----

    private static void say(String text, ChatFormatting color) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal(text).withStyle(color), false);
    }

    /** A clickable chat button running a client command. */
    private static MutableComponent button(String label, String command, String hover, ChatFormatting color) {
        return Component.literal("[" + label + "]").withStyle(Style.EMPTY
                .withColor(color)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal(hover))));
    }

    private static void printChatSummary() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        List<Hotspot> inst = instances;
        List<Group> grp = groups;
        if (inst.isEmpty() && grp.isEmpty()) {
            say("[Fox's Tweaks] FPS scan done (" + frameCount
                    + " frames) - no heavy block entities or entities found.", ChatFormatting.GREEN);
            showingOverlay = false;
            return;
        }

        double fm = frameMicros();
        String frameInfo = fm > 0
                ? String.format(Locale.ROOT, "avg frame %.1f ms (%.0f FPS)", fm / 1000.0, 1_000_000.0 / fm)
                : "";
        mc.player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                "[Fox's Tweaks] FPS scan done: %d frames, %s", frameCount, frameInfo)).withStyle(ChatFormatting.GOLD), false);

        for (int i = 0; i < Math.min(3, inst.size()); i++) {
            Hotspot h = inst.get(i);
            int n = i + 1;
            mc.player.displayClientMessage(Component.literal(String.format(Locale.ROOT, "  %d. %.0f µs ", n,
                            h.microsPerFrame())).withStyle(heatChat(h.microsPerFrame()))
                    .append(Component.literal(percent(h.microsPerFrame()) + " ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(h.typeId() + " ").withStyle(ChatFormatting.WHITE))
                    .append(button("Look", "/foxstweaks fps look " + n, "Turn your view toward it", ChatFormatting.AQUA))
                    .append(Component.literal(" "))
                    .append(button("Go", "/foxstweaks fps tp " + n,
                            FpsActions.canTeleport() ? "Teleport next to it" : "Needs op / cheats", ChatFormatting.GREEN))
                    .append(Component.literal(" "))
                    .append(button("Show", "/foxstweaks fps show item " + n, "Highlight only this one", ChatFormatting.GOLD)),
                    false);
        }

        Group worst = null;
        for (Group g : grp) {
            if (g.count() > 1) {
                worst = g;
                break;
            }
        }
        if (worst != null) {
            int idx = grp.indexOf(worst) + 1;
            mc.player.displayClientMessage(Component.literal(String.format(Locale.ROOT,
                            "  Biggest type: %s x%d = %.2f ms %s ", worst.typeId(), worst.count(),
                            worst.microsPerFrame() / 1000.0, percent(worst.microsPerFrame())))
                    .withStyle(heatChat(worst.microsPerFrame()))
                    .append(button("Show all", "/foxstweaks fps show group " + idx,
                            "Highlight every one of them", ChatFormatting.GOLD)),
                    false);
        }

        mc.player.displayClientMessage(
                button("Open report", "/foxstweaks fps report", "Full list, details, teleport", ChatFormatting.AQUA)
                        .append(Component.literal("  "))
                        .append(button("Hide", "/foxstweaks fps clear", "Remove the highlights", ChatFormatting.GRAY)),
                false);
    }

    private static String percent(double micros) {
        return frameMicros() > 0.0
                ? String.format(Locale.ROOT, "(%.1f%% of frame)", share(micros) * 100.0) : "";
    }

    private static String shortName(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.replace('_', ' ');
    }
}
