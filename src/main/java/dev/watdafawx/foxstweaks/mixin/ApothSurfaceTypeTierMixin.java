package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.watdafawx.foxstweaks.anchor.AnchorTier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Crash guard for {@code SurfaceType#skyOrSameVerticalSlice}, on the invader (boss) spawn path.
 *
 * <p>It asks the level for the nearest player and dereferences the result without a null check. That
 * is unreachable while Apotheosis' own "no player context" bail-out protects it, but
 * {@link ApothMobEventsTierMixin} deliberately gets past that bail-out with an anchor's stand-in
 * player, so an underground farm with nobody online would walk straight into the NPE (the Overworld
 * default surface rule is exactly this one).
 *
 * <p>Answering with the same stand-in also makes its 8-block vertical-slice check pass, which is what
 * lets a roofed farm produce bosses at all.
 */
@Mixin(targets = "dev.shadowsoffire.apotheosis.mobs.util.SurfaceType", remap = false)
public class ApothSurfaceTypeTierMixin {

    @WrapOperation(method = "skyOrSameVerticalSlice", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private static Player foxstweaks$anchorTierForSurface(ServerLevelAccessor level, double x, double y, double z,
                                                          double distance, boolean creative, Operation<Player> original) {
        Player real = original.call(level, x, y, z, distance, creative);
        if (!(level instanceof ServerLevel serverLevel))
            return real;
        return AnchorTier.replace(real, serverLevel, new Vec3(x, y, z));
    }
}
