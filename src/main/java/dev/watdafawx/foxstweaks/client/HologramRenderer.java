package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import dev.watdafawx.foxstweaks.hologram.HologramStructure;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.Prop;
import dev.watdafawx.foxstweaks.hologram.HologramTimeline.TargetKind;
import dev.watdafawx.foxstweaks.hologram.HologramTimelineEval;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Renders unified holograms: root transform + walk-in reveal -> optional groups (linked transform,
 * animation & FX) -> first-class layers (independent transform, animation, fullbright & FX).
 */
public final class HologramRenderer {

    /** Keyed effect strengths per effect index, reused every draw (render thread only). */
    private static final float[] GROUP_FX = new float[HologramSpec.MAX_EFFECTS];
    private static final float[] LAYER_FX = new float[HologramSpec.MAX_EFFECTS];

    private static int lastDrawn;
    private static int drawnBlockEntities;
    private static long lastPassNanos;
    /** Dev check only: when set, layer 0 of this hologram records {keyed base yaw, motion yaw, final yaw, legacy spin + tremor}. */
    public static volatile String devTraceId;
    public static final float[] DEV_TRACE = new float[4];
    private static long lastAnimNanos;
    private static double animSeconds;
    private static final Matrix4f lastView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static Vec3 lastCamera = Vec3.ZERO;

    private static boolean paused;

    /**
     * Benchmark / debug toggle: when true, STRUCTURE layers bypass the compiled VBO cache and emit
     * every block via naive {@code renderSingleBlock}. Toggleable via {@code -Dfoxstweaks.hologram.uncachedStructure=true}
     * or directly from dev checks.
     */
    public static volatile boolean forceUncachedStructure = Boolean.getBoolean("foxstweaks.hologram.uncachedStructure");

    /** Editor-only: freezes every hologram's animation clock so a pose can be lined up. */
    public static void setPaused(boolean value) {
        paused = value;
    }

    public static boolean isPaused() {
        return paused;
    }

    /** Editor-only: restarts the animation clock, so every animation begins at its start again. */
    public static void restartClock() {
        animSeconds = 0.0;
    }

    public static double animSeconds() {
        return animSeconds;
    }

    public static Matrix4f lastView() {
        return lastView;
    }

    public static Matrix4f lastProjection() {
        return lastProjection;
    }

    public static Vec3 lastCamera() {
        return lastCamera;
    }

    public static int lastDrawnCount() {
        return lastDrawn;
    }

    public static int lastDrawnBlockEntityCount() {
        return drawnBlockEntities;
    }

    public static long lastPassNanos() {
        return lastPassNanos;
    }

    private static float lastEntityTicks = -1.0F;

    /** Age (in ticks, with fraction) the last entity layer was drawn at. Dev check only. */
    public static float lastEntityTicks() {
        return lastEntityTicks;
    }

    static float hoverOffset(float hover, float period, double seconds) {
        if (hover <= 0.0F)
            return 0.0F;
        double phase = (seconds % period) / period;
        return (float) (Math.sin(phase * (Math.PI * 2.0)) * hover);
    }

    static float spinAngle(float spin, double seconds) {
        return spin == 0.0F ? 0.0F : (float) ((seconds * spin) % 360.0);
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS)
            return;

        long started = System.nanoTime();
        try {
            renderPass(event);
        } finally {
            lastPassNanos = System.nanoTime() - started;
        }
    }

    private static void renderPass(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return;

        Vec3 camera = event.getCamera().getPosition();
        lastCamera = camera;
        lastView.set(event.getModelViewMatrix());
        lastProjection.set(event.getProjectionMatrix());

        List<HologramSpec> specs = HologramClient.specs();
        HologramSpec hovered = HologramClient.hovered();
        if (specs.isEmpty() && hovered == null)
            return;

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffer = mc.renderBuffers().bufferSource();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);

        long now = System.nanoTime();
        if (lastAnimNanos != 0L && !mc.isPaused() && !paused) {
            double dt = Math.min(0.1, (now - lastAnimNanos) * 1.0E-9);
            if (dt > 0.0)
                animSeconds += dt;
        }
        lastAnimNanos = now;
        double seconds = animSeconds;

        int globalCap = Config.hologramRenderCap();
        int drawn = 0;
        drawnBlockEntities = 0;
        for (int i = 0; i < specs.size(); i++) {
            HologramSpec spec = specs.get(i);
            if (!spec.enabled() || spec.layers().isEmpty())
                continue;

            double cap = Math.min(spec.renderDistance(), globalCap);
            AABB box = spec.box();
            Vec3 center = box.getCenter();
            if (camera.distanceToSqr(center) > cap * cap)
                continue;

            if (spec.timeline() != null) {
                // Keyed offsets and scales can carry content outside the static box.
                float pad = HologramTimelineEval.cullPad(spec.timeline()) * Math.max(1.0F, spec.scale());
                if (pad > 0.0F)
                    box = box.inflate(pad);
            }
            if (event.getFrustum() != null && !event.getFrustum().isVisible(box))
                continue;

            draw(pose, buffer, spec, seconds, camera, partial);
            if (HologramClient.appearProgress(spec.id()) > 0.01F)
                drawn++;
        }
        lastDrawn = drawn;

        if (hovered != null)
            drawOutline(pose, buffer, hovered, camera,
                    mc.player != null && mc.player.isShiftKeyDown());

        HologramSpec preview = HologramClient.previewSpec();
        if (preview != null && HologramClient.previewAnchored())
            drawAnchoredPreview(pose, buffer, preview, seconds, camera, mc, partial);

        buffer.endBatch();
    }

    private static void drawAnchoredPreview(PoseStack pose, MultiBufferSource buffer, HologramSpec spec,
                                            double seconds, Vec3 camera, Minecraft mc, float partialTick) {
        if (mc.player == null)
            return;

        Vec3 look = mc.player.getViewVector(1.0F);
        Vec3 right = new Vec3(-look.z, 0.0, look.x);
        if (right.lengthSqr() < 1.0E-4)
            right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();

        float refScale = spec.scale();
        HologramLayer first = spec.primaryLayer();
        if (first != null && first.kind() != HologramLayer.Kind.PARTICLE)
            refScale *= first.scale();
        double distance = 3.0 + 2.2 * Math.min(refScale, 4.0F);
        double drop = 0.35 + 0.15 * Math.min(refScale, 4.0F);

        Vec3 anchor = mc.player.getEyePosition()
                .add(look.scale(distance))
                .add(right.scale(0.15))
                .add(0.0, -drop, 0.0);

        drawAt(pose, buffer, spec, seconds, camera, anchor, 0xF000F0, 1.0F, partialTick);
    }

    public static void draw(PoseStack pose, MultiBufferSource buffer, HologramSpec spec, double seconds,
                            Vec3 camera, float partialTick) {
        Vec3 origin = Vec3.atLowerCornerOf(spec.pos()).add(spec.offsetX(), spec.offsetY(), spec.offsetZ());
        int worldLight = LevelRenderer.getLightColor(Minecraft.getInstance().level,
                BlockPos.containing(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5));
        drawAt(pose, buffer, spec, seconds, camera, origin, worldLight,
                HologramClient.appearProgress(spec.id()), partialTick);
    }

    private static void drawAt(PoseStack pose, MultiBufferSource buffer, HologramSpec spec, double seconds,
                               Vec3 camera, Vec3 origin, int worldLight, float appear, float partialTick) {
        if (appear <= 0.01F || spec.layers().isEmpty())
            return;

        HologramTriggers.beginFrame();
        HologramAnim.RevealPose reveal = HologramAnim.revealPose(spec.appearStyle(), appear, seconds);

        List<HologramLayer> layers = spec.layers();
        List<HologramGroup> groups = spec.groups();

        // Timeline: keyed values replace the static base (offset, rotation, scale); effects still add on top.
        HologramTimeline tl = spec.timeline();
        float rYaw = spec.yaw();
        float rPitch = spec.pitch();
        float rRoll = spec.roll();
        float rScale = spec.scale();
        double rdx = 0.0;
        double rdy = 0.0;
        double rdz = 0.0;
        int rootTint = 0xFFFFFF;
        if (tl != null) {
            HologramTimelineEval.evaluate(tl, groups, layers, HologramTimelinePlayer.time(spec, tl, partialTick));
            if (HologramTimelineEval.animates(tl, TargetKind.ROOT, 0)) {
                if (HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.VISIBLE), 1.0F) < 0.5F)
                    return;
                rdx = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.OFFSET_X), spec.offsetX()) - spec.offsetX();
                rdy = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.OFFSET_Y), spec.offsetY()) - spec.offsetY();
                rdz = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.OFFSET_Z), spec.offsetZ()) - spec.offsetZ();
                rYaw = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.ROT_Y), rYaw);
                rPitch = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.ROT_X), rPitch);
                rRoll = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.ROT_Z), rRoll);
                rScale = HologramTimelineEval.or(HologramTimelineEval.root(tl, Prop.SCALE), rScale);
                float tint = HologramTimelineEval.root(tl, Prop.TINT);
                if (!Float.isNaN(tint))
                    rootTint = (int) tint;
            }
        }
        buffer = TintedBuffer.tint(buffer, rootTint);

        pose.pushPose();
        pose.translate(origin.x + rdx + 0.5 - camera.x + reveal.glitchX(),
                origin.y + rdy - camera.y + reveal.dy(),
                origin.z + rdz + 0.5 - camera.z);
        pose.mulPose(Axis.YP.rotationDegrees(rYaw + reveal.spinY()));
        pose.mulPose(Axis.XP.rotationDegrees(rPitch));
        pose.mulPose(Axis.ZP.rotationDegrees(rRoll));
        pose.scale(rScale * reveal.sx(), rScale * reveal.sy(), rScale * reveal.sz());

        // 1. Draw ungrouped (independent) layers directly in root frame
        for (int i = 0; i < layers.size(); i++) {
            HologramLayer layer = layers.get(i);
            if (!layer.groupId().isEmpty())
                continue;
            drawLayer(pose, buffer, spec, null, List.of(), layer, i, worldLight, seconds, partialTick, rootTint);
        }

        // 2. Draw each group once, pushing its shared transform/animation/FX frame around its member layers
        for (int g = 0; g < groups.size(); g++) {
            HologramGroup group = groups.get(g);
            if (!group.visible())
                continue;
            boolean gAnim = tl != null && HologramTimelineEval.animates(tl, TargetKind.GROUP, g);
            float gOffXBase = group.offsetX();
            float gOffYBase = group.offsetY();
            float gOffZBase = group.offsetZ();
            float gYaw = group.yaw();
            float gPitch = group.pitch();
            float gRoll = group.roll();
            float gScale = group.scale();
            int gTint = 0xFFFFFF;
            float[] gKeyed = null;
            if (gAnim) {
                if (HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.VISIBLE), 1.0F) < 0.5F)
                    continue;
                gOffXBase = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.OFFSET_X), gOffXBase);
                gOffYBase = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.OFFSET_Y), gOffYBase);
                gOffZBase = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.OFFSET_Z), gOffZBase);
                gYaw = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.ROT_Y), gYaw);
                gPitch = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.ROT_X), gPitch);
                gRoll = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.ROT_Z), gRoll);
                gScale = HologramTimelineEval.or(HologramTimelineEval.group(tl, g, Prop.SCALE), gScale);
                float tint = HologramTimelineEval.group(tl, g, Prop.TINT);
                if (!Float.isNaN(tint))
                    gTint = (int) tint;
                if (HologramTimelineEval.effects(tl, TargetKind.GROUP, g, GROUP_FX))
                    gKeyed = GROUP_FX;
            }
            List<HologramEffect> gEffects = HologramTriggers.resolve(group.effects(), spec, seconds, gKeyed);
            if (HologramAnim.flickerHidden(gEffects, seconds))
                continue;

            float gHover = hoverOffset(group.hover(), group.hoverPeriod(), seconds);
            float gJump = HologramAnim.jumpOffset(gEffects, seconds);
            float gGlitchX = HologramAnim.glitchOffsetX(gEffects, seconds);
            float gGlitchZ = HologramAnim.glitchOffsetZ(gEffects, seconds);
            float gJitter = HologramAnim.tremorDegrees(gEffects, seconds);
            float gSpin = spinAngle(group.spin(), seconds);
            float gAngleY = gYaw + (group.spinAxis() == HologramSpec.AXIS_Y ? gSpin : 0.0F) + gJitter;
            float gAngleX = gPitch + (group.spinAxis() == HologramSpec.AXIS_X ? gSpin : 0.0F) + gJitter * 0.7F;
            float gAngleZ = gRoll + (group.spinAxis() == HologramSpec.AXIS_Z ? gSpin : 0.0F) - gJitter * 0.5F;
            float gWobbleY = HologramAnim.wobbleScaleY(gEffects, seconds);
            float gWobbleXZ = HologramAnim.wobbleScaleXZ(gEffects, seconds);
            HologramAnim.Motion gm = HologramAnim.motion(gEffects, seconds);

            pose.pushPose();
            pose.translate(gOffXBase + gGlitchX + gm.dx(), gOffYBase + gHover + gJump + gm.dy(),
                    gOffZBase + gGlitchZ + gm.dz());
            pose.mulPose(Axis.YP.rotationDegrees(gAngleY + gm.ry()));
            pose.mulPose(Axis.XP.rotationDegrees(gAngleX + gm.rx()));
            pose.mulPose(Axis.ZP.rotationDegrees(gAngleZ + gm.rz()));
            pose.scale(gScale * gWobbleXZ * gm.scale(), gScale * gWobbleY * gm.scale(),
                    gScale * gWobbleXZ * gm.scale());

            MultiBufferSource groupOut = TintedBuffer.wrap(TintedBuffer.tint(buffer, gTint), gEffects, seconds);
            int groupLight = group.fullbright() ? 0xF000F0 : worldLight;
            int inherited = TintedBuffer.multiply(rootTint, gTint);

            for (int i = 0; i < layers.size(); i++) {
                HologramLayer layer = layers.get(i);
                if (!group.id().equals(layer.groupId()))
                    continue;
                drawLayer(pose, groupOut, spec, group, gEffects, layer, i, groupLight, seconds, partialTick, inherited);
            }
            pose.popPose();
        }

        // 3. Fallback: any layer whose groupId references a deleted/missing group still renders in root frame
        for (int i = 0; i < layers.size(); i++) {
            HologramLayer layer = layers.get(i);
            if (!layer.groupId().isEmpty() && spec.groupById(layer.groupId()) == null)
                drawLayer(pose, buffer, spec, null, List.of(), layer, i, worldLight, seconds, partialTick, rootTint);
        }

        pose.popPose();
    }

    /**
     * @param gEffects  the group's effects as resolved for this frame (empty without a group)
     * @param inherited packed RGB tint the parents' timeline keys put on {@code parentBuffer} already; only the
     *                  cached structure path, which bypasses the buffer, needs to multiply it in itself
     */
    private static void drawLayer(PoseStack pose, MultiBufferSource parentBuffer, HologramSpec spec,
                                  @Nullable HologramGroup group, List<HologramEffect> gEffects, HologramLayer layer,
                                  int layerIndex, int parentLight, double seconds, float partialTick, int inherited) {
        if (!layer.visible() || layer.kind() == HologramLayer.Kind.PARTICLE)
            return;
        if (!HologramClient.passesSolo(spec.id(), layerIndex, layer))
            return;

        HologramTimeline tl = spec.timeline();
        boolean lAnim = tl != null && HologramTimelineEval.animates(tl, TargetKind.LAYER, layerIndex);
        float baseX = layer.offsetX();
        float baseY = layer.offsetY();
        float baseZ = layer.offsetZ();
        float baseYaw = layer.yaw();
        float basePitch = layer.pitch();
        float baseRoll = layer.roll();
        float baseScale = layer.scale();
        int lTint = 0xFFFFFF;
        float[] lKeyed = null;
        if (lAnim) {
            if (HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.VISIBLE), 1.0F) < 0.5F)
                return;
            baseX = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.OFFSET_X), baseX);
            baseY = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.OFFSET_Y), baseY);
            baseZ = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.OFFSET_Z), baseZ);
            baseYaw = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.ROT_Y), baseYaw);
            basePitch = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.ROT_X), basePitch);
            baseRoll = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.ROT_Z), baseRoll);
            baseScale = HologramTimelineEval.or(HologramTimelineEval.layer(tl, layerIndex, Prop.SCALE), baseScale);
            float tint = HologramTimelineEval.layer(tl, layerIndex, Prop.TINT);
            if (!Float.isNaN(tint))
                lTint = (int) tint;
            if (HologramTimelineEval.effects(tl, TargetKind.LAYER, layerIndex, LAYER_FX))
                lKeyed = LAYER_FX;
        }

        List<HologramEffect> effects = HologramTriggers.resolve(layer.effects(), spec, seconds, lKeyed);
        if (HologramAnim.flickerHidden(effects, seconds))
            return;

        float lHover = hoverOffset(layer.hover(), layer.hoverPeriod(), seconds);
        float lJump = HologramAnim.jumpOffset(effects, seconds);
        float lGlitchX = HologramAnim.glitchOffsetX(effects, seconds);
        float lGlitchZ = HologramAnim.glitchOffsetZ(effects, seconds);
        float lJitter = HologramAnim.tremorDegrees(effects, seconds);
        float lSpin = spinAngle(layer.spin(), seconds);
        float lAngleY = baseYaw + (layer.spinAxis() == HologramSpec.AXIS_Y ? lSpin : 0.0F) + lJitter;
        float lAngleX = basePitch + (layer.spinAxis() == HologramSpec.AXIS_X ? lSpin : 0.0F) + lJitter * 0.7F;
        float lAngleZ = baseRoll + (layer.spinAxis() == HologramSpec.AXIS_Z ? lSpin : 0.0F) - lJitter * 0.5F;
        float lWobbleY = HologramAnim.wobbleScaleY(effects, seconds);
        float lWobbleXZ = HologramAnim.wobbleScaleXZ(effects, seconds);
        HologramAnim.Motion lm = HologramAnim.motion(effects, seconds);
        lAngleY += lm.ry();
        lAngleX += lm.rx();
        lAngleZ += lm.rz();
        if (devTraceId != null && layerIndex == 0 && devTraceId.equals(spec.id())) {
            DEV_TRACE[0] = baseYaw;
            DEV_TRACE[1] = lm.ry();
            DEV_TRACE[2] = lAngleY;
            DEV_TRACE[3] = (layer.spinAxis() == HologramSpec.AXIS_Y ? lSpin : 0.0F) + lJitter;
        }
        float offX = baseX + lGlitchX + lm.dx();
        float offY = baseY + lHover + lJump + lm.dy();
        float offZ = baseZ + lGlitchZ + lm.dz();

        float sx = baseScale * lWobbleXZ * lm.scale();
        float sy = baseScale * lWobbleY * lm.scale();
        float sz = baseScale * lWobbleXZ * lm.scale();
        if (sy <= 0.01F)
            return;
        parentBuffer = TintedBuffer.tint(parentBuffer, lTint);
        int totalTint = TintedBuffer.multiply(inherited, lTint);

        // GRADIENT ramps over the layer's own height: measured through the current pose, so the parent's
        // transform and scale are included. Entities are about a body tall, blocks one block, structures their H.
        float contentH = layer.kind() == HologramLayer.Kind.ENTITY ? 1.8F
                : (layer.kind() == HologramLayer.Kind.STRUCTURE ? HologramStructure.dimensions(layer.data())[1] : 1.0F);
        var mat = pose.last().pose();
        float bottomY = mat.m01() * offX + mat.m11() * offY + mat.m21() * offZ + mat.m31();
        float topY = bottomY + (mat.m11()) * sy * contentH;
        MultiBufferSource out = TintedBuffer.wrap(parentBuffer, effects, seconds, bottomY, topY);
        int light = layer.fullbright() ? 0xF000F0 : parentLight;

        switch (layer.kind()) {
            case BLOCK -> {
                BlockState state = HologramStates.resolve(layer.id(), layer.props());
                if (state == null)
                    return;

                boolean hasModel = state.getRenderShape() == RenderShape.MODEL;
                FluidState fluid = state.getFluidState();
                boolean hasFluid = !fluid.isEmpty();
                BlockEntity be = HologramBlockEntities.getForLayer(spec.id(), layerIndex, spec.pos(), state, layer.data());
                var ber = be == null ? null : HologramBlockEntities.renderer(be);
                if (!hasModel && !hasFluid && ber == null)
                    return;

                pose.pushPose();
                pose.translate(offX, offY, offZ);
                pose.mulPose(Axis.YP.rotationDegrees(lAngleY));
                pose.mulPose(Axis.XP.rotationDegrees(lAngleX));
                pose.mulPose(Axis.ZP.rotationDegrees(lAngleZ));
                pose.scale(sx, sy, sz);
                pose.translate(-0.5, 0.0, -0.5);
                if (hasModel) {
                    ModelData md = be != null ? be.getModelData() : ModelData.EMPTY;
                    Minecraft.getInstance().getBlockRenderer()
                            .renderSingleBlock(state, pose, out, light, OverlayTexture.NO_OVERLAY, md, null);
                }
                if (hasFluid)
                    HologramStructureMesh.renderSingleFluid(state, fluid, pose, out, light, spec.pos());
                if (ber != null) {
                    renderBlockEntity(pose, out, be, ber, partialTick, light);
                    drawnBlockEntities++;
                }
                pose.popPose();
            }
            case STRUCTURE -> {
                HologramStructureMesh.CompiledStructure compiled = HologramStructureMesh.get(
                        spec.id(), layerIndex, layer.data(), light, spec.pos());
                if (compiled == null)
                    return;

                boolean useUncached = forceUncachedStructure
                        || TintedBuffer.hasPerVertexFx(effects)
                        || TintedBuffer.hasPerVertexFx(gEffects);

                pose.pushPose();
                pose.translate(offX, offY, offZ);
                pose.mulPose(Axis.YP.rotationDegrees(lAngleY));
                pose.mulPose(Axis.XP.rotationDegrees(lAngleX));
                pose.mulPose(Axis.ZP.rotationDegrees(lAngleZ));
                pose.scale(sx, sy, sz);

                double halfW = compiled.structure.sizeX() * 0.5;
                double halfD = compiled.structure.sizeZ() * 0.5;
                if (useUncached) {
                    var blockRenderer = Minecraft.getInstance().getBlockRenderer();
                    HologramStructure struct = compiled.structure;
                    BlockState[] pal = compiled.resolvedPalette;
                    int count = struct.blockCount();
                    BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
                    for (int i = 0; i < count; i++) {
                        int posIdx = struct.positionAt(i);
                        int palIdx = struct.paletteIndexAt(i);
                        if (palIdx < 0 || palIdx >= pal.length)
                            continue;
                        BlockState st = pal[palIdx];
                        if (st.isAir())
                            continue;

                        FluidState fluid = st.getFluidState();
                        boolean hasFluid = !fluid.isEmpty();
                        boolean hasModel = st.getRenderShape() == RenderShape.MODEL;
                        if (!hasFluid && !hasModel)
                            continue;

                        int bx = struct.unpackX(posIdx);
                        int by = struct.unpackY(posIdx);
                        int bz = struct.unpackZ(posIdx);
                        pose.pushPose();
                        pose.translate(bx - halfW, by, bz - halfD);
                        if (hasModel) {
                            blockRenderer.renderSingleBlock(st, pose, out, light, OverlayTexture.NO_OVERLAY,
                                    ModelData.EMPTY, null);
                        }
                        if (hasFluid) {
                            localPos.set(bx, by, bz);
                            HologramStructureMesh.renderFluidImmediate(blockRenderer, compiled.blockGetter, localPos,
                                    st, fluid, pose, out);
                        }
                        pose.popPose();
                    }
                } else {
                    float[] rgb = { (totalTint >> 16 & 0xFF) / 255.0F, (totalTint >> 8 & 0xFF) / 255.0F,
                            (totalTint & 0xFF) / 255.0F };
                    if (!gEffects.isEmpty())
                        TintedBuffer.applyUniformColor(gEffects, seconds, rgb);
                    if (!effects.isEmpty())
                        TintedBuffer.applyUniformColor(effects, seconds, rgb);
                    compiled.draw(pose, lastView, lastProjection, rgb[0], rgb[1], rgb[2]);
                }

                // Draw any block entities inside the structure through the existing BE renderer path.
                List<HologramStructureMesh.BeSlot> bes = compiled.blockEntities;
                for (int i = 0; i < bes.size(); i++) {
                    HologramStructureMesh.BeSlot slot = bes.get(i);
                    BlockEntity be = HologramBlockEntities.getForStructureBlock(
                            spec.id(), layerIndex, slot.posIdx(),
                            spec.pos().offset(slot.x(), slot.y(), slot.z()), slot.state(), slot.data());
                    var ber = be == null ? null : HologramBlockEntities.renderer(be);
                    if (ber == null)
                        continue;
                    pose.pushPose();
                    pose.translate(slot.x() - halfW, slot.y(), slot.z() - halfD);
                    renderBlockEntity(pose, out, be, ber, partialTick, light);
                    drawnBlockEntities++;
                    pose.popPose();
                }
                pose.popPose();
            }
            case ENTITY -> {
                var entity = HologramEntities.get(spec.id(), layer, 0.0, 0.0, 0.0, 0.0F, (float) seconds);
                if (entity == null)
                    return;
                HologramEntities.applyAnimations(entity, effects, gEffects, seconds);
                pose.pushPose();
                pose.translate(offX, offY, offZ);
                pose.mulPose(Axis.YP.rotationDegrees(lAngleY));
                pose.mulPose(Axis.XP.rotationDegrees(lAngleX));
                pose.mulPose(Axis.ZP.rotationDegrees(lAngleZ));
                pose.scale(sx, sy, sz);
                // The entity's age is driven by the hologram clock, so its partial tick must come from the same
                // clock. Mixing in the game timer's partial tick makes the age jump backwards a few times a
                // second, which is exactly what stuttering idle animations look like.
                float entityTicks = (float) seconds * 20.0F;
                float entityPartial = entityTicks - (int) entityTicks;
                lastEntityTicks = entityTicks;
                HologramEntities.render(pose, out, entity, entityPartial, light, 0.0, 0.0, 0.0, 0.0F);
                pose.popPose();
            }
            case PARTICLE -> {
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void renderBlockEntity(PoseStack pose, MultiBufferSource buffer, BlockEntity entity,
                                          net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                                          float partialTick, int light) {
        try {
            renderer.render(entity, partialTick, pose, buffer, light, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
        }
    }

    private static void drawOutline(PoseStack pose, MultiBufferSource.BufferSource buffer, HologramSpec spec,
                                    Vec3 camera, boolean deleting) {
        AABB box = spec.box().move(-camera.x, -camera.y, -camera.z).inflate(0.02);
        float red = deleting ? 1.0F : 0.35F;
        float green = deleting ? 0.3F : 0.9F;
        float blue = deleting ? 0.3F : 1.0F;
        LevelRenderer.renderLineBox(pose, buffer.getBuffer(RenderType.lines()), box, red, green, blue, 0.85F);
        buffer.endBatch(RenderType.lines());
    }

    private HologramRenderer() {
    }
}
