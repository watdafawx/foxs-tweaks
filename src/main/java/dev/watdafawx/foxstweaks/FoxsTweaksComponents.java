package dev.watdafawx.foxstweaks;

import dev.watdafawx.foxstweaks.apotheosis.DisabledAffixes;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Registered unconditionally - the component holds no Apotheosis type, only ids and levels. */
public final class FoxsTweaksComponents {
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, FoxsTweaks.MODID);

    /** Affixes the toggle GUI pulled off the item, so they can be restored (see {@code compat.ApothicAffixToggle}). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<DisabledAffixes>> DISABLED_AFFIXES =
            COMPONENTS.register("disabled_affixes", () -> DataComponentType.<DisabledAffixes>builder()
                    .persistent(DisabledAffixes.CODEC)
                    .networkSynchronized(DisabledAffixes.STREAM_CODEC)
                    .build());

    /** Enchantments the toggle overlay pulled off the item, so they can be restored (see {@code enchant.EnchantToggle}). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.watdafawx.foxstweaks.enchant.DisabledEnchants>> DISABLED_ENCHANTS =
            COMPONENTS.register("disabled_enchants", () -> DataComponentType.<dev.watdafawx.foxstweaks.enchant.DisabledEnchants>builder()
                    .persistent(dev.watdafawx.foxstweaks.enchant.DisabledEnchants.CODEC)
                    .networkSynchronized(dev.watdafawx.foxstweaks.enchant.DisabledEnchants.STREAM_CODEC)
                    .build());

    /** Abilities a relic levels up on its own and how points are shared (see {@code relics.AutoLevelHandler}). Absent = off. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.watdafawx.foxstweaks.relics.AutoLevelSetting>> AUTO_LEVEL =
            COMPONENTS.register("auto_level", () -> DataComponentType.<dev.watdafawx.foxstweaks.relics.AutoLevelSetting>builder()
                    .persistent(dev.watdafawx.foxstweaks.relics.AutoLevelSetting.CODEC)
                    .networkSynchronized(dev.watdafawx.foxstweaks.relics.AutoLevelSetting.STREAM_CODEC)
                    .build());

    /** Legacy single ability id from before {@link #AUTO_LEVEL}; still read, and replaced on the next change. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> AUTO_LEVEL_ABILITY =
            COMPONENTS.register("auto_level_ability", () -> DataComponentType.<String>builder()
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
                    .build());

    /** When true, suppresses particles spawned by this relic while equipped (see {@code relics.RelicParticles}). Absent = show particles. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Boolean>> HIDE_RELIC_PARTICLES =
            COMPONENTS.register("hide_relic_particles", () -> DataComponentType.<Boolean>builder()
                    .persistent(com.mojang.serialization.Codec.BOOL)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL)
                    .build());

    /** Settings and selection state for the Biome Changer Wand. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.watdafawx.foxstweaks.terraform.BiomeWandConfig>> BIOME_WAND =
            COMPONENTS.register("biome_wand", () -> DataComponentType.<dev.watdafawx.foxstweaks.terraform.BiomeWandConfig>builder()
                    .persistent(dev.watdafawx.foxstweaks.terraform.BiomeWandConfig.CODEC)
                    .networkSynchronized(dev.watdafawx.foxstweaks.terraform.BiomeWandConfig.STREAM_CODEC)
                    .build());

    /** Settings and selection state for the Terrain Flattener Wand. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<dev.watdafawx.foxstweaks.terraform.FlattenWandConfig>> FLATTEN_WAND =
            COMPONENTS.register("flatten_wand", () -> DataComponentType.<dev.watdafawx.foxstweaks.terraform.FlattenWandConfig>builder()
                    .persistent(dev.watdafawx.foxstweaks.terraform.FlattenWandConfig.CODEC)
                    .networkSynchronized(dev.watdafawx.foxstweaks.terraform.FlattenWandConfig.STREAM_CODEC)
                    .build());

    private FoxsTweaksComponents() {
    }
}
