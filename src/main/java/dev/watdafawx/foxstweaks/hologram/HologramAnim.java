package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.List;

/**
 * All of the hologram's motion maths, as pure functions of time and distance.
 *
 * <p>Kept separate from the renderer so the dev check can assert the actual curves (an appear radius
 * that never reaches 1.0 is invisible in a screenshot) and so nothing here needs a client to run.
 */
public final class HologramAnim {

    /** 1 while a player is inside the radius, 0 outside; always 1 when the radius is off. */
    public static float appearTarget(double distanceSq, float radius) {
        if (radius <= 0.0F)
            return 1.0F;
        return distanceSq <= (double) radius * radius ? 1.0F : 0.0F;
    }

    /** Moves {@code current} toward {@code target} over {@code seconds}, linearly in time. */
    public static float approach(float current, float target, float seconds, double deltaSeconds) {
        if (seconds <= 0.0F)
            return target;
        float step = (float) Math.max(0.0, deltaSeconds) / seconds;
        if (current < target)
            return Math.min(target, current + step);
        return Math.max(target, current - step);
    }

    /** Smoothstep, so the grow-in eases instead of snapping. */
    public static float ease(float progress) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);
        return p * p * (3.0F - 2.0F * p);
    }

    /** Deterministic -1..1 noise from two out-of-phase sines; no per-frame randomness to jitter. */
    public static float wave(double seconds, float speed, double phase) {
        double t = seconds * speed;
        return (float) (0.5 * Math.sin((t + phase) * Mth.TWO_PI) + 0.5 * Math.sin((t * 2.37 + phase) * Mth.TWO_PI));
    }

    /** True while a FLICKER layer wants the hologram hidden. */
    public static boolean flickerHidden(List<HologramEffect> effects, double seconds) {
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.FLICKER)
                continue;
            float shake = wave(seconds, effect.speed(), 0.11);
            // Only the bottom of the wave hides it, so a strength of 1 is a strobe and 0.2 is a stutter.
            if (shake < -1.0F + effect.strength() * 1.2F)
                return true;
        }
        return false;
    }

    /** Degrees of jitter for TREMOR layers, summed. */
    public static float tremorDegrees(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.TREMOR)
                total += wave(seconds, effect.speed(), 0.37) * 2.5F * effect.strength();
        }
        return total;
    }

    /** Multiplier for WOBBLE layers, around 1.0. */
    public static float wobbleScale(List<HologramEffect> effects, double seconds) {
        float total = 1.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.WOBBLE)
                total += wave(seconds, effect.speed(), 0.71) * 0.06F * effect.strength();
        }
        return total;
    }

    /** Vertical offset in blocks for JUMP layers, summed. */
    public static float jumpOffset(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.JUMP)
                continue;
            // A sawtooth: hold, then snap. That reads as a mistimed projection, not a bobbing float.
            double t = seconds * effect.speed();
            float snap = (float) (t - Math.floor(t));
            total += (snap < 0.4 ? 0.0F : effect.strength() * 0.35F);
        }
        return total;
    }

    /** The colour multiplier of the first TINT layer, or null when there is none. */
    @Nullable
    public static HologramEffect tint(List<HologramEffect> effects) {
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.TINT)
                return effect;
        }
        return null;
    }

    private HologramAnim() {
    }
}
