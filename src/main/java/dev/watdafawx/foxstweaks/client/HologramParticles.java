package dev.watdafawx.foxstweaks.client;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustColorTransitionOptions;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns any {@link ParticleType} (simple or parameterized — vanilla or modded) into a spawnable
 * {@link ParticleOptions}, respecting an optional layer tint color. Unsupported codecs are cached once
 * so neither the picker nor the per-tick emitter retries them.
 */
public final class HologramParticles {
    private static final int DEFAULT_RGB = 0xFFD966;

    private static final Map<ResourceLocation, ParticleOptions> DEFAULT_CACHE = new ConcurrentHashMap<>();
    private static final Set<ResourceLocation> UNSUPPORTED = ConcurrentHashMap.newKeySet();

    private HologramParticles() {
    }

    public static boolean isSupported(ResourceLocation id, ParticleType<?> type) {
        if (type instanceof SimpleParticleType)
            return true;
        if (UNSUPPORTED.contains(id))
            return false;
        if (DEFAULT_CACHE.containsKey(id))
            return true;
        ParticleOptions opt = build(id, type, -1);
        if (opt != null) {
            DEFAULT_CACHE.put(id, opt);
            return true;
        }
        UNSUPPORTED.add(id);
        return false;
    }

    @Nullable
    public static ParticleOptions resolve(ResourceLocation id, int tintRgb) {
        if (UNSUPPORTED.contains(id))
            return null;
        ParticleType<?> type = BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (type == null)
            return null;
        if (type instanceof SimpleParticleType simple)
            return simple;
        if (tintRgb < 0) {
            ParticleOptions cached = DEFAULT_CACHE.get(id);
            if (cached != null)
                return cached;
        }
        ParticleOptions built = build(id, type, tintRgb);
        if (built == null) {
            if (tintRgb < 0)
                UNSUPPORTED.add(id);
            return null;
        }
        if (tintRgb < 0)
            DEFAULT_CACHE.put(id, built);
        return built;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static ParticleOptions build(ResourceLocation id, ParticleType<?> type, int tintRgb) {
        try {
            int rgb = tintRgb >= 0 ? (tintRgb & 0xFFFFFF) : DEFAULT_RGB;
            if (type == ParticleTypes.DUST)
                return new DustParticleOptions(rgbVec(rgb), 1.0F);
            if (type == ParticleTypes.DUST_COLOR_TRANSITION)
                return new DustColorTransitionOptions(rgbVec(rgb), new Vector3f(0.45F, 0.85F, 1.0F), 1.0F);
            if (type == ParticleTypes.ENTITY_EFFECT)
                return ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, 0xFF000000 | rgb);
            if (type == ParticleTypes.BLOCK || type == ParticleTypes.BLOCK_MARKER
                    || type == ParticleTypes.FALLING_DUST || type == ParticleTypes.DUST_PILLAR) {
                return new BlockParticleOption((ParticleType<BlockParticleOption>) type, Blocks.AMETHYST_BLOCK.defaultBlockState());
            }
            if (type == ParticleTypes.ITEM)
                return new ItemParticleOption(ParticleTypes.ITEM, new ItemStack(Items.NETHER_STAR));

            // Generic Codec decode for modded parameterized particles (Relics, Cataclysm, Iron's Spellbooks, Ars, etc.).
            var codec = type.codec().codec();
            var empty = codec.parse(NbtOps.INSTANCE, new CompoundTag()).result();
            if (empty.isPresent() && empty.get() instanceof ParticleOptions opt)
                return opt;

            var populated = codec.parse(NbtOps.INSTANCE, defaultsTag(rgb)).result();
            if (populated.isPresent() && populated.get() instanceof ParticleOptions opt)
                return opt;
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Vector3f rgbVec(int rgb) {
        return new Vector3f(
                ((rgb >> 16) & 0xFF) / 255.0F,
                ((rgb >> 8) & 0xFF) / 255.0F,
                (rgb & 0xFF) / 255.0F);
    }

    private static CompoundTag defaultsTag(int rgb) {
        float r = ((rgb >> 16) & 0xFF) / 255.0F;
        float g = ((rgb >> 8) & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        CompoundTag t = new CompoundTag();
        t.putInt("color", rgb);
        t.putFloat("r", r);
        t.putFloat("g", g);
        t.putFloat("b", b);
        t.putFloat("a", 1.0F);
        t.putFloat("red", r);
        t.putFloat("green", g);
        t.putFloat("blue", b);
        t.putFloat("alpha", 1.0F);
        t.putFloat("scale", 1.0F);
        t.putFloat("size", 1.0F);
        t.putFloat("diameter", 0.25F);
        t.putInt("lifetime", 20);
        t.putInt("duration", 20);
        t.putInt("age", 20);
        t.putInt("maxAge", 20);
        t.putInt("max_age", 20);
        t.putFloat("gravity", 0.0F);
        t.putFloat("speed", 0.0F);
        t.putFloat("roll", 0.0F);
        t.putInt("delay", 0);
        t.putBoolean("resize", false);
        t.putBoolean("physical", false);
        ListTag vec = new ListTag();
        vec.add(FloatTag.valueOf(r));
        vec.add(FloatTag.valueOf(g));
        vec.add(FloatTag.valueOf(b));
        t.put("from_color", vec.copy());
        t.put("to_color", vec);
        return t;
    }
}
