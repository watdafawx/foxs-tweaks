package dev.watdafawx.foxstweaks.client.dev;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import dev.watdafawx.foxstweaks.client.compat.TaczBedrockQuads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Dev-only parity check for {@code TaczBedrockCubeMixin}, run by {@code build.bat taczCheck} (game dir
 * {@code run-tacz/}, TACZ jar in its {@code mods/}). Never installed in a shipped jar.
 *
 * <p>Every TACZ cube built while the game loads is recorded. At the title screen each one is drawn twice through
 * its real {@code compile} under a rotated, translated, non-uniformly scaled pose: once on the fast path, once
 * with the flat copy removed, which is exactly TACZ's own code. The oracle: the fast path's quads must appear, in
 * order and identical, within TACZ's, and every quad TACZ drew that the fast path skipped must have zero area on
 * screen. Writes {@code run-tacz/foxstweaks-tacz-check.txt} and quits.
 */
public final class TaczModelCheck {
    private static final String REPORT = "foxstweaks-tacz-check.txt";
    private static boolean armed;
    private static boolean ran;

    public static void install() {
        TaczBedrockQuads.devRecord = Collections.synchronizedList(new ArrayList<>());
        NeoForge.EVENT_BUS.addListener(TaczModelCheck::onScreen);
        NeoForge.EVENT_BUS.addListener(TaczModelCheck::onTick);
    }

    private static void onScreen(ScreenEvent.Opening event) {
        if (event.getNewScreen() instanceof TitleScreen || event.getNewScreen() instanceof AccessibilityOnboardingScreen) {
            armed = true;
        }
    }

    /** The title screen opens under the loading overlay, before the first resource reload (and TACZ's) is done. */
    private static void onTick(ClientTickEvent.Post event) {
        if (ran || !armed || Minecraft.getInstance().getOverlay() != null) {
            return;
        }

        ran = true;
        var lines = new ArrayList<String>();
        try {
            run(lines);
        } catch (Throwable t) {
            lines.add("FAIL crashed: " + t);
            for (var e : t.getStackTrace()) {
                lines.add("    at " + e);
            }
        }

        try {
            Files.write(FMLPaths.GAMEDIR.get().resolve(REPORT), lines);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Minecraft.getInstance().stop();
    }

    private static void run(List<String> out) throws ReflectiveOperationException {
        // Gun models are built lazily, the first time a gun is drawn; only block/ammo/attachment ones exist by
        // the title screen. Build every gun display now so the check covers them too.
        var before = TaczBedrockQuads.devRecord.size();
        Class.forName("com.tacz.guns.client.resource.ClientIndexManager").getMethod("loadGunDisplay").invoke(null);
        out.add("cubes before / after building every gun display: " + before + " / " + TaczBedrockQuads.devRecord.size());

        // The default gun pack only has per-face UV cubes. Build box-UV ones by hand: plain, mirrored, and one flat
        // in each axis (texOffX, texOffY, x, y, z, width, height, depth, delta, mirror, texWidth, texHeight).
        var box = Class.forName("com.tacz.guns.client.model.bedrock.BedrockCubeBox").getConstructor(float.class,
                float.class, float.class, float.class, float.class, float.class, float.class, float.class, float.class,
                boolean.class, float.class, float.class);
        box.newInstance(4F, 8F, -2F, 1F, -3F, 3F, 5F, 2F, 0F, false, 64F, 32F);
        box.newInstance(4F, 8F, -2F, 1F, -3F, 3F, 5F, 2F, 0.25F, true, 64F, 32F);
        box.newInstance(0F, 0F, 1F, 2F, 3F, 0F, 4F, 5F, 0F, false, 16F, 16F);
        box.newInstance(0F, 0F, 1F, 2F, 3F, 4F, 0F, 5F, 0F, true, 16F, 16F);
        box.newInstance(0F, 0F, 1F, 2F, 3F, 4F, 5F, 0F, 0F, false, 16F, 16F);

        List<Object> cubes;
        synchronized (TaczBedrockQuads.devRecord) {
            cubes = new ArrayList<>(TaczBedrockQuads.devRecord);
        }

        var stack = new PoseStack();
        stack.translate(0.3F, -1.2F, 2.5F);
        stack.mulPose(Axis.XP.rotationDegrees(33));
        stack.mulPose(Axis.YP.rotationDegrees(-71));
        stack.mulPose(Axis.ZP.rotationDegrees(12));
        stack.scale(1.5F, 0.75F, 1.25F);
        var pose = stack.last();

        var failures = new ArrayList<String>();
        var perClass = new TreeMap<String, Integer>();
        long taczQuads = 0;
        long fastQuads = 0;
        var withoutCopy = 0;

        for (var cube : cubes) {
            perClass.merge(cube.getClass().getSimpleName(), 1, Integer::sum);
            var copyField = field(cube);
            var compile = compile(cube);
            var copy = (float[]) copyField.get(cube);
            if (copy == null) {
                withoutCopy++;
                continue;
            }

            var fast = draw(compile, cube, pose);
            copyField.set(cube, null);
            List<float[]> tacz;
            try {
                tacz = draw(compile, cube, pose);
            } finally {
                copyField.set(cube, copy);
            }

            taczQuads += tacz.size() / 4;
            fastQuads += fast.size() / 4;
            var problem = compare(tacz, fast);
            if (problem != null && failures.size() < 20) {
                failures.add(cube.getClass().getSimpleName() + " #" + cubes.indexOf(cube) + ": " + problem);
            } else if (problem != null) {
                failures.add(null);
            }
        }

        var timing = time(cubes, pose);

        out.add("cubes recorded: " + cubes.size() + " " + perClass);
        out.add("cubes without a flat copy (fast path not taken): " + withoutCopy);
        out.add("quads drawn: TACZ " + taczQuads + ", fast path " + fastQuads + " (" + (taczQuads - fastQuads) + " zero-area skipped)");
        out.add(timing);
        check(out, "cubes-recorded", cubes.isEmpty() ? List.of("no TACZ cube was built before the title screen") : List.of());
        check(out, "both-cube-types", perClass.size() < 2 ? List.of("only saw " + perClass.keySet()) : List.of());
        check(out, "fast-path-taken", withoutCopy > 0 ? List.of(withoutCopy + " cubes have no flat copy") : List.of());
        check(out, "zero-area-path-exercised", taczQuads == fastQuads ? List.of("no quad was skipped, the filter is untested") : List.of());
        var shown = failures.stream().filter(f -> f != null).toList();
        var extra = failures.size() - shown.size();
        var parity = new ArrayList<>(shown);
        if (extra > 0) {
            parity.add("... and " + extra + " more cubes");
        }

        check(out, "parity", parity);
        out.add(out.stream().anyMatch(l -> l.startsWith("FAIL")) ? "done: FAILURE" : "done: ALL PASS");
    }

    /**
     * Fast-path quads must be TACZ's quads, in order, with some skipped; each skipped one must have zero area as
     * drawn. Positions and normals compared to 1e-5 relative (the fast path folds the matrix multiply by hand);
     * colour, uv, overlay and light must be exact.
     */
    private static String compare(List<float[]> tacz, List<float[]> fast) {
        var t = 0;
        for (var f = 0; f < fast.size(); f += 4) {
            while (t < tacz.size() && !sameQuad(tacz, t, fast, f)) {
                if (!zeroArea(tacz, t)) {
                    return "TACZ quad " + t / 4 + " has area but the fast path did not draw it identically";
                }

                t += 4;
            }

            if (t >= tacz.size()) {
                return "fast path drew quad " + f / 4 + " that TACZ does not draw";
            }

            t += 4;
        }

        for (; t < tacz.size(); t += 4) {
            if (!zeroArea(tacz, t)) {
                return "trailing TACZ quad " + t / 4 + " has area but was skipped";
            }
        }

        return null;
    }

    private static boolean sameQuad(List<float[]> a, int ai, List<float[]> b, int bi) {
        for (var i = 0; i < 4; i++) {
            var x = a.get(ai + i);
            var y = b.get(bi + i);
            for (var k = 0; k < x.length; k++) {
                var exact = k >= 3 && k <= 7; // colour, u, v, overlay, light
                if (exact ? x[k] != y[k] : Math.abs(x[k] - y[k]) > 1e-5F * Math.max(1.0F, Math.abs(x[k]))) {
                    return false;
                }
            }
        }

        return true;
    }

    private static boolean zeroArea(List<float[]> quad, int at) {
        var p = new Vector3f[4];
        for (var i = 0; i < 4; i++) {
            var v = quad.get(at + i);
            p[i] = new Vector3f(v[0], v[1], v[2]);
        }

        var d1 = new Vector3f(p[2]).sub(p[0]);
        var d2 = new Vector3f(p[3]).sub(p[1]);
        var scale = Math.max(d1.lengthSquared(), d2.lengthSquared());
        return d1.cross(d2).length() <= 1e-5F * Math.max(scale, 1e-6F);
    }

    private static String time(List<Object> cubes, PoseStack.Pose pose) throws ReflectiveOperationException {
        var sink = new Recorder(false);
        var copies = new float[cubes.size()][];
        for (var i = 0; i < cubes.size(); i++) {
            copies[i] = (float[]) field(cubes.get(i)).get(cubes.get(i));
        }

        long taczNs = 0;
        long fastNs = 0;
        for (var round = 0; round < 6; round++) {
            for (var withCopy : new boolean[] { false, true }) {
                for (var i = 0; i < cubes.size(); i++) {
                    field(cubes.get(i)).set(cubes.get(i), withCopy ? copies[i] : null);
                }

                var start = System.nanoTime();
                for (var rep = 0; rep < 20; rep++) {
                    for (var cube : cubes) {
                        compile(cube).invoke(cube, pose, sink, 0xF000F0, 0, 1.0F, 1.0F, 1.0F, 1.0F);
                    }
                }

                var took = System.nanoTime() - start;
                if (round >= 2) { // first rounds are JIT warm-up
                    if (withCopy) {
                        fastNs += took;
                    } else {
                        taczNs += took;
                    }
                }
            }
        }

        for (var i = 0; i < cubes.size(); i++) {
            field(cubes.get(i)).set(cubes.get(i), copies[i]);
        }

        return String.format("time for 4 x 20 passes over all cubes (incl. reflective call): TACZ %.1f ms, fast path %.1f ms",
                taczNs / 1e6, fastNs / 1e6);
    }

    private static List<float[]> draw(Method compile, Object cube, PoseStack.Pose pose) throws ReflectiveOperationException {
        var recorder = new Recorder(true);
        compile.invoke(cube, pose, recorder, 0xF000F0, 3, 0.9F, 0.8F, 0.7F, 0.6F);
        return recorder.vertices;
    }

    private static final Map<Class<?>, Field> FIELDS = new java.util.HashMap<>();
    private static final Map<Class<?>, Method> METHODS = new java.util.HashMap<>();

    private static Field field(Object cube) {
        return FIELDS.computeIfAbsent(cube.getClass(), c -> {
            try {
                var f = c.getDeclaredField("foxstweaks$quads");
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException("mixin not applied to " + c.getName(), e);
            }
        });
    }

    private static Method compile(Object cube) {
        return METHODS.computeIfAbsent(cube.getClass(), c -> {
            try {
                return c.getMethod("compile", PoseStack.Pose.class, VertexConsumer.class, int.class, int.class,
                        float.class, float.class, float.class, float.class);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static void check(List<String> out, String name, List<String> failures) {
        if (failures.isEmpty()) {
            out.add("PASS " + name);
            return;
        }

        out.add("FAIL " + name);
        failures.forEach(f -> out.add("    " + f));
    }

    /** Records each full vertex as x, y, z, colour, u, v, overlay, light, nx, ny, nz. */
    private static final class Recorder implements VertexConsumer {
        final List<float[]> vertices = new ArrayList<>();
        private final boolean keep;

        Recorder(boolean keep) {
            this.keep = keep;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light,
                float nx, float ny, float nz) {
            if (keep) {
                vertices.add(new float[] { x, y, z, Float.intBitsToFloat(color), u, v, overlay, light, nx, ny, nz });
            }
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            throw new UnsupportedOperationException("expected the full addVertex");
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            return this;
        }
    }

    private TaczModelCheck() {
    }
}
