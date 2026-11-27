package dev.watdafawx.foxstweaks.hologram.model;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads glTF 2.0 binary ({@code .glb}) and self-contained {@code .gltf} (data: URIs) into {@link ModelData}.
 * Triangles only. Every offset and count is bounds-checked against the buffers and the {@link ModelData} caps before
 * anything is allocated, since this parses bytes a player uploaded.
 */
public final class GlbReader {
    private static final int GLB_MAGIC = 0x46546C67; // "glTF"
    private static final int CHUNK_JSON = 0x4E4F534A;
    private static final int CHUNK_BIN = 0x004E4942;

    private final JsonObject root;
    private final byte[] bin;
    private final ModelData model = new ModelData();

    private GlbReader(JsonObject root, byte[] bin) {
        this.root = root;
        this.bin = bin;
    }

    public static boolean looksLikeGlb(byte[] b) {
        return b.length >= 12 && ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt(0) == GLB_MAGIC;
    }

    public static ModelData read(byte[] file) throws IOException {
        try {
            JsonObject json;
            byte[] bin = null;
            if (looksLikeGlb(file)) {
                ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
                int total = b.getInt(8);
                if (total < 12 || total > file.length)
                    throw new IOException("The .glb file is truncated");
                int pos = 12;
                String text = null;
                while (pos + 8 <= total) {
                    int len = b.getInt(pos);
                    int type = b.getInt(pos + 4);
                    pos += 8;
                    if (len < 0 || pos + (long) len > total)
                        throw new IOException("The .glb file has a bad chunk");
                    if (type == CHUNK_JSON && text == null)
                        text = new String(file, pos, len, StandardCharsets.UTF_8);
                    else if (type == CHUNK_BIN && bin == null) {
                        bin = new byte[len];
                        System.arraycopy(file, pos, bin, 0, len);
                    }
                    pos += (len + 3) & ~3;
                }
                if (text == null)
                    throw new IOException("The .glb file has no JSON chunk");
                json = JsonParser.parseString(text).getAsJsonObject();
            } else {
                json = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            GlbReader reader = new GlbReader(json, bin);
            reader.build();
            reader.model.validate();
            return reader.model;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Could not read the glTF model: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- structure

    private void build() throws IOException {
        JsonArray nodes = array(this.root, "nodes");
        int nodeCount = nodes.size();
        if (nodeCount == 0)
            throw new IOException("The model has no nodes");
        if (nodeCount > ModelData.MAX_NODES)
            throw new IOException("Too many bones/nodes (" + nodeCount + ", max " + ModelData.MAX_NODES + ")");

        // The scene's roots, else every node that is nobody's child.
        int[] parent = new int[nodeCount];
        java.util.Arrays.fill(parent, -1);
        for (int i = 0; i < nodeCount; i++)
            for (JsonElement c : array(nodes.get(i).getAsJsonObject(), "children")) {
                int ci = c.getAsInt();
                if (ci < 0 || ci >= nodeCount || parent[ci] != -1)
                    throw new IOException("The node tree is malformed");
                parent[ci] = i;
            }

        // Order parents before children (glTF does not guarantee it) and reject cycles.
        int[] order = new int[nodeCount];
        int[] newIndex = new int[nodeCount];
        java.util.Arrays.fill(newIndex, -1);
        int count = 0;
        List<Integer> queue = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++)
            if (parent[i] == -1)
                queue.add(i);
        for (int q = 0; q < queue.size(); q++) {
            int n = queue.get(q);
            order[count] = n;
            newIndex[n] = count++;
            for (JsonElement c : array(nodes.get(n).getAsJsonObject(), "children"))
                queue.add(c.getAsInt());
        }
        if (count != nodeCount)
            throw new IOException("The node tree has a cycle");

        List<ModelData.Texture> textures = new ArrayList<>();
        Map<Integer, Integer> textureMap = new HashMap<>();

        for (int k = 0; k < nodeCount; k++) {
            int src = order[k];
            JsonObject jn = nodes.get(src).getAsJsonObject();
            ModelData.Node n = new ModelData.Node();
            n.name = string(jn, "name", "node" + src);
            n.parent = parent[src] < 0 ? -1 : newIndex[parent[src]];
            readTransform(jn, n);
            n.skin = jn.has("skin") ? jn.get("skin").getAsInt() : -1;
            if (jn.has("mesh"))
                readMesh(jn.get("mesh").getAsInt(), n, textures, textureMap);
            this.model.nodes.add(n);
        }
        this.model.textures.addAll(textures);

        for (JsonElement s : array(this.root, "skins")) {
            if (this.model.skins.size() >= ModelData.MAX_SKINS)
                throw new IOException("Too many skins (max " + ModelData.MAX_SKINS + ")");
            JsonObject js = s.getAsJsonObject();
            JsonArray joints = array(js, "joints");
            if (joints.size() == 0 || joints.size() > ModelData.MAX_JOINTS)
                throw new IOException("A skin has too many joints (max " + ModelData.MAX_JOINTS + ")");
            ModelData.Skin skin = new ModelData.Skin();
            skin.joints = new int[joints.size()];
            for (int j = 0; j < skin.joints.length; j++) {
                int jn = joints.get(j).getAsInt();
                if (jn < 0 || jn >= nodeCount)
                    throw new IOException("A skin joint points at a missing node");
                skin.joints[j] = newIndex[jn];
            }
            skin.inverseBind = new float[skin.joints.length * 16];
            if (js.has("inverseBindMatrices")) {
                float[] ibm = readAccessor(js.get("inverseBindMatrices").getAsInt(), 16, ModelData.MAX_JOINTS * 16);
                if (ibm.length < skin.inverseBind.length)
                    throw new IOException("A skin has too few inverse bind matrices");
                System.arraycopy(ibm, 0, skin.inverseBind, 0, skin.inverseBind.length);
            } else {
                for (int j = 0; j < skin.joints.length; j++) {
                    skin.inverseBind[j * 16] = 1;
                    skin.inverseBind[j * 16 + 5] = 1;
                    skin.inverseBind[j * 16 + 10] = 1;
                    skin.inverseBind[j * 16 + 15] = 1;
                }
            }
            this.model.skins.add(skin);
        }

        for (JsonElement a : array(this.root, "animations")) {
            if (this.model.animations.size() >= ModelData.MAX_ANIMATIONS)
                break;
            readAnimation(a.getAsJsonObject(), newIndex);
        }
    }

    private static void readTransform(JsonObject jn, ModelData.Node n) throws IOException {
        if (jn.has("matrix")) {
            JsonArray m = jn.getAsJsonArray("matrix");
            if (m.size() != 16)
                throw new IOException("A node matrix is malformed");
            float[] f = new float[16];
            for (int i = 0; i < 16; i++)
                f[i] = m.get(i).getAsFloat();
            decompose(f, n);
            return;
        }
        if (jn.has("translation")) {
            JsonArray t = jn.getAsJsonArray("translation");
            n.tx = t.get(0).getAsFloat();
            n.ty = t.get(1).getAsFloat();
            n.tz = t.get(2).getAsFloat();
        }
        if (jn.has("rotation")) {
            JsonArray r = jn.getAsJsonArray("rotation");
            n.rx = r.get(0).getAsFloat();
            n.ry = r.get(1).getAsFloat();
            n.rz = r.get(2).getAsFloat();
            n.rw = r.get(3).getAsFloat();
        }
        if (jn.has("scale")) {
            JsonArray s = jn.getAsJsonArray("scale");
            n.sx = s.get(0).getAsFloat();
            n.sy = s.get(1).getAsFloat();
            n.sz = s.get(2).getAsFloat();
        }
    }

    /** TRS from a column-major matrix (no shear support, which glTF forbids anyway). */
    static void decompose(float[] m, ModelData.Node n) {
        n.tx = m[12];
        n.ty = m[13];
        n.tz = m[14];
        float sx = (float) Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        float sy = (float) Math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6]);
        float sz = (float) Math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10]);
        n.sx = sx;
        n.sy = sy;
        n.sz = sz;
        if (sx < 1.0E-9F || sy < 1.0E-9F || sz < 1.0E-9F)
            return;
        float r00 = m[0] / sx, r10 = m[1] / sx, r20 = m[2] / sx;
        float r01 = m[4] / sy, r11 = m[5] / sy, r21 = m[6] / sy;
        float r02 = m[8] / sz, r12 = m[9] / sz, r22 = m[10] / sz;
        float trace = r00 + r11 + r22;
        float x, y, z, w;
        if (trace > 0) {
            float s = (float) Math.sqrt(trace + 1.0F) * 2;
            w = 0.25F * s;
            x = (r21 - r12) / s;
            y = (r02 - r20) / s;
            z = (r10 - r01) / s;
        } else if (r00 > r11 && r00 > r22) {
            float s = (float) Math.sqrt(1.0F + r00 - r11 - r22) * 2;
            w = (r21 - r12) / s;
            x = 0.25F * s;
            y = (r01 + r10) / s;
            z = (r02 + r20) / s;
        } else if (r11 > r22) {
            float s = (float) Math.sqrt(1.0F + r11 - r00 - r22) * 2;
            w = (r02 - r20) / s;
            x = (r01 + r10) / s;
            y = 0.25F * s;
            z = (r12 + r21) / s;
        } else {
            float s = (float) Math.sqrt(1.0F + r22 - r00 - r11) * 2;
            w = (r10 - r01) / s;
            x = (r02 + r20) / s;
            y = (r12 + r21) / s;
            z = 0.25F * s;
        }
        n.rx = x;
        n.ry = y;
        n.rz = z;
        n.rw = w;
    }

    // ---------------------------------------------------------------- meshes

    private void readMesh(int meshIndex, ModelData.Node node, List<ModelData.Texture> textures, Map<Integer, Integer> textureMap)
            throws IOException {
        JsonArray meshes = array(this.root, "meshes");
        if (meshIndex < 0 || meshIndex >= meshes.size())
            throw new IOException("A node points at a missing mesh");
        int vertexTotal = 0;
        for (JsonElement pe : array(meshes.get(meshIndex).getAsJsonObject(), "primitives")) {
            JsonObject jp = pe.getAsJsonObject();
            int mode = jp.has("mode") ? jp.get("mode").getAsInt() : 4;
            if (mode != 4)
                continue; // points, lines and strips are not drawn
            JsonObject attrs = jp.getAsJsonObject("attributes");
            if (attrs == null || !attrs.has("POSITION"))
                continue;

            ModelData.Prim p = new ModelData.Prim();
            p.pos = readAccessor(attrs.get("POSITION").getAsInt(), 3, ModelData.MAX_VERTICES * 3);
            int v = p.pos.length / 3;
            vertexTotal += v;
            if (vertexTotal > ModelData.MAX_VERTICES)
                throw new IOException("Too many vertices (max " + ModelData.MAX_VERTICES + "); reduce the model's detail");

            int[] idx;
            if (jp.has("indices")) {
                idx = readIndices(jp.get("indices").getAsInt());
            } else {
                idx = new int[v - v % 3];
                for (int i = 0; i < idx.length; i++)
                    idx[i] = i;
            }
            if (idx.length % 3 != 0)
                idx = java.util.Arrays.copyOf(idx, idx.length - idx.length % 3);
            for (int i : idx)
                if (i < 0 || i >= v)
                    throw new IOException("A mesh index points past its vertices");
            p.idx = idx;

            if (attrs.has("NORMAL")) {
                p.nrm = readAccessor(attrs.get("NORMAL").getAsInt(), 3, v * 3);
                if (p.nrm.length != v * 3)
                    throw new IOException("A mesh has mismatched normals");
            } else {
                p.nrm = flatNormals(p.pos, idx);
            }
            if (attrs.has("TEXCOORD_0")) {
                p.uv = readAccessor(attrs.get("TEXCOORD_0").getAsInt(), 2, v * 2);
                if (p.uv.length != v * 2)
                    throw new IOException("A mesh has mismatched UVs");
            } else {
                p.uv = new float[v * 2];
            }
            if (attrs.has("JOINTS_0") && attrs.has("WEIGHTS_0") && node.skin >= 0) {
                float[] j = readAccessor(attrs.get("JOINTS_0").getAsInt(), 4, v * 4);
                float[] w = readAccessor(attrs.get("WEIGHTS_0").getAsInt(), 4, v * 4);
                if (j.length == v * 4 && w.length == v * 4) {
                    p.joints = new short[v * 4];
                    for (int i = 0; i < j.length; i++)
                        p.joints[i] = (short) Math.max(0, Math.min(ModelData.MAX_JOINTS - 1, Math.round(j[i])));
                    p.weights = w;
                }
            }
            if (jp.has("material"))
                readMaterial(jp.get("material").getAsInt(), p, textures, textureMap);
            node.prims.add(p);
        }
    }

    private static float[] flatNormals(float[] pos, int[] idx) {
        float[] nrm = new float[pos.length];
        for (int t = 0; t + 2 < idx.length; t += 3) {
            int a = idx[t] * 3, b = idx[t + 1] * 3, c = idx[t + 2] * 3;
            float ux = pos[b] - pos[a], uy = pos[b + 1] - pos[a + 1], uz = pos[b + 2] - pos[a + 2];
            float vx = pos[c] - pos[a], vy = pos[c + 1] - pos[a + 1], vz = pos[c + 2] - pos[a + 2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            for (int k : new int[] { a, b, c }) {
                nrm[k] += nx;
                nrm[k + 1] += ny;
                nrm[k + 2] += nz;
            }
        }
        for (int i = 0; i < nrm.length; i += 3) {
            float len = (float) Math.sqrt(nrm[i] * nrm[i] + nrm[i + 1] * nrm[i + 1] + nrm[i + 2] * nrm[i + 2]);
            if (len > 1.0E-9F) {
                nrm[i] /= len;
                nrm[i + 1] /= len;
                nrm[i + 2] /= len;
            } else {
                nrm[i + 1] = 1;
            }
        }
        return nrm;
    }

    private void readMaterial(int index, ModelData.Prim p, List<ModelData.Texture> textures, Map<Integer, Integer> textureMap)
            throws IOException {
        JsonArray mats = array(this.root, "materials");
        if (index < 0 || index >= mats.size())
            return;
        JsonObject jm = mats.get(index).getAsJsonObject();
        p.doubleSided = jm.has("doubleSided") && jm.get("doubleSided").getAsBoolean();
        String mode = string(jm, "alphaMode", "OPAQUE");
        p.alphaMode = mode.equals("BLEND") ? ModelData.ALPHA_BLEND : mode.equals("MASK") ? ModelData.ALPHA_MASK : ModelData.ALPHA_OPAQUE;
        JsonObject pbr = jm.getAsJsonObject("pbrMetallicRoughness");
        if (pbr == null)
            return;
        if (pbr.has("baseColorFactor")) {
            JsonArray c = pbr.getAsJsonArray("baseColorFactor");
            p.cr = c.get(0).getAsFloat();
            p.cg = c.get(1).getAsFloat();
            p.cb = c.get(2).getAsFloat();
            p.ca = c.get(3).getAsFloat();
        }
        JsonObject bct = pbr.getAsJsonObject("baseColorTexture");
        if (bct != null && bct.has("index")) {
            int ti = bct.get("index").getAsInt();
            Integer mapped = textureMap.get(ti);
            if (mapped == null) {
                if (textures.size() >= ModelData.MAX_TEXTURES)
                    throw new IOException("Too many textures (max " + ModelData.MAX_TEXTURES + ")");
                ModelData.Texture t = loadTexture(ti);
                if (t == null) {
                    textureMap.put(ti, -1);
                    mapped = -1;
                } else {
                    textures.add(t);
                    mapped = textures.size() - 1;
                    textureMap.put(ti, mapped);
                }
            }
            p.texture = mapped;
        }
    }

    private ModelData.Texture loadTexture(int textureIndex) throws IOException {
        JsonArray textures = array(this.root, "textures");
        if (textureIndex < 0 || textureIndex >= textures.size())
            return null;
        JsonObject jt = textures.get(textureIndex).getAsJsonObject();
        if (!jt.has("source"))
            return null;
        JsonArray images = array(this.root, "images");
        int si = jt.get("source").getAsInt();
        if (si < 0 || si >= images.size())
            return null;
        JsonObject ji = images.get(si).getAsJsonObject();
        byte[] data;
        if (ji.has("bufferView")) {
            data = bufferViewBytes(ji.get("bufferView").getAsInt());
        } else if (ji.has("uri")) {
            data = dataUri(ji.get("uri").getAsString());
        } else {
            return null;
        }
        return ModelTextures.fromBytes(string(ji, "name", "texture" + si), data);
    }

    // ---------------------------------------------------------------- animation

    private void readAnimation(JsonObject ja, int[] newIndex) throws IOException {
        ModelData.Animation anim = new ModelData.Animation();
        anim.name = string(ja, "name", "animation" + this.model.animations.size());
        anim.loop = ModelData.LOOP_LOOP;
        JsonArray samplers = array(ja, "samplers");
        float length = 0.0F;
        for (JsonElement ce : array(ja, "channels")) {
            if (anim.channels.size() >= ModelData.MAX_CHANNELS)
                break;
            JsonObject jc = ce.getAsJsonObject();
            JsonObject target = jc.getAsJsonObject("target");
            if (target == null || !target.has("node") || !target.has("path"))
                continue;
            int node = target.get("node").getAsInt();
            if (node < 0 || node >= newIndex.length)
                continue;
            String path = target.get("path").getAsString();
            int pathId = path.equals("translation") ? ModelData.PATH_TRANSLATION
                    : path.equals("rotation") ? ModelData.PATH_ROTATION
                    : path.equals("scale") ? ModelData.PATH_SCALE : -1;
            if (pathId < 0)
                continue; // morph weights are not supported
            int si = jc.get("sampler").getAsInt();
            if (si < 0 || si >= samplers.size())
                throw new IOException("An animation channel points at a missing sampler");
            JsonObject js = samplers.get(si).getAsJsonObject();
            String interp = string(js, "interpolation", "LINEAR");
            int width = pathId == ModelData.PATH_ROTATION ? 4 : 3;

            float[] times = readAccessor(js.get("input").getAsInt(), 1, ModelData.MAX_KEYS);
            boolean cubic = interp.equals("CUBICSPLINE");
            float[] values = readAccessor(js.get("output").getAsInt(), width, ModelData.MAX_KEYS * width * (cubic ? 3 : 1));
            if (times.length == 0)
                continue;
            if (cubic) {
                // [in-tangent, value, out-tangent] per key: keep the values, which is linear between keys.
                if (values.length < times.length * width * 3)
                    throw new IOException("An animation sampler is malformed");
                float[] kept = new float[times.length * width];
                for (int k = 0; k < times.length; k++)
                    System.arraycopy(values, (k * 3 + 1) * width, kept, k * width, width);
                values = kept;
            } else if (values.length < times.length * width) {
                throw new IOException("An animation sampler is malformed");
            } else if (values.length > times.length * width) {
                values = java.util.Arrays.copyOf(values, times.length * width);
            }
            ModelData.Channel c = new ModelData.Channel();
            c.node = newIndex[node];
            c.path = pathId;
            c.interp = interp.equals("STEP") ? ModelData.INTERP_STEP : ModelData.INTERP_LINEAR;
            c.times = times;
            c.values = values;
            length = Math.max(length, times[times.length - 1]);
            anim.channels.add(c);
        }
        anim.length = length;
        if (!anim.channels.isEmpty())
            this.model.animations.add(anim);
    }

    // ---------------------------------------------------------------- accessors

    private byte[] bufferViewBytes(int view) throws IOException {
        JsonArray views = array(this.root, "bufferViews");
        if (view < 0 || view >= views.size())
            throw new IOException("A buffer view is missing");
        JsonObject jv = views.get(view).getAsJsonObject();
        byte[] buffer = bufferBytes(jv.has("buffer") ? jv.get("buffer").getAsInt() : 0);
        long off = jv.has("byteOffset") ? jv.get("byteOffset").getAsLong() : 0;
        long len = jv.get("byteLength").getAsLong();
        if (off < 0 || len < 0 || off + len > buffer.length)
            throw new IOException("A buffer view runs past its buffer");
        byte[] out = new byte[(int) len];
        System.arraycopy(buffer, (int) off, out, 0, out.length);
        return out;
    }

    private final Map<Integer, byte[]> buffers = new HashMap<>();

    private byte[] bufferBytes(int index) throws IOException {
        if (index == 0 && this.bin != null)
            return this.bin;
        byte[] cached = this.buffers.get(index);
        if (cached != null)
            return cached;
        JsonArray bufs = array(this.root, "buffers");
        if (index < 0 || index >= bufs.size())
            throw new IOException("A buffer is missing");
        JsonObject jb = bufs.get(index).getAsJsonObject();
        if (!jb.has("uri"))
            throw new IOException("The model refers to an external file; use a .glb with everything inside");
        byte[] data = dataUri(jb.get("uri").getAsString());
        this.buffers.put(index, data);
        return data;
    }

    private static byte[] dataUri(String uri) throws IOException {
        if (!uri.startsWith("data:"))
            throw new IOException("The model refers to an external file; use a .glb with everything inside");
        int comma = uri.indexOf(',');
        if (comma < 0 || !uri.substring(0, comma).endsWith(";base64"))
            throw new IOException("Unsupported data URI in the model");
        try {
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        } catch (IllegalArgumentException e) {
            throw new IOException("A data URI in the model is not valid base64");
        }
    }

    /**
     * Reads an accessor into floats (normalised integers become 0..1 / -1..1). {@code components} is checked against
     * the accessor's type, {@code maxElements} caps the element count (times components) before allocating.
     */
    private float[] readAccessor(int index, int components, int maxFloats) throws IOException {
        JsonArray accs = array(this.root, "accessors");
        if (index < 0 || index >= accs.size())
            throw new IOException("An accessor is missing");
        JsonObject ja = accs.get(index).getAsJsonObject();
        int count = ja.get("count").getAsInt();
        int type = ja.get("componentType").getAsInt();
        String kind = ja.get("type").getAsString();
        int width = switch (kind) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IOException("Unsupported accessor type " + kind);
        };
        if (width != components)
            throw new IOException("An accessor has the wrong shape");
        if (count < 0 || (long) count * width > maxFloats)
            throw new IOException("An accessor is larger than the model limits allow");
        boolean normalized = ja.has("normalized") && ja.get("normalized").getAsBoolean();
        int size = componentSize(type);
        float[] out = new float[count * width];
        if (!ja.has("bufferView"))
            return out; // all zeros
        JsonArray views = array(this.root, "bufferViews");
        int vi = ja.get("bufferView").getAsInt();
        if (vi < 0 || vi >= views.size())
            throw new IOException("An accessor points at a missing buffer view");
        JsonObject jv = views.get(vi).getAsJsonObject();
        byte[] buffer = bufferBytes(jv.has("buffer") ? jv.get("buffer").getAsInt() : 0);
        long viewOff = jv.has("byteOffset") ? jv.get("byteOffset").getAsLong() : 0;
        long viewLen = jv.get("byteLength").getAsLong();
        long stride = jv.has("byteStride") ? jv.get("byteStride").getAsLong() : (long) size * width;
        long accOff = ja.has("byteOffset") ? ja.get("byteOffset").getAsLong() : 0;
        if (viewOff < 0 || viewLen < 0 || viewOff + viewLen > buffer.length || stride < (long) size * width && stride != 0)
            throw new IOException("A buffer view runs past its buffer");
        ByteBuffer bb = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < count; i++) {
            long base = viewOff + accOff + i * stride;
            if (base + (long) size * width > viewOff + viewLen)
                throw new IOException("An accessor runs past its buffer view");
            for (int k = 0; k < width; k++)
                out[i * width + k] = component(bb, (int) (base + (long) k * size), type, normalized);
        }
        // Sparse accessors are rare for exported models and not applied.
        return out;
    }

    private int[] readIndices(int index) throws IOException {
        float[] f = readAccessorRaw(index);
        int[] out = new int[f.length];
        for (int i = 0; i < f.length; i++)
            out[i] = (int) f[i];
        return out;
    }

    private float[] readAccessorRaw(int index) throws IOException {
        return readAccessor(index, 1, ModelData.MAX_TRIANGLES * 3);
    }

    private static int componentSize(int type) throws IOException {
        return switch (type) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            case 5125, 5126 -> 4;
            default -> throw new IOException("Unsupported component type " + type);
        };
    }

    private static float component(ByteBuffer bb, int at, int type, boolean normalized) {
        switch (type) {
            case 5120: {
                byte b = bb.get(at);
                return normalized ? Math.max(-1.0F, b / 127.0F) : b;
            }
            case 5121: {
                int b = bb.get(at) & 0xFF;
                return normalized ? b / 255.0F : b;
            }
            case 5122: {
                short s = bb.getShort(at);
                return normalized ? Math.max(-1.0F, s / 32767.0F) : s;
            }
            case 5123: {
                int s = bb.getShort(at) & 0xFFFF;
                return normalized ? s / 65535.0F : s;
            }
            case 5125:
                return (float) (bb.getInt(at) & 0xFFFFFFFFL);
            default:
                return bb.getFloat(at);
        }
    }

    // ---------------------------------------------------------------- json helpers

    private static final JsonArray NONE = new JsonArray();

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : NONE;
    }

    private static String string(JsonObject o, String key, String fallback) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : fallback;
    }
}
