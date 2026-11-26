package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.DoubleConsumer;

/**
 * The simple hologram editor ("Creator"): two edge panels over the live hologram, built for people who want a
 * good-looking result without learning the full editor.
 *
 * <p>It holds no hologram state. Every change goes through the package-private bridge on {@link HologramScreen}
 * (the "Studio"), which keeps layers, undo history, live preview and the studio camera. "Studio" swaps to that
 * same instance, so nothing is lost switching back and forth.
 *
 * <p>The UI is immediate mode: {@link #render} lays out and draws every control and records a hit box for it,
 * and the mouse handlers walk those boxes. Layout therefore cannot drift from what is on screen, which mattered
 * because the right panel scrolls and its sections collapse.
 */
public final class HologramCreatorScreen extends Screen {
    // ---- Palette (shared look with the Studio) ----
    private static final int DIM = 0x1A000000;
    private static final int PANEL_BG = 0xEA0D1117;
    private static final int PANEL_BORDER = 0x40FFFFFF;
    private static final int SURFACE = 0xFF161C24;
    private static final int SURFACE_HOT = 0xFF212B38;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int ACCENT_BG = 0x404CC9F0;
    private static final int VIOLET = 0xFF8B5CFF;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int FAINT = 0xFF5A6575;
    private static final int DANGER = 0xFFFF7A85;
    private static final int GOLD = 0xFFFFC44D;
    private static final int[] AXIS_COLORS = { 0xFFFF5E6C, 0xFF5EE877, 0xFF5CA0FF };
    private static final int[] SWATCHES = {
            0x66E0FF, 0x4D88FF, 0x8B5CFF, 0xFF6EC7, 0xFF4D5A, 0xFF8C3B, 0xFFD24D, 0x5CE67A, 0xFFFFFF
    };

    private static final int ROW = 14;
    private static final int WHEEL = 64;
    private static final ResourceLocation WHEEL_TEX = ResourceLocation.fromNamespaceAndPath("foxstweaks", "creator_wheel");
    private static boolean wheelReady;

    private static final HologramEffect.Type[] FX_TILES = {
            HologramEffect.Type.SCANLINE, HologramEffect.Type.GLITCH, HologramEffect.Type.PULSE,
            HologramEffect.Type.RAINBOW, HologramEffect.Type.FLICKER, HologramEffect.Type.TREMOR,
            HologramEffect.Type.WOBBLE, HologramEffect.Type.JUMP
    };
    private static final HologramEffect.Type[] MOTION_TILES = {
            HologramEffect.Type.SPIN, HologramEffect.Type.BOB, HologramEffect.Type.SWAY,
            HologramEffect.Type.ORBIT, HologramEffect.Type.SLIDE, HologramEffect.Type.BREATHE,
            HologramEffect.Type.HEARTBEAT, HologramEffect.Type.FLIP, HologramEffect.Type.BOUNCE
    };
    private static final HologramEffect.Type[] ENTITY_TILES = {
            HologramEffect.Type.WALK, HologramEffect.Type.ATTACK, HologramEffect.Type.HURT,
            HologramEffect.Type.DEATH, HologramEffect.Type.SNEAK
    };
    private static final String[] REVEALS = { "Rise", "Pop", "Unfold", "Spin", "Drop", "Glitch" };

    private enum Section { SHAPE, COLOUR, FX, MOTION, STACK, REVEAL }

    /** Collapsed sections survive closing the editor, like a designer's panel layout would. */
    private static final Set<Section> collapsed = EnumSet.noneOf(Section.class);

    /** One built-in look: an FX stack that replaces the layer's FX and keeps its motion. */
    private record BuiltinLook(String name, int c1, int c2, boolean stripes, List<HologramEffect> fx) {
    }

    private static final List<BuiltinLook> BUILTIN_LOOKS = List.of(
            new BuiltinLook("Holo", 0xFF0E3B4A, 0xFF3EE6FF, false, List.of(
                    HologramEffect.of(HologramEffect.Type.TINT),
                    HologramEffect.of(HologramEffect.Type.SCANLINE))),
            new BuiltinLook("Ghost", 0xFF2A1450, 0xFFB86BFF, false, List.of(
                    HologramEffect.of(HologramEffect.Type.TINT).withColor(0xB86BFF).withStrength(0.6F),
                    HologramEffect.of(HologramEffect.Type.FLICKER).withStrength(0.2F).withSpeed(3.0F),
                    HologramEffect.of(HologramEffect.Type.WOBBLE).withStrength(0.3F))),
            new BuiltinLook("Glitch", 0xFFFF5C8A, 0xFF14161C, true, List.of(
                    HologramEffect.of(HologramEffect.Type.TINT).withColor(0xFF6EC7).withStrength(0.4F),
                    HologramEffect.of(HologramEffect.Type.GLITCH).withStrength(0.8F),
                    HologramEffect.of(HologramEffect.Type.TREMOR).withStrength(0.4F))),
            new BuiltinLook("Shrine", 0xFF4A3200, 0xFFFFC44D, false, List.of(
                    HologramEffect.of(HologramEffect.Type.GRADIENT).withColor(0xFFB000).withColor2(0xFFF3C4)
                            .withStrength(0.7F),
                    HologramEffect.of(HologramEffect.Type.PULSE).withStrength(0.4F).withSpeed(0.6F))),
            new BuiltinLook("Neon", 0xFF00331A, 0xFF5BE38A, false, List.of(
                    HologramEffect.of(HologramEffect.Type.TINT).withColor(0x5CE67A).withStrength(0.7F),
                    HologramEffect.of(HologramEffect.Type.PULSE).withStrength(0.6F),
                    HologramEffect.of(HologramEffect.Type.SCANLINE).withStrength(0.3F))),
            new BuiltinLook("Prism", 0xFFFF4D5A, 0xFF4D88FF, false, List.of(
                    HologramEffect.of(HologramEffect.Type.RAINBOW))),
            new BuiltinLook("Plain", 0xFF2A2F38, 0xFF6B7383, false, List.of())
    );

    /** A clickable region recorded while drawing. */
    private static final class Hit {
        final int x, y, w, h;
        @Nullable Runnable click;
        @Nullable Runnable rightClick;
        @Nullable DoubleConsumer wheel;
        /** Receives (mouseX, mouseY) on press and while dragging. */
        @Nullable DragFn drag;
        @Nullable String tooltip;
        /** Vertical clip the hit was drawn under: a row half scrolled out must not steal clicks below it. */
        int clipTop = Integer.MIN_VALUE, clipBottom = Integer.MAX_VALUE;

        Hit(int x, int y, int w, int h) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h && my >= clipTop && my < clipBottom;
        }
    }

    @FunctionalInterface
    private interface DragFn {
        void at(double mx, double my);
    }

    private final HologramScreen editor;
    private final List<Hit> hits = new ArrayList<>();
    private int hitClipTop = Integer.MIN_VALUE, hitClipBottom = Integer.MAX_VALUE;

    // layout
    private int leftX, leftW, rightX, rightW, panelTop, panelBottom;
    private int rightScroll, rightContentH, leftScroll, leftContentH;

    // interaction state
    @Nullable
    private DragFn activeDrag;
    private boolean orbiting, panning;
    /** Index into the full effect list of the stack row being dragged, or -1. */
    private int reorderFrom = -1;
    private int reorderTo = -1;
    /** Full-list index of the expanded stack row, or -1. */
    private int expanded = -1;
    /** Which gradient stop the wheel edits: 0 bottom, 1 top. */
    private int gradStop;
    private float hue, sat = 1.0F, val = 1.0F;
    /** The colour the HSV fields were last synced from, so an outside change (undo, look) re-syncs them. */
    private int hsvSource = -1;
    private EditBox hexBox;
    private boolean hexVisible;
    @Nullable
    private String toast;
    private long toastUntil;

    public HologramCreatorScreen(HologramScreen editor) {
        super(Component.literal("Hologram Creator"));
        this.editor = editor;
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void init() {
        // The Studio owns the camera, preview and network; it needs its own init to have a font and minecraft.
        this.editor.init(this.minecraft, this.width, this.height);

        this.leftW = Mth.clamp(this.width * 22 / 100, 128, 170);
        this.rightW = Mth.clamp(this.width * 30 / 100, 190, 236);
        this.leftX = 6;
        this.rightX = this.width - this.rightW - 6;
        this.panelTop = 24;
        this.panelBottom = this.height - 8;

        this.hexBox = new EditBox(this.font, 0, 0, 50, 10, Component.literal("hex"));
        this.hexBox.setBordered(false);
        this.hexBox.setMaxLength(7);
        this.hexBox.setTextColor(TEXT);
        this.hexBox.visible = false;
        addRenderableWidget(this.hexBox);
        ensureWheelTexture();
    }

    @Override
    public void tick() {
        super.tick();
        this.editor.creatorTick();
    }

    @Override
    public void onClose() {
        // Esc / close = the Studio's Cancel: drops local edits and ends the camera.
        this.editor.cancelAndClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Never vanilla's blur: the hologram behind the panels is the thing being edited.
        g.fill(0, 0, this.width, this.height, DIM);
    }

    // ---------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.hits.clear();
        HologramCamera.setAnchor(this.editor.creatorTargetCenter());
        HologramCamera.setMovementBlocked(this.hexBox.isFocused() || this.activeDrag != null);
        renderBackground(g, mx, my, pt);

        renderTopBar(g, mx, my);
        renderLeft(g, mx, my);
        renderRight(g, mx, my);

        String hint = "R-drag look · M-drag pan · Scroll zoom · WASD fly · F frame · Ctrl+Z undo";
        int midL = this.leftX + this.leftW + 6;
        int midR = this.rightX - 6;
        if (this.font.width(hint) <= midR - midL) {
            int hx = (midL + midR - this.font.width(hint)) / 2;
            g.fill(hx - 5, this.height - 15, hx + this.font.width(hint) + 5, this.height - 3, 0xA00D1117);
            g.drawString(this.font, hint, hx, this.height - 13, MUTED, false);
        }

        this.hexBox.visible = this.hexVisible;
        super.render(g, mx, my, pt);

        if (this.toast != null && System.currentTimeMillis() < this.toastUntil) {
            int tw = this.font.width(this.toast);
            int tx = (this.width - tw) / 2;
            int ty = this.height - 34;
            g.fill(tx - 6, ty - 3, tx + tw + 6, ty + 11, 0xE0201018);
            g.drawString(this.font, this.toast, tx, ty, GOLD, false);
        }

        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.tooltip != null && h.contains(mx, my) && this.activeDrag == null) {
                g.renderTooltip(this.font, Component.literal(h.tooltip), mx, my);
                break;
            }
        }
    }

    private void renderTopBar(GuiGraphics g, int mx, int my) {
        String[] labels = { "Undo", "Redo", "Frame", HologramRenderer.isPaused() ? "Play" : "Pause", "Web" };
        int[] widths = new int[labels.length];
        int total = 0;
        for (int i = 0; i < labels.length; i++) {
            widths[i] = this.font.width(labels[i]) + 12;
            total += widths[i] + 2;
        }
        int midL = this.leftX + this.leftW + 6;
        int midR = this.rightX - 6;
        int x = Math.max(midL, (midL + midR - total) / 2);
        int y = 5;
        for (int i = 0; i < labels.length; i++) {
            boolean enabled = switch (i) {
                case 0 -> this.editor.creatorCanUndo();
                case 1 -> this.editor.creatorCanRedo();
                default -> true;
            };
            final int idx = i;
            boolean lit = i == 3 ? HologramRenderer.isPaused() : i == 4 && HologramWebBridge.activeFor(this.editor);
            Hit hit = button(g, mx, my, x, y, widths[i], 15, labels[i], lit, enabled, () -> {
                switch (idx) {
                    case 0 -> this.editor.undo();
                    case 1 -> this.editor.redo();
                    case 2 -> this.editor.creatorFrameCamera();
                    case 3 -> HologramRenderer.setPaused(!HologramRenderer.isPaused());
                    default -> HologramWebBridge.open(this.editor);
                }
            });
            if (i == 4)
                hit.tooltip = "Open the advanced editor in your browser";
            x += widths[i] + 2;
        }
    }

    // ---------------------------------------------------------------- left: layers + looks

    private void renderLeft(GuiGraphics g, int mx, int my) {
        int x = this.leftX;
        int w = this.leftW;
        int top = this.panelTop;
        int bottom = this.panelBottom;
        panel(g, x, top, w, bottom - top);

        int y = top + 6;
        g.drawString(this.font, "LAYERS", x + 8, y, MUTED, false);
        List<HologramLayer> layers = this.editor.creatorLayers();
        int sel = this.editor.creatorSelectedLayer();
        int bx = x + w - 8;
        bx -= 14;
        iconButton(g, mx, my, bx, y - 3, 14, 13, "✕", layers.size() > 1 && sel >= 0, "Remove layer", () -> {
            this.editor.creatorRemoveLayer(this.editor.creatorSelectedLayer());
            this.expanded = -1;
        });
        bx -= 16;
        iconButton(g, mx, my, bx, y - 3, 14, 13, "⧉", sel >= 0 && layers.size() < HologramLayer.MAX_LAYERS,
                "Duplicate layer", () -> this.editor.creatorDuplicateLayer(this.editor.creatorSelectedLayer()));
        bx -= 16;
        iconButton(g, mx, my, bx, y - 3, 14, 13, "\u2316", layers.size() < HologramLayer.MAX_LAYERS,
                "Grab from world: click a block or mob (Shift = several)", () -> this.editor.creatorGrab(this));
        bx -= 16;
        iconButton(g, mx, my, bx, y - 3, 14, 13, "+", true,
                "Browse blocks, mobs and particles (replace this layer or add one)", () -> this.editor.creatorOpenPicker(this));
        y += 14;

        // Layer list gets at most ~45% of the panel; the looks below are the other half of this panel's job.
        int listMax = Math.max(ROW * 3, (bottom - top) * 45 / 100);
        int listH = Math.min(layers.size() * 16, listMax);
        int maxScroll = Math.max(0, layers.size() * 16 - listH);
        this.leftScroll = Mth.clamp(this.leftScroll, 0, maxScroll);
        g.enableScissor(x + 1, y, x + w - 1, y + listH);
        this.hitClipTop = y;
        this.hitClipBottom = y + listH;
        for (int i = 0; i < layers.size(); i++) {
            int ry = y + i * 16 - this.leftScroll;
            if (ry + 16 < y || ry > y + listH)
                continue;
            HologramLayer layer = layers.get(i);
            boolean selected = i == sel;
            boolean hover = mx >= x + 2 && mx < x + w - 2 && my >= ry && my < ry + 15 && my >= y && my < y + listH;
            if (selected) {
                g.fill(x + 2, ry, x + w - 2, ry + 15, ACCENT_BG);
                g.fill(x + 2, ry, x + 4, ry + 15, ACCENT);
            } else if (hover) {
                g.fill(x + 2, ry, x + w - 2, ry + 15, 0x18FFFFFF);
            }
            // eye
            final int li = i;
            String eye = layer.visible() ? "◉" : "○";
            g.drawString(this.font, eye, x + 7, ry + 4, layer.visible() ? TEXT : FAINT, false);
            Hit eyeHit = hit(x + 5, ry, 11, 15);
            eyeHit.click = () -> {
                this.editor.creatorPushUndo();
                this.editor.creatorUpdateLayer(li, l -> l.withVisible(!l.visible()));
            };
            eyeHit.tooltip = layer.visible() ? "Hide layer" : "Show layer";
            drawLayerIcon(g, layer, x + 18, ry);
            String name = this.font.plainSubstrByWidth(layer.shortName(), w - 60);
            g.drawString(this.font, name, x + 36, ry + 4, layer.visible() ? TEXT : MUTED, false);
            int fx = layer.effects().size();
            if (fx > 0) {
                String chip = fx + " fx";
                int cw = this.font.width(chip) + 4;
                g.fill(x + w - 8 - cw, ry + 3, x + w - 8, ry + 12, 0xFF262A34);
                g.drawString(this.font, chip, x + w - 6 - cw, ry + 4, VIOLET, false);
            }
            Hit row = hit(x + 16, ry, w - 18, 15);
            row.click = () -> {
                this.editor.creatorSelectLayer(li);
                this.expanded = -1;
                this.hsvSource = -1;
            };
        }
        g.disableScissor();
        this.hitClipTop = Integer.MIN_VALUE;
        this.hitClipBottom = Integer.MAX_VALUE;
        if (maxScroll > 0) {
            Hit scroller = hit(x, y, w, listH);
            scroller.wheel = d -> this.leftScroll = Mth.clamp(this.leftScroll - (int) (d * 16), 0, maxScroll);
            scrollbar(g, x + w - 4, y, listH, this.leftScroll, maxScroll);
        }
        y += listH + 10;

        // ---- Looks ----
        g.drawString(this.font, "LOOKS", x + 8, y, MUTED, false);
        g.drawString(this.font, "click = apply", x + w - 8 - this.font.width("click = apply"), y, FAINT, false);
        y += 12;
        int cols = 3;
        int gap = 4;
        int tile = (w - 16 - gap * (cols - 1)) / cols;
        int tileH = tile * 2 / 3;
        List<HologramEditorPrefs.Look> saved = HologramEditorPrefs.looks();
        int count = BUILTIN_LOOKS.size() + saved.size() + 1;
        for (int i = 0; i < count; i++) {
            int tx = x + 8 + (i % cols) * (tile + gap);
            int ty = y + (i / cols) * (tileH + 13);
            if (ty + tileH + 10 > bottom - 4)
                break;
            boolean hover = mx >= tx && mx < tx + tile && my >= ty && my < ty + tileH;
            if (i < BUILTIN_LOOKS.size()) {
                BuiltinLook look = BUILTIN_LOOKS.get(i);
                drawLookTile(g, tx, ty, tile, tileH, look.c1, look.c2, look.stripes, look.name.equals("Prism"), hover);
                label(g, look.name, tx, ty + tileH + 2, tile, hover ? ACCENT : MUTED);
                Hit h = hit(tx, ty, tile, tileH + 11);
                h.click = () -> applyBuiltin(look);
                h.tooltip = look.fx.isEmpty() ? "Remove every look effect (keeps motion)" : "Apply " + look.name + " (keeps motion)";
            } else if (i < BUILTIN_LOOKS.size() + saved.size()) {
                int si = i - BUILTIN_LOOKS.size();
                HologramEditorPrefs.Look look = saved.get(si);
                int[] c = lookColours(look.effects());
                drawLookTile(g, tx, ty, tile, tileH, c[0], c[1], false, false, hover);
                g.drawString(this.font, "★", tx + tile - 8, ty + 1, GOLD, false);
                label(g, look.name(), tx, ty + tileH + 2, tile, hover ? ACCENT : MUTED);
                Hit h = hit(tx, ty, tile, tileH + 11);
                h.click = () -> {
                    this.editor.creatorPushUndo();
                    this.editor.creatorSetEffects(look.effects());
                    this.hsvSource = -1;
                    toast("Applied " + look.name());
                };
                h.rightClick = () -> {
                    HologramEditorPrefs.removeLook(si);
                    toast("Deleted " + look.name());
                };
                h.tooltip = "Apply your look (everything: colour, FX and motion)  ·  right-click deletes";
            } else {
                g.fill(tx, ty, tx + tile, ty + tileH, hover ? SURFACE_HOT : SURFACE);
                outline(g, tx, ty, tile, tileH, hover ? ACCENT : 0x30FFFFFF);
                String plus = "+ Save";
                g.drawString(this.font, plus, tx + (tile - this.font.width(plus)) / 2, ty + (tileH - 8) / 2,
                        hover ? ACCENT : MUTED, false);
                label(g, "yours", tx, ty + tileH + 2, tile, FAINT);
                Hit h = hit(tx, ty, tile, tileH + 11);
                h.click = () -> {
                    HologramEditorPrefs.addLook(this.editor.creatorEffects());
                    toast("Saved as a look");
                };
                h.tooltip = "Save this layer's colour, FX and motion as one of your looks";
            }
        }
    }

    // ---------------------------------------------------------------- right: inspector

    private void renderRight(GuiGraphics g, int mx, int my) {
        int x = this.rightX;
        int w = this.rightW;
        int top = this.panelTop;
        int bottom = this.panelBottom;
        panel(g, x, top, w, bottom - top);

        // header
        List<HologramLayer> layers = this.editor.creatorLayers();
        int sel = this.editor.creatorSelectedLayer();
        HologramLayer layer = sel >= 0 && sel < layers.size() ? layers.get(sel) : null;
        String title = layer == null ? "No layer" : layer.shortName();
        g.drawString(this.font, this.font.plainSubstrByWidth(title, w - 16), x + 8, top + 6, TEXT, false);

        // footer
        int footY = bottom - 21;
        int bw = (w - 16 - 4) / 3;
        button(g, mx, my, x + 8, footY, bw, 15, "Studio ›", false, true, this.editor::openStudio)
                .tooltip = "The full editor: groups, gizmo, every number. Same hologram, nothing lost.";
        button(g, mx, my, x + 8 + bw + 2, footY, bw, 15, "Cancel", false, true, this.editor::cancelAndClose);
        primaryButton(g, mx, my, x + 8 + (bw + 2) * 2, footY, w - 16 - (bw + 2) * 2, 15, "Done", this.editor::saveAndClose)
                .tooltip = "Save and close";

        int clipTop = top + 18;
        int clipBottom = footY - 4;
        int viewH = clipBottom - clipTop;
        int maxScroll = Math.max(0, this.rightContentH - viewH);
        this.rightScroll = Mth.clamp(this.rightScroll, 0, maxScroll);
        Hit scroller = hit(x, clipTop, w, viewH);
        scroller.wheel = d -> this.rightScroll = Mth.clamp(this.rightScroll - (int) (d * 20), 0,
                Math.max(0, this.rightContentH - viewH));

        this.hexVisible = false;
        if (layer == null) {
            this.rightContentH = 0;
            return;
        }

        g.enableScissor(x + 1, clipTop, x + w - 1, clipBottom);
        this.hitClipTop = clipTop;
        this.hitClipBottom = clipBottom;
        int startY = clipTop + 2 - this.rightScroll;
        int y = startY;
        int cx = x + 8;
        int cw = w - 16 - (maxScroll > 0 ? 4 : 0);
        List<HologramEffect> effects = this.editor.creatorEffects();

        y = section(g, mx, my, Section.SHAPE, "SHAPE", cx, y, cw);
        if (!collapsed.contains(Section.SHAPE))
            y = renderShape(g, mx, my, layer, sel, cx, y, cw, clipTop, clipBottom);
        y = section(g, mx, my, Section.COLOUR, "COLOUR", cx, y, cw);
        if (!collapsed.contains(Section.COLOUR))
            y = renderColour(g, mx, my, effects, cx, y, cw, clipTop, clipBottom);
        y = section(g, mx, my, Section.FX, "LOOK FX", cx, y, cw);
        if (!collapsed.contains(Section.FX))
            y = renderTiles(g, mx, my, effects, FX_TILES, cx, y, cw, 4, true);
        y = section(g, mx, my, Section.MOTION, "MOTION", cx, y, cw);
        if (!collapsed.contains(Section.MOTION)) {
            y = renderTiles(g, mx, my, effects, MOTION_TILES, cx, y, cw, 5, false);
            if (layer.kind() == HologramLayer.Kind.ENTITY)
                y = renderTiles(g, mx, my, effects, ENTITY_TILES, cx, y, cw, 5, false);
        }
        y = section(g, mx, my, Section.STACK, "STACK  " + stackCount(effects) + "/" + HologramSpec.MAX_EFFECTS, cx, y, cw);
        if (!collapsed.contains(Section.STACK))
            y = renderStack(g, mx, my, effects, cx, y, cw, clipTop, clipBottom);
        y = section(g, mx, my, Section.REVEAL, "REVEAL", cx, y, cw);
        if (!collapsed.contains(Section.REVEAL))
            y = renderReveal(g, mx, my, cx, y, cw);
        g.disableScissor();
        this.hitClipTop = Integer.MIN_VALUE;
        this.hitClipBottom = Integer.MAX_VALUE;

        this.rightContentH = y - startY + 4;
        if (maxScroll > 0)
            scrollbar(g, x + w - 5, clipTop, viewH, this.rightScroll, maxScroll);
    }

    private int section(GuiGraphics g, int mx, int my, Section s, String title, int x, int y, int w) {
        y += 4;
        boolean open = !collapsed.contains(s);
        boolean hover = mx >= x && mx < x + w && my >= y - 2 && my < y + 10;
        g.fill(x, y - 3, x + w, y - 2, 0x14FFFFFF);
        g.drawString(this.font, (open ? "▾ " : "▸ ") + title, x, y + 1, hover ? TEXT : MUTED, false);
        Hit h = hit(x, y - 2, w, 12);
        h.click = () -> {
            if (!collapsed.remove(s))
                collapsed.add(s);
        };
        return y + 13;
    }

    private int renderShape(GuiGraphics g, int mx, int my, HologramLayer layer, int sel, int x, int y, int w,
                            int clipTop, int clipBottom) {
        float scale = this.editor.creatorScale();
        y = slider(g, mx, my, x, y, w, "Size", String.format(Locale.ROOT, "%.2f×", scale),
                (scale - 0.1F) / 3.9F, ACCENT, false, v -> this.editor.creatorSetScale(round(0.1F + (float) v * 3.9F, 0.05F)),
                d -> this.editor.creatorSetScale(Mth.clamp(this.editor.creatorScale() + (float) d * wheelStep(0.05F), 0.05F, 8.0F)),
                "Drag or scroll. Shift = fine, Ctrl = coarse.");
        boolean glow = layer.fullbright();
        int cw = (w - 4) / 2;
        chip(g, mx, my, x, y, cw, "Glow: " + (glow ? "On" : "Off"), glow, () -> {
            this.editor.creatorPushUndo();
            this.editor.creatorUpdateLayer(sel, l -> l.withFullbright(!l.fullbright()));
        }).tooltip = "Full brightness, ignores world light";
        chip(g, mx, my, x + cw + 4, y, w - cw - 4, layer.visible() ? "Visible" : "Hidden", layer.visible(), () -> {
            this.editor.creatorPushUndo();
            this.editor.creatorUpdateLayer(sel, l -> l.withVisible(!l.visible()));
        });
        return y + ROW + 2;
    }

    // ---- colour ----

    private int renderColour(GuiGraphics g, int mx, int my, List<HologramEffect> effects, int x, int y, int w,
                             int clipTop, int clipBottom) {
        int tintIdx = indexOf(effects, HologramEffect.Type.TINT);
        int gradIdx = indexOf(effects, HologramEffect.Type.GRADIENT);
        int mode = gradIdx >= 0 ? 2 : (tintIdx >= 0 ? 1 : 0);
        String[] modes = { "None", "Solid", "Gradient" };
        int mw = (w - 4) / 3;
        for (int i = 0; i < 3; i++) {
            final int m = i;
            chip(g, mx, my, x + i * (mw + 2), y, i == 2 ? w - (mw + 2) * 2 : mw, modes[i], mode == i, () -> setColourMode(m))
                    .tooltip = "Colour: " + modes[i];
        }
        y += ROW + 4;
        if (mode == 0) {
            g.drawString(this.font, "The block keeps its own colours.", x, y, FAINT, false);
            return y + 12;
        }

        int effIdx = mode == 2 ? gradIdx : tintIdx;
        HologramEffect eff = effects.get(effIdx);
        int stop = mode == 2 ? this.gradStop : 0;
        int current = stop == 1 ? eff.color2() : eff.color();
        syncHsv(current);

        // wheel
        int wx = x;
        int wy = y;
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(Math.max(this.val, 0.04F), Math.max(this.val, 0.04F), Math.max(this.val, 0.04F), 1.0F);
        g.blit(WHEEL_TEX, wx, wy, WHEEL, WHEEL, 0, 0, 128, 128, 128, 128);
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        float ang = this.hue * Mth.TWO_PI;
        int kx = wx + WHEEL / 2 + Math.round(Mth.cos(ang) * this.sat * (WHEEL / 2 - 1));
        int ky = wy + WHEEL / 2 - Math.round(Mth.sin(ang) * this.sat * (WHEEL / 2 - 1));
        outline(g, kx - 3, ky - 3, 7, 7, 0xFF000000);
        outline(g, kx - 2, ky - 2, 5, 5, 0xFFFFFFFF);
        final int fEffIdx = effIdx;
        Hit wheelHit = hit(wx, wy, WHEEL, WHEEL);
        wheelHit.drag = (ax, ay) -> {
            double dx = ax - (wx + WHEEL / 2.0);
            double dy = (wy + WHEEL / 2.0) - ay;
            double r = Math.min(1.0, Math.sqrt(dx * dx + dy * dy) / (WHEEL / 2.0));
            double a = Math.atan2(dy, dx);
            this.hue = (float) ((a / (Math.PI * 2) + 1.0) % 1.0);
            this.sat = (float) r;
            writeColour(fEffIdx, stop);
        };
        wheelHit.tooltip = "Hue and saturation";

        // value bar (vertical)
        int vx = wx + WHEEL + 5;
        int top = Mth.hsvToRgb(this.hue, this.sat, 1.0F) | 0xFF000000;
        g.fillGradient(vx, wy, vx + 7, wy + WHEEL, top, 0xFF000000);
        outline(g, vx - 1, wy - 1, 9, WHEEL + 2, 0x40FFFFFF);
        int vk = wy + Math.round((1.0F - this.val) * (WHEEL - 1));
        g.fill(vx - 2, vk - 1, vx + 9, vk + 1, 0xFFFFFFFF);
        Hit valHit = hit(vx - 2, wy, 11, WHEEL);
        valHit.drag = (ax, ay) -> {
            this.val = Mth.clamp((float) (1.0 - (ay - wy) / (WHEEL - 1)), 0.0F, 1.0F);
            writeColour(fEffIdx, stop);
        };
        valHit.tooltip = "Brightness";

        // right of the wheel: gradient stops or swatch, hex, mix
        int rx = vx + 14;
        int rw = x + w - rx;
        int ry = wy;
        if (mode == 2) {
            int c1 = eff.color() | 0xFF000000;
            int c2 = eff.color2() | 0xFF000000;
            for (int i = 0; i < rw; i++) {
                float t = i / (float) Math.max(1, rw - 1);
                g.fill(rx + i, ry, rx + i + 1, ry + 10, lerpColour(c1, c2, t));
            }
            outline(g, rx - 1, ry - 1, rw + 2, 12, 0x40FFFFFF);
            for (int s = 0; s < 2; s++) {
                int sx = s == 0 ? rx : rx + rw - 1;
                boolean on = this.gradStop == s;
                g.fill(sx - 3, ry + 12, sx + 4, ry + 19, (s == 0 ? c1 : c2));
                outline(g, sx - 4, ry + 11, 9, 9, on ? 0xFFFFFFFF : 0x80000000);
                final int fs = s;
                Hit sh = hit(sx - 5, ry + 10, 11, 11);
                sh.click = () -> {
                    this.gradStop = fs;
                    this.hsvSource = -1;
                };
                sh.tooltip = s == 0 ? "Bottom colour" : "Top colour";
            }
            g.drawString(this.font, "bottom", rx, ry + 21, this.gradStop == 0 ? TEXT : FAINT, false);
            String topL = "top";
            g.drawString(this.font, topL, rx + rw - this.font.width(topL), ry + 21, this.gradStop == 1 ? TEXT : FAINT, false);
            Hit swap = hit(rx + rw / 2 - 8, ry + 19, 16, 11);
            g.drawString(this.font, "⇄", rx + rw / 2 - 3, ry + 21, MUTED, false);
            swap.click = () -> {
                this.editor.creatorPushUndo();
                updateEffect(fEffIdx, e -> e.withColor(e.color2()).withColor2(e.color()));
                this.hsvSource = -1;
            };
            swap.tooltip = "Swap top and bottom";
            ry += 33;
        } else {
            g.fill(rx, ry, rx + rw, ry + 22, current | 0xFF000000);
            outline(g, rx - 1, ry - 1, rw + 2, 24, 0x40FFFFFF);
            ry += 27;
        }

        // hex field
        g.drawString(this.font, "#", rx, ry + 1, MUTED, false);
        g.fill(rx + 7, ry - 1, rx + rw, ry + 10, SURFACE);
        boolean visible = ry - 1 >= clipTop && ry + 10 <= clipBottom;
        this.hexVisible = visible;
        this.hexBox.setX(rx + 9);
        this.hexBox.setY(ry + 1);
        this.hexBox.setWidth(rw - 10);
        if (!this.hexBox.isFocused())
            this.hexBox.setValue(String.format(Locale.ROOT, "%06X", current));
        this.hexBox.setResponder(text -> {
            if (text.length() == 6) {
                try {
                    int c = Integer.parseInt(text, 16);
                    if (c != currentColour()) {
                        this.editor.creatorPushUndo();
                        final int fStop = stop;
                        updateEffect(fEffIdx, e -> fStop == 1 ? e.withColor2(c) : e.withColor(c));
                        this.hsvSource = -1;
                    }
                } catch (NumberFormatException ignored) {
                    // not hex yet: keep typing
                }
            }
        });
        ry += 14;

        // mix
        slider(g, mx, my, rx, ry, rw, "Mix", Math.round(eff.strength() * 100) + "%", eff.strength(), ACCENT, false,
                v -> updateEffect(fEffIdx, e -> e.withStrength((float) v)),
                d -> updateEffect(fEffIdx, e -> e.withStrength(Mth.clamp(e.strength() + (float) d * wheelStep(0.05F), 0.0F, 1.0F))),
                "How strongly the colour replaces the block's own");

        y = wy + WHEEL + 5;
        // swatches
        int sw = Math.min(14, (w - (SWATCHES.length - 1) * 2) / SWATCHES.length);
        for (int i = 0; i < SWATCHES.length; i++) {
            int sx = x + i * (sw + 2);
            int c = SWATCHES[i];
            boolean on = c == current;
            g.fill(sx, y, sx + sw, y + sw, c | 0xFF000000);
            outline(g, sx - 1, y - 1, sw + 2, sw + 2, on ? 0xFFFFFFFF : 0x30FFFFFF);
            Hit h = hit(sx, y, sw, sw);
            h.click = () -> {
                this.editor.creatorPushUndo();
                updateEffect(fEffIdx, e -> stop == 1 ? e.withColor2(c) : e.withColor(c));
                this.hsvSource = -1;
            };
        }
        return y + sw + 6;
    }

    private int currentColour() {
        List<HologramEffect> effects = this.editor.creatorEffects();
        int gradIdx = indexOf(effects, HologramEffect.Type.GRADIENT);
        if (gradIdx >= 0)
            return this.gradStop == 1 ? effects.get(gradIdx).color2() : effects.get(gradIdx).color();
        int tintIdx = indexOf(effects, HologramEffect.Type.TINT);
        return tintIdx >= 0 ? effects.get(tintIdx).color() : -1;
    }

    private void syncHsv(int rgb) {
        if (rgb == this.hsvSource)
            return;
        this.hsvSource = rgb;
        float r = (rgb >> 16 & 0xFF) / 255.0F;
        float gg = (rgb >> 8 & 0xFF) / 255.0F;
        float b = (rgb & 0xFF) / 255.0F;
        float max = Math.max(r, Math.max(gg, b));
        float min = Math.min(r, Math.min(gg, b));
        float d = max - min;
        this.val = max;
        this.sat = max <= 0.0F ? 0.0F : d / max;
        if (d <= 0.0F) {
            return; // grey: keep the hue the user last had, so dragging saturation back up stays on it
        }
        float h;
        if (max == r)
            h = ((gg - b) / d) % 6.0F;
        else if (max == gg)
            h = (b - r) / d + 2.0F;
        else
            h = (r - gg) / d + 4.0F;
        this.hue = ((h / 6.0F) + 1.0F) % 1.0F;
    }

    private void writeColour(int effIdx, int stop) {
        int rgb = Mth.hsvToRgb(this.hue, this.sat, this.val) & 0xFFFFFF;
        this.hsvSource = rgb;
        updateEffect(effIdx, e -> stop == 1 ? e.withColor2(rgb) : e.withColor(rgb));
    }

    private void setColourMode(int mode) {
        List<HologramEffect> effects = new ArrayList<>(this.editor.creatorEffects());
        int tintIdx = indexOf(effects, HologramEffect.Type.TINT);
        int gradIdx = indexOf(effects, HologramEffect.Type.GRADIENT);
        int seed = tintIdx >= 0 ? effects.get(tintIdx).color()
                : gradIdx >= 0 ? effects.get(gradIdx).color() : HologramEffect.DEFAULT_COLOR;
        float strength = tintIdx >= 0 ? effects.get(tintIdx).strength()
                : gradIdx >= 0 ? effects.get(gradIdx).strength() : 0.7F;
        effects.removeIf(e -> e.type() == HologramEffect.Type.TINT || e.type() == HologramEffect.Type.GRADIENT);
        // Colour sits first in the stack so the list reads colour -> FX -> motion, like the panel does.
        if (mode == 1)
            effects.add(0, HologramEffect.of(HologramEffect.Type.TINT).withColor(seed).withStrength(strength));
        else if (mode == 2)
            effects.add(0, HologramEffect.of(HologramEffect.Type.GRADIENT).withColor(seed).withStrength(strength));
        if (effects.size() > HologramSpec.MAX_EFFECTS) {
            toast("Stack is full (" + HologramSpec.MAX_EFFECTS + ")");
            return;
        }
        this.editor.creatorPushUndo();
        this.editor.creatorSetEffects(effects);
        this.gradStop = 0;
        this.hsvSource = -1;
    }

    // ---- FX / motion tiles ----

    private int renderTiles(GuiGraphics g, int mx, int my, List<HologramEffect> effects, HologramEffect.Type[] types,
                            int x, int y, int w, int cols, boolean single) {
        int gap = 3;
        int tw = (w - gap * (cols - 1)) / cols;
        int th = 22;
        for (int i = 0; i < types.length; i++) {
            HologramEffect.Type type = types[i];
            int tx = x + (i % cols) * (tw + gap);
            int ty = y + (i / cols) * (th + gap);
            int n = 0;
            for (HologramEffect e : effects)
                if (e.type() == type)
                    n++;
            boolean on = n > 0;
            boolean hover = mx >= tx && mx < tx + tw && my >= ty && my < ty + th;
            g.fill(tx, ty, tx + tw, ty + th, on ? ACCENT_BG : (hover ? SURFACE_HOT : SURFACE));
            outline(g, tx, ty, tw, th, on ? ACCENT : (hover ? 0x60FFFFFF : 0x20FFFFFF));
            String glyph = glyph(type);
            g.drawString(this.font, glyph, tx + (tw - this.font.width(glyph)) / 2, ty + 3, on ? ACCENT : TEXT, false);
            small(g, type.shortName(), tx + tw / 2, ty + 13, on ? ACCENT : MUTED);
            if (n > 1)
                small(g, "×" + n, tx + tw - 5, ty + 2, GOLD);
            Hit h = hit(tx, ty, tw, th);
            h.click = () -> {
                List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
                if (single && indexOf(cur, type) >= 0) {
                    this.editor.creatorPushUndo();
                    cur.removeIf(e -> e.type() == type);
                    this.editor.creatorSetEffects(cur);
                    return;
                }
                if (cur.size() >= HologramSpec.MAX_EFFECTS) {
                    toast("Stack is full (" + HologramSpec.MAX_EFFECTS + ")");
                    return;
                }
                this.editor.creatorPushUndo();
                cur.add(single ? HologramEffect.of(type) : HologramEffect.addable(type, cur));
                this.editor.creatorSetEffects(cur);
                this.expanded = -1;
            };
            h.rightClick = () -> {
                List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
                if (indexOf(cur, type) < 0)
                    return;
                this.editor.creatorPushUndo();
                cur.removeIf(e -> e.type() == type);
                this.editor.creatorSetEffects(cur);
                this.expanded = -1;
            };
            h.tooltip = single ? describe(type) + "  ·  click toggles"
                    : describe(type) + "  ·  click adds (again = next axis), right-click removes all";
        }
        int rows = (types.length + cols - 1) / cols;
        return y + rows * (th + gap) + 2;
    }

    // ---- stack ----

    private int stackCount(List<HologramEffect> effects) {
        return effects.size();
    }

    private int renderStack(GuiGraphics g, int mx, int my, List<HologramEffect> effects, int x, int y, int w,
                            int clipTop, int clipBottom) {
        List<Integer> rows = new ArrayList<>();
        for (int i = 0; i < effects.size(); i++) {
            HologramEffect.Type t = effects.get(i).type();
            if (t != HologramEffect.Type.TINT && t != HologramEffect.Type.GRADIENT)
                rows.add(i);
        }
        if (rows.isEmpty()) {
            g.drawString(this.font, "Nothing yet: pick a look, FX or motion.", x, y, FAINT, false);
            return y + 12;
        }
        int rowH = 16;
        int listTop = y;
        for (int r = 0; r < rows.size(); r++) {
            int idx = rows.get(r);
            HologramEffect e = effects.get(idx);
            int ry = y;
            boolean dragging = this.reorderFrom == idx;
            if (this.reorderFrom >= 0 && this.reorderTo == idx && !dragging)
                g.fill(x, ry - 1, x + w, ry, ACCENT);
            g.fill(x, ry, x + w, ry + rowH - 1, dragging ? 0x30FFFFFF : SURFACE);

            // handle
            g.drawString(this.font, "⋮", x + 3, ry + 4, FAINT, false);
            g.drawString(this.font, "⋮", x + 6, ry + 4, FAINT, false);
            final int fIdx = idx;
            final List<Integer> fRows = rows;
            final int fListTop = listTop;
            Hit handle = hit(x, ry, 11, rowH - 1);
            handle.drag = (ax, ay) -> {
                if (this.reorderFrom < 0)
                    this.reorderFrom = fIdx;
                int slot = Mth.clamp((int) ((ay - fListTop) / rowH), 0, fRows.size() - 1);
                this.reorderTo = fRows.get(slot);
            };
            handle.tooltip = "Drag to reorder";

            // name (click = expand)
            String name = glyph(e.type()) + " " + e.type().shortName();
            g.drawString(this.font, name, x + 13, ry + 4, this.expanded == idx ? ACCENT : TEXT, false);
            Hit nameHit = hit(x + 12, ry, 46, rowH - 1);
            nameHit.click = () -> this.expanded = this.expanded == fIdx ? -1 : fIdx;
            nameHit.tooltip = "Click for speed and timing";

            int px = x + 58;
            // axis chip
            if (e.type().usesAxis()) {
                int ac = switch (e.axisLabel()) {
                    case "X" -> AXIS_COLORS[0];
                    case "Y" -> AXIS_COLORS[1];
                    default -> AXIS_COLORS[2];
                };
                g.fill(px, ry + 3, px + 10, ry + 12, (ac & 0x00FFFFFF) | 0x50000000);
                g.drawString(this.font, e.axisLabel(), px + 2, ry + 4, ac, false);
                Hit ah = hit(px, ry, 10, rowH - 1);
                ah.click = () -> {
                    this.editor.creatorPushUndo();
                    updateEffect(fIdx, ef -> ef.withAxis((ef.axis() + 1) % 3));
                };
                ah.tooltip = "Axis (click cycles X/Y/Z)";
            }
            px += 13;

            // when chip + remove at the right
            String when = e.trigger().shortLabel();
            int ww = this.font.width(when) + 6;
            int rx = x + w - 11;
            int wx = rx - 2 - ww;
            boolean always = e.trigger() == HologramEffect.Trigger.ALWAYS;
            g.fill(wx, ry + 3, wx + ww, ry + 12, always ? 0xFF262A34 : 0x404CC9F0);
            g.drawString(this.font, when, wx + 3, ry + 4, always ? MUTED : ACCENT, false);
            Hit wh = hit(wx, ry, ww, rowH - 1);
            wh.click = () -> {
                this.editor.creatorPushUndo();
                updateEffect(fIdx, ef -> ef.withTrigger(ef.trigger().next()));
            };
            wh.rightClick = () -> {
                this.editor.creatorPushUndo();
                updateEffect(fIdx, ef -> ef.withTrigger(HologramEffect.Trigger.ALWAYS));
            };
            wh.tooltip = "When it plays: " + e.trigger().label() + "  ·  click cycles, right-click = always";

            g.drawString(this.font, "✕", rx + 2, ry + 4, mx >= rx && mx < rx + 10 && my >= ry && my < ry + rowH ? DANGER : FAINT, false);
            Hit xh = hit(rx, ry, 11, rowH - 1);
            xh.click = () -> {
                List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
                if (fIdx < cur.size()) {
                    this.editor.creatorPushUndo();
                    cur.remove(fIdx);
                    this.editor.creatorSetEffects(cur);
                    this.expanded = -1;
                }
            };
            xh.tooltip = "Remove";

            // strength bar
            int bx = px;
            int bw = wx - 4 - bx;
            if (bw > 12 && e.type().usesAmount()) {
                float min = e.type().isFx() ? 0.0F : e.type().minAmount();
                float max = e.type().isFx() ? 2.0F : e.type().maxAmount();
                float t = (e.strength() - min) / (max - min);
                bar(g, bx, ry + 6, bw, t, min < 0 ? (-min) / (max - min) : 0.0F, VIOLET);
                Hit bh = hit(bx, ry, bw, rowH - 1);
                bh.drag = (ax, ay) -> {
                    float v = min + (float) Mth.clamp((ax - bx) / bw, 0.0, 1.0) * (max - min);
                    updateEffect(fIdx, ef -> ef.withStrength(snap(ef.type(), v)));
                };
                bh.wheel = d -> updateEffect(fIdx, ef -> ef.withStrength(
                        Mth.clamp(ef.strength() + (float) d * wheelStep(ef.type().amountStep()), min, max)));
                bh.tooltip = amountText(e);
            }
            y += rowH;

            if (this.expanded == idx) {
                g.fill(x, y - 1, x + w, y + (e.type().usesSpeed() ? 30 : 16), 0xFF10151C);
                if (e.type().usesSpeed()) {
                    float sp = e.speed();
                    y = slider(g, mx, my, x + 4, y + 1, w - 8, "Speed", String.format(Locale.ROOT, "%.2f Hz", sp),
                            (float) (Math.log(sp / 0.05) / Math.log(20.0 / 0.05)), VIOLET, true,
                            v -> updateEffect(fIdx, ef -> ef.withSpeed((float) (0.05 * Math.pow(20.0 / 0.05, v)))),
                            d -> updateEffect(fIdx, ef -> ef.withSpeed(Mth.clamp(ef.speed() * (float) Math.pow(1.1, d), 0.05F, 20.0F))),
                            "Cycles per second (log scale)");
                }
                if (e.type().usesPhase()) {
                    y = slider(g, mx, my, x + 4, y + 1, w - 8, "Offset", Math.round(e.phase() * 100) + "%", e.phase(),
                            VIOLET, true, v -> updateEffect(fIdx, ef -> ef.withPhase((float) Math.min(v, 0.999))),
                            d -> updateEffect(fIdx, ef -> ef.withPhase(ef.phase() + (float) d * 0.05F)),
                            "Where in its cycle it starts, so copies run out of step");
                } else {
                    y += 2;
                }
            }
        }
        return y + 4;
    }

    // ---- reveal ----

    private int renderReveal(GuiGraphics g, int mx, int my, int x, int y, int w) {
        float radius = this.editor.creatorAppearRadius();
        int style = this.editor.creatorAppearStyle();
        boolean off = radius <= 0.0F;
        int cols = 4;
        int cw = (w - (cols - 1) * 2) / cols;
        for (int i = 0; i <= REVEALS.length; i++) {
            int cx = x + (i % cols) * (cw + 2);
            int cy = y + (i / cols) * (ROW + 2);
            boolean on = i == 0 ? off : (!off && style == i - 1);
            final int fi = i;
            chip(g, mx, my, cx, cy, cw, i == 0 ? "Always" : REVEALS[i - 1], on, () -> {
                this.editor.creatorPushUndo();
                if (fi == 0)
                    this.editor.creatorSetAppear(0.0F, this.editor.creatorAppearStyle());
                else
                    this.editor.creatorSetAppear(this.editor.creatorAppearRadius() <= 0.0F ? 8.0F
                            : this.editor.creatorAppearRadius(), fi - 1);
            }).tooltip = i == 0 ? "Always visible" : "Appears with a " + REVEALS[i - 1].toLowerCase(Locale.ROOT)
                    + " when a player walks within the radius";
        }
        y += ((REVEALS.length + cols) / cols) * (ROW + 2) + 2;
        if (!off) {
            y = slider(g, mx, my, x, y, w, "Radius", Math.round(radius) + " blocks", radius / 64.0F, ACCENT, false,
                    v -> this.editor.creatorSetAppear(Math.max(1.0F, Math.round((float) v * 64.0F)), this.editor.creatorAppearStyle()),
                    d -> this.editor.creatorSetAppear(Mth.clamp(this.editor.creatorAppearRadius() + (float) d, 1.0F, 64.0F),
                            this.editor.creatorAppearStyle()), "How close a player must be");
        }
        return y + 2;
    }

    // ---------------------------------------------------------------- effect helpers

    private void updateEffect(int index, java.util.function.UnaryOperator<HologramEffect> fn) {
        List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
        if (index < 0 || index >= cur.size())
            return;
        cur.set(index, fn.apply(cur.get(index)));
        this.editor.creatorSetEffects(cur);
    }

    private void applyBuiltin(BuiltinLook look) {
        List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
        cur.removeIf(e -> e.type().isFx());
        List<HologramEffect> next = new ArrayList<>(look.fx);
        next.addAll(cur);
        if (next.size() > HologramSpec.MAX_EFFECTS)
            next = next.subList(0, HologramSpec.MAX_EFFECTS);
        this.editor.creatorPushUndo();
        this.editor.creatorSetEffects(next);
        this.hsvSource = -1;
        this.gradStop = 0;
        toast(look.fx.isEmpty() ? "Look cleared" : "Applied " + look.name);
    }

    private static int indexOf(List<HologramEffect> effects, HologramEffect.Type type) {
        for (int i = 0; i < effects.size(); i++)
            if (effects.get(i).type() == type)
                return i;
        return -1;
    }

    private static int[] lookColours(List<HologramEffect> effects) {
        int grad = indexOf(effects, HologramEffect.Type.GRADIENT);
        if (grad >= 0)
            return new int[] { effects.get(grad).color() | 0xFF000000, effects.get(grad).color2() | 0xFF000000 };
        int tint = indexOf(effects, HologramEffect.Type.TINT);
        if (tint >= 0) {
            int c = effects.get(tint).color() | 0xFF000000;
            return new int[] { lerpColour(0xFF000000, c, 0.35F), c };
        }
        return new int[] { 0xFF2A2F38, 0xFF6B7383 };
    }

    private static float snap(HologramEffect.Type type, float v) {
        float step = type.amountStep() / 2.0F;
        return step > 0 ? Math.round(v / step) * step : v;
    }

    private static String amountText(HologramEffect e) {
        return switch (e.type()) {
            case SPIN -> Math.round(e.strength()) + "°/s";
            case SWAY, FLIP -> Math.round(e.strength()) + "°";
            case BOB, ORBIT, SLIDE, BOUNCE -> String.format(Locale.ROOT, "%.2f blocks", e.strength());
            default -> Math.round(e.strength() * 100) + "%";
        };
    }

    private static String glyph(HologramEffect.Type type) {
        return switch (type) {
            case TINT -> "■";
            case GRADIENT -> "▤";
            case SCANLINE -> "≡";
            case GLITCH -> "⚡";
            case PULSE -> "☀";
            case RAINBOW -> "❂";
            case FLICKER -> "░";
            case TREMOR -> "≈";
            case WOBBLE -> "∿";
            case JUMP -> "⤒";
            case SPIN -> "↻";
            case SWAY -> "↔";
            case BOB -> "↕";
            case ORBIT -> "◌";
            case SLIDE -> "⇄";
            case BREATHE -> "◎";
            case HEARTBEAT -> "♥";
            case FLIP -> "↺";
            case BOUNCE -> "⤴";
            case WALK -> "→";
            case ATTACK -> "⚔";
            case HURT -> "✖";
            case DEATH -> "☠";
            case SNEAK -> "↘";
        };
    }

    private static String describe(HologramEffect.Type type) {
        return switch (type) {
            case SCANLINE -> "Sweeping sci-fi scan bands";
            case GLITCH -> "Glitch bursts with a colour flash";
            case PULSE -> "Brightness pulse";
            case RAINBOW -> "Cycles through every colour";
            case FLICKER -> "Blinks out for a frame now and then";
            case TREMOR -> "Small fast shake";
            case WOBBLE -> "Squash and stretch";
            case JUMP -> "Mistimed vertical snap";
            case SPIN -> "Turns forever";
            case BOB -> "Floats up and down";
            case SWAY -> "Rocks back and forth";
            case ORBIT -> "Circles around";
            case SLIDE -> "Glides side to side";
            case BREATHE -> "Grows and shrinks";
            case HEARTBEAT -> "Double thump";
            case FLIP -> "Coin flip, then rests";
            case BOUNCE -> "Hops";
            case WALK -> "Walk cycle";
            case ATTACK -> "Swings its arms";
            case HURT -> "Red hurt flash";
            case DEATH -> "Falls over and gets up";
            case SNEAK -> "Crouches";
            default -> type.shortName();
        };
    }

    // ---------------------------------------------------------------- input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (this.hexBox.visible && this.hexBox.isMouseOver(mx, my)) {
            setFocused(this.hexBox);
            this.hexBox.setFocused(true);
            return super.mouseClicked(mx, my, button);
        }
        if (this.hexBox.isFocused()) {
            this.hexBox.setFocused(false);
            setFocused(null);
        }
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (!h.contains(mx, my))
                continue;
            if (button == 1 && h.rightClick != null) {
                h.rightClick.run();
                return true;
            }
            if (button == 0 && h.drag != null) {
                this.editor.creatorPushUndo();
                this.activeDrag = h.drag;
                h.drag.at(mx, my);
                return true;
            }
            if (button == 0 && h.click != null) {
                h.click.run();
                return true;
            }
            if (h.click != null || h.drag != null || h.rightClick != null)
                return true;
        }
        if (insidePanels(mx, my))
            return true;
        if (button == 0 || button == 1) {
            this.orbiting = true;
            return true;
        }
        if (button == 2) {
            this.panning = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.activeDrag != null) {
            this.activeDrag.at(mx, my);
            return true;
        }
        if (this.orbiting && HologramCamera.isActive()) {
            HologramCamera.turn(dx, dy);
            return true;
        }
        if (this.panning && HologramCamera.isActive()) {
            HologramCamera.pan(dx, dy);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (this.reorderFrom >= 0) {
            int from = this.reorderFrom;
            int to = this.reorderTo;
            this.reorderFrom = -1;
            this.reorderTo = -1;
            if (to >= 0 && to != from) {
                List<HologramEffect> cur = new ArrayList<>(this.editor.creatorEffects());
                if (from < cur.size() && to < cur.size()) {
                    HologramEffect moved = cur.remove(from);
                    cur.add(to, moved);
                    this.editor.creatorSetEffects(cur);
                    this.expanded = -1;
                }
            }
        }
        this.activeDrag = null;
        this.orbiting = false;
        this.panning = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if (sy == 0)
            return false;
        // Most specific first: a slider under the mouse beats the panel scroll that contains it.
        Hit best = null;
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.wheel != null && h.contains(mx, my)) {
                if (best == null || h.w * h.h < best.w * best.h)
                    best = h;
            }
        }
        if (best != null) {
            boolean adjusts = best.w * best.h < 4000;
            if (adjusts)
                this.editor.creatorPushUndo();
            best.wheel.accept(Math.signum(sy));
            return true;
        }
        if (!insidePanels(mx, my) && HologramCamera.isActive()) {
            HologramCamera.dolly(Math.signum(sy) * (hasShiftDown() ? 0.35 : 1.0));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (this.hexBox.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_ESCAPE) {
                this.hexBox.setFocused(false);
                setFocused(null);
                this.hsvSource = -1;
                return true;
            }
            return super.keyPressed(key, scan, mods);
        }
        if (key == GLFW.GLFW_KEY_Z && hasControlDown()) {
            if (hasShiftDown())
                this.editor.redo();
            else
                this.editor.undo();
            this.hsvSource = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_Y && hasControlDown()) {
            this.editor.redo();
            this.hsvSource = -1;
            return true;
        }
        if (key == GLFW.GLFW_KEY_F) {
            this.editor.creatorFrameCamera();
            return true;
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            if (this.minecraft.options.keyChat.matches(key, scan)) {
                this.minecraft.tell(() -> this.minecraft.setScreen(new HologramChatScreen("", this)));
                return true;
            }
            if (this.minecraft.options.keyCommand.matches(key, scan)) {
                this.minecraft.tell(() -> this.minecraft.setScreen(new HologramChatScreen("/", this)));
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    private boolean insidePanels(double mx, double my) {
        boolean left = mx >= this.leftX && mx < this.leftX + this.leftW && my >= this.panelTop && my < this.panelBottom;
        boolean right = mx >= this.rightX && mx < this.rightX + this.rightW && my >= this.panelTop && my < this.panelBottom;
        boolean topBar = my < 22 && mx > this.leftX + this.leftW && mx < this.rightX;
        return left || right || topBar;
    }

    private static float wheelStep(float base) {
        if (hasControlDown())
            return base * 5.0F;
        if (hasShiftDown())
            return base * 0.2F;
        return base;
    }

    private static float round(float v, float step) {
        return Math.round(v / step) * step;
    }

    private void toast(String text) {
        this.toast = text;
        this.toastUntil = System.currentTimeMillis() + 1800L;
    }

    // ---------------------------------------------------------------- drawing primitives

    private Hit hit(int x, int y, int w, int h) {
        Hit hit = new Hit(x, y, w, h);
        hit.clipTop = this.hitClipTop;
        hit.clipBottom = this.hitClipBottom;
        this.hits.add(hit);
        return hit;
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BG);
        outline(g, x, y, w, h, PANEL_BORDER);
    }

    private static void outline(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y, x + 1, y + h, color);
        g.fill(x + w - 1, y, x + w, y + h, color);
    }

    private Hit button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, boolean active,
                       boolean enabled, Runnable onClick) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, active ? ACCENT_BG : (hover ? SURFACE_HOT : SURFACE));
        outline(g, x, y, w, h, active ? ACCENT : (hover ? 0x60FFFFFF : 0x25FFFFFF));
        int color = !enabled ? FAINT : (active ? ACCENT : TEXT);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, color, false);
        Hit hit = hit(x, y, w, h);
        if (enabled)
            hit.click = onClick;
        return hit;
    }

    private Hit primaryButton(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, Runnable onClick) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF7FDBF5 : ACCENT);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + (h - 8) / 2, 0xFF04151A, false);
        Hit hit = hit(x, y, w, h);
        hit.click = onClick;
        return hit;
    }

    private void iconButton(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String glyph, boolean enabled,
                            String tooltip, Runnable onClick) {
        boolean hover = enabled && mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, hover ? SURFACE_HOT : SURFACE);
        g.drawString(this.font, glyph, x + (w - this.font.width(glyph)) / 2 + 1, y + (h - 8) / 2 + 1,
                enabled ? (hover ? ACCENT : TEXT) : FAINT, false);
        Hit hit = hit(x, y, w, h);
        hit.tooltip = tooltip;
        if (enabled)
            hit.click = onClick;
    }

    private Hit chip(GuiGraphics g, int mx, int my, int x, int y, int w, String label, boolean on, Runnable onClick) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + ROW;
        g.fill(x, y, x + w, y + ROW - 1, on ? ACCENT_BG : (hover ? SURFACE_HOT : SURFACE));
        outline(g, x, y, w, ROW - 1, on ? ACCENT : (hover ? 0x50FFFFFF : 0x20FFFFFF));
        String text = this.font.plainSubstrByWidth(label, w - 4);
        g.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y + 3, on ? ACCENT : TEXT, false);
        Hit hit = hit(x, y, w, ROW - 1);
        hit.click = onClick;
        return hit;
    }

    /** Label, value and a draggable bar on one row. Returns the next row's y. */
    private int slider(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String value, float t,
                       int color, boolean compact, DoubleConsumer set, DoubleConsumer wheel, String tooltip) {
        int labelW = compact ? 38 : 34;
        g.drawString(this.font, label, x, y + 3, MUTED, false);
        int vw = this.font.width(value);
        g.drawString(this.font, value, x + w - vw, y + 3, TEXT, false);
        int bx = x + labelW;
        int bw = w - labelW - vw - 6;
        if (bw > 8) {
            bar(g, bx, y + 6, bw, t, 0.0F, color);
            Hit h = hit(bx - 3, y, bw + 6, ROW - 1);
            h.drag = (ax, ay) -> set.accept(Mth.clamp((ax - bx) / bw, 0.0, 1.0));
            h.wheel = wheel;
            h.tooltip = tooltip;
        }
        return y + ROW;
    }

    private static void bar(GuiGraphics g, int x, int y, int w, float t, float zero, int color) {
        t = Mth.clamp(t, 0.0F, 1.0F);
        g.fill(x, y - 1, x + w, y + 2, 0xFF262A34);
        int z = x + Math.round(zero * w);
        int k = x + Math.round(t * w);
        g.fill(Math.min(z, k), y - 1, Math.max(z, k), y + 2, color);
        g.fill(k - 2, y - 3, k + 2, y + 4, 0xFFFFFFFF);
    }

    private static void scrollbar(GuiGraphics g, int x, int y, int h, int scroll, int max) {
        g.fill(x, y, x + 2, y + h, 0x20FFFFFF);
        int thumb = Math.max(12, h * h / (h + max));
        int ty = y + (int) ((h - thumb) * (scroll / (float) max));
        g.fill(x, ty, x + 2, ty + thumb, 0x90FFFFFF);
    }

    private void label(GuiGraphics g, String text, int x, int y, int w, int color) {
        String t = this.font.plainSubstrByWidth(text, w);
        g.drawString(this.font, t, x + (w - this.font.width(t)) / 2, y, color, false);
    }

    /** Text at 3/4 size, centred on {@code cx}. */
    private void small(GuiGraphics g, String text, int cx, int y, int color) {
        g.pose().pushPose();
        g.pose().translate(cx, y, 0);
        g.pose().scale(0.75F, 0.75F, 1.0F);
        g.drawString(this.font, text, -this.font.width(text) / 2, 0, color, false);
        g.pose().popPose();
    }

    private void drawLookTile(GuiGraphics g, int x, int y, int w, int h, int c1, int c2, boolean stripes, boolean rainbow,
                              boolean hover) {
        if (rainbow) {
            for (int i = 0; i < w; i++)
                g.fill(x + i, y, x + i + 1, y + h, Mth.hsvToRgb(i / (float) w, 0.65F, 0.95F) | 0xFF000000);
        } else if (stripes) {
            g.fill(x, y, x + w, y + h, c2);
            for (int yy = y; yy < y + h; yy += 3)
                g.fill(x, yy, x + w, Math.min(yy + 1, y + h), c1);
        } else {
            for (int i = 0; i < w; i++)
                g.fill(x + i, y, x + i + 1, y + h, lerpColour(c1, c2, i / (float) Math.max(1, w - 1)));
        }
        outline(g, x, y, w, h, hover ? ACCENT : 0x40FFFFFF);
    }

    private void drawLayerIcon(GuiGraphics g, HologramLayer layer, int x, int y) {
        ItemStack icon = ItemStack.EMPTY;
        switch (layer.kind()) {
            case BLOCK -> {
                var block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(layer.id())).orElse(null);
                if (block != null)
                    icon = new ItemStack(block.asItem());
            }
            case ENTITY -> {
                var type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.tryParse(layer.id())).orElse(null);
                SpawnEggItem egg = type == null ? null : SpawnEggItem.byId(type);
                icon = egg != null ? new ItemStack(egg) : new ItemStack(Items.ARMOR_STAND);
            }
            case PARTICLE -> {
                g.drawString(this.font, "✧", x + 4, y + 4, GOLD, false);
                return;
            }
        }
        if (icon.isEmpty()) {
            g.drawString(this.font, "▣", x + 4, y + 4, MUTED, false);
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(0.875F, 0.875F, 1.0F);
        g.renderItem(icon, 0, 0);
        g.pose().popPose();
    }

    private static int lerpColour(int a, int b, float t) {
        int ar = a >> 16 & 0xFF, ag = a >> 8 & 0xFF, ab = a & 0xFF;
        int br = b >> 16 & 0xFF, bg = b >> 8 & 0xFF, bb = b & 0xFF;
        return 0xFF000000 | Math.round(ar + (br - ar) * t) << 16 | Math.round(ag + (bg - ag) * t) << 8
                | Math.round(ab + (bb - ab) * t);
    }

    /** A 128px hue/saturation disc at full value; brightness is applied with the shader colour when drawn. */
    private static void ensureWheelTexture() {
        if (wheelReady)
            return;
        NativeImage img = new NativeImage(128, 128, true);
        for (int py = 0; py < 128; py++) {
            for (int px = 0; px < 128; px++) {
                double dx = px + 0.5 - 64.0;
                double dy = 64.0 - (py + 0.5);
                double r = Math.sqrt(dx * dx + dy * dy) / 64.0;
                if (r > 1.0) {
                    img.setPixelRGBA(px, py, 0);
                    continue;
                }
                float h = (float) ((Math.atan2(dy, dx) / (Math.PI * 2) + 1.0) % 1.0);
                int rgb = Mth.hsvToRgb(h, (float) r, 1.0F);
                // Soft 1px edge so the disc doesn't look jagged at GUI scale 2.
                int a = r > 0.985 ? (int) (255 * (1.0 - r) / 0.015) : 255;
                int red = rgb >> 16 & 0xFF, green = rgb >> 8 & 0xFF, blue = rgb & 0xFF;
                img.setPixelRGBA(px, py, (a << 24) | (blue << 16) | (green << 8) | red); // ABGR
            }
        }
        Minecraft.getInstance().getTextureManager().register(WHEEL_TEX, new DynamicTexture(img));
        wheelReady = true;
    }

    // ---------------------------------------------------------------- dev-check hooks

    /** The editor behind this screen, for dev checks. */
    public HologramScreen editor() {
        return this.editor;
    }

    /**
     * Dev checks: press the control whose tooltip starts with {@code prefix} through the real mouse path, at the
     * centre of its hit box from the last frame. Returns false when nothing on screen matches.
     */
    public boolean devClick(String prefix, int button) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.tooltip != null && h.tooltip.startsWith(prefix)) {
                double cx = h.x + h.w / 2.0;
                double cy = h.y + h.h / 2.0;
                if (!h.contains(cx, cy))
                    return false; // scrolled out of view: a user could not click it either
                mouseClicked(cx, cy, button);
                mouseReleased(cx, cy, button);
                return true;
            }
        }
        return false;
    }

    /** Dev checks: drag the control under {@code prefix} to fraction {@code t} of its width (sliders). */
    public boolean devDrag(String prefix, double t) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit h = this.hits.get(i);
            if (h.tooltip != null && h.tooltip.startsWith(prefix) && h.drag != null) {
                double cy = h.y + h.h / 2.0;
                mouseClicked(h.x + 1, cy, 0);
                mouseDragged(h.x + t * h.w, cy, 0, 0, 0);
                mouseReleased(h.x + t * h.w, cy, 0);
                return true;
            }
        }
        return false;
    }

    /**
     * Dev checks: scroll the inspector so the control under {@code prefix} is in view (takes effect next frame).
     * Returns false when no such control exists.
     */
    public boolean devReveal(String prefix) {
        for (Hit h : this.hits) {
            if (h.tooltip != null && h.tooltip.startsWith(prefix)) {
                int mid = (h.clipTop == Integer.MIN_VALUE ? 0 : (h.clipTop + h.clipBottom) / 2);
                if (h.clipTop != Integer.MIN_VALUE && !(h.y >= h.clipTop && h.y + h.h <= h.clipBottom))
                    this.rightScroll = Math.max(0, this.rightScroll + (h.y + h.h / 2) - mid);
                return true;
            }
        }
        return false;
    }

    /** Dev checks: free width between the two panels. */
    public int devFreeWidth() {
        return this.rightX - (this.leftX + this.leftW);
    }

    /** Dev checks: collapse or open every inspector section. */
    public void devCollapseAll(boolean collapse) {
        collapsed.clear();
        if (collapse)
            collapsed.addAll(EnumSet.allOf(Section.class));
    }

    /** Dev checks: scroll the inspector to its end, so lower sections are on screen. */
    public void devScrollRight(int pixels) {
        this.rightScroll = Math.max(0, pixels);
    }
}
