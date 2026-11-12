package dev.watdafawx.foxstweaks.enchant;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;

/**
 * Client -> server: switch one enchantment or Apotheosis affix on the sender's item in {@code slot}.
 * Holds no Apotheosis type; the server resolves an affix id against Apotheosis' own registry.
 */
public record ToggleEntryPayload(EquipmentSlot slot, boolean affix, ResourceLocation id, boolean enable) implements CustomPacketPayload {
    public static final Type<ToggleEntryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "toggle_entry"));

    private static final StreamCodec<ByteBuf, EquipmentSlot> SLOT_CODEC =
            ByteBufCodecs.idMapper(i -> EquipmentSlot.values()[i], EquipmentSlot::ordinal);

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleEntryPayload> CODEC = StreamCodec.composite(
            SLOT_CODEC, ToggleEntryPayload::slot,
            ByteBufCodecs.BOOL, ToggleEntryPayload::affix,
            ResourceLocation.STREAM_CODEC, ToggleEntryPayload::id,
            ByteBufCodecs.BOOL, ToggleEntryPayload::enable,
            ToggleEntryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
