package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Turns "play this animation only when ..." into a number from 0 to 1 per effect.
 *
 * <p>An effect with a trigger other than {@code ALWAYS} is run through {@link #resolve}: its strength is scaled by
 * the trigger's gate (eased, so nothing snaps), and it drops out of the list when the gate is shut. Everything
 * downstream (renderer, particles, entity poses) just sees an ordinary effect list, so every animation and FX
 * gets triggers for free.
 *
 * <p>Two kinds of trigger:
 * <ul>
 *   <li><b>Hold</b> (day, night, rain, thunder, jukebox, music): the gate follows a condition and is eased in and
 *   out. A spin keeps its angle while the gate is shut (a per-effect clock only advances while it is open), so it
 *   slows to a halt and picks up from there instead of snapping back.</li>
 *   <li><b>Pulse</b> (a mob died nearby): the gate opens for one cycle of the animation, restarted from the moment
 *   of the death, then fades.</li>
 * </ul>
 *
 * <p>Nothing here costs anything while no effect uses a trigger: {@link #resolve} returns the list it was given.
 * State lives per effect instance (identity), which is replaced whenever the hologram is edited or re-synced.
 */
public final class HologramTriggers {
    /** Blocks around the hologram a jukebox or a death has to be within to count. */
    public static final double RANGE = 24.0;

    private static final float RISE_SECONDS = 0.5F;
    private static final float FALL_SECONDS = 0.8F;
    private static final double FADE_OUT = 0.3;
    private static final int MAX_DEATHS = 16;

    private static final class State {
        float gate;
        double accum;
        double lastSeconds = -1.0;
        boolean init;
        /** Hologram-clock time of the death a pulse is playing from. */
        double pulseStart;
        long used;
    }

    private record Death(double seconds, double x, double y, double z) {
    }

    private static final Map<HologramEffect, State> STATES = new IdentityHashMap<>();
    private static final List<Death> DEATHS = new ArrayList<>();
    private static final Map<Integer, Long> SEEN_DEATHS = new java.util.HashMap<>();
    private static final Map<SoundInstance, Long> MUSIC = new IdentityHashMap<>();
    /** Record sounds (a jukebox's disc) and when they were first seen. */
    private static final Map<SoundInstance, Long> RECORDS = new IdentityHashMap<>();

    private static long frame;
    private static int seenRecords;
    private static String lastSource = "none";

    @org.jetbrains.annotations.Nullable
    private static Vec3 devRecord;

    /** Dev check: what the sound listener has seen. */
    public static String devSoundDebug() {
        return "records seen=" + seenRecords + " tracked=" + RECORDS.size() + " last=" + lastSource;
    }

    public static int devRecordsSeen() {
        return seenRecords;
    }

    /** Dev check: pretend a record is playing at {@code pos} (null stops it). The rig has no audio device. */
    public static void devRecord(@org.jetbrains.annotations.Nullable Vec3 pos) {
        devRecord = pos;
    }

    private static long lastPrune;
    private static long tick;
    private static long deathScanUntil;
    private static boolean devMusic;

    private HologramTriggers() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramTriggers::onTick);
        NeoForge.EVENT_BUS.addListener(HologramTriggers::onSound);
    }

    // ---------------------------------------------------------------- frame bookkeeping

    /** Called once per world pass; also forgets the state of effects that have not been drawn for a while. */
    public static void beginFrame() {
        frame++;
        long now = System.nanoTime();
        if (now - lastPrune > 5_000_000_000L) {
            lastPrune = now;
            Iterator<State> it = STATES.values().iterator();
            while (it.hasNext()) {
                if (now - it.next().used > 10_000_000_000L)
                    it.remove();
            }
        }
    }

    public static void clear() {
        STATES.clear();
        DEATHS.clear();
        SEEN_DEATHS.clear();
        MUSIC.clear();
        RECORDS.clear();
    }

    // ---------------------------------------------------------------- event sources

    private static void onSound(PlaySoundEvent event) {
        SoundInstance sound = event.getSound();
        if (sound == null)
            return;
        if (sound.getSource() == SoundSource.MUSIC)
            MUSIC.put(sound, System.nanoTime());
        else if (sound.getSource() == SoundSource.RECORDS) {
            RECORDS.put(sound, System.nanoTime());
            seenRecords++;
        }
        lastSource = sound.getSource() + " " + sound.getLocation();
    }

    private static void onTick(ClientTickEvent.Post event) {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            if (!DEATHS.isEmpty() || !MUSIC.isEmpty() || !RECORDS.isEmpty())
                clear();
            return;
        }

        if ((!MUSIC.isEmpty() || !RECORDS.isEmpty()) && tick % 10 == 0) {
            long now = System.nanoTime();
            // A sound is queued a tick or two before the engine reports it active, so give it a second.
            MUSIC.entrySet().removeIf(e -> now - e.getValue() > 1_000_000_000L
                    && !mc.getSoundManager().isActive(e.getKey()));
            RECORDS.entrySet().removeIf(e -> now - e.getValue() > 1_000_000_000L
                    && !mc.getSoundManager().isActive(e.getKey()));
        }

        if (tick > deathScanUntil)
            return;
        double now = HologramRenderer.animSeconds();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || living.deathTime < 1)
                continue;
            if (SEEN_DEATHS.putIfAbsent(living.getId(), tick) != null)
                continue;
            DEATHS.add(new Death(now, living.getX(), living.getY(0.5), living.getZ()));
            if (DEATHS.size() > MAX_DEATHS)
                DEATHS.remove(0);
        }
        if (tick % 200 == 0)
            SEEN_DEATHS.values().removeIf(t -> tick - t > 100);
        DEATHS.removeIf(d -> now - d.seconds > 60.0 || d.seconds > now);
    }

    // ---------------------------------------------------------------- dev hooks

    /** Dev check: pretend a mob died at {@code pos} just now. */
    public static void devDeath(Vec3 pos) {
        DEATHS.add(new Death(HologramRenderer.animSeconds(), pos.x, pos.y, pos.z));
    }

    /** Dev check: pretend music is (or is not) playing. */
    public static void devMusic(boolean playing) {
        devMusic = playing;
    }

    public static boolean musicPlaying() {
        return devMusic || !MUSIC.isEmpty();
    }

    // ---------------------------------------------------------------- resolving

    /**
     * The effect list as it should be drawn right now. Returns {@code effects} itself when none of them has a
     * trigger.
     */
    public static List<HologramEffect> resolve(List<HologramEffect> effects, HologramSpec spec, double seconds) {
        return resolve(effects, spec, seconds, null);
    }

    /**
     * As {@link #resolve(List, HologramSpec, double)}, with per-index strengths keyed by the hologram's timeline
     * ({@code NaN} = not keyed; {@code null} = none at all). Trigger state stays keyed on the original effect
     * instance, so a keyed strength does not reset a trigger's gate or a spin's angle.
     */
    public static List<HologramEffect> resolve(List<HologramEffect> effects, HologramSpec spec, double seconds,
                                               @org.jetbrains.annotations.Nullable float[] strengths) {
        if (strengths != null)
            return resolveKeyed(effects, spec, seconds, strengths);
        boolean any = false;
        for (int i = 0; i < effects.size(); i++) {
            if (effects.get(i).trigger() != HologramEffect.Trigger.ALWAYS) {
                any = true;
                break;
            }
        }
        if (!any)
            return effects;

        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
            return effects;
        Vec3 center = spec.box().getCenter();

        List<HologramEffect> out = new ArrayList<>(effects.size());
        for (HologramEffect effect : effects) {
            HologramEffect.Trigger trigger = effect.trigger();
            if (trigger == HologramEffect.Trigger.ALWAYS) {
                out.add(effect);
                continue;
            }

            State st = STATES.computeIfAbsent(effect, k -> new State());
            st.used = System.nanoTime();
            update(st, effect, level, center, seconds);

            float gate = st.gate;
            HologramEffect shown = effect;

            if (effect.type() == HologramEffect.Type.SPIN) {
                // Angle = seconds * amount + phase * 360, so feeding it the gate's own clock keeps the angle
                // continuous however the gate moves. Never dropped: a closed gate just holds the angle.
                double ph = ((st.accum - seconds) * effect.strength() + effect.phase() * 360.0) / 360.0;
                out.add(effect.withPhase((float) (ph - Math.floor(ph))));
                continue;
            }

            if (gate <= 0.002F)
                continue;

            if (trigger.isPulse() && effect.type().usesPhase()) {
                double ph = effect.phase() - st.pulseStart * effect.speed();
                shown = shown.withPhase((float) (ph - Math.floor(ph)));
            }
            out.add(gate >= 0.999F ? shown : shown.withStrength(effect.strength() * gate));
        }
        return out;
    }

    private static List<HologramEffect> resolveKeyed(List<HologramEffect> effects, HologramSpec spec, double seconds,
                                                     float[] strengths) {
        boolean triggers = false;
        for (int i = 0; i < effects.size(); i++)
            triggers |= effects.get(i).trigger() != HologramEffect.Trigger.ALWAYS;
        List<HologramEffect> keyed = new ArrayList<>(effects.size());
        for (int i = 0; i < effects.size(); i++) {
            HologramEffect e = effects.get(i);
            float v = i < strengths.length ? strengths[i] : Float.NaN;
            if (Float.isNaN(v)) {
                keyed.add(e);
                continue;
            }
            float min = e.type().isFx() ? 0.0F : e.type().minAmount();
            float max = e.type().isFx() ? 2.0F : e.type().maxAmount();
            keyed.add(e.withStrength(Mth.clamp(v, min, max)));
        }
        if (!triggers)
            return keyed;
        // Resolve against the originals (their identity holds the trigger state), then swap in keyed strengths.
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null)
            return keyed;
        Vec3 center = spec.box().getCenter();
        List<HologramEffect> out = new ArrayList<>(effects.size());
        for (int i = 0; i < effects.size(); i++) {
            HologramEffect original = effects.get(i);
            HologramEffect effect = keyed.get(i);
            if (original.trigger() == HologramEffect.Trigger.ALWAYS) {
                out.add(effect);
                continue;
            }
            State st = STATES.computeIfAbsent(original, k -> new State());
            st.used = System.nanoTime();
            update(st, original, level, center, seconds);
            if (effect.type() == HologramEffect.Type.SPIN) {
                double ph = ((st.accum - seconds) * effect.strength() + effect.phase() * 360.0) / 360.0;
                out.add(effect.withPhase((float) (ph - Math.floor(ph))));
                continue;
            }
            if (st.gate <= 0.002F)
                continue;
            HologramEffect shown = effect;
            if (effect.trigger().isPulse() && effect.type().usesPhase()) {
                double ph = effect.phase() - st.pulseStart * effect.speed();
                shown = shown.withPhase((float) (ph - Math.floor(ph)));
            }
            out.add(st.gate >= 0.999F ? shown : shown.withStrength(effect.strength() * st.gate));
        }
        return out;
    }

    /** 0..1 level of a hold trigger (day, rain, jukebox, ...) around {@code center}; for the timeline's start trigger. */
    public static float conditionLevel(HologramEffect.Trigger trigger, Level level, Vec3 center) {
        return holdTarget(null, trigger, level, center);
    }

    /**
     * Hologram-clock age in seconds of the latest death within {@link #RANGE} of {@code center}, or -1. Keeps the
     * death scan running while asked.
     */
    public static double latestDeathAge(Vec3 center) {
        deathScanUntil = tick + 40;
        double now = HologramRenderer.animSeconds();
        double best = -1.0;
        for (Death d : DEATHS) {
            double dx = d.x - center.x;
            double dy = d.y - center.y;
            double dz = d.z - center.z;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE)
                continue;
            double age = now - d.seconds;
            if (age >= 0.0 && (best < 0.0 || age < best))
                best = age;
        }
        return best;
    }

    private static void update(State st, HologramEffect effect, Level level, Vec3 center, double seconds) {
        if (seconds == st.lastSeconds)
            return;
        double dt = st.lastSeconds < 0.0 || seconds < st.lastSeconds ? 0.0 : Math.min(0.25, seconds - st.lastSeconds);
        st.lastSeconds = seconds;

        HologramEffect.Trigger trigger = effect.trigger();
        if (trigger.isPulse()) {
            deathScanUntil = tick + 40;
            st.gate = pulseGate(st, effect, center, seconds);
        } else {
            float target = holdTarget(st, trigger, level, center);
            if (!st.init)
                st.gate = target;
            else if (target > st.gate)
                st.gate = HologramAnim.approach(st.gate, target, RISE_SECONDS, dt);
            else
                st.gate = HologramAnim.approach(st.gate, target, FALL_SECONDS, dt);
        }
        st.init = true;
        st.accum += dt * st.gate;
    }

    private static float holdTarget(State st, HologramEffect.Trigger trigger, Level level, Vec3 center) {
        return switch (trigger) {
            case DAY -> dayness(level);
            case NIGHT -> 1.0F - dayness(level);
            case RAIN -> level.getRainLevel(1.0F);
            case THUNDER -> level.getThunderLevel(1.0F);
            case JUKEBOX -> jukeboxNear(center) ? 1.0F : 0.0F;
            case MUSIC -> musicPlaying() ? 1.0F : 0.0F;
            default -> 1.0F;
        };
    }

    /** 1 in daylight, 0 at night, easing through dusk and dawn; 1 in dimensions with a fixed time. */
    public static float dayness(Level level) {
        if (level.dimensionType().hasFixedTime())
            return 1.0F;
        float height = (float) Math.cos(level.getSunAngle(1.0F));
        return Mth.clamp((height + 0.15F) / 0.3F, 0.0F, 1.0F);
    }

    private static float pulseGate(State st, HologramEffect effect, Vec3 center, double seconds) {
        Death latest = null;
        for (Death d : DEATHS) {
            double dx = d.x - center.x;
            double dy = d.y - center.y;
            double dz = d.z - center.z;
            if (dx * dx + dy * dy + dz * dz > RANGE * RANGE)
                continue;
            if (latest == null || d.seconds > latest.seconds)
                latest = d;
        }
        if (latest == null)
            return 0.0F;

        double length = effect.type().usesSpeed() ? Mth.clamp(1.0 / effect.speed(), 0.8, 8.0) : 2.0;
        double age = seconds - latest.seconds;
        if (age < 0.0 || age > length + FADE_OUT)
            return 0.0F;
        st.pulseStart = latest.seconds;
        return age <= length ? 1.0F : (float) (1.0 - (age - length) / FADE_OUT);
    }

    /**
     * Whether a record is playing within {@link #RANGE}. The client's jukebox block entity does not carry the disc
     * (only {@code has_record} is synced, and that stays set after the song ends), so this follows the sound itself.
     */
    private static boolean jukeboxNear(Vec3 center) {
        if (devRecord != null && devRecord.distanceToSqr(center) <= RANGE * RANGE)
            return true;
        for (SoundInstance sound : RECORDS.keySet()) {
            double dx = sound.getX() - center.x;
            double dy = sound.getY() - center.y;
            double dz = sound.getZ() - center.z;
            if (dx * dx + dy * dy + dz * dz <= RANGE * RANGE)
                return true;
        }
        return false;
    }
}
