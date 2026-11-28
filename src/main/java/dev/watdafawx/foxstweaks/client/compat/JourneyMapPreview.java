package dev.watdafawx.foxstweaks.client.compat;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.terraform.MapPreviewBridge;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.model.MapPolygon;
import journeymap.api.v2.client.model.ShapeProperties;

import javax.annotation.Nullable;

/**
 * Optional JourneyMap v2 plugin showing the active Biome Changer / Terrain Flattener selection
 * polygon on both the fullscreen map and minimap. Only loaded when JourneyMap is installed.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapPreview implements IClientPlugin {
    @Nullable
    private static volatile IClientAPI api;
    @Nullable
    private static PolygonOverlay activeOverlay;

    @Override
    public void initialize(IClientAPI jmClientApi) {
        api = jmClientApi;
        MapPreviewBridge.Preview pending = MapPreviewBridge.current();
        if (pending != null) {
            update(pending);
        }
    }

    @Override
    public String getModId() {
        return FoxsTweaks.MODID;
    }

    public static void update(MapPreviewBridge.Preview preview) {
        IClientAPI jm = api;
        if (jm == null) return;
        try {
            if (activeOverlay != null) {
                jm.remove(activeOverlay);
                activeOverlay = null;
            }
            ShapeProperties props = new ShapeProperties()
                    .setFillColor(preview.rgb())
                    .setFillOpacity(0.28F)
                    .setStrokeColor(preview.rgb())
                    .setStrokeOpacity(0.95F)
                    .setStrokeWidth(2.5F);
            MapPolygon poly = new MapPolygon(preview.polygon());
            PolygonOverlay overlay = new PolygonOverlay(FoxsTweaks.MODID, preview.dimension(), props, poly);
            overlay.setOverlayGroupName("Fox's Tweaks Terraform");
            overlay.setTitle(preview.label());
            overlay.setLabel(preview.label());
            jm.show(overlay);
            activeOverlay = overlay;
        } catch (Exception ignored) {
        }
    }

    public static void clear() {
        IClientAPI jm = api;
        if (jm == null || activeOverlay == null) return;
        try {
            jm.remove(activeOverlay);
        } catch (Exception ignored) {
        }
        activeOverlay = null;
    }
}
