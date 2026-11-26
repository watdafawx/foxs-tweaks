package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.client.audio.AudioHub;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Param;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The ♪ panel shared by the Studio and the Creator: edits the audio modulations of one effect or one root / group /
 * layer, with a live level meter. It owns no hologram state: {@code get}/{@code set} read and write the editor's
 * working copy, so undo, the live preview and saving stay the editor's.
 *
 * <p>A side panel, not a window over the middle: the hologram has to stay visible to see what the music does to it.
 * Like every hologram screen it never draws the vanilla blur. Immediate mode: every control registers a {@link Hit}
 * per frame, which is also what the dev check presses ({@link #devClick}).
 */
public final class HologramAudioScreen extends Screen {

    private static final int PANEL_BG = 0xE80D1117;
    private static final int PANEL_BORDER = 0x40FFFFFF;
    private static final int SURFACE = 0xFF161C24;
    private static final int SURFACE_HOT = 0xFF212B38;
    private static final int SURFACE_ACTIVE = 0x404CC9F0;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int WARN = 0xFFF5C062;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int DANGER_TEXT = 0xFFFF7A85;

    private record Hit(int x, int y, int w, int h, String label, Runnable click, @Nullable Wheel wheel) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    private interface Wheel {
        void scroll(int dir, boolean coarse);
    }

    private final Screen returnTo;
    private final HologramScreen editor;
    private final String what;
    private final Supplier<List<HologramAudioMod>> get;
    private final Consumer<List<HologramAudioMod>> set;
    private final List<Param> params;
    private final Param defaultParam;
    private final float defaultBase;
    /** Non-null for root / group / layer panels: the one-click presets. */
    @Nullable
    private final Consumer<HologramAudioMod.Preset> preset;

    private final List<Hit> hits = new ArrayList<>();
    private final float[] meter = new float[5];
    private int selected;
    private int listScroll;
    private long lastUndoMs;

    public HologramAudioScreen(Screen returnTo, HologramScreen editor, String what,
                               Supplier<List<HologramAudioMod>> get, Consumer<List<HologramAudioMod>> set,
                               List<Param> params, Param defaultParam, float defaultBase,
                               @Nullable Consumer<HologramAudioMod.Preset> preset) {
        super(Component.literal("Audio"));
        this.returnTo = returnTo;
        this.editor = editor;
        this.what = what;
        this.get = get;
        this.set = set;
        this.params = params;
        this.defaultParam = defaultParam;
        this.defaultBase = defaultBase;
        this.preset = preset;
        List<HologramAudioMod> cur = get.get();
        // Open on the modulation of the value whose ♪ was pressed, if there is one.
        for (int i = 0; i < cur.size(); i++) {
            if (cur.get(i).param() == defaultParam) {
                this.selected = i;
                break;
            }
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No blur and almost no tint: the point of this panel is watching the hologram react.
    }

    @Override
    public void tick() {
        this.editor.creatorTick();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null)
            this.minecraft.setScreen(this.returnTo);
    }

    // ---------------------------------------------------------------- edits

    private List<HologramAudioMod> mods() {
        return this.get.get();
    }

    @Nullable
    private HologramAudioMod current() {
        List<HologramAudioMod> m = mods();
        if (m.isEmpty())
            return null;
        this.selected = Mth.clamp(this.selected, 0, m.size() - 1);
        return m.get(this.selected);
    }

    /** One undo step per click; a burst of wheel steps shares one. */
    private void undoStep(boolean burst) {
        long now = System.currentTimeMillis();
        if (!burst || now - this.lastUndoMs > 600)
            this.editor.creatorPushUndo();
        this.lastUndoMs = now;
    }

    private void replaceCurrent(java.util.function.UnaryOperator<HologramAudioMod> fn, boolean burst) {
        HologramAudioMod cur = current();
        if (cur == null)
            return;
        undoStep(burst);
        List<HologramAudioMod> next = new ArrayList<>(mods());
        next.set(this.selected, fn.apply(cur));
        this.set.accept(next);
    }

    private void add() {
        List<HologramAudioMod> cur = mods();
        if (cur.size() >= HologramAudioMod.HARD_MAX_PER_VALUE * 2)
            return;
        undoStep(false);
        List<HologramAudioMod> next = new ArrayList<>(cur);
        Param p = this.defaultParam;
        next.add(HologramAudioMod.of(p, p == Param.SCALE ? HologramAudioMod.Signal.BASS : HologramAudioMod.Signal.LEVEL,
                HologramAudioMod.defaultAmount(p, this.defaultBase)));
        this.set.accept(next);
        this.selected = next.size() - 1;
    }

    private void remove(int index) {
        List<HologramAudioMod> cur = mods();
        if (index < 0 || index >= cur.size())
            return;
        undoStep(false);
        List<HologramAudioMod> next = new ArrayList<>(cur);
        next.remove(index);
        this.set.accept(next);
        if (this.selected >= next.size())
            this.selected = Math.max(0, next.size() - 1);
    }

    private Param nextParam(Param p) {
        int i = this.params.indexOf(p);
        return this.params.get((i + 1) % this.params.size());
    }

    // ---------------------------------------------------------------- layout + draw

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        this.hits.clear();
        int w = Math.min(236, Math.max(180, this.width / 3));
        int x0 = this.width - w - 6;
        int y0 = 6;
        int x1 = x0 + w;
        int y1 = this.height - 6;
        g.fill(x0, y0, x1, y1, PANEL_BG);
        g.renderOutline(x0, y0, w, y1 - y0, PANEL_BORDER);
        int x = x0 + 6;
        int iw = w - 12;
        int y = y0 + 6;

        g.drawString(this.font, "♪ Audio", x, y, WARN, false);
        g.drawString(this.font, ellipsize(this.what, iw - 50), x + 46, y, TEXT, false);
        y += 12;
        for (var line : this.font.split(Component.literal(
                "Levels only, computed on this PC. Nothing is recorded or sent."), iw)) {
            g.drawString(this.font, line, x, y, MUTED, false);
            y += 9;
        }
        y += 3;

        // ---- meter
        HologramAudioMod cur = current();
        HologramAudioMod.Source src = cur == null ? HologramAudioMod.Source.AUTO : cur.source();
        AudioHub.meter(src, this.meter);
        String[] names = { "Level", "Bass", "Mid", "High", "Beat" };
        int bw = (iw - 4 * 3) / 5;
        int bh = 22;
        for (int i = 0; i < 5; i++) {
            int bx = x + i * (bw + 3);
            boolean on = cur != null && cur.signal().ordinal() == i;
            g.fill(bx, y, bx + bw, y + bh, SURFACE);
            int fh = Math.round(Mth.clamp(this.meter[i], 0.0F, 1.0F) * bh);
            g.fill(bx, y + bh - fh, bx + bw, y + bh, i == 4 ? WARN : ACCENT);
            if (on)
                g.renderOutline(bx, y, bw, bh, TEXT);
            g.drawCenteredString(this.font, names[i], bx + bw / 2, y + bh + 2, on ? TEXT : MUTED);
        }
        y += bh + 13;
        g.drawString(this.font, ellipsize("Hearing: " + src.label() + (src == HologramAudioMod.Source.SYSTEM
                || src == HologramAudioMod.Source.AUTO ? " · system " + dev.watdafawx.foxstweaks.client.audio.SystemAudio.status()
                : ""), iw), x, y, MUTED, false);
        y += 12;

        // ---- list
        List<HologramAudioMod> list = mods();
        int rows = Math.min(4, Math.max(1, list.size()));
        this.listScroll = Mth.clamp(this.listScroll, 0, Math.max(0, list.size() - rows));
        if (list.isEmpty()) {
            g.drawString(this.font, "Nothing reacts to music yet.", x, y + 3, MUTED, false);
            y += 15;
        }
        for (int r = 0; r < rows && r + this.listScroll < list.size(); r++) {
            int i = r + this.listScroll;
            HologramAudioMod m = list.get(i);
            boolean sel = i == this.selected;
            int rw = iw - 14;
            boolean hot = inside(mx, my, x, y, rw, 13);
            g.fill(x, y, x + rw, y + 13, sel ? SURFACE_ACTIVE : hot ? SURFACE_HOT : SURFACE);
            g.drawString(this.font, ellipsize(m.param().label() + ": " + m.describe(), rw - 6), x + 3, y + 3,
                    sel ? TEXT : MUTED, false);
            final int idx = i;
            this.hits.add(new Hit(x, y, rw, 13, "row " + i + " " + m.param().label(), () -> this.selected = idx, null));
            button(g, mx, my, x + rw + 1, y, 13, 13, "x", "remove " + i, DANGER_TEXT, () -> remove(idx));
            y += 14;
        }
        if (list.size() > rows) {
            g.drawString(this.font, (this.listScroll + 1) + "-" + Math.min(list.size(), this.listScroll + rows) + " of "
                    + list.size() + " (wheel)", x, y, MUTED, false);
            this.hits.add(new Hit(x, y - rows * 14, iw, rows * 14 + 9, "list", () -> {
            }, (d, c) -> this.listScroll -= d));
            y += 10;
        }
        button(g, mx, my, x, y, iw, 14, "+ Add audio", "add", ACCENT, this::add);
        y += 18;

        if (this.preset != null) {
            int pw = (iw - 3) / 2;
            HologramAudioMod.Preset[] ps = HologramAudioMod.Preset.values();
            for (int i = 0; i < ps.length; i++) {
                HologramAudioMod.Preset p = ps[i];
                int px = x + (i % 2) * (pw + 3);
                int py = y + (i / 2) * 15;
                button(g, mx, my, px, py, pw, 14, ellipsize(p.label(), pw - 4), "preset " + p.label(), TEXT, () -> {
                    undoStep(false);
                    this.preset.accept(p);
                    List<HologramAudioMod> now = mods();
                    for (int k = 0; k < now.size(); k++)
                        if (now.get(k).param() == p.mods().get(0).param())
                            this.selected = k;
                });
            }
            y += 2 * 15 + 4;
        }

        // ---- selected modulation
        cur = current();
        int footer = y1 - 22;
        if (cur != null) {
            final HologramAudioMod c = cur;
            y = cycleRow(g, mx, my, x, y, iw, "Value", c.param().label(),
                    () -> replaceCurrent(m -> m.withParam(nextParam(m.param())), false));
            y = cycleRow(g, mx, my, x, y, iw, "Listen", c.source().label(),
                    () -> replaceCurrent(m -> m.withSource(m.source().next()), false));
            y = cycleRow(g, mx, my, x, y, iw, "Signal", c.signal().label(),
                    () -> replaceCurrent(m -> m.withSignal(m.signal().next()), false));
            float step = c.param().amountStep();
            y = numRow(g, mx, my, x, y, iw, "Amount", fmt(c.amount(), step), footer,
                    (d, co) -> replaceCurrent(m -> m.withAmount(m.amount() + d * step * (co ? 10 : 1)), true));
            y = numRow(g, mx, my, x, y, iw, "Sensitivity", fmt(c.sensitivity(), 0.05F) + "x", footer,
                    (d, co) -> replaceCurrent(m -> m.withSensitivity(m.sensitivity() + d * (co ? 0.5F : 0.05F)), true));
            y = numRow(g, mx, my, x, y, iw, "Attack", fmt(c.attack(), 0.01F) + "s", footer,
                    (d, co) -> replaceCurrent(m -> m.withAttack(m.attack() + d * (co ? 0.1F : 0.01F)), true));
            y = numRow(g, mx, my, x, y, iw, "Release", fmt(c.release(), 0.01F) + "s", footer,
                    (d, co) -> replaceCurrent(m -> m.withRelease(m.release() + d * (co ? 0.1F : 0.01F)), true));
            y = numRow(g, mx, my, x, y, iw, "Beat step", c.threshold() <= 0.0F ? "smooth" : fmt(c.threshold(), 0.05F),
                    footer, (d, co) -> replaceCurrent(m -> m.withThreshold(m.threshold() + d * 0.05F), true));
            if (y + 15 <= footer) {
                y = cycleRow(g, mx, my, x, y, iw, "Clamp", c.hasRange()
                        ? fmt(c.min(), step) + " .. " + fmt(c.max(), step) : "off", () -> replaceCurrent(m -> m.hasRange()
                        ? m.withRange(Float.NaN, Float.NaN)
                        : m.withRange(-m.param().maxAmount(), m.param().maxAmount()), false));
            }
            if (c.hasRange()) {
                y = numRow(g, mx, my, x, y, iw, "Min", fmt(c.min(), step), footer,
                        (d, co) -> replaceCurrent(m -> m.withRange(m.min() + d * step * (co ? 10 : 1), m.max()), true));
                y = numRow(g, mx, my, x, y, iw, "Max", fmt(c.max(), step), footer,
                        (d, co) -> replaceCurrent(m -> m.withRange(m.min(), m.max() + d * step * (co ? 10 : 1)), true));
            }
        }

        // ---- footer
        int half = (iw - 3) / 2;
        button(g, mx, my, x, y1 - 20, half, 14, "Audio settings", "settings", TEXT,
                () -> this.minecraft.setScreen(new HologramAudioSettingsScreen(this)));
        button(g, mx, my, x + half + 3, y1 - 20, iw - half - 3, 14, "Done", "done", ACCENT, this::onClose);

        super.render(g, mx, my, partial);
    }

    private int cycleRow(GuiGraphics g, int mx, int my, int x, int y, int iw, String label, String value, Runnable click) {
        g.drawString(this.font, label, x, y + 3, MUTED, false);
        int bx = x + 62;
        button(g, mx, my, bx, y, iw - 62, 13, ellipsize(value, iw - 68), label.toLowerCase(Locale.ROOT), TEXT, click);
        return y + 15;
    }

    private int numRow(GuiGraphics g, int mx, int my, int x, int y, int iw, String label, String value, int footer,
                       Wheel wheel) {
        if (y + 13 > footer)
            return y;
        g.drawString(this.font, label, x, y + 3, MUTED, false);
        int bx = x + 62;
        int vw = iw - 62 - 2 * 15;
        String key = label.toLowerCase(Locale.ROOT);
        button(g, mx, my, bx, y, 13, 13, "-", key + " -", TEXT, () -> wheel.scroll(-1, hasShiftDown()));
        boolean hot = inside(mx, my, bx + 15, y, vw, 13);
        g.fill(bx + 15, y, bx + 15 + vw, y + 13, hot ? SURFACE_HOT : SURFACE);
        g.drawCenteredString(this.font, value, bx + 15 + vw / 2, y + 3, TEXT);
        this.hits.add(new Hit(bx + 15, y, vw, 13, key, () -> {
        }, wheel));
        button(g, mx, my, bx + 17 + vw, y, 13, 13, "+", key + " +", TEXT, () -> wheel.scroll(1, hasShiftDown()));
        return y + 15;
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String text, String key, int color,
                        Runnable click) {
        boolean hot = inside(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hot ? SURFACE_HOT : SURFACE);
        g.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, color);
        this.hits.add(new Hit(x, y, w, h, key, click, null));
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String ellipsize(String s, int w) {
        if (this.font.width(s) <= w)
            return s;
        return this.font.plainSubstrByWidth(s, Math.max(0, w - this.font.width("..."))) + "...";
    }

    private static String fmt(float v, float step) {
        if (Float.isNaN(v))
            return "-";
        return step >= 1.0F ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%.2f", v);
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = this.hits.size() - 1; i >= 0; i--) {
                Hit h = this.hits.get(i);
                if (h.contains(mx, my)) {
                    h.click().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.wheel() != null && h.contains(mx, my)) {
                h.wheel().scroll(sy > 0 ? 1 : -1, hasShiftDown());
                return true;
            }
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    // ---------------------------------------------------------------- dev hooks

    /** Presses the control whose key starts with {@code prefix} as it was laid out last frame. */
    public boolean devClick(String prefix) {
        for (Hit h : this.hits) {
            if (h.label().startsWith(prefix)) {
                h.click().run();
                return true;
            }
        }
        return false;
    }

    /** Scrolls the control whose key starts with {@code prefix}. */
    public boolean devWheel(String prefix, int dir) {
        for (Hit h : this.hits) {
            if (h.wheel() != null && h.label().startsWith(prefix)) {
                h.wheel().scroll(dir, false);
                return true;
            }
        }
        return false;
    }

    public List<HologramAudioMod> devMods() {
        return mods();
    }

    public int devSelected() {
        return this.selected;
    }
}
