package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: update a Spawn Anchor dummy's enabled state, radius, or pick it up. */
public record UpdateAnchorPayload(int entityId, boolean enabled, int radius, boolean pickup) implements CustomPacketPayload {
    public static final Type<UpdateAnchorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "update_anchor"));

    public static final StreamCodec<ByteBuf, UpdateAnchorPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, UpdateAnchorPayload::entityId,
            ByteBufCodecs.BOOL, UpdateAnchorPayload::enabled,
            ByteBufCodecs.VAR_INT, UpdateAnchorPayload::radius,
            ByteBufCodecs.BOOL, UpdateAnchorPayload::pickup,
            UpdateAnchorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
