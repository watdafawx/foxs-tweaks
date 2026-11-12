package dev.watdafawx.foxstweaks.mixin;

import java.util.Comparator;
import java.util.Optional;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.dimension.CopyPortals;
import dev.watdafawx.foxstweaks.dimension.OverworldCopy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.portal.PortalForcer;

/**
 * Vanilla's Nether travel looks for the closest portal block by point of interest, which would pick a
 * portal to the overworld copy standing nearby and drop the player into it. Same search, minus ours.
 */
@Mixin(PortalForcer.class)
public abstract class PortalForcerMixin {
    @Shadow
    @Final
    protected ServerLevel level;

    @Inject(method = "findClosestPortalPosition", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$skipCopyPortals(BlockPos center, boolean nether, WorldBorder border,
            CallbackInfoReturnable<Optional<BlockPos>> cir) {
        if (!OverworldCopy.enabled()) {
            return;
        }

        PoiManager poi = level.getPoiManager();
        int radius = nether ? 16 : 128;
        poi.ensureLoadedAndValid(level, center, radius);

        cir.setReturnValue(poi.getInSquare(t -> t.is(PoiTypes.NETHER_PORTAL), center, radius, PoiManager.Occupancy.ANY)
                .map(PoiRecord::getPos)
                .filter(border::isWithinBounds)
                .filter(p -> level.getBlockState(p).hasProperty(BlockStateProperties.HORIZONTAL_AXIS))
                .filter(p -> CopyPortals.shapeAround(level, p, level.getBlockState(p)).isEmpty())
                .min(Comparator.<BlockPos>comparingDouble(p -> p.distSqr(center)).thenComparingInt(Vec3i::getY)));
    }
}
