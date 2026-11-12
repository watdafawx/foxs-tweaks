package dev.watdafawx.foxstweaks.dimension;

import java.util.List;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/** Server -> client: every dyed portal in one dimension. Optional channel; clients without the mod see purple. */
public record PortalColorsPayload(ResourceKey<Level> dimension, List<PortalColors.Entry> entries) implements CustomPacketPayload {
    public static final Type<PortalColorsPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "portal_colors"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PortalColorsPayload> CODEC = StreamCodec.composite(
            ResourceKey.streamCodec(Registries.DIMENSION), PortalColorsPayload::dimension,
            PortalColors.Entry.CODEC.apply(ByteBufCodecs.list()), PortalColorsPayload::entries,
            PortalColorsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
