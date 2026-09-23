package dev.mtop.foxstweaks.apotheosis;

import java.util.HashMap;
import java.util.Map;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * Affixes pulled off an item stack by the toggle GUI, kept here (id -> level) so they can be put
 * back exactly as they were. Holds no Apotheosis type - see {@code compat.ApothicAffixToggle} for
 * where this is actually read and written against Apotheosis' own {@code ItemAffixes}.
 */
public record DisabledAffixes(Map<ResourceLocation, Float> entries) {
    public static final DisabledAffixes EMPTY = new DisabledAffixes(Map.of());

    public static final Codec<DisabledAffixes> CODEC = Codec
            .unboundedMap(ResourceLocation.CODEC, Codec.FLOAT)
            .xmap(DisabledAffixes::new, DisabledAffixes::entries);

    public static final StreamCodec<ByteBuf, DisabledAffixes> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.map(HashMap::new, ResourceLocation.STREAM_CODEC, ByteBufCodecs.FLOAT), DisabledAffixes::entries,
            DisabledAffixes::new);
}
