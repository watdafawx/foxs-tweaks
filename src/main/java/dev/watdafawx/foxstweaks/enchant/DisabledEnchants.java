package dev.watdafawx.foxstweaks.enchant;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Enchantments pulled off an item by the toggle overlay, kept here (id -> level) so they can be put
 * back exactly as they were. See {@link EnchantToggle}.
 */
public record DisabledEnchants(Map<ResourceLocation, Integer> entries) {
    public static final DisabledEnchants EMPTY = new DisabledEnchants(Map.of());

    public static final Codec<DisabledEnchants> CODEC = Codec
            .unboundedMap(ResourceLocation.CODEC, Codec.INT)
            .xmap(DisabledEnchants::new, DisabledEnchants::entries);

    public static final StreamCodec<ByteBuf, DisabledEnchants> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.VAR_INT), DisabledEnchants::entries,
            DisabledEnchants::new);
}
