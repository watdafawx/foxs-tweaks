package dev.watdafawx.foxstweaks.debug;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.world.RandomizableContainer;

/** State for {@code LootRollLoggerMixin}; interface mixins cannot hold fields of their own. */
public final class LootRollLog {
    private static final Logger LOG = LogUtils.getLogger();
    private static final AtomicInteger LOGGED = new AtomicInteger();

    private LootRollLog() {
    }

    public static void onRoll(RandomizableContainer container, net.minecraft.world.entity.player.Player player) {
        if (!Config.SPEC.isLoaded()) {
            return;
        }

        int limit = Config.DEBUG_LOG_LOOT_ROLLS.get();
        if (limit <= 0) {
            return;
        }
        if (container.getLootTable() == null || (container.getLevel() != null && container.getLevel().isClientSide())) {
            return;
        }

        int n = LOGGED.incrementAndGet();
        if (n <= limit) {
            LOG.warn("Loot rolled ({}/{}) by {} level={}: {} at {} table {}", n, limit,
                    player == null ? "no player" : player.getClass().getName() + " " + player.getGameProfile().getName(),
                    container.getLevel() == null ? "null" : container.getLevel().getClass().getSimpleName(),
                    container.getClass().getName(), container.getBlockPos(), container.getLootTable().location(),
                    new Throwable("call path"));
        }
    }
}
