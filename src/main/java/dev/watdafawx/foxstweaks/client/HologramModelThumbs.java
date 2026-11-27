package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.math.Axis;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Renders each uploaded 3D hologram model once into a 96x96 offscreen FBO at a 3/4 isometric angle
 * and caches the resulting {@link DynamicTexture} for the Asset Library grid.
 */
public final class HologramModelThumbs {
    private static final int SIZE = 96;
    private static final int MAX_THUMBS = 128;

    private static final Map<String, ResourceLocation> THUMBS = new LinkedHashMap<>(32, 0.75F, true);
    private static final AtomicInteger SEQ = new AtomicInteger();
    @Nullable
    private static TextureTarget fbo;
    private static long lastBakeNanos;

    private HologramModelThumbs() {
    }

    @Nullable
    public static ResourceLocation thumbnail(String hash) {
        if (hash == null || hash.isEmpty())
            return null;
        ResourceLocation cached = THUMBS.get(hash);
        if (cached != null)
            return cached;

        // Trigger download/decode if not yet in memory.
        if (HologramAssets.model(hash) == null)
            return null;

        // Bake at most one thumbnail per frame so scrolling a grid of models never hitches.
        long now = System.nanoTime();
        if (now - lastBakeNanos < 12_000_000L)
            return null;
        lastBakeNanos = now;

        ResourceLocation baked = bake(hash);
        if (baked != null) {
            if (THUMBS.size() >= MAX_THUMBS) {
                var it = THUMBS.entrySet().iterator();
                var eldest = it.next();
                Minecraft.getInstance().getTextureManager().release(eldest.getValue());
                it.remove();
            }
            THUMBS.put(hash, baked);
        }
        return baked;
    }

    @Nullable
    private static ResourceLocation bake(String hash) {
        Minecraft mc = Minecraft.getInstance();
        if (fbo == null)
            fbo = new TextureTarget(SIZE, SIZE, true, Minecraft.ON_OSX);

        Matrix4f savedProj = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting savedSort = RenderSystem.getVertexSorting();
        boolean scissorWasOn = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        var mvStack = RenderSystem.getModelViewStack();
        mvStack.pushMatrix();
        mvStack.identity();
        RenderSystem.applyModelViewMatrix();

        try {
            if (scissorWasOn)
                GL11.glDisable(GL11.GL_SCISSOR_TEST);
            fbo.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
            fbo.clear(Minecraft.ON_OSX);
            fbo.bindWrite(true);

            RenderSystem.setProjectionMatrix(
                    new Matrix4f().setOrtho(-0.75F, 0.75F, -0.75F, 0.75F, -10.0F, 10.0F),
                    VertexSorting.ORTHOGRAPHIC_Z);
            Lighting.setupFor3DItems();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            PoseStack pose = new PoseStack();
            pose.mulPose(Axis.XP.rotationDegrees(22.0F));
            pose.mulPose(Axis.YP.rotationDegrees(35.0F));

            MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
            boolean drew = HologramModels.drawForThumbnail(pose, buf, hash);
            buf.endBatch();
            if (!drew)
                return null;

            // Read back FBO color texture and flip Y (OpenGL origin is bottom-left).
            try (NativeImage raw = new NativeImage(SIZE, SIZE, false)) {
                RenderSystem.bindTexture(fbo.getColorTextureId());
                raw.downloadTexture(0, false);
                NativeImage flipped = new NativeImage(SIZE, SIZE, false);
                for (int y = 0; y < SIZE; y++) {
                    int srcY = SIZE - 1 - y;
                    for (int x = 0; x < SIZE; x++)
                        flipped.setPixelRGBA(x, y, raw.getPixelRGBA(x, srcY));
                }
                ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(
                        FoxsTweaks.MODID, "model_thumb/" + SEQ.incrementAndGet());
                mc.getTextureManager().register(loc, new DynamicTexture(flipped));
                return loc;
            }
        } catch (Throwable ignored) {
            return null;
        } finally {
            mvStack.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(savedProj, savedSort);
            Lighting.setupFor3DItems();
            mc.getMainRenderTarget().bindWrite(true);
            if (scissorWasOn)
                GL11.glEnable(GL11.GL_SCISSOR_TEST);
        }
    }

    public static void clear() {
        var tm = Minecraft.getInstance().getTextureManager();
        for (ResourceLocation loc : THUMBS.values())
            tm.release(loc);
        THUMBS.clear();
        if (fbo != null) {
            try {
                fbo.destroyBuffers();
            } catch (Throwable ignored) {
            }
            fbo = null;
        }
    }
}
