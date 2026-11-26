package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Ease;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Key;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Mode;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Prop;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.TargetKind;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Track;

import java.util.List;

/**
 * The timeline maths, as pure functions (no rendering types), so the dev check can assert numbers.
 *
 * <p>Per frame the renderer calls {@link #evaluate} once per hologram that has a timeline: it binds tracks to
 * layer/group indices (cached on the timeline instance for as long as the spec's layer and group lists are the
 * same instances) and samples every track into a float array, one binary search per track. Then each draw asks
 * {@link #root}, {@link #group}, {@link #layer} or {@link #effect} for a value, which is {@code NaN} when nothing
 * animates it. Nothing allocates per frame; a hologram with no timeline never gets here.
 */
public final class HologramTimelineEval {
    public static final int PROPS = Prop.values().length;
    /** Slots per target: one per property, then one per effect index. */
    private static final int SLOTS = PROPS + HologramSpec.MAX_EFFECTS;
    private static final Prop[] PROP_VALUES = Prop.values();

    private HologramTimelineEval() {
    }

    // ---------------------------------------------------------------- clock

    /** Seconds since start mapped into the clip by its play mode. */
    public static double localTime(Mode mode, float length, double elapsed) {
        double len = length;
        return switch (mode) {
            case LOOP -> {
                double t = elapsed % len;
                yield t < 0.0 ? t + len : t;
            }
            case PINGPONG -> {
                double p = elapsed % (2.0 * len);
                if (p < 0.0)
                    p += 2.0 * len;
                yield p <= len ? p : 2.0 * len - p;
            }
            case ONCE -> Math.max(0.0, Math.min(len, elapsed));
        };
    }

    // ---------------------------------------------------------------- easing

    /** Easing curve of a segment at normalised time {@code u} (0..1). The y result may overshoot for CUBIC. */
    public static float ease(Ease ease, float x1, float y1, float x2, float y2, float u) {
        if (u <= 0.0F)
            return 0.0F;
        if (u >= 1.0F)
            return 1.0F;
        return switch (ease) {
            case LINEAR -> u;
            case HOLD -> 0.0F;
            case EASE_IN -> cubicBezier(0.42F, 0.0F, 1.0F, 1.0F, u);
            case EASE_OUT -> cubicBezier(0.0F, 0.0F, 0.58F, 1.0F, u);
            case EASE_IN_OUT -> cubicBezier(0.42F, 0.0F, 0.58F, 1.0F, u);
            case CUBIC -> cubicBezier(x1, y1, x2, y2, u);
        };
    }

    /**
     * CSS {@code cubic-bezier(x1, y1, x2, y2)} at {@code x = u}: solve the curve's x for its parameter (Newton,
     * bisection when the slope is flat), then return its y. With x1, x2 in 0..1 x is monotonic, so there is
     * exactly one answer.
     */
    public static float cubicBezier(float x1, float y1, float x2, float y2, float u) {
        double s = u;
        for (int i = 0; i < 8; i++) {
            double x = bez(x1, x2, s) - u;
            if (Math.abs(x) < 1.0E-6)
                return (float) bez(y1, y2, s);
            double d = bezSlope(x1, x2, s);
            if (Math.abs(d) < 1.0E-6)
                break;
            s -= x / d;
            if (s < 0.0 || s > 1.0)
                break;
        }
        double lo = 0.0;
        double hi = 1.0;
        s = u;
        for (int i = 0; i < 40; i++) {
            double x = bez(x1, x2, s);
            if (Math.abs(x - u) < 1.0E-6)
                break;
            if (x < u)
                lo = s;
            else
                hi = s;
            s = (lo + hi) * 0.5;
        }
        return (float) bez(y1, y2, s);
    }

    private static double bez(double p1, double p2, double s) {
        double inv = 1.0 - s;
        return 3.0 * inv * inv * s * p1 + 3.0 * inv * s * s * p2 + s * s * s;
    }

    private static double bezSlope(double p1, double p2, double s) {
        double inv = 1.0 - s;
        return 3.0 * inv * inv * p1 + 6.0 * inv * s * (p2 - p1) + 3.0 * s * s * (1.0 - p2);
    }

    // ---------------------------------------------------------------- sampling

    /** Index of the last key at or before {@code t}, or -1 when {@code t} is before the first key. */
    public static int keyBefore(List<Key> keys, double t) {
        int lo = 0;
        int hi = keys.size() - 1;
        int found = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (keys.get(mid).time() <= t) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found;
    }

    /** The track's value at clip time {@code t}; NaN for a track with no keys. Tint returns packed RGB. */
    public static float sample(Track track, double t) {
        List<Key> keys = track.keys();
        int n = keys.size();
        if (n == 0)
            return Float.NaN;
        int i = keyBefore(keys, t);
        if (i < 0)
            return keys.get(0).value();
        Key a = keys.get(i);
        if (i >= n - 1)
            return a.value();
        Key b = keys.get(i + 1);
        if (track.prop().stepped() || a.ease() == Ease.HOLD)
            return a.value();
        float span = b.time() - a.time();
        float u = span <= 0.0F ? 1.0F : (float) ((t - a.time()) / span);
        float e = ease(a.ease(), a.x1(), a.y1(), a.x2(), a.y2(), u);
        if (track.prop() == Prop.TINT)
            return lerpColor((int) a.value(), (int) b.value(), Math.max(0.0F, Math.min(1.0F, e)));
        return a.value() + (b.value() - a.value()) * e;
    }

    // ---------------------------------------------------------------- colour (OKLab)

    /**
     * Colour blend in OKLab, a perceptual space: red to green passes through a warm yellow instead of the muddy
     * brown a plain sRGB lerp gives, and brightness stays even across the blend.
     */
    public static int lerpColor(int a, int b, float u) {
        if (u <= 0.0F || a == b)
            return a;
        if (u >= 1.0F)
            return b;
        double[] la = LAB_A.get();
        double[] lb = LAB_B.get();
        toOklab(a, la);
        toOklab(b, lb);
        for (int i = 0; i < 3; i++)
            la[i] += (lb[i] - la[i]) * u;
        return fromOklab(la);
    }

    private static final ThreadLocal<double[]> LAB_A = ThreadLocal.withInitial(() -> new double[3]);
    private static final ThreadLocal<double[]> LAB_B = ThreadLocal.withInitial(() -> new double[3]);

    private static double toLinear(int c) {
        double v = c / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    private static int toSrgb(double v) {
        v = Math.max(0.0, Math.min(1.0, v));
        double s = v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, s)) * 255.0);
    }

    static void toOklab(int rgb, double[] out) {
        double r = toLinear((rgb >> 16) & 0xFF);
        double g = toLinear((rgb >> 8) & 0xFF);
        double b = toLinear(rgb & 0xFF);
        double l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
        double m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
        double s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
        out[0] = 0.2104542553 * l + 0.7936177850 * m - 0.0040720468 * s;
        out[1] = 1.9779984951 * l - 2.4285922050 * m + 0.4505937099 * s;
        out[2] = 0.0259040371 * l + 0.7827717662 * m - 0.8086757660 * s;
    }

    static int fromOklab(double[] lab) {
        double l = lab[0] + 0.3963377774 * lab[1] + 0.2158037573 * lab[2];
        double m = lab[0] - 0.1055613458 * lab[1] - 0.0638541728 * lab[2];
        double s = lab[0] - 0.0894841775 * lab[1] - 1.2914855480 * lab[2];
        l = l * l * l;
        m = m * m * m;
        s = s * s * s;
        int r = toSrgb(4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s);
        int g = toSrgb(-1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s);
        int b = toSrgb(-0.0041960863 * l - 0.7034186168 * m + 1.7076147010 * s);
        return (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- per-frame evaluation

    /** Binds (if the spec's lists changed) and samples every track at clip time {@code t}. */
    public static void evaluate(HologramTimeline tl, List<HologramGroup> groups, List<HologramLayer> layers, double t) {
        bind(tl, groups, layers);
        List<Track> tracks = tl.tracks();
        float[] values = tl.values;
        for (int i = 0; i < values.length; i++)
            values[i] = sample(tracks.get(i), t);
    }

    static void bind(HologramTimeline tl, List<HologramGroup> groups, List<HologramLayer> layers) {
        if (tl.boundLayers == layers && tl.boundGroups == groups && tl.values != null)
            return;
        int[] root = HologramTimeline.unbound(SLOTS);
        int[][] ls = new int[layers.size()][];
        int[][] gs = new int[groups.size()][];
        List<Track> tracks = tl.tracks();
        for (int ti = 0; ti < tracks.size(); ti++) {
            Track t = tracks.get(ti);
            int slot = t.prop() == Prop.EFFECT ? PROPS + t.effect() : t.prop().ordinal();
            switch (t.target()) {
                case ROOT -> root[slot] = ti;
                case GROUP -> {
                    for (int g = 0; g < groups.size(); g++) {
                        if (groups.get(g).id().equals(t.id())) {
                            if (gs[g] == null)
                                gs[g] = HologramTimeline.unbound(SLOTS);
                            gs[g][slot] = ti;
                        }
                    }
                }
                case LAYER -> {
                    for (int l = 0; l < layers.size(); l++) {
                        if (layers.get(l).uid().equals(t.id())) {
                            if (ls[l] == null)
                                ls[l] = HologramTimeline.unbound(SLOTS);
                            ls[l][slot] = ti;
                        }
                    }
                }
            }
        }
        tl.rootSlots = root;
        tl.layerSlots = ls;
        tl.groupSlots = gs;
        tl.values = new float[tracks.size()];
        tl.boundLayers = layers;
        tl.boundGroups = groups;
    }

    private static float slot(HologramTimeline tl, int[] slots, int slot) {
        if (slots == null)
            return Float.NaN;
        int ti = slots[slot];
        return ti < 0 ? Float.NaN : tl.values[ti];
    }

    public static float root(HologramTimeline tl, Prop prop) {
        return slot(tl, tl.rootSlots, prop.ordinal());
    }

    public static float group(HologramTimeline tl, int groupIndex, Prop prop) {
        int[][] gs = tl.groupSlots;
        return gs == null || groupIndex < 0 || groupIndex >= gs.length ? Float.NaN : slot(tl, gs[groupIndex], prop.ordinal());
    }

    public static float layer(HologramTimeline tl, int layerIndex, Prop prop) {
        int[][] ls = tl.layerSlots;
        return ls == null || layerIndex < 0 || layerIndex >= ls.length ? Float.NaN : slot(tl, ls[layerIndex], prop.ordinal());
    }

    /** Whether the group/layer has any track at all (lets a draw skip every per-property lookup). */
    public static boolean animates(HologramTimeline tl, TargetKind kind, int index) {
        if (kind == TargetKind.ROOT) {
            int[] r = tl.rootSlots;
            if (r == null)
                return false;
            for (int s : r)
                if (s >= 0)
                    return true;
            return false;
        }
        int[][] a = kind == TargetKind.GROUP ? tl.groupSlots : tl.layerSlots;
        return a != null && index >= 0 && index < a.length && a[index] != null;
    }

    /**
     * Fills {@code out} (length >= MAX_EFFECTS) with keyed strengths per effect index, NaN where none; returns false
     * (and leaves {@code out} alone) when the target keys no effect strength.
     */
    public static boolean effects(HologramTimeline tl, TargetKind kind, int index, float[] out) {
        int[][] a = kind == TargetKind.GROUP ? tl.groupSlots : tl.layerSlots;
        if (a == null || index < 0 || index >= a.length || a[index] == null)
            return false;
        int[] slots = a[index];
        boolean any = false;
        for (int fx = 0; fx < HologramSpec.MAX_EFFECTS; fx++) {
            int ti = slots[PROPS + fx];
            out[fx] = ti < 0 ? Float.NaN : tl.values[ti];
            any |= ti >= 0;
        }
        return any;
    }

    /** Value of {@code v} if it is set, else {@code fallback}. */
    public static float or(float v, float fallback) {
        return Float.isNaN(v) ? fallback : v;
    }

    /**
     * How far keyed offsets and scales can push content beyond the spec's static box, in the root's units; the
     * renderer inflates its frustum test by this so an animated layer is not culled while still on screen.
     */
    public static float cullPad(HologramTimeline tl) {
        if (tl.cullPad >= 0.0F)
            return tl.cullPad;
        float pad = 0.0F;
        for (Track t : tl.tracks()) {
            boolean offset = t.prop() == Prop.OFFSET_X || t.prop() == Prop.OFFSET_Y || t.prop() == Prop.OFFSET_Z;
            boolean scale = t.prop() == Prop.SCALE;
            if (!offset && !scale)
                continue;
            for (Key k : t.keys())
                pad = Math.max(pad, Math.abs(k.value()) * (scale ? 2.0F : 1.0F));
        }
        tl.cullPad = pad;
        return pad;
    }

    /** Name of a property slot, for messages and the dev check. */
    public static String propName(int slot) {
        return slot < PROPS ? PROP_VALUES[slot].name() : "EFFECT#" + (slot - PROPS);
    }
}
