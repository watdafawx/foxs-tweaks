package dev.watdafawx.foxstweaks.client.perf;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side FPS hotspot profiler (Observable-style for render time).
 *
 * <p>When active, times every {@code BlockEntityRenderDispatcher#render} and
 * {@code EntityRenderDispatcher#render} call with {@code System.nanoTime()} (zero overhead when
 * inactive), maintains a live ranked list of the most expensive block entities and entities, and
 * feeds {@link FpsOverlayRenderer} to highlight them in-world with heat-colored boxes and labels.
 *
 * <p>Triggered either via {@code /foxstweaks fps [seconds|stop|clear]} or automatically when the
 * player runs {@code /sparkc profiler}.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class FpsProfiler {
    private static final int MAX_HIGHLIGHTS = 35;
    private static final double MIN_MICROS_TO_SHOW = 15.0;

    /** Fast-path flag checked by render mixins — single volatile read when off. */
    public static volatile boolean sampling;

    private static volatile boolean showingOverlay;
    private static boolean linkedToSpark;
    private static long startTimeMs;
    private static long endTimeMs;
    private static int frameCount;
    private static int tickCounter;

    // Primitive timing stack (handles nested BE/Entity renders cleanly without allocations).
    private static final long[] BE_START_STACK = new long[32];
    private static int beDepth;
    private static final long[] ENT_START_STACK = new long[32];
    private static int entDepth;

    private static final Map<BlockPos, Accum> BLOCK_ENTITIES = new HashMap<>();
    private static final Map<Integer, Accum> ENTITIES = new HashMap<>();
    private static volatile List<Hotspot> hotspots = List.of();

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
                                        }))));
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
        BLOCK_ENTITIES.clear();
        ENTITIES.clear();
        hotspots = List.of();
        beDepth = 0;
        entDepth = 0;
        frameCount = 0;
        tickCounter = 0;
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
        rebuildHotspots();

        if (printSummary) {
            printChatSummary();
        }
    }

    public static void clear() {
        sampling = false;
        showingOverlay = false;
        linkedToSpark = false;
        BLOCK_ENTITIES.clear();
        ENTITIES.clear();
        hotspots = List.of();

        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                    Component.literal("[Fox's Tweaks] Cleared FPS highlights.").withStyle(ChatFormatting.GRAY), false);
        }
    }

    public static boolean isShowingOverlay() {
        return showingOverlay;
    }

    public static List<Hotspot> getHotspots() {
        return hotspots;
    }

    public static int getFrameCount() {
        return frameCount;
    }

    public static double getRemainingSeconds() {
        if (!sampling)
            return 0.0;
        return Math.max(0.0, (endTimeMs - System.currentTimeMillis()) / 1000.0);
    }

    public static boolean isLinkedToSpark() {
        return linkedToSpark;
    }

    // ---- Mixin hooks (render thread only) ----

    public static void beginBlockEntity() {
        if (beDepth < BE_START_STACK.length) {
            BE_START_STACK[beDepth] = System.nanoTime();
        }
        beDepth++;
    }

    public static void endBlockEntity(BlockEntity be) {
        if (beDepth <= 0)
            return;
        beDepth--;
        if (!sampling || be == null || beDepth >= BE_START_STACK.length)
            return;

        long elapsed = System.nanoTime() - BE_START_STACK[beDepth];
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
        }
        entDepth++;
    }

    public static void endEntity(Entity entity) {
        if (entDepth <= 0)
            return;
        entDepth--;
        if (!sampling || entity == null || entDepth >= ENT_START_STACK.length)
            return;

        long elapsed = System.nanoTime() - ENT_START_STACK[entDepth];
        if (elapsed <= 0L)
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
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (sampling || showingOverlay) {
                sampling = false;
                showingOverlay = false;
                BLOCK_ENTITIES.clear();
                ENTITIES.clear();
                hotspots = List.of();
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

    private static void rebuildHotspots() {
        int frames = Math.max(1, frameCount);
        List<Hotspot> list = new ArrayList<>(BLOCK_ENTITIES.size() + ENTITIES.size());

        for (Accum a : BLOCK_ENTITIES.values()) {
            double us = (a.totalNanos / 1000.0) / frames;
            if (us >= MIN_MICROS_TO_SHOW) {
                list.add(new Hotspot(false, -1, a.blockPos, a.lastBox, a.label, a.typeId, us,
                        Math.max(1, Math.round((float) a.calls / frames))));
            }
        }
        for (Accum a : ENTITIES.values()) {
            double us = (a.totalNanos / 1000.0) / frames;
            if (us >= MIN_MICROS_TO_SHOW) {
                list.add(new Hotspot(true, a.entityId, null, a.lastBox, a.label, a.typeId, us,
                        Math.max(1, Math.round((float) a.calls / frames))));
            }
        }

        list.sort(Comparator.comparingDouble(Hotspot::microsPerFrame).reversed());
        if (list.size() > MAX_HIGHLIGHTS) {
            list = new ArrayList<>(list.subList(0, MAX_HIGHLIGHTS));
        }
        hotspots = list;
    }

    private static void printChatSummary() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;

        List<Hotspot> list = hotspots;
        if (list.isEmpty()) {
            mc.player.displayClientMessage(
                    Component.literal("[Fox's Tweaks] FPS scan done (" + frameCount
                            + " frames) — no heavy BlockEntities or Entities found.").withStyle(ChatFormatting.GREEN),
                    false);
            showingOverlay = false;
            return;
        }

        double totalUs = 0;
        for (Hotspot h : list) {
            totalUs += h.microsPerFrame();
        }

        mc.player.displayClientMessage(
                Component.literal(String.format(Locale.ROOT,
                        "[Fox's Tweaks] Top FPS hotspots (%d frames, %.1f ms/f combined):",
                        frameCount, totalUs / 1000.0)).withStyle(ChatFormatting.GOLD),
                false);

        int shown = Math.min(8, list.size());
        for (int i = 0; i < shown; i++) {
            Hotspot h = list.get(i);
            ChatFormatting color = h.microsPerFrame() >= 500 ? ChatFormatting.RED
                    : h.microsPerFrame() >= 200 ? ChatFormatting.GOLD : ChatFormatting.YELLOW;
            String where = h.isEntity()
                    ? String.format(Locale.ROOT, "(%.0f, %.0f, %.0f)",
                            h.fallbackBox().getCenter().x, h.fallbackBox().getCenter().y, h.fallbackBox().getCenter().z)
                    : String.format(Locale.ROOT, "(%d, %d, %d)",
                            h.blockPos().getX(), h.blockPos().getY(), h.blockPos().getZ());
            mc.player.displayClientMessage(
                    Component.literal(String.format(Locale.ROOT, "  %d. %.0f \u00b5s/f ", i + 1, h.microsPerFrame()))
                            .withStyle(color)
                            .append(Component.literal(h.typeId()).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(" @ " + where).withStyle(ChatFormatting.GRAY)),
                    false);
        }

        mc.player.displayClientMessage(
                Component.literal("  Highlights stay in-world. Run /foxstweaks fps clear to hide.")
                        .withStyle(ChatFormatting.DARK_GRAY),
                false);
    }

    private static String shortName(String id) {
        int colon = id.indexOf(':');
        String path = colon >= 0 ? id.substring(colon + 1) : id;
        return path.replace('_', ' ');
    }
}
