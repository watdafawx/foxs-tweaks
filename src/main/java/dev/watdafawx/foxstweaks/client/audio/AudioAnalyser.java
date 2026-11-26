package dev.watdafawx.foxstweaks.client.audio;

/**
 * Turns mono PCM into the five signals holograms react to, at a fixed 60 Hz hop: level (RMS), bass (20-250 Hz),
 * mid (250 Hz - 4 kHz), high (4-16 kHz) from a 1024-point Hann-windowed FFT, and a beat/onset envelope from
 * spectral flux over an adaptive threshold.
 *
 * <p>Every value is normalised 0..1 by its own automatic gain (a peak follower that halves in about four seconds),
 * so a quiet song and a loud one both use the whole range. A band's gain may not lift it more than ~12x above the
 * overall level, otherwise a pure bass tone would light up "high" from its own FFT leakage. Below about -80 dBFS
 * everything reads 0, so silence is silence rather than amplified noise.
 *
 * <p>Allocation-free after construction; one instance per source, used from one thread (the render thread).
 */
public final class AudioAnalyser {

    public static final int FFT_SIZE = 1024;
    public static final double HOP_HZ = 60.0;

    private static final int HALF = FFT_SIZE / 2;
    private static final float SILENCE_RMS = 1.0E-4F;
    private static final float LEVEL_FLOOR = 0.003F;
    private static final float BAND_RELATIVE_FLOOR = 0.08F;
    private static final float BEAT_DECAY_SECONDS = 0.12F;
    private static final double MIN_ONSET_GAP = 0.2;

    private final float[] window = new float[FFT_SIZE];
    private final float[] re = new float[FFT_SIZE];
    private final float[] im = new float[FFT_SIZE];
    private final float[] mag = new float[HALF];
    private final float[] prevMag = new float[HALF];
    private final float[] samples = new float[FFT_SIZE];
    private final int[] bitrev = new int[FFT_SIZE];
    private final float[] cos = new float[HALF];
    private final float[] sin = new float[HALF];
    private final float windowSum;

    private final float[] fluxHistory = new float[48];
    private int fluxCount;
    private float flux1;
    private float flux2;

    private final double[] onsets = new double[24];
    private int onsetCount;
    private double lastOnset = -10.0;

    private float levelPeak;
    private float bassPeak;
    private float midPeak;
    private float highPeak;

    private float sampleRate;
    private double time;
    private float beatEnv;

    /** Latest normalised outputs, 0..1. */
    public float level;
    public float bass;
    public float mid;
    public float high;
    public float beat;
    /** Raw RMS of the last hop, for meters. */
    public float rawRms;
    /** Total onsets seen; dev checks. */
    public int onsetTotal;

    public AudioAnalyser(float sampleRate) {
        this.sampleRate = sampleRate;
        float sum = 0.0F;
        for (int i = 0; i < FFT_SIZE; i++) {
            this.window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / (FFT_SIZE - 1)));
            sum += this.window[i];
        }
        this.windowSum = sum;
        int bits = Integer.numberOfTrailingZeros(FFT_SIZE);
        for (int i = 0; i < FFT_SIZE; i++)
            this.bitrev[i] = Integer.reverse(i) >>> (32 - bits);
        for (int i = 0; i < HALF; i++) {
            this.cos[i] = (float) Math.cos(-2.0 * Math.PI * i / FFT_SIZE);
            this.sin[i] = (float) Math.sin(-2.0 * Math.PI * i / FFT_SIZE);
        }
    }

    public void setSampleRate(float sampleRate) {
        if (sampleRate > 1000.0F)
            this.sampleRate = sampleRate;
    }

    public float sampleRate() {
        return this.sampleRate;
    }

    /** Frames between two hops at the current sample rate. */
    public int hopFrames() {
        return Math.max(1, (int) Math.round(this.sampleRate / HOP_HZ));
    }

    /** Analysis clock: seconds of audio analysed so far. */
    public double time() {
        return this.time;
    }

    /** The buffer {@link #hop()} reads; fill all {@link #FFT_SIZE} samples (oldest first) before calling it. */
    public float[] input() {
        return this.samples;
    }

    /** Silence: outputs go to 0 at once (the modulations' own release does the fading). */
    public void hopSilent() {
        this.time += 1.0 / HOP_HZ;
        this.level = this.bass = this.mid = this.high = 0.0F;
        this.rawRms = 0.0F;
        decayPeaks();
        java.util.Arrays.fill(this.prevMag, 0.0F);
        // Silence is "no flux", and it still has to run the peak picker: a click followed by silence is only
        // recognised one hop later, here.
        onset(0.0F);
    }

    /** Analyses {@link #input()} as the next 1/60 s hop. */
    public void hop() {
        this.time += 1.0 / HOP_HZ;
        float[] x = this.samples;
        double sq = 0.0;
        for (int i = FFT_SIZE - hopFrames(); i < FFT_SIZE; i++)
            sq += (double) x[i] * x[i];
        float rms = (float) Math.sqrt(sq / Math.min(FFT_SIZE, hopFrames()));
        this.rawRms = rms;
        if (rms < SILENCE_RMS) {
            this.time -= 1.0 / HOP_HZ;
            hopSilent();
            return;
        }

        for (int i = 0; i < FFT_SIZE; i++) {
            int j = this.bitrev[i];
            this.re[j] = x[i] * this.window[i];
            this.im[j] = 0.0F;
        }
        fft();
        float norm = 2.0F / this.windowSum;
        float binHz = this.sampleRate / FFT_SIZE;
        double bassE = 0.0, midE = 0.0, highE = 0.0;
        float flux = 0.0F;
        for (int k = 1; k < HALF; k++) {
            float a = (float) Math.sqrt(this.re[k] * this.re[k] + this.im[k] * this.im[k]) * norm;
            float hz = k * binHz;
            double e = (double) a * a;
            if (hz < 250.0F) {
                if (hz >= 20.0F)
                    bassE += e;
            } else if (hz < 4000.0F) {
                midE += e;
            } else if (hz < 16000.0F) {
                highE += e;
            }
            // Log magnitude flux: onsets in quiet passages count as much as in loud ones.
            float m = (float) Math.log1p(a * 1000.0F);
            float d = m - this.prevMag[k];
            if (d > 0.0F)
                flux += d;
            this.prevMag[k] = m;
            this.mag[k] = a;
        }
        float bassA = (float) Math.sqrt(bassE);
        float midA = (float) Math.sqrt(midE);
        float highA = (float) Math.sqrt(highE);

        decayPeaks();
        this.levelPeak = Math.max(this.levelPeak, rms);
        this.bassPeak = Math.max(this.bassPeak, bassA);
        this.midPeak = Math.max(this.midPeak, midA);
        this.highPeak = Math.max(this.highPeak, highA);
        float levelRef = Math.max(this.levelPeak, LEVEL_FLOOR);
        // A sine at full scale has RMS 0.707 and band amplitude ~1; scale bands into the level's units.
        float bandFloor = Math.max(LEVEL_FLOOR, BAND_RELATIVE_FLOOR * levelRef * 1.41F);
        this.level = clamp01(rms / levelRef);
        this.bass = clamp01(bassA / Math.max(this.bassPeak, bandFloor));
        this.mid = clamp01(midA / Math.max(this.midPeak, bandFloor));
        this.high = clamp01(highA / Math.max(this.highPeak, bandFloor));

        onset(flux);
    }

    private void onset(float flux) {
        // Peak picking with one hop of delay: the previous hop is an onset when it beat the adaptive threshold and
        // both of its neighbours.
        float mean = 0.0F;
        int n = Math.min(this.fluxCount, this.fluxHistory.length);
        for (int i = 0; i < n; i++)
            mean += this.fluxHistory[i];
        mean = n == 0 ? 0.0F : mean / n;
        float var = 0.0F;
        for (int i = 0; i < n; i++) {
            float d = this.fluxHistory[i] - mean;
            var += d * d;
        }
        float std = n == 0 ? 0.0F : (float) Math.sqrt(var / n);
        float threshold = mean + 1.5F * std + 0.5F;
        boolean isOnset = n >= 8 && this.flux1 > threshold && this.flux1 >= flux && this.flux1 > this.flux2
                && this.time - this.lastOnset >= MIN_ONSET_GAP;
        this.fluxHistory[this.fluxCount++ % this.fluxHistory.length] = flux;
        this.flux2 = this.flux1;
        this.flux1 = flux;

        this.beatEnv *= decay(BEAT_DECAY_SECONDS);
        if (isOnset) {
            double at = this.time - 1.0 / HOP_HZ;
            this.lastOnset = at;
            this.onsets[this.onsetCount++ % this.onsets.length] = at;
            this.onsetTotal++;
            this.beatEnv = 1.0F;
        }
        this.beat = this.beatEnv < 0.01F ? 0.0F : this.beatEnv;
    }

    /** Median inter-onset interval as BPM, folded into 70..180; NaN with too few onsets. */
    public double bpm() {
        int n = Math.min(this.onsetCount, this.onsets.length);
        if (n < 4)
            return Double.NaN;
        double[] t = new double[n];
        int start = this.onsetCount - n;
        for (int i = 0; i < n; i++)
            t[i] = this.onsets[(start + i) % this.onsets.length];
        double[] gaps = new double[n - 1];
        for (int i = 1; i < n; i++)
            gaps[i - 1] = t[i] - t[i - 1];
        java.util.Arrays.sort(gaps);
        double bpm = 60.0 / gaps[gaps.length / 2];
        while (bpm < 70.0)
            bpm *= 2.0;
        while (bpm > 180.0)
            bpm /= 2.0;
        return bpm;
    }

    private void decayPeaks() {
        // Halves in ~4 s at 60 hops a second.
        float d = 0.99712F;
        this.levelPeak *= d;
        this.bassPeak *= d;
        this.midPeak *= d;
        this.highPeak *= d;
    }

    private static float decay(float seconds) {
        return (float) Math.exp(-1.0 / (HOP_HZ * seconds));
    }

    private static float clamp01(float v) {
        return v < 0.0F ? 0.0F : Math.min(v, 1.0F);
    }

    /** In-place iterative radix-2 FFT on {@link #re}/{@link #im} (already bit-reversed). */
    private void fft() {
        for (int size = 2; size <= FFT_SIZE; size <<= 1) {
            int half = size >> 1;
            int step = FFT_SIZE / size;
            for (int i = 0; i < FFT_SIZE; i += size) {
                for (int j = 0; j < half; j++) {
                    float wr = this.cos[j * step];
                    float wi = this.sin[j * step];
                    int a = i + j;
                    int b = a + half;
                    float tr = wr * this.re[b] - wi * this.im[b];
                    float ti = wr * this.im[b] + wi * this.re[b];
                    this.re[b] = this.re[a] - tr;
                    this.im[b] = this.im[a] - ti;
                    this.re[a] += tr;
                    this.im[a] += ti;
                }
            }
        }
    }
}
