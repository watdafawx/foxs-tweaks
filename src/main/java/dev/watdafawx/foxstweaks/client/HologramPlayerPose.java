package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.hologram.HologramAttach;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Captures each rendered player's live animated bone transforms ({@code body}, {@code head},
 * {@code rightArm}, {@code leftArm}, {@code rightLeg}, {@code leftLeg}) after Emotecraft,
 * playerAnimator, PAL, BetterCombat, sneaking, swimming, etc. have run, so player-attached holograms
 * move, tilt, spin, flip, and scale in lockstep with animations.
 *
 * <p>Filters out non-world renders (InventoryScreen, Curios/Accessories, Paper-Doll HUDs, Iris shadow pass)
 * by verifying that the poseStack scale and world-space origin match the player in the main camera pass.
 */
public final class HologramPlayerPose {
    private static final long MAX_AGE_NANOS = 100_000_000L; // 100 ms staleness guard
    private static final float MAX_DIST_SQ = 36.0F; // 6 blocks tolerance for emote flips/contraptions

    private record Snapshot(
            Matrix4f base,
            ModelPartSnapshot body,
            ModelPartSnapshot head,
            ModelPartSnapshot rightArm,
            ModelPartSnapshot leftArm,
            ModelPartSnapshot rightLeg,
            ModelPartSnapshot leftLeg,
            float playerScale,
            long nanos
    ) {
    }

    private record ModelPartSnapshot(
            float x, float y, float z,
            float xRot, float yRot, float zRot,
            float xScale, float yScale, float zScale
    ) {
        static ModelPartSnapshot of(ModelPart p) {
            return new ModelPartSnapshot(p.x, p.y, p.z, p.xRot, p.yRot, p.zRot, p.xScale, p.yScale, p.zScale);
        }
    }

    private static final Map<UUID, Snapshot> POSES = new HashMap<>();

    private HologramPlayerPose() {
    }

    public static void install() {
        HologramAttach.livePoseLookup = HologramPlayerPose::lookup;
    }

    public static void clear() {
        POSES.clear();
    }

    /**
     * Called right after {@code PlayerModel#renderToBuffer} while the entity's {@code PoseStack} (with
     * {@code setupRotations}, playerAnimator/Emotecraft root body motion, and model-space flip) is still active.
     */
    public static void capture(AbstractClientPlayer player, PlayerModel<?> model, PoseStack poseStack, float partialTicks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.level == null)
            return;
        Matrix4f pose = poseStack.last().pose();

        // 1. Scale guard: reject GUI / inventory / paper-doll renders (scale ~20-80 instead of ~0.9375 * playerScale).
        float pScale = Math.max(0.05F, player.getScale());
        float expectedScale = 0.9375F * pScale;
        float actualScale = new Vector3f(pose.m00(), pose.m01(), pose.m02()).length();
        if (actualScale < expectedScale * 0.45F || actualScale > expectedScale * 2.2F)
            return;

        // 2. World-position guard: reject Iris/Oculus shadow pass and off-camera mirrors/HUDs.
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Matrix4f base = new Matrix4f()
                .translate((float) cam.x, (float) cam.y, (float) cam.z)
                .mul(pose);
        // Model origin (0, 1.501, 0) in model space maps to the player's feet in world space.
        Vector3f feet = base.transformPosition(new Vector3f(0.0F, 1.501F, 0.0F));
        Vec3 expectedPos = player.getPosition(partialTicks);
        double dx = feet.x - expectedPos.x;
        double dy = feet.y - expectedPos.y;
        double dz = feet.z - expectedPos.z;
        if (dx * dx + dy * dy + dz * dz > MAX_DIST_SQ)
            return;

        POSES.put(player.getUUID(), new Snapshot(
                base,
                ModelPartSnapshot.of(model.body),
                ModelPartSnapshot.of(model.head),
                ModelPartSnapshot.of(model.rightArm),
                ModelPartSnapshot.of(model.leftArm),
                ModelPartSnapshot.of(model.rightLeg),
                ModelPartSnapshot.of(model.leftLeg),
                pScale,
                System.nanoTime()
        ));
    }

    private static Matrix4f partToWorld(Matrix4f base, ModelPartSnapshot part, float restX, float restY, float restZ,
                                        float playerScale, boolean scaleWithPlayer) {
        // Undo LivingEntityRenderer's 0.9375 scale (and playerScale only when scaleWithPlayer is off).
        float invScale = scaleWithPlayer
                ? (1.0F / 0.9375F)
                : (1.0F / Math.max(0.05F, 0.9375F * playerScale));

        Matrix4f m = new Matrix4f(base);
        m.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.zRot != 0.0F)
            m.rotateZ(part.zRot);
        if (part.yRot != 0.0F)
            m.rotateY(part.yRot);
        if (part.xRot != 0.0F)
            m.rotateX(part.xRot);
        if (part.xScale != 1.0F || part.yScale != 1.0F || part.zScale != 1.0F)
            m.scale(part.xScale, part.yScale, part.zScale);
        // Undo LivingEntityRenderer's translate(0, -1.501, 0), scale(-1, -1, 1), 0.9375, and rotateY(180).
        // Subtract only the bone's resting pivot so live Emotecraft/crouch translation is preserved.
        m.translate(-restX / 16.0F, 1.501F - restY / 16.0F, -restZ / 16.0F);
        m.scale(-invScale, -invScale, invScale);
        m.rotateY((float) Math.PI);
        return m;
    }

    @Nullable
    private static Matrix4f lookup(Player player, HologramAttach.Follow follow, boolean scaleWithPlayer) {
        if (follow == HologramAttach.Follow.POSITION)
            return null;
        Snapshot snap = POSES.get(player.getUUID());
        if (snap == null || System.nanoTime() - snap.nanos > MAX_AGE_NANOS)
            return null;
        return switch (follow) {
            case HEAD_LOOK -> partToWorld(snap.base, snap.head, 0.0F, 0.0F, 0.0F, snap.playerScale, scaleWithPlayer);
            case RIGHT_HAND -> partToWorld(snap.base, snap.rightArm, -5.0F, 2.0F, 0.0F, snap.playerScale, scaleWithPlayer);
            case LEFT_HAND -> partToWorld(snap.base, snap.leftArm, 5.0F, 2.0F, 0.0F, snap.playerScale, scaleWithPlayer);
            case RIGHT_LEG -> partToWorld(snap.base, snap.rightLeg, -1.9F, 12.0F, 0.0F, snap.playerScale, scaleWithPlayer);
            case LEFT_LEG -> partToWorld(snap.base, snap.leftLeg, 1.9F, 12.0F, 0.0F, snap.playerScale, scaleWithPlayer);
            default -> partToWorld(snap.base, snap.body, 0.0F, 0.0F, 0.0F, snap.playerScale, scaleWithPlayer);
        };
    }
}
