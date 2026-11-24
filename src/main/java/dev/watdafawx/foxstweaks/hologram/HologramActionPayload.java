package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: save the edited hologram, or delete it.
 *
 * <p>One payload for both because both are "here is a hologram tag and what to do with it", and the
 * 1.21.1 {@code StreamCodec.composite} helper only goes up to six fields anyway.
 */
public record HologramActionPayload(Action action, CompoundTag spec) implements CustomPacketPayload {
    public enum Action {
        SAVE,
        DELETE,
        /** Create a new hologram at the {@code pos} in the tag, using the tag's presentation fields. */
        CREATE,
        /** Re-capture block entity data from the block at or adjacent to the hologram's position. */
        RECAPTURE
    }

    public static final Type<HologramActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_action"));

    public static final StreamCodec<FriendlyByteBuf, HologramActionPayload> CODEC =
            StreamCodec.of(HologramActionPayload::encode, HologramActionPayload::decode);

    public static HologramActionPayload save(HologramSpec spec) {
        return new HologramActionPayload(Action.SAVE, spec.toTag());
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

    public static HologramActionPayload recapture(String id) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
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
