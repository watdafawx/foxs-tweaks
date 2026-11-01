package dev.watdafawx.foxstweaks.client.compat;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import net.irisshaders.iris.gui.GuiUtil;
import net.irisshaders.iris.gui.NavigationController;
import net.irisshaders.iris.gui.element.ShaderPackOptionList;
import net.irisshaders.iris.gui.element.widget.AbstractElementWidget;
import net.irisshaders.iris.gui.element.widget.OptionMenuConstructor;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuContainer;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElement;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuElementScreen;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuOptionElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;

/**
 * Search box for Iris' shader pack settings. Typing replaces the menu with every matching option from all
 * sub-menus, grouped under the menu each one lives in. The rows are Iris' own option buttons, so changing
 * one works exactly like in its menu (pending until Apply/Done).
 *
 * <p>Matching: each word of the query must appear in the option's name, its id ({@code COLORED_LIGHTING}),
 * its description or its menu name - as a substring, or failing that as letters in order ("clrlight").
 */
public final class IrisSettingsSearch {
    /** Current query; one shader screen is open at a time. Reset when a new screen opens. */
    public static String query = "";
    /** Full name of the best match that starts with the query, for the grey autocomplete hint; null if none. */
    public static String completion;
    private static final int MAX_RESULTS = 80;

    private IrisSettingsSearch() {
    }

    public static boolean active() {
        return !query.isBlank();
    }

    /** Fills the list with search results instead of the current menu. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void fill(ShaderPackOptionList list, OptionMenuContainer container, ShaderPackScreen screen, NavigationController navigation) {
        String[] words = normalize(query).split(" ");
        Map<String, OptionMenuElementScreen> screens = new LinkedHashMap<>();
        screens.put("", container.mainScreen);
        screens.putAll(container.subScreens);

        record Hit(OptionMenuOptionElement element, String menu, int score, String name) {
        }
        List<Hit> hits = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, OptionMenuElementScreen> entry : screens.entrySet()) {
            String menu = entry.getKey().isEmpty() ? "Main" : text("screen." + entry.getKey(), entry.getKey());
            for (OptionMenuElement element : entry.getValue().elements) {
                if (!(element instanceof OptionMenuOptionElement option) || !seen.add(option.optionId)) {
                    continue;
                }
                String id = option.optionId;
                String name = text("option." + id, id);
                String comment = text("option." + id + ".comment", "");
                int score = score(words, normalize(name), normalize(id), normalize(comment), normalize(menu));
                if (score > 0) {
                    hits.add(new Hit(option, menu, score, ChatFormatting.stripFormatting(name).trim()));
                }
            }
        }

        String typed = query.stripLeading().toLowerCase(Locale.ROOT);
        completion = hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed().thenComparingInt(h -> h.name().length())).map(Hit::name)
                .filter(n -> n.length() > typed.length() && n.toLowerCase(Locale.ROOT).startsWith(typed))
                .findFirst().orElse(null);

        Map<String, List<Hit>> byMenu = new LinkedHashMap<>();
        hits.stream().sorted(Comparator.comparingInt(Hit::score).reversed()).limit(MAX_RESULTS)
                .forEach(h -> byMenu.computeIfAbsent(h.menu(), k -> new ArrayList<>()).add(h));

        IrisSearchList rows = (IrisSearchList) list;
        rows.foxstweaks$addRow(new Section(navigation, Component.literal(hits.isEmpty()
                ? "No settings match \"" + query.trim() + "\""
                : Math.min(hits.size(), MAX_RESULTS) + " settings match \"" + query.trim() + "\"").withStyle(ChatFormatting.BOLD), false));
        for (Map.Entry<String, List<Hit>> group : byMenu.entrySet()) {
            rows.foxstweaks$addRow(new Section(navigation, Component.literal(group.getKey()).withStyle(ChatFormatting.GRAY), true));
            List<AbstractElementWidget<?>> widgets = new ArrayList<>();
            for (Hit hit : group.getValue()) {
                AbstractElementWidget widget = OptionMenuConstructor.createWidget(hit.element());
                widget.init(screen, navigation);
                widgets.add(widget);
            }
            list.addWidgets(2, widgets);
        }
    }

    /** 0 = no match. Every word must match one of the fields. */
    static int score(String[] words, String name, String id, String comment, String menu) {
        String compactId = id.replace(" ", "");
        String compactName = name.replace(" ", "");
        int total = 0;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            int best = 0;
            if (name.startsWith(word) || id.startsWith(word)) {
                best = 100;
            } else if (name.contains(word) || id.contains(word) || compactId.contains(word) || compactName.contains(word)) {
                best = 80;
            } else if (menu.contains(word)) {
                best = 40;
            } else if (comment.contains(word)) {
                best = 30;
            } else if (inOrder(word, compactName) || inOrder(word, compactId)) {
                best = 15;
            }
            if (best == 0) {
                return 0;
            }
            total += best;
        }
        return total;
    }

    private static boolean inOrder(String word, String target) {
        if (word.length() < 3) {
            return false;
        }
        int i = 0;
        for (int c = 0; c < target.length() && i < word.length(); c++) {
            if (target.charAt(c) == word.charAt(i)) {
                i++;
            }
        }
        return i == word.length();
    }

    static String normalize(String s) {
        return ChatFormatting.stripFormatting(s).toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[^a-z0-9 ]", " ")
                .replaceAll(" +", " ").trim();
    }

    private static String text(String key, String fallback) {
        return GuiUtil.translateOrDefault(Component.literal(fallback), key).getString();
    }

    /** A plain heading row: the result count, or the menu the following options live in. */
    static final class Section extends ShaderPackOptionList.BaseEntry {
        private final Component text;
        private final boolean small;

        Section(NavigationController navigation, Component text, boolean small) {
            super(navigation);
            this.text = text;
            this.small = small;
        }

        @Override
        public void render(GuiGraphics g, int index, int y, int x, int width, int height, int mouseX, int mouseY,
                           boolean hovered, float delta) {
            var font = Minecraft.getInstance().font;
            if (small) {
                g.drawString(font, text, x + 2, y + height - 11, 0xFFFFFF);
                g.fill(x - 3, y + height - 2, x + width, y + height - 1, 0x66BEBEBE);
            } else {
                g.drawCenteredString(font, text, x + width / 2, y + 5, 0xFFFFFF);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return ImmutableList.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return ImmutableList.of();
        }
    }
}
