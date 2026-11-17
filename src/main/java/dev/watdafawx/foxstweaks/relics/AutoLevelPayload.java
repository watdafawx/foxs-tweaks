package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server, about the relic in menu slot {@code slot}: {@link #TOGGLE} marks or unmarks
 * {@code ability}, {@link #CYCLE_MODE} switches between finishing abilities one by one and round
 * robin ({@code ability} is ignored), {@link #CLEAR} unmarks every ability. Holds no Relics type, so
 * it can be registered whether or not Relics is installed; the handler checks.
 */
public record AutoLevelPayload(int container, int slot, String ability, int action) implements CustomPacketPayload {
    public static final int TOGGLE = 0;
    public static final int CYCLE_MODE = 1;
    public static final int CLEAR = 2;

    public static final Type<AutoLevelPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "auto_level"));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoLevelPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.INT, AutoLevelPayload::container,
            ByteBufCodecs.INT, AutoLevelPayload::slot,
            ByteBufCodecs.STRING_UTF8, AutoLevelPayload::ability,
            ByteBufCodecs.VAR_INT, AutoLevelPayload::action,
            AutoLevelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
