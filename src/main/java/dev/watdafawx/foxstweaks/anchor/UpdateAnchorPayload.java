package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: update a Spawn Anchor dummy's enabled state, radius, Apotheosis tier, or name
 * visibility, or pick it up.
 *
 * <p>Hand-written codec: {@code StreamCodec.composite} only goes up to six components in 1.21.1.
 */
public record UpdateAnchorPayload(int entityId, boolean enabled, int radius, String tier, boolean followOwner,
                                  boolean hideName, boolean pickup) implements CustomPacketPayload {
    public static final Type<UpdateAnchorPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "update_anchor"));

    public static final StreamCodec<FriendlyByteBuf, UpdateAnchorPayload> CODEC =
            StreamCodec.of(UpdateAnchorPayload::encode, UpdateAnchorPayload::decode);

    private static void encode(FriendlyByteBuf buf, UpdateAnchorPayload p) {
        buf.writeVarInt(p.entityId);
        buf.writeBoolean(p.enabled);
        buf.writeVarInt(p.radius);
        buf.writeUtf(p.tier, 32);
        buf.writeBoolean(p.followOwner);
        buf.writeBoolean(p.hideName);
        buf.writeBoolean(p.pickup);
    }

    private static UpdateAnchorPayload decode(FriendlyByteBuf buf) {
        return new UpdateAnchorPayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readUtf(32),
                buf.readBoolean(),
                buf.readBoolean(),
                buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
