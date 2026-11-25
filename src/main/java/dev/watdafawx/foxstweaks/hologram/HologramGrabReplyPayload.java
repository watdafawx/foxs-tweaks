package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Server -> client response carrying the grabbed {@link HologramLayer}(s) or a refusal reason.
 */
public record HologramGrabReplyPayload(
        int requestId,
        boolean ok,
        String message,
        List<HologramLayer> layers
) implements CustomPacketPayload {

    public static final Type<HologramGrabReplyPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_grab_reply"));

    public static final StreamCodec<FriendlyByteBuf, HologramGrabReplyPayload> CODEC =
            StreamCodec.of(HologramGrabReplyPayload::encode, HologramGrabReplyPayload::decode);

    public static HologramGrabReplyPayload success(int requestId, String label, List<HologramLayer> layers) {
        return new HologramGrabReplyPayload(requestId, true, label == null ? "" : label, List.copyOf(layers));
    }

    public static HologramGrabReplyPayload error(int requestId, String reason) {
        return new HologramGrabReplyPayload(requestId, false, reason == null ? "" : reason, List.of());
    }

    private static void encode(FriendlyByteBuf buf, HologramGrabReplyPayload p) {
        buf.writeVarInt(p.requestId);
        buf.writeBoolean(p.ok);
        buf.writeUtf(p.message, 256);
        CompoundTag root = new CompoundTag();
        if (!p.layers.isEmpty())
            root.put("layers", HologramLayer.listToTag(p.layers));
        buf.writeNbt(root);
    }

    private static HologramGrabReplyPayload decode(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        boolean ok = buf.readBoolean();
        String message = buf.readUtf(256);
        CompoundTag root = buf.readNbt();
        List<HologramLayer> layers = root != null && root.contains("layers", Tag.TAG_LIST)
                ? HologramLayer.listFromTag(root.getList("layers", Tag.TAG_COMPOUND))
                : List.of();
        return new HologramGrabReplyPayload(requestId, ok, message, layers);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
