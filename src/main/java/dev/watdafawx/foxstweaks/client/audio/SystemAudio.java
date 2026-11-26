package dev.watdafawx.foxstweaks.client.audio;

import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import net.minecraft.Util;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALC11;
import org.lwjgl.openal.ALUtil;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional capture of this PC's own audio for holograms: what the speakers play (Windows, WASAPI loopback) or any
 * input device. <b>Off by default, per player, client only.</b> Nothing is recorded, stored or sent: the samples go
 * into a ring of a few seconds and only the five levels computed from them are ever read.
 *
 * <p>The capture thread runs only while the setting is on <i>and</i> something samples the SYSTEM source (a visible
 * modulated hologram, or the settings meter), and stops five seconds after the last request. A device that
 * disappears ends the thread with a message and the source reads silence until the player picks another.
 */
public final class SystemAudio {

    /** One capture endpoint. {@code id} is what {@link AudioPrefs#deviceId()} stores. */
    public record Device(String id, String name, boolean output) {
    }

    /** A capture implementation; every method runs on the capture thread. */
    interface Backend {
        void open(String deviceId) throws Exception;

        float rate();

        /** Mono samples captured since the last call (0 when none); throws when the device is gone. */
        int read(float[] mono) throws Exception;

        void close();
    }

    private static volatile List<Device> devices = List.of();
    private static volatile boolean listing;
    private static volatile String status = "Off";
    @Nullable
    private static volatile AudioTap tap;
    @Nullable
    private static volatile Thread thread;
    private static volatile boolean stop;
    private static volatile long requested;
    private static volatile boolean failed;
    @Nullable
    private static volatile Backend fake;
    private static volatile String openedId = "";

    private SystemAudio() {
    }

    public static boolean enabled() {
        return fake != null || AudioPrefs.systemEnabled();
    }

    public static String status() {
        return enabled() ? status : "Off";
    }

    /** Last listing; call {@link #refreshDevices()} to enumerate again. */
    public static List<Device> devices() {
        if (devices.isEmpty() && !listing)
            refreshDevices();
        return devices;
    }

    /** Re-enumerates on a background thread; the list is replaced when done. */
    public static void refreshDevices() {
        if (listing)
            return;
        listing = true;
        Util.backgroundExecutor().execute(() -> {
            List<Device> out = new ArrayList<>();
            out.add(new Device("", "Default output (what the PC plays)", true));
            try {
                if (WasapiCapture.available()) {
                    out.addAll(WasapiCapture.list());
                } else {
                    for (String name : OpenAlCapture.list())
                        out.add(new Device("openal:" + name, name + " (input)", false));
                }
            } catch (Throwable t) {
                AudioHub.warnOnce("listing capture devices failed", t);
            } finally {
                devices = List.copyOf(out);
                listing = false;
            }
        });
    }

    public static String deviceName(String id) {
        for (Device d : devices)
            if (d.id().equals(id))
                return d.name();
        return id.isEmpty() ? "Default output" : "Saved device (not connected)";
    }

    /** The system tap if capture is on, starting it when needed; null when the setting is off. */
    @Nullable
    static AudioTap requestTap() {
        if (!enabled())
            return null;
        requested = System.nanoTime();
        String want = fake != null ? "fake" : AudioPrefs.deviceId();
        if (thread != null && !want.equals(openedId))
            stopCapture(); // device changed in the settings
        if (thread == null && !failed)
            start(want);
        return tap;
    }

    /** Client tick: stop capturing when nobody asked for a while, or the setting was turned off. */
    static void tick(boolean anyAudioDemand) {
        if (thread == null)
            return;
        if (!enabled() || System.nanoTime() - requested > 5_000_000_000L)
            stopCapture();
    }

    /** Lets the player retry after a lost device. */
    public static void reset() {
        stopCapture();
        failed = false;
        status = "Idle";
    }

    private static synchronized void start(String deviceId) {
        if (thread != null)
            return;
        stop = false;
        openedId = deviceId;
        Backend backend = fake != null ? fake
                : WasapiCapture.available() && !deviceId.startsWith("openal:") ? new WasapiCapture() : new OpenAlCapture();
        Thread t = new Thread(() -> run(backend, deviceId), "Fox's Tweaks audio capture");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        thread = t;
        status = "Starting...";
        t.start();
    }

    private static void run(Backend backend, String deviceId) {
        float[] buf = new float[8192];
        try {
            backend.open(deviceId);
            AudioTap t = new AudioTap(HologramAudioMod.Source.SYSTEM, null, backend.rate(), true);
            tap = t;
            status = "Listening: " + (fake != null ? "test signal" : deviceName(deviceId));
            AudioHub.info("system capture started (" + status + ", " + (int) backend.rate() + " Hz)");
            while (!stop) {
                int n = backend.read(buf);
                if (n > 0)
                    t.writeMono(buf, n);
                Thread.sleep(10);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            failed = true;
            status = "Device lost: " + e.getMessage();
            AudioHub.warnOnce("system capture stopped", e);
        } finally {
            try {
                backend.close();
            } catch (Throwable ignored) {
                // closing a vanished device can fail; nothing else to release
            }
            tap = null;
            thread = null;
            if (!failed)
                status = "Idle";
        }
    }

    private static void stopCapture() {
        Thread t = thread;
        stop = true;
        if (t != null) {
            try {
                t.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        thread = null;
        tap = null;
    }

    public static boolean running() {
        return thread != null && tap != null;
    }

    // ------------------------------------------------------------ dev hooks

    /** Replaces capture with a generator playing {@code pcm} in real time (looped), for the dev check. */
    public static void devUseFake(float[] pcm, float rate) {
        stopCapture();
        failed = false;
        fake = new FakeBackend(pcm, rate);
    }

    /** A fake device that throws after {@code okMillis}, to test the lost-device path. */
    public static void devUseFailingFake(float[] pcm, float rate, long okMillis) {
        stopCapture();
        failed = false;
        fake = new FakeBackend(pcm, rate, okMillis);
    }

    public static void devClearFake() {
        stopCapture();
        fake = null;
        failed = false;
        status = "Off";
    }

    public static boolean failed() {
        return failed;
    }

    // ------------------------------------------------------------ backends

    /** OpenAL Soft capture: recording devices only (no loopback), used where WASAPI is not available. */
    static final class OpenAlCapture implements Backend {
        private long device;
        private final float rateHz = 44100.0F;
        private ByteBuffer bytes;

        static List<String> list() {
            List<String> names = ALUtil.getStringList(0L, ALC11.ALC_CAPTURE_DEVICE_SPECIFIER);
            return names == null ? List.of() : names;
        }

        @Override
        public void open(String deviceId) {
            String name = deviceId.startsWith("openal:") ? deviceId.substring(7) : null;
            this.device = ALC11.alcCaptureOpenDevice(name, (int) this.rateHz, 0x1101 /* AL_FORMAT_MONO16 */, 44100);
            if (this.device == 0L)
                throw new IllegalStateException("cannot open capture device " + (name == null ? "(default)" : name));
            this.bytes = org.lwjgl.system.MemoryUtil.memAlloc(8192 * 2);
            ALC11.alcCaptureStart(this.device);
        }

        @Override
        public float rate() {
            return this.rateHz;
        }

        @Override
        public int read(float[] mono) {
            int avail = ALC10.alcGetInteger(this.device, ALC11.ALC_CAPTURE_SAMPLES);
            if (ALC10.alcGetError(this.device) != ALC10.ALC_NO_ERROR)
                throw new IllegalStateException("capture device stopped responding");
            int n = Math.min(avail, Math.min(mono.length, 8192));
            if (n <= 0)
                return 0;
            this.bytes.clear();
            ALC11.alcCaptureSamples(this.device, this.bytes, n);
            ShortBuffer s = this.bytes.asShortBuffer();
            for (int i = 0; i < n; i++)
                mono[i] = s.get(i) / 32768.0F;
            return n;
        }

        @Override
        public void close() {
            if (this.device != 0L) {
                ALC11.alcCaptureStop(this.device);
                ALC11.alcCaptureCloseDevice(this.device);
                this.device = 0L;
            }
            if (this.bytes != null) {
                org.lwjgl.system.MemoryUtil.memFree(this.bytes);
                this.bytes = null;
            }
        }
    }

    /** Plays a generated buffer in real time, as a capture device would deliver it. */
    static final class FakeBackend implements Backend {
        private final float[] pcm;
        private final float rateHz;
        private final long failAfterMillis;
        private long startNanos;
        private long delivered;

        FakeBackend(float[] pcm, float rate) {
            this(pcm, rate, -1);
        }

        FakeBackend(float[] pcm, float rate, long failAfterMillis) {
            this.pcm = pcm;
            this.rateHz = rate;
            this.failAfterMillis = failAfterMillis;
        }

        @Override
        public void open(String deviceId) {
            this.startNanos = System.nanoTime();
            this.delivered = 0;
        }

        @Override
        public float rate() {
            return this.rateHz;
        }

        @Override
        public int read(float[] mono) {
            long elapsed = System.nanoTime() - this.startNanos;
            if (this.failAfterMillis >= 0 && elapsed > this.failAfterMillis * 1_000_000L)
                throw new IllegalStateException("test device unplugged");
            long due = (long) (elapsed * 1.0E-9 * this.rateHz);
            int n = (int) Math.min(mono.length, due - this.delivered);
            for (int i = 0; i < n; i++)
                mono[i] = this.pcm[(int) ((this.delivered + i) % this.pcm.length)];
            this.delivered += Math.max(0, n);
            return Math.max(0, n);
        }

        @Override
        public void close() {
        }
    }
}
