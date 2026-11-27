package dev.watdafawx.foxstweaks.client.asset;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure-Java Wavefront {@code .obj} + {@code .mtl} + texture -> binary {@code .glb} (glTF 2.0) converter.
 * Supports triangles, quads, and convex n-gons (fan triangulation), negative OBJ indices, diffuse color
 * ({@code Kd}), opacity ({@code d}/{@code Tr}), diffuse texture ({@code map_Kd}), and automatic flat normals
 * when normals are omitted.
 */
public final class ObjToGlb {

    private static final class Material {
        float r = 1.0F, g = 1.0F, b = 1.0F, a = 1.0F;
        @Nullable
        String mapKd;
    }

    private static final class GroupMesh {
        final String matName;
        final List<Float> pos = new ArrayList<>();
        final List<Float> nrm = new ArrayList<>();
        final List<Float> uv = new ArrayList<>();
        final List<Integer> idx = new ArrayList<>();

        GroupMesh(String matName) {
            this.matName = matName;
        }
    }

    private ObjToGlb() {
    }

    public static byte[] convertFile(Path objPath) throws IOException {
        Path dir = objPath.toAbsolutePath().getParent();
        byte[] raw = Files.readAllBytes(objPath);
        return convert(raw, uri -> GltfBundler.resolveLocal(dir, uri));
    }

    public static byte[] convert(byte[] objBytes, GltfBundler.Lookup lookup) throws IOException {
        List<Float> vs = new ArrayList<>();
        List<Float> vts = new ArrayList<>();
        List<Float> vns = new ArrayList<>();
        Map<String, Material> materials = new LinkedHashMap<>();
        Map<String, GroupMesh> groups = new LinkedHashMap<>();
        String currentMat = "";

        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(objBytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                if (line.startsWith("v ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        vs.add(parseF(p[1]));
                        vs.add(parseF(p[2]));
                        vs.add(parseF(p[3]));
                    }
                } else if (line.startsWith("vt ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 3) {
                        vts.add(parseF(p[1]));
                        // OBJ V=0 is bottom; glTF V=0 is top.
                        vts.add(1.0F - parseF(p[2]));
                    }
                } else if (line.startsWith("vn ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        vns.add(parseF(p[1]));
                        vns.add(parseF(p[2]));
                        vns.add(parseF(p[3]));
                    }
                } else if (line.startsWith("mtllib ")) {
                    String mtlName = line.substring(7).trim();
                    byte[] mtlBytes = lookup.resolve(mtlName);
                    if (mtlBytes != null)
                        parseMtl(mtlBytes, materials);
                } else if (line.startsWith("usemtl ")) {
                    currentMat = line.substring(7).trim();
                } else if (line.startsWith("f ")) {
                    String[] p = line.split("\\s+");
                    if (p.length < 4)
                        continue;
                    int vertCount = p.length - 1;
                    int[] vi = new int[vertCount];
                    int[] ti = new int[vertCount];
                    int[] ni = new int[vertCount];
                    int nV = vs.size() / 3, nT = vts.size() / 2, nN = vns.size() / 3;
                    for (int i = 0; i < vertCount; i++) {
                        String[] tok = p[i + 1].split("/", -1);
                        vi[i] = resolveIdx(tok.length > 0 ? tok[0] : "", nV);
                        ti[i] = resolveIdx(tok.length > 1 ? tok[1] : "", nT);
                        ni[i] = resolveIdx(tok.length > 2 ? tok[2] : "", nN);
                    }
                    GroupMesh gm = groups.computeIfAbsent(currentMat, GroupMesh::new);
                    for (int i = 1; i + 1 < vertCount; i++) {
                        addTriangle(gm, vs, vts, vns, vi[0], ti[0], ni[0], vi[i], ti[i], ni[i], vi[i + 1], ti[i + 1], ni[i + 1]);
                    }
                }
            }
        }

        if (groups.isEmpty())
            throw new IOException("OBJ file has no geometry faces");

        return buildGlb(groups, materials, lookup);
    }

    private static void parseMtl(byte[] mtlBytes, Map<String, Material> out) throws IOException {
        Material cur = null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(mtlBytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#"))
                    continue;
                if (line.startsWith("newmtl ")) {
                    String name = line.substring(7).trim();
                    cur = out.computeIfAbsent(name, k -> new Material());
                } else if (cur != null && line.startsWith("Kd ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 4) {
                        cur.r = clamp01(parseF(p[1]));
                        cur.g = clamp01(parseF(p[2]));
                        cur.b = clamp01(parseF(p[3]));
                    }
                } else if (cur != null && line.startsWith("d ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 2)
                        cur.a = clamp01(parseF(p[1]));
                } else if (cur != null && line.startsWith("Tr ")) {
                    String[] p = line.split("\\s+");
                    if (p.length >= 2)
                        cur.a = clamp01(1.0F - parseF(p[1]));
                } else if (cur != null && line.toLowerCase(Locale.ROOT).startsWith("map_kd ")) {
                    String rest = line.substring(7).trim();
                    // Strip optional MTL map flags like -s 1 1 1 or -bm 1.0 by taking the last path-like token
                    // unless the path itself has spaces and no flags.
                    if (rest.startsWith("-")) {
                        String[] toks = rest.split("\\s+");
                        rest = toks[toks.length - 1];
                    }
                    cur.mapKd = rest;
                }
            }
        }
    }

    private static void addTriangle(GroupMesh gm, List<Float> vs, List<Float> vts, List<Float> vns,
                                    int v0, int t0, int n0, int v1, int t1, int n1, int v2, int t2, int n2) {
        if (v0 < 0 || v1 < 0 || v2 < 0)
            return;
        float x0 = vs.get(v0 * 3), y0 = vs.get(v0 * 3 + 1), z0 = vs.get(v0 * 3 + 2);
        float x1 = vs.get(v1 * 3), y1 = vs.get(v1 * 3 + 1), z1 = vs.get(v1 * 3 + 2);
        float x2 = vs.get(v2 * 3), y2 = vs.get(v2 * 3 + 1), z2 = vs.get(v2 * 3 + 2);

        float fnx = 0.0F, fny = 1.0F, fnz = 0.0F;
        if (n0 < 0 || n1 < 0 || n2 < 0) {
            float ux = x1 - x0, uy = y1 - y0, uz = z1 - z0;
            float vx = x2 - x0, vy = y2 - y0, vz = z2 - z0;
            fnx = uy * vz - uz * vy;
            fny = uz * vx - ux * vz;
            fnz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(fnx * fnx + fny * fny + fnz * fnz);
            if (len > 1.0E-6F) {
                fnx /= len;
                fny /= len;
                fnz /= len;
            } else {
                fnx = 0.0F;
                fny = 1.0F;
                fnz = 0.0F;
            }
        }

        int base = gm.pos.size() / 3;
        emitVert(gm, x0, y0, z0, t0, n0, vts, vns, fnx, fny, fnz);
        emitVert(gm, x1, y1, z1, t1, n1, vts, vns, fnx, fny, fnz);
        emitVert(gm, x2, y2, z2, t2, n2, vts, vns, fnx, fny, fnz);
        gm.idx.add(base);
        gm.idx.add(base + 1);
        gm.idx.add(base + 2);
    }

    private static void emitVert(GroupMesh gm, float x, float y, float z, int ti, int ni,
                                 List<Float> vts, List<Float> vns, float fnx, float fny, float fnz) {
        gm.pos.add(x);
        gm.pos.add(y);
        gm.pos.add(z);
        if (ti >= 0) {
            gm.uv.add(vts.get(ti * 2));
            gm.uv.add(vts.get(ti * 2 + 1));
        } else {
            gm.uv.add(0.0F);
            gm.uv.add(0.0F);
        }
        if (ni >= 0) {
            gm.nrm.add(vns.get(ni * 3));
            gm.nrm.add(vns.get(ni * 3 + 1));
            gm.nrm.add(vns.get(ni * 3 + 2));
        } else {
            gm.nrm.add(fnx);
            gm.nrm.add(fny);
            gm.nrm.add(fnz);
        }
    }

    private static byte[] buildGlb(Map<String, GroupMesh> groups, Map<String, Material> materials,
                                   GltfBundler.Lookup lookup) throws IOException {
        ByteArrayOutputStream bin = new ByteArrayOutputStream();
        JsonArray bufferViews = new JsonArray();
        JsonArray accessors = new JsonArray();
        JsonArray images = new JsonArray();
        JsonArray textures = new JsonArray();
        JsonArray gltfMats = new JsonArray();
        JsonArray prims = new JsonArray();

        Map<String, Integer> texCache = new LinkedHashMap<>();

        for (GroupMesh gm : groups.values()) {
            if (gm.idx.isEmpty())
                continue;
            int vCount = gm.pos.size() / 3;
            int iCount = gm.idx.size();

            // Material
            Material m = materials.getOrDefault(gm.matName, new Material());
            int matIdx = gltfMats.size();
            JsonObject gMat = new JsonObject();
            JsonObject pbr = new JsonObject();
            JsonArray factor = new JsonArray();
            factor.add(m.r);
            factor.add(m.g);
            factor.add(m.b);
            factor.add(m.a);
            pbr.add("baseColorFactor", factor);

            if (m.mapKd != null && !m.mapKd.isEmpty()) {
                Integer texIdx = texCache.get(m.mapKd);
                if (texIdx == null) {
                    byte[] imgBytes = lookup.resolve(m.mapKd);
                    if (imgBytes != null && imgBytes.length > 0) {
                        int bv = writeView(bin, bufferViews, imgBytes);
                        int imgIdx = images.size();
                        JsonObject imgObj = new JsonObject();
                        imgObj.addProperty("bufferView", bv);
                        String low = m.mapKd.toLowerCase(Locale.ROOT);
                        imgObj.addProperty("mimeType", low.endsWith(".jpg") || low.endsWith(".jpeg") ? "image/jpeg" : "image/png");
                        images.add(imgObj);
                        texIdx = textures.size();
                        JsonObject texObj = new JsonObject();
                        texObj.addProperty("source", imgIdx);
                        textures.add(texObj);
                        texCache.put(m.mapKd, texIdx);
                    }
                }
                if (texIdx != null) {
                    JsonObject bct = new JsonObject();
                    bct.addProperty("index", texIdx);
                    pbr.add("baseColorTexture", bct);
                }
            }
            gMat.add("pbrMetallicRoughness", pbr);
            if (m.a < 0.99F)
                gMat.addProperty("alphaMode", "BLEND");
            gMat.addProperty("doubleSided", true);
            gltfMats.add(gMat);

            // POSITION, NORMAL, TEXCOORD_0, INDICES
            int posAcc = writeFloatAccessor(bin, bufferViews, accessors, gm.pos, "VEC3", vCount);
            int nrmAcc = writeFloatAccessor(bin, bufferViews, accessors, gm.nrm, "VEC3", vCount);
            int uvAcc = writeFloatAccessor(bin, bufferViews, accessors, gm.uv, "VEC2", vCount);
            int idxAcc = writeIndexAccessor(bin, bufferViews, accessors, gm.idx, iCount);

            JsonObject attrs = new JsonObject();
            attrs.addProperty("POSITION", posAcc);
            attrs.addProperty("NORMAL", nrmAcc);
            attrs.addProperty("TEXCOORD_0", uvAcc);

            JsonObject prim = new JsonObject();
            prim.add("attributes", attrs);
            prim.addProperty("indices", idxAcc);
            prim.addProperty("material", matIdx);
            prim.addProperty("mode", 4);
            prims.add(prim);
        }

        if (prims.isEmpty())
            throw new IOException("OBJ file produced no valid triangles");

        // Pad bin to 4-byte alignment
        while (bin.size() % 4 != 0)
            bin.write(0);
        byte[] binBytes = bin.toByteArray();

        JsonObject root = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        root.add("asset", asset);
        root.addProperty("scene", 0);

        JsonArray scenes = new JsonArray();
        JsonObject sc = new JsonObject();
        JsonArray scNodes = new JsonArray();
        scNodes.add(0);
        sc.add("nodes", scNodes);
        scenes.add(sc);
        root.add("scenes", scenes);

        JsonArray nodes = new JsonArray();
        JsonObject node0 = new JsonObject();
        node0.addProperty("mesh", 0);
        nodes.add(node0);
        root.add("nodes", nodes);

        JsonArray meshes = new JsonArray();
        JsonObject mesh0 = new JsonObject();
        mesh0.add("primitives", prims);
        meshes.add(mesh0);
        root.add("meshes", meshes);

        JsonArray buffers = new JsonArray();
        JsonObject buf0 = new JsonObject();
        buf0.addProperty("byteLength", binBytes.length);
        buffers.add(buf0);
        root.add("buffers", buffers);

        root.add("bufferViews", bufferViews);
        root.add("accessors", accessors);
        root.add("materials", gltfMats);
        if (!images.isEmpty())
            root.add("images", images);
        if (!textures.isEmpty())
            root.add("textures", textures);

        byte[] jsonRaw = root.toString().getBytes(StandardCharsets.UTF_8);
        int jsonPad = (4 - (jsonRaw.length % 4)) % 4;
        int jsonLen = jsonRaw.length + jsonPad;
        int totalLen = 12 + 8 + jsonLen + 8 + binBytes.length;

        ByteBuffer out = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546C67); // "glTF"
        out.putInt(2);
        out.putInt(totalLen);
        out.putInt(jsonLen);
        out.putInt(0x4E4F534A); // "JSON"
        out.put(jsonRaw);
        for (int i = 0; i < jsonPad; i++)
            out.put((byte) ' ');
        out.putInt(binBytes.length);
        out.putInt(0x004E4942); // "BIN\0"
        out.put(binBytes);
        return out.array();
    }

    private static int writeView(ByteArrayOutputStream bin, JsonArray views, byte[] data) throws IOException {
        while (bin.size() % 4 != 0)
            bin.write(0);
        int offset = bin.size();
        bin.write(data);
        int idx = views.size();
        JsonObject bv = new JsonObject();
        bv.addProperty("buffer", 0);
        bv.addProperty("byteOffset", offset);
        bv.addProperty("byteLength", data.length);
        views.add(bv);
        return idx;
    }

    private static int writeFloatAccessor(ByteArrayOutputStream bin, JsonArray views, JsonArray accessors,
                                          List<Float> floats, String type, int count) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(floats.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : floats)
            bb.putFloat(f);
        int bv = writeView(bin, views, bb.array());
        int idx = accessors.size();
        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", bv);
        acc.addProperty("componentType", 5126); // FLOAT
        acc.addProperty("count", count);
        acc.addProperty("type", type);
        accessors.add(acc);
        return idx;
    }

    private static int writeIndexAccessor(ByteArrayOutputStream bin, JsonArray views, JsonArray accessors,
                                          List<Integer> indices, int count) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(indices.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : indices)
            bb.putInt(v);
        int bv = writeView(bin, views, bb.array());
        int idx = accessors.size();
        JsonObject acc = new JsonObject();
        acc.addProperty("bufferView", bv);
        acc.addProperty("componentType", 5125); // UNSIGNED_INT
        acc.addProperty("count", count);
        acc.addProperty("type", "SCALAR");
        accessors.add(acc);
        return idx;
    }

    private static int resolveIdx(String raw, int size) {
        if (raw == null || raw.isEmpty())
            return -1;
        try {
            int i = Integer.parseInt(raw);
            int idx = i < 0 ? size + i : i - 1;
            return (idx >= 0 && idx < size) ? idx : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float parseF(String s) {
        try {
            float v = Float.parseFloat(s);
            return Float.isFinite(v) ? v : 0.0F;
        } catch (NumberFormatException e) {
            return 0.0F;
        }
    }

    private static float clamp01(float v) {
        return Math.max(0.0F, Math.min(1.0F, v));
    }
}
