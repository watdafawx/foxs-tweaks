package dev.mtop.foxstweaks.ars.compat;

import com.hollingsworth.arsnouveau.api.entity.IDispellable;
import com.hollingsworth.arsnouveau.api.entity.ISummon;
import com.hollingsworth.arsnouveau.api.source.SourcelinkEventQueue;
import com.hollingsworth.arsnouveau.common.block.tile.AgronomicSourcelinkTile;
import com.hollingsworth.arsnouveau.common.block.tile.VitalicSourcelinkTile;
import com.hollingsworth.arsnouveau.common.datagen.BlockTagProvider;
import com.hollingsworth.arsnouveau.common.lib.EntityTags;

import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.level.block.CropGrowEvent;

/**
 * Feeds Ars Nouveau's event-driven sourcelinks from growth and deaths that other mods only simulate,
 * so Ars' own listeners ({@code CropGrowEvent.Post}, {@code LivingDeathEvent}) never see them.
 *
 * <p>Goes through {@link SourcelinkEventQueue#addManaEvent}, the same call Ars' own listeners make,
 * with the same amounts - so range (15 blocks), "is it full" and the particle all stay Ars' own.
 * The event argument is only ever handed to {@code SourcelinkTile#eventInRange}, which ignores it;
 * a real (never posted) event is built anyway, for any addon sourcelink that does read it.
 *
 * <p>The only class touching Ars types. Callers must check {@link #loaded()} first.
 */
public final class ArsSourcelinks {
    private static final boolean LOADED = ModList.get().isLoaded(FoxsTweaks.ARS_NOUVEAU);

    private ArsSourcelinks() {
    }

    /** Plain field read, so safe on a pack without Ars - no Ars type is resolved until a method below runs. */
    public static boolean loaded() {
        return LOADED;
    }

    /** One growth of whatever {@code seed} plants, at {@code pos}. Amounts mirror {@code AgronomicSourcelinkTile}. */
    public static void plantGrew(Level level, BlockPos pos, ItemStack seed) {
        var state = seed.getItem() instanceof BlockItem block ? block.getBlock().defaultBlockState() : null;
        int source = 20;
        if (state != null && state.is(BlockTags.SAPLINGS))
            source = state.is(BlockTagProvider.MAGIC_SAPLINGS) ? 100 : 50;
        else if (state != null && state.is(BlockTagProvider.MAGIC_PLANTS))
            source += 25;

        var grown = level.getBlockState(pos);
        SourcelinkEventQueue.addManaEvent(level, AgronomicSourcelinkTile.class, source,
                new CropGrowEvent.Post(level, pos, grown, grown), pos);
    }

    /** A simulated death of {@code mob}, which may not be in any level. Mirrors {@code VitalicSourcelinkTile#livingDeath}. */
    public static void mobDied(Level level, BlockPos pos, LivingEntity mob) {
        if (mob instanceof IDispellable || mob instanceof ISummon || blacklisted(mob.getType()))
            return;

        SourcelinkEventQueue.addManaEvent(level, VitalicSourcelinkTile.class, 200,
                new LivingDeathEvent(mob, level.damageSources().generic()), pos);
    }

    /** As {@link #mobDied}, for a death with no entity at all (Hostile Neural Networks). */
    public static void mobDied(Level level, BlockPos pos, EntityType<?> type) {
        if (blacklisted(type))
            return;

        SourcelinkEventQueue.addManaEvent(level, VitalicSourcelinkTile.class, 200, null, pos);
    }

    private static boolean blacklisted(EntityType<?> type) {
        return type.is(EntityTags.VITALIC_DEATH_BLACKLIST);
    }
}
