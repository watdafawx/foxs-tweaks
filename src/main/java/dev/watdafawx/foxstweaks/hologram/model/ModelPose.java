package dev.watdafawx.foxstweaks.hologram.model;

import java.util.List;

/**
 * Evaluates a {@link ModelData} at a point in an animation: node transforms, then vertices (rigid or skinned).
 * One instance per drawn model; it owns the scratch arrays so a frame allocates nothing. Pure Java.
 *
 * <p>Matrices are column-major float[16], like glTF and JOML.
 */
public final class ModelPose {
    private final ModelData model;
    /** World matrix of every node. */
    private final float[] world;
    private final float[] local = new float[16];
    private final float[] joint = new float[16];
    private final float[] tmp = new float[16];
    private final float[] q = new float[4];
    private final float[] v3 = new float[4];
    /** Per skin, the joint matrices (world * inverseBind), 16 per joint. */
    private final float[][] jointMatrices;

    public ModelPose(ModelData model) {
        this.model = model;
        int n = model.nodes.size();
        this.world = new float[n * 16];
        this.jointMatrices = new float[model.skins.size()][];
        for (int i = 0; i < this.jointMatrices.length; i++)
            this.jointMatrices[i] = new float[model.skins.get(i).joints.length * 16];
        evaluate(-1, 0.0);
    }

    public ModelData model() {
        return this.model;
    }

    /** The time inside {@code anim} that {@code clock} seconds maps to, honouring its loop mode. */
    public static double wrap(ModelData.Animation anim, double clock, int loopOverride) {
        double length = anim.length;
        if (!(length > 0.0))
            return 0.0;
        int loop = loopOverride >= 0 ? loopOverride : anim.loop;
        if (loop == ModelData.LOOP_LOOP)
            return ((clock % length) + length) % length;
        return Math.max(0.0, Math.min(length, clock));
    }

    /**
     * Fills the node world matrices for animation {@code anim} (-1 = the rest pose) at {@code time} seconds, already
     * wrapped by the caller.
     */
    public void evaluate(int anim, double time) {
        List<ModelData.Node> nodes = this.model.nodes;
        int n = nodes.size();
        ModelData.Animation animation = anim >= 0 && anim < this.model.animations.size()
                ? this.model.animations.get(anim) : null;

        // Local TRS per node: the rest values, replaced by whichever channels animate them.
        float[] t = this.scratchTrs(n);
        for (int i = 0; i < n; i++) {
            ModelData.Node nd = nodes.get(i);
            int o = i * 10;
            t[o] = nd.tx;
            t[o + 1] = nd.ty;
            t[o + 2] = nd.tz;
            t[o + 3] = nd.rx;
            t[o + 4] = nd.ry;
            t[o + 5] = nd.rz;
            t[o + 6] = nd.rw;
            t[o + 7] = nd.sx;
            t[o + 8] = nd.sy;
            t[o + 9] = nd.sz;
        }
        if (animation != null) {
            for (ModelData.Channel c : animation.channels) {
                int o = c.node * 10;
                switch (c.path) {
                    case ModelData.PATH_TRANSLATION -> sample3(c, time, t, o);
                    case ModelData.PATH_SCALE -> sample3(c, time, t, o + 7);
                    default -> sample4(c, time, t, o + 3);
                }
            }
        }

        for (int i = 0; i < n; i++) {
            int o = i * 10;
            compose(this.local, t[o], t[o + 1], t[o + 2], t[o + 3], t[o + 4], t[o + 5], t[o + 6], t[o + 7], t[o + 8], t[o + 9]);
            int parent = nodes.get(i).parent;
            if (parent < 0)
                System.arraycopy(this.local, 0, this.world, i * 16, 16);
            else
                multiply(this.world, parent * 16, this.local, 0, this.world, i * 16);
        }

        for (int s = 0; s < this.jointMatrices.length; s++) {
            ModelData.Skin skin = this.model.skins.get(s);
            float[] out = this.jointMatrices[s];
            for (int j = 0; j < skin.joints.length; j++)
                multiply(this.world, skin.joints[j] * 16, skin.inverseBind, j * 16, out, j * 16);
        }
    }

    private float[] trs;

    private float[] scratchTrs(int n) {
        if (this.trs == null)
            this.trs = new float[n * 10];
        return this.trs;
    }

    // ---------------------------------------------------------------- vertices

    /**
     * Writes the posed position and normal of every vertex of {@code prim} (a mesh on node {@code nodeIndex}) into
     * {@code outPos} / {@code outNrm}, which must hold at least {@code pos.length} floats.
     */
    public void pose(int nodeIndex, ModelData.Prim prim, float[] outPos, float[] outNrm) {
        ModelData.Node node = this.model.nodes.get(nodeIndex);
        int v = prim.vertexCount();
        if (node.skin < 0 || prim.joints == null) {
            System.arraycopy(this.world, nodeIndex * 16, this.joint, 0, 16);
            for (int i = 0; i < v; i++) {
                transformPoint(this.joint, prim.pos, i * 3, outPos);
                transformVector(this.joint, prim.nrm, i * 3, outNrm);
            }
            return;
        }
        float[] jm = this.jointMatrices[node.skin];
        for (int i = 0; i < v; i++) {
            float px = 0, py = 0, pz = 0, nx = 0, ny = 0, nz = 0;
            float sum = 0.0F;
            for (int k = 0; k < 4; k++)
                sum += prim.weights[i * 4 + k];
            float inv = sum > 1.0E-6F ? 1.0F / sum : 0.0F;
            for (int k = 0; k < 4; k++) {
                float w = prim.weights[i * 4 + k] * inv;
                if (w == 0.0F)
                    continue;
                int base = prim.joints[i * 4 + k] * 16;
                transformPoint(jm, base, prim.pos, i * 3, this.v3);
                px += this.v3[0] * w;
                py += this.v3[1] * w;
                pz += this.v3[2] * w;
                transformVector(jm, base, prim.nrm, i * 3, this.v3);
                nx += this.v3[0] * w;
                ny += this.v3[1] * w;
                nz += this.v3[2] * w;
            }
            if (inv == 0.0F) {
                // Unweighted vertex: leave it where the mesh node puts it.
                transformPoint(this.world, nodeIndex * 16, prim.pos, i * 3, this.v3);
                px = this.v3[0];
                py = this.v3[1];
                pz = this.v3[2];
                transformVector(this.world, nodeIndex * 16, prim.nrm, i * 3, this.v3);
                nx = this.v3[0];
                ny = this.v3[1];
                nz = this.v3[2];
            }
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0E-6F) {
                nx /= len;
                ny /= len;
                nz /= len;
            }
            outPos[i * 3] = px;
            outPos[i * 3 + 1] = py;
            outPos[i * 3 + 2] = pz;
            outNrm[i * 3] = nx;
            outNrm[i * 3 + 1] = ny;
            outNrm[i * 3 + 2] = nz;
        }
    }

    private static void transformPoint(float[] m, float[] src, int at, float[] out) {
        float x = src[at], y = src[at + 1], z = src[at + 2];
        out[at] = m[0] * x + m[4] * y + m[8] * z + m[12];
        out[at + 1] = m[1] * x + m[5] * y + m[9] * z + m[13];
        out[at + 2] = m[2] * x + m[6] * y + m[10] * z + m[14];
    }

    private static void transformVector(float[] m, float[] src, int at, float[] out) {
        float x = src[at], y = src[at + 1], z = src[at + 2];
        float nx = m[0] * x + m[4] * y + m[8] * z;
        float ny = m[1] * x + m[5] * y + m[9] * z;
        float nz = m[2] * x + m[6] * y + m[10] * z;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1.0E-6F) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        out[at] = nx;
        out[at + 1] = ny;
        out[at + 2] = nz;
    }

    private static void transformPoint(float[] m, int mo, float[] src, int at, float[] out) {
        float x = src[at], y = src[at + 1], z = src[at + 2];
        out[0] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z + m[mo + 12];
        out[1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z + m[mo + 13];
        out[2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z + m[mo + 14];
    }

    private static void transformVector(float[] m, int mo, float[] src, int at, float[] out) {
        float x = src[at], y = src[at + 1], z = src[at + 2];
        out[0] = m[mo] * x + m[mo + 4] * y + m[mo + 8] * z;
        out[1] = m[mo + 1] * x + m[mo + 5] * y + m[mo + 9] * z;
        out[2] = m[mo + 2] * x + m[mo + 6] * y + m[mo + 10] * z;
    }

    /** The posed world matrix of a node (copy). */
    public float[] worldMatrix(int node) {
        float[] out = new float[16];
        System.arraycopy(this.world, node * 16, out, 0, 16);
        return out;
    }

    // ---------------------------------------------------------------- sampling

    private static int segment(float[] times, double t) {
        int lo = 0, hi = times.length - 1;
        if (t <= times[0])
            return 0;
        if (t >= times[hi])
            return hi;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (times[mid] <= t)
                lo = mid;
            else
                hi = mid;
        }
        return lo;
    }

    private static void sample3(ModelData.Channel c, double time, float[] out, int at) {
        int k = segment(c.times, time);
        int last = c.times.length - 1;
        float[] v = c.values;
        if (k >= last || c.interp == ModelData.INTERP_STEP) {
            int s = Math.min(k, last) * 3;
            out[at] = v[s];
            out[at + 1] = v[s + 1];
            out[at + 2] = v[s + 2];
            return;
        }
        float span = c.times[k + 1] - c.times[k];
        float f = span > 1.0E-9F ? (float) ((time - c.times[k]) / span) : 0.0F;
        f = Math.max(0.0F, Math.min(1.0F, f));
        int a = k * 3, b = a + 3;
        out[at] = v[a] + (v[b] - v[a]) * f;
        out[at + 1] = v[a + 1] + (v[b + 1] - v[a + 1]) * f;
        out[at + 2] = v[a + 2] + (v[b + 2] - v[a + 2]) * f;
    }

    private void sample4(ModelData.Channel c, double time, float[] out, int at) {
        int k = segment(c.times, time);
        int last = c.times.length - 1;
        float[] v = c.values;
        if (k >= last || c.interp == ModelData.INTERP_STEP) {
            int s = Math.min(k, last) * 4;
            out[at] = v[s];
            out[at + 1] = v[s + 1];
            out[at + 2] = v[s + 2];
            out[at + 3] = v[s + 3];
            return;
        }
        float span = c.times[k + 1] - c.times[k];
        float f = span > 1.0E-9F ? (float) ((time - c.times[k]) / span) : 0.0F;
        f = Math.max(0.0F, Math.min(1.0F, f));
        slerp(v, k * 4, (k + 1) * 4, f, this.q);
        out[at] = this.q[0];
        out[at + 1] = this.q[1];
        out[at + 2] = this.q[2];
        out[at + 3] = this.q[3];
    }

    static void slerp(float[] v, int a, int b, float f, float[] out) {
        float ax = v[a], ay = v[a + 1], az = v[a + 2], aw = v[a + 3];
        float bx = v[b], by = v[b + 1], bz = v[b + 2], bw = v[b + 3];
        float dot = ax * bx + ay * by + az * bz + aw * bw;
        if (dot < 0.0F) {
            dot = -dot;
            bx = -bx;
            by = -by;
            bz = -bz;
            bw = -bw;
        }
        float s0, s1;
        if (dot > 0.9995F) {
            s0 = 1.0F - f;
            s1 = f;
        } else {
            float theta = (float) Math.acos(dot);
            float sin = (float) Math.sin(theta);
            s0 = (float) Math.sin((1.0F - f) * theta) / sin;
            s1 = (float) Math.sin(f * theta) / sin;
        }
        float x = ax * s0 + bx * s1, y = ay * s0 + by * s1, z = az * s0 + bz * s1, w = aw * s0 + bw * s1;
        float len = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (len < 1.0E-9F) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 0;
            out[3] = 1;
            return;
        }
        out[0] = x / len;
        out[1] = y / len;
        out[2] = z / len;
        out[3] = w / len;
    }

    // ---------------------------------------------------------------- matrices

    /** m = T * R * S, column-major. */
    public static void compose(float[] m, float tx, float ty, float tz, float qx, float qy, float qz, float qw,
                               float sx, float sy, float sz) {
        float len = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
        if (len < 1.0E-9F) {
            qx = qy = qz = 0;
            qw = 1;
        } else {
            qx /= len;
            qy /= len;
            qz /= len;
            qw /= len;
        }
        float xx = qx * qx, yy = qy * qy, zz = qz * qz;
        float xy = qx * qy, xz = qx * qz, yz = qy * qz;
        float wx = qw * qx, wy = qw * qy, wz = qw * qz;
        m[0] = (1 - 2 * (yy + zz)) * sx;
        m[1] = 2 * (xy + wz) * sx;
        m[2] = 2 * (xz - wy) * sx;
        m[3] = 0;
        m[4] = 2 * (xy - wz) * sy;
        m[5] = (1 - 2 * (xx + zz)) * sy;
        m[6] = 2 * (yz + wx) * sy;
        m[7] = 0;
        m[8] = 2 * (xz + wy) * sz;
        m[9] = 2 * (yz - wx) * sz;
        m[10] = (1 - 2 * (xx + yy)) * sz;
        m[11] = 0;
        m[12] = tx;
        m[13] = ty;
        m[14] = tz;
        m[15] = 1;
    }

    /** out = a * b (column-major), each with its own offset. {@code out} may not alias an input range. */
    public static void multiply(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float sum = 0.0F;
                for (int k = 0; k < 4; k++)
                    sum += a[ao + k * 4 + r] * b[bo + c * 4 + k];
                out[oo + c * 4 + r] = sum;
            }
        }
    }

    /** Euler (degrees) applied Z, then Y, then X rotation order R = Rz * Ry * Rx, as a quaternion (x, y, z, w). */
    public static float[] eulerZyx(double xDeg, double yDeg, double zDeg) {
        double hx = Math.toRadians(xDeg) * 0.5, hy = Math.toRadians(yDeg) * 0.5, hz = Math.toRadians(zDeg) * 0.5;
        double cx = Math.cos(hx), sx = Math.sin(hx), cy = Math.cos(hy), sy = Math.sin(hy), cz = Math.cos(hz), sz = Math.sin(hz);
        // q = qz * qy * qx
        double w = cz * cy * cx + sz * sy * sx;
        double x = cz * cy * sx - sz * sy * cx;
        double y = cz * sy * cx + sz * cy * sx;
        double z = sz * cy * cx - cz * sy * sx;
        return new float[] { (float) x, (float) y, (float) z, (float) w };
    }

    /** Bounds of the rest pose (posed vertices at {@code anim = -1}): {minX, minY, minZ, maxX, maxY, maxZ}. */
    public float[] bounds(int anim, double time) {
        evaluate(anim, time);
        float[] b = { Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE };
        for (int n = 0; n < this.model.nodes.size(); n++) {
            for (ModelData.Prim p : this.model.nodes.get(n).prims) {
                float[] pos = new float[p.pos.length];
                float[] nrm = new float[p.nrm.length];
                pose(n, p, pos, nrm);
                for (int i = 0; i < pos.length; i += 3) {
                    b[0] = Math.min(b[0], pos[i]);
                    b[1] = Math.min(b[1], pos[i + 1]);
                    b[2] = Math.min(b[2], pos[i + 2]);
                    b[3] = Math.max(b[3], pos[i]);
                    b[4] = Math.max(b[4], pos[i + 1]);
                    b[5] = Math.max(b[5], pos[i + 2]);
                }
            }
        }
        if (b[0] > b[3])
            return new float[] { 0, 0, 0, 1, 1, 1 };
        return b;
    }
}
