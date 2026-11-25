package dev.watdafawx.foxstweaks.client.perf;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The full FPS hotspot report: every type (or every single thing) with what it costs, how much FPS it would
 * give back, where it is, and buttons to show it in the world, look at it, teleport next to it or copy its
 * coordinates. Opens from {@code /foxstweaks fps report}, the chat summary or {@code key.foxstweaks.open_fps_report}.
 *
 * <p>Two lists, same data: <b>By type</b> adds every instance of a block entity / entity type together (the
 * "212 drawers" view), <b>Individual</b> ranks single things. The game keeps running behind it so positions and
 * the in-world highlight stay live.
 */
public final class FpsReportScreen extends Screen {
    private static final int ROW_H = 20;
    private static final int PAD = 8;

    private record Row(String label, String typeId, boolean group, double us, int count, double calls,
                       List<FpsProfiler.Hotspot> members) {
    }

    private int tab; // 0 = by type, 1 = individual
    private int selected;
    private int memberSel;
    private int scroll;

    private int left, top, bottom, listW, detailX, detailW, listTop, headerH;
    private int memberListTop = Integer.MAX_VALUE;

    private Button showBtn, lookBtn, tpBtn, copyBtn, hideBtn, stopBtn, sparkBtn, typeTab, itemTab;

    public FpsReportScreen() {
        super(Component.literal("FPS Hotspot Report"));
    }

    @Override
    protected void init() {
        int w = Math.min(width - 16, 680);
        left = (width - w) / 2;
        top = 8;
        bottom = height - 8;
        listW = (int) (w * 0.52);
        detailX = left + listW + PAD;
        detailW = w - listW - PAD;
        headerH = 52;
        int tabY = top + headerH;
        listTop = tabY + 22;

        typeTab = addRenderableWidget(Button.builder(Component.literal("By type"), b -> setTab(0))
                .bounds(left, tabY, 76, 18).tooltip(Tooltip.create(Component.literal(
                        "Every instance of a type added together. Finds hundreds of cheap things."))).build());
        itemTab = addRenderableWidget(Button.builder(Component.literal("Individual"), b -> setTab(1))
                .bounds(left + 80, tabY, 76, 18).tooltip(Tooltip.create(Component.literal(
                        "Single block entities / entities, most expensive first."))).build());
        sparkBtn = addRenderableWidget(Button.builder(Component.literal("Copy spark link"), b -> {
                    String url = FpsProfiler.getSparkUrl();
                    if (url != null)
                        minecraft.keyboardHandler.setClipboard(url);
                }).bounds(left + w - 112, top + 2, 112, 16).build());

        int bw = (detailW - 4) / 2;
        int y3 = bottom - 20;
        int y2 = y3 - 24;
        int y1 = y2 - 24;
        showBtn = addRenderableWidget(Button.builder(Component.literal("Show in world"), b -> showInWorld())
                .bounds(detailX, y1, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Closes this screen and highlights it (every one of the type, or just this one)."))).build());
        lookBtn = addRenderableWidget(Button.builder(Component.literal("Look at it"), b -> {
                    FpsProfiler.Hotspot t = target();
                    if (t != null) {
                        FpsActions.look(t);
                        onClose();
                    }
                }).bounds(detailX + bw + 4, y1, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Turns your view toward the picked one. No permissions needed."))).build());
        tpBtn = addRenderableWidget(Button.builder(Component.literal("Teleport next to it"), b -> {
                    FpsProfiler.Hotspot t = target();
                    if (t != null && FpsActions.teleport(t))
                        onClose();
                }).bounds(detailX, y2, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Runs /tp to a free spot a few blocks from it, facing it. Needs op / cheats."))).build());
        copyBtn = addRenderableWidget(Button.builder(Component.literal("Copy coordinates"), b -> {
                    FpsProfiler.Hotspot t = target();
                    if (t != null)
                        FpsActions.copy(t);
                }).bounds(detailX + bw + 4, y2, bw, 20).build());
        hideBtn = addRenderableWidget(Button.builder(Component.literal("Ignore this type"), b -> {
                    Row r = row();
                    if (r != null) {
                        FpsProfiler.ignoreType(r.typeId());
                        selected = 0;
                        memberSel = 0;
                    }
                }).bounds(detailX, y3, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Remove this type from every list and highlight until the next /foxstweaks fps clear."))).build());
        stopBtn = addRenderableWidget(Button.builder(Component.literal("Stop highlighting"), b -> FpsProfiler.clearFocus())
                .bounds(detailX + bw + 4, y3, bw, 20).tooltip(Tooltip.create(Component.literal(
                        "Back to the normal top hotspots in the world."))).build());
        updateButtons();
    }

    /** Dev check only: switch tab / pick a row without a mouse. */
    public void devSetTab(int t) {
        setTab(t);
    }

    public void devSelect(int index) {
        selected = index;
        memberSel = 0;
    }

    private void setTab(int t) {
        tab = t;
        selected = 0;
        memberSel = 0;
        scroll = 0;
    }

    // ---- Data ----

    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        if (tab == 0) {
            for (FpsProfiler.Group g : FpsProfiler.getGroups())
                out.add(new Row(g.label(), g.typeId(), true, g.microsPerFrame(), g.count(), g.callsPerFrame(), g.members()));
        } else {
            for (FpsProfiler.Hotspot h : FpsProfiler.getInstances())
                out.add(new Row(h.label(), h.typeId(), false, h.microsPerFrame(), 1, h.callsPerFrame(), List.of(h)));
        }
        return out;
    }

    private Row row() {
        List<Row> rows = rows();
        return selected >= 0 && selected < rows.size() ? rows.get(selected) : null;
    }

    private FpsProfiler.Hotspot target() {
        Row r = row();
        if (r == null || r.members().isEmpty())
            return null;
        return r.members().get(Mth.clamp(memberSel, 0, r.members().size() - 1));
    }

    private void showInWorld() {
        Row r = row();
        if (r == null)
            return;
        if (r.group())
            FpsProfiler.focusType(r.typeId());
        else
            FpsProfiler.focusItem(r.members().get(0));
        onClose();
    }

    private void updateButtons() {
        Row r = row();
        boolean has = r != null;
        showBtn.active = has;
        lookBtn.active = has;
        copyBtn.active = has;
        hideBtn.active = has;
        tpBtn.active = has && FpsActions.canTeleport();
        stopBtn.active = FpsProfiler.getFocusItem() != null || FpsProfiler.getFocusType() != null;
        sparkBtn.active = FpsProfiler.getSparkUrl() != null;
        typeTab.active = tab != 0;
        itemTab.active = tab != 1;
    }

    @Override
    public void tick() {
        updateButtons();
    }

    // ---- Drawing ----

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        // A light tint, not vanilla's blur: the highlights in the world stay visible behind the report.
        gg.fill(0, 0, width, height, 0x88000000);
        gg.fill(left - 4, top - 4, left + listW + 4, bottom + 4, 0xEE0B0D12);
        // Header strip over the right half too, so the title and help text are readable on any world.
        gg.fill(left + listW + 4, top - 4, detailX + detailW + 4, top + headerH, 0xEE0B0D12);
        gg.fill(detailX - 4, top + headerH, detailX + detailW + 4, bottom + 4, 0xEE0B0D12);
    }

    @Override
    public void render(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.render(gg, mouseX, mouseY, partialTick);

        drawHeader(gg);

        List<Row> rows = rows();
        int visible = Math.max(1, (bottom - listTop) / ROW_H);
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - visible));
        selected = Mth.clamp(selected, 0, Math.max(0, rows.size() - 1));

        // active tab underline
        gg.fill(left + (tab == 0 ? 0 : 80), top + headerH + 19, left + (tab == 0 ? 76 : 156), top + headerH + 21, 0xFF55AAFF);

        if (rows.isEmpty()) {
            gg.drawString(font, FpsProfiler.sampling ? "Scanning..." : "Nothing heavy found.", left + 4, listTop + 4, 0xFF9AA4B2, false);
        }

        double maxUs = rows.isEmpty() ? 1.0 : Math.max(1.0, rows.get(0).us());
        for (int i = 0; i < visible && scroll + i < rows.size(); i++) {
            int idx = scroll + i;
            Row r = rows.get(idx);
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= left && mouseX < left + listW && mouseY >= y && mouseY < y + ROW_H;
            gg.fill(left, y, left + listW, y + ROW_H - 1, idx == selected ? 0xFF2A3350 : hover ? 0xFF1E2433 : 0xFF141821);

            int barW = (int) ((listW - 2) * Math.min(1.0, r.us() / maxUs));
            gg.fill(left + 1, y + ROW_H - 4, left + 1 + barW, y + ROW_H - 2, FpsProfiler.heatArgb(r.us()));

            String right = rightText(r.us());
            int rw = font.width(right);
            gg.drawString(font, String.valueOf(idx + 1), left + 4, y + 4, 0xFF6F7885, false);
            String name = r.group() && r.count() > 1 ? r.label() + "  x" + r.count() : r.label();
            if (!r.group() && !r.members().isEmpty())
                name += "  " + FpsActions.coords(r.members().get(0));
            gg.drawString(font, font.plainSubstrByWidth(name, listW - rw - 36), left + 22, y + 4, 0xFFE8EDF5, false);
            gg.drawString(font, right, left + listW - rw - 4, y + 4, FpsProfiler.heatArgb(r.us()), false);
        }

        drawDetail(gg, mouseX, mouseY);
    }

    private static String rightText(double us) {
        return FpsProfiler.frameMicros() > 0.0
                ? String.format(Locale.ROOT, "%.1f%%  %.0f µs", FpsProfiler.share(us) * 100.0, us)
                : String.format(Locale.ROOT, "%.0f µs", us);
    }

    private void drawHeader(GuiGraphics gg) {
        int x = left;
        int y = top + 2;
        gg.drawString(font, FpsProfiler.sampling ? "FPS Hotspot Report  (still scanning...)" : "FPS Hotspot Report",
                x, y, 0xFFFFFFFF, false);
        y += 12;
        double fm = FpsProfiler.frameMicros();
        String stats = fm > 0
                ? String.format(Locale.ROOT, "%d frames in %.1fs  ·  avg frame %.1f ms (%.0f FPS)",
                        FpsProfiler.getFrameCount(), FpsProfiler.getScanSeconds(), fm / 1000.0, 1_000_000.0 / fm)
                : String.format(Locale.ROOT, "%d frames in %.1fs", FpsProfiler.getFrameCount(), FpsProfiler.getScanSeconds());
        double you = FpsProfiler.playerMicros();
        if (you >= 1.0) {
            stats += String.format(Locale.ROOT, "  ·  you: %.2f ms%s", you / 1000.0,
                    fm > 0 ? String.format(Locale.ROOT, " (%.1f%%)", FpsProfiler.share(you) * 100.0) : "");
        }
        gg.drawString(font, stats, x, y, 0xFFC8D0DC, false);
        y += 12;
        gg.drawWordWrap(font, Component.literal(
                        "Each number is the time spent drawing that thing, per frame (µs = millionths of a second; "
                                + "self time only). % is its share of the average frame. Shader packs draw things "
                                + "again for shadows, and that cost counts."),
                x, y, listW + detailW - 100, 0xFF9AA4B2);
    }

    private void drawDetail(GuiGraphics gg, int mouseX, int mouseY) {
        int x = detailX;
        int y = top + headerH + 4;
        memberListTop = Integer.MAX_VALUE;

        Row r = row();
        if (r == null) {
            gg.drawString(font, "Pick something on the left.", x, y, 0xFF9AA4B2, false);
            return;
        }

        int heat = FpsProfiler.heatArgb(r.us());
        gg.drawString(font, font.plainSubstrByWidth(r.label(), detailW), x, y, heat, false);
        y += 11;
        gg.drawString(font, font.plainSubstrByWidth(r.typeId() + "  ·  " + FpsProfiler.modName(r.typeId()), detailW),
                x, y, 0xFF8892A0, false);
        y += 14;

        gg.drawString(font, String.format(Locale.ROOT, "%.2f ms per frame", r.us() / 1000.0), x, y, heat, false);
        y += 11;
        if (FpsProfiler.frameMicros() > 0.0) {
            gg.drawString(font, String.format(Locale.ROOT, "%.1f%% of the average frame", FpsProfiler.share(r.us()) * 100.0),
                    x, y, 0xFFC8D0DC, false);
            y += 11;
            double fm = FpsProfiler.frameMicros();
            double gain = FpsProfiler.fpsGain(r.us());
            if (gain > 0.0) {
                gg.drawString(font, String.format(Locale.ROOT, "If it cost nothing: %.0f → %.0f FPS  (+%.0f)",
                        1_000_000.0 / fm, 1_000_000.0 / (fm - r.us()), gain), x, y, 0xFF7CE08A, false);
                y += 11;
            }
        }
        y += 3;

        if (r.group()) {
            gg.drawString(font, String.format(Locale.ROOT, "%d loaded  ·  %.0f µs each  ·  %.0f draws/frame",
                    r.count(), r.us() / Math.max(1, r.count()), r.calls()), x, y, 0xFFC8D0DC, false);
            y += 15;

            gg.drawString(font, "Hottest ones (click to pick):", x, y, 0xFF9AA4B2, false);
            y += 12;
            memberListTop = y;
            int shown = Math.min(7, r.members().size());
            for (int i = 0; i < shown; i++) {
                FpsProfiler.Hotspot h = r.members().get(i);
                boolean sel = i == Mth.clamp(memberSel, 0, r.members().size() - 1);
                boolean hover = mouseX >= x && mouseX < x + detailW && mouseY >= y && mouseY < y + 11;
                if (sel || hover)
                    gg.fill(x - 2, y - 1, x + detailW, y + 10, sel ? 0xFF2A3350 : 0xFF1E2433);
                double dist = FpsActions.distance(h);
                String line = String.format(Locale.ROOT, "%.0f µs  %s  %s %s", h.microsPerFrame(), FpsActions.coords(h),
                        dist >= 0 ? String.format(Locale.ROOT, "%.0fm", dist) : "", FpsActions.arrow(h));
                gg.drawString(font, font.plainSubstrByWidth(line, detailW - 2), x, y + 1, 0xFFE8EDF5, false);
                y += 11;
            }
            if (r.count() > shown) {
                gg.drawString(font, "+" + (r.count() - shown) + " more", x, y + 1, 0xFF6F7885, false);
            }
        } else {
            FpsProfiler.Hotspot h = r.members().get(0);
            gg.drawString(font, String.format(Locale.ROOT, "%.0f draws per frame", r.calls()), x, y, 0xFFC8D0DC, false);
            y += 11;
            double dist = FpsActions.distance(h);
            gg.drawString(font, "At " + FpsActions.coords(h) + (dist >= 0
                            ? String.format(Locale.ROOT, "  ·  %.0f blocks %s", dist, FpsActions.arrow(h)) : ""),
                    x, y, 0xFFC8D0DC, false);
        }
    }

    // ---- Input ----

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        // list rows
        if (mouseX >= left && mouseX < left + listW && mouseY >= listTop && mouseY < bottom) {
            int idx = scroll + (int) ((mouseY - listTop) / ROW_H);
            if (idx >= 0 && idx < rows().size()) {
                selected = idx;
                memberSel = 0;
                return true;
            }
        }

        // hottest-ones list in the detail panel
        Row r = row();
        if (r != null && r.group() && mouseX >= detailX && mouseX < detailX + detailW && mouseY >= memberListTop) {
            int i = (int) ((mouseY - memberListTop) / 11);
            if (i >= 0 && i < Math.min(7, r.members().size())) {
                memberSel = i;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= left && mouseX < left + listW) {
            scroll -= (int) Math.signum(scrollY) * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
