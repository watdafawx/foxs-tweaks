package dev.watdafawx.foxstweaks.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Finds a usable {@code ffmpeg} and remembers the answer.
 *
 * <p>{@code mediaFfmpegPath} may name the executable or the folder holding it. When it is empty the
 * server's {@code PATH} is searched, then the places a package manager usually drops ffmpeg in.
 *
 * <p>A candidate only counts once {@code ffmpeg -version} has actually run and printed a version line
 * within {@value #VERSION_TIMEOUT_SECONDS} seconds: a half-copied download or a stub on {@code PATH}
 * would otherwise be picked silently and then fail on the first real conversion. The answer -
 * including "not found" - is cached for the life of the JVM, so a busy server does not spawn a probe per
 * import; {@link #clearCache()} forgets it after the path is edited in the config.
 *
 * <p>No Minecraft or NeoForge type appears here, so the class also runs in a plain JVM.
 */
public final class FfmpegLocator {
    public static final int VERSION_TIMEOUT_SECONDS = 5;

    private static final System.Logger LOG = System.getLogger("foxstweaks/media");

    private final String configuredPath;
    private final int versionTimeoutSeconds;
    private volatile Optional<Ffmpeg> cached;

    /** A binary that answered {@code -version}: the path that ran, and the first line it printed. */
    public record Ffmpeg(Path path, String version) {
        @Override
        public String toString() {
            return path + " (" + version + ")";
        }
    }

    public FfmpegLocator(String configuredPath) {
        this(configuredPath, VERSION_TIMEOUT_SECONDS);
    }

    public FfmpegLocator(String configuredPath, int versionTimeoutSeconds) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
        this.versionTimeoutSeconds = Math.max(1, versionTimeoutSeconds);
    }

    /** The locator the mod uses: {@code mediaFfmpegPath} from the server config. */
    public static FfmpegLocator server() {
        return new FfmpegLocator(MediaConfig.read().ffmpegPath());
    }

    /**
     * The verified binary, or empty when none was found. Both outcomes are cached.
     */
    public Optional<Ffmpeg> find() {
        Optional<Ffmpeg> known = cached;
        if (known != null) {
            return known;
        }

        synchronized (this) {
            if (cached == null) {
                cached = search();
            }

            return cached;
        }
    }

    /** Forgets the cached answer, so the next {@link #find()} searches again. */
    public void clearCache() {
        synchronized (this) {
            cached = null;
        }
    }

    private Optional<Ffmpeg> search() {
        if (!configuredPath.isEmpty()) {
            Optional<Ffmpeg> configured = probeConfigured(configuredPath);
            if (configured.isPresent()) {
                LOG.log(System.Logger.Level.INFO, "using ffmpeg from mediaFfmpegPath: {0}", configured.get());
                return configured;
            }

            // A typo in the config must not silently pick a different binary without saying so.
            LOG.log(System.Logger.Level.WARNING,
                    "mediaFfmpegPath ''{0}'' is not a usable ffmpeg; searching PATH instead", configuredPath);
        }

        for (Path candidate : candidates()) {
            Optional<Ffmpeg> found = probe(candidate);
            if (found.isPresent()) {
                LOG.log(System.Logger.Level.INFO, "using ffmpeg found at {0}", found.get());
                return found;
            }
        }

        LOG.log(System.Logger.Level.WARNING,
                "ffmpeg not found; set mediaFfmpegPath in foxstweaks-server.toml or install ffmpeg");
        return Optional.empty();
    }

    /**
     * One configured value, in the shapes a server owner actually writes: the exe, the bin folder, or a
     * bare command name that is on {@code PATH} under a non-standard name.
     */
    private Optional<Ffmpeg> probeConfigured(String raw) {
        Path path;
        try {
            path = Path.of(raw);
        } catch (InvalidPathException e) {
            return Optional.empty();
        }

        if (Files.isDirectory(path)) {
            return probe(path.resolve(executableName()));
        }

        if (Files.isRegularFile(path)) {
            Optional<Ffmpeg> found = probe(path);
            if (found.isPresent() || !isWindows()) {
                return found;
            }

            // "C:\ffmpeg\bin\ffmpeg" without the extension is a natural thing to type on Windows.
            return raw.toLowerCase(Locale.ROOT).endsWith(".exe")
                    ? Optional.empty()
                    : probe(path.resolveSibling(path.getFileName() + ".exe"));
        }

        if (raw.indexOf('/') < 0 && raw.indexOf('\\') < 0) {
            return probeProcess(raw, Path.of(raw));
        }

        return Optional.empty();
    }

    private Optional<Ffmpeg> probe(Path exe) {
        if (!Files.isRegularFile(exe)) {
            return Optional.empty();
        }

        Path absolute = exe.toAbsolutePath().normalize();
        return probeProcess(absolute.toString(), absolute);
    }

    private Optional<Ffmpeg> probeProcess(String command, Path reported) {
        Process process = null;
        try {
            process = new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
            if (!process.waitFor(versionTimeoutSeconds, TimeUnit.SECONDS)) {
                LOG.log(System.Logger.Level.DEBUG, "''{0} -version'' did not answer within {1}s",
                        command, versionTimeoutSeconds);
                return Optional.empty();
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (process.exitValue() != 0) {
                return Optional.empty();
            }

            String version = firstNonBlankLine(output);
            if (version == null) {
                return Optional.empty();
            }

            return Optional.of(new Ffmpeg(reported, version.strip()));
        } catch (IOException e) {
            // Access denied, a broken symlink, not actually an executable: just not a candidate.
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } finally {
            if (process != null && process.isAlive()) {
                Processes.killTree(process);
            }
        }
    }

    /** The paths worth probing, cheapest first: every {@code PATH} entry, then the usual install spots. */
    private static List<Path> candidates() {
        Set<Path> found = new LinkedHashSet<>();

        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String entry : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (entry.isBlank()) {
                    continue;
                }

                try {
                    found.add(Path.of(entry).resolve(executableName()));
                } catch (InvalidPathException ignored) {
                    // A malformed PATH entry is not our problem.
                }
            }
        }

        found.addAll(commonLocations());
        return new ArrayList<>(found);
    }

    private static List<Path> commonLocations() {
        List<Path> locations = new ArrayList<>();
        if (isWindows()) {
            locations.add(Path.of("C:\\ffmpeg\\bin\\ffmpeg.exe"));
            addEnv(locations, "ProgramFiles", "ffmpeg\\bin\\ffmpeg.exe");
            addEnv(locations, "ProgramFiles(x86)", "ffmpeg\\bin\\ffmpeg.exe");
            addEnv(locations, "ProgramData", "chocolatey\\bin\\ffmpeg.exe");
            addEnv(locations, "LOCALAPPDATA", "Microsoft\\WinGet\\Links\\ffmpeg.exe");
            addEnv(locations, "USERPROFILE", "scoop\\shims\\ffmpeg.exe");
            addEnv(locations, "USERPROFILE", "scoop\\apps\\ffmpeg\\current\\bin\\ffmpeg.exe");
        } else {
            locations.add(Path.of("/usr/bin/ffmpeg"));
            locations.add(Path.of("/usr/local/bin/ffmpeg"));
            locations.add(Path.of("/snap/bin/ffmpeg"));
            locations.add(Path.of("/usr/local/opt/ffmpeg/bin/ffmpeg")); // Intel Homebrew
            locations.add(Path.of("/opt/homebrew/bin/ffmpeg")); // Apple silicon Homebrew
        }

        return locations;
    }

    private static void addEnv(List<Path> into, String variable, String relative) {
        String root = System.getenv(variable);
        if (root != null && !root.isBlank()) {
            try {
                into.add(Path.of(root).resolve(relative));
            } catch (InvalidPathException ignored) {
                // Nothing to add.
            }
        }
    }

    private static String executableName() {
        return isWindows() ? "ffmpeg.exe" : "ffmpeg";
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line;
            }
        }

        return null;
    }
}
