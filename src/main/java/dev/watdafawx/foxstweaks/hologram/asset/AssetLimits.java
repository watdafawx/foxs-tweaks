package dev.watdafawx.foxstweaks.hologram.asset;

import dev.watdafawx.foxstweaks.ServerConfig;

/** Hard ceilings remain even when server settings are raised. */
public record AssetLimits(int fileBytes, long worldBytes, int dimension, int fps, int seconds, int perPlayer) {
    public static AssetLimits current() {
        return new AssetLimits(ServerConfig.ASSET_MAX_FILE_BYTES.get(), ServerConfig.ASSET_MAX_WORLD_BYTES.get(),
                ServerConfig.ASSET_MAX_DIMENSION.get(), ServerConfig.ASSET_MAX_FPS.get(),
                ServerConfig.ASSET_MAX_SECONDS.get(), ServerConfig.ASSET_MAX_PER_PLAYER.get());
    }
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_FRAMES = 900;
    public static final long PIXEL_BUDGET = 32L * 1024 * 1024;
}
