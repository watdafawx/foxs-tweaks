package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * A GUI scale for the hologram editor that is separate from the game's. Someone who likes scale 3 for vanilla menus can
 * still edit at 2, where the three panels have room. The preference is {@link HologramEditorPrefs#guiScale()}:
 * 0 follows the game, 1-4 is a fixed scale (the window still caps it to what fits, like the vanilla option).
 *
 * <p>Minecraft keeps one scale on the window, so this swaps it while an editor screen is open: the opening event runs
 * before the new screen lays itself out (so it starts at the right size) and puts the vanilla scale back on the way
 * out. A tick check covers a window resize or an options change, which reset the scale underneath the editor.
 */
public final class HologramEditorScale {
    public static final int MAX = 4;

    private HologramEditorScale() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(HologramEditorScale::onScreenOpening);
        NeoForge.EVENT_BUS.addListener(HologramEditorScale::onClientTick);
    }

    public static boolean isEditor(Screen s) {
        return s instanceof HologramScreen || s instanceof HologramChatScreen || s instanceof HologramPickerScreen
                || s instanceof HologramAssetScreen || s instanceof HologramCreatorScreen
                || s instanceof HologramAudioScreen || s instanceof HologramAudioSettingsScreen;
    }

    /** What the window's scale should be for the given screen. */
    private static double target(Minecraft mc, Screen s) {
        Window w = mc.getWindow();
        int pref = isEditor(s) ? HologramEditorPrefs.guiScale() : 0;
        int option = pref > 0 ? pref : mc.options.guiScale().get();
        return w.calculateScale(option, mc.isEnforceUnicode());
    }

    private static void onScreenOpening(ScreenEvent.Opening event) {
        Minecraft mc = Minecraft.getInstance();
        Window w = mc.getWindow();
        double want = target(mc, event.getNewScreen());
        if (w.getGuiScale() != want)
            w.setGuiScale(want);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null || !isEditor(s))
            return;
        Window w = mc.getWindow();
        double want = target(mc, s);
        if (w.getGuiScale() != want) {
            w.setGuiScale(want);
            s.resize(mc, w.getGuiScaledWidth(), w.getGuiScaledHeight());
        }
    }

    /** Cycles Game, 1, 2, 3, 4 and applies it to the open editor at once. */
    public static void cycle() {
        int next = HologramEditorPrefs.guiScale() + 1;
        HologramEditorPrefs.setGuiScale(next > MAX ? 0 : next);
        apply();
    }

    public static void apply() {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s == null)
            return;
        Window w = mc.getWindow();
        double want = target(mc, s);
        if (w.getGuiScale() != want) {
            w.setGuiScale(want);
            s.resize(mc, w.getGuiScaledWidth(), w.getGuiScaledHeight());
        }
    }

    public static String label() {
        int p = HologramEditorPrefs.guiScale();
        return p == 0 ? "Scale: Game" : "Scale: " + p;
    }
}
