package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A waypoint path for a hologram to follow (offsets relative to the hologram's anchor {@code pos}).
 * Supports closed {@link Mode#LOOP} and {@link Mode#PINGPONG} patrol, Catmull-Rom smoothing,
 * configurable speed (blocks/sec), and optional yaw facing along the movement direction.
 */
public record HologramPath(
        List<Point> points,
        float speed,
        Mode mode,
        boolean smooth,
        boolean faceMovement
) {
    public static final int MAX_POINTS = 64;
    public static final float MAX_COORD = 128.0F;
    public static final float MIN_SPEED = 0.1F;
    public static final float MAX_SPEED = 20.0F;

    public record Point(float x, float y, float z) {
        public Point {
            x = Mth.clamp(Float.isFinite(x) ? x : 0.0F, -MAX_COORD, MAX_COORD);
            y = Mth.clamp(Float.isFinite(y) ? y : 0.0F, -MAX_COORD, MAX_COORD);
            z = Mth.clamp(Float.isFinite(z) ? z : 0.0F, -MAX_COORD, MAX_COORD);
        }

        public float distTo(Point o) {
            float dx = o.x - this.x;
            float dy = o.y - this.y;
            float dz = o.z - this.z;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    public enum Mode {
        LOOP("Loop"),
        PINGPONG("Ping-Pong");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public Mode next() {
            return this == LOOP ? PINGPONG : LOOP;
        }

        public static Mode parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty())
                return LOOP;
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT).replace("-", ""));
            } catch (IllegalArgumentException e) {
                return LOOP;
            }
        }
    }

    public record Sample(float x, float y, float z, float yawDeg, boolean moving) {
        public static final Sample ZERO = new Sample(0.0F, 0.0F, 0.0F, 0.0F, false);
    }

    public HologramPath {
        if (points == null)
            points = List.of();
        else if (points.size() > MAX_POINTS)
            points = List.copyOf(points.subList(0, MAX_POINTS));
        else
            points = List.copyOf(points);
        speed = Mth.clamp(Float.isFinite(speed) ? speed : 2.0F, MIN_SPEED, MAX_SPEED);
        mode = mode == null ? Mode.LOOP : mode;
    }

    public static HologramPath empty() {
        return new HologramPath(List.of(), 2.0F, Mode.LOOP, true, true);
    }

    public boolean active() {
        return this.points.size() >= 2;
    }

    /** Expands {@code box} to cover every waypoint offset so culling & distance checks keep moving holograms alive. */
    public AABB expand(AABB box) {
        if (this.points.isEmpty())
            return box;
        float minX = 0.0F, minY = 0.0F, minZ = 0.0F;
        float maxX = 0.0F, maxY = 0.0F, maxZ = 0.0F;
        for (Point p : this.points) {
            if (p.x < minX) minX = p.x;
            if (p.y < minY) minY = p.y;
            if (p.z < minZ) minZ = p.z;
            if (p.x > maxX) maxX = p.x;
            if (p.y > maxY) maxY = p.y;
            if (p.z > maxZ) maxZ = p.z;
        }
        return new AABB(box.minX + minX, box.minY + minY, box.minZ + minZ,
                box.maxX + maxX, box.maxY + maxY, box.maxZ + maxZ);
    }

    /** Evaluates polyline points along the path for editor overlay drawing. */
    public List<Point> polyline(int subdivisions) {
        int n = this.points.size();
        if (n < 2)
            return this.points;
        boolean loop = this.mode == Mode.LOOP;
        int segCount = loop ? n : n - 1;
        int steps = this.smooth && n >= 3 ? Math.max(2, subdivisions) : 1;
        List<Point> out = new ArrayList<>(segCount * steps + 1);
        for (int seg = 0; seg < segCount; seg++) {
            Point p1 = this.points.get(seg);
            Point p2 = this.points.get((seg + 1) % n);
            if (this.smooth && n >= 3) {
                Point p0 = loop ? this.points.get((seg - 1 + n) % n) : this.points.get(Math.max(0, seg - 1));
                Point p3 = loop ? this.points.get((seg + 2) % n) : this.points.get(Math.min(n - 1, seg + 2));
                for (int s = 0; s < steps; s++) {
                    float t = s / (float) steps;
                    out.add(new Point(
                            catmull(p0.x, p1.x, p2.x, p3.x, t),
                            catmull(p0.y, p1.y, p2.y, p3.y, t),
                            catmull(p0.z, p1.z, p2.z, p3.z, t)));
                }
            } else {
                out.add(p1);
            }
        }
        out.add(loop ? this.points.get(0) : this.points.get(n - 1));
        return out;
    }

    public HologramPath withPoint(Point p) {
        if (this.points.size() >= MAX_POINTS)
            return this;
        List<Point> next = new ArrayList<>(this.points.size() + 1);
        next.addAll(this.points);
        next.add(p);
        return new HologramPath(next, this.speed, this.mode, this.smooth, this.faceMovement);
    }

    public HologramPath withoutLast() {
        if (this.points.isEmpty())
            return this;
        List<Point> next = new ArrayList<>(this.points.subList(0, this.points.size() - 1));
        return new HologramPath(next, this.speed, this.mode, this.smooth, this.faceMovement);
    }

    public HologramPath withSpeed(float speed) {
        return new HologramPath(this.points, speed, this.mode, this.smooth, this.faceMovement);
    }

    public HologramPath withMode(Mode mode) {
        return new HologramPath(this.points, this.speed, mode, this.smooth, this.faceMovement);
    }

    public HologramPath withSmooth(boolean smooth) {
        return new HologramPath(this.points, this.speed, this.mode, smooth, this.faceMovement);
    }

    public HologramPath withFaceMovement(boolean faceMovement) {
        return new HologramPath(this.points, this.speed, this.mode, this.smooth, faceMovement);
    }

    /** Evaluates position and facing yaw (degrees, +Z = 0, +X = +90) at {@code seconds}. */
    public Sample sample(double seconds) {
        int n = this.points.size();
        if (n == 0)
            return Sample.ZERO;
        if (n == 1) {
            Point p = this.points.get(0);
            return new Sample(p.x, p.y, p.z, 0.0F, false);
        }

        boolean loop = this.mode == Mode.LOOP;
        int segCount = loop ? n : n - 1;
        float[] segLens = new float[segCount];
        double total = 0.0;
        for (int i = 0; i < segCount; i++) {
            float d = this.points.get(i).distTo(this.points.get((i + 1) % n));
            segLens[i] = d;
            total += d;
        }
        if (total < 1.0E-4) {
            Point p = this.points.get(0);
            return new Sample(p.x, p.y, p.z, 0.0F, false);
        }

        double rawDist = seconds * this.speed;
        double dist;
        boolean reverse = false;
        if (loop) {
            dist = ((rawDist % total) + total) % total;
        } else {
            double period = total * 2.0;
            double p = ((rawDist % period) + period) % period;
            if (p <= total) {
                dist = p;
            } else {
                dist = period - p;
                reverse = true;
            }
        }

        int seg = segCount - 1;
        double acc = 0.0;
        float t = 1.0F;
        for (int i = 0; i < segCount; i++) {
            float len = segLens[i];
            if (dist <= acc + len || i == segCount - 1) {
                seg = i;
                t = len > 1.0E-5F ? (float) Mth.clamp((dist - acc) / len, 0.0, 1.0) : 0.0F;
                break;
            }
            acc += len;
        }

        Point p1 = this.points.get(seg);
        Point p2 = this.points.get((seg + 1) % n);
        float x, y, z, dx, dz;
        if (this.smooth && n >= 3) {
            Point p0 = loop ? this.points.get((seg - 1 + n) % n) : this.points.get(Math.max(0, seg - 1));
            Point p3 = loop ? this.points.get((seg + 2) % n) : this.points.get(Math.min(n - 1, seg + 2));
            x = catmull(p0.x, p1.x, p2.x, p3.x, t);
            y = catmull(p0.y, p1.y, p2.y, p3.y, t);
            z = catmull(p0.z, p1.z, p2.z, p3.z, t);
            dx = catmullDeriv(p0.x, p1.x, p2.x, p3.x, t);
            dz = catmullDeriv(p0.z, p1.z, p2.z, p3.z, t);
        } else {
            x = Mth.lerp(t, p1.x, p2.x);
            y = Mth.lerp(t, p1.y, p2.y);
            z = Mth.lerp(t, p1.z, p2.z);
            dx = p2.x - p1.x;
            dz = p2.z - p1.z;
        }
        if (reverse) {
            dx = -dx;
            dz = -dz;
        }
        float yaw = (dx * dx + dz * dz > 1.0E-6F) ? (float) Math.toDegrees(Math.atan2(dx, dz)) : 0.0F;
        return new Sample(x, y, z, yaw, true);
    }

    private static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5F * ((2.0F * p1)
                + (-p0 + p2) * t
                + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t2
                + (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t3);
    }

    private static float catmullDeriv(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        return 0.5F * ((-p0 + p2)
                + 2.0F * (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t
                + 3.0F * (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t2);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Point p : this.points) {
            CompoundTag pt = new CompoundTag();
            pt.putFloat("x", p.x);
            pt.putFloat("y", p.y);
            pt.putFloat("z", p.z);
            list.add(pt);
        }
        tag.put("points", list);
        tag.putFloat("speed", this.speed);
        tag.putString("mode", this.mode.name());
        tag.putBoolean("smooth", this.smooth);
        tag.putBoolean("face", this.faceMovement);
        return tag;
    }

    @Nullable
    public static HologramPath fromTag(CompoundTag tag) {
        ListTag list = tag.getList("points", Tag.TAG_COMPOUND);
        if (list.isEmpty())
            return null;
        List<Point> pts = new ArrayList<>(Math.min(list.size(), MAX_POINTS));
        for (int i = 0; i < list.size() && pts.size() < MAX_POINTS; i++) {
            CompoundTag pt = list.getCompound(i);
            pts.add(new Point(pt.getFloat("x"), pt.getFloat("y"), pt.getFloat("z")));
        }
        float speed = tag.contains("speed") ? tag.getFloat("speed") : 2.0F;
        Mode mode = Mode.parse(tag.getString("mode"));
        boolean smooth = !tag.contains("smooth") || tag.getBoolean("smooth");
        boolean face = !tag.contains("face") || tag.getBoolean("face");
        return new HologramPath(pts, speed, mode, smooth, face);
    }
}
