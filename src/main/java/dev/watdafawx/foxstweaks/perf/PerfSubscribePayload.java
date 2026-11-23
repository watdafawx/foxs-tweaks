package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: subscribe/unsubscribe from 1 Hz {@link PerfSnapshotPayload} updates. */
public record PerfSubscribePayload(boolean subscribed) implements CustomPacketPayload {
    public static final Type<PerfSubscribePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "perf_subscribe"));

    public static final StreamCodec<ByteBuf, PerfSubscribePayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PerfSubscribePayload::subscribed,
            PerfSubscribePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
