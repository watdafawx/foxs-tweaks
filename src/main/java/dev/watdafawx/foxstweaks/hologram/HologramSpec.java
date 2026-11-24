package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One decorative hologram: a single block drawn in world at a spot, with no collision, no light, no
 * block entity and no chunk ticket of its own.
 *
 * <p>The block is stored by registry name plus the blockstate values that were picked, not as a
 * {@link net.minecraft.world.level.block.state.BlockState}, so a world saved with a mod installed
 * still loads when that mod is missing - {@link Holograms#stateOf} then answers null and nothing is
 * drawn.
 *
 * <p>Everything visual is plain data: the renderer is the only thing that turns it into pixels. The
 * server never ticks a hologram, so an idle hologram costs exactly nothing.
 */
public record HologramSpec(
        String id,
        BlockPos pos,
        float offsetX,
        float offsetY,
        float offsetZ,
        String block,
        Map<String, String> props,
        float scale,
        float yaw,
        float pitch,
        float roll,
        float spin,
        int spinAxis,
        float hover,
        float hoverPeriod,
        boolean fullbright,
        boolean enabled,
        int renderDistance,
        @Nullable UUID owner,
        String ownerName,
        List<HologramEffect> effects,
        float appearRadius,
        float appearSeconds,
        List<HologramPart> parts,
        CompoundTag data
) {
    public static final int AXIS_Y = 0;
    public static final int AXIS_X = 1;
    public static final int AXIS_Z = 2;

    /** Clamps live in the canonical constructor so a hand-written packet cannot produce garbage. */
    public HologramSpec {
        offsetX = Mth.clamp(offsetX, -16.0F, 16.0F);
        offsetY = Mth.clamp(offsetY, -16.0F, 16.0F);
        offsetZ = Mth.clamp(offsetZ, -16.0F, 16.0F);
        scale = Mth.clamp(scale, 0.25F, 8.0F);
        spin = Mth.clamp(spin, -360.0F, 360.0F);
        spinAxis = Mth.clamp(spinAxis, 0, 2);
        hover = Mth.clamp(hover, 0.0F, 4.0F);
        hoverPeriod = Mth.clamp(hoverPeriod, 0.5F, 120.0F);
        renderDistance = Mth.clamp(renderDistance, 8, 512);
        appearRadius = Mth.clamp(appearRadius, 0.0F, 64.0F);
        appearSeconds = Mth.clamp(appearSeconds, 0.0F, 10.0F);
        props = Map.copyOf(props);
        effects = effects.size() > 4 ? List.copyOf(effects.subList(0, 4)) : List.copyOf(effects);
        parts = parts.size() > HologramPart.MAX_PARTS
                ? List.copyOf(parts.subList(0, HologramPart.MAX_PARTS))
                : List.copyOf(parts);
        // Copied and capped so a caller cannot mutate saved data or send oversized NBT payloads.
        data = data.sizeInBytes() > Holograms.MAX_DATA_BYTES ? new CompoundTag() : data.copy();
    }

    public static HologramSpec create(String id, BlockPos pos, String block, @Nullable UUID owner, String ownerName) {
        return new HologramSpec(id, pos, 0.0F, 0.0F, 0.0F, block, Map.of(), 1.0F, 0.0F, 0.0F, 0.0F, 0.0F, AXIS_Y,
                0.0F, 4.0F, false, true, 48, owner, ownerName, List.of(), 0.0F, 0.6F, List.of(),
                new CompoundTag());
    }

    /**
     * Same hologram, re-identified and moved. Every presentation field comes along, which is exactly
     * what the clipboard's paste needs and what keeps a new field from being forgotten at a call site.
     */
    public HologramSpec at(String newId, BlockPos newPos, @Nullable UUID newOwner, String newOwnerName) {
        return new HologramSpec(newId, newPos, this.offsetX, this.offsetY, this.offsetZ, this.block, this.props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, this.renderDistance, newOwner, newOwnerName,
                this.effects, this.appearRadius, this.appearSeconds, this.parts, this.data);
    }

    public HologramSpec withProps(Map<String, String> props) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ, this.block, props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.effects, this.appearRadius, this.appearSeconds, this.parts, this.data);
    }

    public HologramSpec withRenderDistance(int distance) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ, this.block, this.props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, distance, this.owner, this.ownerName,
                this.effects, this.appearRadius, this.appearSeconds, this.parts, this.data);
    }

    public HologramSpec withEffects(List<HologramEffect> effects) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ, this.block, this.props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, this.renderDistance, this.owner, this.ownerName,
                effects, this.appearRadius, this.appearSeconds, this.parts, this.data);
    }

    public HologramSpec withData(CompoundTag data) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ, this.block, this.props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.effects, this.appearRadius, this.appearSeconds, this.parts, data);
    }

    public HologramSpec withParts(List<HologramPart> parts) {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ, this.block, this.props,
                this.scale, this.yaw, this.pitch, this.roll, this.spin, this.spinAxis, this.hover,
                this.hoverPeriod, this.fullbright, this.enabled, this.renderDistance, this.owner, this.ownerName,
                this.effects, this.appearRadius, this.appearSeconds, parts, this.data);
    }

    /** The box the hologram and its parts occupy, used for frustum culling, selection outline and click pick. */
    public AABB box() {
        double cx = this.pos.getX() + this.offsetX + 0.5;
        double cz = this.pos.getZ() + this.offsetZ + 0.5;
        double half = 0.5 * this.scale + 0.05;
        // The model is anchored at its bottom, so a bigger hologram grows upward from the spot.
        double cy = this.pos.getY() + this.offsetY + half;
        AABB bounds = new AABB(cx - half, cy - half, cz - half, cx + half, cy + half, cz + half);
        if (!this.parts.isEmpty()) {
            double baseY = this.pos.getY() + this.offsetY;
            for (HologramPart part : this.parts) {
                double ph = 0.5 * part.scale() + 0.05;
                double px = cx + part.offsetX();
                double py = baseY + part.offsetY() + ph;
                double pz = cz + part.offsetZ();
                bounds = bounds.minmax(new AABB(px - ph, py - ph, pz - ph, px + ph, py + ph, pz + ph));
            }
        }
        return bounds;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", this.id);
        tag.putLong("pos", this.pos.asLong());
        if (this.offsetX != 0.0F)
            tag.putFloat("offsetX", this.offsetX);
        if (this.offsetY != 0.0F)
            tag.putFloat("offsetY", this.offsetY);
        if (this.offsetZ != 0.0F)
            tag.putFloat("offsetZ", this.offsetZ);
        tag.putString("block", this.block);
        CompoundTag props = new CompoundTag();
        this.props.forEach(props::putString);
        tag.put("props", props);
        tag.putFloat("scale", this.scale);
        tag.putFloat("yaw", this.yaw);
        tag.putFloat("pitch", this.pitch);
        tag.putFloat("roll", this.roll);
        tag.putFloat("spin", this.spin);
        tag.putByte("spinAxis", (byte) this.spinAxis);
        tag.putFloat("hover", this.hover);
        tag.putFloat("hoverPeriod", this.hoverPeriod);
        tag.putBoolean("fullbright", this.fullbright);
        tag.putBoolean("enabled", this.enabled);
        tag.putInt("renderDistance", this.renderDistance);
        if (this.owner != null)
            tag.putUUID("owner", this.owner);
        tag.putString("ownerName", this.ownerName);
        if (!this.effects.isEmpty())
            tag.put("effects", HologramEffect.listToTag(this.effects));
        if (!this.parts.isEmpty())
            tag.put("parts", HologramPart.listToTag(this.parts));
        if (!this.data.isEmpty())
            tag.put("blockEntity", this.data.copy());
        tag.putFloat("appearRadius", this.appearRadius);
        tag.putFloat("appearSeconds", this.appearSeconds);
        return tag;
    }

    @Nullable
    public static HologramSpec fromTag(CompoundTag tag) {
        String id = tag.getString("id");
        String block = tag.getString("block");
        if (id.isEmpty())
            return null;

        List<HologramPart> savedParts = tag.contains("parts")
                ? HologramPart.listFromTag(tag.getList("parts", Tag.TAG_COMPOUND))
                : List.of();
        // A hologram with no base block is legal when it has parts (an entity-only display) and junk
        // otherwise.
        if (block.isEmpty() && savedParts.isEmpty())
            return null;

        Map<String, String> props = new LinkedHashMap<>();
        CompoundTag rawProps = tag.getCompound("props");
        for (String key : rawProps.getAllKeys())
            props.put(key, rawProps.getString(key));

        List<HologramEffect> effects = tag.contains("effects")
                ? HologramEffect.listFromTag(tag.getList("effects", Tag.TAG_COMPOUND))
                : List.of();


        UUID owner = tag.contains("owner") ? tag.getUUID("owner") : null;
        return new HologramSpec(id, BlockPos.of(tag.getLong("pos")),
                tag.getFloat("offsetX"), tag.getFloat("offsetY"), tag.getFloat("offsetZ"),
                block, props,
                tag.getFloat("scale"), tag.getFloat("yaw"), tag.getFloat("pitch"), tag.getFloat("roll"),
                tag.getFloat("spin"), tag.getByte("spinAxis"), tag.getFloat("hover"), tag.getFloat("hoverPeriod"),
                tag.getBoolean("fullbright"), !tag.contains("enabled") || tag.getBoolean("enabled"),
                tag.getInt("renderDistance"), owner, tag.getString("ownerName"), effects,
                tag.getFloat("appearRadius"), tag.contains("appearSeconds") ? tag.getFloat("appearSeconds") : 0.6F,
                savedParts, tag.contains("blockEntity") ? tag.getCompound("blockEntity") : new CompoundTag());
    }

    /** True when this tag carries the fields the renderer needs; used to skip junk in a saved list. */
    public static boolean looksValid(Tag tag) {
        return tag instanceof CompoundTag compound && !compound.getString("id").isEmpty()
                && (!compound.getString("block").isEmpty() || !compound.getList("parts", Tag.TAG_COMPOUND).isEmpty());
    }
}
