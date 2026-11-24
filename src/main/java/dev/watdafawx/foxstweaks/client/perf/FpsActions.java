package dev.watdafawx.foxstweaks.client.perf;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

/**
 * What a player can do with one hotspot: look at it, teleport next to it, copy its coordinates.
 * Shared by the chat buttons and {@link FpsReportScreen}.
 */
public final class FpsActions {
    private static final String[] ARROWS = { "↑", "↗", "→", "↘", "↓", "↙", "←", "↖" };

    private FpsActions() {
    }

    /** Where the thing is right now (a live entity moves), or null when nothing can be resolved. */
    public static Vec3 centerOf(FpsProfiler.Hotspot h) {
        AABB box = FpsProfiler.resolveBox(Minecraft.getInstance(), h);
        return box != null ? box.getCenter() : null;
    }

    public static double distance(FpsProfiler.Hotspot h) {
        LocalPlayer p = Minecraft.getInstance().player;
        Vec3 c = centerOf(h);
        return p != null && c != null ? p.position().distanceTo(c) : -1.0;
    }

    /** An arrow pointing from where you look toward the target (up = straight ahead). Empty when unknown. */
    public static String arrow(FpsProfiler.Hotspot h) {
        LocalPlayer p = Minecraft.getInstance().player;
        Vec3 c = centerOf(h);
        if (p == null || c == null)
            return "";
        double dx = c.x - p.getX();
        double dz = c.z - p.getZ();
        if (dx * dx + dz * dz < 1.0)
            return "●"; // right on top of / next to you
        float targetYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float rel = Mth.wrapDegrees(targetYaw - p.getYRot());
        int idx = Math.floorMod(Math.round(rel / 45.0f), 8);
        return ARROWS[idx];
    }

    /** "12 64 -30" - block coordinates, the form every coordinate field accepts. */
    public static String coords(FpsProfiler.Hotspot h) {
        if (!h.isEntity() && h.blockPos() != null)
            return h.blockPos().getX() + " " + h.blockPos().getY() + " " + h.blockPos().getZ();
        Vec3 c = centerOf(h);
        if (c == null)
            return "?";
        return Mth.floor(c.x) + " " + Mth.floor(c.y) + " " + Mth.floor(c.z);
    }

    public static void look(FpsProfiler.Hotspot h) {
        Minecraft mc = Minecraft.getInstance();
        Vec3 c = centerOf(h);
        if (mc.player == null || c == null)
            return;
        mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, c);
        say(mc, "[Fox's Tweaks] Looking at " + h.typeId() + " (" + coords(h) + ")", ChatFormatting.GRAY);
    }

    public static void copy(FpsProfiler.Hotspot h) {
        Minecraft mc = Minecraft.getInstance();
        String text = coords(h);
        mc.keyboardHandler.setClipboard(text);
        say(mc, "[Fox's Tweaks] Copied " + text, ChatFormatting.GRAY);
    }

    /** {@code /tp} needs permission level 2; the client knows its own level. */
    public static boolean canTeleport() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p != null && p.hasPermissions(2);
    }

    /** Teleports next to the target (never inside a wall), facing it. Runs the real {@code /tp} command. */
    public static boolean teleport(FpsProfiler.Hotspot h) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        AABB box = FpsProfiler.resolveBox(mc, h);
        if (p == null || mc.level == null || box == null)
            return false;
        if (!canTeleport()) {
            say(mc, "[Fox's Tweaks] Teleport needs op / cheats. Use Look or the coordinates instead.", ChatFormatting.RED);
            return false;
        }

        Vec3 c = box.getCenter();
        Vec3 spot = findSpot(mc, p, box, c);
        String cmd = String.format(Locale.ROOT, "tp @s %.2f %.2f %.2f facing %.2f %.2f %.2f",
                spot.x, spot.y, spot.z, c.x, c.y, c.z);
        p.connection.sendCommand(cmd);
        return true;
    }

    /** A free standing spot a few blocks from the target, on the side you are already on when possible. */
    private static Vec3 findSpot(Minecraft mc, LocalPlayer p, AABB box, Vec3 c) {
        double dx = p.getX() - c.x;
        double dz = p.getZ() - c.z;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) {
            dx = 1.0;
            dz = 0.0;
        } else {
            dx /= len;
            dz /= len;
        }

        double[] rings = { 2.5, 4.0, 6.0 };
        double[] turns = { 0, 45, -45, 90, -90, 135, -135, 180 };
        for (double ring : rings) {
            for (double turn : turns) {
                double rad = Math.toRadians(turn);
                double rx = dx * Math.cos(rad) - dz * Math.sin(rad);
                double rz = dx * Math.sin(rad) + dz * Math.cos(rad);
                for (double lift : new double[] { 0.0, 1.0 }) {
                    double x = c.x + rx * ring;
                    double y = box.minY + lift;
                    double z = c.z + rz * ring;
                    AABB me = p.getDimensions(p.getPose()).makeBoundingBox(x, y, z);
                    if (mc.level.noCollision(p, me))
                        return new Vec3(x, y, z);
                }
            }
        }
        return new Vec3(c.x, box.maxY + 0.1, c.z);
    }

    private static void say(Minecraft mc, String text, ChatFormatting color) {
        if (mc.player != null)
            mc.player.displayClientMessage(Component.literal(text).withStyle(color), false);
    }
}
