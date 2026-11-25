package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server request to grab a block, entity, or structure box from the world as a {@link HologramLayer}.
 */
public record HologramGrabPayload(
        int requestId,
        TargetKind targetKind,
        BlockPos blockPos,
        BlockPos blockPos2,
        int entityId,
        boolean takeBlock,
        boolean takeState,
        boolean takeContents
) implements CustomPacketPayload {

    public enum TargetKind {
        BLOCK,
        ENTITY,
        STRUCTURE
    }

    public static final Type<HologramGrabPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "hologram_grab"));

    public static final StreamCodec<FriendlyByteBuf, HologramGrabPayload> CODEC =
            StreamCodec.of(HologramGrabPayload::encode, HologramGrabPayload::decode);

    public static HologramGrabPayload forBlock(int requestId, BlockPos pos,
                                               boolean takeBlock, boolean takeState, boolean takeContents) {
        return new HologramGrabPayload(requestId, TargetKind.BLOCK, pos.immutable(), BlockPos.ZERO, -1,
                takeBlock, takeState, takeContents);
    }

    public static HologramGrabPayload forEntity(int requestId, int entityId,
                                                boolean takeBlock, boolean takeState, boolean takeContents) {
        return new HologramGrabPayload(requestId, TargetKind.ENTITY, BlockPos.ZERO, BlockPos.ZERO, entityId,
                takeBlock, takeState, takeContents);
    }

    public static HologramGrabPayload forStructure(int requestId, BlockPos corner1, BlockPos corner2,
                                                   boolean takeState, boolean takeContents) {
        return new HologramGrabPayload(requestId, TargetKind.STRUCTURE, corner1.immutable(), corner2.immutable(), -1,
                true, takeState, takeContents);
    }

    private static void encode(FriendlyByteBuf buf, HologramGrabPayload p) {
        buf.writeVarInt(p.requestId);
        buf.writeByte(p.targetKind.ordinal());
        buf.writeBlockPos(p.blockPos);
        buf.writeBlockPos(p.blockPos2);
        buf.writeVarInt(p.entityId);
        int flags = (p.takeBlock ? 1 : 0) | (p.takeState ? 2 : 0) | (p.takeContents ? 4 : 0);
        buf.writeByte(flags);
    }

    private static HologramGrabPayload decode(FriendlyByteBuf buf) {
        int requestId = buf.readVarInt();
        int ord = buf.readByte();
        TargetKind[] kinds = TargetKind.values();
        TargetKind kind = kinds[Math.max(0, Math.min(ord, kinds.length - 1))];
        BlockPos pos = buf.readBlockPos();
        BlockPos pos2 = buf.readBlockPos();
        int entityId = buf.readVarInt();
        int flags = buf.readByte();
        return new HologramGrabPayload(requestId, kind, pos, pos2, entityId,
                (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
