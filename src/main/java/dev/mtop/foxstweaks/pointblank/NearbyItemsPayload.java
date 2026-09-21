package dev.mtop.foxstweaks.pointblank;

import java.util.HashMap;
import java.util.Map;

import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What the storage around a printer holds, sent to a player who has it open. Point Blank decides
 * whether its Craft button is enabled on the client, from the player's own inventory - so without
 * this the button stays greyed out no matter what the server would have allowed.
 *
 * <p>Registered as {@code optional}, so a client without this mod can still join; it just keeps the
 * button behaviour it had.
 */
public record NearbyItemsPayload(Map<ResourceLocation, Integer> counts) implements CustomPacketPayload {
    public static final Type<NearbyItemsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "printer_nearby_items"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NearbyItemsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), NearbyItemsPayload::counts,
            NearbyItemsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
