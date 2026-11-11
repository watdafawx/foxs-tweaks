package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: mark {@code ability} as the one the relic in menu slot {@code slot} levels up by
 * itself, or clear the choice when {@code ability} is empty. Holds no Relics type, so it can be
 * registered whether or not Relics is installed; the handler checks.
 */
public record AutoLevelPayload(int container, int slot, String ability) implements CustomPacketPayload {
    public static final Type<AutoLevelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "auto_level"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoLevelPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, AutoLevelPayload::container,
            ByteBufCodecs.INT, AutoLevelPayload::slot,
            ByteBufCodecs.STRING_UTF8, AutoLevelPayload::ability,
            AutoLevelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
