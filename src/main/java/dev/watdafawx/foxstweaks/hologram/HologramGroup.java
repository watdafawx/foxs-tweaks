package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * A named group inside a hologram that multiple {@link HologramLayer}s can join so they share
 * linked transform, animation (spin/hover), fullbright, and FX. Layers with an empty {@code groupId}
 * live outside all groups and animate/render independently.
 */
public record HologramGroup(
        String id,
        String name,
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
        List<HologramEffect> effects
) {
    public static final int MAX_GROUPS = 8;

    public HologramGroup {
        id = id == null ? "" : id;
        name = (name == null || name.isEmpty()) ? id : name;
        offsetX = Mth.clamp(offsetX, -16.0F, 16.0F);
        offsetY = Mth.clamp(offsetY, -16.0F, 16.0F);
        offsetZ = Mth.clamp(offsetZ, -16.0F, 16.0F);
        yaw = Mth.wrapDegrees(yaw);
        pitch = Mth.wrapDegrees(pitch);
        roll = Mth.wrapDegrees(roll);
        scale = Mth.clamp(scale, 0.1F, 8.0F);
        spin = Mth.clamp(spin, -360.0F, 360.0F);
        spinAxis = Mth.clamp(spinAxis, 0, 2);
        hover = Mth.clamp(hover, 0.0F, 4.0F);
        hoverPeriod = Mth.clamp(hoverPeriod, 0.5F, 120.0F);
        effects = effects.size() > HologramSpec.MAX_EFFECTS
                ? List.copyOf(effects.subList(0, HologramSpec.MAX_EFFECTS))
                : List.copyOf(effects);
    }

    public static HologramGroup create(String id, String name) {
        return new HologramGroup(id, name, true, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 1.0F,
                0.0F, HologramSpec.AXIS_Y, 0.0F, 4.0F, false, List.of());
    }

    public HologramGroup withVisible(boolean visible) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withOffset(float x, float y, float z) {
        return new HologramGroup(id, name, visible, x, y, z, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withRotation(float yaw, float pitch, float roll) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withScale(float scale) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withSpin(float spin, int spinAxis) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withHover(float hover, float hoverPeriod) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withFullbright(boolean fullbright) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public HologramGroup withEffects(List<HologramEffect> effects) {
        return new HologramGroup(id, name, visible, offsetX, offsetY, offsetZ, yaw, pitch, roll, scale,
                spin, spinAxis, hover, hoverPeriod, fullbright, effects);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", this.id);
        tag.putString("name", this.name);
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
        if (this.scale != 1.0F)
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
        return tag;
    }

    public static ListTag listToTag(List<HologramGroup> groups) {
        ListTag list = new ListTag();
        for (HologramGroup group : groups)
            list.add(group.toTag());
        return list;
    }

    public static List<HologramGroup> listFromTag(ListTag list) {
        List<HologramGroup> groups = new ArrayList<>(list.size());
        for (int i = 0; i < list.size() && groups.size() < MAX_GROUPS; i++) {
            CompoundTag tag = list.getCompound(i);
            String id = tag.getString("id");
            if (id.isEmpty())
                continue;
            String name = tag.contains("name") ? tag.getString("name") : id;
            boolean visible = !tag.contains("visible") || tag.getBoolean("visible");
            float scale = tag.contains("scale") ? tag.getFloat("scale") : 1.0F;
            float hoverPeriod = tag.contains("hoverPeriod") ? tag.getFloat("hoverPeriod") : 4.0F;
            List<HologramEffect> effects = tag.contains("effects")
                    ? HologramEffect.listFromTag(tag.getList("effects", Tag.TAG_COMPOUND))
                    : List.of();
            groups.add(new HologramGroup(id, name, visible,
                    tag.getFloat("dx"), tag.getFloat("dy"), tag.getFloat("dz"),
                    tag.getFloat("yaw"), tag.getFloat("pitch"), tag.getFloat("roll"),
                    scale, tag.getFloat("spin"), tag.getByte("spinAxis"),
                    tag.getFloat("hover"), hoverPeriod, tag.getBoolean("fullbright"), effects));
        }
        return groups;
    }
}
