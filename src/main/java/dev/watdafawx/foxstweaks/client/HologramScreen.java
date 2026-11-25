package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramOpenPayload;
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
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
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
 * 3D-studio hologram editor with unified Layers & Groups, independent per-layer & per-group
 * animation/FX, solo editing, live multiplayer preview streaming, full Undo/Redo history, and
 * selection-driven 3D gizmos.
 */
public class HologramScreen extends Screen {
    // ---- Palette ----
    private static final int DIM = 0x1A000000;
    private static final int PANEL_BG = 0xE80D1117;
    private static final int PANEL_BORDER = 0x40FFFFFF;
    private static final int SURFACE = 0xFF161C24;
    private static final int SURFACE_HOT = 0xFF212B38;
    private static final int SURFACE_ACTIVE = 0x404CC9F0;
    private static final int SOLO_BG = 0x55FFB84D;
    private static final int SOLO_BORDER = 0xFFFFB84D;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int DANGER_BG = 0x30FF4D5A;
    private static final int DANGER_HOT = 0x55FF4D5A;
    private static final int DANGER_TEXT = 0xFFFF7A85;
    private static final int AXIS_X = 0xFFFF5E6C;
    private static final int AXIS_Y = 0xFF5EE877;
    private static final int AXIS_Z = 0xFF5CA0FF;

    private static final int PANEL_H = 284;
    private static final int CELL = 18;
    private static final int GRID_ROWS = 5;
    private static final int LIST_ROW_H = 15;
    private static final int LIST_VISIBLE = 6;
    private static final int PROP_ROWS = 5;
    private static final int PROP_PITCH = 15;
    private static final int MAX_EFFECTS = HologramSpec.MAX_EFFECTS;
    private static final int MAX_HISTORY = 64;
    private static final long LIVE_SYNC_INTERVAL_MS = 100L;
    private static final String[] REVEAL_NAMES = { "Rise", "Pop", "Unfold", "Spin", "Drop", "Glitch" };

    public static final int TAB_LAYERS = 0;
    public static final int TAB_MAIN = 1;
    public static final int TAB_ANIMATE = 2;
    public static final int TAB_FX = 3;
    private static final String[] TAB_LABELS = { "Layers", "Main", "Anim", "FX" };

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

    public enum TargetKind {
        ROOT,
        GROUP,
        LAYER
    }

    private record EntityEntry(EntityType<?> type, String id, String displayName, ItemStack icon) {
    }

    private record EditorSnapshot(
            float rootOx, float rootOy, float rootOz,
            float rootYaw, float rootPitch, float rootRoll, float rootScale,
            int renderDistance, float appearRadius, float appearSeconds, int appearStyle,
            List<HologramGroup> groups,
            List<HologramLayer> layers,
            TargetKind targetKind,
            int selectedLayer,
            @Nullable String selectedGroupId
    ) {
    }

    private record TreeRow(TargetKind kind, int layerIndex, @Nullable String groupId, boolean indented) {
    }

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

    private record FlatBtn(int x, int y, int w, int h, String label, boolean active, boolean danger,
                           boolean solo, boolean enabled, Runnable onClick) {
        FlatBtn(int x, int y, int w, int h, String label, boolean active, boolean danger,
                boolean enabled, Runnable onClick) {
            this(x, y, w, h, label, active, danger, false, enabled, onClick);
        }

        boolean hit(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h;
        }
    }

    // ---- Identity & initial saved copy ----
    private final String id;
    private final BlockPos pos;
    private final int maxRenderDistance;
    @Nullable
    private final UUID owner;
    private final String ownerName;
    private final HologramSpec initialSpec;

    // ---- Live edited spec state ----
    private float rootOffsetX, rootOffsetY, rootOffsetZ;
    private float rootYaw, rootPitch, rootRoll;
    private float rootScale;
    private int renderDistance;
    private float appearRadius;
    private float appearSeconds;
    private int appearStyle;
    private List<HologramGroup> groups;
    private List<HologramLayer> layers;

    // ---- Selection & Solo ----
    private TargetKind targetKind = TargetKind.LAYER;
    private int selectedLayer = 0;
    @Nullable
    private String selectedGroupId;
    private int soloLayerIndex = -1;
    @Nullable
    private String soloGroupId;

    // ---- UI state ----
    private int tab = TAB_MAIN;
    private int pickMode = PICK_BLOCKS;
    private boolean pickerCollapsed;
    private int scroll;
    private int layerScroll;
    private int fxScroll;
    private int animScroll;
    /** Animations copied from one layer/group, pasted onto another. Survives closing the editor. */
    private static List<HologramEffect> animClipboard = List.of();
    private boolean draggingScrollbar;

    private String pickedBlockId = "minecraft:glass";
    private String pickedEntityId = "";
    private String pickedParticleId = "minecraft:end_rod";

    private final HologramGizmo gizmo = new HologramGizmo();
    private final Deque<EditorSnapshot> undoStack = new ArrayDeque<>();
    private final Deque<EditorSnapshot> redoStack = new ArrayDeque<>();
    private boolean orbiting;
    private boolean panning;

    // Live multiplayer preview throttle
    private boolean liveDirty;
    private long lastLiveSyncMs;
    private boolean finalized; // true once Save or Delete sent (so onClose won't send CANCEL)

    // Active drag-scrub or slider drag
    @Nullable
    private NumControl activeNum;
    private double numDragStartX;
    private double numDragStartVal;
    private boolean numDragged;
    private boolean numUndoPushed;

    @Nullable
    private EditBox inlineBox;
    @Nullable
    private NumControl inlineControl;
    private EditBox search;

    // Computed layout
    private int leftX, leftW;
    private int rightX, rightW;
    private int top;
    private int gridCols = 8;

    private List<Block> filteredBlocks = List.of();
    private List<EntityEntry> filteredEntities = List.of();
    private List<String> filteredParticles = List.of();
    @Nullable
    private Block hoveredGridBlock;
    @Nullable
    private EntityEntry hoveredEntity;
    @Nullable
    private String hoveredParticle;
    private int hoveredItemY;

    private final List<NumControl> numControls = new ArrayList<>();
    private final List<FlatBtn> flatBtns = new ArrayList<>();

    public HologramScreen(HologramOpenPayload payload) {
        super(Component.translatable("screen.foxstweaks.hologram.title"));
        HologramSpec spec = payload.spec();
        this.initialSpec = spec;
        this.id = spec.id();
        this.pos = spec.pos();
        this.maxRenderDistance = Math.max(16, payload.maxRenderDistance());
        this.owner = spec.owner();
        this.ownerName = spec.ownerName();
        loadFromSpec(spec);
    }

    private void loadFromSpec(HologramSpec spec) {
        this.rootOffsetX = spec.offsetX();
        this.rootOffsetY = spec.offsetY();
        this.rootOffsetZ = spec.offsetZ();
        this.rootYaw = spec.yaw();
        this.rootPitch = spec.pitch();
        this.rootRoll = spec.roll();
        this.rootScale = spec.scale();
        this.renderDistance = Math.min(spec.renderDistance(), this.maxRenderDistance);
        this.appearRadius = spec.appearRadius();
        this.appearSeconds = spec.appearSeconds();
        this.appearStyle = spec.appearStyle();
        this.groups = new ArrayList<>(spec.groups());
        this.layers = new ArrayList<>(spec.layers());
        if (this.layers.isEmpty())
            this.layers.add(HologramLayer.block("minecraft:stone", Map.of(), new CompoundTag()));

        this.targetKind = TargetKind.LAYER;
        this.selectedLayer = 0;
        this.selectedGroupId = null;
        syncPickerToSelection();
    }

    public static void open(HologramOpenPayload payload) {
        if (payload.spec() == null)
            return;
        Minecraft.getInstance().setScreen(new HologramScreen(payload));
    }

    // ---------------------------------------------------------------- layout & lifecycle

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
        this.rightW = Mth.clamp((int) (remaining * 0.54), 178, 232);
        int leftTarget = remaining - this.rightW;
        this.gridCols = Mth.clamp((leftTarget - 22) / CELL, 4, 8);
        this.leftW = this.gridCols * CELL + 22;

        this.leftX = 8;
        this.rightX = this.width - this.rightW - 8;
        this.top = Math.max(24, (this.height - PANEL_H) / 2);

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

        if (this.pickedEntityId.isEmpty() && !allEntities().isEmpty())
            this.pickedEntityId = allEntities().get(0).id();

        refreshFilter();
        rebuildInspector();
        applyLocalPreviewOnly();
        if (!HologramCamera.isActive())
            HologramCamera.begin(targetWorldCenter(), (float) getTargetScale());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.liveDirty && !this.finalized) {
            long now = System.currentTimeMillis();
            if (now - this.lastLiveSyncMs >= LIVE_SYNC_INTERVAL_MS)
                flushLivePreview();
        }
    }

    private void flushLivePreview() {
        this.liveDirty = false;
        this.lastLiveSyncMs = System.currentTimeMillis();
        HologramNetwork.sendPreview(buildSpec());
    }

    private void applyLocalPreviewOnly() {
        HologramClient.setPreview(buildSpec());
    }

    private void refresh() {
        HologramClient.setPreview(buildSpec());
        this.liveDirty = true;
        long now = System.currentTimeMillis();
        if (now - this.lastLiveSyncMs >= LIVE_SYNC_INTERVAL_MS)
            flushLivePreview();
    }

    // ---------------------------------------------------------------- selection & solo helpers

    @Nullable
    private HologramLayer currentLayer() {
        if (this.targetKind != TargetKind.LAYER)
            return null;
        if (this.selectedLayer < 0 || this.selectedLayer >= this.layers.size())
            return null;
        return this.layers.get(this.selectedLayer);
    }

    @Nullable
    private HologramGroup currentGroup() {
        if (this.targetKind != TargetKind.GROUP || this.selectedGroupId == null)
            return null;
        for (HologramGroup g : this.groups) {
            if (g.id().equals(this.selectedGroupId))
                return g;
        }
        return null;
    }

    private int indexOfGroup(String groupId) {
        if (groupId == null)
            return -1;
        for (int i = 0; i < this.groups.size(); i++) {
            if (this.groups.get(i).id().equals(groupId))
                return i;
        }
        return -1;
    }

    private void selectRoot() {
        commitInlineBox();
        this.targetKind = TargetKind.ROOT;
        rebuildInspector();
    }

    private void selectGroup(String groupId) {
        commitInlineBox();
        if (indexOfGroup(groupId) < 0)
            return;
        this.targetKind = TargetKind.GROUP;
        this.selectedGroupId = groupId;
        rebuildInspector();
    }

    private void selectLayer(int index) {
        commitInlineBox();
        if (index < 0 || index >= this.layers.size())
            return;
        this.targetKind = TargetKind.LAYER;
        this.selectedLayer = index;
        syncPickerToSelection();
        rebuildInspector();
    }

    private void syncPickerToSelection() {
        HologramLayer layer = currentLayer();
        if (layer == null)
            return;
        switch (layer.kind()) {
            case BLOCK -> {
                this.pickedBlockId = layer.id();
                this.pickMode = PICK_BLOCKS;
            }
            case ENTITY -> {
                this.pickedEntityId = layer.id();
                this.pickMode = PICK_ENTITIES;
            }
            case PARTICLE -> {
                this.pickedParticleId = layer.id();
                this.pickMode = PICK_PARTICLES;
            }
        }
        if (this.search != null)
            updateSearchHint();
        refreshFilter();
    }

    private void toggleSoloLayer(int index) {
        if (this.soloLayerIndex == index) {
            this.soloLayerIndex = -1;
            this.soloGroupId = null;
            HologramClient.clearSolo();
        } else {
            this.soloLayerIndex = index;
            this.soloGroupId = null;
            HologramClient.setSoloLayer(this.id, index);
        }
        rebuildInspector();
    }

    private void toggleSoloGroup(String groupId) {
        if (groupId.equals(this.soloGroupId)) {
            this.soloGroupId = null;
            this.soloLayerIndex = -1;
            HologramClient.clearSolo();
        } else {
            this.soloGroupId = groupId;
            this.soloLayerIndex = -1;
            HologramClient.setSoloGroup(this.id, groupId);
        }
        rebuildInspector();
    }

    private void clearSoloIfInvalid() {
        if (this.soloLayerIndex >= this.layers.size()) {
            this.soloLayerIndex = -1;
            HologramClient.clearSolo();
        }
        if (this.soloGroupId != null && indexOfGroup(this.soloGroupId) < 0) {
            this.soloGroupId = null;
            HologramClient.clearSolo();
        }
    }

    private String targetLabel() {
        if (this.targetKind == TargetKind.ROOT)
            return "Root (All)";
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? "Group" : g.name();
        }
        HologramLayer l = currentLayer();
        return l == null ? "Layer" : ((this.selectedLayer + 1) + ". " + l.shortName());
    }

    private void cycleTarget(int delta) {
        List<TreeRow> rows = buildTreeRows();
        if (rows.isEmpty())
            return;
        int cur = 0;
        for (int i = 0; i < rows.size(); i++) {
            TreeRow r = rows.get(i);
            if (r.kind == this.targetKind
                    && (r.kind == TargetKind.ROOT
                    || (r.kind == TargetKind.LAYER && r.layerIndex == this.selectedLayer)
                    || (r.kind == TargetKind.GROUP && r.groupId != null && r.groupId.equals(this.selectedGroupId)))) {
                cur = i;
                break;
            }
        }
        TreeRow next = rows.get(Math.floorMod(cur + delta, rows.size()));
        if (next.kind == TargetKind.ROOT)
            selectRoot();
        else if (next.kind == TargetKind.GROUP && next.groupId != null)
            selectGroup(next.groupId);
        else
            selectLayer(next.layerIndex);
    }

    private List<TreeRow> buildTreeRows() {
        List<TreeRow> rows = new ArrayList<>();
        rows.add(new TreeRow(TargetKind.ROOT, -1, null, false));
        for (HologramGroup g : this.groups) {
            rows.add(new TreeRow(TargetKind.GROUP, -1, g.id(), false));
            for (int i = 0; i < this.layers.size(); i++) {
                if (g.id().equals(this.layers.get(i).groupId()))
                    rows.add(new TreeRow(TargetKind.LAYER, i, g.id(), true));
            }
        }
        for (int i = 0; i < this.layers.size(); i++) {
            String gid = this.layers.get(i).groupId();
            if (gid.isEmpty() || indexOfGroup(gid) < 0)
                rows.add(new TreeRow(TargetKind.LAYER, i, null, false));
        }
        return rows;
    }

    // ---------------------------------------------------------------- history (undo / redo / revert)

    private EditorSnapshot snapshot() {
        return new EditorSnapshot(
                this.rootOffsetX, this.rootOffsetY, this.rootOffsetZ,
                this.rootYaw, this.rootPitch, this.rootRoll, this.rootScale,
                this.renderDistance, this.appearRadius, this.appearSeconds, this.appearStyle,
                List.copyOf(this.groups), List.copyOf(this.layers),
                this.targetKind, this.selectedLayer, this.selectedGroupId);
    }

    private void restoreSnapshot(EditorSnapshot snap) {
        this.rootOffsetX = snap.rootOx();
        this.rootOffsetY = snap.rootOy();
        this.rootOffsetZ = snap.rootOz();
        this.rootYaw = snap.rootYaw();
        this.rootPitch = snap.rootPitch();
        this.rootRoll = snap.rootRoll();
        this.rootScale = snap.rootScale();
        this.renderDistance = snap.renderDistance();
        this.appearRadius = snap.appearRadius();
        this.appearSeconds = snap.appearSeconds();
        this.appearStyle = snap.appearStyle();
        this.groups = new ArrayList<>(snap.groups());
        this.layers = new ArrayList<>(snap.layers());
        this.targetKind = snap.targetKind();
        this.selectedLayer = Mth.clamp(snap.selectedLayer(), 0, Math.max(0, this.layers.size() - 1));
        this.selectedGroupId = snap.selectedGroupId();
        if (this.targetKind == TargetKind.GROUP && indexOfGroup(this.selectedGroupId) < 0)
            this.targetKind = TargetKind.LAYER;
        clearSoloIfInvalid();
        syncPickerToSelection();
        rebuildInspector();
        refresh();
    }

    private void pushUndo() {
        this.undoStack.push(snapshot());
        while (this.undoStack.size() > MAX_HISTORY)
            this.undoStack.removeLast();
        this.redoStack.clear();
    }

    public void undo() {
        EditorSnapshot prev = this.undoStack.poll();
        if (prev == null)
            return;
        this.redoStack.push(snapshot());
        restoreSnapshot(prev);
    }

    public void redo() {
        EditorSnapshot next = this.redoStack.poll();
        if (next == null)
            return;
        this.undoStack.push(snapshot());
        restoreSnapshot(next);
    }

    public void revertToSaved() {
        pushUndo();
        loadFromSpec(this.initialSpec);
        this.soloLayerIndex = -1;
        this.soloGroupId = null;
        HologramClient.clearSolo();
        rebuildInspector();
        refresh();
    }

    // ---------------------------------------------------------------- UI builders

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
            case PICK_ENTITIES -> "search (@mod $tag)...";
            case PICK_PARTICLES -> "search (@mod)...";
            default -> "search (@mod #tip $tag)";
        };
        this.search.setHint(Component.literal(hint).withStyle(style -> style.withColor(MUTED)));
    }

    private void rebuildInspector() {
        this.numControls.clear();
        this.flatBtns.clear();

        // ---- Top-center gizmo, history & camera toolbar ----
        HologramGizmo.Mode[] modes = HologramGizmo.Mode.values();
        String[] modeLabels = { "Move", "Rotate", "Scale", "Off" };
        int btnW = 36;
        int utilGap = 4;
        int totalW = modes.length * (btnW + 2) + utilGap + 32 + 2 + 32 + 2 + 38 + 2 + 30 + 2 + 30
                + 2 + 38 + 2 + 26;
        int gx = (this.width - totalW) / 2;
        for (int i = 0; i < modes.length; i++) {
            HologramGizmo.Mode m = modes[i];
            this.flatBtns.add(new FlatBtn(gx + i * (btnW + 2), 5, btnW, 15, modeLabels[i],
                    this.gizmo.mode() == m, false, true, () -> {
                this.gizmo.setMode(m);
                rebuildInspector();
            }));
        }
        int ux = gx + modes.length * (btnW + 2) + utilGap;
        this.flatBtns.add(new FlatBtn(ux, 5, 32, 15, "Undo", false, false, !this.undoStack.isEmpty(), this::undo));
        this.flatBtns.add(new FlatBtn(ux + 34, 5, 32, 15, "Redo", false, false, !this.redoStack.isEmpty(), this::redo));
        this.flatBtns.add(new FlatBtn(ux + 68, 5, 38, 15, "Revert", false, false, true, this::revertToSaved));
        this.flatBtns.add(new FlatBtn(ux + 108, 5, 30, 15, "Zero", false, false, canZeroTarget(), this::zeroTarget));
        this.flatBtns.add(new FlatBtn(ux + 140, 5, 30, 15, "Cam", false, false, true, this::frameCamera));
        this.flatBtns.add(new FlatBtn(ux + 172, 5, 38, 15, HologramRenderer.isPaused() ? "Play" : "Pause",
                HologramRenderer.isPaused(), false, true, () -> {
            HologramRenderer.setPaused(!HologramRenderer.isPaused());
            rebuildInspector();
        }));
        this.flatBtns.add(new FlatBtn(ux + 212, 5, 26, 15, "T=0", false, false, true,
                HologramRenderer::restartClock));

        // ---- Left panel header / collapse & add-layer button ----
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
            boolean canAdd = this.layers.size() < HologramLayer.MAX_LAYERS;
            String addLabel = canAdd
                    ? ("+ Add " + PICK_LABELS[this.pickMode] + " Layer (" + this.layers.size() + "/" + HologramLayer.MAX_LAYERS + ")")
                    : ("Max " + HologramLayer.MAX_LAYERS + " Layers");
            this.flatBtns.add(new FlatBtn(this.leftX + 8, this.top + PANEL_H - 22, this.leftW - 16, 15,
                    addLabel, false, false, canAdd, this::addLayerFromPicker));
        }

        // ---- Right Inspector tabs (4 tabs: Layers | Main | Anim | FX) ----
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

        // ---- Persistent Target Selector Bar (right under tabs) ----
        int barY = this.top + 38;
        boolean soloActive = this.soloLayerIndex >= 0 || this.soloGroupId != null;
        int soloBtnW = soloActive ? 34 : 0;
        int navW = 14;
        int labelW = rw - navW * 2 - 4 - (soloActive ? soloBtnW + 2 : 0);
        this.flatBtns.add(new FlatBtn(rx, barY, navW, 14, "<", false, false, true, () -> cycleTarget(-1)));
        this.flatBtns.add(new FlatBtn(rx + navW + 2, barY, labelW, 14, targetLabel(),
                this.tab == TAB_LAYERS, false, true, () -> {
            this.tab = TAB_LAYERS;
            rebuildInspector();
        }));
        int nextX = rx + navW + 2 + labelW + 2;
        this.flatBtns.add(new FlatBtn(nextX, barY, navW, 14, ">", false, false, true, () -> cycleTarget(1)));
        if (soloActive) {
            this.flatBtns.add(new FlatBtn(nextX + navW + 2, barY, soloBtnW, 14, "Solo", false, false, true, true, () -> {
                this.soloLayerIndex = -1;
                this.soloGroupId = null;
                HologramClient.clearSolo();
                rebuildInspector();
            }));
        }

        // ---- Tab Body ----
        int y = this.top + 58;
        if (this.tab == TAB_LAYERS) {
            buildLayersTab(rx, rw, y);
        } else if (this.tab == TAB_MAIN) {
            buildMainTab(rx, rw, y);
        } else if (this.tab == TAB_ANIMATE) {
            buildAnimTab(rx, rw, y);
        } else {
            buildFxTab(rx, rw, y);
        }

        // ---- Right Inspector Footer: Done | Cancel | Del ----
        int footY = this.top + PANEL_H - 25;
        int delW = 36;
        int mainBtnsW = rw - delW - 4;
        int doneW = (mainBtnsW - 2) / 2;
        int cancelW = mainBtnsW - 2 - doneW;
        this.flatBtns.add(new FlatBtn(rx, footY, doneW, 17, "Done", true, false, true, this::saveAndClose));
        this.flatBtns.add(new FlatBtn(rx + doneW + 2, footY, cancelW, 17, "Cancel", false, false, true, this::cancelAndClose));
        this.flatBtns.add(new FlatBtn(rx + doneW + 2 + cancelW + 4, footY, delW, 17, "Del", false, true, true,
                this::deleteAndClose));
    }

    private void buildLayersTab(int rx, int rw, int y) {
        // Row 1: +Layer | +Group | Copy | Paste | New
        int b1 = (rw - 8) / 5;
        int rem = rw - (b1 + 2) * 4;
        this.flatBtns.add(new FlatBtn(rx, y, b1, 15, "+Layer", false, false,
                this.layers.size() < HologramLayer.MAX_LAYERS, this::addLayerFromPicker));
        this.flatBtns.add(new FlatBtn(rx + (b1 + 2), y, b1, 15, "+Grp", false, false,
                this.groups.size() < HologramGroup.MAX_GROUPS, this::addGroup));
        this.flatBtns.add(new FlatBtn(rx + (b1 + 2) * 2, y, b1, 15, "Copy", false, false, true, () -> {
            HologramClipboard.copy(buildSpec());
            rebuildInspector();
        }));
        this.flatBtns.add(new FlatBtn(rx + (b1 + 2) * 3, y, b1, 15, "Paste", false, false,
                HologramClipboard.has(), this::applyClipboard));
        this.flatBtns.add(new FlatBtn(rx + (b1 + 2) * 4, y, rem, 15, "New", false, false,
                HologramClipboard.has(), this::duplicateBesideMe));
        y += 19;

        // Scrollable hierarchy tree
        List<TreeRow> rows = buildTreeRows();
        int maxVis = 8;
        this.layerScroll = Mth.clamp(this.layerScroll, 0, Math.max(0, rows.size() - maxVis));
        int rowH = 17;
        for (int v = 0; v < maxVis; v++) {
            int rIdx = this.layerScroll + v;
            if (rIdx >= rows.size())
                break;
            TreeRow row = rows.get(rIdx);
            int ry = y + v * (rowH + 2);

            if (row.kind == TargetKind.ROOT) {
                boolean sel = this.targetKind == TargetKind.ROOT;
                this.flatBtns.add(new FlatBtn(rx, ry, rw, rowH,
                        "Root (All - " + this.layers.size() + " layers)", sel, false, true, this::selectRoot));
            } else if (row.kind == TargetKind.GROUP && row.groupId != null) {
                HologramGroup grp = currentGroupById(row.groupId);
                if (grp == null)
                    continue;
                String gid = grp.id();
                boolean sel = this.targetKind == TargetKind.GROUP && gid.equals(this.selectedGroupId);
                boolean soloed = gid.equals(this.soloGroupId);

                int btnSmall = 15;
                int x = rx;
                this.flatBtns.add(new FlatBtn(x, ry, btnSmall, rowH, grp.visible() ? "V" : "-",
                        grp.visible(), false, true, () -> toggleGroupVisible(gid)));
                x += btnSmall + 2;
                this.flatBtns.add(new FlatBtn(x, ry, btnSmall, rowH, "S", false, false, soloed, true,
                        () -> toggleSoloGroup(gid)));
                x += btnSmall + 2;
                int mainW = rw - (x - rx) - (btnSmall + 2);
                String fxTag = grp.effects().isEmpty() && grp.spin() == 0 && grp.hover() == 0 ? "" : " *";
                this.flatBtns.add(new FlatBtn(x, ry, mainW, rowH, grp.name() + fxTag, sel, false, true,
                        () -> selectGroup(gid)));
                this.flatBtns.add(new FlatBtn(rx + rw - btnSmall, ry, btnSmall, rowH, "x", false, true, true,
                        () -> removeGroup(gid)));
            } else {
                int lIdx = row.layerIndex;
                HologramLayer layer = this.layers.get(lIdx);
                boolean sel = this.targetKind == TargetKind.LAYER && this.selectedLayer == lIdx;
                boolean soloed = this.soloLayerIndex == lIdx;

                int indent = row.indented ? 8 : 0;
                int btnSmall = 14;
                int grpBtnW = this.groups.isEmpty() ? 0 : 18;
                int x = rx + indent;
                this.flatBtns.add(new FlatBtn(x, ry, btnSmall, rowH, layer.visible() ? "V" : "-",
                        layer.visible(), false, true, () -> toggleLayerVisible(lIdx)));
                x += btnSmall + 2;
                this.flatBtns.add(new FlatBtn(x, ry, btnSmall, rowH, "S", false, false, soloed, true,
                        () -> toggleSoloLayer(lIdx)));
                x += btnSmall + 2;

                int rightUsed = btnSmall + 2 + btnSmall + (grpBtnW > 0 ? grpBtnW + 2 : 0);
                int mainW = Math.max(24, (rx + rw) - x - rightUsed - 2);
                this.flatBtns.add(new FlatBtn(x, ry, mainW, rowH, (lIdx + 1) + ". " + layer.label(),
                        sel, false, true, () -> selectLayer(lIdx)));

                int rightXPos = rx + rw - rightUsed;
                if (grpBtnW > 0) {
                    String gLbl = layer.groupId().isEmpty() ? "--" : layer.groupId().toUpperCase(Locale.ROOT);
                    this.flatBtns.add(new FlatBtn(rightXPos, ry, grpBtnW, rowH, gLbl,
                            !layer.groupId().isEmpty(), false, true, () -> cycleLayerGroup(lIdx)));
                    rightXPos += grpBtnW + 2;
                }
                this.flatBtns.add(new FlatBtn(rightXPos, ry, btnSmall, rowH, "+", false, false,
                        this.layers.size() < HologramLayer.MAX_LAYERS, () -> duplicateLayer(lIdx)));
                rightXPos += btnSmall + 2;
                this.flatBtns.add(new FlatBtn(rightXPos, ry, btnSmall, rowH, "x", false, true,
                        this.layers.size() > 1, () -> removeLayer(lIdx)));
            }
        }

        // Bottom utility row: Up / Down for selected layer
        int utilY = this.top + PANEL_H - 46;
        int halfW = (rw - 4) / 2;
        boolean canUp = this.targetKind == TargetKind.LAYER && this.selectedLayer > 0;
        boolean canDown = this.targetKind == TargetKind.LAYER && this.selectedLayer >= 0
                && this.selectedLayer < this.layers.size() - 1;
        this.flatBtns.add(new FlatBtn(rx, utilY, halfW, 15, "Move Up", false, false, canUp,
                () -> moveLayer(this.selectedLayer, -1)));
        this.flatBtns.add(new FlatBtn(rx + halfW + 4, utilY, rw - halfW - 4, 15, "Move Down", false, false, canDown,
                () -> moveLayer(this.selectedLayer, 1)));
    }

    private void buildMainTab(int rx, int rw, int y) {
        int colW = (rw - 6) / 3;
        HologramLayer layer = currentLayer();
        HologramGroup group = currentGroup();

        // Position X / Y / Z
        y += 10;
        this.numControls.add(new NumControl(rx, y, colW, 17, "X", AXIS_X, false,
                -16.0, 16.0, 0.25, 0.0, this::getTargetOffsetX, v -> setTargetOffsetX((float) v), "", 2));
        this.numControls.add(new NumControl(rx + colW + 3, y, colW, 17, "Y", AXIS_Y, false,
                -16.0, 16.0, 0.25, 0.0, this::getTargetOffsetY, v -> setTargetOffsetY((float) v), "", 2));
        this.numControls.add(new NumControl(rx + (colW + 3) * 2, y, rw - (colW + 3) * 2, 17, "Z", AXIS_Z, false,
                -16.0, 16.0, 0.25, 0.0, this::getTargetOffsetZ, v -> setTargetOffsetZ((float) v), "", 2));
        y += 23;

        if (layer != null && layer.kind() == HologramLayer.Kind.PARTICLE) {
            // Particle-specific controls: Spread, Rate, Rise Speed
            y += 10;
            this.numControls.add(new NumControl(rx, y, rw, 17, "Spread", TEXT, true,
                    0.05, 4.0, 0.05, 0.35, this::getTargetScale, v -> setTargetScale((float) v), "m", 2));
            y += 22;
            this.numControls.add(new NumControl(rx, y, rw, 17, "Rate", TEXT, true,
                    1.0, 40.0, 1.0, 10.0,
                    () -> {
                        HologramLayer l = currentLayer();
                        return l == null ? 10.0 : l.particleRate();
                    },
                    v -> updateCurrentLayer(l -> l.withParticle((float) v, l.particleSpeed())), "/s", 0));
            y += 22;
            this.numControls.add(new NumControl(rx, y, rw, 17, "Rise", TEXT, true,
                    -0.5, 0.5, 0.01, 0.01,
                    () -> {
                        HologramLayer l = currentLayer();
                        return l == null ? 0.01 : l.particleSpeed();
                    },
                    v -> updateCurrentLayer(l -> l.withParticle(l.particleRate(), (float) v)), "", 2));
            return;
        }

        // Rotation Pitch / Yaw / Roll
        y += 10;
        this.numControls.add(new NumControl(rx, y, colW, 17, "P", AXIS_X, false,
                -180.0, 180.0, 15.0, 0.0, this::getTargetPitch, v -> setTargetPitch(wrap(v)), "°", 0));
        this.numControls.add(new NumControl(rx + colW + 3, y, colW, 17, "Y", AXIS_Y, false,
                -180.0, 180.0, 15.0, 0.0, this::getTargetYaw, v -> setTargetYaw(wrap(v)), "°", 0));
        this.numControls.add(new NumControl(rx + (colW + 3) * 2, y, rw - (colW + 3) * 2, 17, "R", AXIS_Z, false,
                -180.0, 180.0, 15.0, 0.0, this::getTargetRoll, v -> setTargetRoll(wrap(v)), "°", 0));
        y += 23;

        // Scale slider
        y += 10;
        double minScale = this.targetKind == TargetKind.ROOT ? 0.25 : 0.1;
        this.numControls.add(new NumControl(rx, y, rw, 17, "Scale", TEXT, true,
                minScale, 8.0, 0.25, 1.0, this::getTargetScale, v -> setTargetScale((float) v), "x", 2));
        y += 23;

        if (this.targetKind == TargetKind.ROOT) {
            this.numControls.add(new NumControl(rx, y, rw, 17, "Range", TEXT, true,
                    8.0, this.maxRenderDistance, 8.0, 48.0,
                    () -> this.renderDistance,
                    v -> this.renderDistance = (int) clamp(Math.round(v), 8, this.maxRenderDistance), "m", 0));
            y += 23;
            this.flatBtns.add(new FlatBtn(rx, y, rw, 17,
                    "In-Front Preview: " + (HologramClient.previewAnchored() ? "On" : "Off"),
                    HologramClient.previewAnchored(), false, true, () -> {
                HologramClient.setPreviewAnchored(!HologramClient.previewAnchored());
                rebuildInspector();
                applyLocalPreviewOnly();
            }));
        } else if (group != null) {
            int halfW = (rw - 4) / 2;
            this.flatBtns.add(new FlatBtn(rx, y, halfW, 17,
                    "Glow: " + (group.fullbright() ? "On" : "Off"), group.fullbright(), false, true, () -> {
                pushUndo();
                updateCurrentGroup(g -> g.withFullbright(!g.fullbright()));
                rebuildInspector();
                refresh();
            }));
            this.flatBtns.add(new FlatBtn(rx + halfW + 4, y, rw - halfW - 4, 17,
                    "Visible: " + (group.visible() ? "On" : "Off"), group.visible(), false, true, () -> {
                toggleGroupVisible(group.id());
            }));
        } else if (layer != null) {
            int halfW = (rw - 4) / 2;
            this.flatBtns.add(new FlatBtn(rx, y, halfW, 17,
                    "Glow: " + (layer.fullbright() ? "On" : "Off"), layer.fullbright(), false, true, () -> {
                pushUndo();
                updateCurrentLayer(l -> l.withFullbright(!l.fullbright()));
                rebuildInspector();
                refresh();
            }));
            this.flatBtns.add(new FlatBtn(rx + halfW + 4, y, rw - halfW - 4, 17,
                    "Preview: " + (HologramClient.previewAnchored() ? "On" : "Off"),
                    HologramClient.previewAnchored(), false, true, () -> {
                HologramClient.setPreviewAnchored(!HologramClient.previewAnchored());
                rebuildInspector();
                applyLocalPreviewOnly();
            }));
            y += 22;

            // Block layer BE capture controls
            if (layer.kind() == HologramLayer.Kind.BLOCK) {
                String dataLabel = layer.data().isEmpty() ? "Capture BE" : ("Re-cap (" + layer.data().sizeInBytes() + "B)");
                this.flatBtns.add(new FlatBtn(rx, y, halfW, 16, dataLabel, false, false, true,
                        () -> HologramNetwork.sendRecapture(this.id, this.selectedLayer)));
                this.flatBtns.add(new FlatBtn(rx + halfW + 4, y, rw - halfW - 4, 16, "Clear BE",
                        false, true, !layer.data().isEmpty(), () -> {
                    pushUndo();
                    updateCurrentLayer(l -> l.withData(new CompoundTag()));
                    rebuildInspector();
                    refresh();
                }));
            }
        }
    }

    private void buildAnimTab(int rx, int rw, int y) {
        if (this.targetKind == TargetKind.ROOT) {
            // Walk-in reveal controls live on Root
            y += 10;
            this.numControls.add(new NumControl(rx, y, rw, 17, "Appear", TEXT, true,
                    0.0, 64.0, 2.0, 0.0, () -> this.appearRadius,
                    v -> this.appearRadius = (float) clamp(v, 0.0, 64.0), "m", 0));
            y += 22;
            this.numControls.add(new NumControl(rx, y, rw, 17, "Ease", TEXT, true,
                    0.0, 5.0, 0.2, 0.6, () -> this.appearSeconds,
                    v -> this.appearSeconds = (float) clamp(v, 0.0, 10.0), "s", 1));
            y += 24;
            int rW = (rw - 4) / 3;
            for (int i = 0; i < REVEAL_NAMES.length; i++) {
                int st = i;
                int bx = rx + (i % 3) * (rW + 2);
                int by = y + (i / 3) * 18;
                this.flatBtns.add(new FlatBtn(bx, by, rW, 15, REVEAL_NAMES[i],
                        this.appearStyle == st, false, true, () -> {
                    pushUndo();
                    this.appearStyle = st;
                    rebuildInspector();
                    refresh();
                }));
            }
            return;
        }
        // Group or Layer: a stackable list of animations (motion + entity), instead of one Spin and one Hover.
        List<HologramEffect> list = getTargetEffects();
        boolean entityTarget = this.targetKind == TargetKind.GROUP
                || (currentLayer() != null && currentLayer().kind() == HologramLayer.Kind.ENTITY);

        // Presets: replace the motion animations with a ready-made combo (FX and entity animations stay).
        String[] presets = HologramEffect.PRESETS;
        int presetW = (rw - (presets.length - 1) * 2) / presets.length;
        for (int i = 0; i < presets.length; i++) {
            String name = presets[i];
            this.flatBtns.add(new FlatBtn(rx + i * (presetW + 2), y, presetW, 15, "~" + name, false, false, true,
                    () -> applyPreset(name)));
        }
        y += 19;

        // Row 1-2: motion pills. Clicking one adds another, so "Spin" can be on X and Y at once.
        List<HologramEffect.Type> motions = HologramEffect.Type.motionTypes();
        int perRow = 5;
        int pillW = (rw - (perRow - 1) * 2) / perRow;
        for (int i = 0; i < motions.size(); i++) {
            HologramEffect.Type type = motions.get(i);
            boolean has = indexOfEffect(list, type) >= 0;
            this.flatBtns.add(new FlatBtn(rx + (i % perRow) * (pillW + 2), y + (i / perRow) * 17, pillW, 15,
                    type.shortName(), has, false, list.size() < MAX_EFFECTS, () -> addEffect(type)));
        }
        y += ((motions.size() + perRow - 1) / perRow) * 17;

        // Row 3: entity animations, only where an entity can be drawn.
        if (entityTarget) {
            List<HologramEffect.Type> ents = HologramEffect.Type.entityTypes();
            int eW = (rw - (ents.size() - 1) * 2) / ents.size();
            for (int i = 0; i < ents.size(); i++) {
                HologramEffect.Type type = ents.get(i);
                boolean has = indexOfEffect(list, type) >= 0;
                this.flatBtns.add(new FlatBtn(rx + i * (eW + 2), y, eW, 15, type.shortName(), has, false,
                        has || list.size() < MAX_EFFECTS, () -> toggleEffect(type)));
            }
            y += 17;
        }
        y += 3;

        // Old saves and commands still hold a single Spin/Hover on the layer itself; offer to fold them in.
        if (hasLegacyMotion()) {
            this.flatBtns.add(new FlatBtn(rx, y, rw, 15, "Convert old Spin/Hover to animations", false, false,
                    list.size() + 2 <= MAX_EFFECTS, this::convertLegacyMotion));
            y += 19;
        }

        // Copy / paste, pinned under the card list.
        int copyY = this.top + PANEL_H - 58;
        int halfW = (rw - 2) / 2;
        this.flatBtns.add(new FlatBtn(rx, copyY, halfW, 15, "Copy anims", false, false,
                !effectIndices(true).isEmpty(), this::copyAnims));
        this.flatBtns.add(new FlatBtn(rx + halfW + 2, copyY, rw - halfW - 2, 15, "Paste anims", false, false,
                !animClipboard.isEmpty(), this::pasteAnims));

        int maxCardY = copyY - 4;
        List<Integer> idxs = effectIndices(true);
        this.animScroll = Mth.clamp(this.animScroll, 0, Math.max(0, idxs.size() - 1));
        for (int n = this.animScroll; n < idxs.size(); n++) {
            int idx = idxs.get(n);
            HologramEffect fx = list.get(idx);
            if (y + 21 > maxCardY)
                break;

            HologramEffect.Type type = fx.type();
            int xBtn = 17;
            int axisW = type.usesAxis() ? 22 : 0;
            boolean amount = type.usesAmount();
            boolean speed = type.usesSpeed();
            boolean phase = type.usesPhase();
            int controls = (amount ? 1 : 0) + (speed ? 1 : 0) + (phase ? 1 : 0);
            int trigW = 30;
            int items = controls + (type.usesAxis() ? 1 : 0) + 2;
            int avail = rw - xBtn - axisW - trigW - 3 * (items - 1);
            int weightSum = (amount ? 4 : 0) + (speed ? 3 : 0) + (phase ? 3 : 0);
            int amountW = amount ? avail * 4 / Math.max(1, weightSum) : 0;
            int speedW = speed ? avail * 3 / Math.max(1, weightSum) : 0;
            int phaseW = phase ? avail - amountW - speedW : 0;
            int cx = rx;

            if (!amount && !speed) {
                this.flatBtns.add(new FlatBtn(cx, y, avail, 17, type.shortName() + " (on)", true, false, false,
                        () -> {
                        }));
            }
            if (amount) {
                this.numControls.add(new NumControl(cx, y, amountW, 17, type.shortName(), TEXT, true,
                        type.minAmount(), type.maxAmount(), type.amountStep(), HologramEffect.of(type).strength(),
                        () -> {
                            List<HologramEffect> cur = getTargetEffects();
                            return idx < cur.size() ? cur.get(idx).strength() : 0.0;
                        },
                        v -> setEffectStrength(idx, (float) v), unitOf(type), type.amountDecimals()));
                cx += amountW + 3;
            }
            if (speed) {
                this.numControls.add(new NumControl(cx, y, speedW, 17, "Hz", MUTED, false,
                        0.05, 20.0, 0.05, HologramEffect.of(type).speed(),
                        () -> {
                            List<HologramEffect> cur = getTargetEffects();
                            return idx < cur.size() ? cur.get(idx).speed() : 1.0;
                        },
                        v -> setEffectSpeed(idx, (float) v), "", 2));
                cx += speedW + 3;
            }
            if (phase) {
                this.numControls.add(new NumControl(cx, y, phaseW, 17, "Ph", MUTED, false,
                        0.0, 1.0, 0.05, 0.0,
                        () -> {
                            List<HologramEffect> cur = getTargetEffects();
                            return idx < cur.size() ? cur.get(idx).phase() : 0.0;
                        },
                        v -> setEffectPhase(idx, (float) v), "", 2));
                cx += phaseW + 3;
            }
            this.flatBtns.add(new FlatBtn(cx, y, trigW, 17, fx.trigger().shortLabel(),
                    fx.trigger() != HologramEffect.Trigger.ALWAYS, false, true, () -> cycleEffectTrigger(idx)));
            cx += trigW + 3;
            if (type.usesAxis()) {
                this.flatBtns.add(new FlatBtn(cx, y, axisW, 17, fx.axisLabel(), false, false, true,
                        () -> cycleEffectAxis(idx)));
            }
            this.flatBtns.add(new FlatBtn(rx + rw - xBtn, y, xBtn, 17, "x", false, true, true,
                    () -> removeEffect(idx)));
            y += 24;
        }
    }

    private static String unitOf(HologramEffect.Type type) {
        return switch (type) {
            case SPIN -> "\u00B0/s";
            case SWAY, FLIP -> "\u00B0";
            case BOB, ORBIT, SLIDE, BOUNCE -> "m";
            default -> "";
        };
    }

    /** Indices into the target's effect list for the Anim tab ({@code anim} true) or the FX tab. */
    private List<Integer> effectIndices(boolean anim) {
        List<HologramEffect> all = getTargetEffects();
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).type().isAnim() == anim)
                out.add(i);
        }
        return out;
    }

    private boolean hasLegacyMotion() {
        return getTargetSpin() != 0.0 || getTargetHover() != 0.0;
    }

    private void convertLegacyMotion() {
        List<HologramEffect> next = new ArrayList<>(getTargetEffects());
        double spin = getTargetSpin();
        double hover = getTargetHover();
        if (spin != 0.0 && next.size() < MAX_EFFECTS) {
            next.add(HologramEffect.of(HologramEffect.Type.SPIN)
                    .withAxis(getTargetSpinAxis()).withStrength((float) spin));
        }
        if (hover != 0.0 && next.size() < MAX_EFFECTS) {
            float period = (float) Math.max(0.5, getTargetHoverPeriod());
            next.add(HologramEffect.of(HologramEffect.Type.BOB)
                    .withAxis(HologramSpec.AXIS_Y).withStrength((float) hover).withSpeed(1.0F / period));
        }
        pushUndo();
        setTargetEffects(next);
        setTargetSpin(0.0F);
        setTargetHover(0.0F);
        rebuildInspector();
        refresh();
    }

    private void setEffectPhase(int index, float phase) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, cur.get(index).withPhase(phase));
        setTargetEffects(next);
        refresh();
    }

    /** The target's effects with its motion animations swapped for {@code motions} (everything else is kept). */
    private void replaceMotions(List<HologramEffect> motions, boolean entityToo) {
        List<HologramEffect> next = new ArrayList<>();
        for (HologramEffect fx : getTargetEffects()) {
            boolean drop = fx.type().isMotion() || (entityToo && fx.type().isEntityAnim());
            if (!drop)
                next.add(fx);
        }
        for (HologramEffect fx : motions) {
            if (next.size() < MAX_EFFECTS)
                next.add(fx);
        }
        pushUndo();
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void applyPreset(String name) {
        replaceMotions(HologramEffect.preset(name), false);
    }

    private void copyAnims() {
        List<HologramEffect> out = new ArrayList<>();
        for (HologramEffect fx : getTargetEffects()) {
            if (fx.type().isAnim())
                out.add(fx);
        }
        animClipboard = List.copyOf(out);
        rebuildInspector();
    }

    private void pasteAnims() {
        if (!animClipboard.isEmpty())
            replaceMotions(animClipboard, true);
    }

    private void addEffect(HologramEffect.Type type) {
        List<HologramEffect> cur = getTargetEffects();
        if (cur.size() >= MAX_EFFECTS)
            return;
        pushUndo();
        List<HologramEffect> next = new ArrayList<>(cur);
        next.add(HologramEffect.addable(type, cur));
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void cycleEffectTrigger(int index) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        pushUndo();
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, cur.get(index).withTrigger(cur.get(index).trigger().next()));
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void cycleEffectAxis(int index) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        pushUndo();
        HologramEffect old = cur.get(index);
        int[] order = { HologramSpec.AXIS_X, HologramSpec.AXIS_Y, HologramSpec.AXIS_Z };
        int pos = 0;
        for (int i = 0; i < order.length; i++) {
            if (order[i] == old.axis())
                pos = i;
        }
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, old.withAxis(order[(pos + 1) % order.length]));
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void buildFxTab(int rx, int rw, int y) {
        if (this.targetKind == TargetKind.ROOT) {
            this.flatBtns.add(new FlatBtn(rx, y + 26, rw, 18, "Edit Layer 1 FX", false, false, true,
                    () -> selectLayer(0)));
            if (this.groups.isEmpty()) {
                this.flatBtns.add(new FlatBtn(rx, y + 48, rw, 18, "+ Create Group for Shared FX", false, false, true,
                        this::addGroup));
            }
            return;
        }

        List<HologramEffect> fxList = getTargetEffects();
        List<HologramEffect.Type> allTypes = HologramEffect.Type.fxTypes();
        int row1W = (rw - 8) / 5;
        for (int i = 0; i < 5; i++) {
            HologramEffect.Type type = allTypes.get(i);
            boolean has = indexOfEffect(fxList, type) >= 0;
            this.flatBtns.add(new FlatBtn(rx + i * (row1W + 2), y, row1W, 15, type.shortName(),
                    has, false, has || fxList.size() < MAX_EFFECTS, () -> toggleEffect(type)));
        }
        y += 17;
        int row2W = (rw - 6) / 4;
        for (int i = 5; i < allTypes.size(); i++) {
            HologramEffect.Type type = allTypes.get(i);
            boolean has = indexOfEffect(fxList, type) >= 0;
            this.flatBtns.add(new FlatBtn(rx + (i - 5) * (row2W + 2), y, row2W, 15, type.shortName(),
                    has, false, has || fxList.size() < MAX_EFFECTS, () -> toggleEffect(type)));
        }
        y += 20;

        int maxCardY = this.top + PANEL_H - 32;
        List<Integer> fxIdx = effectIndices(false);
        this.fxScroll = Mth.clamp(this.fxScroll, 0, Math.max(0, fxIdx.size() - 1));
        for (int n = this.fxScroll; n < fxIdx.size(); n++) {
            int idx = fxIdx.get(n);
            HologramEffect fx = fxList.get(idx);
            int cardH = fx.type() == HologramEffect.Type.TINT ? 38 : 21;
            if (y + cardH > maxCardY)
                break;
            int trigW = 30;
            int strW = fx.type() == HologramEffect.Type.TINT ? rw - 23 - trigW : (rw - 27 - trigW) * 60 / 100;
            this.numControls.add(new NumControl(rx, y, strW, 17, fx.label(), TEXT, true,
                    0.0, 2.0, 0.1, 0.5,
                    () -> {
                        List<HologramEffect> cur = getTargetEffects();
                        return idx < cur.size() ? cur.get(idx).strength() : 0.0;
                    },
                    v -> setEffectStrength(idx, (float) v), "", 2));
            if (fx.type() != HologramEffect.Type.TINT) {
                int spdX = rx + strW + 3;
                int spdW = rw - strW - 26 - trigW;
                this.numControls.add(new NumControl(spdX, y, spdW, 17, "Hz", MUTED, false,
                        0.1, 20.0, 0.5, 1.0,
                        () -> {
                            List<HologramEffect> cur = getTargetEffects();
                            return idx < cur.size() ? cur.get(idx).speed() : 1.0;
                        },
                        v -> setEffectSpeed(idx, (float) v), "", 1));
            }
            this.flatBtns.add(new FlatBtn(rx + rw - 20 - trigW, y, trigW, 17, fx.trigger().shortLabel(),
                    fx.trigger() != HologramEffect.Trigger.ALWAYS, false, true, () -> cycleEffectTrigger(idx)));
            this.flatBtns.add(new FlatBtn(rx + rw - 17, y, 17, 17, "x", false, true, true,
                    () -> removeEffect(idx)));
            y += cardH + 3;
        }
    }

    // ---------------------------------------------------------------- target property getters/setters

    @Nullable
    private HologramGroup currentGroupById(String groupId) {
        for (HologramGroup g : this.groups) {
            if (g.id().equals(groupId))
                return g;
        }
        return null;
    }

    private void updateCurrentLayer(java.util.function.UnaryOperator<HologramLayer> fn) {
        if (this.targetKind != TargetKind.LAYER || this.selectedLayer < 0 || this.selectedLayer >= this.layers.size())
            return;
        this.layers.set(this.selectedLayer, fn.apply(this.layers.get(this.selectedLayer)));
    }

    private void updateCurrentGroup(java.util.function.UnaryOperator<HologramGroup> fn) {
        if (this.targetKind != TargetKind.GROUP || this.selectedGroupId == null)
            return;
        int idx = indexOfGroup(this.selectedGroupId);
        if (idx >= 0)
            this.groups.set(idx, fn.apply(this.groups.get(idx)));
    }

    private double getTargetOffsetX() {
        return switch (this.targetKind) {
            case ROOT -> this.rootOffsetX;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.offsetX();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.offsetX();
            }
        };
    }

    private double getTargetOffsetY() {
        return switch (this.targetKind) {
            case ROOT -> this.rootOffsetY;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.offsetY();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.offsetY();
            }
        };
    }

    private double getTargetOffsetZ() {
        return switch (this.targetKind) {
            case ROOT -> this.rootOffsetZ;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.offsetZ();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.offsetZ();
            }
        };
    }

    private void setTargetOffsetX(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootOffsetX = Mth.clamp(v, -16.0F, 16.0F);
            case GROUP -> updateCurrentGroup(g -> g.withOffset(v, g.offsetY(), g.offsetZ()));
            case LAYER -> updateCurrentLayer(l -> l.withOffset(v, l.offsetY(), l.offsetZ()));
        }
    }

    private void setTargetOffsetY(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootOffsetY = Mth.clamp(v, -16.0F, 16.0F);
            case GROUP -> updateCurrentGroup(g -> g.withOffset(g.offsetX(), v, g.offsetZ()));
            case LAYER -> updateCurrentLayer(l -> l.withOffset(l.offsetX(), v, l.offsetZ()));
        }
    }

    private void setTargetOffsetZ(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootOffsetZ = Mth.clamp(v, -16.0F, 16.0F);
            case GROUP -> updateCurrentGroup(g -> g.withOffset(g.offsetX(), g.offsetY(), v));
            case LAYER -> updateCurrentLayer(l -> l.withOffset(l.offsetX(), l.offsetY(), v));
        }
    }

    private double getTargetYaw() {
        return switch (this.targetKind) {
            case ROOT -> this.rootYaw;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.yaw();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.yaw();
            }
        };
    }

    private double getTargetPitch() {
        return switch (this.targetKind) {
            case ROOT -> this.rootPitch;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.pitch();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.pitch();
            }
        };
    }

    private double getTargetRoll() {
        return switch (this.targetKind) {
            case ROOT -> this.rootRoll;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 0.0 : g.roll();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 0.0 : l.roll();
            }
        };
    }

    private void setTargetYaw(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootYaw = Mth.wrapDegrees(v);
            case GROUP -> updateCurrentGroup(g -> g.withRotation(v, g.pitch(), g.roll()));
            case LAYER -> updateCurrentLayer(l -> l.withRotation(v, l.pitch(), l.roll()));
        }
    }

    private void setTargetPitch(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootPitch = Mth.wrapDegrees(v);
            case GROUP -> updateCurrentGroup(g -> g.withRotation(g.yaw(), v, g.roll()));
            case LAYER -> updateCurrentLayer(l -> l.withRotation(l.yaw(), v, l.roll()));
        }
    }

    private void setTargetRoll(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootRoll = Mth.wrapDegrees(v);
            case GROUP -> updateCurrentGroup(g -> g.withRotation(g.yaw(), g.pitch(), v));
            case LAYER -> updateCurrentLayer(l -> l.withRotation(l.yaw(), l.pitch(), v));
        }
    }

    private double getTargetScale() {
        return switch (this.targetKind) {
            case ROOT -> this.rootScale;
            case GROUP -> {
                HologramGroup g = currentGroup();
                yield g == null ? 1.0 : g.scale();
            }
            case LAYER -> {
                HologramLayer l = currentLayer();
                yield l == null ? 1.0 : l.scale();
            }
        };
    }

    private void setTargetScale(float v) {
        switch (this.targetKind) {
            case ROOT -> this.rootScale = Mth.clamp(v, 0.25F, 8.0F);
            case GROUP -> updateCurrentGroup(g -> g.withScale(v));
            case LAYER -> updateCurrentLayer(l -> l.withScale(v));
        }
    }

    private double getTargetSpin() {
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? 0.0 : g.spin();
        }
        HologramLayer l = currentLayer();
        return l == null ? 0.0 : l.spin();
    }

    private int getTargetSpinAxis() {
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? HologramSpec.AXIS_Y : g.spinAxis();
        }
        HologramLayer l = currentLayer();
        return l == null ? HologramSpec.AXIS_Y : l.spinAxis();
    }

    private void setTargetSpin(float v) {
        if (this.targetKind == TargetKind.GROUP)
            updateCurrentGroup(g -> g.withSpin(v, g.spinAxis()));
        else
            updateCurrentLayer(l -> l.withSpin(v, l.spinAxis()));
    }

    private void setTargetSpinAxis(int axis) {
        if (this.targetKind == TargetKind.GROUP)
            updateCurrentGroup(g -> g.withSpin(g.spin(), axis));
        else
            updateCurrentLayer(l -> l.withSpin(l.spin(), axis));
    }

    private double getTargetHover() {
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? 0.0 : g.hover();
        }
        HologramLayer l = currentLayer();
        return l == null ? 0.0 : l.hover();
    }

    private double getTargetHoverPeriod() {
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? 4.0 : g.hoverPeriod();
        }
        HologramLayer l = currentLayer();
        return l == null ? 4.0 : l.hoverPeriod();
    }

    private void setTargetHover(float v) {
        if (this.targetKind == TargetKind.GROUP)
            updateCurrentGroup(g -> g.withHover(v, g.hoverPeriod()));
        else
            updateCurrentLayer(l -> l.withHover(v, l.hoverPeriod()));
    }

    private void setTargetHoverPeriod(float v) {
        if (this.targetKind == TargetKind.GROUP)
            updateCurrentGroup(g -> g.withHover(g.hover(), v));
        else
            updateCurrentLayer(l -> l.withHover(l.hover(), v));
    }

    private List<HologramEffect> getTargetEffects() {
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            return g == null ? List.of() : g.effects();
        }
        if (this.targetKind == TargetKind.LAYER) {
            HologramLayer l = currentLayer();
            return l == null ? List.of() : l.effects();
        }
        return List.of();
    }

    private void setTargetEffects(List<HologramEffect> next) {
        if (this.targetKind == TargetKind.GROUP)
            updateCurrentGroup(g -> g.withEffects(next));
        else if (this.targetKind == TargetKind.LAYER)
            updateCurrentLayer(l -> l.withEffects(next));
    }

    private boolean canZeroTarget() {
        return getTargetOffsetX() != 0.0 || getTargetOffsetY() != 0.0 || getTargetOffsetZ() != 0.0;
    }

    private void zeroTarget() {
        pushUndo();
        setTargetOffsetX(0.0F);
        setTargetOffsetY(0.0F);
        setTargetOffsetZ(0.0F);
        rebuildInspector();
        refresh();
    }

    // ---------------------------------------------------------------- layer & group mutations

    private void addGroup() {
        if (this.groups.size() >= HologramGroup.MAX_GROUPS)
            return;
        pushUndo();
        int n = 1;
        while (indexOfGroup("g" + n) >= 0)
            n++;
        String gid = "g" + n;
        this.groups.add(HologramGroup.create(gid, "Group " + n));
        this.targetKind = TargetKind.GROUP;
        this.selectedGroupId = gid;
        rebuildInspector();
        refresh();
    }

    private void removeGroup(String groupId) {
        int idx = indexOfGroup(groupId);
        if (idx < 0)
            return;
        pushUndo();
        this.groups.remove(idx);
        for (int i = 0; i < this.layers.size(); i++) {
            if (groupId.equals(this.layers.get(i).groupId()))
                this.layers.set(i, this.layers.get(i).withGroupId(""));
        }
        if (this.targetKind == TargetKind.GROUP && groupId.equals(this.selectedGroupId)) {
            this.targetKind = TargetKind.LAYER;
            this.selectedLayer = 0;
            this.selectedGroupId = null;
        }
        clearSoloIfInvalid();
        rebuildInspector();
        refresh();
    }

    private void toggleGroupVisible(String groupId) {
        int idx = indexOfGroup(groupId);
        if (idx < 0)
            return;
        pushUndo();
        HologramGroup g = this.groups.get(idx);
        this.groups.set(idx, g.withVisible(!g.visible()));
        rebuildInspector();
        refresh();
    }

    private void toggleLayerVisible(int index) {
        if (index < 0 || index >= this.layers.size())
            return;
        pushUndo();
        HologramLayer l = this.layers.get(index);
        this.layers.set(index, l.withVisible(!l.visible()));
        rebuildInspector();
        refresh();
    }

    private void cycleLayerGroup(int index) {
        if (index < 0 || index >= this.layers.size() || this.groups.isEmpty())
            return;
        pushUndo();
        HologramLayer l = this.layers.get(index);
        String cur = l.groupId();
        String next;
        if (cur.isEmpty()) {
            next = this.groups.get(0).id();
        } else {
            int gIdx = indexOfGroup(cur);
            next = (gIdx < 0 || gIdx + 1 >= this.groups.size()) ? "" : this.groups.get(gIdx + 1).id();
        }
        this.layers.set(index, l.withGroupId(next));
        rebuildInspector();
        refresh();
    }

    private void duplicateLayer(int index) {
        if (index < 0 || index >= this.layers.size() || this.layers.size() >= HologramLayer.MAX_LAYERS)
            return;
        pushUndo();
        HologramLayer src = this.layers.get(index);
        HologramLayer copy = src.withOffset(src.offsetX(), src.offsetY() + 0.5F, src.offsetZ());
        this.layers.add(index + 1, copy);
        selectLayer(index + 1);
        refresh();
    }

    private void removeLayer(int index) {
        if (this.layers.size() <= 1 || index < 0 || index >= this.layers.size())
            return;
        pushUndo();
        this.layers.remove(index);
        if (this.selectedLayer >= this.layers.size())
            this.selectedLayer = this.layers.size() - 1;
        clearSoloIfInvalid();
        syncPickerToSelection();
        rebuildInspector();
        refresh();
    }

    private void moveLayer(int index, int delta) {
        int dst = index + delta;
        if (index < 0 || index >= this.layers.size() || dst < 0 || dst >= this.layers.size())
            return;
        pushUndo();
        HologramLayer moved = this.layers.remove(index);
        this.layers.add(dst, moved);
        this.selectedLayer = dst;
        if (this.soloLayerIndex == index) {
            this.soloLayerIndex = dst;
            HologramClient.setSoloLayer(this.id, dst);
        }
        rebuildInspector();
        refresh();
    }

    private void addLayerFromPicker() {
        if (this.layers.size() >= HologramLayer.MAX_LAYERS)
            return;
        pushUndo();
        float topY = 0.0F;
        for (HologramLayer l : this.layers)
            topY = Math.max(topY, l.offsetY() + (l.kind() == HologramLayer.Kind.PARTICLE ? 0.5F : l.scale()));

        HologramLayer created = switch (this.pickMode) {
            case PICK_ENTITIES -> HologramLayer.entity(
                    this.pickedEntityId.isEmpty() ? "minecraft:zombie" : this.pickedEntityId, topY);
            case PICK_PARTICLES -> HologramLayer.particle(
                    this.pickedParticleId.isEmpty() ? "minecraft:end_rod" : this.pickedParticleId, topY + 0.5F);
            default -> HologramLayer.block(
                    this.pickedBlockId.isEmpty() ? "minecraft:glass" : this.pickedBlockId, Map.of(), topY);
        };
        if (this.targetKind == TargetKind.GROUP && this.selectedGroupId != null)
            created = created.withGroupId(this.selectedGroupId);

        this.layers.add(created);
        this.targetKind = TargetKind.LAYER;
        this.selectedLayer = this.layers.size() - 1;
        rebuildInspector();
        refresh();
    }

    // ---------------------------------------------------------------- effects mutations

    private static int indexOfEffect(List<HologramEffect> list, HologramEffect.Type type) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).type() == type)
                return i;
        }
        return -1;
    }

    private void toggleEffect(HologramEffect.Type type) {
        List<HologramEffect> cur = getTargetEffects();
        int existing = indexOfEffect(cur, type);
        if (existing >= 0) {
            removeEffect(existing);
            return;
        }
        if (cur.size() >= MAX_EFFECTS)
            return;
        pushUndo();
        List<HologramEffect> next = new ArrayList<>(cur);
        next.add(HologramEffect.of(type));
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void removeEffect(int index) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        pushUndo();
        List<HologramEffect> next = new ArrayList<>(cur);
        next.remove(index);
        setTargetEffects(next);
        rebuildInspector();
        refresh();
    }

    private void setEffectStrength(int index, float strength) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        HologramEffect old = cur.get(index);
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, old.withStrength(strength));
        setTargetEffects(next);
        refresh();
    }

    private void setEffectSpeed(int index, float speed) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        HologramEffect old = cur.get(index);
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, old.withSpeed(speed));
        setTargetEffects(next);
        refresh();
    }

    private void setEffectColor(int index, int color) {
        List<HologramEffect> cur = getTargetEffects();
        if (index < 0 || index >= cur.size())
            return;
        pushUndo();
        HologramEffect old = cur.get(index);
        List<HologramEffect> next = new ArrayList<>(cur);
        next.set(index, old.withColor(color));
        setTargetEffects(next);
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
                list.add(new EntityEntry(type, key.toString(), type.getDescription().getString(),
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
        HologramSearch query = HologramSearch.parse(this.search == null ? "" : this.search.getValue());
        if (this.pickMode == PICK_BLOCKS) {
            List<Block> blocks = allBlocks();
            if (query.isEmpty()) {
                this.filteredBlocks = blocks;
            } else {
                List<Block> matches = new ArrayList<>();
                for (Block b : blocks) {
                    if (query.matchesBlock(b))
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
                    if (query.matchesEntity(e.type(), e.id(), e.displayName()))
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
                    if (query.matchesParticle(p))
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
        HologramCamera.setMovementBlocked(getFocused() instanceof EditBox || this.gizmo.isDragging() || this.activeNum != null);
        HologramCamera.setAnchor(targetWorldCenter());
        renderBackground(graphics, mouseX, mouseY, partialTick);

        if (!HologramClient.previewAnchored()) {
            this.gizmo.render(graphics, this.font, mouseX, mouseY, this.width, this.height,
                    targetWorldCenter(), (float) getTargetScale());
        }

        this.hoveredGridBlock = null;
        this.hoveredEntity = null;
        this.hoveredParticle = null;
        if (!this.pickerCollapsed)
            renderLeftPanel(graphics, mouseX, mouseY);
        renderRightPanel(graphics, mouseX, mouseY);

        for (FlatBtn btn : this.flatBtns)
            drawFlatBtn(graphics, btn, mouseX, mouseY);
        for (NumControl ctrl : this.numControls)
            drawNumControl(graphics, ctrl, mouseX, mouseY);

        String hint = "WASD/Space/Shift: fly  |  Scroll: zoom  |  R-drag: look  |  M-drag: pan  |  1-4 gizmo  |  F: frame cam  |  T: chat";
        int hintW = this.font.width(hint);
        int hintX = (this.width - hintW) / 2;
        int hintY = this.height - 13;
        graphics.fill(hintX - 6, hintY - 2, hintX + hintW + 6, hintY + 10, 0xA00D1117);
        graphics.drawString(this.font, hint, hintX, hintY, MUTED, false);

        super.render(graphics, mouseX, mouseY, partialTick);

        if (!this.pickerCollapsed) {
            int cardX = this.leftX + this.leftW + 6;
            double secs = HologramRenderer.animSeconds();
            if (this.hoveredGridBlock != null) {
                HologramPickerPreview.renderBlockCard(graphics, this.font, this.hoveredGridBlock,
                        cardX, this.hoveredItemY, this.height);
            } else if (this.hoveredEntity != null) {
                HologramPickerPreview.renderEntityCard(graphics, this.font,
                        this.hoveredEntity.id(), this.hoveredEntity.displayName(), this.hoveredEntity.icon(),
                        cardX, this.hoveredItemY, this.height, secs);
            } else if (this.hoveredParticle != null) {
                HologramPickerPreview.renderParticleCard(graphics, this.font, this.hoveredParticle,
                        cardX, this.hoveredItemY, this.height, secs);
            }
        }
    }

    private void drawPanel(GuiGraphics graphics, int x, int y, int w, int h, String title) {
        graphics.fill(x, y, x + w, y + h, PANEL_BG);
        graphics.renderOutline(x, y, w, h, PANEL_BORDER);
        graphics.drawString(this.font, title, x + 8, y + 6, ACCENT, false);
    }

    private void drawFlatBtn(GuiGraphics graphics, FlatBtn btn, int mouseX, int mouseY) {
        boolean hot = btn.enabled && btn.hit(mouseX, mouseY);
        int bg, border, txt;
        if (!btn.enabled) {
            bg = 0x55161C24;
            border = 0x22FFFFFF;
            txt = 0xFF556070;
        } else if (btn.solo) {
            bg = hot ? 0x77FFB84D : SOLO_BG;
            border = SOLO_BORDER;
            txt = 0xFFFFE4B5;
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
        String label = this.font.plainSubstrByWidth(btn.label, Math.max(4, btn.w - 4));
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

        graphics.fill(this.leftX + 8, this.top + 36, this.leftX + this.leftW - 8, this.top + 51, SURFACE);
        graphics.renderOutline(this.leftX + 8, this.top + 36, this.leftW - 16, 15,
                this.search != null && this.search.isFocused() ? ACCENT : 0x28FFFFFF);

        int gridX = this.leftX + 8;
        int gridY = gridTop();
        int gridW = gridWidth();
        int gridH = gridHeight();
        int maxScroll = maxLeftScroll();
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        HologramLayer selLayer = currentLayer();
        String activeId = selLayer == null ? "" : selLayer.id();

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
                String bKey = BuiltInRegistries.BLOCK.getKey(entryBlock).toString();
                boolean selected = selLayer != null && selLayer.kind() == HologramLayer.Kind.BLOCK && bKey.equals(activeId);
                if (hot) {
                    this.hoveredGridBlock = entryBlock;
                    this.hoveredItemY = y;
                }
                if (selected)
                    graphics.fill(x, y, x + CELL, y + CELL, SURFACE_ACTIVE);
                else if (hot)
                    graphics.fill(x, y, x + CELL, y + CELL, SURFACE_HOT);

                graphics.renderItem(new ItemStack(entryBlock.asItem()), x + 1, y + 1);
            }
        } else if (this.pickMode == PICK_ENTITIES) {
            double secs = HologramRenderer.animSeconds();
            for (int row = 0; row < LIST_VISIBLE; row++) {
                int entryIdx = this.scroll + row;
                if (entryIdx >= this.filteredEntities.size())
                    break;
                EntityEntry e = this.filteredEntities.get(entryIdx);
                int y = gridY + row * LIST_ROW_H;
                boolean hot = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= y && mouseY < y + LIST_ROW_H - 1;
                if (hot) {
                    this.hoveredEntity = e;
                    this.hoveredItemY = y;
                }
                boolean selected = selLayer != null && selLayer.kind() == HologramLayer.Kind.ENTITY && e.id().equals(activeId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? SURFACE_ACTIVE : (hot ? SURFACE_HOT : SURFACE));
                boolean drew3d = HologramPickerPreview.drawEntityIcon(
                        graphics, e.id(), gridX + 1, y + 1, 13, 30.0F, secs);
                if (!drew3d && !e.icon().isEmpty()) {
                    graphics.pose().pushPose();
                    graphics.pose().translate(gridX + 1, y + 1, 0);
                    graphics.pose().scale(0.75F, 0.75F, 1.0F);
                    graphics.renderItem(e.icon(), 0, 0);
                    graphics.pose().popPose();
                }
                int textX = gridX + 16;
                String label = this.font.plainSubstrByWidth(e.displayName(), gridW - (textX - gridX) - 2);
                graphics.drawString(this.font, label, textX, y + 3, selected ? ACCENT : TEXT, false);
            }
        } else {
            double secs = HologramRenderer.animSeconds();
            for (int row = 0; row < LIST_VISIBLE; row++) {
                int entryIdx = this.scroll + row;
                if (entryIdx >= this.filteredParticles.size())
                    break;
                String p = this.filteredParticles.get(entryIdx);
                int y = gridY + row * LIST_ROW_H;
                boolean hot = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= y && mouseY < y + LIST_ROW_H - 1;
                if (hot) {
                    this.hoveredParticle = p;
                    this.hoveredItemY = y;
                }
                boolean selected = selLayer != null && selLayer.kind() == HologramLayer.Kind.PARTICLE && p.equals(activeId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? SURFACE_ACTIVE : (hot ? SURFACE_HOT : SURFACE));
                HologramPickerPreview.drawParticleIcon(graphics, p, gridX + 2, y + 1, 12, secs);
                String label = this.font.plainSubstrByWidth(shortId(p), gridW - 18);
                graphics.drawString(this.font, label, gridX + 16, y + 3, selected ? ACCENT : TEXT, false);
            }
        }

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
            BlockState state = currentBlockState();
            Block currentBlock = state == null ? HologramStates.blockOf(this.pickedBlockId) : state.getBlock();
            String name = currentBlock == null ? "none" : currentBlock.getName().getString();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, this.leftW - 16),
                    this.leftX + 8, infoY, TEXT, false);

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
            String picked = this.pickMode == PICK_ENTITIES ? this.pickedEntityId : this.pickedParticleId;
            graphics.drawString(this.font, this.font.plainSubstrByWidth(picked, this.leftW - 16),
                    this.leftX + 8, infoY, ACCENT, false);
            graphics.drawString(this.font, "Click: set selected layer", this.leftX + 8, infoY + 14, MUTED, false);
            graphics.drawString(this.font, "+ Add below: new layer", this.leftX + 8, infoY + 26, MUTED, false);
        }
    }

    private void renderRightPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        drawPanel(graphics, this.rightX, this.top, this.rightW, PANEL_H, "Inspector");
        int rx = this.rightX + 8;
        int rw = this.rightW - 16;

        if (this.tab == TAB_MAIN) {
            HologramLayer layer = currentLayer();
            graphics.drawString(this.font, "Position", rx, this.top + 58, MUTED, false);
            if (layer != null && layer.kind() == HologramLayer.Kind.PARTICLE) {
                graphics.drawString(this.font, "Particle Emitter", rx, this.top + 91, MUTED, false);
            } else {
                graphics.drawString(this.font, "Rotation", rx, this.top + 91, MUTED, false);
                graphics.drawString(this.font, "Display", rx, this.top + 124, MUTED, false);
            }
        } else if (this.tab == TAB_ANIMATE) {
            if (this.targetKind == TargetKind.ROOT) {
                graphics.drawString(this.font, "Walk-in Reveal (Whole Hologram)", rx, this.top + 58, MUTED, false);
            } else if (this.targetKind == TargetKind.LAYER) {
                HologramLayer l = currentLayer();
                if (l != null && !l.groupId().isEmpty()) {
                    HologramGroup g = currentGroupById(l.groupId());
                    if (g != null) {
                        graphics.drawString(this.font, "Stacks with " + g.name(),
                                rx, this.top + PANEL_H - 42, 0xFF7FA8C9, false);
                    }
                }
            }
            if (this.targetKind != TargetKind.ROOT && effectIndices(true).isEmpty()) {
                graphics.drawString(this.font, "Click a pill to add an animation.", rx, this.top + PANEL_H - 92, MUTED, false);
                graphics.drawString(this.font, "Add the same one twice for 2 axes.", rx, this.top + PANEL_H - 81, MUTED, false);
            }
        } else if (this.tab == TAB_FX) {
            if (this.targetKind == TargetKind.ROOT) {
                graphics.drawString(this.font, "FX are per-layer or per-group", rx, this.top + 62, MUTED, false);
                graphics.drawString(this.font, "so layers stay independent.", rx, this.top + 73, MUTED, false);
            } else {
                List<HologramEffect> fxList = getTargetEffects();
                List<Integer> fxIdx = effectIndices(false);
                if (fxIdx.isEmpty()) {
                    graphics.drawString(this.font, "Click an effect pill above", rx, this.top + 104, MUTED, false);
                } else {
                    int y = this.top + 95;
                    int maxCardY = this.top + PANEL_H - 32;
                    for (int n = this.fxScroll; n < fxIdx.size(); n++) {
                        HologramEffect fx = fxList.get(fxIdx.get(n));
                        int cardH = fx.type() == HologramEffect.Type.TINT ? 38 : 21;
                        if (y + cardH > maxCardY)
                            break;
                        if (fx.type() == HologramEffect.Type.TINT) {
                            int swY = y + 20;
                            int swW = (rw - (TINT_COLORS.length - 1) * 2) / TINT_COLORS.length;
                            for (int c = 0; c < TINT_COLORS.length; c++) {
                                int sx = rx + c * (swW + 2);
                                boolean sel = fx.color() == TINT_COLORS[c];
                                graphics.fill(sx, swY, sx + swW, swY + 13, 0xFF000000 | TINT_COLORS[c]);
                                graphics.renderOutline(sx, swY, swW, 13, sel ? 0xFFFFFFFF : 0x55000000);
                            }
                        }
                        y += cardH + 3;
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- input

    private boolean isInsidePanel(double mouseX, double mouseY) {
        boolean inLeft = !this.pickerCollapsed
                && mouseX >= this.leftX && mouseX < this.leftX + this.leftW
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        boolean inRight = mouseX >= this.rightX && mouseX < this.rightX + this.rightW
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        boolean inTopBar = mouseY >= 3 && mouseY <= 23;
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
        if (this.inlineBox != null && this.inlineControl != null && !this.inlineControl.hit(mouseX, mouseY))
            commitInlineBox();

        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        if (button == 0) {
            for (FlatBtn btn : this.flatBtns) {
                if (btn.enabled && btn.hit(mouseX, mouseY)) {
                    btn.onClick.run();
                    return true;
                }
            }
        }

        // TINT swatches on FX tab
        if (this.tab == TAB_FX && this.targetKind != TargetKind.ROOT && button == 0) {
            List<HologramEffect> fxList = getTargetEffects();
            int rx = this.rightX + 8;
            int rw = this.rightW - 16;
            int y = this.top + 95;
            int maxCardY = this.top + PANEL_H - 32;
            List<Integer> fxIdx = effectIndices(false);
            for (int n = this.fxScroll; n < fxIdx.size(); n++) {
                int i = fxIdx.get(n);
                HologramEffect fx = fxList.get(i);
                int cardH = fx.type() == HologramEffect.Type.TINT ? 38 : 21;
                if (y + cardH > maxCardY)
                    break;
                if (fx.type() == HologramEffect.Type.TINT) {
                    int swY = y + 20;
                    int swW = (rw - (TINT_COLORS.length - 1) * 2) / TINT_COLORS.length;
                    for (int c = 0; c < TINT_COLORS.length; c++) {
                        int sx = rx + c * (swW + 2);
                        if (mouseX >= sx && mouseX < sx + swW && mouseY >= swY && mouseY < swY + 13) {
                            setEffectColor(i, TINT_COLORS[c]);
                            return true;
                        }
                    }
                }
                y += cardH + 3;
            }
        }

        // Numeric controls
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
                this.numUndoPushed = false;
                if (ctrl.isSlider) {
                    pushUndo();
                    this.numUndoPushed = true;
                    applySlider(ctrl, mouseX);
                }
                return true;
            }
        }

        if (!this.pickerCollapsed && clickLeftPanel(mouseX, mouseY, button))
            return true;

        if (!isInsidePanel(mouseX, mouseY)) {
            setFocused(null);
            if (button == 1) {
                this.orbiting = true;
                return true;
            }
            if (button == 2) {
                this.panning = true;
                return true;
            }
            if (button == 0) {
                if (!HologramClient.previewAnchored()
                        && this.gizmo.beginDrag(mouseX, mouseY, this.width, this.height, targetWorldCenter(),
                        (float) getTargetOffsetX(), (float) getTargetOffsetY(), (float) getTargetOffsetZ(),
                        (float) getTargetYaw(), (float) getTargetPitch(), (float) getTargetRoll(),
                        (float) getTargetScale())) {
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

            BlockState state = currentBlockState();
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

        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int row = (int) ((mouseY - gridY) / LIST_ROW_H);
            int entryIdx = this.scroll + row;
            if (this.pickMode == PICK_ENTITIES && entryIdx >= 0 && entryIdx < this.filteredEntities.size()) {
                pickEntity(this.filteredEntities.get(entryIdx).id());
                return true;
            }
            if (this.pickMode == PICK_PARTICLES && entryIdx >= 0 && entryIdx < this.filteredParticles.size()) {
                pickParticle(this.filteredParticles.get(entryIdx));
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
            if (Math.abs(mouseX - this.numDragStartX) > 3.0) {
                if (!this.numDragged && !this.numUndoPushed) {
                    pushUndo();
                    this.numUndoPushed = true;
                }
                this.numDragged = true;
            }
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
            if (HologramCamera.isActive())
                HologramCamera.turn(dragX, dragY);
            else if (this.minecraft != null && this.minecraft.player != null)
                this.minecraft.player.turn(dragX * 4.0, dragY * 4.0);
            return true;
        }
        if (this.panning) {
            if (HologramCamera.isActive())
                HologramCamera.pan(dragX, dragY);
            return true;
        }
        if (this.gizmo.isDragging()) {
            this.gizmo.updateDrag(mouseX, mouseY, this.width, this.height,
                    1.0F, hasShiftDown(), new HologramGizmo.Target() {
                        @Override
                        public void setOffset(float x, float y, float z) {
                            setTargetOffsetX(x);
                            setTargetOffsetY(y);
                            setTargetOffsetZ(z);
                            refresh();
                        }

                        @Override
                        public void setRotation(float y, float p, float r) {
                            setTargetYaw(y);
                            setTargetPitch(p);
                            setTargetRoll(r);
                            refresh();
                        }

                        @Override
                        public void setScale(float s) {
                            setTargetScale(s);
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
        this.panning = false;
        if (this.activeNum != null) {
            NumControl released = this.activeNum;
            boolean wasDragged = this.numDragged;
            this.activeNum = null;
            this.numDragged = false;
            this.numUndoPushed = false;
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

        for (NumControl ctrl : this.numControls) {
            if (ctrl.hit(mouseX, mouseY)) {
                pushUndo();
                double mult = hasControlDown() ? 4.0 : (hasShiftDown() ? 0.25 : 1.0);
                double next = ctrl.get.getAsDouble() + dir * ctrl.step * mult;
                ctrl.set.accept(clamp(next, ctrl.min, ctrl.max));
                refresh();
                return true;
            }
        }

        if (mouseX >= this.rightX && mouseX < this.rightX + this.rightW
                && mouseY >= this.top + 54 && mouseY < this.top + PANEL_H - 30) {
            if (this.tab == TAB_LAYERS) {
                int max = Math.max(0, buildTreeRows().size() - 8);
                if (max > 0) {
                    this.layerScroll = Mth.clamp(this.layerScroll - dir, 0, max);
                    rebuildInspector();
                    return true;
                }
            }
            if (this.tab == TAB_FX && effectIndices(false).size() > 3) {
                this.fxScroll = Mth.clamp(this.fxScroll - dir, 0, Math.max(0, effectIndices(false).size() - 1));
                rebuildInspector();
                return true;
            }
            if (this.tab == TAB_ANIMATE && this.targetKind != TargetKind.ROOT && effectIndices(true).size() > 3) {
                this.animScroll = Mth.clamp(this.animScroll - dir, 0, Math.max(0, effectIndices(true).size() - 1));
                rebuildInspector();
                return true;
            }
        }

        if (!this.pickerCollapsed) {
            if (this.pickMode == PICK_BLOCKS) {
                BlockState state = currentBlockState();
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

            int gridX = this.leftX + 8;
            int gridY = gridTop();
            if (mouseX >= gridX && mouseX < scrollbarX() + 8
                    && mouseY >= gridY && mouseY < gridY + gridHeight()) {
                this.scroll = Mth.clamp(this.scroll - dir, 0, maxLeftScroll());
                return true;
            }
        }
        if (!isInsidePanel(mouseX, mouseY) && HologramCamera.isActive()) {
            HologramCamera.dolly(dir * (hasShiftDown() ? 0.35 : 1.0));
            return true;
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
            undo();
            return true;
        }
        if (!(getFocused() instanceof EditBox)) {
            if (keyCode == GLFW.GLFW_KEY_1) {
                this.gizmo.setMode(HologramGizmo.Mode.MOVE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_2) {
                this.gizmo.setMode(HologramGizmo.Mode.ROTATE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_3) {
                this.gizmo.setMode(HologramGizmo.Mode.SCALE);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_4) {
                this.gizmo.setMode(HologramGizmo.Mode.OFF);
                rebuildInspector();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F) {
                frameCamera();
                return true;
            }
            if (this.minecraft != null && this.minecraft.player != null) {
                if (this.minecraft.options.keyChat.matches(keyCode, scanCode)) {
                    openChat("");
                    return true;
                }
                if (this.minecraft.options.keyCommand.matches(keyCode, scanCode)) {
                    openChat("/");
                    return true;
                }
            }
            if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown()) {
                if (hasShiftDown())
                    redo();
                else
                    undo();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Y && hasControlDown()) {
                redo();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void frameCamera() {
        HologramCamera.frame(targetWorldCenter(), (float) getTargetScale());
    }

    /** Opens the normal chat box over the editor; closing or sending returns here with everything as it was. */
    private void openChat(String initial) {
        Minecraft mc = this.minecraft;
        if (mc == null)
            return;
        commitInlineBox();
        // Deferred so the key's own character event lands on this screen, not in the new chat box.
        mc.tell(() -> mc.setScreen(new HologramChatScreen(initial, this)));
    }

    // ---------------------------------------------------------------- picker content actions

    private void ensureLayerTarget() {
        if (this.targetKind != TargetKind.LAYER) {
            this.targetKind = TargetKind.LAYER;
            this.selectedLayer = Mth.clamp(this.selectedLayer, 0, Math.max(0, this.layers.size() - 1));
        }
    }

    public void pickBlock(Block block) {
        String next = BuiltInRegistries.BLOCK.getKey(block).toString();
        this.pickedBlockId = next;
        ensureLayerTarget();
        HologramLayer cur = currentLayer();
        if (cur == null)
            return;
        if (cur.kind() != HologramLayer.Kind.BLOCK || !next.equals(cur.id())) {
            pushUndo();
            updateCurrentLayer(l -> l.withContent(HologramLayer.Kind.BLOCK, next, Map.of(), new CompoundTag()));
            rebuildInspector();
            refresh();
        }
    }

    public void pickEntity(String entityId) {
        this.pickedEntityId = entityId;
        ensureLayerTarget();
        HologramLayer cur = currentLayer();
        if (cur == null)
            return;
        if (cur.kind() != HologramLayer.Kind.ENTITY || !entityId.equals(cur.id())) {
            pushUndo();
            updateCurrentLayer(l -> l.withContent(HologramLayer.Kind.ENTITY, entityId, Map.of(), new CompoundTag()));
            rebuildInspector();
            refresh();
        }
    }

    public void pickParticle(String particleId) {
        this.pickedParticleId = particleId;
        ensureLayerTarget();
        HologramLayer cur = currentLayer();
        if (cur == null)
            return;
        if (cur.kind() != HologramLayer.Kind.PARTICLE || !particleId.equals(cur.id())) {
            pushUndo();
            updateCurrentLayer(l -> l.withContent(HologramLayer.Kind.PARTICLE, particleId, Map.of(), new CompoundTag()));
            rebuildInspector();
            refresh();
        }
    }

    private void cycleProperty(Property<?> property, BlockState state, int direction) {
        if (direction == 0)
            return;
        HologramLayer cur = currentLayer();
        if (cur == null || cur.kind() != HologramLayer.Kind.BLOCK)
            return;
        pushUndo();
        String current = HologramStates.valueOf(state, property);
        String next = HologramStates.cycle(property, current, direction);
        Map<String, String> nextProps = new LinkedHashMap<>(cur.props());
        nextProps.put(property.getName(), next);
        updateCurrentLayer(l -> l.withProps(nextProps));
        refresh();
    }

    // ---------------------------------------------------------------- world center & spec building

    private Vec3 targetWorldCenter() {
        HologramSpec spec = buildSpec();
        Matrix4f m = spec.rootMatrix();
        Vector3f v = new Vector3f(0.0F, 0.5F, 0.0F);
        if (this.targetKind == TargetKind.GROUP) {
            HologramGroup g = currentGroup();
            if (g != null)
                m.mul(HologramSpec.groupMatrix(g));
        } else if (this.targetKind == TargetKind.LAYER) {
            HologramLayer l = currentLayer();
            if (l != null) {
                HologramGroup g = spec.groupById(l.groupId());
                if (g != null)
                    m.mul(HologramSpec.groupMatrix(g));
                m.mul(HologramSpec.layerMatrix(l));
                if (l.kind() == HologramLayer.Kind.PARTICLE)
                    v.set(0.0F, 0.0F, 0.0F);
            }
        }
        m.transformPosition(v);
        return new Vec3(v.x, v.y, v.z);
    }

    private static float wrap(double degrees) {
        return Mth.wrapDegrees((float) degrees);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private HologramSpec buildSpec() {
        return new HologramSpec(this.id, this.pos,
                this.rootOffsetX, this.rootOffsetY, this.rootOffsetZ,
                this.rootYaw, this.rootPitch, this.rootRoll, this.rootScale,
                true, this.renderDistance, this.owner, this.ownerName,
                this.appearRadius, this.appearSeconds, this.appearStyle,
                this.groups, this.layers);
    }

    @Nullable
    private BlockState currentBlockState() {
        HologramLayer l = currentLayer();
        if (l == null || l.kind() != HologramLayer.Kind.BLOCK)
            return null;
        return HologramStates.resolve(l.id(), l.props());
    }

    private void applyClipboard() {
        HologramSpec clip = HologramClipboard.peek();
        if (clip == null)
            return;
        pushUndo();
        loadFromSpec(clip);
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

    public void saveAndClose() {
        commitInlineBox();
        this.finalized = true;
        this.liveDirty = false;
        HologramCamera.end();
        HologramRenderer.setPaused(false);
        HologramNetwork.sendSave(buildSpec());
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    public void cancelAndClose() {
        cancelInlineBox();
        this.finalized = true;
        this.liveDirty = false;
        HologramCamera.end();
        HologramRenderer.setPaused(false);
        HologramNetwork.sendCancel(this.id);
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    public void deleteAndClose() {
        cancelInlineBox();
        this.finalized = true;
        this.liveDirty = false;
        HologramCamera.end();
        HologramRenderer.setPaused(false);
        HologramNetwork.sendDelete(this.id);
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        cancelInlineBox();
        HologramCamera.end();
        HologramRenderer.setPaused(false);
        if (!this.finalized) {
            this.finalized = true;
            this.liveDirty = false;
            HologramNetwork.sendCancel(this.id);
        }
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
        BlockState state = currentBlockState();
        return state == null ? List.of() : HologramStates.properties(state);
    }

    public void devCycleProperty(int index, int direction) {
        BlockState state = currentBlockState();
        if (state == null)
            return;
        List<Property<?>> properties = HologramStates.properties(state);
        if (index < 0 || index >= properties.size())
            return;
        cycleProperty(properties.get(index), state, direction);
    }

    public void devSetScale(float scale) {
        pushUndo();
        setTargetScale(scale);
        refresh();
    }

    public void devSetSpin(float spin) {
        pushUndo();
        setTargetSpin(spin);
        refresh();
    }

    public void devSetOffset(float x, float y, float z) {
        pushUndo();
        setTargetOffsetX(x);
        setTargetOffsetY(y);
        setTargetOffsetZ(z);
        rebuildInspector();
        refresh();
    }

    public void devSetAppearRadius(float radius) {
        pushUndo();
        this.appearRadius = (float) clamp(radius, 0.0, 64.0);
        refresh();
    }

    public void devLeftMode(int mode) {
        setPickMode(mode);
    }

    public int devSearch(String query) {
        if (this.search != null)
            this.search.setValue(query);
        refreshFilter();
        return switch (this.pickMode) {
            case PICK_ENTITIES -> this.filteredEntities.size();
            case PICK_PARTICLES -> this.filteredParticles.size();
            default -> this.filteredBlocks.size();
        };
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
        this.tab = Mth.clamp(tab, TAB_LAYERS, TAB_FX);
        rebuildInspector();
    }

    public int devNumControlCount() {
        return this.numControls.size();
    }

    public void devSelectRoot() {
        selectRoot();
    }

    public void devSelectLayer(int idx) {
        selectLayer(idx);
    }

    public void devSelectGroup(String gid) {
        selectGroup(gid);
    }

    public String devAddGroup() {
        addGroup();
        return this.selectedGroupId == null ? "" : this.selectedGroupId;
    }

    public void devAssignLayerGroup(int layerIdx, String groupId) {
        if (layerIdx < 0 || layerIdx >= this.layers.size())
            return;
        pushUndo();
        this.layers.set(layerIdx, this.layers.get(layerIdx).withGroupId(groupId));
        rebuildInspector();
        refresh();
    }

    /** Clicks the first enabled button with this label through the real mouse path. Dev check only. */
    public boolean devClick(String label) {
        for (FlatBtn btn : this.flatBtns) {
            if (btn.enabled && btn.label.equals(label))
                return mouseClicked(btn.x + btn.w / 2.0, btn.y + btn.h / 2.0, 0);
        }
        return false;
    }

    public boolean devHasButton(String label) {
        for (FlatBtn btn : this.flatBtns) {
            if (btn.label.equals(label))
                return true;
        }
        return false;
    }

    public List<HologramEffect> devEffects() {
        return List.copyOf(getTargetEffects());
    }

    public void devCycleTrigger(int index) {
        cycleEffectTrigger(index);
    }

    public void devSetEffectPhase(int index, float phase) {
        setEffectPhase(index, phase);
    }

    public void devKey(int keyCode) {
        keyPressed(keyCode, 0, 0);
    }

    public void devToggleSoloLayer(int idx) {
        toggleSoloLayer(idx);
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
        pickEntity(id);
    }

    public void devAddLayer(HologramLayer.Kind kind, String id) {
        if (this.layers.size() >= HologramLayer.MAX_LAYERS)
            return;
        pushUndo();
        HologramLayer layer = switch (kind) {
            case ENTITY -> HologramLayer.entity(id, 1.0F);
            case PARTICLE -> HologramLayer.particle(id, 1.5F);
            case BLOCK -> HologramLayer.block(id, Map.of(), 1.0F);
        };
        this.layers.add(layer);
        selectLayer(this.layers.size() - 1);
        refresh();
    }

    public void devFlushLive() {
        flushLivePreview();
    }

    public int devLayerCount() {
        return this.layers.size();
    }

    public int devGridCols() {
        return this.gridCols;
    }

    public int devFreeWidth() {
        int leftEdge = this.pickerCollapsed ? this.leftX + 64 : this.leftX + this.leftW;
        return this.rightX - leftEdge;
    }
}
