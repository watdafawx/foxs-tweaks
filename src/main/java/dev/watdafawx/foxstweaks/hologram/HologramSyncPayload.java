package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: every hologram in the dimension the player is in.
 *
 * <p>The list is re-sent whole after any change instead of patched. It is a few hundred bytes of
 * NBT for a typical world, changes are rare (a human with a wand), and a whole-list payload cannot
 * leave a client holding a stale row the way incremental updates can.
 */
public record HologramSyncPayload(ResourceLocation dimension, List<HologramSpec> specs) implements CustomPacketPayload {
    public static final Type<HologramSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_sync"));

    public static final StreamCodec<FriendlyByteBuf, HologramSyncPayload> CODEC =
            StreamCodec.of(HologramSyncPayload::encode, HologramSyncPayload::decode);

    private static void encode(FriendlyByteBuf buf, HologramSyncPayload p) {
        buf.writeResourceLocation(p.dimension);
        buf.writeVarInt(p.specs.size());
        for (HologramSpec spec : p.specs)
            buf.writeNbt(spec.toTag());
    }

    private static HologramSyncPayload decode(FriendlyByteBuf buf) {
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        List<HologramSpec> specs = new ArrayList<>(Math.max(0, Math.min(count, 4096)));
        for (int i = 0; i < count; i++) {
            var tag = buf.readNbt();
            HologramSpec spec = tag == null ? null : HologramSpec.fromTag(tag);
            if (spec != null)
                specs.add(spec);
        }
        return new HologramSyncPayload(dimension, specs);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
