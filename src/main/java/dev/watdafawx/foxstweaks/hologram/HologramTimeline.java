package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * A hologram's keyframe timeline: a fixed-length clip of property tracks, the After Effects model kept small.
 *
 * <p>Each {@link Track} animates one property of one target (the root, a group by its id, or a layer by its
 * {@link HologramLayer#uid()}). The timeline value <b>replaces the target's static field</b> (offset, rotation,
 * scale, visibility) or adds a colour multiply (tint) or replaces one effect's strength; every motion/FX effect
 * still runs on top, so a keyed position plus a SPIN reads as "moves along the path while spinning".
 *
 * <p>Effect strength tracks name the effect by its <b>index</b> in the target's effect list. Effects have no
 * stable id of their own; the web editor shifts or drops those tracks when it removes or reorders an effect, and
 * an index past the end of the list is simply inert.
 *
 * <p>A key's easing shapes the segment that <b>leaves</b> it (towards the next key), like AE's outgoing
 * interpolation: LINEAR, HOLD (step), the three CSS eases, or CUBIC with an explicit {@code cubic-bezier(x1, y1,
 * x2, y2)} in segment-normalised space (x is time 0..1 and clamped to it, so a curve can never run backwards; y
 * may overshoot). The last key's easing is unused.
 *
 * <p>The record-like parts are immutable. The instance also carries render-thread caches (target binding, track
 * values) for {@link HologramTimelineEval}; they are not part of equality.
 */
public final class HologramTimeline {

    public enum Mode {
        LOOP, PINGPONG, ONCE
    }

    public enum TargetKind {
        ROOT, GROUP, LAYER
    }

    public enum Prop {
        OFFSET_X, OFFSET_Y, OFFSET_Z,
        ROT_X, ROT_Y, ROT_Z,
        SCALE,
        VISIBLE,
        TINT,
        EFFECT;

        public float min() {
            return switch (this) {
                case OFFSET_X, OFFSET_Y, OFFSET_Z -> -16.0F;
                case ROT_X, ROT_Y, ROT_Z -> -3600.0F;
                case SCALE -> 0.05F;
                case VISIBLE, TINT -> 0.0F;
                case EFFECT -> -720.0F;
            };
        }

        public float max() {
            return switch (this) {
                case OFFSET_X, OFFSET_Y, OFFSET_Z -> 16.0F;
                case ROT_X, ROT_Y, ROT_Z -> 3600.0F;
                case SCALE -> 8.0F;
                case VISIBLE -> 1.0F;
                case TINT -> 0xFFFFFF;
                case EFFECT -> 720.0F;
            };
        }

        /** Interpolates as a step whatever the key's easing says. */
        public boolean stepped() {
            return this == VISIBLE;
        }
    }

    public enum Ease {
        LINEAR, HOLD, EASE_IN, EASE_OUT, EASE_IN_OUT, CUBIC
    }

    public static final float MIN_LENGTH = 0.5F;
    public static final float MAX_LENGTH = 120.0F;
    /** Hard ceilings so a hand-written tag cannot allocate without bound; the real caps are in ServerConfig. */
    public static final int HARD_MAX_TRACKS = 256;
    public static final int HARD_MAX_KEYS = 8192;

    /** One keyframe. {@code value} holds a packed 0xRRGGBB (exact in a float) for TINT tracks. */
    public record Key(float time, float value, Ease ease, float x1, float y1, float x2, float y2) {
        public Key {
            time = finite(time, 0.0F);
            value = finite(value, 0.0F);
            ease = ease == null ? Ease.LINEAR : ease;
            x1 = Mth.clamp(finite(x1, 0.33F), 0.0F, 1.0F);
            x2 = Mth.clamp(finite(x2, 0.67F), 0.0F, 1.0F);
            y1 = Mth.clamp(finite(y1, 0.33F), -2.0F, 3.0F);
            y2 = Mth.clamp(finite(y2, 0.67F), -2.0F, 3.0F);
        }

        public static Key linear(float time, float value) {
            return new Key(time, value, Ease.LINEAR, 0.33F, 0.33F, 0.67F, 0.67F);
        }

        public static Key of(float time, float value, Ease ease) {
            return new Key(time, value, ease, 0.33F, 0.33F, 0.67F, 0.67F);
        }

        public static Key cubic(float time, float value, float x1, float y1, float x2, float y2) {
            return new Key(time, value, Ease.CUBIC, x1, y1, x2, y2);
        }

        Key clampTo(Prop prop, float length) {
            float v = Mth.clamp(this.value, prop.min(), prop.max());
            if (prop == Prop.TINT)
                v = Math.round(v) & 0xFFFFFF;
            else if (prop == Prop.VISIBLE)
                v = v >= 0.5F ? 1.0F : 0.0F;
            // Snap to 1/1000 s so a time survives JSON and NBT round trips exactly.
            float t = Math.round(Mth.clamp(this.time, 0.0F, length) * 1000.0F) / 1000.0F;
            if (t == this.time && v == this.value)
                return this;
            return new Key(t, v, this.ease, this.x1, this.y1, this.x2, this.y2);
        }
    }

    /**
     * One animated property. {@code effect} is the effect index for {@link Prop#EFFECT} and 0 otherwise. Keys are
     * sorted by time with no two at the same time (the later one in the input wins).
     */
    public record Track(TargetKind target, String id, Prop prop, int effect, List<Key> keys) {
        public Track {
            target = target == null ? TargetKind.LAYER : target;
            id = target == TargetKind.ROOT || id == null ? "" : id;
            prop = prop == null ? Prop.OFFSET_Y : prop;
            effect = prop == Prop.EFFECT ? Mth.clamp(effect, 0, HologramSpec.MAX_EFFECTS - 1) : 0;
            keys = List.copyOf(keys == null ? List.of() : keys);
        }

        Track normalised(float length) {
            List<Key> sorted = new ArrayList<>(Math.min(this.keys.size(), HARD_MAX_KEYS));
            for (int i = 0; i < this.keys.size() && i < HARD_MAX_KEYS; i++)
                sorted.add(this.keys.get(i).clampTo(this.prop, length));
            // Stable sort, then keep the last key at each time.
            sorted.sort(Comparator.comparingDouble(Key::time));
            List<Key> out = new ArrayList<>(sorted.size());
            for (Key k : sorted) {
                if (!out.isEmpty() && out.get(out.size() - 1).time() == k.time())
                    out.set(out.size() - 1, k);
                else
                    out.add(k);
            }
            boolean same = out.size() == this.keys.size();
            for (int i = 0; same && i < out.size(); i++)
                same = out.get(i) == this.keys.get(i);
            return same ? this : new Track(this.target, this.id, this.prop, this.effect, out);
        }

        public Track withKeys(List<Key> keys) {
            return new Track(this.target, this.id, this.prop, this.effect, keys);
        }

        /** "layer:l2 OFFSET_Y", for messages. */
        public String describe() {
            return this.target.name().toLowerCase(Locale.ROOT) + (this.id.isEmpty() ? "" : ":" + this.id)
                    + " " + this.prop.name() + (this.prop == Prop.EFFECT ? "#" + this.effect : "");
        }
    }

    private final float length;
    private final Mode mode;
    private final HologramEffect.Trigger start;
    private final List<Track> tracks;

    // ---- render-thread caches, see HologramTimelineEval ----
    @Nullable
    Object boundLayers;
    @Nullable
    Object boundGroups;
    int[] rootSlots;
    int[][] layerSlots;
    int[][] groupSlots;
    float[] values;
    float cullPad = -1.0F;

    public HologramTimeline(float length, Mode mode, HologramEffect.Trigger start, List<Track> tracks) {
        this.length = Mth.clamp(finite(length, 4.0F), MIN_LENGTH, MAX_LENGTH);
        this.mode = mode == null ? Mode.LOOP : mode;
        this.start = start == null ? HologramEffect.Trigger.ALWAYS : start;
        List<Track> list = new ArrayList<>(Math.min(tracks.size(), HARD_MAX_TRACKS));
        int keyBudget = HARD_MAX_KEYS;
        for (int i = 0; i < tracks.size() && list.size() < HARD_MAX_TRACKS; i++) {
            Track t = tracks.get(i);
            if (t == null)
                continue;
            if (t.target() == TargetKind.ROOT && t.prop() == Prop.EFFECT)
                continue; // the root has no effects
            Track n = t.normalised(this.length);
            if (n.keys().size() > keyBudget)
                n = n.withKeys(n.keys().subList(0, keyBudget));
            keyBudget -= n.keys().size();
            list.add(n);
        }
        this.tracks = List.copyOf(list);
    }

    public static HologramTimeline empty(float length) {
        return new HologramTimeline(length, Mode.LOOP, HologramEffect.Trigger.ALWAYS, List.of());
    }

    public float length() {
        return this.length;
    }

    public Mode mode() {
        return this.mode;
    }

    public HologramEffect.Trigger start() {
        return this.start;
    }

    public List<Track> tracks() {
        return this.tracks;
    }

    public int keyCount() {
        int n = 0;
        for (Track t : this.tracks)
            n += t.keys().size();
        return n;
    }

    public HologramTimeline withTracks(List<Track> tracks) {
        return new HologramTimeline(this.length, this.mode, this.start, tracks);
    }

    /** Drops tracks whose group or layer is gone; returns this when nothing changed. */
    HologramTimeline boundTo(List<HologramGroup> groups, List<HologramLayer> layers) {
        List<Track> kept = null;
        for (int i = 0; i < this.tracks.size(); i++) {
            Track t = this.tracks.get(i);
            boolean alive = switch (t.target()) {
                case ROOT -> true;
                case GROUP -> groups.stream().anyMatch(g -> g.id().equals(t.id()));
                case LAYER -> layers.stream().anyMatch(l -> l.uid().equals(t.id()));
            };
            if (!alive && kept == null) {
                kept = new ArrayList<>(this.tracks.subList(0, i));
            } else if (alive && kept != null) {
                kept.add(t);
            }
        }
        return kept == null ? this : withTracks(kept);
    }

    /**
     * Null when the timeline fits the server's caps, otherwise why not. Checked on every save path (editor packet,
     * web JSON); the constructor only enforces the hard ceilings.
     */
    @Nullable
    public String checkCaps(int maxTracks, int maxKeys) {
        if (this.tracks.size() > maxTracks)
            return "timeline has " + this.tracks.size() + " tracks (max " + maxTracks + ")";
        int keys = keyCount();
        if (keys > maxKeys)
            return "timeline has " + keys + " keyframes (max " + maxKeys + ")";
        return null;
    }

    /** "4.0 s loop, 3 tracks" for the in-game editors. */
    public String summary() {
        String m = switch (this.mode) {
            case LOOP -> "loop";
            case PINGPONG -> "ping-pong";
            case ONCE -> "once";
        };
        String len = String.format(Locale.ROOT, "%.1f s", this.length);
        String trig = this.start == HologramEffect.Trigger.ALWAYS ? "" : ", on " + this.start.label().toLowerCase(Locale.ROOT);
        return len + " " + m + trig + ", " + this.tracks.size() + (this.tracks.size() == 1 ? " track" : " tracks");
    }

    // ---------------------------------------------------------------- NBT

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("len", this.length);
        if (this.mode != Mode.LOOP)
            tag.putString("mode", this.mode.name());
        if (this.start != HologramEffect.Trigger.ALWAYS)
            tag.putString("start", this.start.name());
        ListTag list = new ListTag();
        for (Track t : this.tracks) {
            CompoundTag tt = new CompoundTag();
            tt.putString("on", t.target().name());
            if (!t.id().isEmpty())
                tt.putString("id", t.id());
            tt.putString("p", t.prop().name());
            if (t.prop() == Prop.EFFECT)
                tt.putByte("fx", (byte) t.effect());
            ListTag keys = new ListTag();
            for (Key k : t.keys()) {
                CompoundTag kt = new CompoundTag();
                kt.putFloat("t", k.time());
                if (t.prop() == Prop.TINT)
                    kt.putInt("c", (int) k.value());
                else
                    kt.putFloat("v", k.value());
                if (k.ease() != Ease.LINEAR)
                    kt.putString("e", k.ease().name());
                if (k.ease() == Ease.CUBIC) {
                    kt.putFloat("x1", k.x1());
                    kt.putFloat("y1", k.y1());
                    kt.putFloat("x2", k.x2());
                    kt.putFloat("y2", k.y2());
                }
                keys.add(kt);
            }
            tt.put("k", keys);
            list.add(tt);
        }
        tag.put("tracks", list);
        return tag;
    }

    @Nullable
    public static HologramTimeline fromTag(CompoundTag tag) {
        if (!tag.contains("len"))
            return null;
        Mode mode = enumOf(Mode.class, tag.getString("mode"), Mode.LOOP);
        HologramEffect.Trigger start = enumOf(HologramEffect.Trigger.class, tag.getString("start"),
                HologramEffect.Trigger.ALWAYS);
        ListTag list = tag.getList("tracks", Tag.TAG_COMPOUND);
        List<Track> tracks = new ArrayList<>(Math.min(list.size(), HARD_MAX_TRACKS));
        for (int i = 0; i < list.size() && tracks.size() < HARD_MAX_TRACKS; i++) {
            CompoundTag tt = list.getCompound(i);
            TargetKind target = enumOf(TargetKind.class, tt.getString("on"), null);
            Prop prop = enumOf(Prop.class, tt.getString("p"), null);
            if (target == null || prop == null)
                continue;
            ListTag keysTag = tt.getList("k", Tag.TAG_COMPOUND);
            List<Key> keys = new ArrayList<>(Math.min(keysTag.size(), HARD_MAX_KEYS));
            for (int k = 0; k < keysTag.size() && k < HARD_MAX_KEYS; k++) {
                CompoundTag kt = keysTag.getCompound(k);
                float value = prop == Prop.TINT ? (kt.getInt("c") & 0xFFFFFF) : kt.getFloat("v");
                Ease ease = enumOf(Ease.class, kt.getString("e"), Ease.LINEAR);
                keys.add(ease == Ease.CUBIC
                        ? Key.cubic(kt.getFloat("t"), value, kt.getFloat("x1"), kt.getFloat("y1"),
                        kt.getFloat("x2"), kt.getFloat("y2"))
                        : Key.of(kt.getFloat("t"), value, ease));
            }
            tracks.add(new Track(target, tt.getString("id"), prop, tt.getByte("fx"), keys));
        }
        return new HologramTimeline(tag.getFloat("len"), mode, start, tracks);
    }

    @Nullable
    private static <E extends Enum<E>> E enumOf(Class<E> type, String name, @Nullable E def) {
        if (name == null || name.isEmpty())
            return def;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return def;
        }
    }

    private static float finite(float v, float def) {
        return Float.isFinite(v) ? v : def;
    }

    // ---------------------------------------------------------------- equality (data only)

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        return o instanceof HologramTimeline t && t.length == this.length && t.mode == this.mode
                && t.start == this.start && t.tracks.equals(this.tracks);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.length, this.mode, this.start, this.tracks);
    }

    @Override
    public String toString() {
        return "HologramTimeline[" + summary() + ", keys=" + keyCount() + "]";
    }

    /** Fills {@code a} with -1; used by the binding. */
    static int[] unbound(int n) {
        int[] a = new int[n];
        Arrays.fill(a, -1);
        return a;
    }
}
