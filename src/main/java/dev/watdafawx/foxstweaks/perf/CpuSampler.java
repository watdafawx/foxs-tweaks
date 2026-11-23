package dev.watdafawx.foxstweaks.perf;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Reads system and process CPU load (0..100%) on a single low-priority background daemon thread
 * once per second, so {@link #sample()} never blocks the server or client tick thread on Windows
 * JMX/PDH queries.
 */
public final class CpuSampler {
    private static final MBeanServer MBEAN = ManagementFactory.getPlatformMBeanServer();
    private static final ObjectName OS_NAME = initName();
    private static final int CPUS = Math.max(1, Runtime.getRuntime().availableProcessors());

    private static volatile float latestSys = -1f;
    private static volatile float latestProc = -1f;
    private static volatile boolean started;

    private static long lastWallNs;
    private static long lastProcCpuNs = -1L;

    public CpuSampler() {
        ensureStarted();
    }

    public void warmup() {
        ensureStarted();
    }

    /** Non-blocking: returns the latest background-sampled {@code [systemPct, processPct]}. */
    public float[] sample() {
        ensureStarted();
        return new float[] { latestSys, latestProc };
    }

    private static void ensureStarted() {
        if (started)
            return;
        synchronized (CpuSampler.class) {
            if (started)
                return;
            started = true;
            Thread t = new Thread(CpuSampler::loop, "foxstweaks-cpu-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        }
    }

    private static void loop() {
        lastWallNs = System.nanoTime();
        lastProcCpuNs = readLong("ProcessCpuTime");
        // Prime Windows PDH counters once off-thread.
        readDouble("CpuLoad");
        readDouble("ProcessCpuLoad");

        while (true) {
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                return;
            }
            pollOnce();
        }
    }

    private static void pollOnce() {
        double sys = readDouble("CpuLoad");
        if (sys < 0.0)
            sys = readDouble("SystemCpuLoad");

        double proc = readDouble("ProcessCpuLoad");
        long now = System.nanoTime();
        long procCpuNs = readLong("ProcessCpuTime");
        if (proc < 0.0 && procCpuNs >= 0L && lastProcCpuNs >= 0L && now > lastWallNs) {
            long dCpu = procCpuNs - lastProcCpuNs;
            long dWall = now - lastWallNs;
            if (dCpu >= 0L && dWall > 0L) {
                proc = Math.min(1.0, (double) dCpu / ((double) dWall * CPUS));
            }
        }
        lastWallNs = now;
        if (procCpuNs >= 0L)
            lastProcCpuNs = procCpuNs;

        latestSys = sys >= 0.0 ? (float) Math.min(100.0, sys * 100.0) : -1f;
        latestProc = proc >= 0.0 ? (float) Math.min(100.0, proc * 100.0) : -1f;
    }

    private static ObjectName initName() {
        try {
            return ObjectName.getInstance("java.lang:type=OperatingSystem");
        } catch (Exception e) {
            return null;
        }
    }

    private static double readDouble(String attr) {
        if (OS_NAME == null)
            return -1.0;
        try {
            Object v = MBEAN.getAttribute(OS_NAME, attr);
            if (v instanceof Number n) {
                double d = n.doubleValue();
                return Double.isNaN(d) ? -1.0 : d;
            }
        } catch (Exception ignored) {
        }
        return -1.0;
    }

    private static long readLong(String attr) {
        if (OS_NAME == null)
            return -1L;
        try {
            Object v = MBEAN.getAttribute(OS_NAME, attr);
            if (v instanceof Number n)
                return n.longValue();
        } catch (Exception ignored) {
        }
        return -1L;
    }
}
