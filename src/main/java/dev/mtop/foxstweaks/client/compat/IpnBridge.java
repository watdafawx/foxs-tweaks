package dev.mtop.foxstweaks.client.compat;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.anti_ad.mc.ipnext.gui.inject.ContainerScreenEventHandler;
import org.anti_ad.mc.ipnext.gui.inject.EditorWidget;
import org.anti_ad.mc.ipnext.gui.inject.base.InsertableWidget;
import org.anti_ad.mc.ipnext.gui.inject.base.SettingsWidget;
import org.anti_ad.mc.ipnext.integration.HintClassData;
import org.anti_ad.mc.ipnext.integration.HintsManagerNG;
import org.anti_ad.mc.ipnext.inventory.ContainerTypes;

import java.util.List;

/**
 * The only class that touches Inventory Profiles Next types. Callers must check that IPN is loaded first.
 *
 * <p>Everything goes through IPN's own objects: the editor and settings screens are opened by the same
 * widget methods IPN's buttons call, and ignore toggles are saved exactly the way IPN's editor screen
 * saves them on close ({@code GUIDEEditorScreen#closeScreen}), so they land in IPN's own
 * {@code config/inventoryprofilesnext/integrationHints/player-defined.json} and its editor shows them.
 */
public final class IpnBridge {
    /** The id IPN's editor uses for hints that have no file of their own yet. */
    private static final String PLAYER_DEFINED = "player-defined";

    public enum Flag { SCREEN_IGNORE, CONTAINER_IGNORE, PLAYER_SIDE_ONLY }

    private IpnBridge() {
    }

    /** Opens IPN's per-screen editor, with the live widgets so its buttons can be moved there too. */
    public static void openEditor(AbstractContainerScreen<?> screen) {
        EditorWidget live = find(EditorWidget.class);

        if (live != null)
            live.showEditorScreen();
        else
            new EditorWidget(screen, HintsManagerNG.INSTANCE.getHints(screen.getClass())).showEditorScreen();
    }

    /** Opens IPN's general config. IPN closes the container first, then opens the config on the next frame. */
    public static void openSettings(AbstractContainerScreen<?> screen) {
        SettingsWidget live = find(SettingsWidget.class);

        if (live != null)
            live.onClick();
        else
            new SettingsWidget(screen, HintsManagerNG.INSTANCE.getHints(screen.getClass())).onClick();
    }

    public static boolean get(AbstractContainerScreen<?> screen, Flag flag) {
        HintClassData hints = hintsFor(screen, flag);
        return flag == Flag.PLAYER_SIDE_ONLY ? hints.getPlayerSideOnly() : hints.getIgnore();
    }

    /** Flips one flag, saves it like IPN's editor does, and returns the new value. */
    public static boolean toggle(AbstractContainerScreen<?> screen, Flag flag) {
        HintClassData target = hintsFor(screen, flag);
        boolean value;

        if (flag == Flag.PLAYER_SIDE_ONLY) {
            value = !target.getPlayerSideOnly();
            target.setPlayerSideOnly(value);
        } else {
            value = !target.getIgnore();
            target.setIgnore(value);
        }

        target.markAsDirty();

        HintClassData screenHints = HintsManagerNG.INSTANCE.getHints(screen.getClass());
        HintClassData containerHints = HintsManagerNG.INSTANCE.getHints(screen.getMenu().getClass());
        String screenId = screenHints.readId();
        String containerId = containerHints.readId();

        if (screenId == null)
            screenId = containerId != null ? containerId : PLAYER_DEFINED;

        if (containerId == null)
            containerId = screenId;

        screenHints.changeId(screenId);
        containerHints.changeId(containerId);
        HintsManagerNG.INSTANCE.saveDirty(screenHints, containerHints);
        ContainerTypes.INSTANCE.reset();
        return value;
    }

    private static HintClassData hintsFor(AbstractContainerScreen<?> screen, Flag flag) {
        Class<?> cls = flag == Flag.CONTAINER_IGNORE ? screen.getMenu().getClass() : screen.getClass();
        return HintsManagerNG.INSTANCE.getHints(cls);
    }

    private static <T> T find(Class<T> type) {
        List<InsertableWidget> widgets = ContainerScreenEventHandler.INSTANCE.getCurrentWidgets();

        if (widgets == null)
            return null;

        for (InsertableWidget widget : widgets) {
            if (type.isInstance(widget))
                return type.cast(widget);
        }

        return null;
    }
}
