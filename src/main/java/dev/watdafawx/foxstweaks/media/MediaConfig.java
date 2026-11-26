package dev.watdafawx.foxstweaks.media;

import dev.watdafawx.foxstweaks.ServerConfig;

/**
 * The one door from the video import into the server config.
 *
 * <p>Kept separate from {@link MediaSettings} because it references {@link ServerConfig}: loading this
 * class pulls in the config spec, which does not exist in a plain JVM. Only mod-side callers ever reach
 * it; the self-test uses {@link MediaSettings#DEFAULTS}.
 */
public final class MediaConfig {
    public static MediaSettings read() {
        return new MediaSettings(
                ServerConfig.mediaFfmpegEnabled(),
                ServerConfig.mediaFfmpegPath(),
                ServerConfig.mediaConvertTimeoutSeconds());
    }

    private MediaConfig() {
    }
}
