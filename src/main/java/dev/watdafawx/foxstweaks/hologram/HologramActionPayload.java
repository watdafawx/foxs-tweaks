package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server hologram editor actions: live preview streaming, save, cancel/revert, delete,
 * create (paste), and block-entity recapture.
 */
public record HologramActionPayload(Action action, CompoundTag spec) implements CustomPacketPayload {
    public enum Action {
        SAVE,
        DELETE,
        /** Create a new hologram at the {@code pos} in the tag, using the tag's presentation fields. */
        CREATE,
        /** Re-capture block entity data from the block at or adjacent to the hologram's position. */
        RECAPTURE,
        /** Transient live edit broadcast to all players in the dimension before Done is pressed. */
        PREVIEW,
        /** Clear any active live preview for this hologram id and revert all players to saved data. */
        CANCEL
    }

    public static final Type<HologramActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_action"));

    public static final StreamCodec<FriendlyByteBuf, HologramActionPayload> CODEC =
            StreamCodec.of(HologramActionPayload::encode, HologramActionPayload::decode);

    public static HologramActionPayload save(HologramSpec spec) {
        return new HologramActionPayload(Action.SAVE, spec.toTag());
    }

    public static HologramActionPayload preview(HologramSpec spec) {
        return new HologramActionPayload(Action.PREVIEW, spec.toTag());
    }

    public static HologramActionPayload cancel(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return new HologramActionPayload(Action.CANCEL, tag);
    }

    public static HologramActionPayload delete(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        return new HologramActionPayload(Action.DELETE, tag);
    }

    /** A copy of {@code style} placed at {@code pos}: the clipboard's paste, resolved by the server. */
    public static HologramActionPayload create(BlockPos pos, HologramSpec style) {
        CompoundTag tag = style.toTag();
        tag.putLong("pos", pos.asLong());
        return new HologramActionPayload(Action.CREATE, tag);
    }

    public static HologramActionPayload recapture(String id, int layerIndex) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putInt("layerIndex", layerIndex);
        return new HologramActionPayload(Action.RECAPTURE, tag);
    }

    private static void encode(FriendlyByteBuf buf, HologramActionPayload p) {
        buf.writeByte(p.action.ordinal());
        buf.writeNbt(p.spec);
    }

    private static HologramActionPayload decode(FriendlyByteBuf buf) {
        int action = buf.readByte();
        CompoundTag tag = buf.readNbt();
        Action[] values = Action.values();
        return new HologramActionPayload(values[Math.max(0, Math.min(action, values.length - 1))],
                tag == null ? new CompoundTag() : tag);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
