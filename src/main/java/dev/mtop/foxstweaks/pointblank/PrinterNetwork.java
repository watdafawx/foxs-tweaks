package dev.mtop.foxstweaks.pointblank;

import java.util.HashMap;
import java.util.function.Supplier;
import java.util.function.Predicate;

import dev.mtop.foxstweaks.Config;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** Keeps a player who has a printer open told what the storage around it holds. */
public final class PrinterNetwork {
    private static final String MENU = "com.vicmatskiv.pointblank.inventory.CraftingContainerMenu";
    private static final int PERIOD_TICKS = 10;
    private static final double RANGE_SQR = 8 * 8;

    private PrinterNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToClient(NearbyItemsPayload.TYPE, NearbyItemsPayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> NearbyCounts.set(payload.counts())));
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
                for (var entry : PrinterStorage.tally(level, pos, wanted.get()).entrySet())
                    report.put(BuiltInRegistries.ITEM.getKey(entry.getKey()), entry.getValue());
            }

            PacketDistributor.sendToPlayer(viewer, new NearbyItemsPayload(report));
        }
    }
}
