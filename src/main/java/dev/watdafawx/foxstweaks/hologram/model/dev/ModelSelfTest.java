package dev.watdafawx.foxstweaks.hologram.model.dev;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.imageio.ImageIO;

import dev.watdafawx.foxstweaks.hologram.model.BlockbenchReader;
import dev.watdafawx.foxstweaks.hologram.model.GlbReader;
import dev.watdafawx.foxstweaks.hologram.model.ModelData;
import dev.watdafawx.foxstweaks.hologram.model.ModelPose;

/** Headless check: {@code build.bat modelSelfTest}. Builds a glb and a bbmodel in memory, reads, round-trips, poses. */
public final class ModelSelfTest {
    private static int failed;

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + name);
        if (!ok)
            failed++;
    }

    private static boolean near(float a, float b) {
        return Math.abs(a - b) < 1.0E-3F;
    }

    public static void main(String[] args) throws Exception {
        glb();
        bbmodel();
        bad();
        System.out.println(failed == 0 ? "ALL PASS" : failed + " FAILED");
        System.exit(failed == 0 ? 0 : 1);
    }

    // A one-triangle mesh skinned to two joints; joint 1 moves +1 in Y between t=0 and t=1.
    private static void glb() throws Exception {
        ByteBuffer bin = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        float[] pos = { 0, 0, 0, 1, 0, 0, 0, 1, 0 };
        for (float f : pos)
            bin.putFloat(f); // 0..36
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0); // 36..44
        // Vertex 0 -> joint 1, vertices 1 and 2 -> joint 0.
        byte[] joints = { 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
        bin.put(joints); // 44..56
        for (int i = 0; i < 3; i++)
            for (float w : new float[] { 1, 0, 0, 0 })
                bin.putFloat(w); // 56..104
        bin.putFloat(0).putFloat(1); // 104..112 times
        bin.putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0); // 112..136 translations
        for (int j = 0; j < 2; j++)
            for (int i = 0; i < 16; i++)
                bin.putFloat(i % 5 == 0 ? 1 : 0); // 136..264 inverse bind
        int binLen = bin.position();
        String json = "{\"asset\":{\"version\":\"2.0\"},\"scene\":0,\"scenes\":[{\"nodes\":[0,2]}],"
                + "\"nodes\":[{\"name\":\"root\",\"children\":[1]},{\"name\":\"bone\"},{\"name\":\"mesh\",\"mesh\":0,\"skin\":0}],"
                + "\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"JOINTS_0\":2,\"WEIGHTS_0\":3},\"indices\":1}]}],"
                + "\"skins\":[{\"joints\":[0,1],\"inverseBindMatrices\":5}],"
                + "\"animations\":[{\"name\":\"rise\",\"samplers\":[{\"input\":4,\"output\":6,\"interpolation\":\"LINEAR\"}],"
                + "\"channels\":[{\"sampler\":0,\"target\":{\"node\":1,\"path\":\"translation\"}}]}],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":2,\"componentType\":5121,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":3,\"componentType\":5126,\"count\":3,\"type\":\"VEC4\"},"
                + "{\"bufferView\":4,\"componentType\":5126,\"count\":2,\"type\":\"SCALAR\"},"
                + "{\"bufferView\":6,\"componentType\":5126,\"count\":2,\"type\":\"MAT4\"},"
                + "{\"bufferView\":5,\"componentType\":5126,\"count\":2,\"type\":\"VEC3\"}],"
                + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},{\"buffer\":0,\"byteOffset\":36,\"byteLength\":6},"
                + "{\"buffer\":0,\"byteOffset\":44,\"byteLength\":12},{\"buffer\":0,\"byteOffset\":56,\"byteLength\":48},"
                + "{\"buffer\":0,\"byteOffset\":104,\"byteLength\":8},{\"buffer\":0,\"byteOffset\":112,\"byteLength\":24},"
                + "{\"buffer\":0,\"byteOffset\":136,\"byteLength\":128}],\"buffers\":[{\"byteLength\":" + binLen + "}]}";
        byte[] j = json.getBytes(StandardCharsets.UTF_8);
        int jp = (j.length + 3) & ~3;
        int bp = (binLen + 3) & ~3;
        ByteBuffer glb = ByteBuffer.allocate(12 + 8 + jp + 8 + bp).order(ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546C67).putInt(2).putInt(glb.capacity());
        glb.putInt(jp).putInt(0x4E4F534A).put(j);
        for (int i = j.length; i < jp; i++)
            glb.put((byte) 0x20);
        glb.putInt(bp).putInt(0x004E4942).put(bin.array(), 0, binLen);

        ModelData m = GlbReader.read(glb.array());
        check("glb: 3 nodes", m.nodes.size() == 3);
        int bone = byName(m, "bone"), mesh = byName(m, "mesh");
        check("glb: parents first", m.nodes.get(bone).parent >= 0 && m.nodes.get(bone).parent < bone);
        check("glb: one skinned prim", m.nodes.get(mesh).prims.size() == 1 && m.nodes.get(mesh).prims.get(0).joints != null);
        check("glb: animation 'rise' 1 s", m.animations.size() == 1 && near(m.animations.get(0).length, 1));
        ModelData r = ModelData.decode(m.encode());
        check("glb: encode/decode keeps tris", r.triangleCount() == 1 && r.vertexCount() == 3);
        ModelPose pose = new ModelPose(r);
        pose.evaluate(0, 1.0);
        float[] p = new float[9], n = new float[9];
        pose.pose(byName(r, "mesh"), r.nodes.get(byName(r, "mesh")).prims.get(0), p, n);
        check("glb: skinned vertex 0 moved up 1", near(p[1], 1.0F));
        check("glb: vertex 1 stays (joint 0)", near(p[4], 0.0F));
        pose.evaluate(0, 0.5);
        pose.pose(byName(r, "mesh"), r.nodes.get(byName(r, "mesh")).prims.get(0), p, n);
        check("glb: halfway is 0.5", near(p[1], 0.5F));
    }

    private static int byName(ModelData m, String name) {
        for (int i = 0; i < m.nodes.size(); i++)
            if (m.nodes.get(i).name.equals(name))
                return i;
        return -1;
    }

    private static void bbmodel() throws Exception {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                img.setRGB(x, y, 0xFFFF8800);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(img, "png", png);
        String b64 = Base64.getEncoder().encodeToString(png.toByteArray());
        String face = "{\"uv\":[0,0,16,16],\"texture\":0}";
        String json = "{\"meta\":{\"format_version\":\"4.0\"},\"resolution\":{\"width\":16,\"height\":16},"
                + "\"elements\":[{\"uuid\":\"c1\",\"type\":\"cube\",\"from\":[0,0,0],\"to\":[16,16,16],\"origin\":[8,0,8],"
                + "\"faces\":{\"north\":" + face + ",\"south\":" + face + ",\"east\":" + face + ",\"west\":" + face
                + ",\"up\":" + face + ",\"down\":" + face + "}}],"
                + "\"outliner\":[{\"uuid\":\"g1\",\"name\":\"spin\",\"origin\":[8,8,8],\"rotation\":[0,0,0],\"children\":[\"c1\"]}],"
                + "\"textures\":[{\"name\":\"t\",\"source\":\"data:image/png;base64," + b64 + "\"}],"
                + "\"animations\":[{\"name\":\"turn\",\"loop\":\"loop\",\"length\":2,\"animators\":{\"g1\":{\"name\":\"spin\","
                + "\"keyframes\":[{\"channel\":\"rotation\",\"time\":0,\"data_points\":[{\"x\":0,\"y\":0,\"z\":0}]},"
                + "{\"channel\":\"rotation\",\"time\":2,\"data_points\":[{\"x\":0,\"y\":\"180\",\"z\":0}]}]}}}]}";
        ModelData m = BlockbenchReader.read(json.getBytes(StandardCharsets.UTF_8));
        check("bb: group node exists", m.nodes.size() == 1 && m.nodes.get(0).name.equals("spin"));
        check("bb: cube merged into group, 12 triangles", m.triangleCount() == 12);
        check("bb: texture embedded 16x16", m.textures.size() == 1 && m.textures.get(0).width == 16);
        check("bb: animation loops, 2 s", m.animations.size() == 1 && m.animations.get(0).loop == ModelData.LOOP_LOOP
                && near(m.animations.get(0).length, 2));
        check("bb: 180 deg turn got extra keys", m.animations.get(0).channels.get(0).times.length > 2);
        ModelData r = ModelData.decode(m.encode());
        ModelPose pose = new ModelPose(r);
        float[] b0 = pose.bounds(-1, 0);
        // Cube spans 0..16 px = 0..1 block, group at its centre.
        check("bb: rest bounds 1 block cube centred on the pivot", near(b0[0], 0F) && near(b0[3], 1F)
                && near(b0[1], 0F) && near(b0[4], 1F));
        pose.evaluate(0, 1.0); // 90 degrees about Y
        float[] w = pose.worldMatrix(0);
        check("bb: group rotated 90 deg at t=1", near(w[0], 0) && near(Math.abs(w[2]), 1));
        pose.evaluate(0, 0.5);
        float[] wq = pose.worldMatrix(0);
        check("bb: 45 deg at t=0.5", near(wq[0], (float) Math.cos(Math.PI / 4)));
    }

    private static void bad() {
        boolean threw = false;
        try {
            GlbReader.read("not a model".getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            threw = true;
        }
        check("reject: garbage glb", threw);
        threw = false;
        try {
            ModelData.decode(new byte[] { 0x46, 0x54, 0x4D, 0x31, 1, (byte) 0xFF, (byte) 0xFF });
        } catch (Exception e) {
            threw = true;
        }
        check("reject: truncated blob", threw);
    }
}
