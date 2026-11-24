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
import java.util.Map;

/**
 * One extra piece of content inside a hologram: a block, an entity or a particle emitter.
 *
 * <p>Offsets are in <b>blocks and fractional</b>, unlike the hologram's own {@code BlockPos}. That is the
 * whole reason this type exists: "a dragon head floating half a block above the pedestal" cannot be
 * expressed with block positions, and stacking three separate holograms cannot keep them in sync when
 * they flicker or grow in.
 *
 * <p>The parent keeps the transform, the effects and the appear radius; a part only says what it is,
 * where it sits relative to the parent, how big it is and which way it faces.
 */
public record HologramPart(
        Kind kind,
        String id,
        Map<String, String> props,
        float offsetX,
        float offsetY,
        float offsetZ,
        float scale,
        float yaw
) {
    public enum Kind {
        BLOCK,
        ENTITY,
        PARTICLE
    }

    /** Parts are cheap to draw but not free; this is the cap the editor enforces. */
    public static final int MAX_PARTS = 4;

    public HologramPart {
        offsetX = Mth.clamp(offsetX, -16.0F, 16.0F);
        offsetY = Mth.clamp(offsetY, -16.0F, 16.0F);
        offsetZ = Mth.clamp(offsetZ, -16.0F, 16.0F);
        scale = Mth.clamp(scale, 0.1F, 8.0F);
        yaw = Mth.wrapDegrees(yaw);
        props = Map.copyOf(props);
    }

    public static HologramPart block(String block, Map<String, String> props, float offsetY) {
        return new HologramPart(Kind.BLOCK, block, props, 0.0F, offsetY, 0.0F, 1.0F, 0.0F);
    }

    public static HologramPart entity(String entityType, float offsetY) {
        return new HologramPart(Kind.ENTITY, entityType, Map.of(), 0.0F, offsetY, 0.0F, 1.0F, 0.0F);
    }

    public static HologramPart particle(String particleType, float offsetY) {
        return new HologramPart(Kind.PARTICLE, particleType, Map.of(), 0.0F, offsetY, 0.0F, 0.25F, 0.0F);
    }

    /** True when this part's content still exists on this install. */
    public boolean resolvable() {
        ResourceLocation key = ResourceLocation.tryParse(this.id);
        if (key == null)
            return false;
        return switch (this.kind) {
            case BLOCK -> BuiltInRegistries.BLOCK.containsKey(key);
            case ENTITY -> BuiltInRegistries.ENTITY_TYPE.containsKey(key);
            case PARTICLE -> BuiltInRegistries.PARTICLE_TYPE.containsKey(key);
        };
    }

    /** Short label for the editor row. */
    public String label() {
        String path = this.id.contains(":") ? this.id.substring(this.id.indexOf(':') + 1) : this.id;
        return this.kind.name().charAt(0) + this.kind.name().substring(1).toLowerCase(java.util.Locale.ROOT)
                + " " + path;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", this.kind.name());
        tag.putString("id", this.id);
        CompoundTag props = new CompoundTag();
        this.props.forEach(props::putString);
        tag.put("props", props);
        tag.putFloat("dx", this.offsetX);
        tag.putFloat("dy", this.offsetY);
        tag.putFloat("dz", this.offsetZ);
        tag.putFloat("scale", this.scale);
        tag.putFloat("yaw", this.yaw);
        return tag;
    }

    public static ListTag listToTag(List<HologramPart> parts) {
        ListTag list = new ListTag();
        for (HologramPart part : parts)
            list.add(part.toTag());
        return list;
    }

    public static List<HologramPart> listFromTag(ListTag list) {
        List<HologramPart> parts = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            Kind kind;
            try {
                kind = Kind.valueOf(tag.getString("kind"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            String id = tag.getString("id");
            if (id.isEmpty())
                continue;

            Map<String, String> props = new LinkedHashMap<>();
            CompoundTag rawProps = tag.getCompound("props");
            for (String key : rawProps.getAllKeys())
                props.put(key, rawProps.getString(key));

            parts.add(new HologramPart(kind, id, props, tag.getFloat("dx"), tag.getFloat("dy"),
                    tag.getFloat("dz"), tag.getFloat("scale"), tag.getFloat("yaw")));
        }
        return parts;
    }

    /** True when this tag is one of ours; used to skip junk in a saved list. */
    public static boolean looksValid(Tag tag) {
        return tag instanceof CompoundTag compound && !compound.getString("kind").isEmpty()
                && !compound.getString("id").isEmpty();
    }
}
