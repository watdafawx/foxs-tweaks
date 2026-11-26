package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.util.Mth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The settings of a {@link HologramLayer.Kind#MODEL} layer: a 3D model from the asset store, with its animation.
 * Like {@link HologramImage} they live in the layer's {@code props} map, and {@link #normalize} is the one place
 * that clamps untrusted props. The layer's {@code id} is the asset hash.
 *
 * @param height    the model's drawn height in blocks (its bounding box is scaled to this); 0 = the model's own size
 * @param animation name of the animation to play, "" = the rest pose
 * @param playback  what happens at the end of the animation
 * @param speed     playback speed multiplier (negative runs backwards)
 * @param start     seconds into the animation the clock starts at
 */
public record HologramModelSettings(float height, String animation, HologramImage.Playback playback, float speed, float start) {
    public static final float MAX_HEIGHT = 32.0F;
    public static final int MAX_NAME = 64;

    public static final String HEIGHT = "h";
    public static final String ANIMATION = "anim";
    public static final String PLAYBACK = "play";
    public static final String SPEED = "speed";
    public static final String START = "start";

    public static final HologramModelSettings DEFAULT = new HologramModelSettings(1.0F, "", HologramImage.Playback.LOOP, 1.0F, 0.0F);

    public HologramModelSettings {
        height = !Float.isFinite(height) || height <= 0.0F ? 0.0F : Mth.clamp(height, 0.05F, MAX_HEIGHT);
        animation = clean(animation);
        playback = playback == null ? HologramImage.Playback.LOOP : playback;
        speed = Mth.clamp(Float.isFinite(speed) ? speed : 1.0F, -8.0F, 8.0F);
        start = Mth.clamp(Float.isFinite(start) ? start : 0.0F, 0.0F, 600.0F);
    }

    private static String clean(String s) {
        if (s == null)
            return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length() && out.length() < MAX_NAME; i++)
            if (!Character.isISOControl(s.charAt(i)))
                out.append(s.charAt(i));
        return out.toString();
    }

    public static HologramModelSettings of(Map<String, String> props) {
        HologramImage.Playback play = HologramImage.Playback.LOOP;
        String raw = props.get(PLAYBACK);
        if (raw != null) {
            try {
                play = HologramImage.Playback.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // keep the default
            }
        }
        return new HologramModelSettings(number(props.get(HEIGHT), 1.0F), props.get(ANIMATION), play,
                number(props.get(SPEED), 1.0F), number(props.get(START), 0.0F));
    }

    public Map<String, String> toProps() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(HEIGHT, format(this.height));
        if (!this.animation.isEmpty())
            out.put(ANIMATION, this.animation);
        if (this.playback != HologramImage.Playback.LOOP)
            out.put(PLAYBACK, this.playback.name());
        if (this.speed != 1.0F)
            out.put(SPEED, format(this.speed));
        if (this.start != 0.0F)
            out.put(START, format(this.start));
        return out;
    }

    public static Map<String, String> normalize(Map<String, String> props) {
        return of(props).toProps();
    }

    public HologramModelSettings withHeight(float height) {
        return new HologramModelSettings(height, this.animation, this.playback, this.speed, this.start);
    }

    public HologramModelSettings withAnimation(String animation) {
        return new HologramModelSettings(this.height, animation, this.playback, this.speed, this.start);
    }

    public HologramModelSettings withPlayback(HologramImage.Playback playback, float speed, float start) {
        return new HologramModelSettings(this.height, this.animation, playback, speed, start);
    }

    /** Model time for hologram clock {@code clock} on an animation {@code length} seconds long. */
    public double time(double clock, double length) {
        double raw = this.start + clock * this.speed;
        if (!(length > 0.0) || !Double.isFinite(raw))
            return 0.0;
        return switch (this.playback) {
            case LOOP -> ((raw % length) + length) % length;
            case ONCE -> Math.max(0.0, Math.min(length, raw));
            case PINGPONG -> {
                double period = length * 2.0;
                double p = ((raw % period) + period) % period;
                yield p < length ? p : period - p;
            }
        };
    }

    private static float number(String raw, float fallback) {
        if (raw == null)
            return fallback;
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String format(float value) {
        float rounded = Math.round(value * 1000.0F) / 1000.0F;
        return rounded == (int) rounded ? Integer.toString((int) rounded) : Float.toString(rounded);
    }
}
