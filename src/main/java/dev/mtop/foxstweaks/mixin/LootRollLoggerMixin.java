package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.debug.LootRollLog;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.entity.player.Player;

/**
 * Debugging aid behind {@code debugLogLootRolls} (off by default). Vanilla rolls a container's loot table the
 * first time anything reads its contents, not just when a player opens it. Structure chests that a mod reads
 * during generation or chunk load end up as plain chests full of rolled loot, which Lootr can no longer convert.
 * This logs who did it: a stack trace for each of the first N rolls with no player. State lives in
 * {@link LootRollLog} because an interface mixin may not declare fields.
 */
@Mixin(RandomizableContainer.class)
public interface LootRollLoggerMixin {
    @Inject(method = "unpackLootTable(Lnet/minecraft/world/entity/player/Player;)V", at = @At("HEAD"))
    private void foxstweaks$logRoll(Player player, CallbackInfo ci) {
        LootRollLog.onRoll((RandomizableContainer) this, player);
    }
}
