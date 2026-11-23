package dev.watdafawx.foxstweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.watdafawx.foxstweaks.anchor.AnchorTier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.UUID;

/**
 * Gives a mob spawning near a spawn anchor the Apotheosis world tier of the anchor's owner.
 *
 * <p>Apotheosis decides everything about a spawn (tier augments, elites, invaders) from the nearest
 * player in the dimension: with nobody there it bails out, and with someone far away it refuses
 * invader spawns as too distant. Both lookups are wrapped here so an anchor can supply the player
 * instead; {@link AnchorTier} keeps the real player whenever one is genuinely close.
 *
 * <p>Targeted by string with {@code require = 0}, so an Apotheosis update that moves these calls
 * leaves the anchor as it was rather than failing at load.
 */
@Mixin(targets = "dev.shadowsoffire.apotheosis.mobs.ApothMobEvents", remap = false)
public class ApothMobEventsTierMixin {

    @WrapOperation(method = "finalizeMobSpawns", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/ServerLevelAccessor;getNearestPlayer(DDDDZ)Lnet/minecraft/world/entity/player/Player;"))
    private Player foxstweaks$anchorTierForSpawn(ServerLevelAccessor level, double x, double y, double z,
                                                 double distance, boolean creative, Operation<Player> original) {
        Player real = original.call(level, x, y, z, distance, creative);
        if (!(level instanceof ServerLevel serverLevel))
            return real;
        return AnchorTier.replace(real, serverLevel, new Vec3(x, y, z));
    }

    /**
     * The elite (miniboss) transform happens on the entity join, not in the spawn event, and resolves
     * the player by the UUID recorded at spawn time. A fake stand-in is not a real player, so this
     * lookup returns null for an offline owner and the mob would stay an ordinary one forever.
     */
    @WrapOperation(method = "delayedEliteMobs", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getPlayerByUUID(Ljava/util/UUID;)Lnet/minecraft/world/entity/player/Player;"))
    private Player foxstweaks$anchorTierForElite(Level level, UUID owner, Operation<Player> original) {
        Player real = original.call(level, owner);
        if (!(level instanceof ServerLevel serverLevel))
            return real;
        return AnchorTier.replaceOwner(real, serverLevel, owner);
    }

    @WrapOperation(method = "delayedEliteMobs", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;getNearestPlayer(Lnet/minecraft/world/entity/Entity;D)Lnet/minecraft/world/entity/player/Player;"))
    private Player foxstweaks$anchorTierForEliteFallback(Level level, Entity entity, double distance,
                                                         Operation<Player> original) {
        Player real = original.call(level, entity, distance);
        if (!(level instanceof ServerLevel serverLevel))
            return real;
        return AnchorTier.replace(real, serverLevel, entity.position());
    }
}
