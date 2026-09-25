package dev.mtop.foxstweaks.ars.compat;

import java.util.List;

import dev.shadowsoffire.hostilenetworks.data.DataModel;
import dev.shadowsoffire.hostilenetworks.data.EntityDataModel;
import dev.shadowsoffire.hostilenetworks.item.DataModelItem;
import dev.shadowsoffire.placebo.reload.DynamicHolder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Turns one finished simulation - in Hostile Neural Networks' chamber or any addon machine built on
 * its data models - into Vitalic Sourcelink deaths at the machine: one per mob the model stack holds.
 *
 * <p>Every machine hands its callers the data model stack it just updated, so this only needs that
 * stack. A plain HNN model holds one mob; ExtraHNN's merged model holds up to four in its own
 * {@code extrahnn:extra_data_model} component, read by id so ExtraHNN is not a compile dependency.
 * Block data models are skipped - mining a block is not a kill.
 *
 * <p>Touches HNN types, so only reach it from code that runs inside an HNN (or addon) machine.
 */
public final class HnnSimulations {
    private static final ResourceLocation EXTRA_DATA_MODEL = ResourceLocation.fromNamespaceAndPath("extrahnn", "extra_data_model");

    private HnnSimulations() {
    }

    public static void finished(BlockEntity machine, ItemStack model) {
        var level = machine.getLevel();
        if (level == null || level.isClientSide() || !ArsSourcelinks.loaded())
            return;

        for (var holder : modelsOf(model))
            if (holder.isBound() && holder.get() instanceof EntityDataModel mob)
                ArsSourcelinks.mobDied(level, machine.getBlockPos(), mob.entity());
    }

    @SuppressWarnings("unchecked")
    private static List<DynamicHolder<DataModel>> modelsOf(ItemStack model) {
        var extra = BuiltInRegistries.DATA_COMPONENT_TYPE.get(EXTRA_DATA_MODEL);
        if (extra != null && model.get(extra) instanceof List<?> merged)
            return (List<DynamicHolder<DataModel>>) merged;

        return List.of(DataModelItem.getStoredModel(model));
    }
}
