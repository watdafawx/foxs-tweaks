package dev.watdafawx.foxstweaks.client.audio;

import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.resources.ResourceLocation;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mono PCM of static (non-streamed) records-category sounds: note blocks and short jukebox-style clips. Vanilla
 * hands their decoded buffer straight to OpenAL and drops it, so the PCM is decoded a second time here, on a
 * background thread, the first time such a sound plays while something wants audio, and kept in a small LRU (a
 * note block's harp sample is a few hundred KB of floats; 32 entries, longer than 30 s are skipped).
 */
final class StaticSoundCache {

    private record Pcm(float[] mono, float rate) {
    }

    private static final int MAX_ENTRIES = 32;
    private static final int MAX_FRAMES = 30 * 48000;

    private static final Map<ResourceLocation, Pcm> CACHE = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<ResourceLocation, Pcm> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private StaticSoundCache() {
    }

    static void decode(ResourceLocation path, AudioTap tap) {
        Pcm hit;
        synchronized (CACHE) {
            hit = CACHE.get(path);
        }
        if (hit != null) {
            tap.attachStatic(hit.mono, hit.rate);
            return;
        }
        Util.backgroundExecutor().execute(() -> {
            try (InputStream in = Minecraft.getInstance().getResourceManager().open(path);
                 JOrbisAudioStream ogg = new JOrbisAudioStream(in)) {
                int channels = Math.max(1, ogg.getFormat().getChannels());
                float rate = ogg.getFormat().getSampleRate();
                float[][] out = { new float[(int) Math.min(MAX_FRAMES, rate * 2)] };
                int[] frames = { 0 };
                int[] ch = { 0 };
                float[] sum = { 0.0F };
                boolean more = true;
                while (more && frames[0] < MAX_FRAMES) {
                    more = ogg.readChunk(v -> {
                        sum[0] += v;
                        if (++ch[0] == channels) {
                            if (frames[0] >= out[0].length && out[0].length < MAX_FRAMES)
                                out[0] = java.util.Arrays.copyOf(out[0], Math.min(MAX_FRAMES, out[0].length * 2));
                            if (frames[0] < out[0].length)
                                out[0][frames[0]++] = sum[0] / channels;
                            ch[0] = 0;
                            sum[0] = 0.0F;
                        }
                    });
                }
                float[] mono = java.util.Arrays.copyOf(out[0], frames[0]);
                Pcm pcm = new Pcm(mono, rate);
                synchronized (CACHE) {
                    CACHE.put(path, pcm);
                }
                tap.attachStatic(mono, rate);
            } catch (Exception e) {
                AudioHub.warnOnce("could not decode " + path, e);
                tap.close();
            }
        });
    }
}
