package dev.mtop.foxstweaks;

import dev.mtop.foxstweaks.apotheosis.DisabledAffixes;
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

    private FoxsTweaksComponents() {
    }
}
