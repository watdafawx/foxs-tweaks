package dev.watdafawx.foxstweaks.client.audio;

import net.minecraft.client.sounds.AudioStream;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Passes every buffer of a streamed sound through unchanged and lets its {@link AudioTap} see a copy. Wrapping at
 * the {@code getStream} call inside {@code SoundEngine#play} covers vanilla's own streams and any mod's
 * {@code SoundInstance} that overrides {@code getStream} to supply its own PCM, as long as it plays through
 * vanilla's engine.
 *
 * <p>The returned buffer is the delegate's own, untouched: the copy reads a {@code duplicate()} so the position the
 * channel uploads from is never moved.
 */
public final class AnalysingAudioStream implements AudioStream {

    private final AudioStream delegate;
    private final AudioTap tap;

    AnalysingAudioStream(AudioStream delegate, AudioTap tap) {
        this.delegate = delegate;
        this.tap = tap;
    }

    public AudioTap tap() {
        return this.tap;
    }

    @Override
    public AudioFormat getFormat() {
        return this.delegate.getFormat();
    }

    @Override
    public ByteBuffer read(int size) throws IOException {
        ByteBuffer buf = this.delegate.read(size);
        if (buf != null) {
            try {
                this.tap.onStreamRead(buf, this.delegate.getFormat(), AudioHub.copyEnabled());
            } catch (RuntimeException e) {
                // Analysis must never stop the music.
                AudioHub.warnOnce("stream copy failed", e);
            }
        }
        return buf;
    }

    @Override
    public void close() throws IOException {
        this.tap.close();
        this.delegate.close();
    }
}
