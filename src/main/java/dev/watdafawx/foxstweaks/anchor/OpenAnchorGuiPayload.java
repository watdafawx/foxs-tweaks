package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: open {@code SpawnAnchorScreen} for a placed Spawn Anchor dummy. */
public record OpenAnchorGuiPayload(
        int entityId,
        boolean enabled,
        int radius,
        int maxRadius,
        int blockX,
        int blockY,
        int blockZ,
        String dimension
) implements CustomPacketPayload {
    public static final Type<OpenAnchorGuiPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "open_anchor_gui"));

    public static final StreamCodec<FriendlyByteBuf, OpenAnchorGuiPayload> CODEC =
            StreamCodec.of(OpenAnchorGuiPayload::encode, OpenAnchorGuiPayload::decode);

    private static void encode(FriendlyByteBuf buf, OpenAnchorGuiPayload p) {
        buf.writeVarInt(p.entityId);
        buf.writeBoolean(p.enabled);
        buf.writeVarInt(p.radius);
        buf.writeVarInt(p.maxRadius);
        buf.writeVarInt(p.blockX);
        buf.writeVarInt(p.blockY);
        buf.writeVarInt(p.blockZ);
        buf.writeUtf(p.dimension, 128);
    }

    private static OpenAnchorGuiPayload decode(FriendlyByteBuf buf) {
        return new OpenAnchorGuiPayload(
                buf.readVarInt(),
                buf.readBoolean(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readVarInt(),
                buf.readUtf(128));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
