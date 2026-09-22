package dev.mtop.foxstweaks.tacz;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;

/**
 * Which gunsmith table (any gun pack's workbench - all the same TACZ block entity, just a different
 * {@code BlockId}) each player last opened. {@code GunSmithTableMenu#doCraft} is given only the
 * player, never a block position, so this is how {@code GunSmithTableMenuMixin} learns where to
 * centre a nearby-storage scan.
 *
 * <p>chisel: entries are never explicitly removed - only ever overwritten on the next open. A stale
 * entry can only be read if a craft packet somehow arrives without the screen being open, which the
 * game's own menu-id check already rejects; at worst that reuses a now-irrelevant old position, no
 * different from nearby storage simply not being reachable.
 */
public final class WorkbenchOpenTracker {
    private static final Map<UUID, BlockPos> OPEN = new HashMap<>();

    private WorkbenchOpenTracker() {
    }

    public static void open(Player player, BlockPos pos) {
        OPEN.put(player.getUUID(), pos);
    }

    public static BlockPos get(Player player) {
        return OPEN.get(player.getUUID());
    }
}
