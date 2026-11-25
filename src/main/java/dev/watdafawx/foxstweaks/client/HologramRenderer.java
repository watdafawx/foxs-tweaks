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
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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

    private static int lastDrawn;
    private static int drawnBlockEntities;
    private static long lastPassNanos;
    private static long lastAnimNanos;
    private static double animSeconds;
    private static final Matrix4f lastView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static Vec3 lastCamera = Vec3.ZERO;

    private static boolean paused;

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

        pose.pushPose();
        pose.translate(origin.x + 0.5 - camera.x + reveal.glitchX(),
                origin.y - camera.y + reveal.dy(),
                origin.z + 0.5 - camera.z);
        pose.mulPose(Axis.YP.rotationDegrees(spec.yaw() + reveal.spinY()));
        pose.mulPose(Axis.XP.rotationDegrees(spec.pitch()));
        pose.mulPose(Axis.ZP.rotationDegrees(spec.roll()));
        pose.scale(spec.scale() * reveal.sx(), spec.scale() * reveal.sy(), spec.scale() * reveal.sz());

        List<HologramLayer> layers = spec.layers();

        // 1. Draw ungrouped (independent) layers directly in root frame
        for (int i = 0; i < layers.size(); i++) {
            HologramLayer layer = layers.get(i);
            if (!layer.groupId().isEmpty())
                continue;
            drawLayer(pose, buffer, spec, null, layer, i, worldLight, seconds, partialTick);
        }

        // 2. Draw each group once, pushing its shared transform/animation/FX frame around its member layers
        List<HologramGroup> groups = spec.groups();
        for (int g = 0; g < groups.size(); g++) {
            HologramGroup group = groups.get(g);
            if (!group.visible())
                continue;
            List<HologramEffect> gEffects = HologramTriggers.resolve(group.effects(), spec, seconds);
            if (HologramAnim.flickerHidden(gEffects, seconds))
                continue;

            float gHover = hoverOffset(group.hover(), group.hoverPeriod(), seconds);
            float gJump = HologramAnim.jumpOffset(gEffects, seconds);
            float gGlitchX = HologramAnim.glitchOffsetX(gEffects, seconds);
            float gGlitchZ = HologramAnim.glitchOffsetZ(gEffects, seconds);
            float gJitter = HologramAnim.tremorDegrees(gEffects, seconds);
            float gSpin = spinAngle(group.spin(), seconds);
            float gAngleY = group.yaw() + (group.spinAxis() == HologramSpec.AXIS_Y ? gSpin : 0.0F) + gJitter;
            float gAngleX = group.pitch() + (group.spinAxis() == HologramSpec.AXIS_X ? gSpin : 0.0F) + gJitter * 0.7F;
            float gAngleZ = group.roll() + (group.spinAxis() == HologramSpec.AXIS_Z ? gSpin : 0.0F) - gJitter * 0.5F;
            float gWobbleY = HologramAnim.wobbleScaleY(gEffects, seconds);
            float gWobbleXZ = HologramAnim.wobbleScaleXZ(gEffects, seconds);
            HologramAnim.Motion gm = HologramAnim.motion(gEffects, seconds);

            pose.pushPose();
            pose.translate(group.offsetX() + gGlitchX + gm.dx(), group.offsetY() + gHover + gJump + gm.dy(),
                    group.offsetZ() + gGlitchZ + gm.dz());
            pose.mulPose(Axis.YP.rotationDegrees(gAngleY + gm.ry()));
            pose.mulPose(Axis.XP.rotationDegrees(gAngleX + gm.rx()));
            pose.mulPose(Axis.ZP.rotationDegrees(gAngleZ + gm.rz()));
            pose.scale(group.scale() * gWobbleXZ * gm.scale(), group.scale() * gWobbleY * gm.scale(),
                    group.scale() * gWobbleXZ * gm.scale());

            MultiBufferSource groupOut = TintedBuffer.wrap(buffer, gEffects, seconds);
            int groupLight = group.fullbright() ? 0xF000F0 : worldLight;

            for (int i = 0; i < layers.size(); i++) {
                HologramLayer layer = layers.get(i);
                if (!group.id().equals(layer.groupId()))
                    continue;
                drawLayer(pose, groupOut, spec, group, layer, i, groupLight, seconds, partialTick);
            }
            pose.popPose();
        }

        // 3. Fallback: any layer whose groupId references a deleted/missing group still renders in root frame
        for (int i = 0; i < layers.size(); i++) {
            HologramLayer layer = layers.get(i);
            if (!layer.groupId().isEmpty() && spec.groupById(layer.groupId()) == null)
                drawLayer(pose, buffer, spec, null, layer, i, worldLight, seconds, partialTick);
        }

        pose.popPose();
    }

    private static void drawLayer(PoseStack pose, MultiBufferSource parentBuffer, HologramSpec spec,
                                  @Nullable HologramGroup group, HologramLayer layer, int layerIndex,
                                  int parentLight, double seconds, float partialTick) {
        if (!layer.visible() || layer.kind() == HologramLayer.Kind.PARTICLE)
            return;
        if (!HologramClient.passesSolo(spec.id(), layerIndex, layer))
            return;

        List<HologramEffect> effects = HologramTriggers.resolve(layer.effects(), spec, seconds);
        if (HologramAnim.flickerHidden(effects, seconds))
            return;

        float lHover = hoverOffset(layer.hover(), layer.hoverPeriod(), seconds);
        float lJump = HologramAnim.jumpOffset(effects, seconds);
        float lGlitchX = HologramAnim.glitchOffsetX(effects, seconds);
        float lGlitchZ = HologramAnim.glitchOffsetZ(effects, seconds);
        float lJitter = HologramAnim.tremorDegrees(effects, seconds);
        float lSpin = spinAngle(layer.spin(), seconds);
        float lAngleY = layer.yaw() + (layer.spinAxis() == HologramSpec.AXIS_Y ? lSpin : 0.0F) + lJitter;
        float lAngleX = layer.pitch() + (layer.spinAxis() == HologramSpec.AXIS_X ? lSpin : 0.0F) + lJitter * 0.7F;
        float lAngleZ = layer.roll() + (layer.spinAxis() == HologramSpec.AXIS_Z ? lSpin : 0.0F) - lJitter * 0.5F;
        float lWobbleY = HologramAnim.wobbleScaleY(effects, seconds);
        float lWobbleXZ = HologramAnim.wobbleScaleXZ(effects, seconds);
        HologramAnim.Motion lm = HologramAnim.motion(effects, seconds);
        lAngleY += lm.ry();
        lAngleX += lm.rx();
        lAngleZ += lm.rz();
        float offX = layer.offsetX() + lGlitchX + lm.dx();
        float offY = layer.offsetY() + lHover + lJump + lm.dy();
        float offZ = layer.offsetZ() + lGlitchZ + lm.dz();

        float sx = layer.scale() * lWobbleXZ * lm.scale();
        float sy = layer.scale() * lWobbleY * lm.scale();
        float sz = layer.scale() * lWobbleXZ * lm.scale();
        if (sy <= 0.01F)
            return;

        // GRADIENT ramps over the layer's own height: measured through the current pose, so the parent's
        // transform and scale are included. Entities are about a body tall, blocks one block.
        float contentH = layer.kind() == HologramLayer.Kind.ENTITY ? 1.8F : 1.0F;
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
                BlockEntity be = HologramBlockEntities.getForLayer(spec.id(), layerIndex, spec.pos(), state, layer.data());
                var ber = be == null ? null : HologramBlockEntities.renderer(be);
                if (!hasModel && ber == null)
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
                if (ber != null) {
                    renderBlockEntity(pose, out, be, ber, partialTick, light);
                    drawnBlockEntities++;
                }
                pose.popPose();
            }
            case ENTITY -> {
                var entity = HologramEntities.get(layer, 0.0, 0.0, 0.0, 0.0F, (float) seconds);
                if (entity == null)
                    return;
                HologramEntities.applyAnimations(entity, effects,
                        group == null ? List.of() : HologramTriggers.resolve(group.effects(), spec, seconds), seconds);
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
