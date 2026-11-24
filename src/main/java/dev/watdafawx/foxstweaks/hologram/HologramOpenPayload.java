package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server -> client: open the hologram editor for one hologram.
 *
 * <p>The server decides when this is sent - it re-runs the same pick the client used, so a client
 * cannot open the editor for a hologram it is nowhere near.
 */
public record HologramOpenPayload(HologramSpec spec, int maxRenderDistance) implements CustomPacketPayload {
    public static final Type<HologramOpenPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_open"));

    public static final StreamCodec<FriendlyByteBuf, HologramOpenPayload> CODEC =
            StreamCodec.of(HologramOpenPayload::encode, HologramOpenPayload::decode);

    private static void encode(FriendlyByteBuf buf, HologramOpenPayload p) {
        buf.writeNbt(p.spec.toTag());
        buf.writeVarInt(p.maxRenderDistance);
    }

    private static HologramOpenPayload decode(FriendlyByteBuf buf) {
        var tag = buf.readNbt();
        return new HologramOpenPayload(HologramSpec.fromTag(tag == null ? new net.minecraft.nbt.CompoundTag() : tag),
                buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
