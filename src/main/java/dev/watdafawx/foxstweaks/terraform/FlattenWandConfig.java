package dev.watdafawx.foxstweaks.terraform;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

import java.util.Optional;

/**
 * Stored as a DataComponent on the Terrain Flattener Wand item. Controls selection area,
 * gradual flatness (0..100%), smoothness blur, edge feathering, target height, tree handling,
 * and natural-blocks-only safety.
 */
public record FlattenWandConfig(
        Mode mode,
        int radius,
        TargetYMode targetYMode,
        int customY,
        float flatness,
        float smoothness,
        float feather,
        TreeMode treeMode,
        WaterMode waterMode,
        boolean naturalOnly,
        boolean protectBlockEntities,
        Optional<BlockPos> pos1,
        Optional<BlockPos> pos2
) {
    public enum Mode {
        BOX("Box"),
        BRUSH("Radius Brush");

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

    public enum TargetYMode {
        CLICKED("Clicked Y"),
        AVERAGE("Area Average"),
        CUSTOM("Custom Y");

        public final String label;

        TargetYMode(String label) {
            this.label = label;
        }

        public TargetYMode next() {
            TargetYMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static TargetYMode byOrdinal(int ord) {
            TargetYMode[] vals = values();
            return vals[Math.floorMod(ord, vals.length)];
        }
    }

    public enum TreeMode {
        REMOVE("Remove Trees"),
        KEEP("Keep Trees"),
        REPLANT("Replant Saplings");

        public final String label;

        TreeMode(String label) {
            this.label = label;
        }

        public TreeMode next() {
            TreeMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static TreeMode byOrdinal(int ord) {
            TreeMode[] vals = values();
            return vals[Math.floorMod(ord, vals.length)];
        }
    }

    public enum WaterMode {
        FILL("Fill/Drain"),
        KEEP("Keep Water"),
        LEVEL("Smooth Pond");

        public final String label;

        WaterMode(String label) {
            this.label = label;
        }

        public WaterMode next() {
            WaterMode[] vals = values();
            return vals[(ordinal() + 1) % vals.length];
        }

        public static WaterMode byOrdinal(int ord) {
            WaterMode[] vals = values();
            return vals[Math.floorMod(ord, vals.length)];
        }
    }

    public static final FlattenWandConfig DEFAULT = new FlattenWandConfig(
            Mode.BRUSH,
            16,
            TargetYMode.CLICKED,
            64,
            0.65F,
            0.50F,
            0.35F,
            TreeMode.REMOVE,
            WaterMode.FILL,
            true,
            true,
            Optional.empty(),
            Optional.empty()
    );

    public static final Codec<FlattenWandConfig> CODEC = RecordCodecBuilder.create(inst -> inst.group(
            Codec.INT.xmap(Mode::byOrdinal, Mode::ordinal).optionalFieldOf("mode", Mode.BRUSH).forGetter(FlattenWandConfig::mode),
            Codec.INT.optionalFieldOf("radius", 16).forGetter(FlattenWandConfig::radius),
            Codec.INT.xmap(TargetYMode::byOrdinal, TargetYMode::ordinal).optionalFieldOf("target_y_mode", TargetYMode.CLICKED).forGetter(FlattenWandConfig::targetYMode),
            Codec.INT.optionalFieldOf("custom_y", 64).forGetter(FlattenWandConfig::customY),
            Codec.FLOAT.optionalFieldOf("flatness", 0.65F).forGetter(FlattenWandConfig::flatness),
            Codec.FLOAT.optionalFieldOf("smoothness", 0.50F).forGetter(FlattenWandConfig::smoothness),
            Codec.FLOAT.optionalFieldOf("feather", 0.35F).forGetter(FlattenWandConfig::feather),
            Codec.INT.xmap(TreeMode::byOrdinal, TreeMode::ordinal).optionalFieldOf("tree_mode", TreeMode.REMOVE).forGetter(FlattenWandConfig::treeMode),
            Codec.INT.xmap(WaterMode::byOrdinal, WaterMode::ordinal).optionalFieldOf("water_mode", WaterMode.FILL).forGetter(FlattenWandConfig::waterMode),
            Codec.BOOL.optionalFieldOf("natural_only", true).forGetter(FlattenWandConfig::naturalOnly),
            Codec.BOOL.optionalFieldOf("protect_block_entities", true).forGetter(FlattenWandConfig::protectBlockEntities),
            BlockPos.CODEC.optionalFieldOf("pos1").forGetter(FlattenWandConfig::pos1),
            BlockPos.CODEC.optionalFieldOf("pos2").forGetter(FlattenWandConfig::pos2)
    ).apply(inst, FlattenWandConfig::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, FlattenWandConfig> STREAM_CODEC =
            StreamCodec.of(FlattenWandConfig::encode, FlattenWandConfig::decode);

    private static void encode(RegistryFriendlyByteBuf buf, FlattenWandConfig c) {
        buf.writeVarInt(c.mode.ordinal());
        buf.writeVarInt(c.radius);
        buf.writeVarInt(c.targetYMode.ordinal());
        buf.writeVarInt(c.customY);
        buf.writeFloat(c.flatness);
        buf.writeFloat(c.smoothness);
        buf.writeFloat(c.feather);
        buf.writeVarInt(c.treeMode.ordinal());
        buf.writeVarInt(c.waterMode.ordinal());
        buf.writeBoolean(c.naturalOnly);
        buf.writeBoolean(c.protectBlockEntities);
        ByteBufCodecs.optional(BlockPos.STREAM_CODEC).encode(buf, c.pos1);
        ByteBufCodecs.optional(BlockPos.STREAM_CODEC).encode(buf, c.pos2);
    }

    private static FlattenWandConfig decode(RegistryFriendlyByteBuf buf) {
        Mode mode = Mode.byOrdinal(buf.readVarInt());
        int radius = buf.readVarInt();
        TargetYMode targetYMode = TargetYMode.byOrdinal(buf.readVarInt());
        int customY = buf.readVarInt();
        float flatness = buf.readFloat();
        float smoothness = buf.readFloat();
        float feather = buf.readFloat();
        TreeMode treeMode = TreeMode.byOrdinal(buf.readVarInt());
        WaterMode waterMode = WaterMode.byOrdinal(buf.readVarInt());
        boolean naturalOnly = buf.readBoolean();
        boolean protectBlockEntities = buf.readBoolean();
        Optional<BlockPos> pos1 = ByteBufCodecs.optional(BlockPos.STREAM_CODEC).decode(buf);
        Optional<BlockPos> pos2 = ByteBufCodecs.optional(BlockPos.STREAM_CODEC).decode(buf);
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather,
                treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public boolean hasSelection() {
        return switch (mode) {
            case BOX -> pos1.isPresent() && pos2.isPresent();
            case BRUSH -> pos1.isPresent();
        };
    }

    public FlattenWandConfig withMode(Mode m) {
        return new FlattenWandConfig(m, radius, targetYMode, customY, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, Optional.empty(), Optional.empty());
    }

    public FlattenWandConfig withRadius(int r) {
        return new FlattenWandConfig(mode, r, targetYMode, customY, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withTargetYMode(TargetYMode t) {
        return new FlattenWandConfig(mode, radius, t, customY, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withCustomY(int y) {
        return new FlattenWandConfig(mode, radius, targetYMode, y, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withFlatness(float f) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, f, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withSmoothness(float s) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, s, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withFeather(float f) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, f, treeMode, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withTreeMode(TreeMode tm) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather, tm, waterMode, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withWaterMode(WaterMode wm) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather, treeMode, wm, naturalOnly, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withNaturalOnly(boolean n) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather, treeMode, waterMode, n, protectBlockEntities, pos1, pos2);
    }

    public FlattenWandConfig withProtectBlockEntities(boolean p) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, p, pos1, pos2);
    }

    public FlattenWandConfig withPositions(Optional<BlockPos> p1, Optional<BlockPos> p2) {
        return new FlattenWandConfig(mode, radius, targetYMode, customY, flatness, smoothness, feather, treeMode, waterMode, naturalOnly, protectBlockEntities, p1, p2);
    }

    public FlattenWandConfig cleared() {
        return withPositions(Optional.empty(), Optional.empty());
    }
}
