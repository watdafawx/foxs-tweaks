package dev.watdafawx.foxstweaks.client.asset;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Optional local Blender bridge for converting {@code .fbx} files to {@code .glb}.
 * Searches {@code PATH} and standard Blender install directories; if Blender is not installed, throws a clear
 * message guiding the user to install Blender or pick the pack's {@code glTF/} / {@code OBJ/} folder instead.
 */
public final class FbxConverter {
    private static volatile String cachedBlender;
    private static volatile boolean searched;

    private FbxConverter() {
    }

    @Nullable
    public static String findBlender() {
        if (searched)
            return cachedBlender;
        searched = true;
        cachedBlender = locate();
        return cachedBlender;
    }

    public static byte[] convertFile(Path fbxPath) throws IOException {
        String blender = findBlender();
        if (blender == null) {
            throw new IOException("Blender is required to import .fbx (install Blender, or use the pack's glTF/OBJ folder)");
        }
        Path outGlb = Files.createTempFile("foxstweaks-fbx-", ".glb");
        Path script = Files.createTempFile("foxstweaks-fbx-", ".py");
        try {
            String py = """
                    import bpy, sys
                    argv = sys.argv[sys.argv.index('--') + 1:]
                    bpy.ops.wm.read_factory_settings(use_empty=True)
                    bpy.ops.import_scene.fbx(filepath=argv[0])
                    bpy.ops.export_scene.gltf(filepath=argv[1], export_format='GLB', export_image_format='AUTO')
                    """;
            Files.writeString(script, py);

            ProcessBuilder pb = new ProcessBuilder(
                    blender, "--background", "--factory-startup", "--python", script.toString(),
                    "--", fbxPath.toAbsolutePath().toString(), outGlb.toAbsolutePath().toString());
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            // Drain output so Blender never blocks on a full pipe.
            byte[] log = proc.getInputStream().readNBytes(32768);
            boolean done = proc.waitFor(60, TimeUnit.SECONDS);
            if (!done) {
                proc.destroyForcibly();
                throw new IOException("Blender timed out converting .fbx");
            }
            if (proc.exitValue() != 0 || !Files.isRegularFile(outGlb) || Files.size(outGlb) < 12) {
                String tail = new String(log).trim();
                if (tail.length() > 140)
                    tail = tail.substring(tail.length() - 140);
                throw new IOException("Blender failed to convert .fbx" + (tail.isEmpty() ? "" : ": " + tail));
            }
            return Files.readAllBytes(outGlb);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("FBX conversion interrupted");
        } finally {
            Files.deleteIfExists(outGlb);
            Files.deleteIfExists(script);
        }
    }

    @Nullable
    private static String locate() {
        // 1. Check PATH
        String pathEnv = System.getenv("PATH");
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exeName = win ? "blender.exe" : "blender";
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.io.File.pathSeparator)) {
                if (dir.isBlank())
                    continue;
                Path cand = Path.of(dir.trim(), exeName);
                if (Files.isRegularFile(cand))
                    return cand.toString();
            }
        }
        // 2. Standard install directories
        if (win) {
            Path bf = Path.of("C:\\Program Files\\Blender Foundation");
            if (Files.isDirectory(bf)) {
                try (Stream<Path> s = Files.list(bf)) {
                    Path found = s.filter(Files::isDirectory)
                            .sorted(Comparator.reverseOrder())
                            .map(d -> d.resolve("blender.exe"))
                            .filter(Files::isRegularFile)
                            .findFirst().orElse(null);
                    if (found != null)
                        return found.toString();
                } catch (IOException ignored) {
                }
            }
            Path steam = Path.of("C:\\Program Files (x86)\\Steam\\steamapps\\common\\Blender\\blender.exe");
            if (Files.isRegularFile(steam))
                return steam.toString();
        } else {
            Path[] unix = {
                    Path.of("/Applications/Blender.app/Contents/MacOS/Blender"),
                    Path.of("/usr/bin/blender"),
                    Path.of("/usr/local/bin/blender"),
                    Path.of("/snap/bin/blender")
            };
            for (Path p : unix) {
                if (Files.isRegularFile(p))
                    return p.toString();
            }
        }
        return null;
    }
}
