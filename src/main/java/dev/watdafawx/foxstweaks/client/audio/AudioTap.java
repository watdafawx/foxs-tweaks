package dev.watdafawx.foxstweaks.client.audio;

import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import net.minecraft.client.resources.sounds.SoundInstance;

import javax.annotation.Nullable;
import javax.sound.sampled.AudioFormat;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * One analysed sound: a jukebox record, a music track, a note, or the system capture.
 *
 * <p><b>Threads.</b> PCM arrives on the sound thread (vanilla's stream pump) or the capture thread and goes into a
 * mono float ring; the render thread reads windows out of it. The writer publishes with the {@code written}
 * volatile after the samples are stored, and the reader never reads past it, so no lock is needed. The ring holds
 * ~11 s, more than the ~4 s vanilla reads ahead of what is heard, so the reader's window is never overwritten
 * while it is being read.
 *
 * <p><b>What is heard, not what was read.</b> Vanilla queues four one-second buffers ahead, so the latest PCM is up
 * to four seconds in the future. The playhead is the frame OpenAL is playing right now: frames delivered minus the
 * frames still queued, plus the source's sample offset into the current buffer (see {@code ChannelAudioTapMixin}),
 * published once per tick and extrapolated with the wall clock in between (at most 100 ms, so a paused game holds).
 * Static (non-streamed) sounds have no stream; their PCM is decoded separately and their playhead is wall clock.
 */
public final class AudioTap {

    private static final int RING = 1 << 19; // 524288 frames, ~11.9 s at 44.1 kHz
    private static final int MASK = RING - 1;

    public final HologramAudioMod.Source source;
    @Nullable
    public final SoundInstance instance;
    private final float[] ring = new float[RING];
    private volatile long written;
    private volatile float sampleRate;

    // Playhead: buffer sizes in queue order, the latest queued count/offset, and when they were read.
    private final long[] bufferFrames = new long[16];
    private int bufferCount;
    private volatile long playFrame = -1;
    private volatile long playNanos;
    /** Live sources (system capture) have no queue: what was just written is what is heard. */
    private final boolean live;
    private volatile long lastWriteNanos;

    // Static sounds: decoded PCM and the moment the channel started.
    @Nullable
    private volatile float[] staticPcm;
    private volatile long staticStartNanos;
    private final float pitch;

    private volatile boolean closed;
    volatile long requestedNanos;
    long analysedNanos;
    final long createdNanos = System.nanoTime();

    final AudioAnalyser analyser;
    private long analysedFrame = -1;

    AudioTap(HologramAudioMod.Source source, @Nullable SoundInstance instance, float sampleRate, boolean live) {
        this.source = source;
        this.instance = instance;
        this.sampleRate = sampleRate;
        this.live = live;
        float p = instance == null ? 1.0F : instance.getPitch();
        this.pitch = Float.isFinite(p) && p > 0.0F ? p : 1.0F;
        this.analyser = new AudioAnalyser(sampleRate * this.pitch);
    }

    public boolean closed() {
        return this.closed;
    }

    void close() {
        this.closed = true;
    }

    public long written() {
        return this.written;
    }

    // ------------------------------------------------------------ writer side (sound / capture thread)

    /** One buffer the stream handed to OpenAL; copies it when {@code copy} is on, always counts it. */
    void onStreamRead(ByteBuffer buf, AudioFormat format, boolean copy) {
        int channels = Math.max(1, format.getChannels());
        int bits = format.getSampleSizeInBits();
        int bytesPerFrame = Math.max(1, channels * Math.max(1, bits / 8));
        int frames = buf.remaining() / bytesPerFrame;
        if (format.getSampleRate() > 1000.0F && format.getSampleRate() != this.sampleRate) {
            this.sampleRate = format.getSampleRate();
        }
        if (copy && frames > 0)
            copyPcm(buf, format, channels, bits, frames);
        synchronized (this.bufferFrames) {
            this.bufferFrames[this.bufferCount++ % this.bufferFrames.length] = frames;
        }
        if (!copy)
            this.written += frames; // keep the frame count right for the playhead even when not copying
        this.lastWriteNanos = System.nanoTime();
    }

    private void copyPcm(ByteBuffer src, AudioFormat format, int channels, int bits, int frames) {
        ByteBuffer buf = src.duplicate().order(format.isBigEndian() ? ByteOrder.BIG_ENDIAN : ByteOrder.LITTLE_ENDIAN);
        boolean isFloat = format.getEncoding() == AudioFormat.Encoding.PCM_FLOAT;
        boolean unsigned = format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
        long w = this.written;
        int pos = buf.position();
        for (int f = 0; f < frames; f++) {
            float sum = 0.0F;
            for (int c = 0; c < channels; c++) {
                float v;
                if (bits == 16) {
                    v = buf.getShort(pos) / 32768.0F;
                    pos += 2;
                } else if (bits == 8) {
                    int b = buf.get(pos);
                    v = unsigned ? ((b & 0xFF) - 128) / 128.0F : b / 128.0F;
                    pos += 1;
                } else if (bits == 32 && isFloat) {
                    v = buf.getFloat(pos);
                    pos += 4;
                } else if (bits == 32) {
                    v = buf.getInt(pos) / 2147483648.0F;
                    pos += 4;
                } else if (bits == 24) {
                    int b0 = buf.get(pos) & 0xFF, b1 = buf.get(pos + 1) & 0xFF, b2 = buf.get(pos + 2);
                    int s = buf.order() == ByteOrder.LITTLE_ENDIAN ? (b2 << 16) | (b1 << 8) | b0
                            : ((b0 << 24) >> 8) | (b1 << 8) | (buf.get(pos + 2) & 0xFF);
                    v = s / 8388608.0F;
                    pos += 3;
                } else {
                    v = 0.0F;
                    pos += Math.max(1, bits / 8);
                }
                sum += v;
            }
            this.ring[(int) (w + f) & MASK] = sum / channels;
        }
        this.written = w + frames;
    }

    /** Mono floats from a capture device or a test generator. */
    public void writeMono(float[] mono, int count) {
        long w = this.written;
        for (int i = 0; i < count; i++)
            this.ring[(int) (w + i) & MASK] = mono[i];
        this.written = w + count;
        this.lastWriteNanos = System.nanoTime();
    }

    /** Called right after vanilla's stream update with what OpenAL reports for the source. */
    public void onPlayhead(int queuedBuffers, int sampleOffset) {
        long unplayed = 0;
        synchronized (this.bufferFrames) {
            int n = Math.min(Math.min(queuedBuffers, this.bufferCount), this.bufferFrames.length);
            for (int i = 1; i <= n; i++)
                unplayed += this.bufferFrames[(this.bufferCount - i) % this.bufferFrames.length];
        }
        long frame = Math.max(0, this.written - unplayed + Math.max(0, sampleOffset));
        this.playNanos = System.nanoTime();
        this.playFrame = frame;
    }

    void attachStatic(float[] pcm, float rate) {
        this.sampleRate = rate;
        this.analyser.setSampleRate(rate * this.pitch);
        this.staticPcm = pcm;
    }

    void markStaticStart() {
        this.staticStartNanos = System.nanoTime();
    }

    // ------------------------------------------------------------ reader side (render thread)

    /** The frame being heard now, or -1 before anything is known. */
    long playheadNow() {
        long now = System.nanoTime();
        float[] pcm = this.staticPcm;
        if (pcm != null) {
            if (this.staticStartNanos == 0)
                return -1;
            long f = (long) ((now - this.staticStartNanos) * 1.0E-9 * this.sampleRate * this.pitch);
            return f >= pcm.length ? -2 : f; // -2: finished
        }
        if (this.live)
            return this.written;
        long frame = this.playFrame;
        if (frame < 0)
            return -1;
        double extra = Math.min(0.1, (now - this.playNanos) * 1.0E-9);
        return Math.min(this.written, frame + (long) (extra * this.sampleRate * this.pitch));
    }

    /** No fresh capture data for a while: the device stopped delivering (WASAPI loopback does that in silence). */
    boolean stale() {
        return this.live && System.nanoTime() - this.lastWriteNanos > 250_000_000L;
    }

    /** Runs the analyser up to the playhead; at most a few hops per frame, so a stall never piles up work. */
    void analyse() {
        long head = playheadNow();
        AudioAnalyser a = this.analyser;
        if (head == -2 || stale()) {
            a.hopSilent();
            return;
        }
        if (head < 0)
            return;
        float rate = this.sampleRate;
        a.setSampleRate(rate * this.pitch);
        int hop = Math.max(1, (int) Math.round(rate * this.pitch / AudioAnalyser.HOP_HZ));
        if (this.analysedFrame < 0 || head - this.analysedFrame > hop * 8L || head < this.analysedFrame)
            this.analysedFrame = Math.max(0, head - hop);
        int steps = 0;
        while (this.analysedFrame + hop <= head && steps < 4) {
            this.analysedFrame += hop;
            fill(a.input(), this.analysedFrame);
            a.hop();
            steps++;
        }
    }

    /** Copies the {@link AudioAnalyser#FFT_SIZE} frames ending at {@code end} (exclusive), zero before the start. */
    void fill(float[] dst, long end) {
        int n = dst.length;
        float[] pcm = this.staticPcm;
        long start = end - n;
        if (pcm != null) {
            // Static sounds are decoded at their own rate; the pitch is folded into the playhead and the analyser.
            for (int i = 0; i < n; i++) {
                long f = start + i;
                dst[i] = f >= 0 && f < pcm.length ? pcm[(int) f] : 0.0F;
            }
            return;
        }
        long w = this.written;
        long oldest = w - RING;
        for (int i = 0; i < n; i++) {
            long f = start + i;
            dst[i] = f >= 0 && f < w && f > oldest ? this.ring[(int) f & MASK] : 0.0F;
        }
    }

    /** Test hook: analyses everything written so far, as fast as possible (no wall clock). */
    public void analyseAllForTest() {
        int hop = this.analyser.hopFrames();
        if (this.analysedFrame < 0)
            this.analysedFrame = 0;
        while (this.analysedFrame + hop <= this.written) {
            this.analysedFrame += hop;
            fill(this.analyser.input(), this.analysedFrame);
            this.analyser.hop();
        }
    }

    public AudioAnalyser analyser() {
        return this.analyser;
    }
}
