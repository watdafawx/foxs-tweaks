package dev.watdafawx.foxstweaks.media.dev;

import dev.watdafawx.foxstweaks.media.FfmpegLocator;
import dev.watdafawx.foxstweaks.media.MediaSettings;
import dev.watdafawx.foxstweaks.media.VideoConverter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Headless self-test for the video import pipeline. Dev only - the mod never loads it.
 *
 * <p>Runs from the main source set's runtime classpath ({@code build.bat mediaSelfTest}), so it must use
 * nothing but plain Java and the media classes themselves. It also proves that claim: if this class
 * needed a Minecraft type to start, the run would fail before printing anything.
 *
 * <p>Cases: the locator (path, version, caching, fallback), the container gate (a text file renamed
 * {@code .mp4}), a real 3 second {@code testsrc} video converted at the caps, the duration cut, and the
 * timeout path (ffmpeg throttled with {@code -re} and a 1 s timeout, so the kill is hit deterministically
 * instead of depending on a huge video).
 */
public final class VideoConverterSelfTest {
    private static final List<String> LINES = new ArrayList<>();
    private static int passed;
    private static int failed;
    private static int skipped;

    public static void main(String[] args) {
        Path temp = null;
        try {
            temp = Files.createTempDirectory("foxstweaks-media-selftest-");
            run(temp);
        } catch (Exception e) {
            fail("self-test crashed", e.toString());
        } finally {
            if (temp != null) {
                deleteTree(temp);
            }
        }

        LINES.forEach(System.out::println);
        System.out.println();
        System.out.println("SUMMARY: " + passed + " passed, " + failed + " failed, " + skipped + " skipped");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Path temp) throws Exception {
        FfmpegLocator locator = new FfmpegLocator("");
        var ffmpeg = locator.find();

        if (ffmpeg.isEmpty()) {
            skip("locator finds ffmpeg", "no ffmpeg on this machine (install ffmpeg or set mediaFfmpegPath)");
            skip("conversion of a 3 s testsrc video", "needs ffmpeg");
            skip("duration cut at maxSeconds", "needs ffmpeg");
            skip("timeout kills the process tree", "needs ffmpeg");
        } else {
            pass("locator finds ffmpeg", ffmpeg.get().path() + " :: " + ffmpeg.get().version());
            checkLocatorCache(locator, ffmpeg.get());
            checkFallback(temp);
        }

        checkDisabled(temp);
        checkContainerGate(temp);

        if (ffmpeg.isEmpty()) {
            return;
        }

        Path video = makeTestVideo(ffmpeg.get(), temp);
        checkConversion(ffmpeg.get(), video, temp);
        checkDurationCut(video, temp);
        checkFrameCap(video, temp);
        checkByteCap(video, temp);
        checkTimeout(ffmpeg.get(), video, temp);
    }

    private static void checkLocatorCache(FfmpegLocator locator, FfmpegLocator.Ffmpeg found) {
        var again = locator.find();
        if (again.isPresent() && again.get().equals(found)) {
            pass("locator caches its answer", "second find() returned the same binary with no new probe");
        } else {
            fail("locator caches its answer", "second find() returned " + again);
        }
    }

    private static void checkFallback(Path temp) {
        Path notHere = temp.resolve("no-ffmpeg-here").resolve("ffmpeg.exe");
        var fallback = new FfmpegLocator(notHere.toString()).find();
        if (fallback.isPresent()) {
            pass("a wrong mediaFfmpegPath falls back to PATH", "found " + fallback.get().path());
        } else {
            fail("a wrong mediaFfmpegPath falls back to PATH", "found nothing");
        }
    }

    private static void checkDisabled(Path temp) throws IOException {
        Path input = temp.resolve("disabled.mp4");
        Files.writeString(input, "not a video");
        Path out = temp.resolve("out-disabled");
        VideoConverter converter = new VideoConverter(
                new FfmpegLocator(""), new MediaSettings(false, "", 60));

        var result = converter.convert(input, out, VideoConverter.Limits.DEFAULTS);
        if (!result.ok() && result.error() != null && result.error().contains("mediaFfmpegEnabled")) {
            pass("mediaFfmpegEnabled = false refuses the import", result.error());
        } else {
            fail("mediaFfmpegEnabled = false refuses the import", result.toString());
        }
    }

    private static void checkContainerGate(Path temp) throws IOException {
        Path fake = temp.resolve("really-a-text-file.mp4");
        Files.writeString(fake, "This is a text file with a video extension.\nIt must never reach ffmpeg.\n");

        Path out = temp.resolve("out-fake");
        VideoConverter converter = new VideoConverter(new FfmpegLocator(""), new MediaSettings(true, "", 60));
        var result = converter.convert(fake, out, VideoConverter.Limits.DEFAULTS);

        boolean rejected = !result.ok()
                && result.error() != null
                && result.error().contains("not a supported video container");
        long frames = countFrames(out);
        if (rejected && frames == 0) {
            pass("a text file renamed .mp4 is rejected", result.error());
        } else {
            fail("a text file renamed .mp4 is rejected", "ok=" + result.ok() + " error=" + result.error()
                    + " frames=" + frames);
        }
    }

    private static Path makeTestVideo(FfmpegLocator.Ffmpeg ffmpeg, Path temp) throws Exception {
        Path video = temp.resolve("testsrc.mp4");
        Path log = temp.resolve("testsrc.log");
        List<String> args = List.of(
                "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=30:duration=3",
                "-c:v", "mpeg4", "-q:v", "4", "-pix_fmt", "yuv420p",
                video.toString());

        int exit = runProcess(ffmpeg.path(), args, log);
        if (exit != 0 || !Files.isRegularFile(video)) {
            throw new IOException("could not build the test video (exit " + exit + "): " + Files.readString(log));
        }

        return video;
    }

    private static void checkConversion(FfmpegLocator.Ffmpeg ffmpeg, Path video, Path temp) throws IOException {
        VideoConverter.Limits limits = VideoConverter.Limits.DEFAULTS;
        VideoConverter converter = new VideoConverter(new FfmpegLocator(""), new MediaSettings(true, "", 60));
        Path out = temp.resolve("out");

        Path pattern = out.resolve("frame_%04d.jpg");
        LINES.add("INFO   ffmpeg argv: " + VideoConverter.arguments(ffmpeg, video, pattern, limits, List.of()));
        var result = converter.convert(video, out, limits);
        if (!result.ok()) {
            fail("converts a 3 s testsrc video", result.error());
            return;
        }

        pass("converts a 3 s testsrc video", result.frames().size() + " frames, "
                + result.width() + "x" + result.height() + ", " + result.fps() + " fps");

        int expected = (int) Math.round(3 * (double) limits.maxFps());
        if (result.frames().size() >= expected - 2 && result.frames().size() <= expected + 1) {
            pass("frame count matches 3 s at the fps cap", result.frames().size() + " frames, expected ~" + expected);
        } else {
            fail("frame count matches 3 s at the fps cap", result.frames().size() + " frames, expected ~" + expected);
        }

        if (result.width() == 256 && result.height() == 192) {
            pass("scaled to the longest side of 256 keeping 4:3", result.width() + "x" + result.height());
        } else {
            fail("scaled to the longest side of 256 keeping 4:3", result.width() + "x" + result.height());
        }

        if (Math.max(result.width(), result.height()) <= limits.maxDimension()) {
            pass("no side goes over maxDimension", Math.max(result.width(), result.height()) + " px");
        } else {
            fail("no side goes over maxDimension", Math.max(result.width(), result.height()) + " px");
        }

        long bytes = 0L;
        boolean insideOutDir = true;
        boolean namesSequential = true;
        for (int i = 0; i < result.frames().size(); i++) {
            Path frame = result.frames().get(i);
            bytes += Files.size(frame);
            insideOutDir &= frame.getParent().equals(out);
            namesSequential &= frame.getFileName().toString()
                    .equals(String.format("frame_%04d.jpg", i + 1));
        }

        if (bytes > 0 && bytes <= limits.maxBytes()) {
            pass("output size is within the byte cap", bytes + " bytes, cap " + limits.maxBytes());
        } else {
            fail("output size is within the byte cap", bytes + " bytes, cap " + limits.maxBytes());
        }

        if (insideOutDir && namesSequential) {
            pass("frames land in the caller's directory as frame_0001.jpg...",
                    result.frames().get(0).getFileName() + " to "
                            + result.frames().get(result.frames().size() - 1).getFileName());
        } else {
            fail("frames land in the caller's directory as frame_0001.jpg...",
                    "insideOutDir=" + insideOutDir + " namesSequential=" + namesSequential);
        }

        if (hasNoLeftoverWorkDirs(temp)) {
            pass("no private temp folder is left behind", "the conversion folder was cleaned up");
        } else {
            fail("no private temp folder is left behind", "a foxstweaks-media-* folder survived");
        }
    }

    private static void checkDurationCut(Path video, Path temp) throws IOException {
        VideoConverter.Limits limits = new VideoConverter.Limits(128, 10, 1, 8L * 1024 * 1024, 1024);
        VideoConverter converter = new VideoConverter(new FfmpegLocator(""), new MediaSettings(true, "", 60));
        Path out = temp.resolve("out-cut");

        var result = converter.convert(video, out, limits);
        if (!result.ok()) {
            fail("cuts the video at maxSeconds", result.error());
            return;
        }

        int expected = (int) Math.round(1 * (double) limits.maxFps());
        boolean framesOk = result.frames().size() >= expected - 2 && result.frames().size() <= expected + 1;
        boolean sizeOk = Math.max(result.width(), result.height()) <= limits.maxDimension();
        if (framesOk && sizeOk) {
            pass("cuts the video at maxSeconds", result.frames().size() + " frames for 1 s at 10 fps, "
                    + result.width() + "x" + result.height());
        } else {
            fail("cuts the video at maxSeconds", result.frames().size() + " frames (" + expected + " expected), "
                    + result.width() + "x" + result.height());
        }
    }

    private static void checkFrameCap(Path video, Path temp) throws IOException {
        VideoConverter.Limits limits = new VideoConverter.Limits(256, 15, 10, 64L * 1024 * 1024, 5);
        VideoConverter converter = new VideoConverter(new FfmpegLocator(""), new MediaSettings(true, "", 60));
        Path out = temp.resolve("out-framecap");

        var result = converter.convert(video, out, limits);
        if (result.ok() && result.frames().size() == 5) {
            pass("maxFrames stops the sequence at the cap", "3 s of video, " + result.frames().size() + " frames kept");
        } else {
            fail("maxFrames stops the sequence at the cap",
                    "ok=" + result.ok() + " frames=" + result.frames().size() + " error=" + result.error());
        }
    }

    private static void checkByteCap(Path video, Path temp) throws IOException {
        // One frame of a 256 px JPEG alone is past 4 KB, so either ffmpeg's -fs or the polling guard has
        // to stop this; both paths must come back as a failure naming the cap, never a partial success.
        VideoConverter.Limits limits = new VideoConverter.Limits(256, 15, 10, 4096, 1024);
        VideoConverter converter = new VideoConverter(new FfmpegLocator(""), new MediaSettings(true, "", 60));
        Path out = temp.resolve("out-bytecap");

        var result = converter.convert(video, out, limits);
        boolean refused = !result.ok() && result.error() != null && result.error().contains("byte cap");
        if (refused && countFrames(out) == 0) {
            pass("maxBytes stops an oversized sequence", result.error());
        } else {
            fail("maxBytes stops an oversized sequence",
                    "ok=" + result.ok() + " frames=" + countFrames(out) + " error=" + result.error());
        }
    }

    private static void checkTimeout(FfmpegLocator.Ffmpeg ffmpeg, Path video, Path temp) throws IOException {
        // -re makes ffmpeg read the 3 s input in real time, so a 1 s timeout always fires; the timeout
        // itself is a whole number of seconds, hence the throttle rather than a tiny video.
        VideoConverter converter = new VideoConverter(
                new FfmpegLocator(ffmpeg.path().toString()), new MediaSettings(true, "", 1));
        Path out = temp.resolve("out-timeout");

        long started = System.nanoTime();
        var result = converter.convert(video, out, VideoConverter.Limits.DEFAULTS, List.of("-re"));
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        boolean timedOut = !result.ok() && result.error() != null && result.error().contains("timed out");
        long frames = countFrames(out);
        // Allow for the 2 s kill grace plus process startup; far below the 3 s the throttled read needs.
        boolean quick = millis < 6000;

        if (timedOut && frames == 0 && quick) {
            pass("timeout kills ffmpeg and leaves no frames", millis + " ms, " + result.error());
        } else {
            fail("timeout kills ffmpeg and leaves no frames",
                    millis + " ms, ok=" + result.ok() + " frames=" + frames + " error=" + result.error());
        }
    }

    private static boolean hasNoLeftoverWorkDirs(Path temp) throws IOException {
        try (Stream<Path> files = Files.list(temp)) {
            return files.noneMatch(p -> p.getFileName().toString().startsWith("foxstweaks-media-"));
        }
    }

    private static long countFrames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return 0L;
        }

        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(p -> p.getFileName().toString().startsWith("frame_")).count();
        }
    }

    private static int runProcess(Path exe, List<String> args, Path log) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.addAll(args);

        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        try {
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("ffmpeg did not finish building the test video within 60 s");
            }

            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Test cleanup.
                }
            });
        } catch (IOException ignored) {
            // Test cleanup.
        }
    }

    private static void pass(String name, String detail) {
        passed++;
        LINES.add("[PASS] " + name + (detail == null || detail.isBlank() ? "" : " :: " + detail));
    }

    private static void fail(String name, String detail) {
        failed++;
        LINES.add("[FAIL] " + name + (detail == null || detail.isBlank() ? "" : " :: " + detail));
    }

    private static void skip(String name, String reason) {
        skipped++;
        LINES.add("[SKIP] " + name + " :: " + reason);
    }

    private VideoConverterSelfTest() {
    }
}
