package dev.watdafawx.foxstweaks.hologram.model;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A 3D model in the one shape the game draws, whatever it was uploaded as: a tree of nodes (bones), each holding
 * triangle meshes, optional skins (glTF joints/weights), textures, and keyframe animations.
 *
 * <p>Both readers ({@link GlbReader}, {@link BlockbenchReader}) produce this, the server validates and re-encodes it
 * into the canonical "FTM1" blob stored as an asset, and the client decodes only that blob. So there is exactly one
 * parser of untrusted-but-sanitised bytes on the client, and no source-format parsing outside the server.
 *
 * <p>Pure Java (no Minecraft types): {@code build.bat modelSelfTest} runs it in a bare JVM.
 *
 * <p>Coordinates are blocks, Y up, rest pose. Rotations are quaternions (x, y, z, w). Animation channels REPLACE
 * the node's rest value for that path, like glTF; the Blockbench reader bakes its additive Euler animation into that
 * form so the player has one rule.
 */
public final class ModelData {
    public static final int MAGIC = 0x46544D31; // "FTM1"
    public static final int VERSION = 2;

    // Hard ceilings. Sized for detailed Sketchfab/game .glb props and rigged characters.
    public static final int MAX_NODES = 4096;
    public static final int MAX_VERTICES = 262_144;
    public static final int MAX_TRIANGLES = 524_288;
    public static final int MAX_TEXTURES = 64;
    public static final int MAX_TEXTURE_DIMENSION = 2048;
    public static final int MAX_TEXTURE_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SKINS = 32;
    public static final int MAX_JOINTS = 1024;
    public static final int MAX_ANIMATIONS = 128;
    public static final int MAX_CHANNELS = 16_384;
    public static final int MAX_KEYS = 400_000;
    public static final float MAX_SECONDS = 600.0F;
    public static final int MAX_NAME = 64;

    public static final int PATH_TRANSLATION = 0, PATH_ROTATION = 1, PATH_SCALE = 2;
    public static final int INTERP_LINEAR = 0, INTERP_STEP = 1;
    public static final int LOOP_ONCE = 0, LOOP_LOOP = 1, LOOP_HOLD = 2;
    public static final int ALPHA_OPAQUE = 0, ALPHA_MASK = 1, ALPHA_BLEND = 2;

    public static final class Node {
        public String name = "";
        /** Index of the parent node, -1 for a root. Parents always come before their children. */
        public int parent = -1;
        public float tx, ty, tz;
        public float rx, ry, rz, rw = 1.0F;
        public float sx = 1.0F, sy = 1.0F, sz = 1.0F;
        public int skin = -1;
        public final List<Prim> prims = new ArrayList<>();
    }

    /** One draw call: triangles sharing a material. */
    public static final class Prim {
        public float[] pos = new float[0];
        public float[] nrm = new float[0];
        public float[] uv = new float[0];
        public int[] idx = new int[0];
        /** Four joint indices per vertex into the node's skin, or null. */
        public short[] joints;
        /** Four weights per vertex, or null. */
        public float[] weights;
        public int texture = -1;
        public float cr = 1.0F, cg = 1.0F, cb = 1.0F, ca = 1.0F;
        public int alphaMode = ALPHA_OPAQUE;
        public boolean doubleSided;

        public int vertexCount() {
            return this.pos.length / 3;
        }
    }

    public static final class Skin {
        /** Node index of each joint. */
        public int[] joints = new int[0];
        /** 16 floats per joint, column major. */
        public float[] inverseBind = new float[0];
    }

    public static final class Texture {
        public String name = "";
        public int width, height;
        /** PNG bytes (the server re-encodes everything it accepts as PNG). */
        public byte[] png = new byte[0];
    }

    public static final class Channel {
        public int node;
        public int path;
        public int interp;
        public float[] times = new float[0];
        /** 3 floats per key (translation, scale) or 4 (rotation). */
        public float[] values = new float[0];
    }

    public static final class Animation {
        public String name = "";
        public float length;
        public int loop = LOOP_LOOP;
        public final List<Channel> channels = new ArrayList<>();
    }

    public final List<Node> nodes = new ArrayList<>();
    public final List<Skin> skins = new ArrayList<>();
    public final List<Texture> textures = new ArrayList<>();
    public final List<Animation> animations = new ArrayList<>();

    // ---------------------------------------------------------------- measuring

    public int vertexCount() {
        int n = 0;
        for (Node node : this.nodes)
            for (Prim p : node.prims)
                n += p.vertexCount();
        return n;
    }

    public int triangleCount() {
        int n = 0;
        for (Node node : this.nodes)
            for (Prim p : node.prims)
                n += p.idx.length / 3;
        return n;
    }

    public int keyCount() {
        int n = 0;
        for (Animation a : this.animations)
            for (Channel c : a.channels)
                n += c.times.length;
        return n;
    }

    public int animationIndex(String name) {
        if (name == null || name.isEmpty())
            return -1;
        for (int i = 0; i < this.animations.size(); i++)
            if (this.animations.get(i).name.equals(name))
                return i;
        return -1;
    }

    // ---------------------------------------------------------------- validation

    /** Throws {@link IOException} with a message fit to show a player when the model breaks a cap or is inconsistent. */
    public void validate() throws IOException {
        if (this.nodes.isEmpty())
            throw new IOException("The model has no nodes");
        if (this.nodes.size() > MAX_NODES)
            throw new IOException("Too many bones/nodes (" + this.nodes.size() + ", max " + MAX_NODES + ")");
        if (this.textures.size() > MAX_TEXTURES)
            throw new IOException("Too many textures (" + this.textures.size() + ", max " + MAX_TEXTURES + ")");
        if (this.skins.size() > MAX_SKINS)
            throw new IOException("Too many skins (max " + MAX_SKINS + ")");
        if (this.animations.size() > MAX_ANIMATIONS)
            throw new IOException("Too many animations (" + this.animations.size() + ", max " + MAX_ANIMATIONS + ")");
        int vertices = vertexCount();
        int triangles = triangleCount();
        if (triangles == 0)
            throw new IOException("The model has no triangles");
        if (vertices > MAX_VERTICES)
            throw new IOException("Too many vertices (" + vertices + ", max " + MAX_VERTICES + "); reduce the model's detail");
        if (triangles > MAX_TRIANGLES)
            throw new IOException("Too many triangles (" + triangles + ", max " + MAX_TRIANGLES + "); reduce the model's detail");
        if (keyCount() > MAX_KEYS)
            throw new IOException("Too many animation keyframes (max " + MAX_KEYS + ")");

        for (int i = 0; i < this.nodes.size(); i++) {
            Node n = this.nodes.get(i);
            if (n.parent < -1 || n.parent >= i)
                throw new IOException("Bone " + i + " has an invalid parent");
            if (n.skin < -1 || n.skin >= this.skins.size())
                throw new IOException("Bone " + i + " has an invalid skin");
            finite(n.tx, n.ty, n.tz, n.rx, n.ry, n.rz, n.rw, n.sx, n.sy, n.sz);
            for (Prim p : n.prims)
                validate(p, n.skin >= 0 ? this.skins.get(n.skin).joints.length : 0);
        }
        for (Skin s : this.skins) {
            if (s.joints.length == 0 || s.joints.length > MAX_JOINTS || s.inverseBind.length != s.joints.length * 16)
                throw new IOException("A skin is malformed or has too many joints (max " + MAX_JOINTS + ")");
            for (int j : s.joints)
                if (j < 0 || j >= this.nodes.size())
                    throw new IOException("A skin joint points at a missing bone");
            finite(s.inverseBind);
        }
        for (Texture t : this.textures)
            if (t.width < 1 || t.height < 1 || t.width > MAX_TEXTURE_DIMENSION || t.height > MAX_TEXTURE_DIMENSION
                    || t.png.length == 0 || t.png.length > MAX_TEXTURE_BYTES)
                throw new IOException("A texture is empty or larger than " + MAX_TEXTURE_DIMENSION + " px");
        for (Animation a : this.animations) {
            if (!(a.length >= 0.0F) || a.length > MAX_SECONDS)
                throw new IOException("Animation \"" + a.name + "\" is longer than " + (int) MAX_SECONDS + " s");
            if (a.channels.size() > MAX_CHANNELS)
                throw new IOException("Animation \"" + a.name + "\" has too many channels");
            for (Channel c : a.channels) {
                int width = c.path == PATH_ROTATION ? 4 : 3;
                if (c.node < 0 || c.node >= this.nodes.size() || c.path < 0 || c.path > 2
                        || c.interp < 0 || c.interp > 1 || c.times.length == 0 || c.values.length != c.times.length * width)
                    throw new IOException("Animation \"" + a.name + "\" has a malformed channel");
                for (int k = 0; k < c.times.length; k++)
                    if (!(c.times[k] >= 0.0F) || (k > 0 && c.times[k] < c.times[k - 1]))
                        throw new IOException("Animation \"" + a.name + "\" has keyframes out of order");
                finite(c.times);
                finite(c.values);
            }
        }
    }

    private static void validate(Prim p, int skinJoints) throws IOException {
        int v = p.vertexCount();
        if (p.pos.length % 3 != 0 || p.nrm.length != p.pos.length || p.uv.length != v * 2 || p.idx.length % 3 != 0)
            throw new IOException("A mesh has mismatched vertex data");
        for (int i : p.idx)
            if (i < 0 || i >= v)
                throw new IOException("A mesh index points past its vertices");
        if (p.texture < -1)
            throw new IOException("A mesh has an invalid texture");
        if ((p.joints == null) != (p.weights == null))
            throw new IOException("A mesh has joints without weights");
        if (p.joints != null) {
            if (skinJoints == 0 || p.joints.length != v * 4 || p.weights.length != v * 4)
                throw new IOException("A skinned mesh has mismatched joint data");
            for (short j : p.joints)
                if (j < 0 || j >= skinJoints)
                    throw new IOException("A skinned mesh points at a missing joint");
            finite(p.weights);
        }
        finite(p.pos);
        finite(p.nrm);
        finite(p.uv);
        finite(p.cr, p.cg, p.cb, p.ca);
    }

    private static void finite(float... values) throws IOException {
        for (float f : values)
            if (!Float.isFinite(f))
                throw new IOException("The model contains a number that is not finite");
    }

    // ---------------------------------------------------------------- canonical encoding

    public static boolean isModel(byte[] bytes) {
        return bytes.length >= 5 && ((bytes[0] & 0xFF) << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8
                | (bytes[3] & 0xFF)) == MAGIC;
    }

    public byte[] encode() throws IOException {
        validate();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(1 << 16);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(MAGIC);
        out.writeByte(VERSION);

        out.writeShort(this.nodes.size());
        for (Node n : this.nodes) {
            string(out, n.name);
            out.writeShort(n.parent);
            floats(out, n.tx, n.ty, n.tz, n.rx, n.ry, n.rz, n.rw, n.sx, n.sy, n.sz);
            out.writeShort(n.skin);
            out.writeShort(n.prims.size());
            for (Prim p : n.prims) {
                int vCount = p.vertexCount();
                out.writeInt(vCount);
                floatArray(out, p.pos);
                floatArray(out, p.nrm);
                floatArray(out, p.uv);
                out.writeInt(p.idx.length);
                if (vCount <= 65535) {
                    for (int i : p.idx)
                        out.writeShort(i);
                } else {
                    for (int i : p.idx)
                        out.writeInt(i);
                }
                out.writeBoolean(p.joints != null);
                if (p.joints != null) {
                    for (short j : p.joints)
                        out.writeShort(j);
                    floatArray(out, p.weights);
                }
                out.writeShort(p.texture);
                floats(out, p.cr, p.cg, p.cb, p.ca);
                out.writeByte(p.alphaMode);
                out.writeBoolean(p.doubleSided);
            }
        }

        out.writeByte(this.skins.size());
        for (Skin s : this.skins) {
            out.writeShort(s.joints.length);
            for (int j : s.joints)
                out.writeShort(j);
            floatArray(out, s.inverseBind);
        }

        out.writeByte(this.textures.size());
        for (Texture t : this.textures) {
            string(out, t.name);
            out.writeShort(t.width);
            out.writeShort(t.height);
            out.writeInt(t.png.length);
            out.write(t.png);
        }

        out.writeByte(this.animations.size());
        for (Animation a : this.animations) {
            string(out, a.name);
            out.writeFloat(a.length);
            out.writeByte(a.loop);
            out.writeShort(a.channels.size());
            for (Channel c : a.channels) {
                out.writeShort(c.node);
                out.writeByte(c.path);
                out.writeByte(c.interp);
                out.writeInt(c.times.length);
                floatArray(out, c.times);
                floatArray(out, c.values);
            }
        }
        out.flush();
        return bytes.toByteArray();
    }

    public static ModelData decode(byte[] bytes) throws IOException {
        if (!isModel(bytes))
            throw new IOException("Not a model blob");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            in.readInt();
            int ver = in.readUnsignedByte();
            if (ver != 1 && ver != VERSION)
                throw new IOException("Unsupported model version");
            ModelData m = new ModelData();
            int total = bytes.length;

            int nodeCount = in.readUnsignedShort();
            if (nodeCount > MAX_NODES)
                throw new IOException("Too many nodes");
            int vertexBudget = MAX_VERTICES;
            int triangleBudget = MAX_TRIANGLES;
            for (int i = 0; i < nodeCount; i++) {
                Node n = new Node();
                n.name = string(in);
                n.parent = in.readShort();
                n.tx = in.readFloat();
                n.ty = in.readFloat();
                n.tz = in.readFloat();
                n.rx = in.readFloat();
                n.ry = in.readFloat();
                n.rz = in.readFloat();
                n.rw = in.readFloat();
                n.sx = in.readFloat();
                n.sy = in.readFloat();
                n.sz = in.readFloat();
                n.skin = ver == 1 ? in.readByte() : in.readShort();
                int primCount = in.readUnsignedShort();
                if (primCount > 256)
                    throw new IOException("Too many meshes on a node");
                for (int k = 0; k < primCount; k++) {
                    Prim p = new Prim();
                    int v = in.readInt();
                    if (v < 0 || v > vertexBudget)
                        throw new IOException("Too many vertices");
                    vertexBudget -= v;
                    p.pos = floatArray(in, v * 3, total);
                    p.nrm = floatArray(in, v * 3, total);
                    p.uv = floatArray(in, v * 2, total);
                    int indexCount = in.readInt();
                    if (indexCount < 0 || indexCount % 3 != 0 || indexCount / 3 > triangleBudget)
                        throw new IOException("Too many triangles");
                    triangleBudget -= indexCount / 3;
                    p.idx = new int[indexCount];
                    if (ver == 1 || v <= 65535) {
                        for (int x = 0; x < indexCount; x++)
                            p.idx[x] = in.readUnsignedShort();
                    } else {
                        for (int x = 0; x < indexCount; x++)
                            p.idx[x] = in.readInt();
                    }
                    if (in.readBoolean()) {
                        p.joints = new short[v * 4];
                        if (ver == 1) {
                            for (int x = 0; x < p.joints.length; x++)
                                p.joints[x] = (short) in.readUnsignedByte();
                        } else {
                            for (int x = 0; x < p.joints.length; x++)
                                p.joints[x] = in.readShort();
                        }
                        p.weights = floatArray(in, v * 4, total);
                    }
                    p.texture = ver == 1 ? in.readByte() : in.readShort();
                    p.cr = in.readFloat();
                    p.cg = in.readFloat();
                    p.cb = in.readFloat();
                    p.ca = in.readFloat();
                    p.alphaMode = in.readUnsignedByte();
                    p.doubleSided = in.readBoolean();
                    n.prims.add(p);
                }
                m.nodes.add(n);
            }

            int skinCount = in.readUnsignedByte();
            if (skinCount > MAX_SKINS)
                throw new IOException("Too many skins");
            for (int i = 0; i < skinCount; i++) {
                Skin s = new Skin();
                int joints = in.readUnsignedShort();
                if (joints > MAX_JOINTS)
                    throw new IOException("Too many joints");
                s.joints = new int[joints];
                for (int j = 0; j < joints; j++)
                    s.joints[j] = in.readUnsignedShort();
                s.inverseBind = floatArray(in, joints * 16, total);
                m.skins.add(s);
            }

            int textureCount = in.readUnsignedByte();
            if (textureCount > MAX_TEXTURES)
                throw new IOException("Too many textures");
            for (int i = 0; i < textureCount; i++) {
                Texture t = new Texture();
                t.name = string(in);
                t.width = in.readUnsignedShort();
                t.height = in.readUnsignedShort();
                int length = in.readInt();
                if (length < 0 || length > MAX_TEXTURE_BYTES || length > total)
                    throw new IOException("A texture is too large");
                t.png = new byte[length];
                in.readFully(t.png);
                m.textures.add(t);
            }

            int animationCount = in.readUnsignedByte();
            if (animationCount > MAX_ANIMATIONS)
                throw new IOException("Too many animations");
            int keyBudget = MAX_KEYS;
            for (int i = 0; i < animationCount; i++) {
                Animation a = new Animation();
                a.name = string(in);
                a.length = in.readFloat();
                a.loop = in.readUnsignedByte();
                int channelCount = in.readUnsignedShort();
                if (channelCount > MAX_CHANNELS)
                    throw new IOException("Too many channels");
                for (int c = 0; c < channelCount; c++) {
                    Channel ch = new Channel();
                    ch.node = in.readUnsignedShort();
                    ch.path = in.readUnsignedByte();
                    ch.interp = in.readUnsignedByte();
                    int keys = in.readInt();
                    if (keys < 0 || keys > keyBudget)
                        throw new IOException("Too many keyframes");
                    keyBudget -= keys;
                    ch.times = floatArray(in, keys, total);
                    ch.values = floatArray(in, keys * (ch.path == PATH_ROTATION ? 4 : 3), total);
                    a.channels.add(ch);
                }
                m.animations.add(a);
            }
            m.validate();
            return m;
        }
    }

    // ---------------------------------------------------------------- IO helpers

    private static void string(DataOutputStream out, String s) throws IOException {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > MAX_NAME * 4)
            b = java.util.Arrays.copyOf(b, MAX_NAME * 4);
        out.writeByte(b.length);
        out.write(b);
    }

    private static String string(DataInputStream in) throws IOException {
        byte[] b = new byte[in.readUnsignedByte()];
        in.readFully(b);
        String s = new String(b, StandardCharsets.UTF_8);
        return s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }

    private static void floats(DataOutputStream out, float... values) throws IOException {
        for (float f : values)
            out.writeFloat(f);
    }

    private static void floatArray(DataOutputStream out, float[] values) throws IOException {
        for (float f : values)
            out.writeFloat(f);
    }

    private static float[] floatArray(DataInputStream in, int count, int remainingCap) throws IOException {
        // Refuse a count the blob cannot possibly hold before allocating for it.
        if (count < 0 || count > remainingCap / 4 + 1)
            throw new IOException("A model array is larger than the blob");
        float[] out = new float[count];
        for (int i = 0; i < count; i++)
            out[i] = in.readFloat();
        return out;
    }
}
