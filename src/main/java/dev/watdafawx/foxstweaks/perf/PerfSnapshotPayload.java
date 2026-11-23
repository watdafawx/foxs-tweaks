package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Server -> client: rolling TPS, MSPT, CPU, heap, GC, world counts, per-dimension tick times, and recent tick sparkline. */
public record PerfSnapshotPayload(
        float[] tps,           // 5s, 10s, 1m, 5m, 15m
        float[] mspt10s,       // min, med, p95, max
        float[] mspt1m,        // min, med, p95, max
        float[] cpuSystem,     // 10s, 1m, 15m (-1 if unavailable)
        float[] cpuProcess,    // 10s, 1m, 15m (-1 if unavailable)
        int heapUsedMb,
        int heapMaxMb,
        int gcCount1m,
        int gcTimeMs1m,
        int loadedChunks,
        int loadedEntities,
        int totalDims,
        List<DimStat> dims,
        float[] recentTicksMs  // up to 32 recent tick durations in ms (oldest -> newest)
) implements CustomPacketPayload {
    public record DimStat(String name, float mspt) {
    }

    public static final int SPARKLINE_SAMPLES = 32;

    public static final Type<PerfSnapshotPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "perf_snapshot"));

    public static final StreamCodec<FriendlyByteBuf, PerfSnapshotPayload> CODEC =
            StreamCodec.of(PerfSnapshotPayload::encode, PerfSnapshotPayload::decode);

    private static void encode(FriendlyByteBuf buf, PerfSnapshotPayload p) {
        writeFloats(buf, p.tps, 5);
        writeFloats(buf, p.mspt10s, 4);
        writeFloats(buf, p.mspt1m, 4);
        writeFloats(buf, p.cpuSystem, 3);
        writeFloats(buf, p.cpuProcess, 3);
        buf.writeVarInt(p.heapUsedMb);
        buf.writeVarInt(p.heapMaxMb);
        buf.writeVarInt(p.gcCount1m);
        buf.writeVarInt(p.gcTimeMs1m);
        buf.writeVarInt(p.loadedChunks);
        buf.writeVarInt(p.loadedEntities);
        buf.writeVarInt(p.totalDims);

        int count = Math.min(p.dims.size(), 8);
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            DimStat d = p.dims.get(i);
            buf.writeUtf(d.name(), 64);
            buf.writeFloat(d.mspt());
        }

        int ticks = p.recentTicksMs == null ? 0 : Math.min(p.recentTicksMs.length, SPARKLINE_SAMPLES);
        buf.writeVarInt(ticks);
        for (int i = 0; i < ticks; i++) {
            buf.writeFloat(p.recentTicksMs[i]);
        }
    }

    private static PerfSnapshotPayload decode(FriendlyByteBuf buf) {
        float[] tps = readFloats(buf, 5);
        float[] mspt10s = readFloats(buf, 4);
        float[] mspt1m = readFloats(buf, 4);
        float[] cpuSystem = readFloats(buf, 3);
        float[] cpuProcess = readFloats(buf, 3);
        int heapUsedMb = buf.readVarInt();
        int heapMaxMb = buf.readVarInt();
        int gcCount1m = buf.readVarInt();
        int gcTimeMs1m = buf.readVarInt();
        int loadedChunks = buf.readVarInt();
        int loadedEntities = buf.readVarInt();
        int totalDims = buf.readVarInt();

        int count = Math.min(Math.max(buf.readVarInt(), 0), 8);
        List<DimStat> dims = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            dims.add(new DimStat(buf.readUtf(64), buf.readFloat()));
        }

        int ticks = Math.min(Math.max(buf.readVarInt(), 0), SPARKLINE_SAMPLES);
        float[] recentTicksMs = readFloats(buf, ticks);

        return new PerfSnapshotPayload(
                tps, mspt10s, mspt1m, cpuSystem, cpuProcess,
                heapUsedMb, heapMaxMb, gcCount1m, gcTimeMs1m,
                loadedChunks, loadedEntities, totalDims, dims, recentTicksMs);
    }

    private static void writeFloats(FriendlyByteBuf buf, float[] arr, int expected) {
        for (int i = 0; i < expected; i++) {
            buf.writeFloat(arr != null && i < arr.length ? arr[i] : 0f);
        }
    }

    private static float[] readFloats(FriendlyByteBuf buf, int count) {
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = buf.readFloat();
        }
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
