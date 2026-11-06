package dev.watdafawx.foxstweaks.client.compat;

import java.lang.reflect.Field;
import java.util.List;

import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;

import net.minecraft.util.FastColor;

/**
 * Flat, pre-filtered copy of a TACZ bedrock cube's quads, drawn by {@code TaczBedrockCubeMixin} in place of
 * TACZ's own {@code compile}.
 *
 * <p>TACZ draws every gun, attachment and ammo model cube by cube, every frame. Its {@code compile} allocates a
 * {@code Vector3f} per quad and a {@code Vector4f} per vertex, and emits all six sides of every cube even when a
 * side has zero area (an empty per-face UV, or the flat sides of a zero-thickness cube). Those rasterize nothing
 * but still cost a vertex upload each, and under Iris a per-quad normal/tangent pass. Here each cube is read once,
 * when TACZ builds it: zero-area quads are dropped, and the rest are kept as plain floats, then drawn with no
 * allocation at all.
 *
 * <p>TACZ types are reached only by reflection, and only while a model loads, so there is no TACZ compile
 * dependency and no reflection per frame. If any field is missing or has an unexpected type, {@link #flatten}
 * returns null and the cube keeps drawing through TACZ's own code.
 */
public final class TaczBedrockQuads {
    private static final Logger LOG = LogUtils.getLogger();

    /** Per quad: normal x, y, z, then four vertices of x, y, z (already divided by 16), u, v. */
    private static final int QUAD = 3 + 4 * 5;

    private static final ClassValue<Field> POLYGONS = new ClassValue<>() {
        @Override
        protected Field computeValue(Class<?> type) {
            try {
                var field = type.getDeclaredField("polygons");
                field.setAccessible(true);
                return field;
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
    };

    private static Field polygonVertices;
    private static Field polygonNormal;
    private static Field vertexPos;
    private static Field vertexU;
    /** Dev only: {@code TaczModelCheck} sets this to collect every cube built. Always null in normal play. */
    public static volatile List<Object> devRecord;

    /** Written last and volatile, so a thread that sees it set also sees the other four. */
    private static volatile Field vertexV;
    private static volatile boolean failed;

    /**
     * Reads {@code cube}'s polygons into a flat array, without the zero-area ones. Returns null (TACZ's own drawing
     * stays in use) when the cube doesn't look the way it did in TACZ 1.1.8.
     */
    public static float[] flatten(Object cube) {
        if (failed) {
            return null;
        }

        try {
            var field = POLYGONS.get(cube.getClass());
            if (field == null || !(field.get(cube) instanceof Object[] polygons)) {
                return fail("no polygons field on " + cube.getClass().getName(), null);
            }

            var out = new float[polygons.length * QUAD];
            var count = 0;
            for (var polygon : polygons) {
                if (polygon == null) {
                    return fail("null polygon in " + cube.getClass().getName(), null);
                }

                if (!lookUpFields(polygon)) {
                    return null;
                }

                if (!(polygonVertices.get(polygon) instanceof Object[] vertices) || vertices.length != 4
                        || !(polygonNormal.get(polygon) instanceof Vector3f normal)) {
                    return fail("unexpected polygon shape in " + cube.getClass().getName(), null);
                }

                var p = new Vector3f[4];
                for (var i = 0; i < 4; i++) {
                    if (!(vertexPos.get(vertices[i]) instanceof Vector3f pos)) {
                        return fail("unexpected vertex in " + cube.getClass().getName(), null);
                    }

                    p[i] = pos;
                }

                // Same test as a quad's area: the diagonals' cross product is zero exactly when it has none.
                var d1 = new Vector3f(p[2]).sub(p[0]);
                var d2 = new Vector3f(p[3]).sub(p[1]);
                if (d1.cross(d2).lengthSquared() == 0.0F) {
                    continue;
                }

                var o = count * QUAD;
                out[o] = normal.x;
                out[o + 1] = normal.y;
                out[o + 2] = normal.z;
                for (var i = 0; i < 4; i++) {
                    var v = o + 3 + i * 5;
                    out[v] = p[i].x / 16.0F;
                    out[v + 1] = p[i].y / 16.0F;
                    out[v + 2] = p[i].z / 16.0F;
                    out[v + 3] = vertexU.getFloat(vertices[i]);
                    out[v + 4] = vertexV.getFloat(vertices[i]);
                }

                count++;
            }

            var record = devRecord;
            if (record != null) {
                record.add(cube);
            }

            if (count == polygons.length) {
                return out;
            }

            var trimmed = new float[count * QUAD];
            System.arraycopy(out, 0, trimmed, 0, trimmed.length);
            return trimmed;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return fail("could not read " + cube.getClass().getName(), e);
        }
    }

    /** Draws exactly what TACZ's {@code compile} would have drawn for the kept quads, in the same order. */
    public static void emit(float[] quads, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay,
            float red, float green, float blue, float alpha) {
        Matrix4f m = pose.pose();
        Matrix3f n = pose.normal();
        var color = FastColor.ARGB32.colorFromFloat(alpha, red, green, blue);

        for (var o = 0; o < quads.length; o += QUAD) {
            float px = quads[o];
            float py = quads[o + 1];
            float pz = quads[o + 2];
            var nx = n.m00() * px + n.m10() * py + n.m20() * pz;
            var ny = n.m01() * px + n.m11() * py + n.m21() * pz;
            var nz = n.m02() * px + n.m12() * py + n.m22() * pz;

            for (var v = o + 3; v < o + QUAD; v += 5) {
                float x = quads[v];
                float y = quads[v + 1];
                float z = quads[v + 2];
                consumer.addVertex(
                        m.m00() * x + m.m10() * y + m.m20() * z + m.m30(),
                        m.m01() * x + m.m11() * y + m.m21() * z + m.m31(),
                        m.m02() * x + m.m12() * y + m.m22() * z + m.m32(),
                        color, quads[v + 3], quads[v + 4], overlay, light, nx, ny, nz);
            }
        }
    }

    /** TACZ has exactly one polygon and one vertex class; their fields are found from the first cube built. */
    private static synchronized boolean lookUpFields(Object polygon) throws ReflectiveOperationException {
        if (vertexV != null) {
            return true;
        }

        var polygonType = polygon.getClass();
        var vertices = polygonType.getField("vertices");
        var normal = polygonType.getField("normal");
        var vertexType = vertices.getType().getComponentType();
        if (vertexType == null) {
            fail("vertices is not an array on " + polygonType.getName(), null);
            return false;
        }

        vertexPos = vertexType.getField("pos");
        vertexU = vertexType.getField("u");
        polygonVertices = vertices;
        polygonNormal = normal;
        vertexV = vertexType.getField("v");
        return true;
    }

    private static float[] fail(String why, Throwable cause) {
        if (!failed) {
            failed = true;
            LOG.warn("[foxstweaks] TACZ model fast path off, TACZ draws models itself: {}", why, cause);
        }

        return null;
    }

    private TaczBedrockQuads() {
    }
}
