package dev.watdafawx.foxstweaks.terraform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
 * Stored as a DataComponent on the Biome Wand item. Holds the selection mode, target biome,
 * vertical range, precision/snow options, and current selection points.
 */
public record BiomeWandConfig(
        Mode mode,
        ResourceLocation targetBiome,
        int brushRadius,
        int floodRadius,
        boolean fullColumn,
        int sliceMinY,
        int sliceMaxY,
        boolean exactEdges,
        boolean clearSnow,
        Optional<BlockPos> pos1,
        Optional<BlockPos> pos2
) {
    public enum Mode {
        BOX("Box"),
        BRUSH("Radius Brush"),
        FLOOD_FILL("Flood-Fill Biome");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            Mode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static Mode byOrdinal(int ord) {
            Mode[] vals = values();
            return vals[Math.floorMod(ord, vals.length)];
        }
    }

    public static final ResourceLocation DEFAULT_BIOME = ResourceLocation.withDefaultNamespace("cherry_grove");

    public static final BiomeWandConfig DEFAULT = new BiomeWandConfig(
            Mode.BOX,
            DEFAULT_BIOME,
            16,
            64,
            true,
            60,
            128,
            true,
            true,
            Optional.empty(),
            Optional.empty()
    );

    public static final Codec<BiomeWandConfig> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.xmap(Mode::byOrdinal, Mode::ordinal).optionalFieldOf("mode", Mode.BOX).forGetter(BiomeWandConfig::mode),
            ResourceLocation.CODEC.optionalFieldOf("target_biome", DEFAULT_BIOME).forGetter(BiomeWandConfig::targetBiome),
            Codec.INT.optionalFieldOf("brush_radius", 16).forGetter(BiomeWandConfig::brushRadius),
            Codec.INT.optionalFieldOf("flood_radius", 64).forGetter(BiomeWandConfig::floodRadius),
            Codec.BOOL.optionalFieldOf("full_column", true).forGetter(BiomeWandConfig::fullColumn),
            Codec.INT.optionalFieldOf("slice_min_y", 60).forGetter(BiomeWandConfig::sliceMinY),
            Codec.INT.optionalFieldOf("slice_max_y", 128).forGetter(BiomeWandConfig::sliceMaxY),
            Codec.BOOL.optionalFieldOf("exact_edges", true).forGetter(BiomeWandConfig::exactEdges),
            Codec.BOOL.optionalFieldOf("clear_snow", true).forGetter(BiomeWandConfig::clearSnow),
            BlockPos.CODEC.optionalFieldOf("pos1").forGetter(BiomeWandConfig::pos1),
            BlockPos.CODEC.optionalFieldOf("pos2").forGetter(BiomeWandConfig::pos2)
    ).apply(inst, BiomeWandConfig::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, BiomeWandConfig> STREAM_CODEC =
            StreamCodec.of(BiomeWandConfig::encode, BiomeWandConfig::decode);

    private static void encode(RegistryFriendlyByteBuf buf, BiomeWandConfig c) {
        buf.writeVarInt(c.mode.ordinal());
        buf.writeResourceLocation(c.targetBiome);
        buf.writeVarInt(c.brushRadius);
        buf.writeVarInt(c.floodRadius);
        buf.writeBoolean(c.fullColumn);
        buf.writeVarInt(c.sliceMinY);
        buf.writeVarInt(c.sliceMaxY);
        buf.writeBoolean(c.exactEdges);
        buf.writeBoolean(c.clearSnow);
        ByteBufCodecs.optional(BlockPos.STREAM_CODEC).encode(buf, c.pos1);
        ByteBufCodecs.optional(BlockPos.STREAM_CODEC).encode(buf, c.pos2);
    }

    private static BiomeWandConfig decode(RegistryFriendlyByteBuf buf) {
        Mode mode = Mode.byOrdinal(buf.readVarInt());
        ResourceLocation biome = buf.readResourceLocation();
        int brushRadius = buf.readVarInt();
        int floodRadius = buf.readVarInt();
        boolean fullColumn = buf.readBoolean();
        int sliceMinY = buf.readVarInt();
        int sliceMaxY = buf.readVarInt();
        boolean exactEdges = buf.readBoolean();
        boolean clearSnow = buf.readBoolean();
        Optional<BlockPos> pos1 = ByteBufCodecs.optional(BlockPos.STREAM_CODEC).decode(buf);
        Optional<BlockPos> pos2 = ByteBufCodecs.optional(BlockPos.STREAM_CODEC).decode(buf);
        return new BiomeWandConfig(mode, biome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY,
                exactEdges, clearSnow, pos1, pos2);
    }

    public boolean hasSelection() {
        return switch (mode) {
            case BOX -> pos1.isPresent() && pos2.isPresent();
            case BRUSH, FLOOD_FILL -> pos1.isPresent();
        };
    }

    public BiomeWandConfig withMode(Mode m) {
        return new BiomeWandConfig(m, targetBiome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY, exactEdges, clearSnow, Optional.empty(), Optional.empty());
    }

    public BiomeWandConfig withTargetBiome(ResourceLocation biome) {
        return new BiomeWandConfig(mode, biome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY, exactEdges, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withBrushRadius(int r) {
        return new BiomeWandConfig(mode, targetBiome, r, floodRadius, fullColumn, sliceMinY, sliceMaxY, exactEdges, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withFloodRadius(int r) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, r, fullColumn, sliceMinY, sliceMaxY, exactEdges, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withFullColumn(boolean fc) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, floodRadius, fc, sliceMinY, sliceMaxY, exactEdges, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withSliceY(int minY, int maxY) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, floodRadius, fullColumn, Math.min(minY, maxY), Math.max(minY, maxY), exactEdges, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withExactEdges(boolean ee) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY, ee, clearSnow, pos1, pos2);
    }

    public BiomeWandConfig withClearSnow(boolean cs) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY, exactEdges, cs, pos1, pos2);
    }

    public BiomeWandConfig withPositions(Optional<BlockPos> p1, Optional<BlockPos> p2) {
        return new BiomeWandConfig(mode, targetBiome, brushRadius, floodRadius, fullColumn, sliceMinY, sliceMaxY, exactEdges, clearSnow, p1, p2);
    }

    public BiomeWandConfig cleared() {
        return withPositions(Optional.empty(), Optional.empty());
    }
}
