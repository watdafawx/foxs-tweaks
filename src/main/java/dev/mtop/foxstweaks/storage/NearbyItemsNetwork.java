package dev.mtop.foxstweaks.storage;

import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers {@link NearbyItemsPayload} - shared by {@code pointblank.PrinterNetwork} and
 * {@code tacz.WorkbenchNetwork}, whichever of them ever actually sends one. Registered unconditionally:
 * an unused optional channel costs nothing, and this class references neither Point Blank nor TACZ.
 */
public final class NearbyItemsNetwork {
    private NearbyItemsNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToClient(NearbyItemsPayload.TYPE, NearbyItemsPayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> NearbyCounts.set(payload.counts())));
    }
}
