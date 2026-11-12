package dev.watdafawx.foxstweaks;

import dev.watdafawx.foxstweaks.apotheosis.AffixToggleNetwork;
import dev.watdafawx.foxstweaks.gag.TemporalPouchCurio;
import dev.watdafawx.foxstweaks.gag.TemporalPouchNetwork;
import dev.watdafawx.foxstweaks.relics.RelicsIntegration;
import dev.watdafawx.foxstweaks.storage.NearbyItemsNetwork;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

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
    public static final String GAG = "gag";
    public static final String CURIOS = "curios";

    public FoxsTweaks(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        // Registered unconditionally - an unused optional channel costs nothing, and both the
        // Point Blank printer and TACZ's gunsmith table may want to send on it.
        modBus.addListener(NearbyItemsNetwork::register);
        modBus.addListener(AffixToggleNetwork::register);
        modBus.addListener(TemporalPouchNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.relics.AutoLevelNetwork::register);

        FoxsTweaksComponents.COMPONENTS.register(modBus);

        // Overworld copy: datapack dimension + portals made of a vanilla frame block and vanilla portal blocks.
        dev.watdafawx.foxstweaks.dimension.OverworldCopyEvents.init(modBus);

        if (FMLEnvironment.dist.isClient())
            modBus.addListener(dev.watdafawx.foxstweaks.client.PortalTint::register);

        if (TemporalPouchNetwork.isAvailable())
            modBus.addListener((FMLCommonSetupEvent event) -> event.enqueueWork(TemporalPouchCurio::register));

        if (ModList.get().isLoaded(RELICS))
            RelicsIntegration.init();

        // FTB sidebar clicks on owo-lib screens (Accessories). Client only; imports FTB types.
        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("ftblibrary"))
            dev.watdafawx.foxstweaks.client.compat.FtbSidebarRelease.init();

        // IPN buttons in FTB Library's sidebar. Client only; IpnSidebar imports FTB and IPN types.
        if (FMLEnvironment.dist.isClient() && ModList.get().isLoaded("ftblibrary") && ModList.get().isLoaded("inventoryprofilesnext"))
            dev.watdafawx.foxstweaks.client.compat.IpnSidebar.init(modBus);

        // Dev-only parity check for the TACZ model fast path; `build.bat taczCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.taczCheck") && ModList.get().isLoaded("tacz"))
            dev.watdafawx.foxstweaks.client.dev.TaczModelCheck.install();

        // Dev-only screenshot of the Relics auto-level toggle; `build.bat relicsShot` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.relicsShot") && ModList.get().isLoaded(RELICS))
            dev.watdafawx.foxstweaks.client.dev.RelicsShot.install();

        // Dev-only check of the overworld-copy portals; `build.bat runPortalShot` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.portalShot"))
            dev.watdafawx.foxstweaks.client.dev.PortalShot.install();

        // Dev-only check for EMI Slim; `build.bat runEmiSlimCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.emiSlimCheck") && ModList.get().isLoaded("emi"))
            dev.watdafawx.foxstweaks.client.dev.EmiSlimCheck.install();
    }
}
