package dev.mtop.foxstweaks.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.ars.compat.ArsSourcelinks;
import net.darkhax.botanypots.common.api.context.BlockEntityContext;
import net.darkhax.botanypots.common.api.context.BotanyPotContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;

/**
 * A crop in a pot never grows in the world, so Ars' Agronomic Sourcelink (which listens for
 * {@code CropGrowEvent.Post}) never hears of it. Each harvest is reported as one growth instead.
 *
 * <p>Hooked on the crop rather than on any pot: every pot mod here (Botany Pots itself, its tiered,
 * power and dimensional pots, Botany Gardens) calls {@code Crop#onHarvest} from its own tick, and
 * {@code BasicCrop} is the only {@code Crop} implementation ({@code BlockDerivedCrop} extends it
 * without overriding this). Botany Gardens calls it once per loot roll, so its yield upgrades raise
 * the source too - same as more plants would.
 */
@Mixin(targets = "net.darkhax.botanypots.common.impl.data.recipe.crop.BasicCrop", remap = false)
public abstract class BotanyPotsCropMixin {
    @Inject(method = "onHarvest", at = @At("HEAD"))
    private void foxstweaks$feedSourcelink(BotanyPotContext context, Level level, Consumer<ItemStack> drops, CallbackInfo ci) {
        if (!(level instanceof ServerLevel) || !Config.BOTANY_POTS_SOURCE.get() || !ArsSourcelinks.loaded())
            return;

        var pos = positionOf(context);
        if (pos != null)
            ArsSourcelinks.plantGrew(level, pos, context.getSeedItem());
    }

    /**
     * {@code BotanyPotContext} has no position of its own. Botany Pots' own context has the pot; any
     * other (Botany Gardens' per-cell one) is asked for the loot origin it would drop at.
     */
    private static BlockPos positionOf(BotanyPotContext context) {
        if (context instanceof BlockEntityContext pot)
            return pot.pot().getBlockPos();

        try {
            var origin = context.createLootParams(Blocks.AIR.defaultBlockState()).getParamOrNull(LootContextParams.ORIGIN);
            return origin == null ? null : BlockPos.containing(origin);
        } catch (RuntimeException e) {
            // A context with no world behind it (Botany Pots' own debug commands) - nothing grew anywhere.
            return null;
        }
    }
}
