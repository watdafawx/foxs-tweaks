package dev.mtop.foxstweaks.pointblank;

import java.util.HashMap;
import java.util.function.Supplier;
import java.util.function.Predicate;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.storage.NearbyItemsPayload;
import dev.mtop.foxstweaks.storage.NearbyStorage;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Keeps a player who has a printer open told what the storage around it holds. Registration of the
 * payload itself is shared - see {@code storage.NearbyItemsNetwork}.
 */
public final class PrinterNetwork {
    private static final String MENU = "com.vicmatskiv.pointblank.inventory.CraftingContainerMenu";
    private static final int PERIOD_TICKS = 10;
    private static final double RANGE_SQR = 8 * 8;

    private PrinterNetwork() {
    }

    /**
     * Called every server tick by the printer. {@code wanted} says which items are worth reporting
     * (everything any Point Blank recipe asks for); it is only asked for when someone is actually
     * looking at the printer, since building it walks every recipe.
     */
    public static void tick(BlockEntity printer, Supplier<Predicate<Item>> wanted) {
        var level = printer.getLevel();
        if (level == null || level.isClientSide || level.getGameTime() % PERIOD_TICKS != 0 || !Config.PRINTER_NEARBY_STORAGE.get())
            return;

        var pos = printer.getBlockPos();
        HashMap<net.minecraft.resources.ResourceLocation, Integer> report = null;

        for (var player : level.players()) {
            if (!(player instanceof ServerPlayer viewer)
                    || viewer.distanceToSqr(pos.getCenter()) > RANGE_SQR
                    || !viewer.containerMenu.getClass().getName().equals(MENU)
                    || !viewer.connection.hasChannel(NearbyItemsPayload.TYPE))
                continue;

            if (report == null) {
                report = new HashMap<>();
                for (var entry : NearbyStorage.tally(level, pos, Config.PRINTER_STORAGE_RANGE.get(), wanted.get()).entrySet())
                    report.put(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue());
            }

            PacketDistributor.sendToPlayer(viewer, new NearbyItemsPayload(report));
        }
    }
}
