package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;

/**
 * Client-side performance sampler + server-snapshot subscription manager.
 *
 * <p>Tracks client FPS, 1% low FPS, frame time percentiles (min/med/p95/max over 10s), client heap,
 * 1m GC pauses, client process/system CPU, GPU utilization (when available), and ping. Also toggles
 * the optional server subscription whenever Tab is held or the mini-HUD is pinned.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class ClientPerfTracker {
    private static final int FRAME_RING = 4096;
    private static final int SEC_RING = 60;
    private static final long NS_PER_SEC = 1_000_000_000L;

    private static final long[] frameDurationNs = new long[FRAME_RING];
    private static final long[] frameEndNs = new long[FRAME_RING];
    private static int frameHead;
    private static int frameCount;
    private static long lastFrameNs;

    private static final float[] cpuSysRing = new float[SEC_RING];
    private static final float[] cpuProcRing = new float[SEC_RING];
    private static final int[] gcCountDeltaRing = new int[SEC_RING];
    private static final int[] gcTimeDeltaRing = new int[SEC_RING];
    private static int secHead;
    private static int secCount;
    private static long lastSecSampleNs;
    private static long lastTotalGcCount;
    private static long lastTotalGcTimeMs;

    private static long[] frameScratch = new long[1024];
    private static final CpuSampler CPU = new CpuSampler();
    private static long lastStatsComputeNs;
    private static ClientStats cachedClientStats = ClientStats.EMPTY;

    private static volatile PerfSnapshotPayload serverSnapshot;
    private static boolean subscribedToServer;
    private static boolean miniHudInitialized;
    private static boolean miniHudPinned;

    /** Dev-only hook for {@code PerfShot} to simulate holding Tab on an unfocused window. */
    public static boolean forceTabForTest;

    public record ClientStats(
            int fps,
            int fpsOnePercentLow,
            float frameMinMs,
            float frameMedMs,
            float frameP95Ms,
            float frameMaxMs,
            float cpuProc10s,
            float cpuSys10s,
            float gpuPercent,
            int heapUsedMb,
            int heapMaxMb,
            int gcCount1m,
            int gcTimeMs1m,
            int pingMs,
            boolean localServer,
            int renderDistance,
            int clientEntities
    ) {
        static final ClientStats EMPTY = new ClientStats(0, 0, 0, 0, 0, 0, -1, -1, -1, 0, 0, 0, 0, -1, true, 0, 0);
    }

    private ClientPerfTracker() {
    }

    public static void onSnapshot(PerfSnapshotPayload payload) {
        serverSnapshot = payload;
    }

    public static PerfSnapshotPayload serverSnapshot() {
        return serverSnapshot;
    }

    public static boolean isTabOverlayVisible() {
        if (!Config.enabled(Config.PERF_TAB_OVERLAY))
            return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return false;
        if (forceTabForTest)
            return true;
        return mc.screen == null && mc.options.keyPlayerList.isDown();
    }

    public static boolean isMiniHudVisible() {
        ensureMiniHudInit();
        if (!miniHudPinned)
            return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.level != null && !mc.options.hideGui && !mc.getDebugOverlay().showDebugScreen();
    }

    public static void setMiniHudPinned(boolean pinned) {
        miniHudInitialized = true;
        miniHudPinned = pinned;
    }

    public static boolean isMiniHudPinned() {
        ensureMiniHudInit();
        return miniHudPinned;
    }

    private static void ensureMiniHudInit() {
        if (!miniHudInitialized && Config.SPEC.isLoaded()) {
            miniHudInitialized = true;
            miniHudPinned = Config.PERF_MINI_HUD.get();
        }
    }

    public static ClientStats clientStats() {
        long now = System.nanoTime();
        if (now - lastStatsComputeNs >= 250_000_000L) {
            lastStatsComputeNs = now;
            cachedClientStats = computeClientStats(now);
        }
        return cachedClientStats;
    }

    /** Copies the most recent {@code maxSamples} frame times in ms (oldest -> newest) for the sparkline. */
    public static float[] recentFrameTimesMs(int maxSamples) {
        int n = Math.min(frameCount, maxSamples);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            int idx = (frameHead - n + i + FRAME_RING) % FRAME_RING;
            out[i] = frameDurationNs[idx] / 1_000_000.0f;
        }
        return out;
    }

    @SubscribeEvent
    public static void onRenderFramePost(RenderFrameEvent.Post event) {
        long now = System.nanoTime();
        if (lastFrameNs != 0L) {
            long dur = Math.max(0L, now - lastFrameNs);
            // Ignore giant pause spikes (>2s) from alt-tab / world load so p95/1% low stays meaningful
            if (dur < 2_000_000_000L) {
                frameDurationNs[frameHead] = dur;
                frameEndNs[frameHead] = now;
                frameHead = (frameHead + 1) % FRAME_RING;
                if (frameCount < FRAME_RING)
                    frameCount++;
            }
        }
        lastFrameNs = now;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ensureMiniHudInit();

        while (FoxsTweaksKeys.TOGGLE_PERF_HUD.consumeClick()) {
            miniHudPinned = !miniHudPinned;
        }

        long now = System.nanoTime();
        if (lastSecSampleNs == 0L) {
            lastSecSampleNs = now;
            Arrays.fill(cpuSysRing, -1f);
            Arrays.fill(cpuProcRing, -1f);
            recordGcBaseline();
        } else if (now - lastSecSampleNs >= NS_PER_SEC) {
            lastSecSampleNs = now;
            samplePerSecond();
        }

        boolean wantServer = mc.player != null && mc.getConnection() != null
                && (isTabOverlayVisible() || isMiniHudVisible());
        if (wantServer != subscribedToServer) {
            ClientPacketListener conn = mc.getConnection();
            if (conn != null && conn.hasChannel(PerfSubscribePayload.TYPE)) {
                PacketDistributor.sendToServer(new PerfSubscribePayload(wantServer));
                subscribedToServer = wantServer;
            } else if (!wantServer) {
                subscribedToServer = false;
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        serverSnapshot = null;
        subscribedToServer = false;
    }

    private static void recordGcBaseline() {
        long count = 0;
        long time = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) count += c;
            if (t > 0) time += t;
        }
        lastTotalGcCount = count;
        lastTotalGcTimeMs = time;
    }

    private static void samplePerSecond() {
        float[] cpu = CPU.sample();
        float sys = cpu[0];
        float proc = cpu[1];

        long gcCount = 0;
        long gcTime = 0;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long c = bean.getCollectionCount();
            long t = bean.getCollectionTime();
            if (c > 0) gcCount += c;
            if (t > 0) gcTime += t;
        }
        int dCount = (int) Math.max(0L, gcCount - lastTotalGcCount);
        int dTime = (int) Math.max(0L, gcTime - lastTotalGcTimeMs);
        lastTotalGcCount = gcCount;
        lastTotalGcTimeMs = gcTime;

        cpuSysRing[secHead] = sys;
        cpuProcRing[secHead] = proc;
        gcCountDeltaRing[secHead] = dCount;
        gcTimeDeltaRing[secHead] = dTime;
        secHead = (secHead + 1) % SEC_RING;
        if (secCount < SEC_RING)
            secCount++;
    }

    private static ClientStats computeClientStats(long nowNs) {
        Minecraft mc = Minecraft.getInstance();
        int fps = mc.getFps();

        long cutoff = nowNs - 10L * NS_PER_SEC;
        int n = 0;
        for (int i = 0; i < frameCount; i++) {
            int idx = (frameHead - 1 - i + FRAME_RING) % FRAME_RING;
            if (frameEndNs[idx] < cutoff)
                break;
            if (n >= frameScratch.length)
                frameScratch = Arrays.copyOf(frameScratch, frameScratch.length * 2);
            frameScratch[n++] = frameDurationNs[idx];
        }

        float minMs = 0f, medMs = 0f, p95Ms = 0f, maxMs = 0f;
        int onePercentLow = fps;
        if (n > 0) {
            Arrays.sort(frameScratch, 0, n);
            minMs = frameScratch[0] / 1_000_000.0f;
            medMs = frameScratch[n / 2] / 1_000_000.0f;
            p95Ms = frameScratch[Math.min(n - 1, (int) ((n - 1) * 0.95))] / 1_000_000.0f;
            maxMs = frameScratch[n - 1] / 1_000_000.0f;
            float p99Ms = frameScratch[Math.min(n - 1, (int) ((n - 1) * 0.99))] / 1_000_000.0f;
            if (p99Ms > 0.01f) {
                onePercentLow = Math.max(1, Math.round(1000.0f / p99Ms));
            }
        }

        Runtime rt = Runtime.getRuntime();
        int heapUsedMb = (int) ((rt.totalMemory() - rt.freeMemory()) >> 20);
        int heapMaxMb = (int) (rt.maxMemory() >> 20);

        int gcCount1m = 0;
        int gcTimeMs1m = 0;
        int gcN = Math.min(secCount, SEC_RING);
        for (int i = 0; i < gcN; i++) {
            int idx = (secHead - 1 - i + SEC_RING) % SEC_RING;
            gcCount1m += gcCountDeltaRing[idx];
            gcTimeMs1m += gcTimeDeltaRing[idx];
        }

        float cpuProc10s = averageRing(cpuProcRing, 10);
        float cpuSys10s = averageRing(cpuSysRing, 10);
        double gpuRaw = mc.getGpuUtilization();
        float gpuPercent = gpuRaw > 0.0 ? (float) Math.min(100.0, gpuRaw) : -1f;

        boolean local = mc.isLocalServer();
        int ping = -1;
        if (!local && mc.player != null && mc.getConnection() != null) {
            PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
            if (info != null) {
                ping = info.getLatency();
            }
        }

        int renderDist = mc.options.getEffectiveRenderDistance();
        int clientEntities = mc.level != null ? mc.level.getEntityCount() : 0;

        return new ClientStats(
                fps, onePercentLow, minMs, medMs, p95Ms, maxMs,
                cpuProc10s, cpuSys10s, gpuPercent,
                heapUsedMb, heapMaxMb, gcCount1m, gcTimeMs1m,
                ping, local, renderDist, clientEntities);
    }

    private static float averageRing(float[] ring, int windowSec) {
        int n = Math.min(secCount, windowSec);
        if (n == 0)
            return -1f;
        double sum = 0;
        int valid = 0;
        for (int i = 0; i < n; i++) {
            int idx = (secHead - 1 - i + SEC_RING) % SEC_RING;
            float v = ring[idx];
            if (v >= 0f) {
                sum += v;
                valid++;
            }
        }
        return valid == 0 ? -1f : (float) (sum / valid);
    }
}
