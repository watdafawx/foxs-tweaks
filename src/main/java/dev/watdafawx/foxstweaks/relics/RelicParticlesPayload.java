package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: toggle {@link dev.watdafawx.foxstweaks.FoxsTweaksComponents#HIDE_RELIC_PARTICLES} on a relic slot. */
public record RelicParticlesPayload(int container, int slot, boolean hidden) implements CustomPacketPayload {
    public static final Type<RelicParticlesPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "relic_particles"));

    public static final StreamCodec<ByteBuf, RelicParticlesPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, RelicParticlesPayload::container,
            ByteBufCodecs.INT, RelicParticlesPayload::slot,
            ByteBufCodecs.BOOL, RelicParticlesPayload::hidden,
            RelicParticlesPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
