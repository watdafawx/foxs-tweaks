package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Lightweight rolling server performance sampler (TPS, MSPT percentiles, CPU, heap, GC, world
 * counts, per-dimension tick time). Samples ticks cheaply in the background so 1m/5m/15m history is
 * ready the instant a player holds Tab; builds and sends {@link PerfSnapshotPayload} only while at
 * least one player is subscribed.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class ServerPerfTracker {
    private static final int TICK_RING = 18_000; // 15 min @ 20 TPS
    private static final int SEC_RING = 900;     // 15 min @ 1 Hz
    private static final long NS_PER_SEC = 1_000_000_000L;

    private static final long[] tickDurationNs = new long[TICK_RING];
    private static final long[] tickEndNs = new long[TICK_RING];
    private static int tickHead;
    private static int tickCount;
    private static long tickStartNs;
    private static long serverStartNs;

    private static final float[] cpuSysRing = new float[SEC_RING];
    private static final float[] cpuProcRing = new float[SEC_RING];
    private static final int[] gcCountDeltaRing = new int[SEC_RING];
    private static final int[] gcTimeDeltaRing = new int[SEC_RING];
    private static int secHead;
    private static int secCount;
    private static long lastSecSampleNs;
    private static long lastTotalGcCount;
    private static long lastTotalGcTimeMs;

    private static final Set<UUID> SUBSCRIBERS = new HashSet<>();
    private static final CpuSampler CPU = new CpuSampler();
    private static long[] scratch = new long[1280];

    private ServerPerfTracker() {
    }

    public static void setSubscribed(ServerPlayer player, boolean subscribed) {
        if (subscribed) {
            SUBSCRIBERS.add(player.getUUID());
            MinecraftServer server = player.getServer();
            if (server != null && player.connection.hasChannel(PerfSnapshotPayload.TYPE)) {
                PacketDistributor.sendToPlayer(player, buildSnapshot(server));
            }
        } else {
            SUBSCRIBERS.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public static void onServerStarting(ServerStartingEvent event) {
        reset();
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SUBSCRIBERS.clear();
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIBERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTickPre(ServerTickEvent.Pre event) {
        tickStartNs = System.nanoTime();
    }

    @SubscribeEvent
    public static void onServerTickPost(ServerTickEvent.Post event) {
        long now = System.nanoTime();
        if (tickStartNs == 0L)
            return;

        if (serverStartNs == 0L)
            serverStartNs = now;

        long dur = Math.max(0L, now - tickStartNs);
        tickDurationNs[tickHead] = dur;
        tickEndNs[tickHead] = now;
        tickHead = (tickHead + 1) % TICK_RING;
        if (tickCount < TICK_RING)
            tickCount++;

        if (lastSecSampleNs == 0L) {
            lastSecSampleNs = now;
            recordGcBaseline();
            return;
        }

        if (now - lastSecSampleNs >= NS_PER_SEC) {
            lastSecSampleNs = now;
            samplePerSecond();

            if (!SUBSCRIBERS.isEmpty()) {
                MinecraftServer server = event.getServer();
                if (!ServerConfig.perfOverlayEnabled()) {
                    SUBSCRIBERS.clear();
                    return;
                }
                PerfSnapshotPayload snapshot = buildSnapshot(server);
                boolean opOnly = ServerConfig.perfOverlayOpOnly();
                var it = SUBSCRIBERS.iterator();
                while (it.hasNext()) {
                    ServerPlayer player = server.getPlayerList().getPlayer(it.next());
                    if (player == null) {
                        it.remove();
                        continue;
                    }
                    if (opOnly && !player.hasPermissions(2)) {
                        it.remove();
                        continue;
                    }
                    if (player.connection.hasChannel(PerfSnapshotPayload.TYPE)) {
                        PacketDistributor.sendToPlayer(player, snapshot);
                    }
                }
            }
        }
    }

    private static void reset() {
        tickHead = 0;
        tickCount = 0;
        tickStartNs = 0L;
        serverStartNs = System.nanoTime();
        secHead = 0;
        secCount = 0;
        lastSecSampleNs = 0L;
        SUBSCRIBERS.clear();
        Arrays.fill(cpuSysRing, -1f);
        Arrays.fill(cpuProcRing, -1f);
        Arrays.fill(gcCountDeltaRing, 0);
        Arrays.fill(gcTimeDeltaRing, 0);
        CPU.warmup();
        recordGcBaseline();
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

    private static PerfSnapshotPayload buildSnapshot(MinecraftServer server) {
        long now = System.nanoTime();
        float[] tps = new float[] {
                computeTps(now, 5),
                computeTps(now, 10),
                computeTps(now, 60),
                computeTps(now, 300),
                computeTps(now, 900)
        };
        float[] mspt10s = computeMspt(now, 10);
        float[] mspt1m = computeMspt(now, 60);
        float[] cpuSys = new float[] {
                averageRing(cpuSysRing, 10),
                averageRing(cpuSysRing, 60),
                averageRing(cpuSysRing, 900)
        };
        float[] cpuProc = new float[] {
                averageRing(cpuProcRing, 10),
                averageRing(cpuProcRing, 60),
                averageRing(cpuProcRing, 900)
        };

        Runtime rt = Runtime.getRuntime();
        int heapUsedMb = (int) ((rt.totalMemory() - rt.freeMemory()) >> 20);
        int heapMaxMb = (int) (rt.maxMemory() >> 20);

        int gcCount1m = sumRing(gcCountDeltaRing, 60);
        int gcTimeMs1m = sumRing(gcTimeDeltaRing, 60);

        int chunks = 0;
        int entities = 0;
        List<PerfSnapshotPayload.DimStat> dims = new ArrayList<>();

        for (ServerLevel level : server.getAllLevels()) {
            chunks += level.getChunkSource().getLoadedChunksCount();
            for (var ignored : level.getAllEntities()) {
                entities++;
            }
            long[] times = server.getTickTime(level.dimension());
            float avgMs = 0f;
            if (times != null && times.length > 0) {
                long sum = 0L;
                for (long t : times) sum += t;
                avgMs = (float) (sum / (double) times.length / 1_000_000.0);
            }
            String dimPath = level.dimension().location().getPath();
            if (dimPath.startsWith("the_"))
                dimPath = dimPath.substring(4);
            dims.add(new PerfSnapshotPayload.DimStat(dimPath, avgMs));
        }

        dims.sort(Comparator.comparingDouble(PerfSnapshotPayload.DimStat::mspt).reversed());
        int totalDims = dims.size();
        List<PerfSnapshotPayload.DimStat> activeDims = new ArrayList<>(4);
        for (PerfSnapshotPayload.DimStat d : dims) {
            if (activeDims.size() >= 4)
                break;
            if (d.mspt() >= 0.05f || activeDims.isEmpty()) {
                activeDims.add(d);
            }
        }

        int sparkN = Math.min(tickCount, PerfSnapshotPayload.SPARKLINE_SAMPLES);
        float[] recentTicksMs = new float[sparkN];
        for (int i = 0; i < sparkN; i++) {
            int idx = (tickHead - sparkN + i + TICK_RING) % TICK_RING;
            recentTicksMs[i] = tickDurationNs[idx] / 1_000_000.0f;
        }

        return new PerfSnapshotPayload(
                tps, mspt10s, mspt1m, cpuSys, cpuProc,
                heapUsedMb, heapMaxMb, gcCount1m, gcTimeMs1m,
                chunks, entities, totalDims, activeDims, recentTicksMs);
    }

    private static float computeTps(long nowNs, int windowSec) {
        if (tickCount == 0)
            return 20.0f;

        long cutoff = nowNs - windowSec * NS_PER_SEC;
        int count = 0;
        long oldest = nowNs;
        for (int i = 0; i < tickCount; i++) {
            int idx = (tickHead - 1 - i + TICK_RING) % TICK_RING;
            long end = tickEndNs[idx];
            if (end < cutoff)
                break;
            count++;
            oldest = end;
        }
        if (count == 0)
            return 0f;

        double uptimeSec = Math.max(0.25, (nowNs - serverStartNs) / 1_000_000_000.0);
        double spanSec = Math.min(windowSec, Math.max(uptimeSec, (nowNs - oldest) / 1_000_000_000.0));
        if (spanSec < 0.25)
            return 20.0f;
        return (float) Math.min(20.0, count / spanSec);
    }

    private static float[] computeMspt(long nowNs, int windowSec) {
        if (tickCount == 0)
            return new float[] { 0f, 0f, 0f, 0f };

        long cutoff = nowNs - windowSec * NS_PER_SEC;
        int n = 0;
        for (int i = 0; i < tickCount; i++) {
            int idx = (tickHead - 1 - i + TICK_RING) % TICK_RING;
            if (tickEndNs[idx] < cutoff)
                break;
            if (n >= scratch.length)
                scratch = Arrays.copyOf(scratch, scratch.length * 2);
            scratch[n++] = tickDurationNs[idx];
        }
        if (n == 0)
            return new float[] { 0f, 0f, 0f, 0f };

        Arrays.sort(scratch, 0, n);
        float min = scratch[0] / 1_000_000.0f;
        float med = scratch[n / 2] / 1_000_000.0f;
        float p95 = scratch[Math.min(n - 1, (int) ((n - 1) * 0.95))] / 1_000_000.0f;
        float max = scratch[n - 1] / 1_000_000.0f;
        return new float[] { min, med, p95, max };
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

    private static int sumRing(int[] ring, int windowSec) {
        int n = Math.min(secCount, windowSec);
        int sum = 0;
        for (int i = 0; i < n; i++) {
            int idx = (secHead - 1 - i + SEC_RING) % SEC_RING;
            sum += ring[idx];
        }
        return sum;
    }
}
