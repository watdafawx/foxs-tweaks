package dev.watdafawx.foxstweaks.client.audio;

import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Param;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies audio modulations while a hologram is drawn. Render thread only.
 *
 * <p><b>Per-modulation state</b> (the attack/release envelope and the accumulated phase of rate-like targets) is
 * keyed by the identity of the {@link HologramAudioMod} record. The effect and timeline copies made every frame
 * share their lists, so the state survives them; an edit makes a new record and starts fresh, which is right.
 *
 * <p><b>Rates without jumps.</b> An animation reads {@code seconds * speed + phase}. Changing {@code speed} every
 * frame would make that jump back and forth, so for effects that use a phase the speed change is integrated over
 * time instead and folded into the phase: {@code phase' = phase + integral(delta) - seconds * delta}, which leaves
 * {@code seconds * (speed + delta) + phase'} continuous. A SPIN's strength (degrees per second) is handled the same
 * way. FX without a phase (pulse, scanline...) take the speed as is and can visibly step on a sharp change.
 */
public final class HologramAudioEval {

    private static final class State {
        float env;
        long lastNanos;
        double phaseAcc;
        float prevDt;
        long used;
    }

    /** Summed property modulations for one target; read right after {@link #props}. */
    public static final class Props {
        private final float[] add = new float[Param.values().length];
        private final boolean[] has = new boolean[Param.values().length];
        private final float[] min = new float[Param.values().length];
        private final float[] max = new float[Param.values().length];

        public boolean has(Param p) {
            return this.has[p.ordinal()];
        }

        /** {@code base} with the modulations added, clamped to their ranges. */
        public float apply(Param p, float base) {
            int i = p.ordinal();
            if (!this.has[i])
                return base;
            float v = base + this.add[i];
            if (!Float.isNaN(this.min[i]))
                v = Math.max(v, this.min[i]);
            if (!Float.isNaN(this.max[i]))
                v = Math.min(v, this.max[i]);
            return v;
        }

        /** VISIBLE and BRIGHT are factors 0..1 that start from "off" for positive amounts; 1 when unmodulated. */
        public float factor(Param p) {
            int i = p.ordinal();
            if (!this.has[i])
                return 1.0F;
            return Mth.clamp(apply(p, 1.0F), 0.0F, 1.0F);
        }

        public boolean hidden() {
            return has(Param.VISIBLE) && factor(Param.VISIBLE) < 0.5F;
        }

        /** {@code tint} darkened by the BRIGHT factor. */
        public int tint(int tint) {
            if (!has(Param.BRIGHT))
                return tint;
            float f = factor(Param.BRIGHT);
            int r = Math.round(((tint >> 16) & 0xFF) * f);
            int g = Math.round(((tint >> 8) & 0xFF) * f);
            int b = Math.round((tint & 0xFF) * f);
            return (r << 16) | (g << 8) | b;
        }

        private void reset() {
            java.util.Arrays.fill(this.add, 0.0F);
            java.util.Arrays.fill(this.has, false);
            java.util.Arrays.fill(this.min, Float.NaN);
            java.util.Arrays.fill(this.max, Float.NaN);
        }
    }

    private static final Map<HologramAudioMod, State> STATES = new IdentityHashMap<>();
    private static final Map<String, double[]> WARP = new HashMap<>();
    private static final Props PROPS = new Props();
    private static final float[][] SIG = new float[HologramAudioMod.Source.values().length][5];
    private static final long[] SIG_AT = new long[HologramAudioMod.Source.values().length];
    private static Vec3 at = Vec3.ZERO;
    private static long frame;
    private static long lastPrune;

    private HologramAudioEval() {
    }

    /** Where the hologram being drawn listens from; resets the per-frame signal cache. */
    public static void begin(Vec3 center) {
        at = center;
        frame++;
    }

    private static float signal(HologramAudioMod mod) {
        int s = mod.source().ordinal();
        if (SIG_AT[s] != frame) {
            SIG_AT[s] = frame;
            AudioHub.sample(mod.source(), at, SIG[s]);
        }
        return SIG[s][mod.signal().ordinal()];
    }

    /** The mod's current 0..1 output after sensitivity, step threshold and attack/release. */
    static float envelope(HologramAudioMod mod, float raw, long now) {
        State st = STATES.computeIfAbsent(mod, k -> new State());
        st.used = now;
        float target = Mth.clamp(raw * mod.sensitivity(), 0.0F, 1.0F);
        if (mod.threshold() > 0.0F)
            target = target >= mod.threshold() ? 1.0F : 0.0F;
        float dt = st.lastNanos == 0 ? 0.0F : Math.min(0.25F, (now - st.lastNanos) * 1.0E-9F);
        st.lastNanos = now;
        st.prevDt = dt;
        float tau = target > st.env ? mod.attack() : mod.release();
        st.env = tau <= 0.001F ? target : st.env + (target - st.env) * (1.0F - (float) Math.exp(-dt / tau));
        return st.env;
    }

    private static float contribution(HologramAudioMod mod, long now) {
        return mod.amount() * envelope(mod, signal(mod), now);
    }

    /** Effects with their own parameters modulated; the same list when none has a modulation. */
    public static List<HologramEffect> effects(List<HologramEffect> effects, double seconds) {
        boolean any = false;
        for (int i = 0; i < effects.size() && !any; i++)
            any = effects.get(i).hasAudio();
        if (!any)
            return effects;
        long now = System.nanoTime();
        prune(now);
        List<HologramEffect> out = new ArrayList<>(effects.size());
        for (HologramEffect e : effects)
            out.add(e.hasAudio() ? modulate(e, seconds, now) : e);
        return out;
    }

    private static HologramEffect modulate(HologramEffect e, double seconds, long now) {
        float strength = e.strength();
        float speed = e.speed();
        double phase = e.phase();
        float hue = 0.0F;
        float sMin = Float.NaN, sMax = Float.NaN, vMin = Float.NaN, vMax = Float.NaN;
        boolean spin = e.type() == HologramEffect.Type.SPIN;
        boolean phased = e.type().usesPhase();
        for (HologramAudioMod mod : e.audio()) {
            float c = contribution(mod, now);
            switch (mod.param()) {
                case STRENGTH -> {
                    if (spin) {
                        // Degrees per second: integrate so the angle never jumps.
                        State st = STATES.get(mod);
                        st.phaseAcc += c * dtOf(st, now);
                        phase += (st.phaseAcc - seconds * c) / 360.0;
                    }
                    strength += c;
                    if (mod.hasRange()) {
                        sMin = Float.isNaN(mod.min()) ? sMin : mod.min();
                        sMax = Float.isNaN(mod.max()) ? sMax : mod.max();
                    }
                }
                case SPEED -> {
                    if (phased) {
                        State st = STATES.get(mod);
                        st.phaseAcc += c * dtOf(st, now);
                        phase += st.phaseAcc - seconds * c;
                    }
                    speed += c;
                    if (mod.hasRange()) {
                        vMin = Float.isNaN(mod.min()) ? vMin : mod.min();
                        vMax = Float.isNaN(mod.max()) ? vMax : mod.max();
                    }
                }
                case PHASE -> phase += c;
                case HUE -> hue += c;
                default -> {
                }
            }
        }
        if (!Float.isNaN(sMin))
            strength = Math.max(strength, sMin);
        if (!Float.isNaN(sMax))
            strength = Math.min(strength, sMax);
        if (!Float.isNaN(vMin))
            speed = Math.max(speed, vMin);
        if (!Float.isNaN(vMax))
            speed = Math.min(speed, vMax);
        // Wrap in double: the raw sum can be thousands of cycles, past float precision.
        HologramEffect out = e.withStrength(strength).withSpeed(speed).withPhase((float) (phase - Math.floor(phase)));
        if (hue != 0.0F)
            out = out.withColor(shiftHue(e.color(), hue)).withColor2(shiftHue(e.color2(), hue));
        return out;
    }

    /** Seconds since this state's previous frame (the envelope just stored this frame's time in lastNanos). */
    private static float dtOf(State st, long now) {
        return st.prevDt;
    }

    /** Property modulations of one target; null when it has none. */
    @Nullable
    public static Props props(HologramTimeline tl, HologramTimeline.TargetKind kind, String id) {
        List<HologramTimeline.AudioLink> links = tl.audio();
        if (links.isEmpty())
            return null;
        Props p = null;
        long now = System.nanoTime();
        for (HologramTimeline.AudioLink link : links) {
            if (!link.matches(kind, id) || link.mod().param() == Param.RATE)
                continue;
            if (p == null) {
                p = PROPS;
                p.reset();
                prune(now);
            }
            HologramAudioMod mod = link.mod();
            int i = mod.param().ordinal();
            float c = contribution(mod, now);
            if (mod.param().baselineFromAmount() && mod.amount() > 0.0F)
                c -= mod.amount(); // positive: off at silence, the base value when loud
            p.add[i] += c;
            p.has[i] = true;
            if (!Float.isNaN(mod.min()))
                p.min[i] = mod.min();
            if (!Float.isNaN(mod.max()))
                p.max[i] = mod.max();
        }
        return p;
    }

    /** The playback-rate factor the root's RATE modulations ask for (1 = normal, never below 0). */
    public static float rate(HologramTimeline tl) {
        float r = 1.0F;
        boolean any = false;
        long now = System.nanoTime();
        for (HologramTimeline.AudioLink link : tl.audio()) {
            if (link.target() != HologramTimeline.TargetKind.ROOT || link.mod().param() != Param.RATE)
                continue;
            any = true;
            r += contribution(link.mod(), now);
        }
        return any ? Math.max(0.0F, r) : 1.0F;
    }

    public static boolean hasRate(@Nullable HologramTimeline tl) {
        if (tl == null)
            return false;
        for (HologramTimeline.AudioLink link : tl.audio())
            if (link.mod().param() == Param.RATE)
                return true;
        return false;
    }

    /**
     * {@code elapsed} warped by the audio-driven playback rate of {@code spec}'s timeline: integrated per hologram so
     * a rate change speeds the clip up or slows it down from where it is instead of jumping.
     */
    public static double warp(HologramSpec spec, HologramTimeline tl, double elapsed, Vec3 center) {
        at = center;
        frame++;
        double[] w = WARP.computeIfAbsent(spec.id(), k -> new double[] { elapsed, elapsed });
        double dt = elapsed - w[0];
        if (dt < 0.0 || dt > 1.0)
            dt = 0.0; // a jump (world reload, pause) restarts the integration
        w[0] = elapsed;
        w[1] += dt * rate(tl);
        if (WARP.size() > 512)
            WARP.clear();
        return w[1];
    }

    /**
     * Playback speed for time-based media on this hologram.
     * TODO(image layers): the IMAGE/frame-sequence playback should multiply its frame advance by this, so a video
     * layer can follow the music the same way the timeline does.
     */
    public static float playbackRate(HologramSpec spec) {
        HologramTimeline tl = spec.timeline();
        return tl == null || !hasRate(tl) ? 1.0F : rate(tl);
    }

    private static int shiftHue(int rgb, float turns) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F, g = ((rgb >> 8) & 0xFF) / 255.0F, b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b)), d = max - min;
        float h = 0.0F;
        if (d > 1.0E-6F) {
            if (max == r)
                h = ((g - b) / d) / 6.0F;
            else if (max == g)
                h = ((b - r) / d + 2.0F) / 6.0F;
            else
                h = ((r - g) / d + 4.0F) / 6.0F;
        }
        h += turns;
        h -= (float) Math.floor(h);
        return Mth.hsvToRgb(h, max <= 0.0F ? 0.0F : d / max, max) & 0xFFFFFF;
    }

    private static void prune(long now) {
        if (now - lastPrune < 5_000_000_000L)
            return;
        lastPrune = now;
        STATES.values().removeIf(s -> now - s.used > 10_000_000_000L);
    }

    // ------------------------------------------------------------ dev hooks

    /** Smoothed output of {@code mod} as last evaluated, or NaN. */
    public static float devEnvelope(HologramAudioMod mod) {
        State st = STATES.get(mod);
        return st == null ? Float.NaN : st.env;
    }

    public static void devReset() {
        STATES.clear();
        WARP.clear();
    }
}
