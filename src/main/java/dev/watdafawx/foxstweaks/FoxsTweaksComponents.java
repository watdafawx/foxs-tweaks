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

    /** Ability id a relic levels up on its own (see {@code relics.AutoLevelHandler}). Absent = auto-level off. */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<String>> AUTO_LEVEL_ABILITY =
            COMPONENTS.register("auto_level_ability", () -> DataComponentType.<String>builder()
                    .persistent(com.mojang.serialization.Codec.STRING)
                    .networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8)
                    .build());

    private FoxsTweaksComponents() {
    }
}
