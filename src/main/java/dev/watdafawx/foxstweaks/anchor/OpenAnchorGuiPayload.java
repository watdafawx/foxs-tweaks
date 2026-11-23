package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: open {@code SpawnAnchorScreen} for a placed Spawn Anchor dummy.
 *
 * <p>{@code tier}/{@code followOwner}/{@code ownerName} describe the anchor's Apotheosis world tier (empty
 * and false when it carries none), and {@code hideName} whether its floating name is hidden.
 */
public record OpenAnchorGuiPayload(
        int entityId,
        boolean enabled,
        int radius,
        int maxRadius,
        int blockX,
        int blockY,
        int blockZ,
        String dimension,
        String tier,
        boolean followOwner,
        String ownerName,
        boolean hideName
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
        buf.writeUtf(p.tier, 32);
        buf.writeBoolean(p.followOwner);
        buf.writeUtf(p.ownerName, 64);
        buf.writeBoolean(p.hideName);
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
                buf.readUtf(128),
                buf.readUtf(32),
                buf.readBoolean(),
                buf.readUtf(64),
                buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
