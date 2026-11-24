package dev.watdafawx.foxstweaks.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector2d;
import org.joml.Vector4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 3D viewport transform gizmo for {@link HologramScreen}: Move (X/Y/Z arrows + center), Rotate
 * (X/Y/Z rings) and Scale (axis + center handles), projected from the last level render's view and
 * projection matrices.
 *
 * <p>Pure 2D overlay math over {@link HologramRenderer}'s captured camera matrices, so it works
 * cleanly with shaders and needs no extra world render pass.
 */
final class HologramGizmo {

    enum Mode {
        MOVE("Move"),
        ROTATE("Rotate"),
        SCALE("Scale"),
        OFF("Off");

        final String label;

        Mode(String label) {
            this.label = label;
        }
    }

    private record Segment(double x1, double y1, double x2, double y2, int axis) {
    }

    private static final int[] AXIS_COLORS = {
            0xFFFF5566, // X - red
            0xFF66E877, // Y - green
            0xFF5599FF  // Z - blue
    };
    private static final int HOT_COLOR = 0xFFFFEC9B;
    private static final String[] AXIS_LABELS = { "X", "Y", "Z" };

    private Mode mode = Mode.MOVE;
    private int dragAxisIndex = -1;
    private int hoverAxisIndex = -1;
    private final List<Segment> segments = new ArrayList<>();

    private double initialMouseX;
    private double initialMouseY;
    private double initialParam;
    private Vec3 dragOrigin = Vec3.ZERO;
    private Vec3 dragAxis = Vec3.ZERO;
    private Vec3 initialPlane = Vec3.ZERO;
    private double lastAngle;
    private double angleSum;

    private float startOffsetX, startOffsetY, startOffsetZ;
    private float startYaw, startPitch, startRoll;
    private float startScale;

    Mode mode() {
        return this.mode;
    }

    void setMode(Mode mode) {
        this.mode = mode;
        this.dragAxisIndex = -1;
    }

    boolean isDragging() {
        return this.dragAxisIndex >= 0;
    }

    void cancelDrag() {
        this.dragAxisIndex = -1;
    }

    private double handleLength(float scale) {
        return this.mode == Mode.SCALE ? Math.max(0.75, 0.65 * scale) : Math.max(0.9, 0.75 * scale);
    }

    private static Vec3 axisVec(int axis) {
        return switch (axis) {
            case 0 -> new Vec3(1.0, 0.0, 0.0);
            case 1 -> new Vec3(0.0, 1.0, 0.0);
            default -> new Vec3(0.0, 0.0, 1.0);
        };
    }

    @Nullable
    private static Vector2d project(Vec3 worldPos, int screenW, int screenH) {
        Vec3 rel = worldPos.subtract(HologramRenderer.lastCamera());
        Vector4f v = new Vector4f((float) rel.x, (float) rel.y, (float) rel.z, 1.0F);
        Matrix4f vp = new Matrix4f(HologramRenderer.lastProjection()).mul(HologramRenderer.lastView());
        vp.transform(v);
        if (v.w <= 0.001F)
            return null;
        double sx = (v.x / v.w * 0.5 + 0.5) * screenW;
        double sy = (0.5 - v.y / v.w * 0.5) * screenH;
        return new Vector2d(sx, sy);
    }

    private static Vec3 ray(double mouseX, double mouseY, int screenW, int screenH) {
        Matrix4f inv = new Matrix4f(HologramRenderer.lastProjection()).mul(HologramRenderer.lastView()).invert();
        float ndcX = (float) (2.0 * mouseX / Math.max(1, screenW) - 1.0);
        float ndcY = (float) (1.0 - 2.0 * mouseY / Math.max(1, screenH));
        Vector4f v = inv.transform(new Vector4f(ndcX, ndcY, 1.0F, 1.0F));
        return new Vec3(v.x / v.w, v.y / v.w, v.z / v.w).normalize();
    }

    @Nullable
    private static Vec3 intersectPlane(double mouseX, double mouseY, int screenW, int screenH,
                                       Vec3 planeOrigin, Vec3 planeNormal) {
        Vec3 cam = HologramRenderer.lastCamera();
        Vec3 dir = ray(mouseX, mouseY, screenW, screenH);
        double denom = dir.dot(planeNormal);
        if (Math.abs(denom) < 1.0E-5)
            return null;
        double t = planeOrigin.subtract(cam).dot(planeNormal) / denom;
        if (t <= 0.0)
            return null;
        return cam.add(dir.scale(t));
    }

    private double axisParameter(double mouseX, double mouseY, int screenW, int screenH) {
        Vec3 cam = HologramRenderer.lastCamera();
        Vec3 r = ray(mouseX, mouseY, screenW, screenH);
        Vec3 w0 = this.dragOrigin.subtract(cam);
        double a = this.dragAxis.dot(this.dragAxis);
        double b = this.dragAxis.dot(r);
        double c = r.dot(r);
        double d = this.dragAxis.dot(w0);
        double e = r.dot(w0);
        double denom = a * c - b * b;
        if (Math.abs(denom) < 1.0E-6)
            return 0.0;
        return (b * e - c * d) / denom;
    }

    private void addSegment(Vec3 a, Vec3 b, int axis, int screenW, int screenH) {
        Vector2d pa = project(a, screenW, screenH);
        Vector2d pb = project(b, screenW, screenH);
        if (pa != null && pb != null)
            this.segments.add(new Segment(pa.x, pa.y, pb.x, pb.y, axis));
    }

    private void buildGeometry(Vec3 center, float scale, int screenW, int screenH) {
        this.segments.clear();
        if (this.mode == Mode.OFF)
            return;

        double len = handleLength(scale);
        if (this.mode == Mode.ROTATE) {
            int steps = 28;
            for (int axis = 0; axis < 3; axis++) {
                Vec3 u = axisVec((axis + 1) % 3);
                Vec3 v = axisVec((axis + 2) % 3);
                Vec3 prev = center.add(u.scale(len));
                for (int s = 1; s <= steps; s++) {
                    double a = (Math.PI * 2.0 * s) / steps;
                    Vec3 next = center.add(u.scale(Math.cos(a) * len)).add(v.scale(Math.sin(a) * len));
                    addSegment(prev, next, axis, screenW, screenH);
                    prev = next;
                }
            }
        } else {
            for (int axis = 0; axis < 3; axis++) {
                Vec3 tip = center.add(axisVec(axis).scale(len));
                addSegment(center, tip, axis, screenW, screenH);
            }
        }
    }

    private int hitTest(double mouseX, double mouseY, Vec3 center, int screenW, int screenH) {
        if (this.mode == Mode.OFF)
            return -1;

        if (this.mode != Mode.ROTATE) {
            Vector2d c = project(center, screenW, screenH);
            if (c != null && Math.hypot(mouseX - c.x, mouseY - c.y) <= 9.0)
                return 3;
        }

        int bestAxis = -1;
        double bestDist = 9.0;
        for (Segment seg : this.segments) {
            double d = distToSegment(mouseX, mouseY, seg.x1, seg.y1, seg.x2, seg.y2);
            if (d < bestDist) {
                bestDist = d;
                bestAxis = seg.axis;
            }
        }
        return bestAxis;
    }

    private static double distToSegment(double px, double py, double x1, double y1, double x2, double y2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq < 1.0E-4)
            return Math.hypot(px - x1, py - y1);
        double t = Mth.clamp(((px - x1) * dx + (py - y1) * dy) / lenSq, 0.0, 1.0);
        return Math.hypot(px - (x1 + t * dx), py - (y1 + t * dy));
    }

    void render(GuiGraphics graphics, Font font, int mouseX, int mouseY, int screenW, int screenH,
                Vec3 center, float scale) {
        if (this.mode == Mode.OFF)
            return;

        buildGeometry(center, scale, screenW, screenH);
        this.hoverAxisIndex = hitTest(mouseX, mouseY, center, screenW, screenH);
        int active = this.dragAxisIndex >= 0 ? this.dragAxisIndex : this.hoverAxisIndex;

        for (Segment seg : this.segments) {
            int color = seg.axis == active ? HOT_COLOR : AXIS_COLORS[seg.axis];
            drawLine(graphics, seg.x1, seg.y1, seg.x2, seg.y2, color);
        }

        double len = handleLength(scale);
        if (this.mode != Mode.ROTATE) {
            for (int axis = 0; axis < 3; axis++) {
                Vector2d tip = project(center.add(axisVec(axis).scale(len)), screenW, screenH);
                if (tip == null)
                    continue;
                int tx = (int) tip.x;
                int ty = (int) tip.y;
                int color = axis == active ? HOT_COLOR : AXIS_COLORS[axis];
                graphics.fill(tx - 3, ty - 3, tx + 4, ty + 4, color);
                graphics.drawString(font, AXIS_LABELS[axis], tx + 5, ty - 4, color, true);
            }

            Vector2d c = project(center, screenW, screenH);
            if (c != null) {
                int cx = (int) c.x;
                int cy = (int) c.y;
                int color = active == 3 ? HOT_COLOR : 0xFFE8EDF5;
                graphics.fill(cx - 4, cy - 4, cx + 5, cy + 5, color);
                graphics.renderOutline(cx - 5, cy - 5, 11, 11, 0xCC0B0D12);
            }
        }
    }

    private static void drawLine(GuiGraphics graphics, double x1, double y1, double x2, double y2, int color) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        int steps = (int) Math.min(200.0, Math.max(Math.abs(dx), Math.abs(dy)));
        for (int i = 0; i <= steps; i++) {
            double t = steps == 0 ? 0.0 : (double) i / steps;
            int x = (int) (x1 + dx * t);
            int y = (int) (y1 + dy * t);
            graphics.fill(x - 1, y - 1, x + 2, y + 2, color);
        }
    }

    boolean beginDrag(double mouseX, double mouseY, int screenW, int screenH, Vec3 center,
                      float offsetX, float offsetY, float offsetZ,
                      float yaw, float pitch, float roll, float scale) {
        if (this.mode == Mode.OFF)
            return false;

        buildGeometry(center, scale, screenW, screenH);
        int hit = hitTest(mouseX, mouseY, center, screenW, screenH);
        if (hit < 0)
            return false;

        this.dragAxisIndex = hit;
        this.initialMouseX = mouseX;
        this.initialMouseY = mouseY;
        this.dragOrigin = center;
        this.dragAxis = hit == 3 ? ray(screenW * 0.5, screenH * 0.5, screenW, screenH) : axisVec(hit);
        this.initialParam = axisParameter(mouseX, mouseY, screenW, screenH);
        Vec3 planeHit = intersectPlane(mouseX, mouseY, screenW, screenH, this.dragOrigin, this.dragAxis);
        this.initialPlane = planeHit != null ? planeHit : this.dragOrigin;
        this.lastAngle = 0.0;
        this.angleSum = 0.0;

        this.startOffsetX = offsetX;
        this.startOffsetY = offsetY;
        this.startOffsetZ = offsetZ;
        this.startYaw = yaw;
        this.startPitch = pitch;
        this.startRoll = roll;
        this.startScale = scale;
        return true;
    }

    interface Target {
        void setOffset(float x, float y, float z);
        void setRotation(float yaw, float pitch, float roll);
        void setScale(float scale);
    }

    void updateDrag(double mouseX, double mouseY, int screenW, int screenH, float stepMult, boolean free,
                    Target target) {
        if (this.dragAxisIndex < 0)
            return;

        if (this.mode == Mode.MOVE) {
            double snap = free ? 0.0 : 0.25 * stepMult;
            Vec3 delta;
            if (this.dragAxisIndex == 3) {
                Vec3 hit = intersectPlane(mouseX, mouseY, screenW, screenH, this.dragOrigin, this.dragAxis);
                if (hit == null)
                    return;
                delta = hit.subtract(this.initialPlane);
                if (snap > 0.0) {
                    delta = new Vec3(
                            Math.rint(delta.x / snap) * snap,
                            Math.rint(delta.y / snap) * snap,
                            Math.rint(delta.z / snap) * snap);
                }
            } else {
                double dist = axisParameter(mouseX, mouseY, screenW, screenH) - this.initialParam;
                if (snap > 0.0)
                    dist = Math.rint(dist / snap) * snap;
                delta = this.dragAxis.scale(dist);
            }
            target.setOffset(
                    Mth.clamp((float) (this.startOffsetX + delta.x), -16.0F, 16.0F),
                    Mth.clamp((float) (this.startOffsetY + delta.y), -16.0F, 16.0F),
                    Mth.clamp((float) (this.startOffsetZ + delta.z), -16.0F, 16.0F));
        } else if (this.mode == Mode.ROTATE) {
            Vec3 hit = intersectPlane(mouseX, mouseY, screenW, screenH, this.dragOrigin, this.dragAxis);
            if (hit == null)
                return;
            Vec3 v0 = this.initialPlane.subtract(this.dragOrigin);
            Vec3 v1 = hit.subtract(this.dragOrigin);
            if (v0.lengthSqr() < 1.0E-5 || v1.lengthSqr() < 1.0E-5)
                return;
            v0 = v0.normalize();
            v1 = v1.normalize();
            double angle = Math.toDegrees(Math.atan2(v0.cross(v1).dot(this.dragAxis), v0.dot(v1)));
            double diff = angle - this.lastAngle;
            if (diff > 180.0)
                diff -= 360.0;
            else if (diff < -180.0)
                diff += 360.0;
            this.lastAngle = angle;
            this.angleSum += diff;

            double applied = this.angleSum;
            double snap = free ? 0.0 : 15.0 * stepMult;
            if (snap > 0.0)
                applied = Math.rint(applied / snap) * snap;

            float yaw = this.startYaw;
            float pitch = this.startPitch;
            float roll = this.startRoll;
            if (this.dragAxisIndex == 0)
                pitch = Mth.wrapDegrees((float) (this.startPitch + applied));
            else if (this.dragAxisIndex == 1)
                yaw = Mth.wrapDegrees((float) (this.startYaw + applied));
            else if (this.dragAxisIndex == 2)
                roll = Mth.wrapDegrees((float) (this.startRoll + applied));
            target.setRotation(yaw, pitch, roll);
        } else if (this.mode == Mode.SCALE) {
            double factor;
            if (this.dragAxisIndex == 3) {
                factor = Math.exp(((mouseX - this.initialMouseX) + (this.initialMouseY - mouseY)) / 100.0);
            } else {
                double delta = axisParameter(mouseX, mouseY, screenW, screenH) - this.initialParam;
                factor = Math.max(0.05, 1.0 + delta / Math.max(0.5, handleLength(this.startScale)));
            }
            double next = this.startScale * factor;
            double snap = free ? 0.0 : 0.25 * stepMult;
            if (snap > 0.0)
                next = Math.rint(next / snap) * snap;
            target.setScale(Mth.clamp((float) next, 0.25F, 8.0F));
        }
    }
}
