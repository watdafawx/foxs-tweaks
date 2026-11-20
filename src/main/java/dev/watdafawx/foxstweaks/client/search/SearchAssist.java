package dev.watdafawx.foxstweaks.client.search;

import java.util.List;
import java.util.Locale;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * Decides which text boxes are search boxes, for {@code EditBoxSearchAssistMixin}. No mod is named anywhere:
 * a box is a search box when its label, hint or class says so, which covers EMI, JEI, AE2, Refined Storage,
 * the creative inventory and most others. Names that fail can be added in {@code searchAssistExtraBoxes}.
 */
public final class SearchAssist {
    private SearchAssist() {
    }

    public static boolean enabled() {
        return Config.enabled(Config.SEARCH_ASSIST);
    }

    public static boolean isSearchBox(EditBox box, Component hint) {
        if (!enabled() || !box.isActive()) {
            return false;
        }
        Screen screen = Minecraft.getInstance().screen;
        String boxClass = box.getClass().getName().toLowerCase(Locale.ROOT);
        String screenClass = screen == null ? "" : classChain(screen.getClass());
        if (matchesAny(Config.SEARCH_ASSIST_SKIP_BOXES.get(), boxClass, screenClass)) {
            return false;
        }
        if (matchesAny(Config.SEARCH_ASSIST_EXTRA_BOXES.get(), boxClass, screenClass)) {
            return true;
        }
        return looksLikeSearch(label(box.getMessage())) || looksLikeSearch(label(hint)) || looksLikeSearch(boxClass);
    }

    /** The screen's class and every parent up to Screen, so a terminal subclass still matches its base's name. */
    private static String classChain(Class<?> c) {
        StringBuilder sb = new StringBuilder();
        for (; c != null && c != Screen.class && c != Object.class; c = c.getSuperclass()) {
            sb.append(c.getName().toLowerCase(Locale.ROOT)).append(' ');
        }
        return sb.toString();
    }
    private static boolean matchesAny(List<? extends String> needles, String boxClass, String screenClass) {
        for (String needle : needles) {
            String n = needle.toLowerCase(Locale.ROOT);
            if (!n.isEmpty() && (boxClass.contains(n) || screenClass.contains(n))) {
                return true;
            }
        }
        return false;
    }

    /** Visible text plus the translation key, since a key like {@code gui.foo.search} names the purpose too. */
    private static String label(Component c) {
        if (c == null) {
            return "";
        }
        String key = c.getContents() instanceof TranslatableContents t ? t.getKey() : "";
        return (c.getString() + " " + key).toLowerCase(Locale.ROOT);
    }

    private static boolean looksLikeSearch(String s) {
        return s.contains("search") || s.contains("filter") || s.contains("find");
    }
}
