package dev.mtop.foxstweaks.tacz;

import java.util.HashMap;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import dev.mtop.foxstweaks.storage.NearbyItemsPayload;
import dev.mtop.foxstweaks.storage.NearbyStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps a player who has a gunsmith table open told what the storage around it holds, so
 * {@code GunSmithTableScreenMixin} can fold it into the table's own ingredient-count display - which
 * otherwise only reads the player's own inventory, showing "not enough" for something nearby storage
 * would actually cover once crafted.
 *
 * <p>Unlike {@code pointblank.PrinterNetwork}, TACZ's table has no block-entity tick of its own to
 * piggyback on, so this rides the vanilla per-player tick instead - safe to do unconditionally
 * (nothing here is a TACZ or gun-mod type; {@code WorkbenchOpenTracker}/{@code NearbyStorage} are
 * pure Minecraft/NeoForge), and cheap, since real work only happens for a player who is both looking
 * at a gunsmith table and due for the periodic report.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class WorkbenchNetwork {
    private static final String MENU = "com.tacz.guns.inventory.GunSmithTableMenu";
    private static final int PERIOD_TICKS = 10;

    private WorkbenchNetwork() {
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!Config.TACZ_WORKBENCH_NEARBY_STORAGE.get() || !(event.getEntity() instanceof ServerPlayer viewer))
            return;

        var level = viewer.level();
        if (level.isClientSide || level.getGameTime() % PERIOD_TICKS != 0
                || !viewer.containerMenu.getClass().getName().equals(MENU)
                || !viewer.connection.hasChannel(NearbyItemsPayload.TYPE))
            return;

        var pos = WorkbenchOpenTracker.get(viewer);
        if (pos == null)
            return;

        var tally = NearbyStorage.tally(level, pos, Config.TACZ_WORKBENCH_STORAGE_RANGE.get(), item -> true);
        if (tally.isEmpty())
            return;

        var report = new HashMap<net.minecraft.resources.ResourceLocation, Integer>();
        tally.forEach((item, count) -> report.put(BuiltInRegistries.ITEM.getKey(item), count));

        PacketDistributor.sendToPlayer(viewer, new NearbyItemsPayload(report));
    }
}
