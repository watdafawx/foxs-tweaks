package dev.watdafawx.foxstweaks.client.audio;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.HologramAudioSettingsScreen;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.HologramAudioSettingsScreen;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.commands.Commands;
import net.minecraft.commands.Commands;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundSourceEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The registry of analysed sounds and the one place holograms ask "how loud is the bass here".
 *
 * <p><b>Cost when unused.</b> Two gates keep this near zero. Streams are always wrapped (a pointer and a frame
 * counter), but PCM is only copied while {@link #copyEnabled()}: some hologram in the client's world has an audio
 * modulation, or an editor/settings screen asked for a meter in the last few seconds. The FFT only runs for a tap
 * while something {@link #sample samples} its source, i.e. a visible hologram with a modulation on that source; a
 * tap nobody sampled for a second is skipped. System capture runs only while it is switched on <i>and</i> sampled.
 *
 * <p><b>Mixing.</b> Each tap is analysed (and auto-gained) on its own. A hologram combines the taps of one source
 * weighted by that category's volume slider, the instance's volume and vanilla's linear attenuation over its range,
 * measured from the hologram or from the player ({@link AudioPrefs#listenAtPlayer}): out = sum(w * s) / max(1,
 * sum(w)), so one distant jukebox is quieter than one beside the hologram, and two close ones average. AUTO takes
 * the larger of jukebox, music and (when enabled) system for every signal.
 */
public final class AudioHub {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<String> WARNED = new HashSet<>();

    private static final List<AudioTap> TAPS = new CopyOnWriteArrayList<>();
    private static volatile boolean copy;
    private static long demandUntil;
    private static Object lastSpecs;
    private static boolean specsWantAudio;
    /** Analyser hops run, for the "no work when unused" dev check. */
    public static long analyseCalls;
    @Nullable
    private static AudioTap devTap;
    @Nullable
    private static HologramAudioMod.Source devSource;

    // Render thread only.
    private static final float[] TMP = new float[5];
    private static final float[] ACC = new float[5];

    private AudioHub() {
    }

    public static void install() {
        NeoForge.EVENT_BUS.addListener(AudioHub::onClientTick);
        NeoForge.EVENT_BUS.addListener(AudioHub::onStaticSound);
        NeoForge.EVENT_BUS.addListener(AudioHub::onRegisterClientCommands);
    }

    private static boolean openSettings;

    /** {@code /foxstweaks audio}: this player's audio settings, on this client only (the server never sees it). */
    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(FoxsTweaks.MODID)
                .then(Commands.literal("audio").executes(ctx -> {
                    // The chat screen closes itself after a command, so open on a later tick.
                    openSettings = true;
                    return 1;
                })));
    }

    // ------------------------------------------------------------ gates

    /** Whether streams copy their PCM now; read on the sound thread. */
    public static boolean copyEnabled() {
        return copy;
    }

    /** An editor, panel or meter is open: keep copying for a few more seconds. */
    public static void touchDemand() {
        demandUntil = System.nanoTime() + 5_000_000_000L;
        copy = true;
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        List<HologramSpec> specs = HologramClient.specs();
        if (specs != lastSpecs) {
            lastSpecs = specs;
            specsWantAudio = false;
            for (HologramSpec spec : specs) {
                if (usesAudio(spec)) {
                    specsWantAudio = true;
                    break;
                }
            }
        }
        HologramSpec preview = HologramClient.previewSpec();
        boolean want = specsWantAudio || (preview != null && usesAudio(preview)) || System.nanoTime() < demandUntil;
        copy = want;

        Minecraft mc = Minecraft.getInstance();
        if (openSettings && !(mc.screen instanceof ChatScreen)) {
            openSettings = false;
            mc.setScreen(new HologramAudioSettingsScreen(mc.screen));
        }
        for (AudioTap tap : TAPS) {
            if (tap.closed()) {
                TAPS.remove(tap);
                continue;
            }
            SoundInstance inst = tap.instance;
            if (inst != null && tapAgeSeconds(tap) > 2.0 && !mc.getSoundManager().isActive(inst)) {
                tap.close();
                TAPS.remove(tap);
            }
        }
        SystemAudio.tick(want);
    }

    private static double tapAgeSeconds(AudioTap tap) {
        return (System.nanoTime() - tap.createdNanos) * 1.0E-9;
    }

    /** Whether any value of {@code spec} is audio modulated. */
    public static boolean usesAudio(HologramSpec spec) {
        if (spec.timeline() != null && !spec.timeline().audio().isEmpty())
            return true;
        for (HologramLayer layer : spec.layers())
            for (HologramEffect e : layer.effects())
                if (e.hasAudio())
                    return true;
        for (HologramGroup group : spec.groups())
            for (HologramEffect e : group.effects())
                if (e.hasAudio())
                    return true;
        return false;
    }

    // ------------------------------------------------------------ taps

    @Nullable
    private static HologramAudioMod.Source sourceOf(SoundInstance instance) {
        SoundSource s = instance.getSource();
        if (s == SoundSource.RECORDS)
            return HologramAudioMod.Source.JUKEBOX;
        if (s == SoundSource.MUSIC)
            return HologramAudioMod.Source.MUSIC;
        return null;
    }

    /** Called from the {@code SoundEngine#play} mixin with each streamed sound's stream, on the sound thread. */
    public static AudioStream wrap(SoundInstance instance, AudioStream stream) {
        if (stream == null || stream instanceof AnalysingAudioStream)
            return stream;
        HologramAudioMod.Source source;
        try {
            source = sourceOf(instance);
        } catch (RuntimeException e) {
            return stream;
        }
        if (source == null)
            return stream;
        AudioTap tap = new AudioTap(source, instance, stream.getFormat().getSampleRate(), false);
        TAPS.add(tap);
        return new AnalysingAudioStream(stream, tap);
    }

    /** Static sounds (note blocks, short records): PCM decoded on the side, playhead from the wall clock. */
    private static void onStaticSound(PlaySoundSourceEvent event) {
        if (!copy)
            return;
        SoundInstance instance = event.getSound();
        HologramAudioMod.Source source = sourceOf(instance);
        if (source == null)
            return;
        Sound sound = instance.getSound();
        if (sound == null)
            return;
        AudioTap tap = new AudioTap(source, instance, 44100.0F, false);
        tap.markStaticStart();
        TAPS.add(tap);
        StaticSoundCache.decode(sound.getPath(), tap);
    }

    // ------------------------------------------------------------ sampling

    /**
     * The five signals (level, bass, mid, high, beat; 0..1) of {@code source} as heard at {@code at}, into
     * {@code out}. Marks the source as wanted, which is what lets its taps run their analysers.
     */
    public static void sample(HologramAudioMod.Source source, Vec3 at, float[] out) {
        java.util.Arrays.fill(out, 0, 5, 0.0F);
        if (source == HologramAudioMod.Source.AUTO) {
            sampleOne(HologramAudioMod.Source.JUKEBOX, at, out);
            sampleMax(HologramAudioMod.Source.MUSIC, at, out);
            if (SystemAudio.enabled())
                sampleMax(HologramAudioMod.Source.SYSTEM, at, out);
            return;
        }
        sampleOne(source, at, out);
    }

    private static void sampleMax(HologramAudioMod.Source source, Vec3 at, float[] out) {
        float[] tmp = TMP;
        java.util.Arrays.fill(tmp, 0.0F);
        sampleOne(source, at, tmp);
        for (int i = 0; i < 5; i++)
            out[i] = Math.max(out[i], tmp[i]);
    }

    private static void sampleOne(HologramAudioMod.Source source, Vec3 at, float[] out) {
        long now = System.nanoTime();
        if (source == HologramAudioMod.Source.SYSTEM) {
            AudioTap tap = SystemAudio.requestTap();
            if (tap != null)
                accumulate(tap, 1.0F, now, out);
            return;
        }
        Vec3 listen = AudioPrefs.listenAtPlayer() ? playerPos(at) : at;
        float total = 0.0F;
        float[] acc = ACC;
        java.util.Arrays.fill(acc, 0.0F);
        for (AudioTap tap : TAPS) {
            if (tap.source != source || tap.closed())
                continue;
            float w = weight(tap, listen);
            if (w <= 0.0F)
                continue;
            accumulate(tap, w, now, acc);
            total += w;
        }
        AudioTap dev = devTap;
        if (dev != null && devSource == source) {
            accumulate(dev, 1.0F, now, acc);
            total += 1.0F;
        }
        float div = Math.max(1.0F, total);
        for (int i = 0; i < 5; i++)
            out[i] = Math.min(1.0F, acc[i] / div);
    }

    private static void accumulate(AudioTap tap, float w, long now, float[] out) {
        tap.requestedNanos = now;
        // Analyse at most once per ~4 ms, however many holograms ask in one frame.
        if (now - tap.analysedNanos > 4_000_000L) {
            tap.analysedNanos = now;
            tap.analyse();
            analyseCalls++;
        }
        AudioAnalyser a = tap.analyser;
        out[0] += w * a.level;
        out[1] += w * a.bass;
        out[2] += w * a.mid;
        out[3] += w * a.high;
        out[4] += w * a.beat;
    }

    private static Vec3 playerPos(Vec3 fallback) {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? mc.player.getEyePosition() : fallback;
    }

    /** Slider x instance volume x vanilla's linear falloff. */
    static float weight(AudioTap tap, Vec3 listen) {
        SoundInstance inst = tap.instance;
        if (inst == null)
            return 1.0F;
        Minecraft mc = Minecraft.getInstance();
        float slider = mc.options.getSoundSourceVolume(inst.getSource());
        float vol;
        float range;
        try {
            vol = inst.getVolume();
            Sound sound = inst.getSound();
            range = Math.max(vol, 1.0F) * (sound == null ? 16 : sound.getAttenuationDistance());
        } catch (RuntimeException e) {
            return 0.0F;
        }
        float w = slider * Math.min(1.0F, Math.max(0.0F, vol));
        if (inst.isRelative() || inst.getAttenuation() == SoundInstance.Attenuation.NONE)
            return w;
        double d = Math.sqrt(listen.distanceToSqr(inst.getX(), inst.getY(), inst.getZ()));
        return (float) (w * Math.max(0.0, 1.0 - d / Math.max(1.0F, range)));
    }

    /** Level meter for the editors: the source as heard at the player. */
    public static void meter(HologramAudioMod.Source source, float[] out) {
        touchDemand();
        Minecraft mc = Minecraft.getInstance();
        Vec3 at = mc.player != null ? mc.player.position() : Vec3.ZERO;
        sample(source, at, out);
    }

    /** Taps currently registered, for the settings screen and dev checks. */
    public static int tapCount() {
        return TAPS.size();
    }

    // ------------------------------------------------------------ dev hooks

    /** A generator tap the dev check writes into; it counts as one more tap of {@code source} at full weight. */
    public static AudioTap devTap(HologramAudioMod.Source source, float rate) {
        AudioTap tap = new AudioTap(source, null, rate, true);
        devTap = tap;
        devSource = source;
        return tap;
    }

    public static void devClear() {
        devTap = null;
        devSource = null;
    }

    /** A free-standing tap (not registered) for analysing generated PCM in a check. */
    public static AudioTap devDetachedTap(HologramAudioMod.Source source, float rate) {
        return new AudioTap(source, null, rate, true);
    }

    static void warnOnce(String what, Throwable t) {
        synchronized (WARNED) {
            if (WARNED.add(what))
                LOGGER.warn("Hologram audio: {}", what, t);
        }
    }

    static void info(String msg) {
        LOGGER.info("Hologram audio: {}", msg);
    }
}
