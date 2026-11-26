package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline;
import dev.watdafawx.foxstweaks.hologram.HologramTimelineEval;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Where each hologram's timeline is right now (clip seconds), on the client.
 *
 * <p>The clock is the <b>level's game time</b> (ticks + partial tick, over 20), not the effect clock: every
 * client in the world reads the same game time, so all players see the same frame of an untriggered timeline,
 * and it pauses with the game. The effect clock ({@link HologramRenderer#animSeconds()}) is a per-client
 * accumulator, which is fine for loops nobody compares but would let two players' keyframes drift apart.
 *
 * <p>A start trigger restarts the clip: a hold trigger (day, rain, jukebox, ...) plays from the moment its
 * condition turns true and shows frame 0 while it is false; a pulse (a mob died within range) plays from the
 * latest death and then follows the play mode (ONCE holds the last frame until the next death).
 *
 * <p>The web editor can override one hologram's playhead for the local preview only ({@link #setPlayhead}):
 * paused at a time (scrubbing) or playing from a time. Nothing about it is saved or sent to the server.
 */
public final class HologramTimelinePlayer {

    public enum PlayheadMode {
        /** No override: the normal game-time clock. */
        LIVE,
        PLAY,
        PAUSE
    }

    private static final class TriggerState {
        boolean open;
        double start = Double.NaN;
        long used;
    }

    private static final Map<String, TriggerState> TRIGGERS = new HashMap<>();
    private static final Map<String, Double> LAST = new HashMap<>();

    @Nullable
    private static String overrideId;
    private static PlayheadMode overrideMode = PlayheadMode.LIVE;
    private static double overrideTime;
    private static long overrideNanos;

    private static boolean frozen;
    private static double frozenElapsed;
    private static long lastPrune;

    private HologramTimelinePlayer() {
    }

    /** Preview-only playhead for hologram {@code id}; {@link PlayheadMode#LIVE} drops the override. */
    public static void setPlayhead(String id, PlayheadMode mode, double time) {
        if (mode == PlayheadMode.LIVE) {
            clearPlayhead();
            return;
        }
        overrideId = id;
        overrideMode = mode;
        overrideTime = Double.isFinite(time) ? Math.max(0.0, time) : 0.0;
        overrideNanos = System.nanoTime();
    }

    public static void clearPlayhead() {
        overrideId = null;
        overrideMode = PlayheadMode.LIVE;
    }

    public static PlayheadMode playheadMode(String id) {
        return id.equals(overrideId) ? overrideMode : PlayheadMode.LIVE;
    }

    /** Clip time last drawn for hologram {@code id}, or NaN; dev checks. */
    public static double lastTime(String id) {
        Double v = LAST.get(id);
        return v == null ? Double.NaN : v;
    }

    /** Game seconds the clock runs on: level time, frozen while the editor's animation pause is on. */
    static double elapsed(Level level, float partialTick) {
        double now = (level.getGameTime() + (double) partialTick) / 20.0;
        if (HologramRenderer.isPaused()) {
            if (!frozen) {
                frozen = true;
                frozenElapsed = now;
            }
            return frozenElapsed;
        }
        frozen = false;
        return now;
    }

    /** Clip time to draw {@code spec}'s timeline at this frame. */
    public static double time(HologramSpec spec, HologramTimeline tl, float partialTick) {
        double t = compute(spec, tl, partialTick);
        LAST.put(spec.id(), t);
        return t;
    }

    private static double compute(HologramSpec spec, HologramTimeline tl, float partialTick) {
        float len = tl.length();
        if (spec.id().equals(overrideId)) {
            if (overrideMode == PlayheadMode.PAUSE)
                return Math.min(len, overrideTime);
            double since = (System.nanoTime() - overrideNanos) * 1.0E-9;
            return HologramTimelineEval.localTime(tl.mode(), len, overrideTime + since);
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
            return 0.0;
        double elapsed = elapsed(level, partialTick);
        HologramEffect.Trigger start = tl.start();
        if (start == HologramEffect.Trigger.ALWAYS)
            return HologramTimelineEval.localTime(tl.mode(), len, elapsed);

        prune();
        TriggerState st = TRIGGERS.computeIfAbsent(spec.id(), k -> new TriggerState());
        st.used = System.nanoTime();
        Vec3 center = spec.box().getCenter();
        if (start.isPulse()) {
            double age = HologramTriggers.latestDeathAge(center);
            if (age >= 0.0) {
                double at = elapsed - age;
                // A newer death restarts the clip; the same death seen again must not nudge it.
                if (Double.isNaN(st.start) || at > st.start + 0.25)
                    st.start = at;
            }
            return Double.isNaN(st.start) ? 0.0 : HologramTimelineEval.localTime(tl.mode(), len, elapsed - st.start);
        }
        boolean open = HologramTriggers.conditionLevel(start, level, center) >= 0.5F;
        if (open && !st.open)
            st.start = elapsed;
        st.open = open;
        if (!open || Double.isNaN(st.start))
            return 0.0;
        return HologramTimelineEval.localTime(tl.mode(), len, elapsed - st.start);
    }

    private static void prune() {
        long now = System.nanoTime();
        if (now - lastPrune < 5_000_000_000L)
            return;
        lastPrune = now;
        TRIGGERS.values().removeIf(s -> now - s.used > 30_000_000_000L);
        if (LAST.size() > 512)
            LAST.clear();
    }

    public static void clear() {
        TRIGGERS.clear();
        LAST.clear();
        clearPlayhead();
    }
}
