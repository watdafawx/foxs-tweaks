package dev.watdafawx.foxstweaks.client.web;

import dev.watdafawx.foxstweaks.hologram.HologramSpec;

import javax.annotation.Nullable;

/**
 * What the web companion may do to an open hologram editor, and nothing more. Implemented by
 * {@code client/HologramWebBridge} on top of {@code HologramScreen}'s package-private web hooks, so this package
 * never touches editor internals. Every method runs on the client main thread.
 */
public interface WebEditor {
    /** The editor's selection plus its editor-local solo filter. {@code kind} is root, group or layer. */
    record Selection(String kind, int layer, @Nullable String group, int soloLayer, @Nullable String soloGroup) {
    }

    /** The edited hologram right now: what the live preview shows. */
    HologramSpec spec();

    int maxRenderDistance();

    Selection selection();

    boolean canUndo();

    boolean canRedo();

    /** False once the editor saved, cancelled, deleted or closed. */
    boolean alive();

    /**
     * Replaces the edited spec (identity fields are the editor's own and are not taken from {@code spec}) and
     * refreshes the live preview. {@code newUndoStep} pushes one undo snapshot first.
     */
    void apply(HologramSpec spec, boolean newUndoStep);

    void select(String kind, int layer, @Nullable String group);

    /** Solo one layer, one group, or (both unset) nobody. */
    void solo(int layer, @Nullable String group);

    void undo();

    void redo();

    void save();

    void cancel();

    /** Tells the player something without opening a screen (action bar). */
    void notifyPlayer(String message);
}
