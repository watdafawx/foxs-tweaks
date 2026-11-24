package dev.watdafawx.foxstweaks.client.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.FoxsTweaksKeys;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Locale;

/**
 * Draws Observable-style through-wall 3D heat boxes + numbered floating labels over the hottest
 * BlockEntities and Entities recorded by {@link FpsProfiler}, plus a HUD list (top-left) with the top hotspots,
 * your own render cost, and where each one is.
 *
 * <p>Which labels you see is not random: the world shows the top {@value #MAX_LABELS} entries in rank order
 * (the number matches the HUD list and the report), within {@value #MAX_LABEL_DIST} blocks. Everything else in
 * the list still gets its box. Labels keep a constant size on screen (see {@link #labelScale}).
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class FpsOverlayRenderer {
    private static final double MAX_LABEL_DIST = 160.0;
    private static final int MAX_LABELS = 20;
    private static final int HUD_ROWS = 5;

    private FpsOverlayRenderer() {
    }

    /** Constant on-screen size: world size grows with distance (clamped so it never explodes up close). */
    private static float labelScale(double dist) {
        return (float) (0.0030 * Mth.clamp(dist, 2.0, 120.0));
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        if (!FpsProfiler.isShowingOverlay())
            return;

        List<FpsProfiler.Hotspot> list = FpsProfiler.worldList();
        if (list.isEmpty())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        boolean focused = FpsProfiler.getFocusItem() != null;
        Camera camera = event.getCamera();
        Vec3 camPos = camera.getPosition();
        PoseStack pose = event.getPoseStack();

        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f mat = pose.last().pose();

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tess = Tesselator.getInstance();

        // Pass 1: translucent filled boxes (+ a tall beam on the one thing being tracked)
        BufferBuilder quads = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean anyBox = false;
        for (FpsProfiler.Hotspot h : list) {
            AABB box = FpsProfiler.resolveBox(mc, h);
            if (box == null)
                continue;
            float[] rgb = FpsProfiler.heatRgb(h.microsPerFrame());
            addFilledBox(quads, mat, box, rgb[0], rgb[1], rgb[2], 0.24f);
            if (focused) {
                Vec3 c = box.getCenter();
                AABB beam = new AABB(c.x - 0.06, box.minY - 64.0, c.z - 0.06, c.x + 0.06, box.maxY + 192.0, c.z + 0.06);
                addFilledBox(quads, mat, beam, rgb[0], rgb[1], rgb[2], 0.55f);
            }
            anyBox = true;
        }
        if (anyBox) {
            var mesh = quads.build();
            if (mesh != null)
                BufferUploader.drawWithShader(mesh);
        }

        // Pass 2: crisp wireframe outlines
        BufferBuilder lines = tess.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        boolean anyLine = false;
        for (FpsProfiler.Hotspot h : list) {
            AABB box = FpsProfiler.resolveBox(mc, h);
            if (box == null)
                continue;
            float[] rgb = FpsProfiler.heatRgb(h.microsPerFrame());
            addWireBox(lines, mat, box, rgb[0], rgb[1], rgb[2], 0.92f);
            anyLine = true;
        }
        if (anyLine) {
            var mesh = lines.build();
            if (mesh != null)
                BufferUploader.drawWithShader(mesh);
        }

        // Pass 3: numbered labels, rank order, so "#3" here is "3." in the HUD list and the report.
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        int labelsDrawn = 0;
        int maxLabels = FpsProfiler.getFocusType() != null ? MAX_LABELS * 2 : MAX_LABELS;
        for (int i = 0; i < list.size() && labelsDrawn < maxLabels; i++) {
            FpsProfiler.Hotspot h = list.get(i);
            AABB box = FpsProfiler.resolveBox(mc, h);
            if (box == null)
                continue;
            Vec3 center = box.getCenter();
            double dist = center.distanceTo(camPos);
            if (dist > MAX_LABEL_DIST)
                continue;

            float scale = labelScale(dist);
            double us = h.microsPerFrame();
            Component text = Component.literal(focused ? "" : "#" + (i + 1) + " ")
                    .withStyle(Style.EMPTY.withColor(FpsProfiler.heatChat(us)).withBold(true))
                    .append(Component.literal(String.format(Locale.ROOT, "%.0f µs", us))
                            .withStyle(FpsProfiler.heatChat(us)))
                    .append(Component.literal(percentOf(us) + "  " + h.label()).withStyle(ChatFormatting.WHITE));

            pose.pushPose();
            pose.translate(center.x, box.maxY + 0.2 + 5.0 * scale, center.z);
            pose.mulPose(camera.rotation());
            pose.scale(scale, -scale, scale);

            float x = -font.width(text) / 2.0f;
            // Drop shadow instead of the font's backdrop quad: the backdrop z-fought with the text and dimmed its tail.
            font.drawInBatch(text, x, 0, 0xFFFFFFFF, true, pose.last().pose(), buf,
                    Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
            pose.popPose();
            labelsDrawn++;
        }
        buf.endBatch();

        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        pose.popPose();
    }

    private static String percentOf(double us) {
        return FpsProfiler.frameMicros() > 0.0
                ? String.format(Locale.ROOT, " · %.1f%%", FpsProfiler.share(us) * 100.0) : "";
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!FpsProfiler.isShowingOverlay())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.screen instanceof FpsReportScreen)
            return; // the report shows the same numbers; the HUD list would bleed through its panels

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        List<FpsProfiler.Hotspot> list = FpsProfiler.getHotspots();

        String header;
        int accent;
        if (FpsProfiler.sampling) {
            String src = FpsProfiler.isLinkedToSpark() ? "sparkc" : String.format(Locale.ROOT, "%.1fs", FpsProfiler.getRemainingSeconds());
            header = String.format(Locale.ROOT, "FPS Scan (%s) · %d hotspots · %d f",
                    src, list.size(), FpsProfiler.getFrameCount());
            accent = 0xFFFFAA00;
        } else {
            double fm = FpsProfiler.frameMicros();
            header = fm > 0
                    ? String.format(Locale.ROOT, "FPS Highlights (%d) · avg %.1f ms (%.0f FPS)", list.size(), fm / 1000.0, 1_000_000.0 / fm)
                    : String.format(Locale.ROOT, "FPS Highlights (%d)", list.size());
            accent = 0xFF55FF55;
        }

        // Rows: top hotspots, then you. Built first so the panel can be as wide as its widest line.
        int rows = Math.min(HUD_ROWS, list.size());
        String[] rowText = new String[rows];
        int[] rowColor = new int[rows];
        for (int i = 0; i < rows; i++) {
            FpsProfiler.Hotspot h = list.get(i);
            double dist = FpsActions.distance(h);
            rowText[i] = String.format(Locale.ROOT, "#%d  %s%.0f µs  %s  %s %s", i + 1,
                    FpsProfiler.frameMicros() > 0 ? String.format(Locale.ROOT, "%.1f%%  ", FpsProfiler.share(h.microsPerFrame()) * 100.0) : "",
                    h.microsPerFrame(), h.label(),
                    dist >= 0 ? String.format(Locale.ROOT, "%.0fm", dist) : "", FpsActions.arrow(h));
            rowColor[i] = FpsProfiler.heatArgb(h.microsPerFrame());
        }
        double you = FpsProfiler.playerMicros();
        String youLine = you >= 1.0
                ? String.format(Locale.ROOT, "You  %.0f µs%s  (shadow pass / hands, not highlighted)", you,
                        FpsProfiler.frameMicros() > 0 ? String.format(Locale.ROOT, "  %.1f%%", FpsProfiler.share(you) * 100.0) : "")
                : null;

        String hint = FoxsTweaksKeys.OPEN_FPS_REPORT.isUnbound()
                ? "/foxstweaks fps report · clear"
                : FoxsTweaksKeys.OPEN_FPS_REPORT.getTranslatedKeyMessage().getString() + " = report · /foxstweaks fps clear";
        String focus = FpsProfiler.getFocusItem() != null
                ? "Showing 1 hotspot  (/foxstweaks fps show off)"
                : FpsProfiler.getFocusType() != null
                        ? "Showing every " + FpsProfiler.getFocusType() + "  (/foxstweaks fps show off)"
                        : null;

        int w = font.width(header);
        for (String r : rowText)
            w = Math.max(w, font.width(r));
        if (youLine != null)
            w = Math.max(w, font.width(youLine));
        if (focus != null)
            w = Math.max(w, font.width(focus));
        w = Math.max(w, font.width(hint));
        w += 12;

        int x = 8;
        int y = 8;
        int lines = 1 + rows + (youLine != null ? 1 : 0) + (focus != null ? 1 : 0) + (FpsProfiler.sampling ? 0 : 1);
        int h = lines * 11 + 5;
        gg.fill(x, y, x + w, y + h, 0xCC0B0D12);
        gg.fill(x, y, x + 2, y + h, accent);

        int ty = y + 4;
        gg.drawString(font, header, x + 6, ty, 0xFFE8EDF5, false);
        ty += 11;
        for (int i = 0; i < rows; i++) {
            gg.drawString(font, rowText[i], x + 6, ty, rowColor[i], false);
            ty += 11;
        }
        if (youLine != null) {
            gg.drawString(font, youLine, x + 6, ty, 0xFF9AA4B2, false);
            ty += 11;
        }
        if (focus != null) {
            gg.drawString(font, focus, x + 6, ty, 0xFFFFD866, false);
            ty += 11;
        }
        if (!FpsProfiler.sampling)
            gg.drawString(font, hint, x + 6, ty, 0xFF6F7885, false);
    }

    private static void addFilledBox(BufferBuilder b, Matrix4f m, AABB box, float r, float g, float bl, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        // -Y
        quad(b, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        // +Y
        quad(b, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, bl, a);
        // -Z
        quad(b, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl, a);
        // +Z
        quad(b, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        // -X
        quad(b, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        // +X
        quad(b, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl, a);
    }

    private static void quad(BufferBuilder b, Matrix4f m,
                             float x0, float y0, float z0,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             float x3, float y3, float z3,
                             float r, float g, float bl, float a) {
        b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
        b.addVertex(m, x2, y2, z2).setColor(r, g, bl, a);
        b.addVertex(m, x3, y3, z3).setColor(r, g, bl, a);
    }

    private static void addWireBox(BufferBuilder b, Matrix4f m, AABB box, float r, float g, float bl, float a) {
        float x0 = (float) box.minX, y0 = (float) box.minY, z0 = (float) box.minZ;
        float x1 = (float) box.maxX, y1 = (float) box.maxY, z1 = (float) box.maxZ;
        // bottom
        seg(b, m, x0, y0, z0, x1, y0, z0, r, g, bl, a);
        seg(b, m, x1, y0, z0, x1, y0, z1, r, g, bl, a);
        seg(b, m, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        seg(b, m, x0, y0, z1, x0, y0, z0, r, g, bl, a);
        // top
        seg(b, m, x0, y1, z0, x1, y1, z0, r, g, bl, a);
        seg(b, m, x1, y1, z0, x1, y1, z1, r, g, bl, a);
        seg(b, m, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        seg(b, m, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        // verticals
        seg(b, m, x0, y0, z0, x0, y1, z0, r, g, bl, a);
        seg(b, m, x1, y0, z0, x1, y1, z0, r, g, bl, a);
        seg(b, m, x1, y0, z1, x1, y1, z1, r, g, bl, a);
        seg(b, m, x0, y0, z1, x0, y1, z1, r, g, bl, a);
    }

    private static void seg(BufferBuilder b, Matrix4f m,
                            float x0, float y0, float z0,
                            float x1, float y1, float z1,
                            float r, float g, float bl, float a) {
        b.addVertex(m, x0, y0, z0).setColor(r, g, bl, a);
        b.addVertex(m, x1, y1, z1).setColor(r, g, bl, a);
    }
}
