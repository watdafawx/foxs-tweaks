package dev.watdafawx.foxstweaks.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import dev.watdafawx.foxstweaks.attribute.AttributeDial;
import dev.watdafawx.foxstweaks.attribute.AttributeDialNetwork;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.ai.attributes.Attribute;

/**
 * The attribute dial: pick one of your attributes, drag it down from what gear gives you to its vanilla default (0 when it has none).
 *
 * <p>Left: search box, "hide unchanged" filter and the list. Right: the selected attribute with a slider and presets.
 * The server owns the numbers ({@link AttributeDial}); this screen only asks and shows the table it gets back, with the
 * last change applied locally until that answer arrives so the slider never snaps back.
 */
public class AttributeDialScreen extends Screen {
    private static final int AMBER = 0xFFFFB629;
    private static final int ROW_H = 20;
    private static final double[] PRESETS = {0.0, 0.25, 0.5, 0.75, 1.0};

    /** Kept between openings, like Apothic's own panel keeps its "hide unchanged". */
    private static boolean hideUnchanged;

    private record Row(ResourceLocation id, Component name, String hay, AttributeDial.Entry entry) {
    }

    private final @Nullable Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private final List<Row> shown = new ArrayList<>();
    private final List<Button> chips = new ArrayList<>();
    private EditBox search;
    private Button filterButton;
    private Button resetButton;

    private @Nullable ResourceLocation selected;
    private int scroll;
    private int lastVersion = -1;

    private boolean dragging;
    private double dragRatio;
    private @Nullable ResourceLocation pendingId;
    private double pendingRatio;

    private int left, top, panelW, panelH, listW, edX, edW, listTop, listBottom;

    public AttributeDialScreen(@Nullable Screen parent) {
        super(Component.translatable("foxstweaks.dial.title"));
        this.parent = parent;
    }

    // ---- layout ----

    @Override
    protected void init() {
        panelW = Math.min(width - 16, 600);
        panelH = Math.min(height - 16, 300);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        listW = panelW * 45 / 100;
        edX = left + listW + 8;
        edW = panelW - listW - 8;
        listTop = top + 64;
        listBottom = top + panelH - 24;

        String old = search == null ? "" : search.getValue();
        search = new EditBox(font, left + 6, top + 22, listW - 12, 16, Component.translatable("foxstweaks.dial.search"));
        search.setHint(Component.translatable("foxstweaks.dial.search").withStyle(ChatFormatting.DARK_GRAY));
        search.setMaxLength(48);
        search.setValue(old);
        search.setResponder(text -> applyFilter());
        addRenderableWidget(search);
        setInitialFocus(search);

        filterButton = addRenderableWidget(Button.builder(filterLabel(), b -> {
            hideUnchanged = !hideUnchanged;
            b.setMessage(filterLabel());
            applyFilter();
        }).bounds(left + 6, top + 42, listW - 12, 16).build());

        resetButton = addRenderableWidget(Button.builder(Component.translatable("foxstweaks.dial.reset_all"), b -> {
            AttributeDialClient.send(new AttributeDialNetwork.ResetAll());
            pendingId = null;
        }).bounds(left + 6, top + panelH - 20, listW - 12, 16).build());

        chips.clear();
        int gap = 3;
        int w = (edW - 20 - gap * (PRESETS.length - 1)) / PRESETS.length;
        for (int i = 0; i < PRESETS.length; i++) {
            double ratio = PRESETS[i];
            Component label = ratio == 0.0 ? Component.translatable("foxstweaks.dial.off")
                    : ratio == 1.0 ? Component.translatable("foxstweaks.dial.full")
                    : Component.literal(Math.round(ratio * 100) + "%");
            chips.add(addRenderableWidget(Button.builder(label, b -> commitSelected(ratio))
                    .bounds(edX + 10 + i * (w + gap), top + 112, w, 16).build()));
        }

        lastVersion = -1;
        AttributeDialClient.send(new AttributeDialNetwork.Open());
        rebuild();
    }

    private Component filterLabel() {
        return Component.translatable("foxstweaks.dial.hide_unchanged", hideUnchanged ? "On" : "Off");
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- data ----

    private void rebuild() {
        rows.clear();

        for (AttributeDial.Entry e : AttributeDialClient.entries()) {
            Attribute attribute = BuiltInRegistries.ATTRIBUTE.get(e.id());
            Component name = attribute == null ? Component.literal(e.id().getPath()) : Component.translatable(attribute.getDescriptionId());
            rows.add(new Row(e.id(), name, (name.getString() + " " + e.id()).toLowerCase(Locale.ROOT), e));
        }

        rows.sort(Comparator.comparing(r -> r.name().getString().toLowerCase(Locale.ROOT)));
        applyFilter();
        labelChips();
    }

    private void applyFilter() {
        shown.clear();
        String[] words = search == null ? new String[0] : search.getValue().toLowerCase(Locale.ROOT).trim().split("\\s+");

        for (Row row : rows) {
            if (hideUnchanged && row.entry().ratio() >= 1.0 && Math.abs(row.entry().gear() - row.entry().base()) < 1e-9)
                continue;

            boolean ok = true;
            for (String word : words)
                if (!word.isEmpty() && !matches(word, row.hay())) {
                    ok = false;
                    break;
                }

            if (ok)
                shown.add(row);
        }

        scroll = Mth.clamp(scroll, 0, maxScroll());

        if (selected == null && !shown.isEmpty())
            selected = shown.get(0).id();
    }

    /** Substring first; otherwise the letters in order ("blkrng" finds "block interaction range"). */
    static boolean matches(String word, String hay) {
        if (hay.contains(word))
            return true;

        int at = 0;
        for (int i = 0; i < word.length(); i++) {
            at = hay.indexOf(word.charAt(i), at);
            if (at < 0)
                return false;
            at++;
        }
        return true;
    }

    private @Nullable Row current() {
        if (selected == null)
            return null;

        for (Row row : rows)
            if (row.id().equals(selected))
                return row;

        return null;
    }

    private double ratioOf(Row row) {
        if (dragging && row.id().equals(selected))
            return dragRatio;
        if (row.id().equals(pendingId))
            return pendingRatio;
        return row.entry().ratio();
    }

    private double valueOf(Row row) {
        double ratio = ratioOf(row);
        return ratio == row.entry().ratio() ? row.entry().value() : row.entry().at(ratio);
    }

    private void commitSelected(double ratio) {
        Row row = current();

        if (row == null)
            return;

        ratio = Math.round(Mth.clamp(ratio, 0.0, 1.0) * 100.0) / 100.0;
        pendingId = row.id();
        pendingRatio = ratio;
        AttributeDialClient.send(new AttributeDialNetwork.Set(row.id(), ratio));
    }

    @Override
    public void tick() {
        if (AttributeDialClient.version() != lastVersion) {
            lastVersion = AttributeDialClient.version();
            if (!dragging)
                pendingId = null;
            rebuild();
        }
    }

    // ---- drawing ----

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9)
            return Long.toString((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    private int maxScroll() {
        int visible = (listBottom - listTop) / ROW_H;
        return Math.max(0, shown.size() - visible);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xA0000000);
        panel(g, left, top, listW, panelH);
        panel(g, edX - 4, top, edW + 4, panelH);
    }

    private static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xF0202026);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);

        g.drawString(font, title, left + 8, top + 8, 0xFFFFFFFF, true);
        renderList(g, mouseX, mouseY);
        renderEditor(g);

        int hovered = rowAt(mouseX, mouseY);
        if (hovered >= 0 && !dragging) {
            Row row = shown.get(hovered);
            boolean rendered = false;
            if (minecraft != null && minecraft.player != null
                    && net.neoforged.fml.ModList.get().isLoaded("apothic_attributes")) {
                rendered = dev.watdafawx.foxstweaks.client.compat.ApothicDialTooltip.renderTooltip(
                        g, font, minecraft.player, row.entry(), row.name(),
                        valueOf(row), ratioOf(row), mouseX, mouseY, width);
            }
            if (!rendered)
                g.renderComponentTooltip(font, tooltipFor(row), mouseX, mouseY);
        }
    }

    private String formatVal(ResourceLocation id, double v) {
        String plain = fmt(v);
        if (net.neoforged.fml.ModList.get().isLoaded("apothic_attributes"))
            return dev.watdafawx.foxstweaks.client.compat.ApothicDialTooltip.formatValue(id, v, plain);
        return plain;
    }

    /** What Apothic's own panel shows on hover: current value, base, limits and every modifier, plus our dial's share. */
    List<Component> tooltipFor(Row row) {
        List<Component> lines = new ArrayList<>();
        AttributeDial.Entry e = row.entry();
        lines.add(row.name().copy().withStyle(ChatFormatting.GOLD, ChatFormatting.UNDERLINE));

        var holder = BuiltInRegistries.ATTRIBUTE.getHolder(e.id()).orElse(null);
        if (holder != null) {
            String descKey = holder.value().getDescriptionId() + ".desc";
            if (net.minecraft.client.resources.language.I18n.exists(descKey))
                lines.add(Component.translatable(descKey).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
        }

        lines.add(Component.translatable("foxstweaks.dial.tip.current", formatVal(e.id(), valueOf(row))).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable("foxstweaks.dial.tip.base", formatVal(e.id(), e.base())).withStyle(ChatFormatting.GRAY));

        if (holder != null && holder.value() instanceof net.minecraft.world.entity.ai.attributes.RangedAttribute ranged)
            lines.add(Component.translatable("foxstweaks.dial.tip.limits", formatVal(e.id(), ranged.getMinValue()), formatVal(e.id(), ranged.getMaxValue())).withStyle(ChatFormatting.GRAY));

        var inst = holder == null || minecraft == null || minecraft.player == null ? null : minecraft.player.getAttribute(holder);
        if (inst != null) {
            ResourceLocation dial = AttributeDial.modifierId(e.id());
            List<net.minecraft.world.entity.ai.attributes.AttributeModifier> mods = new ArrayList<>(inst.getModifiers());
            mods.removeIf(m -> m.id().equals(dial) || m.amount() == 0.0);
            mods.sort(Comparator.comparingInt((net.minecraft.world.entity.ai.attributes.AttributeModifier m) -> m.operation().ordinal())
                    .thenComparing(m -> m.id().toString()));

            if (!mods.isEmpty())
                lines.add(Component.translatable("foxstweaks.dial.tip.modifiers").withStyle(ChatFormatting.GOLD));

            int shownMods = 0;
            for (var m : mods) {
                if (shownMods++ == 12) {
                    lines.add(Component.translatable("foxstweaks.dial.tip.more", mods.size() - 12).withStyle(ChatFormatting.DARK_GRAY));
                    break;
                }

                Component comp = holder.value().toComponent(m, net.minecraft.world.item.TooltipFlag.NORMAL);
                lines.add(comp);
            }
        }

        if (ratioOf(row) < 1.0)
            lines.add(Component.translatable("foxstweaks.dial.tip.dial", Math.round(ratioOf(row) * 100), formatVal(e.id(), e.floor()), formatVal(e.id(), e.gear())).withStyle(ChatFormatting.GOLD));

        return lines;
    }

    private void renderList(GuiGraphics g, int mouseX, int mouseY) {
        int rowW = listW - 18;
        g.enableScissor(left + 6, listTop, left + 6 + rowW + 8, listBottom);

        int visible = (listBottom - listTop) / ROW_H;
        for (int i = 0; i < visible && scroll + i < shown.size(); i++) {
            Row row = shown.get(scroll + i);
            int y = listTop + i * ROW_H;
            boolean sel = row.id().equals(selected);
            boolean hot = mouseX >= left + 6 && mouseX < left + 6 + rowW && mouseY >= y && mouseY < y + ROW_H - 2;

            g.fill(left + 6, y, left + 6 + rowW, y + ROW_H - 2, sel ? 0xFF3A3A44 : hot ? 0xFF2E2E37 : 0xFF26262C);
            if (sel)
                g.renderOutline(left + 6, y, rowW, ROW_H - 2, AMBER);

            double ratio = ratioOf(row);
            boolean edited = ratio < 1.0;
            Component value = Component.literal(formatVal(row.id(), valueOf(row)));
            int valueColor = edited ? AMBER : Math.abs(row.entry().gear() - row.entry().base()) < 1e-9 ? 0xFFAAAAAA : 0xFF55FF55;
            int valueW = font.width(value);
            int right = left + 6 + rowW - 4;

            g.drawString(font, value, right - valueW, y + 5, valueColor, false);

            int reserved = valueW + 10;
            if (edited) {
                Component gear = Component.literal(formatVal(row.id(), row.entry().gear())).withStyle(ChatFormatting.GRAY, ChatFormatting.STRIKETHROUGH);
                g.drawString(font, gear, right - valueW - 4 - font.width(gear), y + 5, 0xFF888888, false);
                reserved += font.width(gear) + 4;
            }

            String name = font.plainSubstrByWidth(row.name().getString(), rowW - 8 - reserved);
            g.drawString(font, name, left + 10, y + 5, 0xFFE8E8EE, false);
        }

        g.disableScissor();

        if (shown.isEmpty()) {
            Component none = Component.translatable(AttributeDialClient.entries().isEmpty() ? "foxstweaks.dial.waiting" : "foxstweaks.dial.none");
            g.drawWordWrap(font, none, left + 10, listTop + 6, listW - 20, 0xFF9A9AA8);
        }

        if (maxScroll() > 0) {
            int trackH = listBottom - listTop;
            int knobH = Math.max(12, trackH * visible / shown.size());
            int knobY = listTop + (trackH - knobH) * scroll / maxScroll();
            g.fill(left + listW - 9, listTop, left + listW - 5, listBottom, 0xFF101014);
            g.fill(left + listW - 9, knobY, left + listW - 5, knobY + knobH, 0xFF8B8B8B);
        }
    }

    /** First chip is "Off" for an attribute vanilla starts at 0, "Base" when vanilla gives it something. */
    private void labelChips() {
        Row row = current();

        if (row != null && !chips.isEmpty())
            chips.get(0).setMessage(Component.translatable(row.entry().floor() == 0.0 ? "foxstweaks.dial.off" : "foxstweaks.dial.base"));
    }

    private int sliderX() {
        return edX + 10;
    }

    private int sliderW() {
        return edW - 20;
    }

    private int sliderY() {
        return top + 78;
    }

    private void renderEditor(GuiGraphics g) {
        Row row = current();
        boolean has = row != null;

        for (Button chip : chips)
            chip.active = has;

        if (!has)
            return;

        labelChips();

        double ratio = ratioOf(row);
        double gear = row.entry().gear();
        double value = valueOf(row);
        int x = edX + 10;

        g.drawString(font, row.name(), x, top + 10, 0xFFFFFFFF, true);
        String subtitle = subtitleFor(row);
        g.drawString(font, font.plainSubstrByWidth(subtitle, edW - 20), x, top + 22, 0xFF9A9AA8, false);

        Component big = Component.literal(formatVal(row.id(), value));
        g.pose().pushPose();
        g.pose().scale(2.0F, 2.0F, 1.0F);
        g.drawString(font, big, x / 2, (top + 36) / 2, ratio < 1.0 ? AMBER : 0xFFE8E8EE, true);
        g.pose().popPose();
        g.drawString(font, Component.translatable("foxstweaks.dial.of_gear", formatVal(row.id(), gear)), x + font.width(big) * 2 + 8, top + 48, 0xFF9A9AA8, false);

        // Fill bar: how much of the gear value is in use.
        int by = top + 62;
        g.fill(x, by, x + sliderW(), by + 6, 0xFF101014);
        g.fill(x, by, x + (int) (sliderW() * ratio), by + 6, AMBER);
        g.renderOutline(x - 1, by - 1, sliderW() + 2, 8, 0xFF3A3A44);

        // Slider.
        int sy = sliderY();
        g.fill(x, sy, x + sliderW(), sy + 4, 0xFF101014);
        g.renderOutline(x - 1, sy - 1, sliderW() + 2, 6, 0xFF3A3A44);
        int kx = x + (int) (sliderW() * ratio);
        g.fill(kx - 3, sy - 5, kx + 3, sy + 9, dragging ? 0xFFFFFFFF : AMBER);
        g.renderOutline(kx - 3, sy - 5, 6, 14, 0xFF000000);

        String low = row.entry().floor() == 0.0 ? Component.translatable("foxstweaks.dial.off").getString() : formatVal(row.id(), row.entry().floor());
        g.drawString(font, low, x, sy + 14, 0xFF9A9AA8, false);
        String pct = Math.round(ratio * 100) + "%";
        g.drawString(font, pct, x + sliderW() / 2 - font.width(pct) / 2, sy + 14, 0xFFE8E8EE, false);
        String full = formatVal(row.id(), gear);
        g.drawString(font, full, x + sliderW() - font.width(full), sy + 14, 0xFF9A9AA8, false);

        // Active preset gets an outline.
        for (int i = 0; i < chips.size(); i++) {
            if (Math.abs(ratio - PRESETS[i]) < 0.005) {
                Button chip = chips.get(i);
                g.renderOutline(chip.getX() - 1, chip.getY() - 1, chip.getWidth() + 2, chip.getHeight() + 2, AMBER);
            }
        }

        int iy = top + 136;
        Component vanilla = Component.translatable("foxstweaks.dial.vanilla", formatVal(row.id(), row.entry().floor()));
        int used = font.split(vanilla, edW - 20).size() * 9;
        g.drawWordWrap(font, vanilla, x, iy, edW - 20, 0xFF9A9AA8);
        g.drawWordWrap(font, Component.translatable("foxstweaks.dial.stored", String.format(Locale.ROOT, "%.2f", ratio)), x, iy + used + 6, edW - 20, 0xFF9A9AA8);
    }

    private String subtitleFor(Row row) {
        if (minecraft != null && minecraft.options.advancedItemTooltips)
            return row.id().toString();
        Attribute attr = BuiltInRegistries.ATTRIBUTE.get(row.id());
        if (attr != null) {
            String descKey = attr.getDescriptionId() + ".desc";
            if (net.minecraft.client.resources.language.I18n.exists(descKey))
                return Component.translatable(descKey).getString();
        }
        String ns = row.id().getNamespace();
        return net.neoforged.fml.ModList.get().getModContainerById(ns)
                .map(c -> c.getModInfo().getDisplayName())
                .orElse(ns);
    }

    // ---- input ----

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < left + 6 || mouseX >= left + listW - 10 || mouseY < listTop || mouseY >= listBottom)
            return -1;

        int index = scroll + (int) ((mouseY - listTop) / ROW_H);
        return index < shown.size() ? index : -1;
    }

    private boolean onSlider(double mouseX, double mouseY) {
        return current() != null && mouseX >= sliderX() - 6 && mouseX <= sliderX() + sliderW() + 6
                && mouseY >= sliderY() - 8 && mouseY <= sliderY() + 12;
    }

    private double ratioAtMouse(double mouseX) {
        return Mth.clamp((mouseX - sliderX()) / sliderW(), 0.0, 1.0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        if (button != 0)
            return false;

        int index = rowAt(mouseX, mouseY);
        if (index >= 0) {
            selected = shown.get(index).id();
            pendingId = null;
            labelChips();
            return true;
        }

        if (onSlider(mouseX, mouseY)) {
            dragging = true;
            dragRatio = ratioAtMouse(mouseX);
            return true;
        }

        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            dragRatio = ratioAtMouse(mouseX);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            commitSelected(ratioAtMouse(mouseX));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX < edX - 4) {
            scroll = Mth.clamp(scroll - (int) Math.signum(scrollY), 0, maxScroll());
            return true;
        }

        Row row = current();
        if (row != null && scrollY != 0) {
            commitSelected(ratioOf(row) + Math.signum(scrollY) * 0.05);
            return true;
        }

        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode != 256 && !(getFocused() instanceof EditBox) && (FoxsTweaksKeys.OPEN_ATTRIBUTE_DIAL.matches(keyCode, scanCode)
                || minecraft.options.keyInventory.matches(keyCode, scanCode))) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---- hooks for client/dev/AttributeDialCheck: positions of real controls, so a check clicks through the real handlers ----

    public List<ResourceLocation> devShown() {
        return shown.stream().map(Row::id).toList();
    }

    public @Nullable ResourceLocation devSelected() {
        return selected;
    }

    public void devSearch(String text) {
        search.setValue(text);
    }

    public Button devFilterButton() {
        return filterButton;
    }

    public Button devResetButton() {
        return resetButton;
    }

    public List<Button> devChips() {
        return chips;
    }

    public boolean devDragging() {
        return dragging;
    }

    public double[] devSliderPoint(double ratio) {
        return new double[] {sliderX() + sliderW() * ratio, sliderY() + 2};
    }

    /** Centre of a row if it is on screen, else null. */
    public double @Nullable [] devRowPoint(ResourceLocation id) {
        int index = shown.stream().map(Row::id).toList().indexOf(id);
        int visible = (listBottom - listTop) / ROW_H;

        if (index < 0 || index < scroll || index >= scroll + visible)
            return null;

        return new double[] {left + 20, listTop + (index - scroll) * ROW_H + ROW_H / 2.0 - 1};
    }

    public List<String> devTooltip(ResourceLocation id) {
        for (Row row : rows)
            if (row.id().equals(id))
                return tooltipFor(row).stream().map(Component::getString).toList();
        return List.of();
    }

    public void devScrollTo(ResourceLocation id) {
        int index = shown.stream().map(Row::id).toList().indexOf(id);
        int visible = (listBottom - listTop) / ROW_H;

        if (index >= 0)
            scroll = Mth.clamp(index - visible / 2, 0, maxScroll());
    }
}
