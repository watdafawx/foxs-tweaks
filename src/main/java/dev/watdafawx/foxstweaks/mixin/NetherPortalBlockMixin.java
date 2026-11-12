package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.dimension.CopyPortals;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.portal.DimensionTransition;

/**
 * Portals to the overworld copy are vanilla portal blocks in a frame of another block. Vanilla would
 * pop them on the next neighbour update (its frame check is obsidian only) and send them to the Nether,
 * so a portal block that sits in a complete frame of the configured block is handled here instead.
 * Anything else, including every Nether portal, falls through to vanilla untouched.
 */
@Mixin(NetherPortalBlock.class)
public abstract class NetherPortalBlockMixin {
    @Inject(method = "updateShape", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$keepCopyPortal(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
            BlockPos pos, BlockPos neighborPos, CallbackInfoReturnable<BlockState> cir) {
        if (CopyPortals.shapeAround(level, pos, state).isPresent()) {
            cir.setReturnValue(state);
        }
    }

    /** No wait for our portals when instant teleport is on; the entity is standing in the portal block. */
    @Inject(method = "getPortalTransitionTime", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$instant(ServerLevel level, Entity entity, CallbackInfoReturnable<Integer> cir) {
        if (!Config.OVERWORLD_COPY_INSTANT.get()) {
            return;
        }

        BlockPos feet = entity.blockPosition();

        for (BlockPos pos : new BlockPos[] { feet, feet.above(), BlockPos.containing(entity.getEyePosition()) }) {
            if (CopyPortals.shapeAround(level, pos, level.getBlockState(pos)).isPresent()) {
                cir.setReturnValue(0);
                return;
            }
        }
    }

    @Inject(method = "getPortalDestination", at = @At("HEAD"), cancellable = true)
    private void foxstweaks$copyDestination(ServerLevel level, Entity entity, BlockPos pos,
            CallbackInfoReturnable<DimensionTransition> cir) {
        var shape = CopyPortals.shapeAround(level, pos, level.getBlockState(pos));

        if (shape.isPresent()) {
            cir.setReturnValue(CopyPortals.destination(level, entity, pos, shape.get()));
        }
    }
}
