package dev.watdafawx.foxstweaks.relics;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Per-relic particle suppression flag ({@link FoxsTweaksComponents#HIDE_RELIC_PARTICLES}) and
 * thread-local suppression scope active while a hidden-particle curio is executing {@code curioTick}.
 *
 * <p>Imports no Relics or Curios types so mixins and network handlers can call it unconditionally.
 */
public final class RelicParticles {
    private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private RelicParticles() {
    }

    public static boolean isHidden(ItemStack stack) {
        return !stack.isEmpty() && Boolean.TRUE.equals(stack.get(FoxsTweaksComponents.HIDE_RELIC_PARTICLES.get()));
    }

    public static void setHidden(ItemStack stack, boolean hidden) {
        if (stack.isEmpty())
            return;

        if (hidden) {
            stack.set(FoxsTweaksComponents.HIDE_RELIC_PARTICLES.get(), Boolean.TRUE);
        } else {
            stack.remove(FoxsTweaksComponents.HIDE_RELIC_PARTICLES.get());
        }
    }

    public static void push() {
        DEPTH.get()[0]++;
    }

    public static void pop() {
        int[] d = DEPTH.get();
        if (d[0] > 0)
            d[0]--;
    }

    public static boolean isSuppressed() {
        return DEPTH.get()[0] > 0;
    }

    public static boolean shouldSuppressFor(ItemStack stack) {
        return Config.enabled(Config.RELIC_PARTICLE_TOGGLE) && isHidden(stack);
    }

    /** Server-side handler for {@link RelicParticlesPayload}, menu-slot based like Relics' own option packets. */
    public static void applyFromClient(ServerPlayer player, int container, int slot, boolean hidden) {
        var menu = player.containerMenu;
        if (menu.containerId != container || !menu.isValidSlotIndex(slot))
            return;

        ItemStack stack = menu.getSlot(slot).getItem();
        if (stack.isEmpty())
            return;

        setHidden(stack, hidden);
        menu.broadcastChanges();
    }
}
