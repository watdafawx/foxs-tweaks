package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.perf.ClientPerfTracker;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.List;

/**
 * The "Move Performance Overlay" key: while the Tab performance cards are on screen it cycles their placement
 * preset on the spot, and anywhere else it opens {@link PerfOverlayScreen} for dragging.
 *
 * <p>Cycling covers "move it while I'm looking at it"; the screen covers placing it against a specific HUD
 * element, where a real cursor is needed (Minecraft hides the cursor while no screen is open, so dragging on
 * the HUD itself is not possible).
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class PerfOverlayMover {
    private static final List<String> PLACEMENTS = List.of(
            "flank", "topLeft", "topCenter", "topRight", "bottomLeft", "bottomCenter", "bottomRight");

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();

        while (FoxsTweaksKeys.MOVE_PERF_OVERLAY.consumeClick()) {
            if (mc.player == null)
                continue;

            if (ClientPerfTracker.isTabOverlayVisible() && mc.screen == null) {
                // On the spot: next preset, offsets cleared so the preset actually lands where it says.
                String current = Config.perfOverlayPlacement();
                int index = PLACEMENTS.indexOf(current);
                String next = PLACEMENTS.get((index + 1) % PLACEMENTS.size());
                Config.setPerfOverlayPosition(next, 0, 0);
            } else if (mc.screen == null) {
                mc.setScreen(new PerfOverlayScreen(null));
            }
        }
    }

    private PerfOverlayMover() {
    }
}
