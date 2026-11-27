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

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Captures each rendered player's live animated {@code body} and {@code head} transforms (after Emotecraft,
 * playerAnimator, PAL, BetterCombat, sneaking, swimming, etc. have run) so player-attached holograms move,
 * tilt, spin, and flip in lockstep with emotes instead of staying stiff.
 */
public final class HologramPlayerPose {
    private static final long MAX_AGE_NANOS = 100_000_000L; // 100 ms staleness guard

    private record Snapshot(Matrix4f body, Matrix4f head, long nanos) {
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
    public static void capture(AbstractClientPlayer player, PlayerModel<?> model, PoseStack poseStack) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null)
            return;
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        float invScale = 1.0F / Math.max(0.05F, 0.9375F * player.getScale());
        Matrix4f base = new Matrix4f()
                .translate((float) cam.x, (float) cam.y, (float) cam.z)
                .mul(poseStack.last().pose());

        Matrix4f body = partToWorld(base, model.body, invScale);
        Matrix4f head = partToWorld(base, model.head, invScale);
        POSES.put(player.getUUID(), new Snapshot(body, head, System.nanoTime()));
    }

    private static Matrix4f partToWorld(Matrix4f base, ModelPart part, float invScale) {
        Matrix4f m = new Matrix4f(base);
        m.translate(part.x / 16.0F, part.y / 16.0F, part.z / 16.0F);
        if (part.zRot != 0.0F)
            m.rotateZ(part.zRot);
        if (part.yRot != 0.0F)
            m.rotateY(part.yRot);
        if (part.xRot != 0.0F)
            m.rotateX(part.xRot);
        // Undo LivingEntityRenderer's translate(0, -1.501, 0), scale(-1, -1, 1), scale(0.9375), and rotateY(180).
        m.translate(0.0F, 1.501F, 0.0F);
        m.scale(-invScale, -invScale, invScale);
        m.rotateY((float) Math.PI);
        return m;
    }

    @Nullable
    private static Matrix4f lookup(Player player, HologramAttach.Follow follow) {
        if (follow == HologramAttach.Follow.POSITION)
            return null;
        Snapshot snap = POSES.get(player.getUUID());
        if (snap == null || System.nanoTime() - snap.nanos > MAX_AGE_NANOS)
            return null;
        return new Matrix4f(follow == HologramAttach.Follow.HEAD_LOOK ? snap.head : snap.body);
    }
}
