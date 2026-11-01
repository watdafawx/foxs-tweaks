package dev.watdafawx.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Bedrock Ores queues every unconfigured bedrock ore it sees for removal in a plain
 * {@code ArrayList} per level, written by {@code schedule} and drained by the level tick on the
 * server thread. A bedrock ore block entity calls {@code schedule} when it is added to a level, and
 * mods that build chunks on their own threads (Distant Horizons' server-side world generation in
 * {@code FEATURES} mode) do that off the server thread. The unsynchronized list then loses writes or
 * gains {@code null} slots, and the next tick crashes in {@code isLoaded} with
 * "Cannot invoke BlockPos.getX() because pos is null".
 *
 * <p>Off-thread calls are handed to the server thread instead, so the list only ever changes there,
 * and a {@code null} that got in anyway is skipped. Targeted by name; against Bedrock Ores
 * 2.2.1 - re-check {@code UnconfiguredOreCleanup} on update.
 */
@Mixin(targets = "li.cil.bedrockores.common.block.entity.UnconfiguredOreCleanup", remap = false)
public abstract class BedrockOresCleanupMixin {
    @Shadow
    public static void schedule(ServerLevel level, BlockPos pos) {
        throw new AssertionError();
    }

    @Inject(method = "schedule", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$onServerThread(ServerLevel level, BlockPos pos, CallbackInfo ci) {
        MinecraftServer server = level.getServer();
        if (server != null && !server.isSameThread()) {
            BlockPos at = pos.immutable();
            server.execute(() -> schedule(level, at));
            ci.cancel();
        }
    }

    @Inject(method = "isLoaded", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$skipNull(ServerLevel level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (pos == null)
            cir.setReturnValue(false);
    }
}
