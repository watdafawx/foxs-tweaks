package dev.mtop.foxstweaks;

import dev.mtop.foxstweaks.apotheosis.AffixToggleNetwork;
import dev.mtop.foxstweaks.relics.RelicsIntegration;
import dev.mtop.foxstweaks.storage.NearbyItemsNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Quality-of-life tweaks for gear mods, as a set of independent optional integrations.
 *
 * <p><b>Relics</b> ({@link RelicsIntegration}) - constellation research completes on pickup, and an
 * auto-solve button sits next to the hint button on the research screen.
 *
 * <p><b>Apotheosis</b> ({@code client.AffixTooltipHandler}) - a second tooltip listing affixes and
 * gem sockets, shown while a key is held. {@code client.AffixToggleKeyHandler} additionally lets any
 * affix on the held item be switched off and back on ({@link AffixToggleNetwork}).
 *
 * <p>Neither parent mod is required. The Relics half is wired up here rather than through
 * {@code @EventBusSubscriber} because FML loads annotated classes unconditionally, which would
 * throw {@link NoClassDefFoundError} on a pack without Relics. The Apotheosis half needs no such
 * care: it hangs off vanilla tooltip events and only reaches Apotheosis classes behind a check.
 */
@Mod(FoxsTweaks.MODID)
public class FoxsTweaks {
    public static final String MODID = "foxstweaks";

    public static final String RELICS = "relics";
    public static final String APOTHEOSIS = "apotheosis";
    public static final String POINT_BLANK = "pointblank";
    public static final String ARS_NOUVEAU = "ars_nouveau";

    public FoxsTweaks(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Registered unconditionally - an unused optional channel costs nothing, and both the
        // Point Blank printer and TACZ's gunsmith table may want to send on it.
        modBus.addListener(NearbyItemsNetwork::register);
        modBus.addListener(AffixToggleNetwork::register);

        FoxsTweaksComponents.COMPONENTS.register(modBus);

        if (ModList.get().isLoaded(RELICS))
            RelicsIntegration.init();
    }
}
