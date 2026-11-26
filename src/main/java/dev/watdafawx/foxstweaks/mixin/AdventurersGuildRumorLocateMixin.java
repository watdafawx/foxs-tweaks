package dev.watdafawx.foxstweaks.mixin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.datafixers.util.Pair;
import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.Structure;

/**
 * Taverns &amp; Adventurers' rumor and dungeon-map lookups ({@code RumorService.locate}) call
 * {@code ChunkGenerator#findNearestMapStructure} straight on the server thread. On a big modded world that search
 * can sit on chunk IO or a slow worldgen noise (ReTerraForged) for a minute, and the watchdog then kills the
 * server ("a single server tick took 60000 seconds").
 *
 * <p>The search runs on one worker thread and the tick waits for it at most {@code taverns locate wait} ms. A
 * search that is not finished by then counts as "nothing found" (the mod already handles that, it just tries
 * another rumor), and while the worker is still stuck on it further searches are refused at once instead of
 * stacking up. Against Taverns &amp; Adventurers 0.4.5 - re-check the member names on update.
 */
@Mixin(targets = "com.theeclipsedspire.adventurersguild.expedition.RumorService", remap = false)
public abstract class AdventurersGuildRumorLocateMixin {
    private static final ExecutorService foxstweaks$worker = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "foxstweaks-rumor-locate");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean foxstweaks$busy = new AtomicBoolean();

    @WrapOperation(method = "locate(Lnet/minecraft/server/level/ServerLevel;Ljava/util/List;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;",
            require = 0,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkGenerator;findNearestMapStructure(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/HolderSet;Lnet/minecraft/core/BlockPos;IZ)Lcom/mojang/datafixers/util/Pair;"))
    private static Pair<BlockPos, Holder<Structure>> foxstweaks$boundedLocate(ChunkGenerator generator, ServerLevel level,
            HolderSet<Structure> structures, BlockPos origin, int radius, boolean skipKnown,
            Operation<Pair<BlockPos, Holder<Structure>>> original) {
        int wait = Config.TAVERNS_LOCATE_WAIT_MS.get();
        if (wait <= 0)
            return original.call(generator, level, structures, origin, radius, skipKnown);
        if (!foxstweaks$busy.compareAndSet(false, true))
            return null;
        Future<Pair<BlockPos, Holder<Structure>>> job;
        try {
            job = foxstweaks$worker.submit(() -> {
                try {
                    return original.call(generator, level, structures, origin, radius, skipKnown);
                } finally {
                    foxstweaks$busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            foxstweaks$busy.set(false);
            return null;
        }
        try {
            return job.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            LogUtils.getLogger().warn("Taverns & Adventurers: structure search near {} took over {} ms, giving up on it (worker keeps going)", origin, wait);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            LogUtils.getLogger().warn("Taverns & Adventurers: structure search failed", e);
            return null;
        }
    }
}
