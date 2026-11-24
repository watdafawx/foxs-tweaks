package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.List;

/**
 * All of the hologram's motion and effect maths, as pure functions of time and distance.
 *
 * <p>Kept separate from the renderer so the dev check can assert the actual curves and so nothing
 * here needs a client to run.
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

    /** Overshoot bounce ease for the POP reveal style. */
    public static float easeOutBack(float progress) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);
        float c1 = 1.70158F;
        float c3 = c1 + 1.0F;
        float u = p - 1.0F;
        return Math.max(0.01F, 1.0F + c3 * u * u * u + c1 * u * u);
    }

    /**
     * Computed transform for the 6 Walk-in Reveal styles at progress {@code p} in 0..1.
     */
    public record RevealPose(float sx, float sy, float sz, float dy, float spinY, float glitchX) {
    }

    public static RevealPose revealPose(int style, float progress, double seconds) {
        float p = Mth.clamp(progress, 0.0F, 1.0F);
        if (p >= 0.999F)
            return new RevealPose(1.0F, 1.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        float e = ease(p);
        return switch (style) {
            case HologramSpec.REVEAL_POP -> {
                float s = easeOutBack(p);
                yield new RevealPose(s, s, s, 0.0F, 0.0F, 0.0F);
            }
            case HologramSpec.REVEAL_UNFOLD -> {
                // Sci-fi teleporter beam: thin & tall -> expands into full shape.
                float sxz = 0.06F + 0.94F * e;
                float sy = 2.2F - 1.2F * e;
                yield new RevealPose(sxz, sy, sxz, 0.0F, 0.0F, 0.0F);
            }
            case HologramSpec.REVEAL_SPIN -> {
                float s = 0.1F + 0.9F * e;
                float spin = (1.0F - e) * 360.0F;
                yield new RevealPose(s, s, s, -(1.0F - e) * 0.4F, spin, 0.0F);
            }
            case HologramSpec.REVEAL_DROP -> {
                // Drops from above and settles with a slight landing squash.
                float dropY = (1.0F - e) * 2.5F;
                float squash = p > 0.75F ? (float) Math.sin((p - 0.75F) * 4.0F * Math.PI) * 0.15F : 0.0F;
                yield new RevealPose(1.0F + squash, 1.0F - squash, 1.0F + squash, dropY, 0.0F, 0.0F);
            }
            case HologramSpec.REVEAL_GLITCH -> {
                float s = 0.35F + 0.65F * e;
                float jitter = (1.0F - e) * wave(seconds, 18.0F, 0.23) * 0.65F;
                yield new RevealPose(s, s, s, 0.0F, 0.0F, jitter);
            }
            default -> {
                // REVEAL_RISE: smooth grow + rise into place.
                float s = 0.15F + 0.85F * e;
                yield new RevealPose(s, s, s, -(1.0F - e) * 0.65F, 0.0F, 0.0F);
            }
        };
    }

    /** Deterministic -1..1 noise from two out-of-phase sines; no per-frame randomness to jitter. */
    public static float wave(double seconds, float speed, double phase) {
        double t = (seconds * speed) % 1000.0;
        return (float) (0.5 * Math.sin((t + phase) * (Math.PI * 2.0))
                + 0.5 * Math.sin((t * 2.37 + phase) * (Math.PI * 2.0)));
    }

    /** True while a FLICKER or GLITCH layer wants the hologram briefly hidden. */
    public static boolean flickerHidden(List<HologramEffect> effects, double seconds) {
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.FLICKER) {
                float shake = wave(seconds, effect.speed(), 0.11);
                if (shake < -1.0F + effect.strength() * 1.2F)
                    return true;
            } else if (effect.type() == HologramEffect.Type.GLITCH) {
                if (glitchBurst(seconds, effect.speed()) > 0.92F && effect.strength() > 0.6F)
                    return true;
            }
        }
        return false;
    }

    /** 0..1 spike during periodic cyber-glitch bursts. */
    public static float glitchBurst(double seconds, float speed) {
        double t = (seconds * speed) % 1000.0;
        double cycle = t - Math.floor(t);
        if (cycle > 0.18)
            return 0.0F;
        return (float) Math.sin((cycle / 0.18) * Math.PI);
    }

    /** Horizontal X glitch snap in blocks. */
    public static float glitchOffsetX(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.GLITCH)
                continue;
            float burst = glitchBurst(seconds, effect.speed());
            if (burst > 0.01F)
                total += Math.signum(wave(seconds, effect.speed() * 11.0F, 0.19)) * burst * 0.28F * effect.strength();
        }
        return total;
    }

    /** Horizontal Z glitch snap in blocks. */
    public static float glitchOffsetZ(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.GLITCH)
                continue;
            float burst = glitchBurst(seconds, effect.speed());
            if (burst > 0.01F)
                total += wave(seconds, effect.speed() * 13.0F, 0.63) * burst * 0.22F * effect.strength();
        }
        return total;
    }

    /** Degrees of jitter for TREMOR layers, summed. */
    public static float tremorDegrees(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.TREMOR)
                total += wave(seconds, effect.speed(), 0.37) * 5.0F * effect.strength();
        }
        return total;
    }

    /** Vertical squash-and-stretch multiplier for WOBBLE layers, around 1.0. */
    public static float wobbleScaleY(List<HologramEffect> effects, double seconds) {
        float total = 1.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.WOBBLE)
                total += wave(seconds, effect.speed(), 0.71) * 0.14F * effect.strength();
        }
        return Math.max(0.4F, total);
    }

    /** Horizontal squash-and-stretch (inverse of Y so volume feels preserved). */
    public static float wobbleScaleXZ(List<HologramEffect> effects, double seconds) {
        float sy = wobbleScaleY(effects, seconds);
        return 1.0F / (float) Math.sqrt(sy);
    }

    /** Backwards-compatible helper returning Y wobble scale. */
    public static float wobbleScale(List<HologramEffect> effects, double seconds) {
        return wobbleScaleY(effects, seconds);
    }

    /** Vertical offset in blocks for JUMP layers, summed. */
    public static float jumpOffset(List<HologramEffect> effects, double seconds) {
        float total = 0.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.JUMP)
                continue;
            double t = (seconds * effect.speed()) % 1000.0;
            float snap = (float) (t - Math.floor(t));
            total += (snap < 0.45 ? 0.0F : effect.strength() * 0.45F);
        }
        return total;
    }

    /**
     * The summed result of every motion animation on a layer or group: a translation in blocks, a rotation
     * in degrees per axis, and a uniform scale factor. Applied on top of the layer's own transform.
     */
    public record Motion(float dx, float dy, float dz, float rx, float ry, float rz, float scale) {
        public static final Motion NONE = new Motion(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F);
    }

    /** Sums every SPIN/SWAY/BOB/ORBIT/SLIDE/BREATHE/HEARTBEAT/FLIP/BOUNCE entry; {@link Motion#NONE} when there are none. */
    public static Motion motion(List<HologramEffect> effects, double seconds) {
        float dx = 0.0F, dy = 0.0F, dz = 0.0F;
        float rx = 0.0F, ry = 0.0F, rz = 0.0F;
        float scale = 1.0F;
        boolean any = false;

        for (HologramEffect effect : effects) {
            if (!effect.type().isMotion())
                continue;
            any = true;

            float amount = effect.strength();
            double cycles = seconds * effect.speed() + effect.phase();
            double frac = cycles - Math.floor(cycles);
            float sine = (float) Math.sin(frac * (Math.PI * 2.0));
            int axis = effect.axis();

            switch (effect.type()) {
                case SPIN -> {
                    float angle = (float) ((seconds * amount + effect.phase() * 360.0) % 360.0);
                    switch (axis) {
                        case HologramSpec.AXIS_X -> rx += angle;
                        case HologramSpec.AXIS_Z -> rz += angle;
                        default -> ry += angle;
                    }
                }
                case SWAY -> {
                    float angle = amount * sine;
                    switch (axis) {
                        case HologramSpec.AXIS_X -> rx += angle;
                        case HologramSpec.AXIS_Z -> rz += angle;
                        default -> ry += angle;
                    }
                }
                case FLIP -> {
                    // Turn during the first 40% of each cycle, hold still for the rest.
                    float angle = amount * ease((float) (frac / 0.4));
                    switch (axis) {
                        case HologramSpec.AXIS_X -> rx += angle;
                        case HologramSpec.AXIS_Z -> rz += angle;
                        default -> ry += angle;
                    }
                }
                case BOB -> {
                    float d = amount * sine;
                    switch (axis) {
                        case HologramSpec.AXIS_X -> dx += d;
                        case HologramSpec.AXIS_Z -> dz += d;
                        default -> dy += d;
                    }
                }
                case SLIDE -> {
                    // Triangle wave through smoothstep: dwells at each end instead of reversing instantly.
                    float tri = (float) (1.0 - 4.0 * Math.abs(frac - 0.5)) * -1.0F;
                    float d = amount * (ease((tri + 1.0F) * 0.5F) * 2.0F - 1.0F);
                    switch (axis) {
                        case HologramSpec.AXIS_X -> dx += d;
                        case HologramSpec.AXIS_Z -> dz += d;
                        default -> dy += d;
                    }
                }
                case BOUNCE -> {
                    float d = amount * (float) Math.abs(Math.sin(frac * Math.PI));
                    switch (axis) {
                        case HologramSpec.AXIS_X -> dx += d;
                        case HologramSpec.AXIS_Z -> dz += d;
                        default -> dy += d;
                    }
                }
                case ORBIT -> {
                    float cos = (float) Math.cos(frac * (Math.PI * 2.0)) * amount;
                    float sin = sine * amount;
                    switch (axis) {
                        case HologramSpec.AXIS_X -> {
                            dy += cos;
                            dz += sin;
                        }
                        case HologramSpec.AXIS_Z -> {
                            dx += cos;
                            dy += sin;
                        }
                        default -> {
                            dx += cos;
                            dz += sin;
                        }
                    }
                }
                case BREATHE -> scale *= Math.max(0.05F, 1.0F + amount * sine);
                case HEARTBEAT -> {
                    float beat = bump(frac, 0.0, 0.16) + 0.7F * bump(frac, 0.24, 0.16);
                    scale *= Math.max(0.05F, 1.0F + amount * beat);
                }
                default -> {
                }
            }
        }

        return any ? new Motion(dx, dy, dz, rx, ry, rz, scale) : Motion.NONE;
    }

    /** A half-sine hump of width {@code length} starting at {@code start}, 0 elsewhere. */
    private static float bump(double frac, double start, double length) {
        if (frac < start || frac >= start + length)
            return 0.0F;
        return (float) Math.sin((frac - start) / length * Math.PI);
    }

    /** Brightness multiplier from PULSE layers (0.35 .. 1.35). */
    public static float pulseMultiplier(List<HologramEffect> effects, double seconds) {
        float mult = 1.0F;
        for (HologramEffect effect : effects) {
            if (effect.type() != HologramEffect.Type.PULSE)
                continue;
            double phase = ((seconds * effect.speed()) % 1.0) * (Math.PI * 2.0);
            float wave = (float) (0.5 + 0.5 * Math.sin(phase)); // 0..1
            float depth = Mth.clamp(effect.strength() * 0.55F, 0.0F, 0.75F);
            mult *= (1.0F - depth) + depth * 1.45F * wave;
        }
        return mult;
    }

    /** Packed RGB for RAINBOW layer, or -1 if none. */
    public static int rainbowColor(List<HologramEffect> effects, double seconds) {
        for (HologramEffect effect : effects) {
            if (effect.type() == HologramEffect.Type.RAINBOW) {
                float hue = (float) ((seconds * effect.speed()) % 1.0);
                if (hue < 0.0F)
                    hue += 1.0F;
                return Mth.hsvToRgb(hue, Mth.clamp(effect.strength() * 0.85F, 0.1F, 1.0F), 1.0F);
            }
        }
        return -1;
    }

    @Nullable
    public static HologramEffect firstOf(List<HologramEffect> effects, HologramEffect.Type type) {
        for (HologramEffect effect : effects) {
            if (effect.type() == type)
                return effect;
        }
        return null;
    }

    /** The colour multiplier of the first TINT layer, or null when there is none. */
    @Nullable
    public static HologramEffect tint(List<HologramEffect> effects) {
        return firstOf(effects, HologramEffect.Type.TINT);
    }

    private HologramAnim() {
    }
}
