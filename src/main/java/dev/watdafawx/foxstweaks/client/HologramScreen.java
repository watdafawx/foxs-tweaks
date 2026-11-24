package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramOpenPayload;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 3D-studio hologram editor: collapsible Content Picker on the left, unified Inspector on the right,
 * and an open 3D viewport in the middle with Move/Rotate/Scale gizmos and right-drag camera orbit.
 *
 * <p>Every numeric control supports drag-scrubbing (click-drag horizontally), mouse wheel, right-click
 * reset, and click-to-type exact numbers.
 */
public class HologramScreen extends Screen {
    // ---- Palette (flat dark studio, zero vanilla grey buttons) ----
    private static final int DIM = 0x1A000000;
    private static final int PANEL_BG = 0xE80D1117;
    private static final int PANEL_BORDER = 0x40FFFFFF;
    private static final int SURFACE = 0xFF161C24;
    private static final int SURFACE_HOT = 0xFF212B38;
    private static final int SURFACE_ACTIVE = 0x404CC9F0;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int DANGER_BG = 0x30FF4D5A;
    private static final int DANGER_HOT = 0x55FF4D5A;
    private static final int DANGER_TEXT = 0xFFFF7A85;
    private static final int AXIS_X = 0xFFFF5E6C;
    private static final int AXIS_Y = 0xFF5EE877;
    private static final int AXIS_Z = 0xFF5CA0FF;

    private static final int PANEL_H = 276;
    private static final int CELL = 18;
    private static final int GRID_ROWS = 5;
    private static final int LIST_ROW_H = 15;
    private static final int LIST_VISIBLE = 6; // 6 * 15 == 90 == GRID_ROWS * CELL
    private static final int PROP_ROWS = 5;
    private static final int PROP_PITCH = 15;
    private static final int MAX_EFFECTS = 4;

    public static final int TAB_TRANSFORM = 0;
    public static final int TAB_ANIMATE = 1;
    public static final int TAB_FX = 2;
    public static final int TAB_MORE = 3;
    private static final String[] TAB_LABELS = { "Main", "Animate", "FX", "More" };

    public static final int PICK_BLOCKS = 0;
    public static final int PICK_ENTITIES = 1;
    public static final int PICK_PARTICLES = 2;
    private static final String[] PICK_LABELS = { "Block", "Entity", "Particle" };

    private static final int[] TINT_COLORS = {
            0x66E0FF, 0xFF4D5A, 0x5CE67A, 0xFFD24D, 0xB86BFF, 0xFF6EC7, 0xFFFFFF, 0xFF8C3B, 0x4D88FF
    };

    private static List<Block> ALL_BLOCKS;
    private static List<EntityEntry> ALL_ENTITIES;
    private static List<String> ALL_PARTICLES;

    private record EntityEntry(String id, String displayName, ItemStack icon) {
    }

    private record TransformSnapshot(float ox, float oy, float oz, float yaw, float pitch, float roll, float scale) {
    }

    /** A clickable/draggable/scrollable numeric control (either a compact scrub box or a horizontal slider). */
    private record NumControl(
            int x, int y, int w, int h,
            String prefix, int prefixColor,
            boolean isSlider,
            double min, double max, double step, double defaultVal,
            DoubleSupplier get, DoubleConsumer set,
            String suffix, int decimals
    ) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }

        String formatVal(double v) {
            String fmt = decimals == 0 ? "%.0f" : (decimals == 1 ? "%.1f" : "%.2f");
            return String.format(Locale.ROOT, fmt, v) + suffix;
        }
    }

    /** A flat clickable pill or toggle button built per-layout so hit-testing and drawing stay in sync. */
    private record FlatBtn(int x, int y, int w, int h, String label, boolean active, boolean danger,
                           boolean enabled, Runnable onClick) {
        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    // ---- Spec state ----
    private final String id;
    private final BlockPos pos;
    private final int maxRenderDistance;
    @Nullable
    private final UUID owner;
    private final String ownerName;

    private float offsetX, offsetY, offsetZ;
    private String block;
    private Map<String, String> props;
    private float scale;
    private float yaw, pitch, roll;
    private float spin;
    private int spinAxis;
    private float hover;
    private float hoverPeriod;
    private boolean fullbright;
    private int renderDistance;
    private List<HologramEffect> effects;
    private List<HologramPart> parts;
    private CompoundTag data;
    private float appearRadius;
    private float appearSeconds;

    // ---- UI state ----
    private int tab = TAB_TRANSFORM;
    private int pickMode = PICK_BLOCKS;
    private boolean pickerCollapsed;
    private int scroll;
    private boolean draggingScrollbar;

    private String selectedEntityId = "";
    private String selectedParticleId = "minecraft:end_rod";
    private boolean primaryPartOverride;
    private int selectedPart = -1;

    private final HologramGizmo gizmo = new HologramGizmo();
    private final Deque<TransformSnapshot> undoStack = new ArrayDeque<>();
    private boolean orbiting;

    // Active drag-scrub or slider drag
    @Nullable
    private NumControl activeNum;
    private double numDragStartX;
    private double numDragStartVal;
    private boolean numDragged;

    // Inline exact number EditBox
    @Nullable
    private EditBox inlineBox;
    @Nullable
    private NumControl inlineControl;

    // Search EditBox in left panel
    private EditBox search;

    // Computed layout bounds
    private int leftX, leftW;
    private int rightX, rightW;
    private int top;
    private int gridCols = 8;

    private List<Block> filteredBlocks = List.of();
    private List<EntityEntry> filteredEntities = List.of();
    private List<String> filteredParticles = List.of();
    @Nullable
    private Block hoveredGridBlock;

    // Per-frame interactive controls for the inspector & toolbar
    private final List<NumControl> numControls = new ArrayList<>();
    private final List<FlatBtn> flatBtns = new ArrayList<>();

    public HologramScreen(HologramOpenPayload payload) {
        super(Component.translatable("screen.foxstweaks.hologram.title"));
        HologramSpec spec = payload.spec();
        this.id = spec.id();
        this.pos = spec.pos();
        this.offsetX = spec.offsetX();
        this.offsetY = spec.offsetY();
        this.offsetZ = spec.offsetZ();
        this.block = spec.block();
        this.props = new LinkedHashMap<>(spec.props());
        this.scale = spec.scale();
        this.yaw = spec.yaw();
        this.pitch = spec.pitch();
        this.roll = spec.roll();
        this.spin = spec.spin();
        this.spinAxis = spec.spinAxis();
        this.hover = spec.hover();
        this.hoverPeriod = spec.hoverPeriod();
        this.fullbright = spec.fullbright();
        this.renderDistance = spec.renderDistance();
        this.effects = new ArrayList<>(spec.effects());
        this.parts = new ArrayList<>(spec.parts());
        this.data = spec.data().copy();
        this.appearRadius = spec.appearRadius();
        this.appearSeconds = spec.appearSeconds();
        this.maxRenderDistance = Math.max(16, payload.maxRenderDistance());
        this.owner = spec.owner();
        this.ownerName = spec.ownerName();
    }

    public static void open(HologramOpenPayload payload) {
        if (payload.spec() == null)
            return;
        Minecraft.getInstance().setScreen(new HologramScreen(payload));
    }

    // ---------------------------------------------------------------- layout

    private int gridWidth() {
        return this.gridCols * CELL;
    }

    private int gridTop() {
        return this.top + 56;
    }

    private int gridHeight() {
        return GRID_ROWS * CELL;
    }

    private int scrollbarX() {
        return this.leftX + 8 + gridWidth() + 3;
    }

    @Override
    protected void init() {
        int gap = Math.max(96, this.width * 18 / 100);
        int remaining = Math.max(240, this.width - 16 - gap);
        this.rightW = Mth.clamp((int) (remaining * 0.54), 168, 224);
        int leftTarget = remaining - this.rightW;
        this.gridCols = Mth.clamp((leftTarget - 22) / CELL, 4, 8);
        this.leftW = this.gridCols * CELL + 22;

        this.leftX = 8;
        this.rightX = this.width - this.rightW - 8;
        this.top = Math.max(26, (this.height - PANEL_H) / 2);

        this.search = new EditBox(this.font, this.leftX + 8, this.top + 38, this.leftW - 16, 13,
                Component.literal("Search"));
        this.search.setBordered(false);
        this.search.setTextColor(TEXT);
        updateSearchHint();
        this.search.setResponder(text -> {
            this.scroll = 0;
            refreshFilter();
        });
        addRenderableWidget(this.search);
        this.search.visible = !this.pickerCollapsed;

        if (this.selectedEntityId.isEmpty() && !allEntities().isEmpty())
            this.selectedEntityId = allEntities().get(0).id();

        if ("minecraft:air".equals(this.block) && !this.parts.isEmpty()) {
            this.primaryPartOverride = true;
            HologramPart first = this.parts.get(0);
            if (first.kind() == HologramPart.Kind.ENTITY) {
                this.selectedEntityId = first.id();
                this.pickMode = PICK_ENTITIES;
            } else if (first.kind() == HologramPart.Kind.PARTICLE) {
                this.selectedParticleId = first.id();
                this.pickMode = PICK_PARTICLES;
            }
        }

        refreshFilter();
        rebuildInspector();
        refresh();
    }

    private void setPickMode(int mode) {
        this.pickMode = Mth.clamp(mode, PICK_BLOCKS, PICK_PARTICLES);
        this.scroll = 0;
        this.draggingScrollbar = false;
        if (this.search != null) {
            this.search.setValue("");
            updateSearchHint();
        }
        refreshFilter();
        rebuildInspector();
    }

    private void setPickerCollapsed(boolean collapsed) {
        this.pickerCollapsed = collapsed;
        if (this.search != null) {
            this.search.visible = !collapsed;
            if (collapsed && this.search.isFocused())
                setFocused(null);
        }
        rebuildInspector();
    }

    private void updateSearchHint() {
        if (this.search == null)
            return;
        String hint = switch (this.pickMode) {
            case PICK_ENTITIES -> "search entities...";
            case PICK_PARTICLES -> "search particles...";
            default -> "search blocks...";
        };
        this.search.setHint(Component.literal(hint).withStyle(style -> style.withColor(MUTED)));
    }

    /** Rebuilds all interactive flat buttons and numeric controls for the current tab & toolbar. */
    private void rebuildInspector() {
        this.numControls.clear();
        this.flatBtns.clear();

        // ---- Top-center gizmo toolbar ----
        HologramGizmo.Mode[] modes = HologramGizmo.Mode.values();
        String[] modeLabels = { "Move", "Rotate", "Scale", "Off" };
        int btnW = 38;
        int totalW = modes.length * (btnW + 2) + 4 + 34 + 2 + 34;
        int gx = (this.width - totalW) / 2;
        for (int i = 0; i < modes.length; i++) {
            HologramGizmo.Mode m = modes[i];
            this.flatBtns.add(new FlatBtn(gx + i * (btnW + 2), 6, btnW, 15, modeLabels[i],
                    this.gizmo.mode() == m, false, true, () -> {
                this.gizmo.setMode(m);
                rebuildInspector();
            }));
        }
        int utilX = gx + modes.length * (btnW + 2) + 4;
        this.flatBtns.add(new FlatBtn(utilX, 6, 34, 15, "Undo", false, false, !this.undoStack.isEmpty(),
                this::undoTransform));
        this.flatBtns.add(new FlatBtn(utilX + 36, 6, 34, 15, "Zero", false, false,
                this.offsetX != 0 || this.offsetY != 0 || this.offsetZ != 0, () -> {
            pushUndo();
            this.offsetX = 0.0F;
            this.offsetY = 0.0F;
            this.offsetZ = 0.0F;
            rebuildInspector();
            refresh();
        }));

        // ---- Left panel header / collapse button ----
        if (this.pickerCollapsed) {
            this.flatBtns.add(new FlatBtn(this.leftX, this.top, 64, 18, "Content >", false, false, true,
                    () -> setPickerCollapsed(false)));
        } else {
            this.flatBtns.add(new FlatBtn(this.leftX + this.leftW - 22, this.top + 4, 16, 13, "<", false, false, true,
                    () -> setPickerCollapsed(true)));
            int pTabW = (this.leftW - 16 - 4) / 3;
            for (int i = 0; i < 3; i++) {
                int m = i;
                this.flatBtns.add(new FlatBtn(this.leftX + 8 + i * (pTabW + 2), this.top + 20, pTabW, 14,
                        PICK_LABELS[i], this.pickMode == m, false, true, () -> setPickMode(m)));
            }
            // Stack as extra layer button at bottom of left panel
            boolean canStack = this.parts.size() < HologramPart.MAX_PARTS;
            String stackLabel = canStack
                    ? ("+ Stack " + PICK_LABELS[this.pickMode] + " Layer (" + this.parts.size() + "/" + HologramPart.MAX_PARTS + ")")
                    : ("Max " + HologramPart.MAX_PARTS + " Layers");
            this.flatBtns.add(new FlatBtn(this.leftX + 8, this.top + PANEL_H - 22, this.leftW - 16, 15,
                    stackLabel, false, false, canStack, this::stackCurrentPick));
        }

        // ---- Right Inspector tabs ----
        int rx = this.rightX + 8;
        int rw = this.rightW - 16;
        int tabW = (rw - (TAB_LABELS.length - 1) * 2) / TAB_LABELS.length;
        for (int i = 0; i < TAB_LABELS.length; i++) {
            int idx = i;
            this.flatBtns.add(new FlatBtn(rx + i * (tabW + 2), this.top + 20, tabW, 15, TAB_LABELS[i],
                    this.tab == idx, false, true, () -> {
                commitInlineBox();
                this.tab = idx;
                rebuildInspector();
            }));
        }

        // ---- Inspector tab body ----
        int y = this.top + 44;
        if (this.tab == TAB_TRANSFORM) {
            // Position X / Y / Z
            y += 11; // section label drawn in render
            int colW = (rw - 6) / 3;
            this.numControls.add(new NumControl(rx, y, colW, 18, "X", AXIS_X, false,
                    -16.0, 16.0, 0.25, 0.0, () -> this.offsetX, v -> this.offsetX = (float) v, "", 2));
            this.numControls.add(new NumControl(rx + colW + 3, y, colW, 18, "Y", AXIS_Y, false,
                    -16.0, 16.0, 0.25, 0.0, () -> this.offsetY, v -> this.offsetY = (float) v, "", 2));
            this.numControls.add(new NumControl(rx + (colW + 3) * 2, y, rw - (colW + 3) * 2, 18, "Z", AXIS_Z, false,
                    -16.0, 16.0, 0.25, 0.0, () -> this.offsetZ, v -> this.offsetZ = (float) v, "", 2));
            y += 26;

            // Rotation Pitch / Yaw / Roll
            y += 11;
            this.numControls.add(new NumControl(rx, y, colW, 18, "P", AXIS_X, false,
                    -180.0, 180.0, 15.0, 0.0, () -> this.pitch, v -> this.pitch = wrap(v), "°", 0));
            this.numControls.add(new NumControl(rx + colW + 3, y, colW, 18, "Y", AXIS_Y, false,
                    -180.0, 180.0, 15.0, 0.0, () -> this.yaw, v -> this.yaw = wrap(v), "°", 0));
            this.numControls.add(new NumControl(rx + (colW + 3) * 2, y, rw - (colW + 3) * 2, 18, "R", AXIS_Z, false,
                    -180.0, 180.0, 15.0, 0.0, () -> this.roll, v -> this.roll = wrap(v), "°", 0));
            y += 26;

            // Scale slider
            y += 11;
            this.numControls.add(new NumControl(rx, y, rw, 18, "Scale", TEXT, true,
                    0.25, 8.0, 0.25, 1.0, () -> this.scale, v -> this.scale = (float) clamp(v, 0.25, 8.0), "x", 2));
            y += 25;

            // Range slider
            this.numControls.add(new NumControl(rx, y, rw, 18, "Range", TEXT, true,
                    8.0, this.maxRenderDistance, 8.0, 48.0,
                    () -> this.renderDistance,
                    v -> this.renderDistance = (int) clamp(Math.round(v), 8, this.maxRenderDistance), "m", 0));
            y += 24;

            // Glow + Preview in front inline toggles
            int halfW = (rw - 4) / 2;
            this.flatBtns.add(new FlatBtn(rx, y, halfW, 18,
                    "Glow: " + (this.fullbright ? "On" : "Off"), this.fullbright, false, true, () -> {
                this.fullbright = !this.fullbright;
                rebuildInspector();
                refresh();
            }));
            this.flatBtns.add(new FlatBtn(rx + halfW + 4, y, rw - halfW - 4, 18,
                    "Preview: " + (HologramClient.previewAnchored() ? "On" : "Off"),
                    HologramClient.previewAnchored(), false, true, () -> {
                HologramClient.setPreviewAnchored(!HologramClient.previewAnchored());
                rebuildInspector();
                refresh();
            }));
        } else if (this.tab == TAB_ANIMATE) {
            this.numControls.add(new NumControl(rx, y, rw, 18, "Spin", TEXT, true,
                    -360.0, 360.0, 15.0, 0.0, () -> this.spin, v -> this.spin = (float) clamp(v, -360.0, 360.0), "°/s", 0));
            y += 23;

            // Spin axis segmented bar
            int axisW = (rw - 4) / 3;
            String[] axes = { "Axis: X", "Axis: Y", "Axis: Z" };
            int[] axisIds = { HologramSpec.AXIS_X, HologramSpec.AXIS_Y, HologramSpec.AXIS_Z };
            for (int i = 0; i < 3; i++) {
                int ax = axisIds[i];
                this.flatBtns.add(new FlatBtn(rx + i * (axisW + 2), y, axisW, 16, axes[i],
                        this.spinAxis == ax, false, true, () -> {
                    this.spinAxis = ax;
                    rebuildInspector();
                    refresh();
                }));
            }
            y += 24;

            this.numControls.add(new NumControl(rx, y, rw, 18, "Hover", TEXT, true,
                    0.0, 4.0, 0.25, 0.0, () -> this.hover, v -> this.hover = (float) clamp(v, 0.0, 4.0), "m", 2));
            y += 23;

            this.numControls.add(new NumControl(rx, y, rw, 18, "Period", TEXT, true,
                    0.5, 30.0, 0.5, 4.0, () -> this.hoverPeriod,
                    v -> this.hoverPeriod = (float) clamp(v, 0.5, 120.0), "s", 1));
            y += 28;

            // Walk-in appear
            y += 11;
            this.numControls.add(new NumControl(rx, y, rw, 18, "Appear", TEXT, true,
                    0.0, 64.0, 2.0, 0.0, () -> this.appearRadius,
                    v -> this.appearRadius = (float) clamp(v, 0.0, 64.0), "m", 0));
            y += 23;

            this.numControls.add(new NumControl(rx, y, rw, 18, "Ease", TEXT, true,
                    0.0, 5.0, 0.2, 0.6, () -> this.appearSeconds,
                    v -> this.appearSeconds = (float) clamp(v, 0.0, 10.0), "s", 1));
        } else if (this.tab == TAB_FX) {
            // 5 direct toggle pills for each effect type
            HologramEffect.Type[] allTypes = HologramEffect.Type.values();
            int pillW = (rw - 8) / 5;
            String[] shortNames = { "Tint", "Flkr", "Trmr", "Wbl", "Jump" };
            for (int i = 0; i < allTypes.length; i++) {
                HologramEffect.Type type = allTypes[i];
                boolean has = indexOfEffect(type) >= 0;
                this.flatBtns.add(new FlatBtn(rx + i * (pillW + 2), y, pillW, 16, shortNames[i],
                        has, false, has || this.effects.size() < MAX_EFFECTS, () -> toggleEffect(type)));
            }
            y += 22;

            // Cards for each active effect
            for (int i = 0; i < this.effects.size(); i++) {
                int idx = i;
                HologramEffect fx = this.effects.get(i);
                int cardH = fx.type() == HologramEffect.Type.TINT ? 42 : 24;
                int strW = fx.type() == HologramEffect.Type.TINT ? rw - 20 : (rw - 24) * 60 / 100;
                this.numControls.add(new NumControl(rx, y, strW, 18, fx.label(), TEXT, true,
                        0.0, 2.0, 0.1, 0.5,
                        () -> idx < this.effects.size() ? this.effects.get(idx).strength() : 0.0,
                        v -> setEffectStrength(idx, (float) v), "", 2));
                if (fx.type() != HologramEffect.Type.TINT) {
                    int spdX = rx + strW + 3;
                    int spdW = rw - strW - 23;
                    this.numControls.add(new NumControl(spdX, y, spdW, 18, "Hz", MUTED, false,
                            0.1, 20.0, 0.5, 1.0,
                            () -> idx < this.effects.size() ? this.effects.get(idx).speed() : 1.0,
                            v -> setEffectSpeed(idx, (float) v), "", 1));
                }
                this.flatBtns.add(new FlatBtn(rx + rw - 17, y, 17, 18, "x", false, true, true,
                        () -> removeEffect(idx)));
                y += cardH + 4;
            }
        } else {
            // More tab: Clipboard, BE Data, and Stacked Layers (Parts)
            int thirdW = (rw - 4) / 3;
            this.flatBtns.add(new FlatBtn(rx, y, thirdW, 18, "Copy", false, false, true, () -> {
                HologramClipboard.copy(buildSpec());
                rebuildInspector();
            }));
            this.flatBtns.add(new FlatBtn(rx + thirdW + 2, y, thirdW, 18, "Paste", false, false,
                    HologramClipboard.has(), this::applyClipboard));
            this.flatBtns.add(new FlatBtn(rx + (thirdW + 2) * 2, y, rw - (thirdW + 2) * 2, 18, "Paste New",
                    false, false, HologramClipboard.has(), this::duplicateBesideMe));
            y += 24;

            // BE Data controls
            int halfW = (rw - 4) / 2;
            String dataLabel = this.data.isEmpty() ? "Capture BE" : ("Re-cap (" + this.data.sizeInBytes() + "B)");
            this.flatBtns.add(new FlatBtn(rx, y, halfW, 18, dataLabel, false, false, true,
                    () -> HologramNetwork.sendRecapture(this.id)));
            this.flatBtns.add(new FlatBtn(rx + halfW + 4, y, rw - halfW - 4, 18, "Clear BE",
                    false, true, !this.data.isEmpty(), () -> {
                this.data = new CompoundTag();
                rebuildInspector();
                refresh();
            }));
            y += 28;

            // Extra Layers (Parts) list
            y += 11; // section label "Layers (N/4)"
            for (int i = 0; i < this.parts.size(); i++) {
                int idx = i;
                HologramPart p = this.parts.get(i);
                this.flatBtns.add(new FlatBtn(rx, y, rw - 20, 17,
                        p.label() + (idx == 0 && this.primaryPartOverride ? " (main)" : ""),
                        idx == this.selectedPart, false, true, () -> {
                    this.selectedPart = idx;
                    rebuildInspector();
                }));
                this.flatBtns.add(new FlatBtn(rx + rw - 18, y, 18, 17, "x", false, true, true,
                        () -> removePart(idx)));
                y += 19;
            }

            // Selected layer fine controls (X / Y / Z + Size / Yaw)
            if (this.selectedPart >= 0 && this.selectedPart < this.parts.size()) {
                int pIdx = this.selectedPart;
                int sy = this.top + PANEL_H - 72;
                int c3 = (rw - 6) / 3;
                this.numControls.add(new NumControl(rx, sy, c3, 16, "X", AXIS_X, false,
                        -16.0, 16.0, 0.25, 0.0,
                        () -> pIdx < this.parts.size() ? this.parts.get(pIdx).offsetX() : 0.0,
                        v -> setPartField(pIdx, 0, (float) v), "", 2));
                this.numControls.add(new NumControl(rx + c3 + 3, sy, c3, 16, "Y", AXIS_Y, false,
                        -16.0, 16.0, 0.25, 0.0,
                        () -> pIdx < this.parts.size() ? this.parts.get(pIdx).offsetY() : 0.0,
                        v -> setPartField(pIdx, 1, (float) v), "", 2));
                this.numControls.add(new NumControl(rx + (c3 + 3) * 2, sy, rw - (c3 + 3) * 2, 16, "Z", AXIS_Z, false,
                        -16.0, 16.0, 0.25, 0.0,
                        () -> pIdx < this.parts.size() ? this.parts.get(pIdx).offsetZ() : 0.0,
                        v -> setPartField(pIdx, 2, (float) v), "", 2));

                int c2 = (rw - 4) / 2;
                this.numControls.add(new NumControl(rx, sy + 19, c2, 16, "Size", TEXT, false,
                        0.1, 8.0, 0.1, 1.0,
                        () -> pIdx < this.parts.size() ? this.parts.get(pIdx).scale() : 1.0,
                        v -> setPartField(pIdx, 3, (float) v), "x", 2));
                this.numControls.add(new NumControl(rx + c2 + 4, sy + 19, rw - c2 - 4, 16, "Yaw", TEXT, false,
                        -180.0, 180.0, 15.0, 0.0,
                        () -> pIdx < this.parts.size() ? this.parts.get(pIdx).yaw() : 0.0,
                        v -> setPartField(pIdx, 4, wrap(v)), "°", 0));
            }
        }

        // ---- Right Inspector footer: Done / Delete ----
        int footY = this.top + PANEL_H - 26;
        int halfW = (rw - 4) / 2;
        this.flatBtns.add(new FlatBtn(rx, footY, halfW, 18, "Done", true, false, true, this::saveAndClose));
        this.flatBtns.add(new FlatBtn(rx + halfW + 4, footY, rw - halfW - 4, 18, "Delete", false, true, true,
                this::deleteAndClose));
    }

    // ---------------------------------------------------------------- effects & parts logic

    private int indexOfEffect(HologramEffect.Type type) {
        for (int i = 0; i < this.effects.size(); i++) {
            if (this.effects.get(i).type() == type)
                return i;
        }
        return -1;
    }

    private void toggleEffect(HologramEffect.Type type) {
        int existing = indexOfEffect(type);
        if (existing >= 0) {
            removeEffect(existing);
            return;
        }
        if (this.effects.size() >= MAX_EFFECTS)
            return;
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.add(HologramEffect.of(type));
        this.effects = next;
        rebuildInspector();
        refresh();
    }

    private void removeEffect(int index) {
        if (index < 0 || index >= this.effects.size())
            return;
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.remove(index);
        this.effects = next;
        rebuildInspector();
        refresh();
    }

    private void setEffectStrength(int index, float strength) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), strength, old.speed(), old.color()));
        this.effects = next;
        refresh();
    }

    private void setEffectSpeed(int index, float speed) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), old.strength(), speed, old.color()));
        this.effects = next;
        refresh();
    }

    private void setEffectColor(int index, int color) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), old.strength(), old.speed(), color));
        this.effects = next;
        refresh();
    }

    private void setPrimaryPart(HologramPart part) {
        this.block = "minecraft:air";
        this.props = new LinkedHashMap<>();
        this.data = new CompoundTag();
        List<HologramPart> next = new ArrayList<>(this.parts);
        if (this.primaryPartOverride && !next.isEmpty())
            next.set(0, part);
        else
            next.add(0, part);
        if (next.size() > HologramPart.MAX_PARTS)
            next = new ArrayList<>(next.subList(0, HologramPart.MAX_PARTS));
        this.parts = next;
        this.primaryPartOverride = true;
        this.selectedPart = 0;
        rebuildInspector();
        refresh();
    }

    private void stackCurrentPick() {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;
        float topY = "minecraft:air".equals(this.block) ? 0.0F : this.scale;
        HologramPart part = switch (this.pickMode) {
            case PICK_ENTITIES -> HologramPart.entity(this.selectedEntityId, topY);
            case PICK_PARTICLES -> HologramPart.particle(this.selectedParticleId, topY + 0.5F);
            default -> HologramPart.block(this.block, this.props, topY);
        };
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        this.selectedPart = next.size() - 1;
        this.tab = TAB_MORE;
        rebuildInspector();
        refresh();
    }

    private void setPartField(int index, int field, float value) {
        if (index < 0 || index >= this.parts.size())
            return;
        HologramPart old = this.parts.get(index);
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.set(index, new HologramPart(old.kind(), old.id(), old.props(),
                field == 0 ? value : old.offsetX(),
                field == 1 ? value : old.offsetY(),
                field == 2 ? value : old.offsetZ(),
                field == 3 ? value : old.scale(),
                field == 4 ? value : old.yaw()));
        this.parts = next;
        refresh();
    }

    private void removePart(int index) {
        if (index < 0 || index >= this.parts.size())
            return;
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.remove(index);
        this.parts = next;
        if (index == 0)
            this.primaryPartOverride = false;
        if (this.selectedPart >= next.size())
            this.selectedPart = next.size() - 1;
        rebuildInspector();
        refresh();
    }

    // ---------------------------------------------------------------- inline number input

    private void openInlineBox(NumControl ctrl) {
        commitInlineBox();
        this.inlineControl = ctrl;
        this.inlineBox = new EditBox(this.font, ctrl.x + 2, ctrl.y + 4, ctrl.w - 4, ctrl.h - 6,
                Component.literal(ctrl.prefix));
        this.inlineBox.setBordered(false);
        this.inlineBox.setTextColor(ACCENT);
        String fmt = ctrl.decimals == 0 ? "%.0f" : (ctrl.decimals == 1 ? "%.1f" : "%.2f");
        this.inlineBox.setValue(String.format(Locale.ROOT, fmt, ctrl.get.getAsDouble()));
        this.inlineBox.moveCursorToEnd(false);
        this.inlineBox.setHighlightPos(0);
        addRenderableWidget(this.inlineBox);
        setFocused(this.inlineBox);
    }

    private void commitInlineBox() {
        if (this.inlineBox == null || this.inlineControl == null)
            return;
        try {
            double parsed = Double.parseDouble(this.inlineBox.getValue().trim());
            if (!Double.isNaN(parsed) && !Double.isInfinite(parsed)) {
                pushUndo();
                this.inlineControl.set.accept(clamp(parsed, this.inlineControl.min, this.inlineControl.max));
                refresh();
            }
        } catch (NumberFormatException ignored) {
        }
        cancelInlineBox();
    }

    private void cancelInlineBox() {
        if (this.inlineBox != null) {
            removeWidget(this.inlineBox);
            this.inlineBox = null;
        }
        this.inlineControl = null;
    }

    // ---------------------------------------------------------------- registries & filtering

    private static List<Block> allBlocks() {
        if (ALL_BLOCKS == null) {
            ALL_BLOCKS = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.asItem() != Items.AIR)
                    .filter(block -> block.defaultBlockState().getRenderShape() == RenderShape.MODEL
                            || block.defaultBlockState().getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED
                            || block instanceof EntityBlock)
                    .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                    .toList();
        }
        return ALL_BLOCKS;
    }

    private static List<EntityEntry> allEntities() {
        if (ALL_ENTITIES == null) {
            List<EntityEntry> list = new ArrayList<>();
            for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                ResourceLocation key = entry.getKey().location();
                EntityType<?> type = entry.getValue();
                Item egg = SpawnEggItem.byId(type);
                list.add(new EntityEntry(key.toString(), type.getDescription().getString(),
                        egg != null ? new ItemStack(egg) : ItemStack.EMPTY));
            }
            list.sort(Comparator.comparing(EntityEntry::id));
            ALL_ENTITIES = List.copyOf(list);
        }
        return ALL_ENTITIES;
    }

    private static List<String> allParticles() {
        if (ALL_PARTICLES == null) {
            ALL_PARTICLES = BuiltInRegistries.PARTICLE_TYPE.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof SimpleParticleType)
                    .map(entry -> entry.getKey().location().toString())
                    .sorted().toList();
        }
        return ALL_PARTICLES;
    }

    private void refreshFilter() {
        String query = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        if (this.pickMode == PICK_BLOCKS) {
            List<Block> blocks = allBlocks();
            if (query.isEmpty()) {
                this.filteredBlocks = blocks;
            } else {
                List<Block> matches = new ArrayList<>();
                for (Block b : blocks) {
                    if (BuiltInRegistries.BLOCK.getKey(b).toString().contains(query)
                            || b.getName().getString().toLowerCase(Locale.ROOT).contains(query))
                        matches.add(b);
                }
                this.filteredBlocks = matches;
            }
        } else if (this.pickMode == PICK_ENTITIES) {
            List<EntityEntry> entities = allEntities();
            if (query.isEmpty()) {
                this.filteredEntities = entities;
            } else {
                List<EntityEntry> matches = new ArrayList<>();
                for (EntityEntry e : entities) {
                    if (e.id().contains(query) || e.displayName().toLowerCase(Locale.ROOT).contains(query))
                        matches.add(e);
                }
                this.filteredEntities = matches;
            }
        } else {
            List<String> particles = allParticles();
            if (query.isEmpty()) {
                this.filteredParticles = particles;
            } else {
                List<String> matches = new ArrayList<>();
                for (String p : particles) {
                    if (p.contains(query))
                        matches.add(p);
                }
                this.filteredParticles = matches;
            }
        }
        this.scroll = Mth.clamp(this.scroll, 0, maxLeftScroll());
    }

    private int maxLeftScroll() {
        if (this.pickMode == PICK_BLOCKS)
            return Math.max(0, (this.filteredBlocks.size() + this.gridCols - 1) / this.gridCols - GRID_ROWS);
        if (this.pickMode == PICK_ENTITIES)
            return Math.max(0, this.filteredEntities.size() - LIST_VISIBLE);
        return Math.max(0, this.filteredParticles.size() - LIST_VISIBLE);
    }

    private int leftTotalRows() {
        if (this.pickMode == PICK_BLOCKS)
            return Math.max(1, (this.filteredBlocks.size() + this.gridCols - 1) / this.gridCols);
        if (this.pickMode == PICK_ENTITIES)
            return Math.max(1, this.filteredEntities.size());
        return Math.max(1, this.filteredParticles.size());
    }

    private int leftVisibleRows() {
        return this.pickMode == PICK_BLOCKS ? GRID_ROWS : LIST_VISIBLE;
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        graphics.fill(0, 0, this.width, this.height, DIM);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        if (!HologramClient.previewAnchored())
            this.gizmo.render(graphics, this.font, mouseX, mouseY, this.width, this.height,
                    hologramWorldCenter(), this.scale);

        this.hoveredGridBlock = null;
        if (!this.pickerCollapsed)
            renderLeftPanel(graphics, mouseX, mouseY);
        renderRightPanel(graphics, mouseX, mouseY);

        // Render all flat buttons (top toolbar, panel tabs, toggles, footer)
        for (FlatBtn btn : this.flatBtns)
            drawFlatBtn(graphics, btn, mouseX, mouseY);

        // Render all sliders & scrub boxes
        for (NumControl ctrl : this.numControls)
            drawNumControl(graphics, ctrl, mouseX, mouseY);

        // Subtle viewport hint
        String hint = "Right-drag: look  |  Drag numbers or click to type  |  G/R/S gizmo";
        int hintW = this.font.width(hint);
        int hintX = (this.width - hintW) / 2;
        int hintY = this.height - 13;
        graphics.fill(hintX - 6, hintY - 2, hintX + hintW + 6, hintY + 10, 0xA00D1117);
        graphics.drawString(this.font, hint, hintX, hintY, MUTED, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.hoveredGridBlock != null) {
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(this.hoveredGridBlock);
            graphics.renderComponentTooltip(this.font, List.of(
                    this.hoveredGridBlock.getName(),
                    Component.literal(key.toString()).withStyle(s -> s.withColor(MUTED))),
                    mouseX, mouseY);
        }
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h, String title) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.renderOutline(x, y, w, h, PANEL_BORDER);
        graphics.drawString(this.font, title, x + 8, y + 6, ACCENT, false);
    }

    private void drawFlatBtn(GuiGraphics graphics, FlatBtn btn, int mouseX, int mouseY) {
        boolean hot = btn.enabled && btn.hit(mouseX, mouseY);
        int bg;
        int border;
        int txt;
        if (!btn.enabled) {
            bg = 0x55161C24;
            border = 0x22FFFFFF;
            txt = 0xFF556070;
        } else if (btn.danger) {
            bg = hot ? DANGER_HOT : DANGER_BG;
            border = hot ? 0xAAFF5E6C : 0x44FF5E6C;
            txt = DANGER_TEXT;
        } else if (btn.active) {
            bg = hot ? 0x604CC9F0 : SURFACE_ACTIVE;
            border = ACCENT;
            txt = TEXT;
        } else {
            bg = hot ? SURFACE_HOT : SURFACE;
            border = hot ? 0x55FFFFFF : 0x28FFFFFF;
            txt = hot ? TEXT : MUTED;
        }
        graphics.fill(btn.x, btn.y, btn.x + btn.w, btn.y + btn.h, bg);
        graphics.renderOutline(btn.x, btn.y, btn.w, btn.h, border);
        String label = this.font.plainSubstrByWidth(btn.label, btn.w - 4);
        graphics.drawString(this.font, label,
                btn.x + (btn.w - this.font.width(label)) / 2,
                btn.y + (btn.h - 8) / 2, txt, false);
    }

    private void drawNumControl(GuiGraphics graphics, NumControl ctrl, int mouseX, int mouseY) {
        boolean isEditing = this.inlineControl == ctrl;
        boolean hot = ctrl.hit(mouseX, mouseY) || this.activeNum == ctrl;
        graphics.fill(ctrl.x, ctrl.y, ctrl.x + ctrl.w, ctrl.y + ctrl.h, hot ? SURFACE_HOT : SURFACE);

        double val = ctrl.get.getAsDouble();
        if (ctrl.isSlider && !isEditing) {
            double frac = Mth.clamp((val - ctrl.min) / Math.max(1.0E-5, ctrl.max - ctrl.min), 0.0, 1.0);
            int fillW = (int) Math.round(frac * (ctrl.w - 2));
            if (fillW > 0)
                graphics.fill(ctrl.x + 1, ctrl.y + 1, ctrl.x + 1 + fillW, ctrl.y + ctrl.h - 1, 0x384CC9F0);
        }

        graphics.renderOutline(ctrl.x, ctrl.y, ctrl.w, ctrl.h,
                isEditing ? ACCENT : (hot ? 0x664CC9F0 : 0x28FFFFFF));

        if (!isEditing) {
            int ty = ctrl.y + (ctrl.h - 8) / 2;
            graphics.drawString(this.font, ctrl.prefix, ctrl.x + 4, ty, ctrl.prefixColor, false);
            String valStr = ctrl.formatVal(val);
            graphics.drawString(this.font, valStr, ctrl.x + ctrl.w - 4 - this.font.width(valStr), ty, ACCENT, false);
        }
    }

    private void renderLeftPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        drawPanel(graphics, this.leftX, this.top, this.leftW, PANEL_H, "Content");

        // Search box background
        graphics.fill(this.leftX + 8, this.top + 36, this.leftX + this.leftW - 8, this.top + 51, SURFACE);
        graphics.renderOutline(this.leftX + 8, this.top + 36, this.leftW - 16, 15,
                this.search != null && this.search.isFocused() ? ACCENT : 0x28FFFFFF);

        int gridX = this.leftX + 8;
        int gridY = gridTop();
        int gridW = gridWidth();
        int gridH = gridHeight();
        int maxScroll = maxLeftScroll();
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        if (this.pickMode == PICK_BLOCKS) {
            int first = this.scroll * this.gridCols;
            for (int index = 0; index < this.gridCols * GRID_ROWS; index++) {
                int entry = first + index;
                if (entry >= this.filteredBlocks.size())
                    break;
                Block entryBlock = this.filteredBlocks.get(entry);
                int x = gridX + (index % this.gridCols) * CELL;
                int y = gridY + (index / this.gridCols) * CELL;

                boolean hot = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
                boolean selected = BuiltInRegistries.BLOCK.getKey(entryBlock).toString().equals(this.block);
                if (hot)
                    this.hoveredGridBlock = entryBlock;
                if (selected)
                    graphics.fill(x, y, x + CELL, y + CELL, SURFACE_ACTIVE);
                else if (hot)
                    graphics.fill(x, y, x + CELL, y + CELL, SURFACE_HOT);

                graphics.renderItem(new ItemStack(entryBlock.asItem()), x + 1, y + 1);
            }
        } else if (this.pickMode == PICK_ENTITIES) {
            for (int row = 0; row < LIST_VISIBLE; row++) {
                int entryIdx = this.scroll + row;
                if (entryIdx >= this.filteredEntities.size())
                    break;
                EntityEntry e = this.filteredEntities.get(entryIdx);
                int y = gridY + row * LIST_ROW_H;
                boolean hot = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= y && mouseY < y + LIST_ROW_H - 1;
                boolean selected = this.primaryPartOverride && e.id().equals(this.selectedEntityId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? SURFACE_ACTIVE : (hot ? SURFACE_HOT : SURFACE));
                int textX = gridX + 3;
                if (!e.icon().isEmpty()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(gridX + 1, y + 1, 0);
                    graphics.pose().scale(0.75F, 0.75F, 1.0F);
                    graphics.renderItem(e.icon(), 0, 0);
                    graphics.pose().popPose();
                    textX = gridX + 15;
                }
                String label = this.font.plainSubstrByWidth(e.displayName(), gridW - (textX - gridX) - 2);
                graphics.drawString(this.font, label, textX, y + 3, selected ? ACCENT : TEXT, false);
            }
        } else {
            for (int row = 0; row < LIST_VISIBLE; row++) {
                int entryIdx = this.scroll + row;
                if (entryIdx >= this.filteredParticles.size())
                    break;
                String p = this.filteredParticles.get(entryIdx);
                int y = gridY + row * LIST_ROW_H;
                boolean hot = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= y && mouseY < y + LIST_ROW_H - 1;
                boolean selected = this.primaryPartOverride && p.equals(this.selectedParticleId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? SURFACE_ACTIVE : (hot ? SURFACE_HOT : SURFACE));
                String label = this.font.plainSubstrByWidth(shortId(p), gridW - 6);
                graphics.drawString(this.font, label, gridX + 3, y + 3, selected ? ACCENT : TEXT, false);
            }
        }

        // Draggable scrollbar
        if (maxScroll > 0) {
            int barX = scrollbarX();
            int barHeight = Math.max(14, gridH * leftVisibleRows() / leftTotalRows());
            int barY = gridY + (gridH - barHeight) * this.scroll / maxScroll;
            boolean barHot = this.draggingScrollbar
                    || (mouseX >= barX - 2 && mouseX <= barX + 6 && mouseY >= gridY && mouseY < gridY + gridH);
            graphics.fill(barX, gridY, barX + 4, gridY + gridH, 0x28FFFFFF);
            graphics.fill(barX, barY, barX + 4, barY + barHeight, barHot ? ACCENT : 0xAA4CC9F0);
        }

        int infoY = gridY + gridH + 6;
        if (this.pickMode == PICK_BLOCKS) {
            Block currentBlock = HologramStates.blockOf(this.block);
            String name = currentBlock == null ? "none" : currentBlock.getName().getString();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, this.leftW - 16),
                    this.leftX + 8, infoY, TEXT, false);

            BlockState state = currentState();
            if (state != null) {
                List<Property<?>> properties = HologramStates.properties(state);
                for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                    Property<?> property = properties.get(i);
                    int y = infoY + 12 + i * PROP_PITCH;
                    boolean hot = mouseX >= this.leftX + 8 && mouseX < this.leftX + this.leftW - 8
                            && mouseY >= y && mouseY < y + PROP_PITCH - 2;
                    graphics.fill(this.leftX + 8, y, this.leftX + this.leftW - 8, y + PROP_PITCH - 2,
                            hot ? SURFACE_HOT : SURFACE);
                    graphics.drawString(this.font, property.getName(), this.leftX + 12, y + 3, MUTED, false);
                    String value = HologramStates.valueOf(state, property);
                    graphics.drawString(this.font, value,
                            this.leftX + this.leftW - 12 - this.font.width(value), y + 3, ACCENT, false);
                }
            }
        } else {
            String picked = this.pickMode == PICK_ENTITIES ? this.selectedEntityId : this.selectedParticleId;
            graphics.drawString(this.font, this.font.plainSubstrByWidth(picked, this.leftW - 16),
                    this.leftX + 8, infoY, ACCENT, false);
            graphics.drawString(this.font, "Click item to set hologram",
                    this.leftX + 8, infoY + 14, MUTED, false);
            graphics.drawString(this.font, "Or stack below as extra layer",
                    this.leftX + 8, infoY + 26, MUTED, false);
        }
    }

    private void renderRightPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        drawPanel(graphics, this.rightX, this.top, this.rightW, PANEL_H, "Inspector");
        int rx = this.rightX + 8;
        int rw = this.rightW - 16;

        if (this.tab == TAB_TRANSFORM) {
            graphics.drawString(this.font, "Position", rx, this.top + 44, MUTED, false);
            graphics.drawString(this.font, "Rotation", rx, this.top + 81, MUTED, false);
            graphics.drawString(this.font, "Display", rx, this.top + 118, MUTED, false);
        } else if (this.tab == TAB_ANIMATE) {
            graphics.drawString(this.font, "Walk-in Reveal", rx, this.top + 142, MUTED, false);
        } else if (this.tab == TAB_FX) {
            if (this.effects.isEmpty()) {
                graphics.drawString(this.font, "Click an effect above to enable", rx, this.top + 76, MUTED, false);
            } else {
                // Draw TINT color swatches under any TINT card
                int y = this.top + 66;
                for (int i = 0; i < this.effects.size(); i++) {
                    HologramEffect fx = this.effects.get(i);
                    if (fx.type() == HologramEffect.Type.TINT) {
                        int swY = y + 22;
                        int swW = (rw - (TINT_COLORS.length - 1) * 2) / TINT_COLORS.length;
                        for (int c = 0; c < TINT_COLORS.length; c++) {
                            int sx = rx + c * (swW + 2);
                            boolean sel = fx.color() == TINT_COLORS[c];
                            graphics.fill(sx, swY, sx + swW, swY + 14, 0xFF000000 | TINT_COLORS[c]);
                            graphics.renderOutline(sx, swY, swW, 14, sel ? 0xFFFFFFFF : 0x55000000);
                        }
                        y += 46;
                    } else {
                        y += 28;
                    }
                }
            }
        } else {
            graphics.drawString(this.font, "Layers (" + this.parts.size() + "/" + HologramPart.MAX_PARTS + ")",
                    rx, this.top + 96, MUTED, false);
            if (this.parts.isEmpty())
                graphics.drawString(this.font, "No extra layers stacked", rx, this.top + 110, 0xFF556070, false);
            if (this.selectedPart >= 0 && this.selectedPart < this.parts.size())
                graphics.drawString(this.font, "Selected layer offset & scale:", rx, this.top + PANEL_H - 83, MUTED, false);
        }
    }

    // ---------------------------------------------------------------- input

    private boolean isInsidePanel(double mouseX, double mouseY) {
        boolean inLeft = !this.pickerCollapsed
                && mouseX >= this.leftX && mouseX < this.leftX + this.leftW
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        boolean inRight = mouseX >= this.rightX && mouseX < this.rightX + this.rightW
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        boolean inTopBar = mouseY >= 4 && mouseY <= 24;
        return inLeft || inRight || inTopBar;
    }

    private void updateScrollFromMouseY(double mouseY) {
        int maxScroll = maxLeftScroll();
        if (maxScroll <= 0)
            return;
        int gridY = gridTop();
        int gridH = gridHeight();
        int barHeight = Math.max(14, gridH * leftVisibleRows() / leftTotalRows());
        int track = Math.max(1, gridH - barHeight);
        double frac = Mth.clamp((mouseY - gridY - barHeight * 0.5) / track, 0.0, 1.0);
        this.scroll = (int) Math.round(frac * maxScroll);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Clicking outside active inline EditBox commits it
        if (this.inlineBox != null && this.inlineControl != null && !this.inlineControl.hit(mouseX, mouseY))
            commitInlineBox();

        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        // Flat buttons
        if (button == 0) {
            for (FlatBtn btn : this.flatBtns) {
                if (btn.enabled && btn.hit(mouseX, mouseY)) {
                    btn.onClick.run();
                    return true;
                }
            }
        }

        // Tint color swatches on FX tab
        if (this.tab == TAB_FX && button == 0) {
            int rx = this.rightX + 8;
            int rw = this.rightW - 16;
            int y = this.top + 66;
            for (int i = 0; i < this.effects.size(); i++) {
                HologramEffect fx = this.effects.get(i);
                if (fx.type() == HologramEffect.Type.TINT) {
                    int swY = y + 22;
                    int swW = (rw - (TINT_COLORS.length - 1) * 2) / TINT_COLORS.length;
                    for (int c = 0; c < TINT_COLORS.length; c++) {
                        int sx = rx + c * (swW + 2);
                        if (mouseX >= sx && mouseX < sx + swW && mouseY >= swY && mouseY < swY + 14) {
                            setEffectColor(i, TINT_COLORS[c]);
                            return true;
                        }
                    }
                    y += 46;
                } else {
                    y += 28;
                }
            }
        }

        // Numeric sliders & scrub controls
        for (NumControl ctrl : this.numControls) {
            if (!ctrl.hit(mouseX, mouseY))
                continue;
            if (button == 1) {
                pushUndo();
                ctrl.set.accept(ctrl.defaultVal);
                refresh();
                return true;
            }
            if (button == 0) {
                this.activeNum = ctrl;
                this.numDragStartX = mouseX;
                this.numDragStartVal = ctrl.get.getAsDouble();
                this.numDragged = false;
                if (ctrl.isSlider) {
                    pushUndo();
                    applySlider(ctrl, mouseX);
                }
                return true;
            }
        }

        // Left Content Picker
        if (!this.pickerCollapsed && clickLeftPanel(mouseX, mouseY, button))
            return true;

        // Open 3D viewport interactions
        if (!isInsidePanel(mouseX, mouseY)) {
            setFocused(null);
            if (button == 1) {
                this.orbiting = true;
                return true;
            }
            if (button == 0) {
                if (!HologramClient.previewAnchored()
                        && this.gizmo.beginDrag(mouseX, mouseY, this.width, this.height, hologramWorldCenter(),
                        this.offsetX, this.offsetY, this.offsetZ, this.yaw, this.pitch, this.roll, this.scale)) {
                    pushUndo();
                    return true;
                }
                this.orbiting = true;
                return true;
            }
        }
        return false;
    }

    private void applySlider(NumControl ctrl, double mouseX) {
        double frac = Mth.clamp((mouseX - (ctrl.x + 2)) / Math.max(1.0, ctrl.w - 4), 0.0, 1.0);
        double raw = ctrl.min + frac * (ctrl.max - ctrl.min);
        if (!hasShiftDown() && ctrl.step > 0.0)
            raw = Math.rint(raw / ctrl.step) * ctrl.step;
        ctrl.set.accept(clamp(raw, ctrl.min, ctrl.max));
        refresh();
    }

    private boolean clickLeftPanel(double mouseX, double mouseY, int button) {
        int gridX = this.leftX + 8;
        int gridY = gridTop();
        int gridW = gridWidth();
        int gridH = gridHeight();
        int barX = scrollbarX();

        if (maxLeftScroll() > 0 && mouseX >= barX - 2 && mouseX <= barX + 8
                && mouseY >= gridY && mouseY < gridY + gridH) {
            this.draggingScrollbar = true;
            updateScrollFromMouseY(mouseY);
            return true;
        }

        if (this.pickMode == PICK_BLOCKS) {
            if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
                int col = (int) ((mouseX - gridX) / CELL);
                int row = (int) ((mouseY - gridY) / CELL);
                int entry = (this.scroll + row) * this.gridCols + col;
                if (entry >= 0 && entry < this.filteredBlocks.size()) {
                    pickBlock(this.filteredBlocks.get(entry));
                    return true;
                }
                return false;
            }

            BlockState state = currentState();
            if (state != null) {
                List<Property<?>> properties = HologramStates.properties(state);
                int infoY = gridY + gridH + 6;
                for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                    int y = infoY + 12 + i * PROP_PITCH;
                    if (mouseX >= this.leftX + 8 && mouseX < this.leftX + this.leftW - 8
                            && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                        cycleProperty(properties.get(i), state, button == 1 ? -1 : 1);
                        return true;
                    }
                }
            }
            return false;
        }

        // Entity or Particle list click: immediately sets the hologram to be that entity/particle
        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int row = (int) ((mouseY - gridY) / LIST_ROW_H);
            int entryIdx = this.scroll + row;
            if (this.pickMode == PICK_ENTITIES && entryIdx >= 0 && entryIdx < this.filteredEntities.size()) {
                String pickedId = this.filteredEntities.get(entryIdx).id();
                this.selectedEntityId = pickedId;
                setPrimaryPart(HologramPart.entity(pickedId, 0.0F));
                return true;
            }
            if (this.pickMode == PICK_PARTICLES && entryIdx >= 0 && entryIdx < this.filteredParticles.size()) {
                String pickedId = this.filteredParticles.get(entryIdx);
                this.selectedParticleId = pickedId;
                setPrimaryPart(HologramPart.particle(pickedId, 0.5F));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            updateScrollFromMouseY(mouseY);
            return true;
        }
        if (this.activeNum != null) {
            if (Math.abs(mouseX - this.numDragStartX) > 3.0)
                this.numDragged = true;
            if (this.activeNum.isSlider) {
                applySlider(this.activeNum, mouseX);
            } else if (this.numDragged) {
                double dx = mouseX - this.numDragStartX;
                double sensitivity = (hasShiftDown() ? 0.25 : 1.0) * this.activeNum.step / 8.0;
                double next = this.numDragStartVal + dx * sensitivity;
                if (!hasShiftDown() && this.activeNum.step > 0.0)
                    next = Math.rint(next / this.activeNum.step) * this.activeNum.step;
                this.activeNum.set.accept(clamp(next, this.activeNum.min, this.activeNum.max));
                refresh();
            }
            return true;
        }
        if (this.orbiting) {
            if (this.minecraft != null && this.minecraft.player != null)
                this.minecraft.player.turn(dragX * 4.0, dragY * 4.0);
            return true;
        }
        if (this.gizmo.isDragging()) {
            this.gizmo.updateDrag(mouseX, mouseY, this.width, this.height,
                    1.0F, hasShiftDown(), new HologramGizmo.Target() {
                        @Override
                        public void setOffset(float x, float y, float z) {
                            HologramScreen.this.offsetX = x;
                            HologramScreen.this.offsetY = y;
                            HologramScreen.this.offsetZ = z;
                            refresh();
                        }

                        @Override
                        public void setRotation(float y, float p, float r) {
                            HologramScreen.this.yaw = y;
                            HologramScreen.this.pitch = p;
                            HologramScreen.this.roll = r;
                            refresh();
                        }

                        @Override
                        public void setScale(float s) {
                            HologramScreen.this.scale = s;
                            refresh();
                        }
                    });
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        this.orbiting = false;
        if (this.activeNum != null) {
            NumControl released = this.activeNum;
            boolean wasDragged = this.numDragged;
            this.activeNum = null;
            this.numDragged = false;
            if (!wasDragged && !released.isSlider)
                openInlineBox(released);
            return true;
        }
        if (this.gizmo.isDragging()) {
            this.gizmo.cancelDrag();
            rebuildInspector();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int dir = (int) Math.signum(scrollY);
        if (dir == 0)
            return false;

        // Numeric sliders & scrub controls take the wheel directly
        for (NumControl ctrl : this.numControls) {
            if (ctrl.hit(mouseX, mouseY)) {
                double mult = hasControlDown() ? 4.0 : (hasShiftDown() ? 0.25 : 1.0);
                double next = ctrl.get.getAsDouble() + dir * ctrl.step * mult;
                ctrl.set.accept(clamp(next, ctrl.min, ctrl.max));
                refresh();
                return true;
            }
        }

        if (!this.pickerCollapsed) {
            // Property rows in Block mode
            if (this.pickMode == PICK_BLOCKS) {
                BlockState state = currentState();
                if (state != null) {
                    List<Property<?>> properties = HologramStates.properties(state);
                    int infoY = gridTop() + gridHeight() + 6;
                    for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                        int y = infoY + 12 + i * PROP_PITCH;
                        if (mouseX >= this.leftX + 8 && mouseX < this.leftX + this.leftW - 8
                                && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                            cycleProperty(properties.get(i), state, dir);
                            return true;
                        }
                    }
                }
            }

            // Left picker grid / list + scrollbar
            int gridX = this.leftX + 8;
            int gridY = gridTop();
            if (mouseX >= gridX && mouseX < scrollbarX() + 8
                    && mouseY >= gridY && mouseY < gridY + gridHeight()) {
                this.scroll = Mth.clamp(this.scroll - dir, 0, maxLeftScroll());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.inlineBox != null && this.inlineBox.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                commitInlineBox();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                cancelInlineBox();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.gizmo.isDragging()) {
            this.gizmo.cancelDrag();
            undoTransform();
            return true;
        }
        if (!(getFocused() instanceof EditBox)) {
            if (keyCode == GLFW.GLFW_KEY_G) {
                this.gizmo.setMode(HologramGizmo.Mode.MOVE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_R) {
                this.gizmo.setMode(HologramGizmo.Mode.ROTATE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_S) {
                this.gizmo.setMode(HologramGizmo.Mode.SCALE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown()) {
                undoTransform();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ---------------------------------------------------------------- state helpers

    private void pushUndo() {
        this.undoStack.push(new TransformSnapshot(
                this.offsetX, this.offsetY, this.offsetZ, this.yaw, this.pitch, this.roll, this.scale));
        while (this.undoStack.size() > 32)
            this.undoStack.removeLast();
    }

    private void undoTransform() {
        TransformSnapshot snap = this.undoStack.poll();
        if (snap == null)
            return;
        this.offsetX = snap.ox();
        this.offsetY = snap.oy();
        this.offsetZ = snap.oz();
        this.yaw = snap.yaw();
        this.pitch = snap.pitch();
        this.roll = snap.roll();
        this.scale = snap.scale();
        rebuildInspector();
        refresh();
    }

    private Vec3 hologramWorldCenter() {
        return new Vec3(
                this.pos.getX() + this.offsetX + 0.5,
                this.pos.getY() + this.offsetY + 0.5 * this.scale,
                this.pos.getZ() + this.offsetZ + 0.5);
    }

    private static float wrap(double degrees) {
        return Mth.wrapDegrees((float) degrees);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void refresh() {
        HologramClient.setPreview(buildSpec());
    }

    private HologramSpec buildSpec() {
        return new HologramSpec(this.id, this.pos, this.offsetX, this.offsetY, this.offsetZ,
                this.block, this.props, this.scale, this.yaw, this.pitch,
                this.roll, this.spin, this.spinAxis, this.hover, this.hoverPeriod, this.fullbright, true,
                this.renderDistance, this.owner, this.ownerName, this.effects, this.appearRadius,
                this.appearSeconds, this.parts, this.data);
    }

    @Nullable
    private BlockState currentState() {
        return HologramStates.resolve(this.block, this.props);
    }

    private void applyClipboard() {
        HologramSpec clip = HologramClipboard.peek();
        if (clip == null)
            return;
        this.offsetX = clip.offsetX();
        this.offsetY = clip.offsetY();
        this.offsetZ = clip.offsetZ();
        this.block = clip.block();
        this.props = new LinkedHashMap<>(clip.props());
        this.scale = clip.scale();
        this.yaw = clip.yaw();
        this.pitch = clip.pitch();
        this.roll = clip.roll();
        this.spin = clip.spin();
        this.spinAxis = clip.spinAxis();
        this.hover = clip.hover();
        this.hoverPeriod = clip.hoverPeriod();
        this.fullbright = clip.fullbright();
        this.renderDistance = Math.min(clip.renderDistance(), this.maxRenderDistance);
        this.effects = new ArrayList<>(clip.effects());
        this.parts = new ArrayList<>(clip.parts());
        this.data = clip.data().copy();
        this.appearRadius = clip.appearRadius();
        this.appearSeconds = clip.appearSeconds();
        this.primaryPartOverride = "minecraft:air".equals(this.block) && !this.parts.isEmpty();
        rebuildInspector();
        refresh();
    }

    private void duplicateBesideMe() {
        HologramSpec clip = HologramClipboard.peek();
        var player = this.minecraft == null ? null : this.minecraft.player;
        if (clip == null || player == null)
            return;
        var target = BlockPos.containing(player.getEyePosition().add(player.getViewVector(1.0F).scale(3.0)));
        HologramNetwork.sendCreate(target, clip);
    }

    public void pickBlock(Block block) {
        String next = BuiltInRegistries.BLOCK.getKey(block).toString();
        if (!next.equals(this.block)) {
            this.block = next;
            this.props = new LinkedHashMap<>();
            this.data = new CompoundTag();
            if (this.primaryPartOverride && !"minecraft:air".equals(next) && !this.parts.isEmpty()) {
                List<HologramPart> trimmed = new ArrayList<>(this.parts);
                trimmed.remove(0);
                this.parts = trimmed;
                this.primaryPartOverride = false;
                if (this.selectedPart >= trimmed.size())
                    this.selectedPart = trimmed.size() - 1;
                rebuildInspector();
            }
            refresh();
        }
    }

    private void cycleProperty(Property<?> property, BlockState state, int direction) {
        if (direction == 0)
            return;
        String current = HologramStates.valueOf(state, property);
        String next = HologramStates.cycle(property, current, direction);
        Map<String, String> props = new LinkedHashMap<>(this.props);
        props.put(property.getName(), next);
        this.props = props;
        refresh();
    }

    public void saveAndClose() {
        commitInlineBox();
        HologramNetwork.sendSave(buildSpec());
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    public void deleteAndClose() {
        cancelInlineBox();
        HologramNetwork.sendDelete(this.id);
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        cancelInlineBox();
        HologramClient.clearPreview();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- dev-check hooks

    public HologramSpec current() {
        return buildSpec();
    }

    public List<Property<?>> currentProperties() {
        BlockState state = currentState();
        return state == null ? List.of() : HologramStates.properties(state);
    }

    public void devCycleProperty(int index, int direction) {
        BlockState state = currentState();
        if (state == null)
            return;
        List<Property<?>> properties = HologramStates.properties(state);
        if (index < 0 || index >= properties.size())
            return;
        cycleProperty(properties.get(index), state, direction);
    }

    public void devSetScale(float scale) {
        this.scale = (float) clamp(scale, 0.25, 8.0);
        refresh();
    }

    public void devSetSpin(float spin) {
        this.spin = (float) clamp(spin, -360.0, 360.0);
        refresh();
    }

    public void devSetOffset(float x, float y, float z) {
        this.offsetX = (float) clamp(x, -16.0, 16.0);
        this.offsetY = (float) clamp(y, -16.0, 16.0);
        this.offsetZ = (float) clamp(z, -16.0, 16.0);
        rebuildInspector();
        refresh();
    }

    public void devSetAppearRadius(float radius) {
        this.appearRadius = (float) clamp(radius, 0.0, 64.0);
        refresh();
    }

    public void devLeftMode(int mode) {
        setPickMode(mode);
    }

    public void devCollapsePicker(boolean collapsed) {
        setPickerCollapsed(collapsed);
    }

    public int devScroll() {
        return this.scroll;
    }

    public void devDragScrollbar(double fraction) {
        int gridY = gridTop();
        int gridH = gridHeight();
        double y = gridY + Mth.clamp(fraction, 0.0, 1.0) * gridH;
        this.draggingScrollbar = true;
        updateScrollFromMouseY(y);
        this.draggingScrollbar = false;
    }

    public int devTab() {
        return this.tab;
    }

    public void devTab(int tab) {
        this.tab = Mth.clamp(tab, TAB_TRANSFORM, TAB_MORE);
        rebuildInspector();
    }

    public int devNumControlCount() {
        return this.numControls.size();
    }

    public void devToggleEffect(HologramEffect.Type type) {
        toggleEffect(type);
    }

    public void devSetEffectStrength(int index, float strength) {
        setEffectStrength(index, strength);
    }

    public void devSetEffectColor(int index, int color) {
        setEffectColor(index, color);
    }

    public void devSetEffectSpeed(int index, float speed) {
        setEffectSpeed(index, speed);
    }

    public void devRemoveEffect(int index) {
        removeEffect(index);
    }

    public void devPickEntity(String id) {
        this.selectedEntityId = id;
        setPrimaryPart(HologramPart.entity(id, 0.0F));
    }

    public void devAddPart(int kind, String id) {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;
        float topY = this.scale;
        HologramPart part = switch (Mth.clamp(kind, 0, 2)) {
            case 1 -> HologramPart.entity(id, topY);
            case 2 -> HologramPart.particle(id, topY * 0.5F);
            default -> HologramPart.block(id, Map.of(), topY);
        };
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        this.selectedPart = next.size() - 1;
        rebuildInspector();
        refresh();
    }

    public void devAdjustPart(int index, float dx, float dy, float dz, float scale, float yaw) {
        if (index < 0 || index >= this.parts.size())
            return;
        HologramPart old = this.parts.get(index);
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.set(index, new HologramPart(old.kind(), old.id(), old.props(),
                old.offsetX() + dx, old.offsetY() + dy, old.offsetZ() + dz, scale, yaw));
        this.parts = next;
        rebuildInspector();
        refresh();
    }

    public int devPartCount() {
        return this.parts.size();
    }

    public int devGridCols() {
        return this.gridCols;
    }

    public int devFreeWidth() {
        int leftEdge = this.pickerCollapsed ? this.leftX + 64 : this.leftX + this.leftW;
        return this.rightX - leftEdge;
    }
}
