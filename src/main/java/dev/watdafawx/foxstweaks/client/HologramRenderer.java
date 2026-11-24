package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;

import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Draws holograms: one {@code renderSingleBlock} per hologram, plus the selection outline.
 *
 * <p>Nothing here is a block entity or an entity, so the whole feature is this pass plus
 * {@link HologramClient}'s list. That is also why an idle hologram costs nothing - the pass only
 * runs for what is on screen, and a hologram with no animation is a single cached model draw.
 *
 * <p>Stage is after translucent blocks so a glass hologram blends against what is behind it rather
 * than being swallowed by it. Do not move this to {@code AFTER_LEVEL}: shader packs replace the
 * level pass and the holograms then light up under Iris' tonemap.
 */
public final class HologramRenderer {

    private static int lastDrawn;
    private static int drawnBlockEntities;
    private static long lastPassNanos;
    private static final Matrix4f lastView = new Matrix4f();
    private static final Matrix4f lastProjection = new Matrix4f();
    private static Vec3 lastCamera = Vec3.ZERO;

    public static Matrix4f lastView() {
        return lastView;
    }

    public static Matrix4f lastProjection() {
        return lastProjection;
    }

    public static Vec3 lastCamera() {
        return lastCamera;
    }

    /** How many holograms the last frame actually drew; the dev check's benchmark reports this. */
    public static int lastDrawnCount() {
        return lastDrawn;
    }

    /** CPU nanoseconds the hologram pass took last frame. The benchmark's real number. */
    /** How many block-entity bases were drawn last frame. */
    public static int lastDrawnBlockEntityCount() {
        return drawnBlockEntities;
    }

    public static long lastPassNanos() {
        return lastPassNanos;
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
        float seconds = (mc.level.getGameTime() + partial) / 20.0F;

        int globalCap = Config.hologramRenderCap();
        int drawn = 0;
        drawnBlockEntities = 0;
        for (int i = 0; i < specs.size(); i++) {
            HologramSpec spec = specs.get(i);
            if (!spec.enabled())
                continue;

            double cap = Math.min(spec.renderDistance(), globalCap);
            double centerX = spec.pos().getX() + spec.offsetX() + 0.5;
            double centerY = spec.pos().getY() + spec.offsetY() + 0.5 * spec.scale();
            double centerZ = spec.pos().getZ() + spec.offsetZ() + 0.5;
            if (camera.distanceToSqr(centerX, centerY, centerZ) > cap * cap)
                continue;

            // Frustum cull before any pose work. Distance alone still drew everything behind you, and
            // this is the cheap win that batching identical models cannot give: an off-screen hologram
            // costs nothing at all.
            if (event.getFrustum() != null && !event.getFrustum().isVisible(spec.box()))
                continue;

            draw(pose, buffer, spec, seconds, camera, null, partial);
            if (HologramClient.appearProgress(spec.id()) > 0.01F)
                drawn++;
        }
        lastDrawn = drawn;

        if (hovered != null)
            drawOutline(pose, buffer, hovered, camera,
                    mc.player != null && mc.player.isShiftKeyDown());

        HologramSpec preview = HologramClient.previewSpec();
        if (preview != null && HologramClient.previewAnchored())
            drawAnchoredPreview(pose, buffer, preview, seconds, camera, mc);

        buffer.endBatch();
    }

    /**
     * Draws the edited hologram a few blocks in front of the camera at full brightness. This is the
     * editor's "look at it properly" view: the world copy may be behind you, on the other side of a
     * wall, or too small at its own scale.
     */
    private static void drawAnchoredPreview(PoseStack pose, MultiBufferSource buffer, HologramSpec spec,
                                            float seconds, Vec3 camera, Minecraft mc) {
        if (mc.player == null)
            return;

        Vec3 look = mc.player.getViewVector(1.0F);
        Vec3 right = new Vec3(-look.z, 0.0, look.x);
        if (right.lengthSqr() < 1.0E-4)
            right = new Vec3(1.0, 0.0, 0.0);
        right = right.normalize();

        // The preview sits in the free band between the two panels. Distance grows with the
        // hologram's own size so it keeps a similar apparent size instead of filling the camera at 8x.
        double distance = 3.0 + 2.2 * spec.scale();
        double drop = 0.35 + 0.15 * spec.scale();

        Vec3 anchor = mc.player.getEyePosition()
                .add(look.scale(distance))
                .add(right.scale(0.15))
                .add(0.0, -drop, 0.0);

        drawAt(pose, buffer, spec, seconds, camera, anchor, 0xF000F0, null, 1.0F, 0.0F);
    }

    /**
     * Draws one hologram, camera-relative. {@code override} lets a caller force the state it wants
     * (the editor preview), otherwise the saved block and property values are resolved.
     */
    public static void draw(PoseStack pose, MultiBufferSource buffer, HologramSpec spec, float seconds,
                            Vec3 camera, @Nullable BlockState override, float partialTick) {
        Vec3 origin = Vec3.atLowerCornerOf(spec.pos()).add(spec.offsetX(), spec.offsetY(), spec.offsetZ());
        int light = spec.fullbright()
                ? 0xF000F0
                : LevelRenderer.getLightColor(Minecraft.getInstance().level,
                        net.minecraft.core.BlockPos.containing(origin.x + 0.5, origin.y + 0.5, origin.z + 0.5));
        drawAt(pose, buffer, spec, seconds, camera, origin, light, override,
                HologramClient.appearProgress(spec.id()), partialTick);
    }

    private static void drawAt(PoseStack pose, MultiBufferSource buffer, HologramSpec spec, float seconds,
                               Vec3 camera, Vec3 origin, int light, @Nullable BlockState override, float appear,
                               float partialTick) {
        if (appear <= 0.01F)
            return;

        List<HologramEffect> effects = spec.effects();
        if (HologramAnim.flickerHidden(effects, seconds))
            return;

        BlockState state = override != null ? override : Holograms.stateOf(spec);
        boolean hasModel = state != null && state.getRenderShape() == RenderShape.MODEL;

        // Blocks whose look is a block entity renderer (chests, signs, machine LEDs) have no model at
        // all, so they are drawn from a block entity carrying the data captured when the hologram was
        // placed. A block can be both (a beacon: model plus beam), so neither path excludes the other.
        var blockEntity = override == null ? HologramBlockEntities.get(spec) : null;
        var blockEntityRenderer = blockEntity == null ? null : HologramBlockEntities.renderer(blockEntity);

        if (!hasModel && blockEntityRenderer == null && spec.parts().isEmpty())
            return;

        // Sphere of appearance: grow from a point and settle into place. Alpha cannot do this - most
        // block render types do not blend - so the fade is geometry, not transparency.
        float eased = HologramAnim.ease(appear);
        float settle = 0.18F + 0.82F * eased;
        float drop = (1.0F - eased) * 0.5F;

        float jitter = HologramAnim.tremorDegrees(effects, seconds);
        float spinAngle = spec.spin() * seconds;
        float angleY = spec.yaw() + (spec.spinAxis() == HologramSpec.AXIS_Y ? spinAngle : 0.0F) + jitter;
        float angleX = spec.pitch() + (spec.spinAxis() == HologramSpec.AXIS_X ? spinAngle : 0.0F) + jitter * 0.7F;
        float angleZ = spec.roll() + (spec.spinAxis() == HologramSpec.AXIS_Z ? spinAngle : 0.0F) - jitter * 0.5F;

        float hoverOffset = 0.0F;
        if (spec.hover() > 0.0F)
            hoverOffset = Mth.sin(seconds * Mth.TWO_PI / spec.hoverPeriod()) * spec.hover();

        float wobble = HologramAnim.wobbleScale(effects, seconds);

        pose.pushPose();
        // Translate to the cell's centre at its floor FIRST so yaw/pitch/roll/spin rotate in place
        // around the hologram's centre instead of orbiting around the NW block corner.
        pose.translate(origin.x + 0.5 - camera.x,
                origin.y - camera.y + hoverOffset + HologramAnim.jumpOffset(effects, seconds) - drop,
                origin.z + 0.5 - camera.z);
        pose.mulPose(Axis.YP.rotationDegrees(angleY));
        pose.mulPose(Axis.XP.rotationDegrees(angleX));
        pose.mulPose(Axis.ZP.rotationDegrees(angleZ));

        HologramEffect tint = HologramAnim.tint(effects);
        MultiBufferSource out = tint == null ? buffer
                : new TintedBuffer(buffer, tint.color(), tint.strength());

        float scale = spec.scale() * settle * wobble;

        if (hasModel) {
            ModelData modelData = blockEntity != null ? blockEntity.getModelData() : ModelData.EMPTY;
            pose.pushPose();
            pose.scale(scale, scale, scale);
            pose.translate(-0.5, 0.0, -0.5);
            Minecraft.getInstance().getBlockRenderer()
                    .renderSingleBlock(state, pose, out, light, OverlayTexture.NO_OVERLAY, modelData, null);
            pose.popPose();
        }

        if (blockEntityRenderer != null) {
            pose.pushPose();
            pose.scale(scale, scale, scale);
            pose.translate(-0.5, 0.0, -0.5);
            renderBlockEntity(pose, out, blockEntity, blockEntityRenderer, partialTick, light);
            pose.popPose();
            drawnBlockEntities++;
        }

        boolean hasBase = hasModel || blockEntityRenderer != null;
        List<HologramPart> parts = spec.parts();
        for (int i = 0; i < parts.size(); i++)
            drawPart(pose, out, spec, parts.get(i), i, light, seconds,
                    (hasBase ? 1.0F : spec.scale()) * settle * wobble, partialTick);

        pose.popPose();
    }

    /**
     * One content part, in the parent's rotated frame: offsets are blocks relative to the parent's
     * centre, scale is absolute (parts do not inherit the parent's size, or a 4x hologram would make a
     * 4x dragon head nobody asked for).
     */
    private static void drawPart(PoseStack pose, MultiBufferSource buffer, HologramSpec spec, HologramPart part,
                                 int partIndex, int light, float seconds, float settle, float partialTick) {
        float partScale = part.scale() * settle;
        if (partScale <= 0.01F)
            return;

        switch (part.kind()) {
            case BLOCK -> {
                BlockState partState = HologramStates.resolve(part.id(), part.props());
                if (partState == null)
                    return;
                boolean partHasModel = partState.getRenderShape() == RenderShape.MODEL;
                BlockEntity partBe = HologramBlockEntities.getForPart(spec.id(), partIndex, spec.pos(), partState);
                var partBer = partBe == null ? null : HologramBlockEntities.renderer(partBe);
                if (!partHasModel && partBer == null)
                    return;
                pose.pushPose();
                pose.translate(part.offsetX(), part.offsetY(), part.offsetZ());
                pose.mulPose(Axis.YP.rotationDegrees(part.yaw()));
                pose.scale(partScale, partScale, partScale);
                pose.translate(-0.5, 0.0, -0.5);
                if (partHasModel) {
                    ModelData md = partBe != null ? partBe.getModelData() : ModelData.EMPTY;
                    Minecraft.getInstance().getBlockRenderer()
                            .renderSingleBlock(partState, pose, buffer, light, OverlayTexture.NO_OVERLAY, md, null);
                }
                if (partBer != null)
                    renderBlockEntity(pose, buffer, partBe, partBer, partialTick, light);
                pose.popPose();
            }
            case ENTITY -> {
                // Rendered at the pose origin, so the transform above places it. EntityRenderDispatcher
                // translates by the coordinates it is given, so they are zero here.
                var entity = HologramEntities.get(part, 0.0, 0.0, 0.0, part.yaw(), seconds);
                if (entity == null)
                    return;
                pose.pushPose();
                pose.translate(part.offsetX(), part.offsetY(), part.offsetZ());
                pose.scale(partScale, partScale, partScale);
                HologramEntities.render(pose, buffer, entity, partialTick, light, 0.0, 0.0, 0.0, part.yaw());
                pose.popPose();
            }
            case PARTICLE -> {
                // Spawned from HologramClient's tick, not from the render pass: particles are stateful
                // and must not be added per frame.
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void renderBlockEntity(PoseStack pose, MultiBufferSource buffer, BlockEntity entity,
                                          net.minecraft.client.renderer.blockentity.BlockEntityRenderer renderer,
                                          float partialTick, int light) {
        // The dispatcher's own render() translates by (be pos - camera), which would fight the pose we
        // already built; the renderer itself expects a pose at the block's corner, same as a model.
        try {
            renderer.render(entity, partialTick, pose, buffer, light, OverlayTexture.NO_OVERLAY);
        } catch (Throwable ignored) {
            // A modded BER that assumes the block is physically in the level must not crash the frame.
        }
    }

    private static void drawOutline(PoseStack pose, MultiBufferSource.BufferSource buffer, HologramSpec spec,
                                    Vec3 camera, boolean deleting) {        AABB box = spec.box().move(-camera.x, -camera.y, -camera.z).inflate(0.02);
        float red = deleting ? 1.0F : 0.35F;
        float green = deleting ? 0.3F : 0.9F;
        float blue = deleting ? 0.3F : 1.0F;
        LevelRenderer.renderLineBox(pose, buffer.getBuffer(RenderType.lines()), box, red, green, blue, 0.85F);
        buffer.endBatch(RenderType.lines());
    }

    private HologramRenderer() {
    }
}
