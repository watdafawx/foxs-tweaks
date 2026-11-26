package dev.watdafawx.foxstweaks.media;

/** Process-tree housekeeping shared by the locator and the converter. */
final class Processes {
    /**
     * Kills the process and every child it spawned.
     *
     * <p>{@code destroyForcibly()} alone would leave ffmpeg's own threads and any helper it started
     * behind, and on Windows those keep the output file open. Descendants are collected first, while
     * the tree is still there to walk.
     */
    static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private Processes() {
    }
}
