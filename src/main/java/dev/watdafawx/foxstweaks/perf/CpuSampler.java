package dev.watdafawx.foxstweaks.perf;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;

/**
 * Reads system and process CPU load (0..100%) via JMX {@code java.lang:type=OperatingSystem},
 * avoiding direct {@code com.sun.management} module visibility issues under ModLauncher and
 * falling back to {@code ProcessCpuTime} deltas when Windows PDH counters return {@code -1}.
 */
public final class CpuSampler {
    private static final MBeanServer MBEAN = ManagementFactory.getPlatformMBeanServer();
    private static final ObjectName OS_NAME = initName();
    private static final int CPUS = Math.max(1, Runtime.getRuntime().availableProcessors());

    private long lastWallNs;
    private long lastProcCpuNs = -1L;

    public CpuSampler() {
        warmup();
    }

    public void warmup() {
        lastWallNs = System.nanoTime();
        lastProcCpuNs = readLong("ProcessCpuTime");
        // Prime PDH counters on Windows so subsequent reads are non-negative.
        readDouble("CpuLoad");
        readDouble("ProcessCpuLoad");
    }

    /** Returns {@code [systemPct, processPct]}; each entry is {@code -1f} if unavailable. */
    public float[] sample() {
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

        float sysPct = sys >= 0.0 ? (float) Math.min(100.0, sys * 100.0) : -1f;
        float procPct = proc >= 0.0 ? (float) Math.min(100.0, proc * 100.0) : -1f;
        return new float[] { sysPct, procPct };
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
