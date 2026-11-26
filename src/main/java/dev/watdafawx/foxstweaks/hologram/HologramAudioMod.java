package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One audio modulation: "add {@code amount} x (this signal of this source) to that value".
 *
 * <p>Where a modulation lives decides what it drives. On a {@link HologramEffect} it drives one of that effect's
 * own parameters ({@link Param#isEffectParam()}); on a {@link HologramTimeline} it is wrapped in a
 * {@link HologramTimeline.AudioLink} that names a root/group/layer, like a keyframe track, and drives a property
 * ({@link Param#isPropParam()}) or the timeline's playback rate ({@link Param#RATE}). Several modulations on one
 * value simply add up.
 *
 * <p>Everything here is data: the signals come from the client ({@code client/audio/*}), which also owns the
 * smoothing state, keyed by the identity of this record (the {@code with*} copies of an effect share its list, so
 * the state survives the per-frame copies the trigger and timeline code make).
 *
 * <p>{@code amount} is in the target's own unit (blocks, degrees, Hz, strength, scale factor, hue turns, playback
 * rate). {@code threshold} above 0 turns the signal into a step: 1 while the smoothed signal is at or above it, else
 * 0, which is what "flash on the beat" needs. {@code min}/{@code max} clamp the final summed value; NaN means no
 * clamp beyond the target's own legal range.
 */
public record HologramAudioMod(Param param, Source source, Signal signal, float amount, float sensitivity,
                               float attack, float release, float threshold, float min, float max) {

    /** Hard ceiling per value, so a hand-written tag cannot allocate without bound; the real caps are in ServerConfig. */
    public static final int HARD_MAX_PER_VALUE = 8;

    public enum Source {
        /** Whatever is playing: jukebox and music, plus system audio when the player switched it on. */
        AUTO("Auto"),
        /** Records/jukebox/note blocks slider: every sound in that category, weighted by distance. */
        JUKEBOX("Jukebox"),
        /** The music slider (menu and in-game music, no distance). */
        MUSIC("Music"),
        /** PC output or a microphone, captured locally. Off unless the player switched it on. */
        SYSTEM("System");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public Source next() {
            Source[] all = values();
            return all[(this.ordinal() + 1) % all.length];
        }
    }

    public enum Signal {
        LEVEL("Level"),
        BASS("Bass"),
        MID("Mid"),
        HIGH("High"),
        BEAT("Beat");

        private final String label;

        Signal(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public Signal next() {
            Signal[] all = values();
            return all[(this.ordinal() + 1) % all.length];
        }
    }

    public enum Param {
        // ---- on an effect ----
        STRENGTH("Strength"),
        SPEED("Speed"),
        PHASE("Offset"),
        /** Turns the effect's colour round the hue wheel; amount 1 = one full turn. */
        HUE("Hue"),
        // ---- on a timeline link (root / group / layer) ----
        OFFSET_X("X"),
        OFFSET_Y("Y"),
        OFFSET_Z("Z"),
        ROT_X("Pitch"),
        ROT_Y("Yaw"),
        ROT_Z("Roll"),
        SCALE("Scale"),
        /** 0..1; under 0.5 the target is hidden. */
        VISIBLE("Visible"),
        /** Brightness multiply of the target's tint, 0..1 (vertex colours cannot go brighter than the model). */
        BRIGHT("Brightness"),
        /** The timeline's playback rate (root only); 1 = normal speed. */
        RATE("Playback rate");

        private final String label;

        Param(String label) {
            this.label = label;
        }

        public String label() {
            return this.label;
        }

        public boolean isEffectParam() {
            return this.ordinal() <= HUE.ordinal();
        }

        public boolean isPropParam() {
            return !isEffectParam();
        }

        /**
         * Positive amounts on these start from "off" and reach the base value when loud: a "visible with the
         * music" or "glow with the music" modulation would otherwise be stuck at full at silence.
         */
        public boolean baselineFromAmount() {
            return this == VISIBLE || this == BRIGHT;
        }

        /** The largest |amount| accepted; also the slider range in the editors. */
        public float maxAmount() {
            return switch (this) {
                case STRENGTH -> 720.0F;
                case SPEED -> 20.0F;
                case PHASE, HUE, VISIBLE, BRIGHT -> 1.0F;
                case OFFSET_X, OFFSET_Y, OFFSET_Z -> 8.0F;
                case ROT_X, ROT_Y, ROT_Z -> 360.0F;
                case SCALE -> 4.0F;
                case RATE -> 4.0F;
            };
        }

        /** Natural editor step (wheel / drag) for the amount. */
        public float amountStep() {
            return switch (this) {
                case STRENGTH -> 0.05F;
                case SPEED, PHASE, HUE, VISIBLE, BRIGHT, SCALE, RATE -> 0.05F;
                case OFFSET_X, OFFSET_Y, OFFSET_Z -> 0.05F;
                case ROT_X, ROT_Y, ROT_Z -> 5.0F;
            };
        }

        public static List<Param> effectParams() {
            return List.of(STRENGTH, SPEED, PHASE, HUE);
        }

        public static List<Param> propParams() {
            return List.of(OFFSET_X, OFFSET_Y, OFFSET_Z, ROT_X, ROT_Y, ROT_Z, SCALE, VISIBLE, BRIGHT);
        }
    }

    public HologramAudioMod {
        param = param == null ? Param.STRENGTH : param;
        source = source == null ? Source.AUTO : source;
        signal = signal == null ? Signal.LEVEL : signal;
        float lim = param.maxAmount();
        amount = Mth.clamp(finite(amount, 0.0F), -lim, lim);
        sensitivity = Mth.clamp(finite(sensitivity, 1.0F), 0.1F, 8.0F);
        attack = Mth.clamp(finite(attack, 0.02F), 0.0F, 2.0F);
        release = Mth.clamp(finite(release, 0.2F), 0.0F, 4.0F);
        threshold = Mth.clamp(finite(threshold, 0.0F), 0.0F, 1.0F);
        // NaN is the "no clamp" marker, so only infinities are replaced.
        min = Float.isInfinite(min) ? Float.NaN : min;
        max = Float.isInfinite(max) ? Float.NaN : max;
        if (!Float.isNaN(min) && !Float.isNaN(max) && min > max) {
            float t = min;
            min = max;
            max = t;
        }
    }

    /** A new modulation with the defaults the editors start from. */
    public static HologramAudioMod of(Param param, Signal signal, float amount) {
        return new HologramAudioMod(param, Source.AUTO, signal, amount, 1.0F,
                signal == Signal.BEAT ? 0.0F : 0.03F, signal == Signal.BEAT ? 0.15F : 0.2F, 0.0F, Float.NaN, Float.NaN);
    }

    /** The amount a fresh modulation of {@code param} gets: visible, not violent. */
    public static float defaultAmount(Param param, float base) {
        return switch (param) {
            case STRENGTH -> Math.abs(base) > 1.0F ? Math.abs(base) : 0.8F;
            case SPEED -> 1.0F;
            case PHASE -> 0.25F;
            case HUE -> 0.3F;
            case OFFSET_X, OFFSET_Y, OFFSET_Z -> 0.3F;
            case ROT_X, ROT_Y, ROT_Z -> 20.0F;
            case SCALE -> 0.25F;
            case VISIBLE -> 1.0F;
            case BRIGHT -> 0.7F;
            case RATE -> 1.0F;
        };
    }

    public HologramAudioMod withSource(Source source) {
        return new HologramAudioMod(this.param, source, this.signal, this.amount, this.sensitivity, this.attack,
                this.release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withSignal(Signal signal) {
        return new HologramAudioMod(this.param, this.source, signal, this.amount, this.sensitivity, this.attack,
                this.release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withAmount(float amount) {
        return new HologramAudioMod(this.param, this.source, this.signal, amount, this.sensitivity, this.attack,
                this.release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withSensitivity(float sensitivity) {
        return new HologramAudioMod(this.param, this.source, this.signal, this.amount, sensitivity, this.attack,
                this.release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withAttack(float attack) {
        return new HologramAudioMod(this.param, this.source, this.signal, this.amount, this.sensitivity, attack,
                this.release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withRelease(float release) {
        return new HologramAudioMod(this.param, this.source, this.signal, this.amount, this.sensitivity, this.attack,
                release, this.threshold, this.min, this.max);
    }

    public HologramAudioMod withThreshold(float threshold) {
        return new HologramAudioMod(this.param, this.source, this.signal, this.amount, this.sensitivity, this.attack,
                this.release, threshold, this.min, this.max);
    }

    public HologramAudioMod withRange(float min, float max) {
        return new HologramAudioMod(this.param, this.source, this.signal, this.amount, this.sensitivity, this.attack,
                this.release, this.threshold, min, max);
    }

    public HologramAudioMod withParam(Param param) {
        return new HologramAudioMod(param, this.source, this.signal, this.amount, this.sensitivity, this.attack,
                this.release, this.threshold, this.min, this.max);
    }

    public boolean hasRange() {
        return !Float.isNaN(this.min) || !Float.isNaN(this.max);
    }

    /**
     * One-click looks for a layer, group or the whole hologram. Applying one replaces the target's modulations of the
     * params it uses and leaves the rest alone, so applying it twice changes nothing and two presets can be combined.
     */
    public enum Preset {
        PULSE_BASS("Pulse to the bass", "Grows a little on every bass hit"),
        FLASH_BEAT("Flash on the beat", "Dim between beats, full brightness on each beat"),
        DANCE("Dance", "Hops on the beat and sways with the mids"),
        GLOW("Glow with the music", "Brighter the louder it gets, dark in silence");

        private final String label;
        private final String hint;

        Preset(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }

        public String label() {
            return this.label;
        }

        public String hint() {
            return this.hint;
        }

        public List<HologramAudioMod> mods() {
            return switch (this) {
                case PULSE_BASS -> List.of(new HologramAudioMod(Param.SCALE, Source.AUTO, Signal.BASS, 0.25F, 1.2F,
                        0.02F, 0.18F, 0.0F, Float.NaN, Float.NaN));
                case FLASH_BEAT -> List.of(new HologramAudioMod(Param.BRIGHT, Source.AUTO, Signal.BEAT, 0.6F, 1.0F,
                        0.0F, 0.12F, 0.5F, Float.NaN, Float.NaN));
                case DANCE -> List.of(
                        new HologramAudioMod(Param.OFFSET_Y, Source.AUTO, Signal.BEAT, 0.25F, 1.0F, 0.0F, 0.2F, 0.0F,
                                Float.NaN, Float.NaN),
                        new HologramAudioMod(Param.ROT_Y, Source.AUTO, Signal.MID, 15.0F, 1.0F, 0.08F, 0.35F, 0.0F,
                                Float.NaN, Float.NaN));
                case GLOW -> List.of(new HologramAudioMod(Param.BRIGHT, Source.AUTO, Signal.LEVEL, 0.7F, 1.0F, 0.05F,
                        0.4F, 0.0F, Float.NaN, Float.NaN));
            };
        }

        /** {@code tl} may be null: an audio-only timeline is made for it. */
        public HologramTimeline applyTo(@javax.annotation.Nullable HologramTimeline tl,
                                        HologramTimeline.TargetKind target, String id) {
            HologramTimeline out = tl == null ? HologramTimeline.empty(4.0F) : tl;
            List<HologramAudioMod> all = mods();
            java.util.EnumSet<Param> params = java.util.EnumSet.noneOf(Param.class);
            for (HologramAudioMod m : all)
                params.add(m.param());
            for (Param p : params) {
                List<HologramAudioMod> mine = new java.util.ArrayList<>();
                for (HologramAudioMod m : all)
                    if (m.param() == p)
                        mine.add(m);
                out = out.withAudioFor(target, id, p, mine);
            }
            return out;
        }
    }

    /** "Bass +0.25 (Jukebox)", for chips and badges. */
    public String describe() {
        String amt = Math.abs(this.amount) >= 10.0F
                ? String.format(Locale.ROOT, "%+.0f", this.amount)
                : String.format(Locale.ROOT, "%+.2f", this.amount);
        return this.signal.label() + " " + amt + (this.threshold > 0.0F ? " step" : "")
                + (this.source == Source.AUTO ? "" : " (" + this.source.label() + ")");
    }

    // ---------------------------------------------------------------- NBT

    /** Only non-default fields are written, so an old reader skipping the list loses nothing it understood. */
    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("p", this.param.name());
        if (this.source != Source.AUTO)
            tag.putString("src", this.source.name());
        tag.putString("sig", this.signal.name());
        tag.putFloat("amt", this.amount);
        if (this.sensitivity != 1.0F)
            tag.putFloat("sens", this.sensitivity);
        tag.putFloat("atk", this.attack);
        tag.putFloat("rel", this.release);
        if (this.threshold > 0.0F)
            tag.putFloat("thr", this.threshold);
        if (!Float.isNaN(this.min))
            tag.putFloat("min", this.min);
        if (!Float.isNaN(this.max))
            tag.putFloat("max", this.max);
        return tag;
    }

    @javax.annotation.Nullable
    public static HologramAudioMod fromTag(CompoundTag tag) {
        Param param = enumOf(Param.class, tag.getString("p"));
        Signal signal = enumOf(Signal.class, tag.getString("sig"));
        if (param == null || signal == null)
            return null;
        Source source = enumOf(Source.class, tag.getString("src"));
        return new HologramAudioMod(param, source == null ? Source.AUTO : source, signal, tag.getFloat("amt"),
                tag.contains("sens") ? tag.getFloat("sens") : 1.0F,
                tag.contains("atk") ? tag.getFloat("atk") : 0.03F,
                tag.contains("rel") ? tag.getFloat("rel") : 0.2F,
                tag.getFloat("thr"),
                tag.contains("min") ? tag.getFloat("min") : Float.NaN,
                tag.contains("max") ? tag.getFloat("max") : Float.NaN);
    }

    public static ListTag listToTag(List<HologramAudioMod> mods) {
        ListTag list = new ListTag();
        for (HologramAudioMod mod : mods)
            list.add(mod.toTag());
        return list;
    }

    /** Reads at most {@code limit} entries; effect lists keep only effect parameters and vice versa. */
    public static List<HologramAudioMod> listFromTag(ListTag list, int limit, boolean effectParams) {
        List<HologramAudioMod> out = new ArrayList<>(Math.min(list.size(), limit));
        for (int i = 0; i < list.size() && out.size() < limit; i++) {
            if (list.get(i).getId() != Tag.TAG_COMPOUND)
                continue;
            HologramAudioMod mod = fromTag(list.getCompound(i));
            if (mod != null && mod.param().isEffectParam() == effectParams)
                out.add(mod);
        }
        return List.copyOf(out);
    }

    /** Keeps at most {@link #HARD_MAX_PER_VALUE} per parameter and only effect parameters. */
    static List<HologramAudioMod> sanitiseEffectMods(List<HologramAudioMod> mods) {
        if (mods == null || mods.isEmpty())
            return List.of();
        int[] count = new int[Param.values().length];
        boolean same = true;
        for (HologramAudioMod mod : mods) {
            if (mod == null || !mod.param().isEffectParam() || ++count[mod.param().ordinal()] > HARD_MAX_PER_VALUE) {
                same = false;
                break;
            }
        }
        if (same)
            return List.copyOf(mods);
        java.util.Arrays.fill(count, 0);
        List<HologramAudioMod> out = new ArrayList<>();
        for (HologramAudioMod mod : mods)
            if (mod != null && mod.param().isEffectParam() && ++count[mod.param().ordinal()] <= HARD_MAX_PER_VALUE)
                out.add(mod);
        return List.copyOf(out);
    }

    /**
     * Null when every audio modulation in {@code spec} fits the server's caps, otherwise why not. Checked on every
     * save path next to the timeline caps; the records only enforce {@link #HARD_MAX_PER_VALUE}.
     */
    @javax.annotation.Nullable
    public static String checkCaps(HologramSpec spec, int perValue, int perHologram) {
        int total = 0;
        for (HologramGroup group : spec.groups()) {
            String p = effectCaps(group.effects(), perValue, "group " + group.id());
            if (p != null)
                return p;
            total += count(group.effects());
        }
        for (HologramLayer layer : spec.layers()) {
            String p = effectCaps(layer.effects(), perValue, "layer " + layer.uid());
            if (p != null)
                return p;
            total += count(layer.effects());
        }
        HologramTimeline tl = spec.timeline();
        if (tl != null) {
            List<HologramTimeline.AudioLink> links = tl.audio();
            for (int i = 0; i < links.size(); i++) {
                HologramTimeline.AudioLink a = links.get(i);
                int same = 0;
                for (HologramTimeline.AudioLink b : links)
                    if (b.matches(a.target(), a.id()) && b.mod().param() == a.mod().param())
                        same++;
                if (same > perValue)
                    return a.target().name().toLowerCase(Locale.ROOT) + (a.id().isEmpty() ? "" : " " + a.id()) + " "
                            + a.mod().param().label() + " has " + same + " audio modulations (max " + perValue + ")";
            }
            total += links.size();
        }
        if (total > perHologram)
            return "hologram has " + total + " audio modulations (max " + perHologram + ")";
        return null;
    }

    /** Number of modulations on the effects, for caps and badges. */
    public static int count(List<HologramEffect> effects) {
        int n = 0;
        for (HologramEffect e : effects)
            n += e.audio().size();
        return n;
    }

    @javax.annotation.Nullable
    private static String effectCaps(List<HologramEffect> effects, int perValue, String where) {
        for (HologramEffect e : effects) {
            if (e.audio().isEmpty())
                continue;
            int[] count = new int[Param.values().length];
            for (HologramAudioMod m : e.audio()) {
                if (++count[m.param().ordinal()] > perValue)
                    return where + " " + e.label() + " " + m.param().label() + " has more than " + perValue
                            + " audio modulations";
            }
        }
        return null;
    }

    @javax.annotation.Nullable
    private static <E extends Enum<E>> E enumOf(Class<E> type, String name) {
        if (name == null || name.isEmpty())
            return null;
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static float finite(float v, float def) {
        return Float.isFinite(v) ? v : def;
    }

    // ---------------------------------------------------------------- equality: NaN-safe

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        return o instanceof HologramAudioMod m && m.param == this.param && m.source == this.source
                && m.signal == this.signal && Float.compare(m.amount, this.amount) == 0
                && Float.compare(m.sensitivity, this.sensitivity) == 0 && Float.compare(m.attack, this.attack) == 0
                && Float.compare(m.release, this.release) == 0 && Float.compare(m.threshold, this.threshold) == 0
                && Float.compare(m.min, this.min) == 0 && Float.compare(m.max, this.max) == 0;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(this.param, this.source, this.signal, this.amount, this.sensitivity,
                this.attack, this.release, this.threshold, this.min, this.max);
    }
}
