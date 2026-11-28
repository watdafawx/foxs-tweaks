package dev.watdafawx.foxstweaks.client.compat;

import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftbchunks.client.gui.RegionMapPanel;
import dev.watdafawx.foxstweaks.client.terraform.MapPreviewBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Optional FTB Chunks fullscreen map overlay for Biome Changer / Terrain Flattener previews.
 * Invoked from {@code FtbChunksMapOverlayMixin} only when FTB Chunks is installed.
 */
public final class FtbChunksPreview {
    public static void renderOverlay(GuiGraphics graphics, RegionMapPanel panel, LargeMapScreen screen,
                                     int regionMinX, int regionMinZ, int panelX, int panelY, int panelW, int panelH) {
        MapPreviewBridge.Preview preview = MapPreviewBridge.current();
        if (preview == null || !preview.dimension().equals(screen.currentDimension())) return;

        List<BlockPos> poly = preview.polygon();
        if (poly.size() < 3) return;

        int tileSize = screen.getRegionTileSize();
        if (tileSize <= 0) return;

        double scale = tileSize / 512.0; // 1 region = 512 blocks
        double originWorldX = regionMinX * 512.0;
        double originWorldZ = regionMinZ * 512.0;
        double scrollX = panel.getScrollX();
        double scrollY = panel.getScrollY();

        int minSX = Integer.MAX_VALUE, maxSX = Integer.MIN_VALUE;
        int minSY = Integer.MAX_VALUE, maxSY = Integer.MIN_VALUE;

        int[] sx = new int[poly.size()];
        int[] sy = new int[poly.size()];
        for (int i = 0; i < poly.size(); i++) {
            BlockPos p = poly.get(i);
            sx[i] = panelX + (int) Math.round((p.getX() - originWorldX) * scale - scrollX);
            sy[i] = panelY + (int) Math.round((p.getZ() - originWorldZ) * scale - scrollY);
            if (sx[i] < minSX) minSX = sx[i];
            if (sx[i] > maxSX) maxSX = sx[i];
            if (sy[i] < minSY) minSY = sy[i];
            if (sy[i] > maxSY) maxSY = sy[i];
        }

        // Cull if completely off-screen
        if (maxSX < panelX || minSX > panelX + panelW || maxSY < panelY || minSY > panelY + panelH) return;

        int fillColor = 0x44000000 | preview.rgb();
        int borderColor = 0xEE000000 | preview.rgb();

        // Scanline-fill the actual polygon shape (hugs circles and irregular flood-fill biomes)
        int fy0 = Math.max(panelY, minSY);
        int fy1 = Math.min(panelY + panelH, maxSY);
        int n = sx.length;
        int[] xs = new int[n];
        for (int py = fy0; py < fy1; py++) {
            int hits = 0;
            for (int i = 0, j = n - 1; i < n; j = i++) {
                int y0 = sy[i], y1 = sy[j];
                if ((y0 <= py && y1 > py) || (y1 <= py && y0 > py)) {
                    xs[hits++] = sx[i] + (py - y0) * (sx[j] - sx[i]) / (y1 - y0);
                }
            }
            java.util.Arrays.sort(xs, 0, hits);
            for (int i = 0; i + 1 < hits; i += 2) {
                int xStart = Math.max(panelX, xs[i]);
                int xEnd = Math.min(panelX + panelW, xs[i + 1]);
                if (xEnd > xStart) {
                    graphics.fill(xStart, py, xEnd, py + 1, fillColor);
                }
            }
        }

        // Draw crisp polygon perimeter segments
        for (int i = 0; i < sx.length; i++) {
            int j = (i + 1) % sx.length;
            drawLine2D(graphics, sx[i], sy[i], sx[j], sy[j], borderColor, panelX, panelY, panelX + panelW, panelY + panelH);
        }

        // Label centered above polygon
        int labelX = (minSX + maxSX) / 2;
        int labelY = Math.max(panelY + 4, minSY - 12);
        graphics.drawCenteredString(Minecraft.getInstance().font, preview.label(), labelX, labelY, 0xFF000000 | preview.rgb());
    }

    private static void drawLine2D(GuiGraphics g, int x0, int y0, int x1, int y1, int color,
                                   int minX, int minY, int maxX, int maxY) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int steps = Math.max(dx, dy);
        if (steps == 0) return;
        for (int i = 0; i <= steps; i++) {
            int px = x0 + (x1 - x0) * i / steps;
            int py = y0 + (y1 - y0) * i / steps;
            if (px >= minX && px < maxX && py >= minY && py < maxY) {
                g.fill(px, py, px + 2, py + 2, color);
            }
        }
    }

    private FtbChunksPreview() {
    }
}
