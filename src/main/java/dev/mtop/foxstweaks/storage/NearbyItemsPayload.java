package dev.mtop.foxstweaks.storage;

import java.util.HashMap;
import java.util.Map;

import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * What the storage around a crafting station holds, sent to a player who has it open. Point Blank's
 * printer and TACZ's gunsmith table both decide client-side what to show as "can afford" from the
 * player's own inventory alone - so without this, nearby storage the server would actually accept is
 * invisible or shown as insufficient on the client. See {@code pointblank.PrinterNetwork} and
 * {@code tacz.WorkbenchNetwork}, which are the only senders; either, both or neither may be active
 * depending on which gun mods are installed.
 *
 * <p>Registered as {@code optional}, so a client without the relevant gun mod can still join; it just
 * keeps whichever behaviour that screen has without this.
 */
public record NearbyItemsPayload(Map<ResourceLocation, Integer> counts) implements CustomPacketPayload {
    public static final Type<NearbyItemsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "nearby_items"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NearbyItemsPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), NearbyItemsPayload::counts,
            NearbyItemsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
