package dev.watdafawx.foxstweaks;

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
 * gem sockets, shown while a key is held. {@code client.ToggleKeyHandler} additionally lets any
 * affix on the held item be switched off and back on ({@code enchant.ToggleNetwork}).
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
        container.registerConfig(ModConfig.Type.SERVER, ServerConfig.SPEC);

        // Registered unconditionally - an unused optional channel costs nothing, and both the
        // Point Blank printer and TACZ's gunsmith table may want to send on it.
        modBus.addListener(NearbyItemsNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.enchant.ToggleNetwork::register);
        modBus.addListener(TemporalPouchNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.relics.AutoLevelNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.relics.RelicParticlesNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.shrink.ShrinkNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.perf.PerfNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.anchor.AnchorNetwork::register);
        modBus.addListener(dev.watdafawx.foxstweaks.hologram.HologramNetwork::register);

        FoxsTweaksComponents.COMPONENTS.register(modBus);
        FoxsTweaksItems.init(modBus);

        // Overworld copy: datapack dimension + portals made of a vanilla frame block and vanilla portal blocks.
        dev.watdafawx.foxstweaks.dimension.OverworldCopyEvents.init(modBus);

        if (FMLEnvironment.dist.isClient())
            modBus.addListener(dev.watdafawx.foxstweaks.client.PortalTint::register);

        // Hologram rendering + editor. Client only; the server half lives in `hologram/Holograms`.
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.HologramClient.init();
            modBus.addListener(dev.watdafawx.foxstweaks.client.HologramStructureMesh::registerReload);

        // Settings screen (Mods list "Config" button + hotkey).
        if (FMLEnvironment.dist.isClient())
            dev.watdafawx.foxstweaks.client.ConfigScreenKeys.registerScreen(container);

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

        // Dev-only check of the toggle overlay; `build.bat runToggleShot` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.toggleShot"))
            dev.watdafawx.foxstweaks.client.dev.ToggleShot.install();

        // Dev-only check for EMI Slim; `build.bat runEmiSlimCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.emiSlimCheck") && ModList.get().isLoaded("emi"))
            dev.watdafawx.foxstweaks.client.dev.EmiSlimCheck.install();

        // Dev-only check for the search box assist; `build.bat runSearchAssistCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.searchAssistCheck"))
            dev.watdafawx.foxstweaks.client.dev.SearchAssistCheck.install();

        // Dev-only check of the search assist inside EMI / JEI / AE2; `build.bat runSearch{Emi,Jei,Ae2}Check` sets the mode.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && System.getProperty("foxstweaks.searchModsCheck") != null)
            dev.watdafawx.foxstweaks.client.dev.SearchAssistModsCheck.install(System.getProperty("foxstweaks.searchModsCheck"));

        // Dev-only check for the Kaleidoscope Grilling skewer cache; `build.bat runGrillingCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.grillingCheck") && ModList.get().isLoaded("kaleidoscope_grilling"))
            dev.watdafawx.foxstweaks.client.dev.GrillingCheck.install();

        // Dev-only check of the Shrink hotkey and settings key; `build.bat runShrinkCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.shrinkCheck") && ModList.get().isLoaded("shrink") && ModList.get().isLoaded(CURIOS))
            dev.watdafawx.foxstweaks.client.dev.ShrinkCheck.install();

        // Dev-only check + screenshots of the Tab performance overlay and mini-HUD; `build.bat runPerfShot` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.perfShot"))
            dev.watdafawx.foxstweaks.client.dev.PerfShot.install();

        // Dev-only end-to-end check of the FPS hotspot tools; `build.bat runFpsCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.fpsCheck"))
            dev.watdafawx.foxstweaks.client.dev.FpsCheck.install();

        // Dev-only end-to-end check of virtual spawn anchors; `build.bat runAnchorCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.anchorCheck"))
            dev.watdafawx.foxstweaks.client.dev.AnchorCheck.install();

        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramAssetCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramAssetCheck.install();

        // Dev-only end-to-end check of holograms; `build.bat runHologramCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramCheck.install();

        // Dev-only check of the simple hologram editor; `build.bat runHologramCreatorCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramCreatorCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramCreatorCheck.install();
        // Dev-only check of the hologram web companion; `build.bat runHologramWebCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramWebCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramWebCheck.install();

        // Dev-only end-to-end check of hologram grab mode; `build.bat runHologramGrabCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramGrabCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramGrabCheck.install();

        // Dev-only end-to-end check and benchmark of hologram structure grab; `build.bat runHologramStructureCheck`.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.hologramStructureCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramStructureCheck.install();

        // Dev-only check of the hologram asset browser; `build.bat runHologramPickerCheck` sets the property.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production
                && Boolean.getBoolean("foxstweaks.hologramPickerCheck"))
            dev.watdafawx.foxstweaks.client.dev.HologramPickerCheck.install();

        // Dev-only check that an anchor carries its owner's Apotheosis world tier; `build.bat runAnchorTierCheck`.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.anchorTierCheck"))
            dev.watdafawx.foxstweaks.client.dev.AnchorTierCheck.install();

        // Dev-only check of the settings screen's sections; `build.bat runConfigCheck`.
        if (FMLEnvironment.dist.isClient() && !FMLEnvironment.production && Boolean.getBoolean("foxstweaks.configCheck"))
            dev.watdafawx.foxstweaks.client.dev.ConfigScreenCheck.install();
    }
}
