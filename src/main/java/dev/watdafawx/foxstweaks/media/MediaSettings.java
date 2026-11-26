package dev.watdafawx.foxstweaks.media;

/**
 * The handful of server settings the video import reads, as plain values.
 *
 * <p>Deliberately not the config spec itself: {@link FfmpegLocator} and {@link VideoConverter} must be
 * runnable from a plain JVM (see {@code media/dev/VideoConverterSelfTest}), where no config file and no
 * NeoForge class exists. {@link MediaConfig} is the only place that touches the spec.
 */
public record MediaSettings(boolean enabled, String ffmpegPath, int convertTimeoutSeconds) {
    public static final MediaSettings DEFAULTS = new MediaSettings(true, "", 60);

    public MediaSettings {
        if (ffmpegPath == null) ffmpegPath = "";
        if (convertTimeoutSeconds < 1) convertTimeoutSeconds = 1;
    }
}
