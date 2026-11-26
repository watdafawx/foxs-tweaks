package dev.watdafawx.foxstweaks.client.dev;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramAudioScreen;
import dev.watdafawx.foxstweaks.client.HologramAudioSettingsScreen;
import dev.watdafawx.foxstweaks.client.HologramCreatorScreen;
import dev.watdafawx.foxstweaks.client.HologramEditorPrefs;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.audio.AudioAnalyser;
import dev.watdafawx.foxstweaks.client.audio.AudioHub;
import dev.watdafawx.foxstweaks.client.audio.AudioPrefs;
import dev.watdafawx.foxstweaks.client.audio.AudioTap;
import dev.watdafawx.foxstweaks.client.audio.HologramAudioEval;
import dev.watdafawx.foxstweaks.client.audio.SystemAudio;
import dev.watdafawx.foxstweaks.client.web.HologramWebDev;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Param;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Preset;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Signal;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Source;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.AudioLink;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.TargetKind;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

/**
 * Dev-only check of music-reactive holograms. No speaker is involved: every signal is generated.
 *
 * <ul>
 *   <li><b>Analyser</b> on free-standing taps (generated PCM, analysed as fast as possible): 60 Hz / 1 kHz / 8 kHz
 *   sines land in bass / mid / high, a 120 BPM click track reads 120 BPM, silence drops every output to 0.</li>
 *   <li><b>Live path</b> through {@link SystemAudio}'s fake capture device (its real capture thread, ring and
 *   analyser, at wall-clock speed): modulations change scale, brightness, strength and hue, stack, clamp and decay
 *   in silence.</li>
 *   <li><b>Data</b>: presets, NBT, the web JSON both ways, the server's caps.</li>
 *   <li><b>UI</b>: the Creator's music section and ♪ chips, the ♪ panel, the settings screen, {@code /foxstweaks
 *   audio}, save, and a refused over-cap save.</li>
 *   <li><b>Cost</b>: no analyser hop runs while no hologram uses audio.</li>
 * </ul>
 *
 * <p>Run with {@code build.bat runHologramAudioCheck} on rig. Report:
 * {@code run-hologram-audio/foxstweaks-hologram-audio-check.txt}.
 */
public final class HologramAudioCheck {
    private static final Logger LOG = LogUtils.getLogger();
    private static final float RATE = 48_000.0F;

    private record Step(int waitTicks, Runnable run) {
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;
    private static String hologramId = "";
    private static HologramScreen editor;
    private static long callsBefore;
    private static boolean systemPrefBefore;
    private static float amountBefore;

    // Live-path fixtures
    private static HologramAudioMod scaleBass;
    private static HologramAudioMod scaleLevel;
    private static HologramAudioMod brightLevel;
    private static HologramTimeline liveTl;
    private static List<HologramEffect> liveFx;

    public static void install() {
        NeoForge.EVENT_BUS.addListener(HologramAudioCheck::onTick);
        scenarios();
    }

    // ---------------------------------------------------------------- signals

    private static float[] sine(double hz, double seconds, double amp) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        for (int i = 0; i < out.length; i++)
            out[i] = (float) (amp * Math.sin(2.0 * Math.PI * hz * i / RATE));
        return out;
    }

    /** 10 ms clicks (1 kHz plus noise, decaying) at {@code bpm}, silence between. */
    private static float[] clicks(double bpm, double seconds) {
        float[] out = new float[(int) Math.round(seconds * RATE)];
        Random rnd = new Random(42);
        int period = (int) Math.round(RATE * 60.0 / bpm);
        int len = (int) (RATE * 0.010);
        for (int start = (int) (RATE * 0.1); start + len < out.length; start += period) {
            for (int i = 0; i < len; i++) {
                double env = Math.exp(-i / (len / 4.0));
                out[start + i] = (float) (0.8 * env * (0.6 * Math.sin(2.0 * Math.PI * 1000.0 * i / RATE)
                        + 0.4 * (rnd.nextDouble() * 2.0 - 1.0)));
            }
        }
        return out;
    }

    private static AudioAnalyser analyse(float[] pcm) {
        AudioTap tap = AudioHub.devDetachedTap(Source.JUKEBOX, RATE);
        tap.writeMono(pcm, pcm.length);
        tap.analyseAllForTest();
        return tap.analyser();
    }

    private static String bands(AudioAnalyser a) {
        return String.format("level=%.2f bass=%.2f mid=%.2f high=%.2f beat=%.2f", a.level, a.bass, a.mid, a.high,
                a.beat);
    }

    // ---------------------------------------------------------------- helpers

    private static HologramAudioMod mod(Param p, Signal s, float amount, float attack, float release) {
        return new HologramAudioMod(p, Source.SYSTEM, s, amount, 1.0F, attack, release, 0.0F, Float.NaN, Float.NaN);
    }

    private static String layerUid() {
        return editor == null ? "" : editor.current().layers().get(0).uid();
    }

    private static Vec3 here() {
        var p = Minecraft.getInstance().player;
        return p == null ? Vec3.ZERO : p.position();
    }

    /** One render frame of the live fixtures: sample, envelope, apply. */
    private static HologramAudioEval.Props liveProps() {
        HologramAudioEval.begin(here());
        return HologramAudioEval.props(liveTl, TargetKind.LAYER, "L");
    }

    private static HologramCreatorScreen creator() {
        return Minecraft.getInstance().screen instanceof HologramCreatorScreen c ? c : null;
    }

    private static HologramAudioScreen panel() {
        return Minecraft.getInstance().screen instanceof HologramAudioScreen p ? p : null;
    }

    private static void click(String tooltip) {
        var c = creator();
        if (c == null || !c.devClick(tooltip, 0))
            check("control clickable: " + tooltip, false, "screen=" + Minecraft.getInstance().screen);
    }

    private static void reveal(String tooltip) {
        var c = creator();
        if (c == null || !c.devReveal(tooltip))
            check("control exists: " + tooltip, false, "screen=" + Minecraft.getInstance().screen);
    }

    private static void panelClick(String key) {
        var p = panel();
        if (p == null || !p.devClick(key))
            check("panel control: " + key, false, "screen=" + Minecraft.getInstance().screen);
    }

    private static List<HologramAudioMod> layerLinks(HologramSpec spec, String uid) {
        return spec == null || spec.timeline() == null ? List.of() : spec.timeline().audioOf(TargetKind.LAYER, uid);
    }

    private static long count(List<HologramAudioMod> mods, Param p) {
        return mods.stream().filter(m -> m.param() == p).count();
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void editCommand() {
        onServer(player -> {
            player.server.getCommands().performPrefixedCommand(
                    player.createCommandSourceStack().withPermission(4), "foxstweaks hologram edit " + hologramId);
            return null;
        });
    }

    // ---------------------------------------------------------------- scenarios

    private static void scenarios() {
        add(2, () -> {
            Minecraft mc = Minecraft.getInstance();
            try {
                mc.getWindow().setWindowed(1920, 1080);
            } catch (Throwable t) {
                LOG.warn("could not resize the window", t);
            }
            mc.resizeDisplay();
            mc.options.guiScale().set(2);
            mc.resizeDisplay();
            systemPrefBefore = AudioPrefs.systemEnabled();
        });
        add(40, () -> onServer(player -> {
            player.serverLevel().setDayTime(6000);
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            return null;
        }));
        add(20, () -> {
            onServer(player -> {
                player.server.getCommands().performPrefixedCommand(
                        player.createCommandSourceStack().withPermission(4),
                        "foxstweaks hologram add minecraft:amethyst_block");
                return null;
            });
            hologramId = onServer(player -> {
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("hologram created", !hologramId.isEmpty(), "none");
        });

        // ---- cost: a hologram without audio in view, nothing open: no hop, no PCM copies ----
        add(60, () -> callsBefore = AudioHub.analyseCalls);
        add(60, () -> {
            check("no analyser work while no hologram uses audio", AudioHub.analyseCalls == callsBefore,
                    (AudioHub.analyseCalls - callsBefore) + " hops");
            check("no PCM copied while unused", !AudioHub.copyEnabled(), "copy on");
            check("system capture idle while unused", !SystemAudio.running(), "running");
        });

        // ---- analyser: bands, beat, silence (generated PCM, analysed offline) ----
        add(1, () -> {
            AudioAnalyser lo = analyse(sine(60, 1.0, 0.5));
            check("60 Hz lands in bass", lo.bass > 0.8F && lo.mid < 0.3F && lo.high < 0.3F, bands(lo));
            log("  60 Hz  " + bands(lo));
            AudioAnalyser mid = analyse(sine(1000, 1.0, 0.5));
            check("1 kHz lands in mid", mid.mid > 0.8F && mid.bass < 0.3F && mid.high < 0.3F, bands(mid));
            log("  1 kHz  " + bands(mid));
            AudioAnalyser hi = analyse(sine(8000, 1.0, 0.5));
            check("8 kHz lands in high", hi.high > 0.8F && hi.bass < 0.3F && hi.mid < 0.3F, bands(hi));
            log("  8 kHz  " + bands(hi));

            AudioAnalyser quiet = analyse(sine(60, 2.0, 0.02F));
            check("auto-gain: a quiet bass still reads full", quiet.bass > 0.8F && quiet.level > 0.8F, bands(quiet));

            AudioAnalyser beat = analyse(clicks(120, 12.0));
            double bpm = beat.bpm();
            check("120 BPM click track reads 120 BPM +-5%", Math.abs(bpm - 120.0) <= 6.0, "bpm=" + bpm);
            check("one onset per click", beat.onsetTotal >= 21 && beat.onsetTotal <= 25,
                    "onsets=" + beat.onsetTotal + " (24 clicks)");
            log(String.format("  clicks bpm=%.1f onsets=%d", bpm, beat.onsetTotal));

            float[] toneThenSilence = new float[(int) (RATE * 1.5)];
            System.arraycopy(sine(60, 1.0, 0.5), 0, toneThenSilence, 0, (int) RATE);
            AudioAnalyser silent = analyse(toneThenSilence);
            check("silence drops every output to 0", silent.level == 0.0F && silent.bass == 0.0F
                    && silent.mid == 0.0F && silent.high == 0.0F && silent.beat < 0.01F, bands(silent));
        });

        // ---- live path: fake capture device -> capture thread -> tap -> modulations ----
        add(1, () -> {
            SystemAudio.devUseFake(sine(60, 1.0, 0.5F), RATE);
            HologramAudioEval.devReset();
            scaleBass = mod(Param.SCALE, Signal.BASS, 0.5F, 0.0F, 0.1F);
            brightLevel = mod(Param.BRIGHT, Signal.LEVEL, 1.0F, 0.0F, 0.1F);
            liveTl = HologramTimeline.empty(4.0F).withAudio(List.of(new AudioLink(TargetKind.LAYER, "L", scaleBass),
                    new AudioLink(TargetKind.LAYER, "L", brightLevel)));
            HologramEffect tint = HologramEffect.of(HologramEffect.Type.TINT).withColor(0xFF0000).withStrength(0.2F)
                    .withAudio(List.of(mod(Param.STRENGTH, Signal.LEVEL, 0.5F, 0.0F, 0.1F),
                            mod(Param.HUE, Signal.BASS, 0.33F, 0.0F, 0.1F)));
            liveFx = List.of(tint);
        });
        for (int i = 0; i < 40; i++)
            add(1, HologramAudioCheck::liveProps); // a frame per tick, as the renderer would
        add(1, () -> {
            check("fake capture device is running", SystemAudio.running() && SystemAudio.status().startsWith("Listening"),
                    SystemAudio.status());
            var p = liveProps();
            float scale = p == null ? Float.NaN : p.apply(Param.SCALE, 1.0F);
            check("bass raises scale (1 -> ~1.5)", p != null && scale > 1.35F && scale < 1.55F, "scale=" + scale);
            int tint = p == null ? 0 : p.tint(0xFFFFFF);
            check("loud music keeps full brightness", (tint >> 16 & 0xFF) > 0xC0, String.format("tint=%06X", tint));
            HologramAudioEval.begin(here());
            HologramEffect e = HologramAudioEval.effects(liveFx, 1.0).get(0);
            check("level raises effect strength (0.2 -> ~0.7)", e.strength() > 0.6F && e.strength() < 0.75F,
                    "strength=" + e.strength());
            int r = e.color() >> 16 & 0xFF, g = e.color() >> 8 & 0xFF;
            check("bass shifts the hue (red -> green)", g > 0xC0 && r < 0x40, String.format("color=%06X", e.color()));
            check("unmodulated list passes through untouched",
                    HologramAudioEval.effects(List.of(HologramEffect.of(HologramEffect.Type.TINT)), 1.0).size() == 1,
                    "changed");

            // Stacking: a second scale modulation adds on top; a clamp caps the sum.
            scaleLevel = mod(Param.SCALE, Signal.LEVEL, 0.3F, 0.0F, 0.1F);
            liveTl = liveTl.withAudio(List.of(new AudioLink(TargetKind.LAYER, "L", scaleBass),
                    new AudioLink(TargetKind.LAYER, "L", scaleLevel),
                    new AudioLink(TargetKind.LAYER, "L", brightLevel)));
        });
        add(2, HologramAudioCheck::liveProps);
        add(1, () -> {
            var p = liveProps();
            float scale = p == null ? Float.NaN : p.apply(Param.SCALE, 1.0F);
            check("two scale modulations stack (~1.8)", Math.abs(scale - 1.8F) < 0.12F, "scale=" + scale);
            HologramAudioMod capped = scaleLevel.withRange(Float.NaN, 1.4F);
            liveTl = liveTl.withAudio(List.of(new AudioLink(TargetKind.LAYER, "L", scaleBass),
                    new AudioLink(TargetKind.LAYER, "L", capped), new AudioLink(TargetKind.LAYER, "L", brightLevel)));
            scaleLevel = capped;
        });
        add(2, HologramAudioCheck::liveProps);
        add(1, () -> {
            var p = liveProps();
            float scale = p == null ? Float.NaN : p.apply(Param.SCALE, 1.0F);
            check("clamp caps the stacked value at 1.4", Math.abs(scale - 1.4F) < 0.01F, "scale=" + scale);
            check("other targets are untouched", HologramAudioEval.props(liveTl, TargetKind.LAYER, "other") == null,
                    "props for an unrelated layer");
            // Silence: the device keeps delivering, but zeros.
            SystemAudio.devUseFake(new float[4800], RATE);
        });
        for (int i = 0; i < 30; i++)
            add(1, HologramAudioCheck::liveProps);
        add(1, () -> {
            var p = liveProps();
            float scale = p == null ? Float.NaN : p.apply(Param.SCALE, 1.0F);
            check("silence: scale decays back to 1", Math.abs(scale - 1.0F) < 0.02F, "scale=" + scale);
            float env = HologramAudioEval.devEnvelope(scaleBass);
            check("silence: envelope decays to ~0", env < 0.02F, "env=" + env);
            int tint = p == null ? 0xFFFFFF : p.tint(0xFFFFFF);
            check("silence: brightness modulation goes dark", (tint >> 16 & 0xFF) < 0x20, String.format("tint=%06X", tint));
            SystemAudio.devUseFake(sine(60, 1.0, 0.5F), RATE); // back on for the UI part and its screenshots
        });

        // ---- presets, NBT, web JSON, caps ----
        add(1, () -> {
            for (Preset preset : Preset.values()) {
                HologramTimeline tl = preset.applyTo(null, TargetKind.LAYER, "L");
                List<HologramAudioMod> mods = tl.audioOf(TargetKind.LAYER, "L");
                boolean all = preset.mods().stream().allMatch(m -> mods.stream()
                        .anyMatch(x -> x.param() == m.param() && x.signal() == m.signal()));
                check("preset " + preset.label() + " applies", all && tl.tracks().isEmpty(), mods.toString());
            }
            HologramTimeline both = Preset.GLOW.applyTo(Preset.DANCE.applyTo(null, TargetKind.LAYER, "L"),
                    TargetKind.LAYER, "L");
            List<HologramAudioMod> m = both.audioOf(TargetKind.LAYER, "L");
            check("Dance + Glow combine", count(m, Param.OFFSET_Y) == 1 && count(m, Param.ROT_Y) == 1
                    && count(m, Param.BRIGHT) == 1, m.toString());
            HologramTimeline twice = Preset.PULSE_BASS.applyTo(Preset.PULSE_BASS.applyTo(null, TargetKind.LAYER, "L"),
                    TargetKind.LAYER, "L");
            check("a preset applied twice does not double", count(twice.audioOf(TargetKind.LAYER, "L"), Param.SCALE) == 1,
                    twice.audio().toString());

            HologramEffect fx = liveFx.get(0);
            HologramEffect back = HologramEffect.listFromTag(HologramEffect.listToTag(List.of(fx))).get(0);
            check("effect audio survives NBT", back.audio().equals(fx.audio()), back.audio() + " vs " + fx.audio());
            HologramTimeline tlBack = HologramTimeline.fromTag(liveTl.toTag());
            check("timeline audio survives NBT (incl. clamp)", tlBack.audio().equals(liveTl.audio()),
                    tlBack.audio() + " vs " + liveTl.audio());
            check("audio-only timeline stays audio-only", tlBack.isAudioOnly() && tlBack.tracks().isEmpty(),
                    "tracks=" + tlBack.tracks().size());
        });

        // ---- UI: the Creator's music section ----
        add(10, () -> {
            HologramEditorPrefs.setStartInCreator(true);
            editCommand();
        });
        add(10, () -> {
            var c = creator();
            check("edit opens the Creator", c != null, String.valueOf(Minecraft.getInstance().screen));
            if (c == null)
                return;
            editor = c.editor();
            reveal("Grows a little on every bass hit");
        });
        add(4, () -> {
            click("Grows a little on every bass hit");
            List<HologramAudioMod> mods = layerLinks(editor.current(), layerUid());
            check("Creator preset chip adds bass -> scale", mods.stream()
                    .anyMatch(m -> m.param() == Param.SCALE && m.signal() == Signal.BASS), mods.toString());
            reveal("Glitch bursts");
        });
        add(4, () -> click("Glitch bursts"));
        add(2, () -> reveal("Make it react to music"));
        add(6, () -> shot("audio-creator-music.png"));
        add(2, () -> click("Make it react to music"));

        // ---- the ♪ panel on an effect ----
        add(4, () -> {
            var p = panel();
            check("stack row ♪ opens the audio panel", p != null, String.valueOf(Minecraft.getInstance().screen));
            panelClick("add");
        });
        add(2, () -> {
            var p = panel();
            amountBefore = p == null || p.devMods().isEmpty() ? Float.NaN : p.devMods().get(0).amount();
            panelClick("amount +");
            panelClick("signal");
        });
        add(6, () -> {
            var p = panel();
            List<HologramAudioMod> mods = p == null ? List.of() : p.devMods();
            check("panel adds a strength modulation", mods.size() == 1 && mods.get(0).param() == Param.STRENGTH,
                    mods.toString());
            check("panel + raises the amount", !mods.isEmpty() && mods.get(0).amount() > amountBefore,
                    "before=" + amountBefore + " " + mods);
            check("panel cycles the signal", !mods.isEmpty() && mods.get(0).signal() == Signal.LEVEL.next(),
                    mods.toString());
            var fx = editor.current().layers().get(0).effects();
            check("the effect in the editor got it", fx.stream().anyMatch(HologramEffect::hasAudio), fx.toString());
            shot("audio-panel-effect.png");
            panelClick("done");
        });

        // ---- the ♪ panel on the layer, its preset row and the settings screen ----
        add(4, () -> {
            check("Done returns to the Creator", creator() != null, String.valueOf(Minecraft.getInstance().screen));
            reveal("Every value");
        });
        add(2, () -> click("Every value"));
        add(4, () -> {
            check("music section opens the layer panel", panel() != null, String.valueOf(Minecraft.getInstance().screen));
            panelClick("preset Dance");
        });
        add(10, () -> {
            List<HologramAudioMod> mods = layerLinks(editor.current(), layerUid());
            check("panel preset Dance adds hop + sway", count(mods, Param.OFFSET_Y) == 1 && count(mods, Param.ROT_Y) == 1
                    && count(mods, Param.SCALE) == 1, mods.toString());
            shot("audio-panel-layer.png");
            panelClick("settings");
        });
        add(10, () -> {
            var s = Minecraft.getInstance().screen;
            check("Audio settings opens the settings screen", s instanceof HologramAudioSettingsScreen, String.valueOf(s));
            check("fake device reports listening", SystemAudio.status().startsWith("Listening"), SystemAudio.status());
            if (s instanceof HologramAudioSettingsScreen st) {
                boolean before = AudioPrefs.systemEnabled();
                st.devClick("system");
                check("system audio toggle flips the setting", AudioPrefs.systemEnabled() != before, "unchanged");
                st.devClick("system");
                check("and back", AudioPrefs.systemEnabled() == before, "not restored");
            }
            shot("audio-settings.png");
        });
        add(4, () -> {
            if (Minecraft.getInstance().screen instanceof HologramAudioSettingsScreen st)
                st.devClick("done");
            check("settings Done returns to the panel", panel() != null, String.valueOf(Minecraft.getInstance().screen));
            panelClick("done");
        });

        // ---- save: the server keeps everything ----
        add(4, () -> click("Save and close"));
        add(10, () -> {
            HologramSpec saved = onServer(p -> Holograms.get(p.serverLevel(), hologramId));
            String uid = saved == null ? "" : saved.layers().get(0).uid();
            List<HologramAudioMod> links = layerLinks(saved, uid);
            check("server keeps layer modulations", count(links, Param.SCALE) == 1 && count(links, Param.OFFSET_Y) == 1,
                    links.toString());
            check("server keeps effect modulations", saved != null
                    && saved.layers().get(0).effects().stream().anyMatch(HologramEffect::hasAudio),
                    saved == null ? "null" : saved.layers().get(0).effects().toString());
            if (saved == null)
                return;
            HologramSpec web = HologramWebDev.roundTrip(saved, 64);
            check("web JSON round trip keeps layer modulations",
                    web.timeline() != null && web.timeline().audio().equals(saved.timeline().audio()),
                    web.timeline() == null ? "no timeline" : web.timeline().audio().toString());
            check("web JSON round trip keeps effect modulations",
                    web.layers().get(0).effects().get(0).audio().equals(saved.layers().get(0).effects().get(0).audio())
                            || web.layers().get(0).effects().equals(saved.layers().get(0).effects()),
                    web.layers().get(0).effects().toString());
            check("web JSON carries audio", HologramWebDev.json(saved, 64).contains("\"audio\""), "missing");

            // Caps, as the server and the page see them.
            List<AudioLink> five = new ArrayList<>();
            for (int i = 0; i < 5; i++)
                five.add(new AudioLink(TargetKind.LAYER, uid, HologramAudioMod.of(Param.SCALE, Signal.BASS, 0.1F)));
            HologramSpec over = saved.withTimeline(HologramTimeline.empty(4.0F).withAudio(five));
            HologramSpec atCap = saved.withTimeline(HologramTimeline.empty(4.0F).withAudio(five.subList(0, 4)));
            String overProblem = onServer(p -> HologramNetwork.timelineProblem(over));
            String capProblem = onServer(p -> HologramNetwork.timelineProblem(atCap));
            check("server refuses 5 modulations on one value", overProblem != null, "accepted");
            check("server accepts 4", capProblem == null, String.valueOf(capProblem));
            boolean webRefused;
            try {
                HologramWebDev.roundTrip(over, 64);
                webRefused = false;
            } catch (IllegalArgumentException e) {
                webRefused = true;
            }
            check("web JSON refuses the over-cap spec", webRefused, "accepted");
        });

        // ---- in view with audio: the renderer samples it ----
        add(1, () -> callsBefore = AudioHub.analyseCalls);
        add(40, () -> {
            check("a visible hologram with audio runs the analyser", AudioHub.analyseCalls > callsBefore,
                    "hops=" + (AudioHub.analyseCalls - callsBefore));
            check("PCM is copied while a hologram uses audio", AudioHub.copyEnabled(), "copy off");
            shot("audio-world.png");
        });

        // ---- an over-cap save from the editor is refused; the stored hologram stays as it was ----
        add(4, HologramAudioCheck::editCommand);
        add(10, () -> {
            var c = creator();
            check("editor reopens", c != null, String.valueOf(Minecraft.getInstance().screen));
            if (c != null) {
                editor = c.editor();
                reveal("Every value");
            }
        });
        add(2, () -> click("Every value"));
        add(4, () -> {
            for (int i = 0; i < 4; i++)
                panelClick("add");
            var p = panel();
            check("panel lets you go past the server cap (the server decides)",
                    p != null && count(p.devMods(), Param.SCALE) == 5, p == null ? "no panel" : p.devMods().toString());
            panelClick("done");
        });
        add(4, () -> click("Save and close"));
        add(10, () -> {
            HologramSpec saved = onServer(p -> Holograms.get(p.serverLevel(), hologramId));
            String uid = saved == null ? "" : saved.layers().get(0).uid();
            List<HologramAudioMod> links = layerLinks(saved, uid);
            check("server refused the over-cap save", count(links, Param.SCALE) == 1, links.toString());
            var s = Minecraft.getInstance().screen;
            if (s != null)
                s.onClose();
        });

        // ---- /foxstweaks audio ----
        add(6, () -> Minecraft.getInstance().player.connection.sendCommand("foxstweaks audio"));
        add(6, () -> {
            var s = Minecraft.getInstance().screen;
            check("/foxstweaks audio opens the settings", s instanceof HologramAudioSettingsScreen, String.valueOf(s));
            if (s != null)
                s.onClose();
        });

        // ---- a capture device that vanishes ----
        add(2, () -> SystemAudio.devUseFailingFake(sine(60, 1.0, 0.5F), RATE, 400));
        for (int i = 0; i < 30; i++)
            add(1, () -> AudioHub.meter(Source.SYSTEM, new float[5]));
        add(1, () -> {
            check("lost device is reported", SystemAudio.failed() && SystemAudio.status().startsWith("Device lost"),
                    SystemAudio.status());
            check("capture thread ended", !SystemAudio.running(), "still running");
            float[] m = new float[5];
            AudioHub.meter(Source.SYSTEM, m);
            check("a lost device reads silence, not stale levels", m[0] == 0.0F && m[1] == 0.0F,
                    java.util.Arrays.toString(m));
            // The status line and Retry only show with the setting on (the fake device alone does not switch it).
            AudioPrefs.setSystemEnabled(true);
            Minecraft.getInstance().setScreen(new HologramAudioSettingsScreen(null));
        });
        add(6, () -> {
            shot("audio-settings-lost.png");
            boolean pressed = Minecraft.getInstance().screen instanceof HologramAudioSettingsScreen st
                    && st.devClick("refresh");
            check("Retry clears the failure", pressed && !SystemAudio.failed(), "pressed=" + pressed + " "
                    + SystemAudio.status());
            SystemAudio.devClearFake();
            if (AudioPrefs.systemEnabled() != systemPrefBefore)
                AudioPrefs.setSystemEnabled(systemPrefBefore);
            var s = Minecraft.getInstance().screen;
            if (s != null)
                s.onClose();
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-audio-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------- harness

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramAudioCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size())
            return;

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("HologramAudioCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-audiocheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramAudioCheck() {
    }
}
