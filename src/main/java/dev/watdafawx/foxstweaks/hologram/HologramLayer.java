package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One first-class content layer inside a hologram: a block, an entity, or a particle emitter.
 *
 * <p>Every piece of content in a hologram is a {@code HologramLayer} with its own local transform,
 * animation (spin/hover), fullbright, FX list, visibility, and optional {@link HologramGroup}
 * membership ({@code groupId == ""} means independent/ungrouped).
 */
public record HologramLayer(
        Kind kind,
        String id,
        Map<String, String> props,
        CompoundTag data,
        String groupId,
        boolean visible,
        float offsetX,
        float offsetY,
        float offsetZ,
        float yaw,
        float pitch,
        float roll,
        float scale,
        float spin,
        int spinAxis,
        float hover,
        float hoverPeriod,
        boolean fullbright,
        List<HologramEffect> effects,
        float particleRate,
        float particleSpeed,
        String uid
) {
    public enum Kind {
        BLOCK,
        ENTITY,
        PARTICLE,
        STRUCTURE,
        /** A flat picture or animation from the asset store; {@code id} is the asset hash, settings in {@link HologramImage}. */
        IMAGE,
        /** A 3D model from the asset store; {@code id} is the asset hash, settings in {@link HologramModelSettings}. */
        MODEL
    }

    public static final int MAX_LAYERS = 16;

    public HologramLayer {
        id = id == null ? "" : id;
        // Image settings are untrusted strings until normalised; clamping here covers every way a layer is made.
        props = kind == Kind.IMAGE ? Map.copyOf(HologramImage.normalize(props))
                : kind == Kind.MODEL ? Map.copyOf(HologramModelSettings.normalize(props)) : Map.copyOf(props);
        int maxBytes = Holograms.maxDataBytes(kind);
        data = (data == null || data.sizeInBytes() > maxBytes) ? new CompoundTag() : data.copy();
        groupId = groupId == null ? "" : groupId;
        offsetX = Mth.clamp(offsetX, -16.0F, 16.0F);
        offsetY = Mth.clamp(offsetY, -16.0F, 16.0F);
        offsetZ = Mth.clamp(offsetZ, -16.0F, 16.0F);
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.wrapDegrees(pitch);
        roll = Mth.wrapDegrees(roll);
        scale = Mth.clamp(scale, 0.05F, 8.0F);
        spin = Mth.clamp(spin, -360.0F, 360.0F);
        spinAxis = Mth.clamp(spinAxis, 0, 2);
        hover = Mth.clamp(hover, 0.0F, 4.0F);
        hoverPeriod = Mth.clamp(hoverPeriod, 0.5F, 120.0F);
        effects = effects.size() > HologramSpec.MAX_EFFECTS
                ? List.copyOf(effects.subList(0, HologramSpec.MAX_EFFECTS))
                : List.copyOf(effects);
        particleRate = Mth.clamp(particleRate, 1.0F, 40.0F);
        particleSpeed = Mth.clamp(particleSpeed, -0.5F, 0.5F);
        uid = uid == null || !UID.matcher(uid).matches() ? "" : uid;
    }

    /** Stable id the timeline addresses a layer by; "" until the owning {@link HologramSpec} assigns one. */
    public static final java.util.regex.Pattern UID = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,24}");

    /** The pre-timeline shape: no uid yet, the spec assigns one. */
    public HologramLayer(Kind kind, String id, Map<String, String> props, CompoundTag data, String groupId,
                         boolean visible, float offsetX, float offsetY, float offsetZ, float yaw, float pitch,
                         float roll, float scale, float spin, int spinAxis, float hover, float hoverPeriod,
                         boolean fullbright, List<HologramEffect> effects, float particleRate, float particleSpeed) {
        this(kind, id, props, data, groupId, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, "");
    }

    public HologramLayer withUid(String uid) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public static HologramLayer block(String blockId, Map<String, String> props, CompoundTag data) {
        return new HologramLayer(Kind.BLOCK, blockId, props, data, "", true,
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    public static HologramLayer block(String blockId, Map<String, String> props, float offsetY) {
        return new HologramLayer(Kind.BLOCK, blockId, props, new CompoundTag(), "", true,
                0.0F, offsetY, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    public static HologramLayer entity(String entityId, float offsetY) {
        return new HologramLayer(Kind.ENTITY, entityId, Map.of(), new CompoundTag(), "", true,
                0.0F, offsetY, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    public static HologramLayer particle(String particleId, float offsetY) {
        return new HologramLayer(Kind.PARTICLE, particleId, Map.of(), new CompoundTag(), "", true,
                0.0F, offsetY, 0.0F, 0.0F, 0.0F, 0.0F, 0.35F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    public static HologramLayer structure(HologramStructure structure) {
        return new HologramLayer(Kind.STRUCTURE, structure.displayName(), Map.of(), structure.toTag(), "", true,
                0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    /** A new image layer: one block wide, keeps the asset's aspect ratio, emissive. */
    public static HologramLayer image(String assetHash, float offsetY) {
        return new HologramLayer(Kind.IMAGE, assetHash, HologramImage.DEFAULT.toProps(), new CompoundTag(), "", true,
                0.0F, offsetY, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    /** The image settings of an {@link Kind#IMAGE} layer (defaults for any other kind). */
    public HologramImage image() {
        return this.kind == Kind.IMAGE ? HologramImage.of(this.props) : HologramImage.DEFAULT;
    }

    public HologramLayer withImage(HologramImage image) {
        return withProps(image.toProps());
    }

    /** A new model layer: one block tall, plays no animation until one is picked. */
    public static HologramLayer model(String assetHash, float offsetY) {
        return new HologramLayer(Kind.MODEL, assetHash, HologramModelSettings.DEFAULT.toProps(), new CompoundTag(), "", true,
                0.0F, offsetY, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of(), 10.0F, 0.01F);
    }

    /** The model settings of a {@link Kind#MODEL} layer (defaults for any other kind). */
    public HologramModelSettings model() {
        return this.kind == Kind.MODEL ? HologramModelSettings.of(this.props) : HologramModelSettings.DEFAULT;
    }

    public HologramLayer withModel(HologramModelSettings settings) {
        return withProps(settings.toProps());
    }

    public HologramLayer withContent(Kind kind, String id, Map<String, String> props, CompoundTag data) {
        float nextScale = (this.kind != kind && kind == Kind.PARTICLE) ? 0.35F
                : (this.kind == Kind.PARTICLE && kind != Kind.PARTICLE ? 1.0F : this.scale);
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, nextScale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed);
    }

    public HologramLayer withProps(Map<String, String> props) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withData(CompoundTag data) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withGroupId(String groupId) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withVisible(boolean visible) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withOffset(float x, float y, float z) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                x, y, z, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withRotation(float yaw, float pitch, float roll) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withScale(float scale) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withSpin(float spin, int spinAxis) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withHover(float hover, float hoverPeriod) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withFullbright(boolean fullbright) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withEffects(List<HologramEffect> effects) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, particleRate, particleSpeed, uid);
    }

    public HologramLayer withParticle(float rate, float speed) {
        return new HologramLayer(kind, id, props, data, groupId, visible,
                offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects, rate, speed, uid);
    }

    /**
     * Structure and image layers carry content the editor's picker cannot make (a grabbed structure, an
     * uploaded asset), so picking a block, entity or particle must never overwrite them.
     */
    public boolean fixedContent() {
        return this.kind == Kind.STRUCTURE || this.kind == Kind.IMAGE || this.kind == Kind.MODEL;
    }

    public boolean resolvable() {
        if (this.kind == Kind.STRUCTURE)
            return HologramStructure.isValidTag(this.data);
        if (this.kind == Kind.IMAGE || this.kind == Kind.MODEL)
            return dev.watdafawx.foxstweaks.hologram.asset.AssetFrames.validId(this.id);
        ResourceLocation key = ResourceLocation.tryParse(this.id);
        if (key == null)
            return false;
        return switch (this.kind) {
            case BLOCK -> BuiltInRegistries.BLOCK.containsKey(key);
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            case PARTICLE -> BuiltInRegistries.PARTICLE_TYPE.containsKey(key);
            case STRUCTURE, IMAGE, MODEL -> true;
        };
    }

    public String shortName() {
        if (this.kind == Kind.MODEL)
            return "Model " + (this.id.length() >= 8 ? this.id.substring(0, 8) : this.id);
        if (this.kind == Kind.IMAGE)
            return "Image " + (this.id.length() >= 8 ? this.id.substring(0, 8) : this.id);
        if (this.kind == Kind.STRUCTURE) {
            if (!this.id.isEmpty())
                return this.id;
            int[] d = HologramStructure.dimensions(this.data);
            return "Structure " + d[0] + "x" + d[1] + "x" + d[2];
        }
        int colon = this.id.indexOf(':');
        return colon >= 0 ? this.id.substring(colon + 1) : this.id;
    }

    public String label() {
        String k = switch (this.kind) {
            case BLOCK -> "B";
            case ENTITY -> "E";
            case PARTICLE -> "P";
            case STRUCTURE -> "S";
            case IMAGE -> "I";
            case MODEL -> "M";
        };
        return "[" + k + "] " + shortName();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", this.kind.name());
        tag.putString("id", this.id);
        if (!this.props.isEmpty()) {
            CompoundTag p = new CompoundTag();
            this.props.forEach(p::putString);
            tag.put("props", p);
        }
        if (!this.data.isEmpty())
            tag.put(this.kind == Kind.STRUCTURE ? "structure" : "blockEntity", this.data.copy());
        if (!this.groupId.isEmpty())
            tag.putString("groupId", this.groupId);
        if (!this.visible)
            tag.putBoolean("visible", false);
        if (this.offsetX != 0.0F)
            tag.putFloat("dx", this.offsetX);
        if (this.offsetY != 0.0F)
            tag.putFloat("dy", this.offsetY);
        if (this.offsetZ != 0.0F)
            tag.putFloat("dz", this.offsetZ);
        if (this.yaw != 0.0F)
            tag.putFloat("yaw", this.yaw);
        if (this.pitch != 0.0F)
            tag.putFloat("pitch", this.pitch);
        if (this.roll != 0.0F)
            tag.putFloat("roll", this.roll);
        tag.putFloat("scale", this.scale);
        if (this.spin != 0.0F)
            tag.putFloat("spin", this.spin);
        if (this.spinAxis != HologramSpec.AXIS_Y)
            tag.putByte("spinAxis", (byte) this.spinAxis);
        if (this.hover != 0.0F)
            tag.putFloat("hover", this.hover);
        if (this.hoverPeriod != 4.0F)
            tag.putFloat("hoverPeriod", this.hoverPeriod);
        if (this.fullbright)
            tag.putBoolean("fullbright", true);
        if (!this.effects.isEmpty())
            tag.put("effects", HologramEffect.listToTag(this.effects));
        if (!this.uid.isEmpty())
            tag.putString("uid", this.uid);
        if (this.kind == Kind.PARTICLE) {
            tag.putFloat("rate", this.particleRate);
            tag.putFloat("speed", this.particleSpeed);
        }
        return tag;
    }

    public static ListTag listToTag(List<HologramLayer> layers) {
        ListTag list = new ListTag();
        for (HologramLayer layer : layers)
            list.add(layer.toTag());
        return list;
    }

    public static List<HologramLayer> listFromTag(ListTag list) {
        List<HologramLayer> layers = new ArrayList<>(list.size());
        for (int i = 0; i < list.size() && layers.size() < MAX_LAYERS; i++) {
            CompoundTag tag = list.getCompound(i);
            Kind kind;
            try {
                kind = Kind.valueOf(tag.getString("kind").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String id = tag.getString("id");
            if (id.isEmpty())
                continue;

            Map<String, String> props = new LinkedHashMap<>();
            if (tag.contains("props", Tag.TAG_COMPOUND)) {
                CompoundTag rawProps = tag.getCompound("props");
                for (String key : rawProps.getAllKeys())
                    props.put(key, rawProps.getString(key));
            }
            CompoundTag data = kind == Kind.STRUCTURE && tag.contains("structure", Tag.TAG_COMPOUND)
                    ? tag.getCompound("structure")
                    : (tag.contains("blockEntity", Tag.TAG_COMPOUND)
                    ? tag.getCompound("blockEntity")
                    : new CompoundTag());
            String groupId = tag.getString("groupId");
            boolean visible = !tag.contains("visible") || tag.getBoolean("visible");
            float scale = tag.contains("scale") ? tag.getFloat("scale")
                    : (kind == Kind.PARTICLE ? 0.35F : 1.0F);
            float hoverPeriod = tag.contains("hoverPeriod") ? tag.getFloat("hoverPeriod") : 4.0F;
            List<HologramEffect> effects = tag.contains("effects")
                    ? HologramEffect.listFromTag(tag.getList("effects", Tag.TAG_COMPOUND))
                    : List.of();
            float rate = tag.contains("rate") ? tag.getFloat("rate") : 10.0F;
            float speed = tag.contains("speed") ? tag.getFloat("speed") : 0.01F;

            layers.add(new HologramLayer(kind, id, props, data, groupId, visible,
                    tag.getFloat("dx"), tag.getFloat("dy"), tag.getFloat("dz"),
                    tag.getFloat("yaw"), tag.getFloat("pitch"), tag.getFloat("roll"),
                    scale, tag.getFloat("spin"), tag.getByte("spinAxis"),
                    tag.getFloat("hover"), hoverPeriod, tag.getBoolean("fullbright"),
                    effects, rate, speed, tag.getString("uid")));
        }
        return layers;
    }
}
