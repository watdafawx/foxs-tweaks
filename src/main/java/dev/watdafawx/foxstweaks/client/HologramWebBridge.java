package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.client.web.HologramWebSession;
import dev.watdafawx.foxstweaks.client.web.WebEditor;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;

/**
 * Adapts {@link HologramScreen}'s package-private web hooks to {@link WebEditor}, the only view of the editor the
 * web companion ({@code client/web}) gets. Lives in this package because the hooks are package-private on
 * purpose, like the Creator bridge.
 */
public final class HologramWebBridge implements WebEditor {
    private final HologramScreen editor;

    private HologramWebBridge(HologramScreen editor) {
        this.editor = editor;
    }

    /** The "Web" buttons in the Studio and the Creator: start (or reuse) the session and open the browser. */
    public static void open(HologramScreen editor) {
        HologramWebSession.openInBrowser(new HologramWebBridge(editor));
    }

    /** Starts (or reuses) the session without opening a browser; returns its URL, or null if it failed. */
    @Nullable
    public static String start(HologramScreen editor) {
        return HologramWebSession.start(new HologramWebBridge(editor));
    }

    public static boolean activeFor(HologramScreen editor) {
        HologramWebSession session = HologramWebSession.current();
        return session != null && session.editor() instanceof HologramWebBridge b && b.editor == editor;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof HologramWebBridge b && b.editor == this.editor;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this.editor);
    }

    @Override
    public HologramSpec spec() {
        return this.editor.current();
    }

    @Override
    public int maxRenderDistance() {
        return this.editor.webMaxRenderDistance();
    }

    @Override
    public Selection selection() {
        return this.editor.webSelection();
    }

    @Override
    public boolean canUndo() {
        return this.editor.creatorCanUndo();
    }

    @Override
    public boolean canRedo() {
        return this.editor.creatorCanRedo();
    }

    @Override
    public boolean alive() {
        return this.editor.webAlive();
    }

    @Override
    public void apply(HologramSpec spec, boolean newUndoStep) {
        this.editor.webApply(spec, newUndoStep);
    }

    @Override
    public void select(String kind, int layer, @Nullable String group) {
        this.editor.webSelect(kind, layer, group);
    }

    @Override
    public void solo(int layer, @Nullable String group) {
        this.editor.webSolo(layer, group);
    }

    @Override
    public void undo() {
        this.editor.undo();
    }

    @Override
    public void redo() {
        this.editor.redo();
    }

    @Override
    public void save() {
        this.editor.saveAndClose();
    }

    @Override
    public void cancel() {
        this.editor.cancelAndClose();
    }

    @Override
    public void notifyPlayer(String message) {
        var player = Minecraft.getInstance().player;
        if (player != null)
            player.displayClientMessage(Component.literal(message), true);
    }
}
