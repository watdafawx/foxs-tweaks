package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One layer of a hologram's effects. Layers are a list, so they stack: a red {@code TINT} plus a
 * {@code FLICKER} plus a {@code TREMOR} reads as a failing projector, which is the point.
 *
 * <p>Every effect here is pure maths on the draw call - no shaders, no framebuffers, no post-processing
 * pass. That is deliberate: a real distortion pass needs an off-screen target per hologram, which
 * collides head-on with shader packs and costs a full-screen copy. These are cheap, look right, and work
 * inside Iris' world pass.
 *
 * <p>{@code speed} is in Hz (cycles per second) and each type has its own natural default, because
 * "speed 1" means completely different things for a 6 Hz tremor and a 0.6 Hz wobble.
 */
public record HologramEffect(Type type, float strength, float speed, int color) {

    public enum Type {
        /** Multiplies the block's colours. The stored {@code color} is the tint. */
        TINT,
        /** Hides the hologram for a few frames at a time. Works on solid render types, unlike alpha. */
        FLICKER,
        /** Small, fast rotation jitter. */
        TREMOR,
        /** Slow scale/skew breathing. */
        WOBBLE,
        /** Periodic vertical snap, like a mistimed projection. */
        JUMP
    }

    /** The default hologram cyan, used by {@link Type#TINT}. */
    public static final int DEFAULT_COLOR = 0x66E0FF;

    public HologramEffect {
        strength = Mth.clamp(strength, 0.0F, 2.0F);
        speed = Mth.clamp(speed, 0.05F, 20.0F);
        color &= 0xFFFFFF;
    }

    /** The defaults a new layer starts with: strong enough to see, gentle enough to keep. */
    public static HologramEffect of(Type type) {
        return switch (type) {
            case TINT -> new HologramEffect(type, 0.65F, 1.0F, DEFAULT_COLOR);
            case FLICKER -> new HologramEffect(type, 0.5F, 7.0F, DEFAULT_COLOR);
            case TREMOR -> new HologramEffect(type, 0.6F, 5.0F, DEFAULT_COLOR);
            case WOBBLE -> new HologramEffect(type, 0.35F, 0.6F, DEFAULT_COLOR);
            case JUMP -> new HologramEffect(type, 0.4F, 0.8F, DEFAULT_COLOR);
        };
    }

    /** The next unused type, so "Add effect" never stacks two identical layers by accident. */
    public static Type nextType(List<HologramEffect> existing) {
        for (Type type : Type.values()) {
            boolean used = false;
            for (HologramEffect effect : existing) {
                if (effect.type() == type) {
                    used = true;
                    break;
                }
            }
            if (!used)
                return type;
        }
        return Type.TINT;
    }

    public String label() {
        return this.type.name().charAt(0) + this.type.name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", this.type.name());
        tag.putFloat("strength", this.strength);
        tag.putFloat("speed", this.speed);
        tag.putInt("color", this.color);
        return tag;
    }

    public static ListTag listToTag(List<HologramEffect> effects) {
        ListTag list = new ListTag();
        for (HologramEffect effect : effects)
            list.add(effect.toTag());
        return list;
    }

    public static List<HologramEffect> listFromTag(ListTag list) {
        List<HologramEffect> effects = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            Type type;
            try {
                type = Type.valueOf(tag.getString("type"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            effects.add(new HologramEffect(type, tag.getFloat("strength"), tag.getFloat("speed"),
                    tag.contains("color") ? tag.getInt("color") : DEFAULT_COLOR));
        }
        return effects;
    }

    /** True when this tag is one of ours; used to skip junk in a saved list. */
    public static boolean looksValid(Tag tag) {
        return tag instanceof CompoundTag compound && !compound.getString("type").isEmpty();
    }
}
