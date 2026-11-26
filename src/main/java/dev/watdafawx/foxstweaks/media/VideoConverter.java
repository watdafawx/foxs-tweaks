package dev.watdafawx.foxstweaks.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Turns an uploaded video into a numbered JPEG frame sequence, by running ffmpeg as a separate process.
 *
 * <p>Frames are what a hologram layer needs (see {@code client/web} and {@code docs/hologram-web-protocol.md}):
 * {@code frame_0001.jpg} and up, at a bounded resolution and frame rate, with no audio.
 *
 * <p>Everything here is deliberately plain Java - no Minecraft type - so the pipeline can be driven from
 * a test without a game. The rules that matter on a live server:
 *
 * <ul>
 *   <li>One conversion at a time per server ({@link #SLOT}); up to {@value #MAX_QUEUE} callers wait, the
 *       rest are refused instead of piling up. <b>Callers must not run this on the server thread</b> - a
 *       conversion can take the whole timeout.</li>
 *   <li>ffmpeg is started with an argument <em>list</em>, never a shell string, and its stdin is closed,
 *       so an uploaded file name can never be read as an option or a command.</li>
 *   <li>Frames are written into a private temp folder and only moved into the caller's directory once
 *       the whole conversion succeeded (or not at all), so a failure leaves no half a sequence behind.
 *       The input is the caller's file - normally already in a private temp folder it made - and is
 *       never modified or deleted here</li>
 *   <li>Duration, frame rate, resolution, frame count and total bytes are all capped, and the process tree
 *       is killed on timeout</li>
 * </ul>
 */
public final class VideoConverter {
    /** Waiters allowed behind the one running conversion. */
    public static final int MAX_QUEUE = 4;

    private static final System.Logger LOG = System.getLogger("foxstweaks/media");
    private static final String FRAME_PREFIX = "frame_";
    private static final String FRAME_SUFFIX = ".jpg";
    private static final String FRAME_PATTERN = FRAME_PREFIX + "%04d" + FRAME_SUFFIX;
    private static final long POLL_MILLIS = 50;
    private static final long KILL_GRACE_MILLIS = 2000;

    private static final Semaphore SLOT = new Semaphore(1, true);
    private static final AtomicInteger WAITING = new AtomicInteger();

    private final FfmpegLocator locator;
    private final MediaSettings settings;

    /**
     * What one import costs, in the units a hologram cares about.
     *
     * @param maxDimension longest side of an output frame, in pixels
     * @param maxFps       output frame rate
     * @param maxSeconds   how much of the start of the video is kept
     * @param maxBytes     total size of the produced frames
     * @param maxFrames    hard frame count ceiling, on top of {@code maxSeconds * maxFps}
     */
    public record Limits(int maxDimension, int maxFps, int maxSeconds, long maxBytes, int maxFrames) {
        public static final Limits DEFAULTS = new Limits(256, 15, 10, 64L * 1024 * 1024, 1024);

        public Limits {
            if (maxDimension < 8 || maxDimension > 4096) {
                throw new IllegalArgumentException("maxDimension must be 8..4096, was " + maxDimension);
            }

            if (maxFps < 1 || maxFps > 60) {
                throw new IllegalArgumentException("maxFps must be 1..60, was " + maxFps);
            }

            if (maxSeconds < 1 || maxSeconds > 600) {
                throw new IllegalArgumentException("maxSeconds must be 1..600, was " + maxSeconds);
            }

            if (maxBytes < 4096) {
                throw new IllegalArgumentException("maxBytes must be at least 4096, was " + maxBytes);
            }

            if (maxFrames < 1) {
                throw new IllegalArgumentException("maxFrames must be positive, was " + maxFrames);
            }
        }
    }

    /**
     * The outcome: on success the frames now sitting in the caller's directory, with their size and rate;
     * on failure an empty list and a message fit to show a player.
     */
    public record Conversion(boolean ok, List<Path> frames, double fps, int width, int height, String error) {
        public Conversion {
            frames = frames == null ? List.of() : List.copyOf(frames);
        }

        public static Conversion success(List<Path> frames, double fps, int width, int height) {
            return new Conversion(true, frames, fps, width, height, null);
        }

        public static Conversion failure(String error) {
            return new Conversion(false, List.of(), 0.0, 0, 0, error);
        }
    }

    /** The containers the gate accepts, decided from the first bytes of the file - not from its name. */
    public enum Container {
        MP4, WEBM, GIF, AVI
    }

    public VideoConverter(FfmpegLocator locator, MediaSettings settings) {
        this.locator = locator;
        this.settings = settings;
    }

    /** The converter the mod uses: ffmpeg from {@code mediaFfmpegPath}, timeout from the server config. */
    public static VideoConverter server() {
        MediaSettings settings = MediaConfig.read();
        return new VideoConverter(new FfmpegLocator(settings.ffmpegPath()), settings);
    }

    public Conversion convert(Path input, Path outDir, Limits limits) {
        return convert(input, outDir, limits, List.of());
    }

    /**
     * Same as {@link #convert(Path, Path, Limits)}, with extra ffmpeg <em>input</em> options placed right
     * before {@code -i}.
     *
     * <p>Exists for the dev self-test, which passes {@code -re} to be able to hit the timeout with a tiny
     * timeout instead of a huge video; nothing in the mod calls it.
     */
    public Conversion convert(Path input, Path outDir, Limits limits, List<String> extraInputOptions) {
        if (!settings.enabled()) {
            return Conversion.failure("video import is disabled (mediaFfmpegEnabled = false)");
        }

        if (input == null || outDir == null || limits == null) {
            return Conversion.failure("input file, output directory and limits are all required");
        }

        try {
            if (!Files.isRegularFile(input)) {
                return Conversion.failure("input file not found: " + input);
            }

            if (Files.size(input) == 0L) {
                return Conversion.failure("input file is empty: " + input.getFileName());
            }

            Container container = detect(input);
            if (container == null) {
                return Conversion.failure("not a supported video container (mp4/mov, webm/mkv, gif or avi): "
                        + input.getFileName());
            }

            var ffmpeg = locator.find();
            if (ffmpeg.isEmpty()) {
                return Conversion.failure("ffmpeg not found; set mediaFfmpegPath or put ffmpeg on the server PATH");
            }

            if (!acquire()) {
                return Conversion.failure("the video import queue is full (" + MAX_QUEUE
                        + " waiting); try again once the running import finishes");
            }

            try {
                return run(ffmpeg.get(), input, outDir, limits, extraInputOptions);
            } finally {
                SLOT.release();
            }
        } catch (IOException e) {
            return Conversion.failure("could not read the upload: " + e.getMessage());
        } catch (RuntimeException e) {
            // A surprise must not take the whole server thread down with it.
            return Conversion.failure("video conversion failed: " + e);
        }
    }

    /**
     * The exact argv handed to ffmpeg. Public so the self-test (and a curious operator) can print it:
     * nothing is ever passed through a shell.
     *
     * <p>{@code fps} is pinned to the cap rather than taken from the source, because the source rate is
     * unknown until it is decoded; asking for the cap keeps a 60 fps phone video down to 15 and a slow
     * one up to 15, and makes the frame count predictable ({@code maxSeconds * maxFps}).
     */
    public static List<String> arguments(FfmpegLocator.Ffmpeg ffmpeg, Path input, Path framesPattern,
                                         Limits limits, List<String> extraInputOptions) {
        List<String> args = new ArrayList<>();
        args.add(ffmpeg.path().toString());
        args.add("-nostdin");
        args.add("-hide_banner");
        args.add("-loglevel");
        args.add("error");
        args.add("-y");

        if (extraInputOptions != null) {
            args.addAll(extraInputOptions);
        }

        args.add("-i");
        args.add(input.toString());
        args.add("-t");
        args.add(Integer.toString(limits.maxSeconds()));
        args.add("-an");
        args.add("-sn");
        args.add("-dn");

        int dimension = limits.maxDimension();
        // `min(dim, iw)` keeps a small source small instead of blowing it up to the cap;
        // force_divisible_by=2 keeps the JPEG encoder happy. The quotes are for ffmpeg's own filtergraph
        // parser (the commas inside min(...) would otherwise split the options), not for a shell.
        args.add("-vf");
        args.add("fps=" + limits.maxFps()
                + ",scale=w='min(" + dimension + ",iw)':h='min(" + dimension + ",ih)'"
                + ":force_original_aspect_ratio=decrease:force_divisible_by=2");

        args.add("-frames:v");
        args.add(Integer.toString(frameCap(limits)));
        // No -fs here on purpose: with the image2 muxer it limits each output *file*, and ffmpeg writes
        // one file per frame, so it would cap every frame instead of the sequence. The total is enforced
        // by watching the temp folder while ffmpeg runs (frameBytes) and by the final sum.
        args.add("-q:v");
        args.add("5");
        args.add(framesPattern.toString());
        return args;
    }

    private Conversion run(FfmpegLocator.Ffmpeg ffmpeg, Path input, Path outDir, Limits limits,
                           List<String> extraInputOptions) {
        Path work = null;
        Process process = null;
        try {
            Files.createDirectories(outDir);
            work = Files.createTempDirectory("foxstweaks-media-");
            Path log = work.resolve("ffmpeg.log");
            Path pattern = work.resolve(FRAME_PATTERN);

            List<String> args = arguments(ffmpeg, input, pattern, limits, extraInputOptions);
            LOG.log(System.Logger.Level.DEBUG, "ffmpeg {0}", args);

            process = new ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();

            long timeoutSeconds = Math.max(1, settings.convertTimeoutSeconds());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);

            while (!process.waitFor(POLL_MILLIS, TimeUnit.MILLISECONDS)) {
                if (frameBytes(work, limits.maxBytes()) > limits.maxBytes()) {
                    Processes.killTree(process);
                    process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                    return Conversion.failure("output grew past the " + limits.maxBytes()
                            + " byte cap; conversion stopped");
                }

                if (System.nanoTime() >= deadline) {
                    Processes.killTree(process);
                    process.waitFor(KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS);
                    return Conversion.failure("conversion timed out after " + timeoutSeconds
                            + "s (mediaConvertTimeoutSeconds)");
                }
            }

            int exit = process.exitValue();
            List<Path> produced = listFrames(work);
            if (exit != 0) {
                return Conversion.failure("ffmpeg failed (exit " + exit + "): " + tail(log));
            }

            if (produced.isEmpty()) {
                return Conversion.failure("ffmpeg produced no frames: " + tail(log));
            }

            if (produced.size() > frameCap(limits)) {
                return Conversion.failure("ffmpeg produced " + produced.size() + " frames, more than the "
                        + frameCap(limits) + " frame cap");
            }

            long total = totalBytes(produced);
            if (total > limits.maxBytes()) {
                return Conversion.failure("the frames total " + total + " bytes, over the "
                        + limits.maxBytes() + " byte cap");
            }

            int[] size = jpegSize(produced.get(0));
            if (size == null) {
                return Conversion.failure("ffmpeg wrote no readable JPEG frame");
            }

            List<Path> moved = new ArrayList<>(produced.size());
            try {
                for (Path frame : produced) {
                    moved.add(move(frame, outDir.resolve(frame.getFileName().toString())));
                }
            } catch (IOException e) {
                // Do not leave half a sequence in the caller's folder: either all frames or none.
                for (Path frame : moved) {
                    try {
                        Files.deleteIfExists(frame);
                    } catch (IOException ignored) {
                        // Best effort.
                    }
                }

                throw e;
            }

            LOG.log(System.Logger.Level.INFO, "converted {0} to {1} frames at {2}x{3}, {4} fps", input,
                    moved.size(), size[0], size[1], limits.maxFps());
            return Conversion.success(moved, limits.maxFps(), size[0], size[1]);
        } catch (IOException e) {
            return Conversion.failure("video conversion failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Conversion.failure("video conversion was interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                Processes.killTree(process);
            }

            if (work != null) {
                deleteTree(work);
            }
        }
    }

    /**
     * Takes the single conversion slot, or refuses once the queue is full. Counts itself as a waiter
     * first, so the bound covers both the running conversion's queue and callers about to join it.
     */
    private static boolean acquire() {
        int waiting = WAITING.incrementAndGet();
        if (waiting > MAX_QUEUE) {
            WAITING.decrementAndGet();
            return false;
        }

        try {
            SLOT.acquire();
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            WAITING.decrementAndGet();
        }
    }

    private static int frameCap(Limits limits) {
        long byTime = (long) limits.maxSeconds() * limits.maxFps() + 2L;
        return (int) Math.min(limits.maxFrames(), byTime);
    }

    /**
     * The first bytes a container must start with. A file is accepted on its content, never on the name
     * an upload happened to carry - the whole point is to reject a text file renamed {@code .mp4} before
     * ffmpeg ever sees it.
     */
    public static Container detect(Path input) throws IOException {
        byte[] head = new byte[12];
        int read;
        try (InputStream in = Files.newInputStream(input)) {
            read = in.readNBytes(head, 0, head.length);
        }

        if (read >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p') {
            return Container.MP4;
        }

        if (read >= 4 && (head[0] & 0xFF) == 0x1A && (head[1] & 0xFF) == 0x45
                && (head[2] & 0xFF) == 0xDF && (head[3] & 0xFF) == 0xA3) {
            return Container.WEBM;
        }

        if (read >= 6 && head[0] == 'G' && head[1] == 'I' && head[2] == 'F'
                && head[3] == '8' && (head[4] == '7' || head[4] == '9') && head[5] == 'a') {
            return Container.GIF;
        }

        if (read >= 12 && head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F'
                && head[8] == 'A' && head[9] == 'V' && head[10] == 'I' && head[11] == ' ') {
            return Container.AVI;
        }

        return null;
    }

    private static List<Path> listFrames(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith(FRAME_PREFIX) && name.endsWith(FRAME_SUFFIX);
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    /** Bytes of the JPEG frames written so far, stopping as soon as the cap is passed. */
    private static long frameBytes(Path dir, long cap) {
        long total = 0L;
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir)) {
            for (Path file : files) {
                if (!file.getFileName().toString().startsWith(FRAME_PREFIX)) {
                    continue;
                }

                try {
                    total += Files.size(file);
                } catch (IOException ignored) {
                    // A file ffmpeg is still writing may briefly be missing; the next poll sees it.
                }

                if (total > cap) {
                    return total;
                }
            }
        } catch (IOException ignored) {
            // The directory itself can vanish on cleanup; the caller checks the real total afterwards.
        }

        return total;
    }

    private static long totalBytes(List<Path> frames) throws IOException {
        long total = 0L;
        for (Path frame : frames) {
            total += Files.size(frame);
        }

        return total;
    }

    /** Moves across filesystems are not supported everywhere, so fall back to copy + delete. */
    private static Path move(Path source, Path target) throws IOException {
        try {
            return Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(source);
            return target;
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover temp file is not worth failing the import over.
                }
            });
        } catch (IOException ignored) {
            // Same.
        }
    }

    private static String tail(Path log) {
        try {
            if (!Files.isRegularFile(log)) {
                return "";
            }

            String text = Files.readString(log, StandardCharsets.UTF_8).strip();
            int limit = 400;
            return text.length() <= limit ? text : text.substring(text.length() - limit);
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Width and height from a JPEG's SOF header. Done by hand rather than through ImageIO so the class
     * stays free of {@code java.desktop} - a server without the AWT module would otherwise fail here.
     */
    private static int[] jpegSize(Path jpeg) {
        try {
            byte[] data = Files.readAllBytes(jpeg);
            if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8) {
                return null;
            }

            int i = 2;
            while (i + 9 < data.length) {
                if ((data[i] & 0xFF) != 0xFF) {
                    i++;
                    continue;
                }

                int marker = data[i + 1] & 0xFF;
                i += 2;

                if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                    continue; // Standalone markers carry no length.
                }

                if (marker == 0xD9 || marker == 0xDA) {
                    break; // End of image, or the scan starts: the header is behind us.
                }

                int length = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
                boolean startOfFrame = marker >= 0xC0 && marker <= 0xCF
                        && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
                if (startOfFrame) {
                    int height = ((data[i + 3] & 0xFF) << 8) | (data[i + 4] & 0xFF);
                    int width = ((data[i + 5] & 0xFF) << 8) | (data[i + 6] & 0xFF);
                    return width > 0 && height > 0 ? new int[] {width, height} : null;
                }

                if (length < 2) {
                    return null;
                }

                i += length;
            }
        } catch (IOException ignored) {
            // Unreadable frame: the caller reports it.
        }

        return null;
    }
}
