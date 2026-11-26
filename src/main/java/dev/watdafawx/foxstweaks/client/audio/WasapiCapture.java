package dev.watdafawx.foxstweaks.client.audio;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Guid.GUID;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.util.ArrayList;
import java.util.List;

/**
 * Windows audio capture through WASAPI, called through JNA with no native library of our own: only the system's
 * own {@code ole32}/{@code mmdevapi}. JNA and jna-platform ship with Minecraft.
 *
 * <p>Why WASAPI: it is the only way found to hear <i>what the PC plays</i> (Spotify through the speakers). OpenAL
 * Soft's capture (bundled with the game) and javax.sound both open only recording endpoints (microphones, line in,
 * virtual cables); a playback device cannot be opened for capture through either. WASAPI's shared-mode loopback
 * flag on a render endpoint gives exactly the mixed output. Recording endpoints are opened the normal way.
 *
 * <p>COM interfaces are called through their vtables by index (IMMDeviceEnumerator, IMMDeviceCollection,
 * IMMDevice, IPropertyStore, IAudioClient, IAudioCaptureClient), which is why the indices below are what they are.
 * Every call runs on the capture thread, which owns its COM apartment.
 */
final class WasapiCapture implements SystemAudio.Backend {

    private static final GUID CLSID_ENUMERATOR = new GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}");
    private static final GUID IID_ENUMERATOR = new GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}");
    private static final GUID IID_AUDIO_CLIENT = new GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}");
    private static final GUID IID_CAPTURE_CLIENT = new GUID("{C8ADBD64-E71E-48a0-A4DE-185C395CD317}");
    private static final GUID PKEY_FRIENDLY_NAME = new GUID("{a45c254e-df1c-4efd-8020-67d146a850e0}");
    private static final int CLSCTX_ALL = 23;
    private static final int E_RENDER = 0;
    private static final int E_CAPTURE = 1;
    private static final int DEVICE_STATE_ACTIVE = 1;
    private static final int AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000;
    private static final int AUDCLNT_BUFFERFLAGS_SILENT = 2;

    private Pointer client;
    private Pointer capture;
    private int channels;
    private int bits;
    private boolean isFloat;
    private float rate;

    static boolean available() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");
    }

    private static int call(Pointer obj, int idx, Object... args) {
        Pointer vt = obj.getPointer(0);
        Function f = Function.getFunction(vt.getPointer((long) idx * Native.POINTER_SIZE));
        Object[] all = new Object[args.length + 1];
        all[0] = obj;
        System.arraycopy(args, 0, all, 1, args.length);
        return f.invokeInt(all);
    }

    private static void release(Pointer obj) {
        if (obj != null)
            call(obj, 2);
    }

    private static void ok(int hr, String what) {
        if (hr < 0)
            throw new IllegalStateException(what + " failed (0x" + Integer.toHexString(hr) + ")");
    }

    private static Pointer enumerator() {
        // S_FALSE (already initialised) and RPC_E_CHANGED_MODE both leave COM usable on this thread.
        Ole32.INSTANCE.CoInitializeEx(null, 0);
        PointerByReference pe = new PointerByReference();
        ok(Ole32.INSTANCE.CoCreateInstance(CLSID_ENUMERATOR, null, CLSCTX_ALL, IID_ENUMERATOR, pe).intValue(),
                "CoCreateInstance(MMDeviceEnumerator)");
        return pe.getValue();
    }

    /** Active render endpoints (as loopback) and capture endpoints, with their friendly names. */
    static List<SystemAudio.Device> list() {
        List<SystemAudio.Device> out = new ArrayList<>();
        Pointer en = enumerator();
        try {
            for (int flow : new int[] { E_RENDER, E_CAPTURE }) {
                PointerByReference pc = new PointerByReference();
                ok(call(en, 3, flow, DEVICE_STATE_ACTIVE, pc), "EnumAudioEndpoints");
                Pointer col = pc.getValue();
                try {
                    IntByReference cnt = new IntByReference();
                    ok(call(col, 3, cnt), "GetCount");
                    for (int i = 0; i < cnt.getValue(); i++) {
                        PointerByReference pd = new PointerByReference();
                        if (call(col, 4, i, pd) < 0)
                            continue;
                        Pointer dev = pd.getValue();
                        try {
                            String id = deviceId(dev);
                            String name = friendlyName(dev);
                            boolean render = flow == E_RENDER;
                            out.add(new SystemAudio.Device((render ? "wasapi:out:" : "wasapi:in:") + id,
                                    render ? name + " (what the PC plays)" : name + " (input)", render));
                        } finally {
                            release(dev);
                        }
                    }
                } finally {
                    release(col);
                }
            }
        } finally {
            release(en);
        }
        return out;
    }

    private static String deviceId(Pointer dev) {
        PointerByReference pid = new PointerByReference();
        ok(call(dev, 5, pid), "GetId");
        String id = pid.getValue().getWideString(0);
        Ole32.INSTANCE.CoTaskMemFree(pid.getValue());
        return id;
    }

    private static String friendlyName(Pointer dev) {
        PointerByReference pps = new PointerByReference();
        if (call(dev, 4, 0, pps) < 0)
            return "Unknown device";
        Pointer store = pps.getValue();
        try {
            Memory key = new Memory(20);
            // GUID.toByteArray() is not the in-memory layout; write the structure and copy its bytes.
            PKEY_FRIENDLY_NAME.write();
            key.write(0, PKEY_FRIENDLY_NAME.getPointer().getByteArray(0, 16), 0, 16);
            key.setInt(16, 14);
            Memory pv = new Memory(24);
            pv.clear();
            if (call(store, 5, key, pv) < 0 || pv.getShort(0) != 31)
                return "Unknown device";
            return pv.getPointer(8).getWideString(0);
        } finally {
            release(store);
        }
    }

    @Override
    public void open(String deviceId) {
        Pointer en = enumerator();
        Pointer dev = null;
        boolean loopback;
        try {
            PointerByReference pd = new PointerByReference();
            if (deviceId.isEmpty()) {
                ok(call(en, 4, E_RENDER, 0, pd), "GetDefaultAudioEndpoint");
                loopback = true;
            } else {
                loopback = deviceId.startsWith("wasapi:out:");
                String raw = deviceId.substring(deviceId.indexOf(':', 7) + 1);
                ok(call(en, 5, new com.sun.jna.WString(raw), pd), "GetDevice");
            }
            dev = pd.getValue();
            PointerByReference pcl = new PointerByReference();
            ok(call(dev, 3, IID_AUDIO_CLIENT, CLSCTX_ALL, null, pcl), "Activate(IAudioClient)");
            this.client = pcl.getValue();
        } finally {
            release(dev);
            release(en);
        }
        PointerByReference pfmt = new PointerByReference();
        ok(call(this.client, 8, pfmt), "GetMixFormat");
        Pointer fmt = pfmt.getValue();
        int tag = fmt.getShort(0) & 0xFFFF;
        this.channels = Math.max(1, fmt.getShort(2));
        this.rate = fmt.getInt(4);
        this.bits = fmt.getShort(14);
        // WAVE_FORMAT_IEEE_FLOAT, or EXTENSIBLE whose sub-format GUID starts with 3 (KSDATAFORMAT_SUBTYPE_IEEE_FLOAT).
        this.isFloat = tag == 3 || (tag == 0xFFFE && fmt.getInt(24) == 3);
        // 100 ms of buffer in 100 ns units; we poll every 10 ms.
        ok(call(this.client, 3, 0, loopback ? AUDCLNT_STREAMFLAGS_LOOPBACK : 0, 1_000_000L, 0L, fmt, null),
                "IAudioClient.Initialize");
        Ole32.INSTANCE.CoTaskMemFree(fmt);
        PointerByReference pcap = new PointerByReference();
        ok(call(this.client, 14, IID_CAPTURE_CLIENT, pcap), "GetService(IAudioCaptureClient)");
        this.capture = pcap.getValue();
        ok(call(this.client, 10), "Start");
    }

    @Override
    public float rate() {
        return this.rate;
    }

    @Override
    public int read(float[] mono) {
        int total = 0;
        IntByReference next = new IntByReference();
        ok(call(this.capture, 5, next), "GetNextPacketSize");
        while (next.getValue() > 0) {
            PointerByReference data = new PointerByReference();
            IntByReference n = new IntByReference();
            IntByReference flags = new IntByReference();
            ok(call(this.capture, 3, data, n, flags, null, null), "GetBuffer");
            int frames = n.getValue();
            int room = Math.min(frames, mono.length - total);
            boolean silent = (flags.getValue() & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
            Pointer p = data.getValue();
            for (int f = 0; f < room; f++) {
                float sum = 0.0F;
                if (!silent) {
                    for (int c = 0; c < this.channels; c++) {
                        long off = ((long) f * this.channels + c) * (this.bits / 8);
                        sum += switch (this.bits) {
                            case 32 -> this.isFloat ? p.getFloat(off) : p.getInt(off) / 2147483648.0F;
                            case 16 -> p.getShort(off) / 32768.0F;
                            case 24 -> (((p.getByte(off + 2) << 16) | ((p.getByte(off + 1) & 0xFF) << 8)
                                    | (p.getByte(off) & 0xFF))) / 8388608.0F;
                            default -> 0.0F;
                        };
                    }
                }
                mono[total + f] = sum / this.channels;
            }
            total += room;
            ok(call(this.capture, 4, frames), "ReleaseBuffer");
            ok(call(this.capture, 5, next), "GetNextPacketSize");
            if (total >= mono.length)
                break;
        }
        return total;
    }

    @Override
    public void close() {
        try {
            if (this.client != null)
                call(this.client, 11);
        } catch (RuntimeException ignored) {
            // the device may already be gone
        }
        release(this.capture);
        release(this.client);
        this.capture = null;
        this.client = null;
    }
}
