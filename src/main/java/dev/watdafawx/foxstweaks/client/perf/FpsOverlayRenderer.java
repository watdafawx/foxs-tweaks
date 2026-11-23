package dev.watdafawx.foxstweaks.client.perf;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
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
 * Draws Observable-style through-wall 3D heat boxes + floating µs/frame labels over the hottest
 * BlockEntities and Entities recorded by {@link FpsProfiler}, plus a compact status badge on the HUD.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public final class FpsOverlayRenderer {
    private static final double MAX_LABEL_DIST_SQ = 48.0 * 48.0;
    private static final int MAX_LABELS = 15;

    private FpsOverlayRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;
        if (!FpsProfiler.isShowingOverlay())
            return;

        List<FpsProfiler.Hotspot> list = FpsProfiler.getHotspots();
        if (list.isEmpty())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

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

        // Pass 1: translucent filled boxes
        BufferBuilder quads = tess.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        boolean anyBox = false;
        for (FpsProfiler.Hotspot h : list) {
            AABB box = resolveBox(mc, h);
            if (box == null)
                continue;
            float[] rgb = heatColor(h.microsPerFrame());
            addFilledBox(quads, mat, box, rgb[0], rgb[1], rgb[2], 0.24f);
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
            AABB box = resolveBox(mc, h);
            if (box == null)
                continue;
            float[] rgb = heatColor(h.microsPerFrame());
            addWireBox(lines, mat, box, rgb[0], rgb[1], rgb[2], 0.92f);
            anyLine = true;
        }
        if (anyLine) {
            var mesh = lines.build();
            if (mesh != null)
                BufferUploader.drawWithShader(mesh);
        }

        // Pass 3: floating µs/frame labels on top N nearby hotspots
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        Font font = mc.font;
        int labelsDrawn = 0;
        for (FpsProfiler.Hotspot h : list) {
            if (labelsDrawn >= MAX_LABELS)
                break;
            AABB box = resolveBox(mc, h);
            if (box == null)
                continue;
            Vec3 center = box.getCenter();
            double distSq = center.distanceToSqr(camPos);
            if (distSq > MAX_LABEL_DIST_SQ)
                continue;

            double dist = Math.sqrt(distSq);
            float scale = (float) (0.018 * Math.max(1.0, dist / 6.0));
            String text = String.format(Locale.ROOT, "%.0f \u00b5s  %s", h.microsPerFrame(), h.label());

            pose.pushPose();
            pose.translate(center.x, box.maxY + 0.35, center.z);
            pose.mulPose(camera.rotation());
            pose.scale(scale, -scale, scale);

            float x = -font.width(text) / 2.0f;
            int col = packRgb(heatColor(h.microsPerFrame()));
            font.drawInBatch(text, x, 0, col, false, pose.last().pose(), buf,
                    Font.DisplayMode.SEE_THROUGH, 0xA0000000, 0xF000F0);
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

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        if (!FpsProfiler.isShowingOverlay())
            return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui)
            return;

        GuiGraphics gg = event.getGuiGraphics();
        Font font = mc.font;
        List<FpsProfiler.Hotspot> list = FpsProfiler.getHotspots();

        String line;
        int accent;
        if (FpsProfiler.sampling) {
            String src = FpsProfiler.isLinkedToSpark() ? "sparkc" : String.format(Locale.ROOT, "%.1fs", FpsProfiler.getRemainingSeconds());
            line = String.format(Locale.ROOT, "FPS Scan (%s) · %d hotspots · %d f",
                    src, list.size(), FpsProfiler.getFrameCount());
            accent = 0xFFFFAA00;
        } else {
            line = String.format(Locale.ROOT, "FPS Highlights (%d) · /foxstweaks fps clear", list.size());
            accent = 0xFF55FF55;
        }

        int w = font.width(line) + 12;
        int x = 8;
        int y = 8;
        gg.fill(x, y, x + w, y + 16, 0xCC0B0D12);
        gg.fill(x, y, x + 2, y + 16, accent);
        gg.drawString(font, line, x + 6, y + 4, 0xFFE8EDF5, false);
    }

    private static AABB resolveBox(Minecraft mc, FpsProfiler.Hotspot h) {
        if (h.isEntity()) {
            Entity e = mc.level != null ? mc.level.getEntity(h.entityId()) : null;
            return e != null ? e.getBoundingBox().inflate(0.03) : (h.fallbackBox() != null ? h.fallbackBox().inflate(0.03) : null);
        }
        return h.blockPos() != null ? new AABB(h.blockPos()).inflate(0.01) : h.fallbackBox();
    }

    /** Green (<80µs) -> Yellow (200µs) -> Orange (450µs) -> Red (>=700µs). */
    private static float[] heatColor(double micros) {
        if (micros >= 700.0)
            return new float[] { 1.0f, 0.18f, 0.18f };
        if (micros >= 400.0)
            return new float[] { 1.0f, 0.50f, 0.12f };
        if (micros >= 150.0)
            return new float[] { 1.0f, 0.85f, 0.15f };
        return new float[] { 0.35f, 0.95f, 0.35f };
    }

    private static int packRgb(float[] rgb) {
        int r = Math.min(255, Math.max(0, (int) (rgb[0] * 255.0f)));
        int g = Math.min(255, Math.max(0, (int) (rgb[1] * 255.0f)));
        int b = Math.min(255, Math.max(0, (int) (rgb[2] * 255.0f)));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
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
