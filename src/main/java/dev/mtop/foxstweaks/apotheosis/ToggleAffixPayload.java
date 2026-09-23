package dev.mtop.foxstweaks.apotheosis;

import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: toggle one affix on the sender's main-hand item. Holds no Apotheosis type;
 * the server resolves {@code affixId} against Apotheosis' own registry, see
 * {@code compat.ApothicAffixToggle}.
 */
public record ToggleAffixPayload(ResourceLocation affixId, boolean enable) implements CustomPacketPayload {
    public static final Type<ToggleAffixPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "toggle_affix"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ToggleAffixPayload> CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ToggleAffixPayload::affixId,
            ByteBufCodecs.BOOL, ToggleAffixPayload::enable,
            ToggleAffixPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
