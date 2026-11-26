package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

/**
 * Detached client-side studio camera active while {@link HologramScreen} is open.
 *
 * <p>Lets the player fly freely around the hologram (WASD + Space/Shift/Q/E, scroll to dolly,
 * right-drag to look, middle-drag to pan) without moving their actual body or breaking server
 * reach/survival limits.
 */
public final class HologramCamera {
    private static final double MAX_RADIUS = 32.0;
    private static final double FLY_SPEED = 7.0; // blocks per second

    private static boolean active;
    private static Vec3 anchor = Vec3.ZERO;
    private static Vec3 pos = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    private static long lastFrameNanos;
    private static boolean movementBlocked;

    private HologramCamera() {
    }

    public static void begin(Vec3 hologramCenter, float size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        lastFrameNanos = System.nanoTime();
        active = true;
        movementBlocked = false;
        frame(hologramCenter, size);
    }

    /**
     * Puts the camera a comfortable distance from the hologram, on the player's side of it, a little above and
     * looking at it. Starting inside the player's head means the first thing every edit session needs is a
     * walk backwards.
     */
    public static void frame(Vec3 center, float size) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        anchor = center;

        double dist = Mth.clamp(2.6 + 2.0 * Math.max(1.0F, size), 3.5, 14.0);
        Vec3 away = mc.player.getEyePosition().subtract(center);
        away = new Vec3(away.x, 0.0, away.z);
        if (away.lengthSqr() < 0.25) {
            Vec3 look = mc.player.getViewVector(1.0F);
            away = new Vec3(-look.x, 0.0, -look.z);
        }
        if (away.lengthSqr() < 1.0E-4)
            away = new Vec3(0.0, 0.0, 1.0);
        away = away.normalize();

        pos = center.add(away.scale(dist)).add(0.0, dist * 0.28, 0.0);
        Vec3 d = center.subtract(pos).normalize();
        pitch = (float) Math.toDegrees(-Math.asin(d.y));
        yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
    }

    public static void resetToPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null)
            return;
        pos = mc.player.getEyePosition();
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
    }

    public static void end() {
        active = false;
        lastFrameNanos = 0L;
    }

    public static void setAnchor(Vec3 hologramCenter) {
        anchor = hologramCenter;
    }

    public static void setMovementBlocked(boolean blocked) {
        movementBlocked = blocked;
    }

    public static boolean isActive() {
        if (!active)
            return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.screen instanceof HologramScreen || mc.screen instanceof HologramChatScreen
                || mc.screen instanceof HologramPickerScreen || mc.screen instanceof HologramCreatorScreen
                || mc.screen instanceof HologramAudioScreen || mc.screen instanceof HologramAudioSettingsScreen)) {
            active = false;
            return false;
        }
        return !HologramClient.previewAnchored();
    }

    public static Vec3 pos() {
        return pos;
    }

    public static float yaw() {
        return yaw;
    }

    public static float pitch() {
        return pitch;
    }

    public static Vec3 forward() {
        float yawRad = (float) Math.toRadians(yaw);
        float pitchRad = (float) Math.toRadians(pitch);
        double cosPitch = Math.cos(pitchRad);
        return new Vec3(
                -Math.sin(yawRad) * cosPitch,
                -Math.sin(pitchRad),
                Math.cos(yawRad) * cosPitch).normalize();
    }

    public static Vec3 right() {
        float yawRad = (float) Math.toRadians(yaw);
        return new Vec3(-Math.cos(yawRad), 0.0, -Math.sin(yawRad)).normalize();
    }

    public static Vec3 up() {
        return right().cross(forward()).normalize();
    }

    public static void turn(double deltaX, double deltaY) {
        yaw = Mth.wrapDegrees(yaw + (float) (deltaX * 0.45));
        pitch = Mth.clamp(pitch + (float) (deltaY * 0.45), -89.0F, 89.0F);
    }

    public static void dolly(double amount) {
        moveBy(forward().scale(amount));
    }

    public static void pan(double deltaX, double deltaY) {
        double scale = 0.02;
        Vec3 delta = right().scale(-deltaX * scale).add(up().scale(deltaY * scale));
        moveBy(delta);
    }

    /** Called every frame from {@code Camera#setup} to integrate held WASD / Space / Shift smoothly. */
    public static void tickFrame() {
        long now = System.nanoTime();
        double dt = lastFrameNanos == 0L ? 0.0 : Math.min(0.1, (now - lastFrameNanos) * 1.0E-9);
        lastFrameNanos = now;
        if (dt <= 0.0 || movementBlocked)
            return;

        Minecraft mc = Minecraft.getInstance();
        Window window = mc.getWindow();
        // Only the two editors fly the camera: typing in the chat that opens over them must not, nor the picker
        // or audio screens. The Creator is the default editor, so leaving it out took WASD away from most users.
        if (window == null || !(mc.screen instanceof HologramScreen || mc.screen instanceof HologramCreatorScreen))
            return;
        long handle = window.getWindow();

        double fwd = 0.0;
        double strafe = 0.0;
        double vert = 0.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS)
            fwd += 1.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS)
            fwd -= 1.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS)
            strafe += 1.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS)
            strafe -= 1.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_SPACE) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_E) == GLFW.GLFW_PRESS)
            vert += 1.0;
        if (GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_SHIFT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_Q) == GLFW.GLFW_PRESS)
            vert -= 1.0;

        if (fwd == 0.0 && strafe == 0.0 && vert == 0.0)
            return;

        Vec3 dir = forward().scale(fwd).add(right().scale(strafe)).add(0.0, vert, 0.0);
        if (dir.lengthSqr() > 1.0E-6) {
            double speed = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                    ? FLY_SPEED * 2.0 : FLY_SPEED;
            moveBy(dir.normalize().scale(speed * dt));
        }
    }

    private static void moveBy(Vec3 delta) {
        Vec3 next = pos.add(delta);
        Vec3 offset = next.subtract(anchor);
        if (offset.lengthSqr() > MAX_RADIUS * MAX_RADIUS)
            next = anchor.add(offset.normalize().scale(MAX_RADIUS));
        pos = next;
    }
}
