package dev.watdafawx.foxstweaks.perf;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renders Option A (Hero + Live Sparkline &amp; Gauges) Server and Client performance cards.
 *
 * <p>When vanilla's Tab player list is visible, the two cards flank it on the left (Server) and
 * right (Client) so a crowded server with dozens of players never pushes the cards off-screen. If
 * the screen is too narrow to flank, they automatically fall back below the Tab list.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class PerfOverlayRenderer {
    private static final int BG_COLOR = 0xCC0B0D12;
    private static final int BORDER_COLOR = 0x55FFFFFF;
    private static final int DIVIDER_COLOR = 0x28FFFFFF;
    private static final int TRACK_BG = 0x30FFFFFF;
    private static final int SPARK_BG = 0x66000000;
    private static final int SPARK_BORDER = 0x28FFFFFF;
    private static final int BUDGET_LINE = 0xAAFF5555;

    private static final int SERVER_ACCENT = 0xFFFFAA00;
    private static final int CLIENT_ACCENT = 0xFF55DDFF;

    private static final int COL_LABEL = 0xFFBFC8D6;
    private static final int COL_MUTED = 0xFF8C96A6;
    private static final int COL_VALUE = 0xFFE8EDF5;
    private static final int COL_GREEN = 0xFF55FF55;
    private static final int COL_YELLOW = 0xFFFFFF55;
    private static final int COL_RED = 0xFFFF5555;

    private static final int CARD_W = 194;    private static final int PAD_X = 7;
    private static final int CARD_GAP = 6;
    private static final int SPARK_W = 76;
    private static final int SPARK_H = 26;

    private static boolean vanillaTabRenderedThisFrame;
    private static int tabMinX;
    private static int tabMinY;
    private static int tabMaxX;
    private static int tabMaxY;

    /** Last observed vanilla Tab bounds & placement mode (for diagnostics / tests). */
    public static int lastVanillaTabBottomY;
    public static boolean lastFlankedVanillaTab;

    private PerfOverlayRenderer() {
    }

    public static void beginVanillaTab() {
        vanillaTabRenderedThisFrame = true;
        tabMinX = Integer.MAX_VALUE;
        tabMinY = Integer.MAX_VALUE;
        tabMaxX = 0;
        tabMaxY = 0;
    }

    public static void recordVanillaTabBounds(int minX, int minY, int maxX, int maxY) {
        if (!vanillaTabRenderedThisFrame)
            return;
        if (minX < tabMinX) tabMinX = minX;
        if (minY < tabMinY) tabMinY = minY;
        if (maxX > tabMaxX) tabMaxX = maxX;
        if (maxY > tabMaxY) tabMaxY = maxY;
    }

    @SubscribeEvent
    public static void onRenderTabLayer(RenderGuiLayerEvent.Post event) {
        if (!event.getName().equals(VanillaGuiLayers.TAB_LIST))
            return;

        boolean hasVanilla = vanillaTabRenderedThisFrame && tabMaxY > 0 && tabMaxX > tabMinX;
        int vMinX = tabMinX;
        int vMinY = tabMinY;
        int vMaxX = tabMaxX;
        int vMaxY = tabMaxY;
        vanillaTabRenderedThisFrame = false;
        lastVanillaTabBottomY = hasVanilla ? vMaxY : 0;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return;

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;

        if (ClientPerfTracker.isTabOverlayVisible()) {
            renderTabCards(gg, font, hasVanilla, vMinX, vMinY, vMaxX, vMaxY);
        } else {
            lastFlankedVanillaTab = false;
            if (ClientPerfTracker.isMiniHudVisible()) {
                renderMiniHud(gg, font);
            }
        }
    }

    private static void renderTabCards(GuiGraphics gg, Font font,
                                       boolean hasVanilla, int vMinX, int vMinY, int vMaxX, int vMaxY) {
        PerfSnapshotPayload srv = Config.perfOverlayServerCard() ? ClientPerfTracker.serverSnapshot() : null;
        ClientPerfTracker.ClientStats cli = ClientPerfTracker.clientStats();
        float[] cliFrames = ClientPerfTracker.recentFrameTimesMs(PerfSnapshotPayload.SPARKLINE_SAMPLES);

        int screenW = gg.guiWidth();
        int screenH = gg.guiHeight();
        int cliH = 122;
        int srvH = srv != null ? serverCardHeight(srv) : cliH;

        // An explicit placement wins over the player list: the cards are pinned to an edge as a
        // stacked block, nudged by the offset. This is what makes the overlay movable at all.
        String placement = Config.perfOverlayPlacement();
        if (!"flank".equals(placement)) {
            lastFlankedVanillaTab = false;
            renderAnchored(gg, font, placement, screenW, screenH, srv, srvH, cli, cliFrames, cliH);
            return;
        }

        // Flank mode: both cards beside the player list when they fit, else centred below it.
        boolean showClient = Config.perfOverlayClientCard();
        if (hasVanilla && showClient && srv != null) {
            int leftX = vMinX - CARD_GAP - CARD_W;
            int rightX = vMaxX + CARD_GAP;
            if (leftX >= 4 && rightX + CARD_W <= screenW - 4) {
                lastFlankedVanillaTab = true;
                drawServerCard(gg, font, leftX, clampY(vMinY, srvH, screenH), CARD_W, srvH, srv);
                drawClientCard(gg, font, rightX, clampY(vMinY, cliH, screenH), CARD_W, cliH, cli, cliFrames);
                return;
            }
        }

        lastFlankedVanillaTab = false;
        int startY = hasVanilla ? vMaxY + CARD_GAP : 10;
        int cards = (srv != null ? 1 : 0) + (showClient ? 1 : 0);
        int combinedW = cards == 2 ? CARD_W * 2 + CARD_GAP : CARD_W;
        if (combinedW <= screenW - 8) {
            int maxH = cards == 2 ? Math.max(srvH, cliH) : (srv != null ? srvH : cliH);
            int y = clampY(startY, maxH, screenH);
            int x = (screenW - combinedW) / 2;
            if (srv != null) {
                drawServerCard(gg, font, x, y, CARD_W, srvH, srv);
                x += CARD_W + CARD_GAP;
            }
            if (showClient)
                drawClientCard(gg, font, x, y, CARD_W, cliH, cli, cliFrames);
        } else {
            int x = Math.max(4, (screenW - CARD_W) / 2);
            int y = clampY(startY, srvH + cliH + CARD_GAP, screenH);
            if (srv != null) {
                drawServerCard(gg, font, x, y, CARD_W, srvH, srv);
                y += srvH + CARD_GAP;
            }
            if (showClient)
                drawClientCard(gg, font, x, y, CARD_W, cliH, cli, cliFrames);
        }
    }

    /**
     * Pins the cards to an edge or corner as a stacked block (Server above Client), ignoring the player
     * list. Used for every placement other than {@code flank}; the offsets let a player push the block
     * away from whatever else they have on screen, and the result is clamped so nothing goes off-screen.
     */
    private static void renderAnchored(GuiGraphics gg, Font font, String placement,
                                       int screenW, int screenH, PerfSnapshotPayload srv, int srvH,
                                       ClientPerfTracker.ClientStats cli, float[] cliFrames, int cliH) {
        boolean showClient = Config.perfOverlayClientCard();
        boolean showServer = srv != null;
        if (!showClient && !showServer)
            return;

        int gap = showClient && showServer ? CARD_GAP : 0;
        int totalH = (showServer ? srvH : 0) + gap + (showClient ? cliH : 0);
        int offX = Config.perfOverlayOffsetX();
        int offY = Config.perfOverlayOffsetY();

        int x = clampX(anchorX(placement, screenW) + offX, screenW);
        int y = clampY(anchorY(placement, screenH, totalH) + offY, totalH, screenH);

        if (showServer) {
            drawServerCard(gg, font, x, y, CARD_W, srvH, srv);
            y += srvH + gap;
        }
        if (showClient)
            drawClientCard(gg, font, x, y, CARD_W, cliH, cli, cliFrames);

        // A pinned placement is the only one the player can have moved, so only that one says how.
        drawMoveHint(gg, font, x, clampY(anchorY(placement, screenH, totalH) + offY, totalH, screenH) + totalH, screenW);
    }

    /** Screen X of a placement's block, before the player's offset. */
    public static int anchorX(String placement, int screenW) {
        if (placement.endsWith("Left"))
            return 4;
        if (placement.endsWith("Right"))
            return screenW - CARD_W - 4;
        return (screenW - CARD_W) / 2;
    }

    /** Screen Y of a placement's block, before the player's offset. */
    public static int anchorY(String placement, int screenH, int totalH) {
        return placement.startsWith("bottom") ? screenH - totalH - 4 : 4;
    }

    public static int clampX(int x, int screenW) {
        return Math.max(2, Math.min(x, screenW - CARD_W - 2));
    }

    public static int cardWidth() {
        return CARD_W;
    }

    public static int cardGap() {
        return CARD_GAP;
    }

    /** Height of the client card; the server card is taller when it lists dimensions. */
    public static int clientCardHeight() {
        return 122;
    }

    /** "Move: <key>" under the block, so the placement key is discoverable without reading the config. */
    public static void drawMoveHint(GuiGraphics gg, Font font, int x, int y, int screenW) {
        var key = dev.watdafawx.foxstweaks.client.FoxsTweaksKeys.MOVE_PERF_OVERLAY;
        if (key.getKey().getValue() == com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue())
            return;

        String hint = "Move: " + key.getTranslatedKeyMessage().getString();
        gg.drawString(font, hint, clampX(x, screenW), y + 2, COL_MUTED, false);
    }

    private static int serverCardHeight(PerfSnapshotPayload s) {
        int dimRows = Math.min(s.dims().size(), 4);
        // 116 base (header + hero + 3 gauges + world line) + dim section (header 11 + 10px per row)
        return dimRows > 0 ? 116 + 13 + dimRows * 10 + 4 : 118;
    }

    private static void drawServerCard(GuiGraphics gg, Font font, int x, int y, int w, int h, PerfSnapshotPayload s) {
        drawCardShell(gg, x, y, w, h, SERVER_ACCENT);

        // Header + health pill
        gg.drawString(font, txt("SERVER", SERVER_ACCENT), x + PAD_X, y + 6, SERVER_ACCENT, true);
        float tps5s = s.tps()[0];
        float med10s = s.mspt10s()[1];
        float p9510s = s.mspt10s()[2];
        float max10s = s.mspt10s()[3];
        String pillText;
        int pillCol;
        if (tps5s < 18.0f || med10s >= 50.0f) {
            pillText = "LAGGING";
            pillCol = COL_RED;
        } else if (tps5s < 19.5f || p9510s >= 40.0f || max10s >= 100.0f) {
            pillText = "SPIKING";
            pillCol = COL_YELLOW;
        } else {
            pillText = "HEALTHY";
            pillCol = COL_GREEN;
        }
        drawPill(gg, font, x + w - PAD_X, y + 5, pillText, pillCol);

        // Hero TPS + Sparkline
        int heroY = y + 19;
        boolean capped = tps5s >= 19.95f;
        String heroStr = capped ? "*20.0" : fmt1(tps5s);
        int tpsCol = colorTps(tps5s);
        drawScaledString(gg, font, heroStr, x + PAD_X, heroY, 1.5f, tpsCol);
        int heroW = Math.round(font.width(heroStr) * 1.5f);
        gg.drawString(font, txt("TPS", COL_MUTED), x + PAD_X + heroW + 3, heroY + 4, COL_MUTED, true);

        MutableComponent tpsSub = txt("1m ", COL_MUTED)
                .append(txt(fmt1(s.tps()[2]), colorTps(s.tps()[2])))
                .append(txt(" · 15m ", COL_MUTED))
                .append(txt(fmt1(s.tps()[4]), colorTps(s.tps()[4])));
        gg.drawString(font, tpsSub, x + PAD_X, heroY + 17, COL_MUTED, true);

        drawSparkline(gg, x + w - PAD_X - SPARK_W, heroY, SPARK_W, SPARK_H,
                s.recentTicksMs(), 50.0f, 70.0f, 40.0f, 50.0f);

        // Gauges
        int innerW = w - PAD_X * 2;
        int gy = y + 49;

        // Tick 10s gauge (med + p95 out of 50ms budget)
        MutableComponent tickVals = txt(fmt1(s.mspt10s()[0]), colorMs(s.mspt10s()[0], 40f, 50f))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt1(med10s), colorMs(med10s, 40f, 50f)))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt1(p9510s), colorMs(p9510s, 40f, 50f)))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt0(max10s), colorMs(max10s, 40f, 50f)))
                .append(txt("ms", COL_MUTED));
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "Tick 10s", tickVals,
                clamp01(med10s / 50.0f), colorMs(med10s, 40f, 50f),
                clamp01(p9510s / 50.0f), withAlpha(colorMs(p9510s, 40f, 50f), 0x88));

        // CPU gauge (proc + sys)
        gy += 18;
        float procCpu = s.cpuProcess()[0];
        float sysCpu = s.cpuSystem()[0];
        MutableComponent cpuVals = formatCpu(procCpu).append(txt(" proc", COL_MUTED));
        if (sysCpu >= 0f) {
            cpuVals.append(txt(" · ", COL_MUTED)).append(formatCpu(sysCpu)).append(txt(" sys", COL_MUTED));
        }
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "CPU", cpuVals,
                procCpu >= 0f ? clamp01(procCpu / 100f) : 0f, procCpu >= 0f ? colorCpu(procCpu) : COL_MUTED,
                sysCpu >= 0f ? clamp01(sysCpu / 100f) : 0f, 0x55FFFFFF);

        // Heap & GC gauge
        gy += 18;
        float heapFrac = s.heapMaxMb() > 0 ? clamp01((float) s.heapUsedMb() / s.heapMaxMb()) : 0f;
        int heapPct = Math.round(heapFrac * 100f);
        int heapCol = colorPct(heapPct);
        int gcCol = s.gcTimeMs1m() < 200 ? COL_GREEN : (s.gcTimeMs1m() < 800 ? COL_YELLOW : COL_RED);
        MutableComponent heapVals = txt(fmtMem(s.heapUsedMb()) + "/" + fmtMem(s.heapMaxMb()), heapCol)
                .append(txt(" · GC ", COL_MUTED))
                .append(txt(s.gcCount1m() + "x " + s.gcTimeMs1m() + "ms", gcCol));
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "Heap", heapVals,
                heapFrac, heapCol, 0f, 0);

        // Footer: world counts + readable per-dimension mini-bars
        int fy = y + 104;
        gg.fill(x + PAD_X, fy - 3, x + w - PAD_X, fy - 2, DIVIDER_COLOR);

        MutableComponent worldLeft = txt("World ", COL_MUTED)
                .append(txt(fmtInt(s.loadedChunks()), COL_VALUE))
                .append(txt(" ch · ", COL_MUTED))
                .append(txt(fmtInt(s.loadedEntities()), COL_VALUE))
                .append(txt(" ent", COL_MUTED));
        gg.drawString(font, worldLeft, x + PAD_X, fy, COL_MUTED, true);

        int shown = Math.min(s.dims().size(), 4);
        if (shown > 0) {
            int dy = fy + 13;
            gg.fill(x + PAD_X, dy - 3, x + w - PAD_X, dy - 2, DIVIDER_COLOR);

            gg.drawString(font, txt("Dimensions", COL_LABEL), x + PAD_X, dy, COL_LABEL, true);
            int hidden = Math.max(0, s.totalDims() - shown);
            if (hidden > 0) {
                Component idleTxt = txt("+" + hidden + " idle", COL_MUTED);
                gg.drawString(font, idleTxt, x + w - PAD_X - font.width(idleTxt), dy, COL_MUTED, true);
            }

            float maxDimMs = 5.0f;
            for (int i = 0; i < shown; i++) {
                maxDimMs = Math.max(maxDimMs, s.dims().get(i).mspt());
            }
            float barScale = Math.max(25.0f, maxDimMs);
            int barW = 46;
            int msColW = 34;
            int barX = x + w - PAD_X - msColW - barW - 4;
            int nameMaxW = barX - (x + PAD_X) - 4;

            dy += 11;
            for (int i = 0; i < shown; i++) {
                var d = s.dims().get(i);
                String niceName = trimToWidth(font, formatDimName(d.name()), nameMaxW);
                gg.drawString(font, txt(niceName, COL_VALUE), x + PAD_X, dy, COL_VALUE, true);

                int barY = dy + 2;
                gg.fill(barX, barY, barX + barW, barY + 4, TRACK_BG);
                int fillW = Math.max(d.mspt() >= 0.05f ? 1 : 0, Math.round(clamp01(d.mspt() / barScale) * barW));
                int dCol = colorMs(d.mspt(), 20f, 35f);
                if (fillW > 0) {
                    gg.fill(barX, barY, barX + fillW, barY + 4, dCol);
                }

                Component msTxt = txt(fmt1(d.mspt()) + "ms", dCol);
                gg.drawString(font, msTxt, x + w - PAD_X - font.width(msTxt), dy, dCol, true);
                dy += 10;
            }
        }
    }

    private static void drawClientCard(GuiGraphics gg, Font font, int x, int y, int w, int h,
                                       ClientPerfTracker.ClientStats c, float[] recentFramesMs) {
        drawCardShell(gg, x, y, w, h, CLIENT_ACCENT);

        // Header + Ping pill
        gg.drawString(font, txt("CLIENT", CLIENT_ACCENT), x + PAD_X, y + 6, CLIENT_ACCENT, true);
        String pingPill;
        int pingCol;
        if (c.localServer()) {
            pingPill = "LOCAL";
            pingCol = COL_GREEN;
        } else if (c.pingMs() >= 0) {
            pingPill = c.pingMs() + "ms";
            pingCol = colorPing(c.pingMs());
        } else {
            pingPill = "--";
            pingCol = COL_MUTED;
        }
        drawPill(gg, font, x + w - PAD_X, y + 5, pingPill, pingCol);

        // Hero FPS + Live Sparkline
        int heroY = y + 19;
        String fpsStr = String.valueOf(c.fps());
        int fpsCol = colorFps(c.fps());
        drawScaledString(gg, font, fpsStr, x + PAD_X, heroY, 1.5f, fpsCol);
        int heroW = Math.round(font.width(fpsStr) * 1.5f);
        gg.drawString(font, txt("FPS", COL_MUTED), x + PAD_X + heroW + 3, heroY + 4, COL_MUTED, true);

        MutableComponent fpsSub = txt("1% ", COL_MUTED)
                .append(txt(String.valueOf(c.fpsOnePercentLow()), colorFps(c.fpsOnePercentLow())))
                .append(txt(" · med ", COL_MUTED))
                .append(txt(fmt1(c.frameMedMs()) + "ms", colorMs(c.frameMedMs(), 16.7f, 33.3f)));
        gg.drawString(font, fpsSub, x + PAD_X, heroY + 17, COL_MUTED, true);

        drawSparkline(gg, x + w - PAD_X - SPARK_W, heroY, SPARK_W, SPARK_H,
                recentFramesMs, 16.7f, 35.0f, 16.7f, 33.3f);

        // Gauges
        int innerW = w - PAD_X * 2;
        int gy = y + 49;

        // Frame 10s gauge (med + p95 out of 33.3ms)
        MutableComponent ftVals = txt(fmt1(c.frameMinMs()), colorMs(c.frameMinMs(), 16.7f, 33.3f))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt1(c.frameMedMs()), colorMs(c.frameMedMs(), 16.7f, 33.3f)))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt1(c.frameP95Ms()), colorMs(c.frameP95Ms(), 16.7f, 33.3f)))
                .append(txt("/", COL_MUTED))
                .append(txt(fmt0(c.frameMaxMs()), colorMs(c.frameMaxMs(), 16.7f, 33.3f)))
                .append(txt("ms", COL_MUTED));
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "Frame 10s", ftVals,
                clamp01(c.frameMedMs() / 33.3f), colorMs(c.frameMedMs(), 16.7f, 33.3f),
                clamp01(c.frameP95Ms() / 33.3f), withAlpha(colorMs(c.frameP95Ms(), 16.7f, 33.3f), 0x88));

        // CPU gauge
        gy += 18;
        MutableComponent cpuVals = formatCpu(c.cpuProc10s()).append(txt(" proc", COL_MUTED));
        if (c.cpuSys10s() >= 0f) {
            cpuVals.append(txt(" · ", COL_MUTED)).append(formatCpu(c.cpuSys10s())).append(txt(" sys", COL_MUTED));
        }
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "CPU", cpuVals,
                c.cpuProc10s() >= 0f ? clamp01(c.cpuProc10s() / 100f) : 0f, CLIENT_ACCENT,
                c.cpuSys10s() >= 0f ? clamp01(c.cpuSys10s() / 100f) : 0f, 0x55FFFFFF);

        // Heap & GC gauge
        gy += 18;
        float heapFrac = c.heapMaxMb() > 0 ? clamp01((float) c.heapUsedMb() / c.heapMaxMb()) : 0f;
        int heapPct = Math.round(heapFrac * 100f);
        int heapCol = colorPct(heapPct);
        int gcCol = c.gcTimeMs1m() < 200 ? COL_GREEN : (c.gcTimeMs1m() < 800 ? COL_YELLOW : COL_RED);
        MutableComponent heapVals = txt(fmtMem(c.heapUsedMb()) + "/" + fmtMem(c.heapMaxMb()), heapCol)
                .append(txt(" · GC ", COL_MUTED))
                .append(txt(c.gcCount1m() + "x " + c.gcTimeMs1m() + "ms", gcCol));
        drawGaugeRow(gg, font, x + PAD_X, gy, innerW, "Heap", heapVals,
                heapFrac, heapCol, 0f, 0);

        // Footer: View distance, client entities, GPU %
        int fy = y + 104;
        gg.fill(x + PAD_X, fy - 3, x + w - PAD_X, fy - 2, DIVIDER_COLOR);

        MutableComponent viewLine = txt("View ", COL_MUTED)
                .append(txt(c.renderDistance() + " ch", COL_VALUE))
                .append(txt(" · ", COL_MUTED))
                .append(txt(fmtInt(c.clientEntities()), COL_VALUE))
                .append(txt(" ent", COL_MUTED));
        gg.drawString(font, viewLine, x + PAD_X, fy, COL_MUTED, true);

        if (c.gpuPercent() >= 0f) {
            MutableComponent gpuLine = txt("GPU ", COL_MUTED).append(formatCpu(c.gpuPercent()));
            gg.drawString(font, gpuLine, x + PAD_X, fy + 9, COL_MUTED, true);
        } else {
            MutableComponent memPctLine = txt("Heap ", COL_MUTED)
                    .append(txt(heapPct + "%", heapCol))
                    .append(txt(" · ", COL_MUTED))
                    .append(txt(c.localServer() ? "Singleplayer" : "Multiplayer", COL_MUTED));
            gg.drawString(font, memPctLine, x + PAD_X, fy + 9, COL_MUTED, true);
        }
    }

    private static void drawCardShell(GuiGraphics gg, int x, int y, int w, int h, int accent) {
        gg.fill(x, y, x + w, y + h, BG_COLOR);
        gg.renderOutline(x, y, w, h, BORDER_COLOR);
        gg.fill(x + 1, y + 1, x + w - 1, y + 3, accent);
    }

    private static void drawPill(GuiGraphics gg, Font font, int rightX, int y, String label, int col) {
        int tw = font.width(label);
        int pw = tw + 6;
        int ph = 11;
        int px = rightX - pw;
        gg.fill(px, y, px + pw, y + ph, withAlpha(col, 0x26));
        gg.renderOutline(px, y, pw, ph, withAlpha(col, 0x88));
        gg.drawString(font, txt(label, col), px + 3, y + 2, col, true);
    }

    private static void drawScaledString(GuiGraphics gg, Font font, String text, int x, int y, float scale, int col) {
        var pose = gg.pose();
        pose.pushPose();
        pose.translate(x, y, 0);
        pose.scale(scale, scale, 1.0f);
        gg.drawString(font, text, 0, 0, col, true);
        pose.popPose();
    }

    private static void drawSparkline(GuiGraphics gg, int x, int y, int w, int h,
                                      float[] samples, float budgetMs, float maxScaleMs,
                                      float warnMs, float badMs) {
        gg.fill(x, y, x + w, y + h, SPARK_BG);
        gg.renderOutline(x, y, w, h, SPARK_BORDER);

        int innerX = x + 2;
        int innerY = y + 2;
        int innerW = w - 4;
        int innerH = h - 4;

        // Dashed budget threshold line (50ms server / 16.7ms client)
        int budgetY = innerY + innerH - Math.round(clamp01(budgetMs / maxScaleMs) * innerH);
        for (int dx = 0; dx < innerW; dx += 4) {
            gg.fill(innerX + dx, budgetY, Math.min(innerX + innerW, innerX + dx + 2), budgetY + 1, BUDGET_LINE);
        }

        if (samples == null || samples.length == 0)
            return;

        int bars = Math.min(samples.length, innerW / 2);
        int start = samples.length - bars;
        int barW = Math.max(1, innerW / Math.max(bars, 1));
        int offsetX = innerX + innerW - bars * barW;

        for (int i = 0; i < bars; i++) {
            float v = samples[start + i];
            int bh = Math.max(1, Math.round(clamp01(v / maxScaleMs) * innerH));
            int col = colorMs(v, warnMs, badMs);
            int bx = offsetX + i * barW;
            int bw = barW > 2 ? barW - 1 : barW;
            gg.fill(bx, innerY + innerH - bh, bx + bw, innerY + innerH, col);
        }
    }

    private static void drawGaugeRow(GuiGraphics gg, Font font, int x, int y, int w,
                                     String label, Component rightText,
                                     float primaryFrac, int primaryCol,
                                     float secondaryFrac, int secondaryCol) {
        gg.drawString(font, txt(label, COL_LABEL), x, y, COL_LABEL, true);
        int rw = font.width(rightText);
        gg.drawString(font, rightText, x + w - rw, y, COL_VALUE, true);

        int barY = y + 10;
        int barH = 4;
        gg.fill(x, barY, x + w, barY + barH, TRACK_BG);

        int secW = Math.round(clamp01(secondaryFrac) * w);
        if (secW > 0 && secondaryCol != 0) {
            gg.fill(x, barY, x + secW, barY + barH, secondaryCol);
        }
        int priW = Math.round(clamp01(primaryFrac) * w);
        if (priW > 0) {
            gg.fill(x, barY, x + priW, barY + barH, primaryCol);
        }
    }

    private static void renderMiniHud(GuiGraphics gg, Font font) {
        PerfSnapshotPayload srv = ClientPerfTracker.serverSnapshot();
        ClientPerfTracker.ClientStats cli = ClientPerfTracker.clientStats();

        List<Component> lines = new ArrayList<>(2);
        if (srv != null) {
            MutableComponent l1 = txt("SRV ", SERVER_ACCENT)
                    .append(formatTps(srv.tps()[0]))
                    .append(txt(" TPS · ", COL_MUTED))
                    .append(txt(fmt1(srv.mspt10s()[1]) + "ms", colorMs(srv.mspt10s()[1], 40f, 50f)))
                    .append(txt(" (p95 ", COL_MUTED))
                    .append(txt(fmt1(srv.mspt10s()[2]), colorMs(srv.mspt10s()[2], 40f, 50f)))
                    .append(txt(") · ", COL_MUTED))
                    .append(formatMemShort(srv.heapUsedMb(), srv.heapMaxMb()));
            lines.add(l1);
        }

        MutableComponent l2 = txt("CLI ", CLIENT_ACCENT)
                .append(txt(cli.fps() + " FPS", colorFps(cli.fps())))
                .append(txt(" (1% ", COL_MUTED))
                .append(txt(String.valueOf(cli.fpsOnePercentLow()), colorFps(cli.fpsOnePercentLow())))
                .append(txt(") · ", COL_MUTED))
                .append(txt(fmt1(cli.frameMedMs()) + "ms", colorMs(cli.frameMedMs(), 16.7f, 33.3f)))
                .append(txt(" · ", COL_MUTED))
                .append(formatMemShort(cli.heapUsedMb(), cli.heapMaxMb()));
        if (!cli.localServer() && cli.pingMs() >= 0) {
            l2.append(txt(" · ", COL_MUTED))
                    .append(txt(cli.pingMs() + "ms", colorPing(cli.pingMs())));
        }
        lines.add(l2);

        int maxW = 0;
        for (Component line : lines) {
            maxW = Math.max(maxW, font.width(line));
        }
        int x = 6;
        int y = 6;
        int w = maxW + PAD_X * 2;
        int h = lines.size() * 10 + 9;

        gg.fill(x, y, x + w, y + h, BG_COLOR);
        gg.renderOutline(x, y, w, h, BORDER_COLOR);
        int cy = y + 5;
        for (Component line : lines) {
            gg.drawString(font, line, x + PAD_X, cy, COL_VALUE, true);
            cy += 10;
        }
    }

    private static int clampY(int y, int h, int screenH) {
        return Math.max(4, Math.min(y, Math.max(4, screenH - h - 4)));
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int withAlpha(int rgb, int alphaByte) {
        return (alphaByte << 24) | (rgb & 0x00FFFFFF);
    }

    private static String formatDimName(String raw) {
        return switch (raw) {
            case "overworld" -> "Overworld";
            case "nether" -> "Nether";
            case "end" -> "The End";
            default -> {
                StringBuilder sb = new StringBuilder(raw.length());
                boolean cap = true;
                for (int i = 0; i < raw.length(); i++) {
                    char ch = raw.charAt(i);
                    if (ch == '_' || ch == '-' || ch == '/') {
                        sb.append(' ');
                        cap = true;
                    } else {
                        sb.append(cap ? Character.toUpperCase(ch) : ch);
                        cap = false;
                    }
                }
                yield sb.toString();
            }
        };
    }

    private static String trimToWidth(Font font, String s, int maxW) {
        if (font.width(s) <= maxW)
            return s;
        String ell = "..";
        int ellW = font.width(ell);
        int end = s.length();
        while (end > 1 && font.width(s.substring(0, end)) + ellW > maxW) {
            end--;
        }
        return s.substring(0, end) + ell;
    }

    private static Component formatTps(float tps) {
        String text = tps >= 19.95f ? "*20.0" : fmt1(tps);
        return txt(text, colorTps(tps));
    }

    private static MutableComponent formatCpu(float pct) {
        if (pct < 0f)
            return txt("--", COL_MUTED);
        return txt(Math.round(pct) + "%", colorCpu(pct));
    }

    private static Component formatMemShort(int usedMb, int maxMb) {
        int pct = maxMb > 0 ? Math.min(100, Math.round(usedMb * 100f / maxMb)) : 0;
        return txt(fmtMem(usedMb) + "/" + fmtMem(maxMb), colorPct(pct));
    }

    private static String fmtMem(int mb) {
        if (mb >= 1024)
            return String.format(Locale.ROOT, "%.1fG", mb / 1024.0);
        return mb + "M";
    }

    private static String fmt1(float v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static String fmt0(float v) {
        return v >= 100f ? String.valueOf(Math.round(v)) : fmt1(v);
    }

    private static String fmtInt(int v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static int colorTps(float tps) {
        return tps >= 19.5f ? COL_GREEN : (tps >= 17.0f ? COL_YELLOW : COL_RED);
    }

    private static int colorCpu(float pct) {
        return pct < 60f ? COL_GREEN : (pct < 85f ? COL_YELLOW : COL_RED);
    }

    private static int colorPct(int pct) {
        return pct < 70 ? COL_GREEN : (pct < 85 ? COL_YELLOW : COL_RED);
    }

    private static int colorMs(float ms, float warn, float bad) {
        return ms < warn ? COL_GREEN : (ms < bad ? COL_YELLOW : COL_RED);
    }

    private static int colorFps(int fps) {
        return fps >= 60 ? COL_GREEN : (fps >= 30 ? COL_YELLOW : COL_RED);
    }

    private static int colorPing(int ms) {
        return ms < 60 ? COL_GREEN : (ms < 150 ? COL_YELLOW : COL_RED);
    }

    private static MutableComponent txt(String s, int rgb) {
        return Component.literal(s).withStyle(st -> st.withColor(rgb & 0xFFFFFF));
    }
}
