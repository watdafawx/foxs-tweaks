package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.util.Mth;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * The settings of an {@link HologramLayer.Kind#IMAGE} layer: a flat picture from the asset store.
 *
 * <p>They live in the layer's {@code props} map (string keys, string values) instead of new record components:
 * {@code props} already travels through every save path (NBT, the editor packet, the web JSON), so an image
 * layer needs no new field anywhere, and an old save simply has no image layers. The layer's {@code id} is the
 * asset's SHA-256 and its {@code fullbright} flag is "emissive". {@link #normalize} is the one place that turns
 * untrusted props into clamped ones; {@link HologramLayer}'s constructor runs it, so garbage never gets stored.
 *
 * @param width       quad width in blocks (before the layer's scale)
 * @param height      quad height in blocks; 0 keeps the asset's aspect ratio
 * @param billboard   how the quad is turned
 * @param doubleSided false hides the back of a {@link Billboard#FIXED} quad
 * @param playback    what happens at the end of an animation
 * @param speed       playback speed multiplier (negative runs backwards)
 * @param start       seconds into the animation the clock starts at
 */
public record HologramImage(float width, float height, Billboard billboard, boolean doubleSided, Playback playback,
                            float speed, float start) {

    public enum Billboard {
        /** Follows the layer's rotation like any block. */
        FIXED,
        /** Always faces the camera. */
        FACE,
        /** Turns around the vertical axis only, so it stays upright. */
        FACE_Y
    }

    public enum Playback {
        LOOP,
        ONCE,
        PINGPONG
    }

    public static final float MIN_SIZE = 0.05F;
    public static final float MAX_SIZE = 16.0F;

    public static final String WIDTH = "w";
    public static final String HEIGHT = "h";
    public static final String BILLBOARD = "billboard";
    public static final String DOUBLE_SIDED = "sides";
    public static final String PLAYBACK = "play";
    public static final String SPEED = "speed";
    public static final String START = "start";

    public static final HologramImage DEFAULT = new HologramImage(1.0F, 0.0F, Billboard.FIXED, true, Playback.LOOP, 1.0F, 0.0F);

    /**
     * Client-only hook: asset hash -> height / width, or NaN while unknown. The server leaves it unset and treats
     * an auto height as square, which only matters for the reach box.
     */
    public static volatile ToDoubleFunction<String> aspectLookup;

    public HologramImage {
        width = Mth.clamp(finite(width, 1.0F), MIN_SIZE, MAX_SIZE);
        height = finite(height, 0.0F) <= 0.0F ? 0.0F : Mth.clamp(height, MIN_SIZE, MAX_SIZE);
        billboard = billboard == null ? Billboard.FIXED : billboard;
        playback = playback == null ? Playback.LOOP : playback;
        speed = Mth.clamp(finite(speed, 1.0F), -8.0F, 8.0F);
        start = Mth.clamp(finite(start, 0.0F), 0.0F, 600.0F);
    }

    private static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    public boolean keepAspect() {
        return this.height <= 0.0F;
    }

    /** The drawn height: the set one, or the width times the asset's aspect ratio (square while unknown). */
    public float heightFor(String hash) {
        if (!keepAspect())
            return this.height;
        ToDoubleFunction<String> lookup = aspectLookup;
        double aspect = lookup == null ? Double.NaN : lookup.applyAsDouble(hash);
        if (!Double.isFinite(aspect) || aspect <= 0.0)
            aspect = 1.0;
        return Mth.clamp((float) (this.width * aspect), MIN_SIZE, MAX_SIZE);
    }

    /**
     * Where in the asset to show at hologram clock {@code clock} (seconds) for an animation {@code duration}
     * seconds long. LOOP is left unwrapped: the asset store wraps it, and does so exactly on frame boundaries.
     */
    public double assetTime(double clock, double duration) {
        double raw = this.start + clock * this.speed;
        if (!(duration > 0.0) || !Double.isFinite(raw))
            return 0.0;
        double last = Math.max(0.0, duration - 1.0E-3);
        return switch (this.playback) {
            case LOOP -> ((raw % duration) + duration) % duration;
            case ONCE -> Math.max(0.0, Math.min(last, raw));
            case PINGPONG -> {
                double period = duration * 2.0;
                double p = ((raw % period) + period) % period;
                yield Math.min(last, p < duration ? p : period - p);
            }
        };
    }

    public static HologramImage of(Map<String, String> props) {
        return new HologramImage(
                parse(props.get(WIDTH), 1.0F),
                parse(props.get(HEIGHT), 0.0F),
                enumOf(Billboard.class, props.get(BILLBOARD), Billboard.FIXED),
                !"1".equals(props.get(DOUBLE_SIDED)),
                enumOf(Playback.class, props.get(PLAYBACK), Playback.LOOP),
                parse(props.get(SPEED), 1.0F),
                parse(props.get(START), 0.0F));
    }

    /** Only the keys that differ from the defaults, so a plain image layer saves almost nothing extra. */
    public Map<String, String> toProps() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put(WIDTH, format(this.width));
        if (!keepAspect())
            out.put(HEIGHT, format(this.height));
        if (this.billboard != Billboard.FIXED)
            out.put(BILLBOARD, this.billboard.name());
        if (!this.doubleSided)
            out.put(DOUBLE_SIDED, "1");
        if (this.playback != Playback.LOOP)
            out.put(PLAYBACK, this.playback.name());
        if (this.speed != 1.0F)
            out.put(SPEED, format(this.speed));
        if (this.start != 0.0F)
            out.put(START, format(this.start));
        return out;
    }

    /** Untrusted props in, clamped props out; unknown keys are dropped. */
    public static Map<String, String> normalize(Map<String, String> props) {
        return of(props).toProps();
    }

    public HologramImage withSize(float width, float height) {
        return new HologramImage(width, height, this.billboard, this.doubleSided, this.playback, this.speed, this.start);
    }

    public HologramImage withBillboard(Billboard billboard) {
        return new HologramImage(this.width, this.height, billboard, this.doubleSided, this.playback, this.speed, this.start);
    }

    public HologramImage withDoubleSided(boolean doubleSided) {
        return new HologramImage(this.width, this.height, this.billboard, doubleSided, this.playback, this.speed, this.start);
    }

    public HologramImage withPlayback(Playback playback, float speed, float start) {
        return new HologramImage(this.width, this.height, this.billboard, this.doubleSided, playback, speed, start);
    }

    private static float parse(String raw, float fallback) {
        if (raw == null)
            return fallback;
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback) {
        if (raw == null)
            return fallback;
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static String format(float value) {
        // Rounded to 1/1000: enough for blocks and seconds, and keeps the props (and NBT) short and stable.
        float rounded = Math.round(value * 1000.0F) / 1000.0F;
        return rounded == (int) rounded ? Integer.toString((int) rounded) : Float.toString(rounded);
    }
}
