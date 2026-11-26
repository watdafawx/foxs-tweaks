package dev.watdafawx.foxstweaks.hologram.model;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Reads Blockbench {@code .bbmodel} (generic / Java block / entity models): cube and mesh elements, the group tree,
 * embedded textures and keyframe animations.
 *
 * <p>Conventions, from Blockbench's own source: units are 1/16 block (divided by 16 here); a group's pivot is its
 * {@code origin}, its node sits at {@code origin - parent origin}; a cube's geometry is {@code from/to - own origin};
 * rotations are Euler degrees applied in Z, Y, X order ({@code Rz*Ry*Rx}); animation rotation is ADDED to the rest
 * rotation per axis, position is added, scale is multiplied. The player uses glTF's replace-style channels, so each
 * animated bone's Euler sum is baked into quaternion keys here (long turns get extra keys so slerp cannot take the
 * short way round). Catmull-Rom and Bezier keys are played as linear.
 */
public final class BlockbenchReader {
    private static final float UNIT = 1.0F / 16.0F;

    private final JsonObject root;
    private final ModelData model = new ModelData();
    private final Map<String, JsonObject> elements = new HashMap<>();
    private final Set<String> animated = new HashSet<>();
    /** Node index per element/group uuid, for animation targeting. */
    private final Map<String, Integer> nodeOf = new HashMap<>();
    private final Map<String, Integer> nodeByName = new HashMap<>();
    private final List<float[]> restOrigin = new ArrayList<>();
    private final List<float[]> restRotation = new ArrayList<>();
    private int[] textureSize = new int[0];
    private int resW = 16, resH = 16;

    private BlockbenchReader(JsonObject root) {
        this.root = root;
    }

    public static boolean looksLikeBbmodel(byte[] b) {
        int n = Math.min(b.length, 4096);
        String head = new String(b, 0, n, StandardCharsets.UTF_8);
        return head.stripLeading().startsWith("{") && (head.contains("\"elements\"") || head.contains("\"outliner\"") || head.contains("\"meta\""));
    }

    public static ModelData read(byte[] file) throws IOException {
        try {
            JsonObject json = JsonParser.parseString(new String(file, StandardCharsets.UTF_8)).getAsJsonObject();
            BlockbenchReader r = new BlockbenchReader(json);
            r.build();
            r.model.validate();
            return r.model;
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IOException("Could not read the Blockbench model: " + e.getMessage(), e);
        }
    }

    private void build() throws IOException {
        JsonObject res = this.root.has("resolution") ? this.root.getAsJsonObject("resolution") : null;
        if (res != null) {
            this.resW = Math.max(1, res.get("width").getAsInt());
            this.resH = Math.max(1, res.get("height").getAsInt());
        }
        readTextures();
        for (JsonElement e : arr(this.root, "elements")) {
            JsonObject o = e.getAsJsonObject();
            if (o.has("uuid"))
                this.elements.put(o.get("uuid").getAsString(), o);
        }
        collectAnimated();

        float[] zero = { 0, 0, 0 };
        for (JsonElement c : arr(this.root, "outliner"))
            node(c, -1, zero);
        if (this.model.nodes.isEmpty())
            throw new IOException("The Blockbench model has no geometry");
        readAnimations();
    }

    // ---------------------------------------------------------------- textures

    private void readTextures() throws IOException {
        List<int[]> sizes = new ArrayList<>();
        for (JsonElement e : arr(this.root, "textures")) {
            if (this.model.textures.size() >= ModelData.MAX_TEXTURES)
                throw new IOException("Too many textures (max " + ModelData.MAX_TEXTURES + ")");
            JsonObject jt = e.getAsJsonObject();
            String src = jt.has("source") ? jt.get("source").getAsString() : "";
            int comma = src.indexOf(',');
            if (!src.startsWith("data:") || comma < 0)
                throw new IOException("The model's textures are not embedded; save the .bbmodel with textures included");
            byte[] bytes;
            try {
                bytes = Base64.getDecoder().decode(src.substring(comma + 1));
            } catch (IllegalArgumentException ex) {
                throw new IOException("A texture in the model is not valid base64");
            }
            ModelData.Texture t = ModelTextures.fromBytes(jt.has("name") ? jt.get("name").getAsString() : "texture", bytes);
            this.model.textures.add(t);
            int uw = jt.has("uv_width") ? jt.get("uv_width").getAsInt() : this.resW;
            int uh = jt.has("uv_height") ? jt.get("uv_height").getAsInt() : this.resH;
            sizes.add(new int[] { Math.max(1, uw), Math.max(1, uh) });
        }
        this.textureSize = new int[sizes.size() * 2];
        for (int i = 0; i < sizes.size(); i++) {
            this.textureSize[i * 2] = sizes.get(i)[0];
            this.textureSize[i * 2 + 1] = sizes.get(i)[1];
        }
    }

    private void collectAnimated() {
        for (JsonElement a : arr(this.root, "animations"))
            for (Map.Entry<String, JsonElement> en : a.getAsJsonObject().has("animators")
                    ? a.getAsJsonObject().getAsJsonObject("animators").entrySet() : new JsonObject().entrySet())
                this.animated.add(en.getKey());
    }

    // ---------------------------------------------------------------- tree

    /**
     * Adds the node for an outliner entry (a cube/mesh uuid or a group object) and its children.
     * {@code groupOrigin} is the nearest enclosing node's pivot, in pixels.
     */
    private void node(JsonElement entry, int parent, float[] parentOrigin) throws IOException {
        if (entry.isJsonPrimitive()) {
            JsonObject el = this.elements.get(entry.getAsString());
            if (el == null)
                return;
            element(entry.getAsString(), el, parent, parentOrigin);
            return;
        }
        JsonObject g = entry.getAsJsonObject();
        if (this.model.nodes.size() >= ModelData.MAX_NODES)
            throw new IOException("Too many bones/nodes (max " + ModelData.MAX_NODES + ")");
        float[] origin = vec(g, "origin");
        float[] rot = vec(g, "rotation");
        ModelData.Node n = newNode(g.has("name") ? g.get("name").getAsString() : "group", parent, origin, parentOrigin, rot);
        int index = this.model.nodes.size() - 1;
        if (g.has("uuid"))
            this.nodeOf.put(g.get("uuid").getAsString(), index);
        this.nodeByName.putIfAbsent(n.name.toLowerCase(java.util.Locale.ROOT), index);
        for (JsonElement c : arr(g, "children"))
            node(c, index, origin);
    }

    private ModelData.Node newNode(String name, int parent, float[] origin, float[] parentOrigin, float[] rot) {
        ModelData.Node n = new ModelData.Node();
        n.name = name;
        n.parent = parent;
        n.tx = (origin[0] - parentOrigin[0]) * UNIT;
        n.ty = (origin[1] - parentOrigin[1]) * UNIT;
        n.tz = (origin[2] - parentOrigin[2]) * UNIT;
        float[] q = ModelPose.eulerZyx(rot[0], rot[1], rot[2]);
        n.rx = q[0];
        n.ry = q[1];
        n.rz = q[2];
        n.rw = q[3];
        this.model.nodes.add(n);
        this.restOrigin.add(new float[] { n.tx, n.ty, n.tz });
        this.restRotation.add(rot);
        return n;
    }

    private void element(String uuid, JsonObject el, int parent, float[] parentOrigin) throws IOException {
        if (el.has("visibility") && !el.get("visibility").getAsBoolean())
            return;
        String type = el.has("type") ? el.get("type").getAsString() : "cube";
        float[] origin = vec(el, "origin");
        float[] rot = vec(el, "rotation");
        boolean rotated = rot[0] != 0 || rot[1] != 0 || rot[2] != 0;
        boolean own = rotated || this.animated.contains(uuid) || type.equals("mesh") || parent < 0;
        ModelData.Node target;
        float[] offset;
        if (own) {
            if (this.model.nodes.size() >= ModelData.MAX_NODES)
                throw new IOException("Too many bones/nodes (max " + ModelData.MAX_NODES + ")");
            target = newNode(el.has("name") ? el.get("name").getAsString() : type, parent, origin, parentOrigin, rot);
            this.nodeOf.put(uuid, this.model.nodes.size() - 1);
            offset = new float[] { 0, 0, 0 };
        } else {
            // Unrotated, unanimated cube: its geometry goes straight into the enclosing group, in that group's frame.
            target = this.model.nodes.get(parent);
            offset = parentOrigin;
        }
        if (type.equals("cube"))
            cube(el, target, own ? origin : offset, own);
        else if (type.equals("mesh"))
            mesh(el, target);
    }

    // ---------------------------------------------------------------- cubes

    private void cube(JsonObject el, ModelData.Node node, float[] pivot, boolean own) {
        float[] from = vec(el, "from"), to = vec(el, "to");
        float inflate = el.has("inflate") ? el.get("inflate").getAsFloat() : 0.0F;
        float[] o = vec(el, "origin");
        // Own node: geometry relative to the cube's origin. Merged: relative to the group's origin (`pivot`).
        float[] rel = own ? o : pivot;
        float x0 = (from[0] - inflate - rel[0]) * UNIT, y0 = (from[1] - inflate - rel[1]) * UNIT, z0 = (from[2] - inflate - rel[2]) * UNIT;
        float x1 = (to[0] + inflate - rel[0]) * UNIT, y1 = (to[1] + inflate - rel[1]) * UNIT, z1 = (to[2] + inflate - rel[2]) * UNIT;
        if (x1 == x0)
            x1 += 0.001F * UNIT;
        if (y1 == y0)
            y1 += 0.001F * UNIT;
        if (z1 == z0)
            z1 += 0.001F * UNIT;
        JsonObject faces = el.has("faces") ? el.getAsJsonObject("faces") : new JsonObject();
        boolean boxUv = el.has("box_uv") && el.get("box_uv").getAsBoolean();
        float[] uvOffset = el.has("uv_offset") ? vec2(el, "uv_offset") : new float[] { 0, 0 };
        float w = Math.abs(to[0] - from[0]), h = Math.abs(to[1] - from[1]), d = Math.abs(to[2] - from[2]);

        // {TL, TR, BR, BL} corner positions per face, seen from outside (counter-clockwise TL, BL, BR, TR).
        String[] names = { "north", "south", "east", "west", "up", "down" };
        float[][][] corners = {
                { { x1, y1, z0 }, { x0, y1, z0 }, { x0, y0, z0 }, { x1, y0, z0 } }, // north
                { { x0, y1, z1 }, { x1, y1, z1 }, { x1, y0, z1 }, { x0, y0, z1 } }, // south
                { { x1, y1, z1 }, { x1, y1, z0 }, { x1, y0, z0 }, { x1, y0, z1 } }, // east
                { { x0, y1, z0 }, { x0, y1, z1 }, { x0, y0, z1 }, { x0, y0, z0 } }, // west
                { { x0, y1, z0 }, { x1, y1, z0 }, { x1, y1, z1 }, { x0, y1, z1 } }, // up
                { { x0, y0, z1 }, { x1, y0, z1 }, { x1, y0, z0 }, { x0, y0, z0 } }, // down
        };
        float[][] normals = { { 0, 0, -1 }, { 0, 0, 1 }, { 1, 0, 0 }, { -1, 0, 0 }, { 0, 1, 0 }, { 0, -1, 0 } };

        for (int f = 0; f < 6; f++) {
            JsonObject face = faces.has(names[f]) && faces.get(names[f]).isJsonObject() ? faces.getAsJsonObject(names[f]) : null;
            if (face == null)
                continue;
            int tex = textureIndex(face);
            if (tex < 0)
                continue;
            float[] uv;
            if (face.has("uv") && face.get("uv").isJsonArray() && face.getAsJsonArray("uv").size() == 4) {
                uv = new float[4];
                for (int i = 0; i < 4; i++)
                    uv[i] = face.getAsJsonArray("uv").get(i).getAsFloat();
            } else if (boxUv) {
                uv = boxUv(f, w, h, d, uvOffset);
            } else {
                continue;
            }
            int rotation = face.has("rotation") ? ((face.get("rotation").getAsInt() / 90) % 4 + 4) % 4 : 0;
            float tw = Math.max(1, this.textureSize.length > tex * 2 ? this.textureSize[tex * 2] : this.resW);
            float th = Math.max(1, this.textureSize.length > tex * 2 ? this.textureSize[tex * 2 + 1] : this.resH);
            float[][] cuv = {
                    { uv[0] / tw, uv[1] / th }, { uv[2] / tw, uv[1] / th }, { uv[2] / tw, uv[3] / th }, { uv[0] / tw, uv[3] / th } };
            ModelData.Prim p = prim(node, tex);
            addQuad(p, corners[f], normals[f], cuv, rotation);
        }
    }

    /** Blockbench's box-UV layout (uv = [x1, y1, x2, y2]); only used when a face carries no uv of its own. */
    private static float[] boxUv(int face, float w, float h, float d, float[] off) {
        float ox = off[0], oy = off[1];
        return switch (face) {
            case 2 -> new float[] { ox, oy + d, ox + d, oy + d + h }; // east
            case 3 -> new float[] { ox + d + w, oy + d, ox + d + w + d, oy + d + h }; // west
            case 4 -> new float[] { ox + d + w, oy + d, ox + d, oy }; // up
            case 5 -> new float[] { ox + d + w * 2, oy, ox + d + w, oy + d }; // down
            case 1 -> new float[] { ox + d * 2 + w, oy + d, ox + d * 2 + w * 2, oy + d + h }; // south
            default -> new float[] { ox + d, oy + d, ox + d + w, oy + d + h }; // north
        };
    }

    private int textureIndex(JsonObject face) {
        JsonElement t = face.get("texture");
        if (t == null)
            return this.model.textures.isEmpty() ? -1 : 0;
        if (t.isJsonNull() || (t.isJsonPrimitive() && t.getAsJsonPrimitive().isBoolean()))
            return -1;
        int i = t.getAsInt();
        return i >= 0 && i < this.model.textures.size() ? i : -1;
    }

    private static ModelData.Prim prim(ModelData.Node node, int texture) {
        for (ModelData.Prim p : node.prims)
            if (p.texture == texture)
                return p;
        ModelData.Prim p = new ModelData.Prim();
        p.texture = texture;
        p.alphaMode = ModelData.ALPHA_MASK;
        p.doubleSided = false;
        node.prims.add(p);
        return p;
    }

    private static void addQuad(ModelData.Prim p, float[][] c, float[] n, float[][] uv, int rotation) {
        int base = p.pos.length / 3;
        float[] pos = java.util.Arrays.copyOf(p.pos, p.pos.length + 12);
        float[] nrm = java.util.Arrays.copyOf(p.nrm, p.nrm.length + 12);
        float[] tex = java.util.Arrays.copyOf(p.uv, p.uv.length + 8);
        for (int i = 0; i < 4; i++) {
            int at = (base + i) * 3;
            pos[at] = c[i][0];
            pos[at + 1] = c[i][1];
            pos[at + 2] = c[i][2];
            nrm[at] = n[0];
            nrm[at + 1] = n[1];
            nrm[at + 2] = n[2];
            float[] t = uv[((i - rotation) % 4 + 4) % 4];
            tex[(base + i) * 2] = t[0];
            tex[(base + i) * 2 + 1] = t[1];
        }
        int[] idx = java.util.Arrays.copyOf(p.idx, p.idx.length + 6);
        int k = p.idx.length;
        // TL, BL, BR and TL, BR, TR are counter-clockwise from outside.
        idx[k] = base;
        idx[k + 1] = base + 3;
        idx[k + 2] = base + 2;
        idx[k + 3] = base;
        idx[k + 4] = base + 2;
        idx[k + 5] = base + 1;
        p.pos = pos;
        p.nrm = nrm;
        p.uv = tex;
        p.idx = idx;
    }

    // ---------------------------------------------------------------- meshes

    private void mesh(JsonObject el, ModelData.Node node) {
        JsonObject verts = el.has("vertices") ? el.getAsJsonObject("vertices") : new JsonObject();
        JsonObject faces = el.has("faces") ? el.getAsJsonObject("faces") : new JsonObject();
        for (Map.Entry<String, JsonElement> fe : faces.entrySet()) {
            JsonObject face = fe.getValue().getAsJsonObject();
            int tex = textureIndex(face);
            if (tex < 0 || !face.has("vertices"))
                continue;
            JsonArray keys = face.getAsJsonArray("vertices");
            int n = keys.size();
            if (n < 3)
                continue;
            JsonObject uvs = face.has("uv") ? face.getAsJsonObject("uv") : new JsonObject();
            float tw = Math.max(1, this.textureSize.length > tex * 2 ? this.textureSize[tex * 2] : this.resW);
            float th = Math.max(1, this.textureSize.length > tex * 2 ? this.textureSize[tex * 2 + 1] : this.resH);
            float[][] pts = new float[n][];
            float[][] tc = new float[n][];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                String key = keys.get(i).getAsString();
                if (!verts.has(key)) {
                    ok = false;
                    break;
                }
                JsonArray v = verts.getAsJsonArray(key);
                pts[i] = new float[] { v.get(0).getAsFloat() * UNIT, v.get(1).getAsFloat() * UNIT, v.get(2).getAsFloat() * UNIT };
                JsonArray uv = uvs.has(key) ? uvs.getAsJsonArray(key) : null;
                tc[i] = uv == null ? new float[] { 0, 0 } : new float[] { uv.get(0).getAsFloat() / tw, uv.get(1).getAsFloat() / th };
            }
            if (!ok)
                continue;
            // Face normal from the first triangle (Blockbench meshes are flat shaded by default).
            float ux = pts[1][0] - pts[0][0], uy = pts[1][1] - pts[0][1], uz = pts[1][2] - pts[0][2];
            float vx = pts[2][0] - pts[0][0], vy = pts[2][1] - pts[0][1], vz = pts[2][2] - pts[0][2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1.0E-9F) {
                nx /= len;
                ny /= len;
                nz /= len;
            } else {
                ny = 1;
            }
            ModelData.Prim p = prim(node, tex);
            p.doubleSided = true; // vertex order in a user-made mesh is not guaranteed
            int base = p.pos.length / 3;
            float[] pos = java.util.Arrays.copyOf(p.pos, p.pos.length + n * 3);
            float[] nrm = java.util.Arrays.copyOf(p.nrm, p.nrm.length + n * 3);
            float[] tex2 = java.util.Arrays.copyOf(p.uv, p.uv.length + n * 2);
            for (int i = 0; i < n; i++) {
                int at = (base + i) * 3;
                pos[at] = pts[i][0];
                pos[at + 1] = pts[i][1];
                pos[at + 2] = pts[i][2];
                nrm[at] = nx;
                nrm[at + 1] = ny;
                nrm[at + 2] = nz;
                tex2[(base + i) * 2] = tc[i][0];
                tex2[(base + i) * 2 + 1] = tc[i][1];
            }
            int[] idx = java.util.Arrays.copyOf(p.idx, p.idx.length + (n - 2) * 3);
            int k = p.idx.length;
            for (int i = 1; i + 1 < n; i++) {
                idx[k++] = base;
                idx[k++] = base + i;
                idx[k++] = base + i + 1;
            }
            p.pos = pos;
            p.nrm = nrm;
            p.uv = tex2;
            p.idx = idx;
        }
    }

    // ---------------------------------------------------------------- animations

    private void readAnimations() throws IOException {
        for (JsonElement ae : arr(this.root, "animations")) {
            if (this.model.animations.size() >= ModelData.MAX_ANIMATIONS)
                break;
            JsonObject ja = ae.getAsJsonObject();
            ModelData.Animation anim = new ModelData.Animation();
            anim.name = ja.has("name") ? ja.get("name").getAsString() : "animation" + this.model.animations.size();
            String loop = ja.has("loop") ? ja.get("loop").getAsString() : "once";
            anim.loop = loop.equals("loop") ? ModelData.LOOP_LOOP : loop.equals("hold") ? ModelData.LOOP_HOLD : ModelData.LOOP_ONCE;
            float length = ja.has("length") ? ja.get("length").getAsFloat() : 0.0F;
            JsonObject animators = ja.has("animators") ? ja.getAsJsonObject("animators") : new JsonObject();
            for (Map.Entry<String, JsonElement> en : animators.entrySet()) {
                JsonObject animator = en.getValue().getAsJsonObject();
                Integer node = this.nodeOf.get(en.getKey());
                if (node == null && animator.has("name"))
                    node = this.nodeByName.get(animator.get("name").getAsString().toLowerCase(java.util.Locale.ROOT));
                if (node == null)
                    continue;
                bake(anim, node, animator);
                for (ModelData.Channel c : anim.channels)
                    length = Math.max(length, c.times[c.times.length - 1]);
            }
            anim.length = Math.min(length, ModelData.MAX_SECONDS);
            if (!anim.channels.isEmpty())
                this.model.animations.add(anim);
        }
    }

    private void bake(ModelData.Animation anim, int node, JsonObject animator) throws IOException {
        List<JsonObject> rot = new ArrayList<>(), pos = new ArrayList<>(), scl = new ArrayList<>();
        for (JsonElement ke : arr(animator, "keyframes")) {
            JsonObject k = ke.getAsJsonObject();
            String channel = k.has("channel") ? k.get("channel").getAsString() : "";
            if (!k.has("data_points") || k.getAsJsonArray("data_points").isEmpty())
                continue;
            switch (channel) {
                case "rotation" -> rot.add(k);
                case "position" -> pos.add(k);
                case "scale" -> scl.add(k);
                default -> {
                }
            }
        }
        float[] restRot = this.restRotation.get(node);
        float[] restPos = this.restOrigin.get(node);

        if (!rot.isEmpty()) {
            sort(rot);
            List<Float> times = new ArrayList<>();
            List<float[]> eulers = new ArrayList<>();
            boolean step = false;
            float[] prev = null;
            float prevT = 0;
            for (JsonObject k : rot) {
                float t = time(k);
                float[] v = point(k, 0.0F);
                float[] e = { restRot[0] + v[0], restRot[1] + v[1], restRot[2] + v[2] };
                if (prev != null && !isStep(k)) {
                    float delta = Math.max(Math.abs(e[0] - prev[0]), Math.max(Math.abs(e[1] - prev[1]), Math.abs(e[2] - prev[2])));
                    int extra = (int) Math.floor(delta / 60.0F);
                    for (int s = 1; s <= extra; s++) {
                        float f = (float) s / (extra + 1);
                        times.add(prevT + (t - prevT) * f);
                        eulers.add(new float[] { prev[0] + (e[0] - prev[0]) * f, prev[1] + (e[1] - prev[1]) * f, prev[2] + (e[2] - prev[2]) * f });
                    }
                }
                times.add(t);
                eulers.add(e);
                prev = e;
                prevT = t;
                step |= isStep(k);
            }
            ModelData.Channel c = channel(node, ModelData.PATH_ROTATION, times, 4);
            for (int i = 0; i < eulers.size(); i++) {
                float[] q = ModelPose.eulerZyx(eulers.get(i)[0], eulers.get(i)[1], eulers.get(i)[2]);
                System.arraycopy(q, 0, c.values, i * 4, 4);
            }
            c.interp = step ? ModelData.INTERP_STEP : ModelData.INTERP_LINEAR;
            add(anim, c);
        }
        if (!pos.isEmpty()) {
            sort(pos);
            List<Float> times = new ArrayList<>();
            for (JsonObject k : pos)
                times.add(time(k));
            ModelData.Channel c = channel(node, ModelData.PATH_TRANSLATION, times, 3);
            boolean step = false;
            for (int i = 0; i < pos.size(); i++) {
                float[] v = point(pos.get(i), 0.0F);
                c.values[i * 3] = restPos[0] + v[0] * UNIT;
                c.values[i * 3 + 1] = restPos[1] + v[1] * UNIT;
                c.values[i * 3 + 2] = restPos[2] + v[2] * UNIT;
                step |= isStep(pos.get(i));
            }
            c.interp = step ? ModelData.INTERP_STEP : ModelData.INTERP_LINEAR;
            add(anim, c);
        }
        if (!scl.isEmpty()) {
            sort(scl);
            List<Float> times = new ArrayList<>();
            for (JsonObject k : scl)
                times.add(time(k));
            ModelData.Channel c = channel(node, ModelData.PATH_SCALE, times, 3);
            boolean step = false;
            for (int i = 0; i < scl.size(); i++) {
                float[] v = point(scl.get(i), 1.0F);
                c.values[i * 3] = v[0];
                c.values[i * 3 + 1] = v[1];
                c.values[i * 3 + 2] = v[2];
                step |= isStep(scl.get(i));
            }
            c.interp = step ? ModelData.INTERP_STEP : ModelData.INTERP_LINEAR;
            add(anim, c);
        }
    }

    private void add(ModelData.Animation anim, ModelData.Channel c) throws IOException {
        if (anim.channels.size() >= ModelData.MAX_CHANNELS)
            throw new IOException("An animation has too many channels");
        anim.channels.add(c);
    }

    private static ModelData.Channel channel(int node, int path, List<Float> times, int width) {
        ModelData.Channel c = new ModelData.Channel();
        c.node = node;
        c.path = path;
        c.times = new float[times.size()];
        for (int i = 0; i < c.times.length; i++)
            c.times[i] = times.get(i);
        c.values = new float[times.size() * width];
        return c;
    }

    private static void sort(List<JsonObject> keys) {
        keys.sort((a, b) -> Float.compare(time(a), time(b)));
    }

    private static float time(JsonObject k) {
        float t = k.has("time") ? k.get("time").getAsFloat() : 0.0F;
        return Math.max(0.0F, Float.isFinite(t) ? t : 0.0F);
    }

    private static boolean isStep(JsonObject k) {
        return k.has("interpolation") && k.get("interpolation").getAsString().equals("step");
    }

    /** x, y, z of the first data point; they may be numbers or numeric strings (Molang expressions read as the fallback). */
    private static float[] point(JsonObject k, float fallback) {
        JsonObject dp = k.getAsJsonArray("data_points").get(0).getAsJsonObject();
        return new float[] { number(dp.get("x"), fallback), number(dp.get("y"), fallback), number(dp.get("z"), fallback) };
    }

    private static float number(JsonElement e, float fallback) {
        if (e == null || e.isJsonNull())
            return fallback;
        try {
            float f = e.getAsFloat();
            return Float.isFinite(f) ? f : fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    // ---------------------------------------------------------------- json helpers

    private static final JsonArray NONE = new JsonArray();

    private static JsonArray arr(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : NONE;
    }

    private static float[] vec(JsonObject o, String key) {
        float[] v = { 0, 0, 0 };
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray())
            for (int i = 0; i < 3 && i < e.getAsJsonArray().size(); i++)
                v[i] = number(e.getAsJsonArray().get(i), 0.0F);
        return v;
    }

    private static float[] vec2(JsonObject o, String key) {
        float[] v = { 0, 0 };
        JsonElement e = o.get(key);
        if (e != null && e.isJsonArray())
            for (int i = 0; i < 2 && i < e.getAsJsonArray().size(); i++)
                v[i] = number(e.getAsJsonArray().get(i), 0.0F);
        return v;
    }
}
