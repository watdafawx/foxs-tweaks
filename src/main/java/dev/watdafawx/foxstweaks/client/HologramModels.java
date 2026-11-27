package dev.watdafawx.foxstweaks.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.platform.NativeImage;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramModelSettings;
import dev.watdafawx.foxstweaks.hologram.model.ModelData;
import dev.watdafawx.foxstweaks.hologram.model.ModelPose;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

import javax.annotation.Nullable;

/**
 * Draws 3D model layers. One {@link Runtime} per asset hash holds the decoded {@link ModelData}, its textures (as
 * dynamic textures) and scratch arrays, so a frame allocates nothing: every mesh is posed on the CPU
 * ({@link ModelPose}) and emitted as triangles through the layer's own buffer, which keeps tint, glow and shader
 * packs working like any other hologram content. A hologram holds a few models at most (the server caps it), so
 * the CPU cost is what the vertex caps in {@link ModelData} bound.
 */
public final class HologramModels {
    private static final int MAX_RUNTIMES = 8;
    private static final Map<String, Runtime> RUNTIMES = new LinkedHashMap<>(16, 0.75F, true);
    private static ResourceLocation white;
    private static int counter;

    /** Dev check only: triangles emitted by the last model drawn, and its posed bounds' height. */
    public static volatile int lastTriangles;
    public static volatile int lastLayerTexture = -1;

    private HologramModels() {
    }

    private record CompiledVbo(RenderType type, VertexBuffer vbo) {
    }

    private static final class Runtime {
        final ModelData model;
        final ModelPose pose;
        final List<DynamicTexture> textures = new ArrayList<>();
        final List<ResourceLocation> locations = new ArrayList<>();
        /** Scratch per mesh (identity: a prim is one object). */
        final Map<ModelData.Prim, float[][]> scratch = new IdentityHashMap<>();
        /** Rest-pose bounds {minX, minY, minZ, maxX, maxY, maxZ}. */
        final float[] bounds;
        int lastAnim = Integer.MIN_VALUE;
        double lastTime = Double.NaN;
        /** Compiled GPU VBOs for static / rest-pose / paused draws (one draw call per RenderType). */
        final List<CompiledVbo> vbos = new ArrayList<>();
        int vboLight = Integer.MIN_VALUE;
        int vboAnim = Integer.MIN_VALUE;
        double vboTime = Double.NaN;
        int vboTriangles;

        Runtime(String hash, ModelData model) {
            this.model = model;
            this.pose = new ModelPose(model);
            this.bounds = this.pose.bounds(-1, 0.0);
            int n = ++counter;
            for (int i = 0; i < model.textures.size(); i++) {
                try (NativeImage image = NativeImage.read(new ByteArrayInputStream(model.textures.get(i).png))) {
                    DynamicTexture tex = new DynamicTexture(image);
                    ResourceLocation loc = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "model/" + n + "/" + i);
                    Minecraft.getInstance().getTextureManager().register(loc, tex);
                    this.textures.add(tex);
                    this.locations.add(loc);
                } catch (IOException | RuntimeException e) {
                    this.textures.add(null);
                    this.locations.add(null);
                }
            }
            for (ModelData.Node node : model.nodes)
                for (ModelData.Prim p : node.prims)
                    this.scratch.put(p, new float[][] { new float[p.pos.length], new float[p.nrm.length] });
        }

        void closeVbos() {
            for (CompiledVbo cb : this.vbos) {
                try {
                    cb.vbo.close();
                } catch (Throwable ignored) {
                }
            }
            this.vbos.clear();
            this.vboLight = Integer.MIN_VALUE;
        }

        void rebuildVbos(int light) {
            closeVbos();
            Map<RenderType, List<ModelData.Prim>> byType = new LinkedHashMap<>();
            for (int n = 0; n < this.model.nodes.size(); n++) {
                for (ModelData.Prim p : this.model.nodes.get(n).prims) {
                    ResourceLocation tex = p.texture >= 0 && p.texture < this.locations.size()
                            ? this.locations.get(p.texture) : null;
                    if (tex == null)
                        tex = white();
                    RenderType type = p.alphaMode == ModelData.ALPHA_BLEND ? RenderType.entityTranslucent(tex)
                            : p.doubleSided ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
                    byType.computeIfAbsent(type, k -> new ArrayList<>()).add(p);
                }
            }
            int triangles = 0;
            for (var entry : byType.entrySet()) {
                RenderType type = entry.getKey();
                List<ModelData.Prim> prims = entry.getValue();
                int estVerts = 0;
                for (ModelData.Prim p : prims)
                    estVerts += (p.idx.length / 3) * 4;
                int cap = Math.max(256, estVerts * type.format().getVertexSize());
                try (ByteBufferBuilder bb = new ByteBufferBuilder(cap)) {
                    BufferBuilder buf = new BufferBuilder(bb, type.mode(), type.format());
                    for (ModelData.Prim p : prims) {
                        float[][] s = this.scratch.get(p);
                        float[] pos = s[0], nrm = s[1];
                        int cr = to255(p.cr), cg = to255(p.cg), cb = to255(p.cb), ca = to255(p.ca);
                        for (int t = 0; t + 2 < p.idx.length; t += 3) {
                            int a = p.idx[t], b = p.idx[t + 1], c = p.idx[t + 2];
                            emitLocal(buf, pos, nrm, p.uv, a, cr, cg, cb, ca, light);
                            emitLocal(buf, pos, nrm, p.uv, b, cr, cg, cb, ca, light);
                            emitLocal(buf, pos, nrm, p.uv, c, cr, cg, cb, ca, light);
                            emitLocal(buf, pos, nrm, p.uv, c, cr, cg, cb, ca, light);
                            triangles++;
                        }
                    }
                    MeshData mesh = buf.build();
                    if (mesh != null) {
                        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
                        vbo.bind();
                        vbo.upload(mesh);
                        VertexBuffer.unbind();
                        this.vbos.add(new CompiledVbo(type, vbo));
                    }
                }
            }
            this.vboLight = light;
            this.vboAnim = this.lastAnim;
            this.vboTime = this.lastTime;
            this.vboTriangles = triangles;
        }

        void release() {
            closeVbos();
            var manager = Minecraft.getInstance().getTextureManager();
            for (ResourceLocation loc : this.locations)
                if (loc != null)
                    manager.release(loc);
        }
    }

    /** The model's rest-pose height in blocks, 1 while it loads. Used for gradients and reach hints. */
    public static float nativeHeight(String hash) {
        Runtime r = RUNTIMES.get(hash);
        return r == null ? 1.0F : Math.max(0.001F, r.bounds[4] - r.bounds[1]);
    }

    private static ModelData lookup(String hash) {
        if (dev.watdafawx.foxstweaks.hologram.HologramNetwork.isExternalUrl(hash))
            return dev.watdafawx.foxstweaks.client.asset.HologramUrlModels.model(hash);
        return HologramAssets.model(hash);
    }

    /** Names of the animations of a loaded model, empty while it loads. */
    public static List<String> animations(String hash) {
        ModelData m = lookup(hash);
        List<String> out = new ArrayList<>();
        if (m != null)
            for (ModelData.Animation a : m.animations)
                out.add(a.name);
        return out;
    }

    /** Length in seconds of the named animation of {@code hash}, 0 when unknown. */
    public static float animationLength(String hash, String name) {
        ModelData m = lookup(hash);
        if (m == null)
            return 0.0F;
        int i = m.animationIndex(name);
        return i < 0 ? 0.0F : m.animations.get(i).length;
    }

    public static void clear() {
        for (Runtime r : RUNTIMES.values())
            r.release();
        RUNTIMES.clear();
        HologramModelThumbs.clear();
        dev.watdafawx.foxstweaks.client.asset.HologramUrlModels.clear();
        if (white != null) {
            Minecraft.getInstance().getTextureManager().release(white);
            white = null;
        }
    }

    /**
     * Draws {@code hash} at rest pose, centered at origin and normalized so its largest dimension is ~1.1 units.
     * Used by {@link HologramModelThumbs} to bake 96x96 offscreen thumbnails for the Asset Library.
     */
    static boolean drawForThumbnail(PoseStack pose, MultiBufferSource out, String hash) {
        Runtime r = runtime(hash);
        if (r == null)
            return false;
        if (r.lastAnim != -1 || r.lastTime != 0.0) {
            r.pose.evaluate(-1, 0.0);
            for (int n = 0; n < r.model.nodes.size(); n++) {
                for (ModelData.Prim p : r.model.nodes.get(n).prims) {
                    float[][] s = r.scratch.get(p);
                    r.pose.pose(n, p, s[0], s[1]);
                }
            }
            r.lastAnim = -1;
            r.lastTime = 0.0;
        }
        float[] b = r.bounds;
        float extX = Math.max(0.001F, b[3] - b[0]);
        float extY = Math.max(0.001F, b[4] - b[1]);
        float extZ = Math.max(0.001F, b[5] - b[2]);
        float maxExt = Math.max(extX, Math.max(extY, extZ));
        float k = 1.1F / maxExt;

        pose.pushPose();
        pose.scale(k, k, k);
        pose.translate(-(b[0] + b[3]) * 0.5F, -(b[1] + b[4]) * 0.5F, -(b[2] + b[5]) * 0.5F);
        PoseStack.Pose last = pose.last();
        int light = 0xF000F0;
        for (int n = 0; n < r.model.nodes.size(); n++) {
            for (ModelData.Prim p : r.model.nodes.get(n).prims) {
                float[][] s = r.scratch.get(p);
                ResourceLocation tex = p.texture >= 0 && p.texture < r.locations.size() ? r.locations.get(p.texture) : null;
                if (tex == null)
                    tex = white();
                RenderType type = p.alphaMode == ModelData.ALPHA_BLEND ? RenderType.entityTranslucent(tex)
                        : p.doubleSided ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
                VertexConsumer vc = out.getBuffer(type);
                int cr = to255(p.cr), cg = to255(p.cg), cb = to255(p.cb), ca = to255(p.ca);
                float[] pos = s[0], nrm = s[1];
                for (int t = 0; t + 2 < p.idx.length; t += 3) {
                    int a = p.idx[t], bb = p.idx[t + 1], c = p.idx[t + 2];
                    vertex(vc, last, pos, nrm, p.uv, a, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, bb, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, c, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, c, cr, cg, cb, ca, light);
                }
            }
        }
        pose.popPose();
        return true;
    }

    private static Runtime runtime(String hash) {
        Runtime r = RUNTIMES.get(hash);
        if (r != null)
            return r;
        ModelData m = lookup(hash);
        if (m == null)
            return null;
        if (RUNTIMES.size() >= MAX_RUNTIMES) {
            var it = RUNTIMES.entrySet().iterator();
            var eldest = it.next();
            eldest.getValue().release();
            it.remove();
        }
        r = new Runtime(hash, m);
        RUNTIMES.put(hash, r);
        return r;
    }

    private static ResourceLocation white() {
        if (white == null) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFFFFFFFF);
            white = ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "model/white");
            Minecraft.getInstance().getTextureManager().register(white, new DynamicTexture(image));
        }
        return white;
    }

    /**
     * Draws the layer's model at the current pose, bottom-centred on the origin and scaled so its rest-pose height
     * is the layer's {@code height} setting (0 keeps its own size). While the asset loads, a faint box marks the spot.
     */
    public static void draw(PoseStack pose, MultiBufferSource out, HologramLayer layer, int light, double clock) {
        draw(pose, out, layer, light, clock, false);
    }

    /**
     * Same as {@link #draw(PoseStack, MultiBufferSource, HologramLayer, int, double)}, optionally auto-selecting a
     * walk/run/move animation when {@code autoWalk} is true and no explicit animation is chosen on the layer.
     */
    public static void draw(PoseStack pose, MultiBufferSource out, HologramLayer layer, int light, double clock,
                            boolean autoWalk) {
        draw(pose, out, layer, light, clock, autoWalk, null, null, 1.0F, 1.0F, 1.0F);
    }

    /**
     * Draws the layer's model; when {@code view} and {@code projection} are provided and the pose is static or
     * unchanged (e.g. rest-pose / paused high-poly models), renders via compiled GPU {@link VertexBuffer}s in one
     * draw call per render type instead of submitting every triangle on the CPU each frame.
     */
    public static void draw(PoseStack pose, MultiBufferSource out, HologramLayer layer, int light, double clock,
                            boolean autoWalk, @Nullable Matrix4f view, @Nullable Matrix4f projection,
                            float tintR, float tintG, float tintB) {
        HologramModelSettings settings = layer.model();
        Runtime r = runtime(layer.id());
        if (r == null) {
            float h = settings.height() > 0.0F ? settings.height() : 1.0F;
            LevelRenderer.renderLineBox(pose, out.getBuffer(RenderType.lines()),
                    -h * 0.3, 0.0, -h * 0.3, h * 0.3, h, h * 0.3, 0.55F, 0.85F, 1.0F, 0.6F);
            lastTriangles = 0;
            return;
        }

        int anim = r.model.animationIndex(settings.animation());
        if (anim < 0 && autoWalk && !r.model.animations.isEmpty())
            anim = findWalkAnimation(r.model);
        double time = 0.0;
        if (anim >= 0)
            time = settings.time(clock, r.model.animations.get(anim).length);
        boolean dirtyPose = anim != r.lastAnim || time != r.lastTime;
        if (dirtyPose) {
            r.pose.evaluate(anim, time);
            for (int n = 0; n < r.model.nodes.size(); n++) {
                for (ModelData.Prim p : r.model.nodes.get(n).prims) {
                    float[][] s = r.scratch.get(p);
                    r.pose.pose(n, p, s[0], s[1]);
                }
            }
            r.lastAnim = anim;
            r.lastTime = time;
        }

        float[] b = r.bounds;
        float nativeH = Math.max(0.001F, b[4] - b[1]);
        float k = settings.height() > 0.0F ? settings.height() / nativeH : 1.0F;
        pose.pushPose();
        pose.scale(k, k, k);
        pose.translate(-(b[0] + b[3]) * 0.5F, -b[1], -(b[2] + b[5]) * 0.5F);

        if (view != null && projection != null && (anim < 0 || !dirtyPose)) {
            if (r.vbos.isEmpty() || r.vboLight != light || r.vboAnim != r.lastAnim || r.vboTime != r.lastTime)
                r.rebuildVbos(light);
            Matrix4f mv = new Matrix4f(view).mul(pose.last().pose());
            for (int i = 0; i < r.vbos.size(); i++) {
                CompiledVbo cb = r.vbos.get(i);
                cb.type.setupRenderState();
                ShaderInstance shader = RenderSystem.getShader();
                try {
                    if (shader != null) {
                        RenderSystem.setShaderColor(tintR, tintG, tintB, 1.0F);
                        cb.vbo.bind();
                        cb.vbo.drawWithShader(mv, projection, shader);
                        VertexBuffer.unbind();
                    }
                } finally {
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    cb.type.clearRenderState();
                }
            }
            lastTriangles = r.vboTriangles;
            pose.popPose();
            return;
        }

        PoseStack.Pose last = pose.last();

        int triangles = 0;
        for (int n = 0; n < r.model.nodes.size(); n++) {
            for (ModelData.Prim p : r.model.nodes.get(n).prims) {
                float[][] s = r.scratch.get(p);
                ResourceLocation tex = p.texture >= 0 && p.texture < r.locations.size() ? r.locations.get(p.texture) : null;
                if (tex == null)
                    tex = white();
                RenderType type = p.alphaMode == ModelData.ALPHA_BLEND ? RenderType.entityTranslucent(tex)
                        : p.doubleSided ? RenderType.entityCutoutNoCull(tex) : RenderType.entityCutout(tex);
                VertexConsumer vc = out.getBuffer(type);
                int cr = to255(p.cr), cg = to255(p.cg), cb = to255(p.cb), ca = to255(p.ca);
                float[] pos = s[0], nrm = s[1];
                for (int t = 0; t + 2 < p.idx.length; t += 3) {
                    int a = p.idx[t], bb = p.idx[t + 1], c = p.idx[t + 2];
                    vertex(vc, last, pos, nrm, p.uv, a, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, bb, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, c, cr, cg, cb, ca, light);
                    vertex(vc, last, pos, nrm, p.uv, c, cr, cg, cb, ca, light); // triangles go out as degenerate quads
                    triangles++;
                }
            }
        }
        lastTriangles = triangles;
        pose.popPose();
    }

    private static void emitLocal(VertexConsumer vc, float[] pos, float[] nrm, float[] uv, int i,
                                  int r, int g, int b, int a, int light) {
        vc.addVertex(pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                .setColor(r, g, b, a)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]);
    }

    private static int findWalkAnimation(ModelData model) {
        String[] prefs = { "walk", "run", "move", "swim", "fly" };
        for (String key : prefs) {
            for (int i = 0; i < model.animations.size(); i++) {
                String name = model.animations.get(i).name;
                if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(key))
                    return i;
            }
        }
        return 0;
    }

    private static int to255(float f) {
        return Math.max(0, Math.min(255, Math.round(f * 255.0F)));
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose pose, float[] pos, float[] nrm, float[] uv, int i,
                               int r, int g, int b, int a, int light) {
        vc.addVertex(pose, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2])
                .setColor(r, g, b, a)
                .setUv(uv[i * 2], uv[i * 2 + 1])
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nrm[i * 3], nrm[i * 3 + 1], nrm[i * 3 + 2]);
    }
}
