package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramOpenPayload;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;

/**
 * The hologram editor: two small panels pinned to the screen edges, with the middle left clear so the
 * hologram itself stays visible while you work.
 *
 * <p>Why it looks like this:
 *
 * <ul>
 * <li><b>Two panels, not one.</b> A single window wide enough for a picker plus every control was
 * covering half the screen at a normal GUI scale. Picking content (blocks, entities, particles) lives
 * on the left, values on the right.
 * <li><b>No blurred menu background.</b> Vanilla's screen background blurs the world, which defeats the
 * point - the preview is the real hologram in the real world.
 * <li><b>3D viewport controls.</b> Right-drag (or left-drag empty space) orbits the player camera while
 * the GUI stays open, and 3D Move/Rotate/Scale gizmos let you position the hologram directly in world.
 * <li><b>Every number takes the mouse wheel</b>, and one Step multiplier scales all of them, because the
 * natural increment differs per field (size 0.25, angles 15 degrees, range 8 blocks).
 * </ul>
 */
public class HologramScreen extends Screen {
    private static final int PANEL_H = 278;
    private static final int CELL = 18;
    private static final int GRID_ROWS = 5;
    private static final int LIST_ROW_H = 15;
    private static final int LIST_VISIBLE = 6; // 6 * 15 = 90 == GRID_ROWS * CELL
    private static final int PROP_ROWS = 6;
    private static final int PROP_PITCH = 16;
    private static final int ROW_H = 23;
    private static final int MAX_EFFECTS = 4;

    private static final float[] STEPS = { 1.0F, 2.0F, 5.0F, 10.0F };

    private static final int BG = 0xEE0B0D12;
    private static final int BORDER = 0x66FFFFFF;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF9AA5B4;
    private static final int ACCENT = 0xFF66E0FF;
    private static final int ROW_BG = 0x40203A4A;
    private static final int ROW_BG_HOT = 0x60406A80;
    private static final int DIM = 0x22000000;

    private static final int TAB_LOOK = 0;
    private static final int TAB_MOVE = 1;
    private static final int TAB_FX = 2;
    private static final int TAB_PARTS = 3;
    private static final int TAB_SHARE = 4;
    private static final int TAB_COUNT = 5;

    private static final int LEFT_BLOCKS = 0;
    private static final int LEFT_ENTITIES = 1;
    private static final int LEFT_PARTICLES = 2;

    private static List<Block> ALL_BLOCKS;
    private static List<EntityEntry> ALL_ENTITIES;
    private static List<String> ALL_PARTICLES;

    private record EntityEntry(String id, String displayName, ItemStack icon) {
    }

    /** Snapshot for Ctrl+Z undo of gizmo drags. */
    private record TransformSnapshot(float ox, float oy, float oz, float yaw, float pitch, float roll, float scale) {
    }

    /** A row that behaves like a slider: the wheel and the two buttons all move it by {@code step}. */
    private record NumRow(String label, DoubleSupplier get, DoubleConsumer set, double step,
                          DoubleFunction<String> format, @Nullable Runnable reset) {
        NumRow(String label, DoubleSupplier get, DoubleConsumer set, double step, DoubleFunction<String> format) {
            this(label, get, set, step, format, null);
        }
    }

    /** A row that flips. */
    private record ToggleRow(String label, BooleanSupplier get, Runnable toggle) {
    }

    /** A row that is a button: copies, pastes. {@code available} gates it, {@code hint} is the right side. */
    private record ActionRow(String label, Runnable action, BooleanSupplier available,
                             java.util.function.Supplier<String> hint) {
    }

    /** One effect layer: the wheel changes its strength, right-click removes it. */
    private record EffectRow(int index, HologramEffect effect) {
    }

    /** The "add content" row: wheel picks the next entry, click adds it, right-click changes the kind. */
    private record AddRow() {
    }

    /** One content part: wheel moves it vertically, click selects it for fine editing, right-click removes it. */
    private record PartRow(int index, HologramPart part) {
    }

    private final String id;
    private final BlockPos pos;
    private final int maxRenderDistance;
    @Nullable
    private final UUID owner;
    private final String ownerName;

    private float offsetX;
    private float offsetY;
    private float offsetZ;
    private String block;
    private Map<String, String> props;

    private float scale;
    private float yaw;
    private float pitch;
    private float roll;
    private float spin;
    private int spinAxis;
    private float hover;
    private float hoverPeriod;
    private boolean fullbright;
    private int renderDistance;
    private List<HologramEffect> effects;
    private List<HologramPart> parts;
    private CompoundTag data;
    private int addKind;
    private int pickIndex;
    private int selectedPart = -1;
    private int selectedEffect = -1;
    private float appearRadius;
    private float appearSeconds;

    private int tab = TAB_LOOK;
    private int leftMode = LEFT_BLOCKS;
    private int stepIndex;
    private int scroll;
    private boolean draggingScrollbar;

    private String selectedEntityId = "";
    private String selectedParticleId = "minecraft:end_rod";
    private long lastListClickTime;
    private String lastListClickId = "";

    private final HologramGizmo gizmo = new HologramGizmo();
    private final Deque<TransformSnapshot> undoStack = new ArrayDeque<>();
    private boolean orbiting;

    private int blockLeft;
    private int adjustLeft;
    private int top;
    private int gridCols = 9;
    private int adjustPanelWidth = 200;

    private EditBox search;
    private Button stepButton;
    private Button[] gizmoButtons;

    private List<Block> filteredBlocks = List.of();
    private List<EntityEntry> filteredEntities = List.of();
    private List<String> filteredParticles = List.of();
    private List<Object> rows = List.of();

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

    private int blockWidth() {
        return this.gridCols * CELL + 24;
    }

    private int adjustWidth() {
        return this.adjustPanelWidth;
    }

    private int tabWidth() {
        return (this.adjustPanelWidth - 20 - (TAB_COUNT - 1) * 4) / TAB_COUNT;
    }

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
        return this.blockLeft + 10 + gridWidth() + 3;
    }

    @Override
    protected void init() {
        // Two panels hug the edges and the middle stays free for the hologram. Both panels and the
        // grid's column count come from the screen width, so the gap survives a high GUI scale: at
        // 640x360 (scale 3) the gap is ~200px, at 467 wide (scale 4) still ~100px.
        int gap = Math.max(90, this.width * 16 / 100);
        int remaining = Math.max(240, this.width - 16 - gap);
        this.adjustPanelWidth = Mth.clamp((int) (remaining * 0.56), 150, 236);
        int blockTarget = remaining - this.adjustPanelWidth;
        this.gridCols = Mth.clamp((blockTarget - 24) / CELL, 4, 9);
        int adjustWidth = this.adjustPanelWidth;

        this.blockLeft = 8;
        this.adjustLeft = this.width - adjustWidth - 8;
        this.top = Math.max(6, (this.height - PANEL_H) / 2);

        // Left panel mode tabs: Block / Entity / Partcl
        int leftTabW = (blockWidth() - 20 - 6) / 3;
        String[] leftLabels = { "Block", "Entity", "Partcl" };
        for (int i = 0; i < 3; i++) {
            int mode = i;
            addRenderableWidget(Button.builder(Component.literal(leftLabels[i]), b -> setLeftMode(mode))
                    .bounds(this.blockLeft + 10 + i * (leftTabW + 3), this.top + 18, leftTabW, 15).build());
        }

        this.search = new EditBox(this.font, this.blockLeft + 10, this.top + 36, blockWidth() - 20, 14,
                Component.literal("Search"));
        updateSearchHint();
        this.search.setResponder(text -> {
            this.scroll = 0;
            refreshFilter();
        });
        addRenderableWidget(this.search);

        // Top-center compact gizmo mode toolbar in the open viewport
        HologramGizmo.Mode[] modes = HologramGizmo.Mode.values();
        int gBtnW = 40;
        int totalGW = modes.length * (gBtnW + 2) + 46;
        int gx = (this.width - totalGW) / 2;
        this.gizmoButtons = new Button[modes.length];
        for (int i = 0; i < modes.length; i++) {
            HologramGizmo.Mode m = modes[i];
            this.gizmoButtons[i] = addRenderableWidget(Button.builder(Component.literal(m.label), b -> {
                this.gizmo.setMode(m);
                updateGizmoButtonLabels();
            }).bounds(gx + i * (gBtnW + 2), 6, gBtnW, 16).build());
        }
        addRenderableWidget(Button.builder(Component.literal("Zero"), b -> {
            pushUndo();
            this.offsetX = 0.0F;
            this.offsetY = 0.0F;
            this.offsetZ = 0.0F;
            refresh();
        }).bounds(gx + modes.length * (gBtnW + 2) + 4, 6, 42, 16).build());
        updateGizmoButtonLabels();

        // Right panel tabs
        String[] labels = { "Look", "Move", "FX", "Parts", "Share" };
        int tabWidth = tabWidth();
        for (int i = 0; i < labels.length; i++) {
            int index = i;
            addRenderableWidget(Button.builder(Component.literal(labels[i]), b -> {
                this.tab = index;
                rebuildRows();
            }).bounds(this.adjustLeft + 10 + i * (tabWidth + 4), this.top + 20, tabWidth, 18).build());
        }

        this.stepButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            this.stepIndex = (this.stepIndex + 1) % STEPS.length;
            updateLabels();
        }).bounds(this.adjustLeft + 10, this.top + PANEL_H - 50, adjustWidth - 20, 18).build());

        int half = (adjustWidth - 20 - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
                .bounds(this.adjustLeft + 10, this.top + PANEL_H - 26, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteAndClose())
                .bounds(this.adjustLeft + 10 + half + 4, this.top + PANEL_H - 26, half, 20).build());

        if (this.selectedEntityId.isEmpty() && !allEntities().isEmpty())
            this.selectedEntityId = allEntities().get(0).id();

        refreshFilter();
        rebuildRows();
        refresh();
    }

    private void setLeftMode(int mode) {
        this.leftMode = Mth.clamp(mode, LEFT_BLOCKS, LEFT_PARTICLES);
        this.scroll = 0;
        this.draggingScrollbar = false;
        if (this.search != null) {
            this.search.setValue("");
            updateSearchHint();
        }
        if (this.leftMode == LEFT_ENTITIES)
            this.addKind = 1;
        else if (this.leftMode == LEFT_PARTICLES)
            this.addKind = 2;
        refreshFilter();
        rebuildRows();
    }

    private void updateSearchHint() {
        if (this.search == null)
            return;
        String hint = switch (this.leftMode) {
            case LEFT_ENTITIES -> "search entities...";
            case LEFT_PARTICLES -> "search particles...";
            default -> "search blocks...";
        };
        this.search.setHint(Component.literal(hint).withStyle(style -> style.withColor(MUTED)));
    }

    private void updateGizmoButtonLabels() {
        if (this.gizmoButtons == null)
            return;
        HologramGizmo.Mode[] modes = HologramGizmo.Mode.values();
        for (int i = 0; i < modes.length; i++) {
            boolean active = this.gizmo.mode() == modes[i];
            String text = active ? "[" + modes[i].label + "]" : modes[i].label;
            this.gizmoButtons[i].setMessage(Component.literal(text));
        }
    }

    private void rebuildRows() {
        List<Object> built = new ArrayList<>();
        if (this.tab == TAB_LOOK) {
            built.add(new NumRow("Size", () -> this.scale, v -> this.scale = (float) clamp(v, 0.25, 8.0), 0.25,
                    v -> String.format(Locale.ROOT, "%.2fx", v)));
            built.add(new NumRow("Yaw", () -> this.yaw, v -> this.yaw = wrap(v), 15.0, HologramScreen::fmtDegrees,
                    () -> this.yaw = 0.0F));
            built.add(new NumRow("Pitch", () -> this.pitch, v -> this.pitch = wrap(v), 15.0, HologramScreen::fmtDegrees,
                    () -> this.pitch = 0.0F));
            built.add(new NumRow("Roll", () -> this.roll, v -> this.roll = wrap(v), 15.0, HologramScreen::fmtDegrees,
                    () -> this.roll = 0.0F));
            built.add(new ToggleRow("Glow", () -> this.fullbright, () -> {
                this.fullbright = !this.fullbright;
                refresh();
            }));
            built.add(new NumRow("Range", () -> this.renderDistance,
                    v -> this.renderDistance = (int) clamp(Math.round(v), 8, this.maxRenderDistance), 8.0,
                    v -> (int) v + " blocks"));
        } else if (this.tab == TAB_MOVE) {
            built.add(new NumRow("Offset X", () -> this.offsetX, v -> this.offsetX = (float) clamp(v, -16.0, 16.0),
                    0.25, v -> String.format(Locale.ROOT, "%.2f", v), () -> this.offsetX = 0.0F));
            built.add(new NumRow("Offset Y", () -> this.offsetY, v -> this.offsetY = (float) clamp(v, -16.0, 16.0),
                    0.25, v -> String.format(Locale.ROOT, "%.2f", v), () -> this.offsetY = 0.0F));
            built.add(new NumRow("Offset Z", () -> this.offsetZ, v -> this.offsetZ = (float) clamp(v, -16.0, 16.0),
                    0.25, v -> String.format(Locale.ROOT, "%.2f", v), () -> this.offsetZ = 0.0F));
            built.add(new NumRow("Spin", () -> this.spin, v -> this.spin = (float) clamp(v, -360.0, 360.0),
                    15.0, v -> v == 0.0 ? "off" : String.format(Locale.ROOT, "%.0f deg/s", v),
                    () -> this.spin = 0.0F));
            built.add(new NumRow("Axis", () -> this.spinAxis,
                    v -> this.spinAxis = (int) Mth.clamp(Math.round(v), 0L, 2L), 1.0,
                    v -> switch ((int) v) {
                        case HologramSpec.AXIS_X -> "X";
                        case HologramSpec.AXIS_Z -> "Z";
                        default -> "Y";
                    }));
            built.add(new NumRow("Hover", () -> this.hover, v -> this.hover = (float) clamp(v, 0.0, 4.0),
                    0.25, v -> v <= 0.0 ? "off" : String.format(Locale.ROOT, "%.2f", v),
                    () -> this.hover = 0.0F));
            built.add(new NumRow("Period", () -> this.hoverPeriod,
                    v -> this.hoverPeriod = (float) clamp(v, 0.5, 120.0), 1.0,
                    v -> String.format(Locale.ROOT, "%.1f s", v)));
        } else if (this.tab == TAB_PARTS) {
            built.add(new AddRow());
            for (int i = 0; i < this.parts.size(); i++)
                built.add(new PartRow(i, this.parts.get(i)));
            if (this.selectedPart >= this.parts.size())
                this.selectedPart = this.parts.size() - 1;
        } else if (this.tab == TAB_FX) {
            built.add(new ActionRow("Add effect", this::addEffect, () -> this.effects.size() < MAX_EFFECTS,
                    () -> this.effects.size() < MAX_EFFECTS ? "layer" : "max " + MAX_EFFECTS));
            for (int i = 0; i < this.effects.size(); i++)
                built.add(new EffectRow(i, this.effects.get(i)));
            if (this.selectedEffect >= this.effects.size())
                this.selectedEffect = this.effects.size() - 1;
            if (this.selectedEffect < 0 && !this.effects.isEmpty())
                this.selectedEffect = 0;
            built.add(new NumRow("Appear radius", () -> this.appearRadius,
                    v -> this.appearRadius = (float) clamp(v, 0.0, 64.0), 2.0,
                    v -> v <= 0.0 ? "always on" : String.format(Locale.ROOT, "%.0f blocks", v),
                    () -> this.appearRadius = 0.0F));
        } else {
            built.add(new ActionRow("Copy settings", () -> HologramClipboard.copy(buildSpec()), () -> true,
                    () -> "to clipboard"));
            built.add(new ActionRow("Paste settings", this::applyClipboard, HologramClipboard::has,
                    () -> HologramClipboard.has() ? HologramClipboard.label() : "clipboard empty"));
            built.add(new ActionRow("Paste as new", this::duplicateBesideMe, HologramClipboard::has,
                    () -> "in front of you"));
            built.add(new ToggleRow("Preview in front", HologramClient::previewAnchored, () -> {
                HologramClient.setPreviewAnchored(!HologramClient.previewAnchored());
                refresh();
            }));
            built.add(new ActionRow("Re-capture BE data", () -> HologramNetwork.sendRecapture(this.id), () -> true,
                    () -> this.data.isEmpty() ? "none" : this.data.sizeInBytes() + " B"));
            built.add(new ActionRow("Clear BE data", () -> {
                this.data = new CompoundTag();
                rebuildRows();
                refresh();
            }, () -> !this.data.isEmpty(), () -> this.data.isEmpty() ? "empty" : "clear"));
        }
        this.rows = List.copyOf(built);
        updateLabels();
    }

    private static final int[] TINT_COLORS = {
            0x66E0FF, 0xFF4D5A, 0x5CE67A, 0xFFD24D, 0xB86BFF, 0xFF6EC7, 0xFFFFFF, 0xFF8C3B, 0x4D88FF
    };
    private static final String[] TINT_NAMES = {
            "Cyan", "Red", "Green", "Gold", "Purple", "Pink", "White", "Orange", "Blue"
    };

    private void addEffect() {
        if (this.effects.size() >= MAX_EFFECTS)
            return;
        this.effects = new ArrayList<>(this.effects);
        this.effects.add(HologramEffect.of(HologramEffect.nextType(this.effects)));
        this.selectedEffect = this.effects.size() - 1;
        rebuildRows();
        refresh();
    }

    private void removeEffect(int index) {
        if (index < 0 || index >= this.effects.size())
            return;
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.remove(index);
        this.effects = next;
        if (this.selectedEffect >= next.size())
            this.selectedEffect = next.size() - 1;
        rebuildRows();
        refresh();
    }

    private void setEffectStrength(int index, float strength) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), strength, old.speed(), old.color()));
        this.effects = next;
        rebuildRows();
        refresh();
    }

    private void setEffectSpeed(int index, float speed) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), old.strength(), speed, old.color()));
        this.effects = next;
        rebuildRows();
        refresh();
    }

    private void cycleEffectColor(int index, int direction) {
        if (index < 0 || index >= this.effects.size())
            return;
        HologramEffect old = this.effects.get(index);
        int cur = 0;
        for (int i = 0; i < TINT_COLORS.length; i++) {
            if (TINT_COLORS[i] == old.color()) {
                cur = i;
                break;
            }
        }
        int nextIdx = Math.floorMod(cur + (direction >= 0 ? 1 : -1), TINT_COLORS.length);
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.set(index, new HologramEffect(old.type(), old.strength(), old.speed(), TINT_COLORS[nextIdx]));
        this.effects = next;
        rebuildRows();
        refresh();
    }

    private static String colorName(int rgb) {
        for (int i = 0; i < TINT_COLORS.length; i++) {
            if (TINT_COLORS[i] == rgb)
                return TINT_NAMES[i];
        }
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    // ---------------------------------------------------------------- content parts

    private static final String[] ADD_KINDS = { "block", "entity", "particle" };

    private static List<EntityEntry> allEntities() {
        if (ALL_ENTITIES == null) {
            List<EntityEntry> list = new ArrayList<>();
            for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
                ResourceLocation key = entry.getKey().location();
                EntityType<?> type = entry.getValue();
                String id = key.toString();
                String name = type.getDescription().getString();
                Item egg = SpawnEggItem.byId(type);
                ItemStack icon = egg != null ? new ItemStack(egg) : ItemStack.EMPTY;
                list.add(new EntityEntry(id, name, icon));
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

    private String pickedEntity() {
        if (!this.selectedEntityId.isEmpty())
            return this.selectedEntityId;
        List<EntityEntry> ids = allEntities();
        return ids.isEmpty() ? "" : ids.get(Math.floorMod(this.pickIndex, ids.size())).id();
    }

    private String pickedParticle() {
        if (!this.selectedParticleId.isEmpty())
            return this.selectedParticleId;
        List<String> ids = allParticles();
        return ids.isEmpty() ? "minecraft:crit" : ids.get(Math.floorMod(this.pickIndex, ids.size()));
    }

    private String addDescription() {
        return switch (this.addKind) {
            case 1 -> "entity " + shortId(pickedEntity());
            case 2 -> "particle " + shortId(pickedParticle());
            default -> "block " + shortId(this.block);
        };
    }

    private static String shortId(String id) {
        int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private void cycleAddKind() {
        this.addKind = (this.addKind + 1) % ADD_KINDS.length;
        rebuildRows();
    }

    private void cyclePick(int direction) {
        if (this.addKind == 1) {
            List<EntityEntry> ids = allEntities();
            if (!ids.isEmpty()) {
                this.pickIndex = Math.floorMod(this.pickIndex + direction, ids.size());
                this.selectedEntityId = ids.get(this.pickIndex).id();
            }
        } else if (this.addKind == 2) {
            List<String> ids = allParticles();
            if (!ids.isEmpty()) {
                this.pickIndex = Math.floorMod(this.pickIndex + direction, ids.size());
                this.selectedParticleId = ids.get(this.pickIndex);
            }
        }
        rebuildRows();
    }

    private void addPart() {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;

        // New parts start on top of the base, not inside it: a 2.5x hologram swallows anything placed at y=1.
        float topY = this.scale;
        HologramPart part = switch (this.addKind) {
            case 1 -> HologramPart.entity(pickedEntity(), topY);
            case 2 -> HologramPart.particle(pickedParticle(), topY);
            default -> HologramPart.block(this.block, this.props, topY);
        };

        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        this.selectedPart = next.size() - 1;
        rebuildRows();
        refresh();
    }

    private void addSpecificPart(HologramPart part) {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        this.selectedPart = next.size() - 1;
        this.tab = TAB_PARTS;
        rebuildRows();
        refresh();
    }

    private void adjustPart(int index, float dx, float dy, float dz, float dScale, float dYaw) {
        if (index < 0 || index >= this.parts.size())
            return;
        HologramPart old = this.parts.get(index);
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.set(index, new HologramPart(old.kind(), old.id(), old.props(),
                old.offsetX() + dx, old.offsetY() + dy, old.offsetZ() + dz,
                old.scale() + dScale, old.yaw() + dYaw));
        this.parts = next;
        rebuildRows();
        refresh();
    }

    private void removePart(int index) {
        if (index < 0 || index >= this.parts.size())
            return;
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.remove(index);
        this.parts = next;
        if (this.selectedPart >= next.size())
            this.selectedPart = next.size() - 1;
        rebuildRows();
        refresh();
    }

    private void updateLabels() {
        if (this.stepButton == null)
            return;
        this.stepButton.setMessage(Component.literal(String.format(Locale.ROOT,
                "Step  x%.0f  (wheel / - +)", STEPS[this.stepIndex])));
    }

    private int rowY(int index) {
        return this.top + 42 + index * ROW_H;
    }

    // ---------------------------------------------------------------- state & undo

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

    private static String fmtDegrees(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }

    /** Pushes the edited copy to the client list so the world renderer draws it behind the GUI. */
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

    private void refreshFilter() {
        String query = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        if (this.leftMode == LEFT_BLOCKS) {
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
        } else if (this.leftMode == LEFT_ENTITIES) {
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
        if (this.leftMode == LEFT_BLOCKS)
            return Math.max(0, (this.filteredBlocks.size() + this.gridCols - 1) / this.gridCols - GRID_ROWS);
        if (this.leftMode == LEFT_ENTITIES)
            return Math.max(0, this.filteredEntities.size() - LIST_VISIBLE);
        return Math.max(0, this.filteredParticles.size() - LIST_VISIBLE);
    }

    private int leftTotalRows() {
        if (this.leftMode == LEFT_BLOCKS)
            return Math.max(1, (this.filteredBlocks.size() + this.gridCols - 1) / this.gridCols);
        if (this.leftMode == LEFT_ENTITIES)
            return Math.max(1, this.filteredEntities.size());
        return Math.max(1, this.filteredParticles.size());
    }

    private int leftVisibleRows() {
        return this.leftMode == LEFT_BLOCKS ? GRID_ROWS : LIST_VISIBLE;
    }

    private static List<Block> allBlocks() {
        if (ALL_BLOCKS == null) {
            ALL_BLOCKS = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.asItem() != Items.AIR)
                    // Include MODEL, ENTITYBLOCK_ANIMATED, and any EntityBlock (e.g. flags, sliding
                    // doors, banners, beds, modded machines whose RenderShape is INVISIBLE because
                    // they draw entirely through a BlockEntityRenderer).
                    .filter(block -> block.defaultBlockState().getRenderShape() == RenderShape.MODEL
                            || block.defaultBlockState().getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED
                            || block instanceof EntityBlock)
                    .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                    .toList();
        }
        return ALL_BLOCKS;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Only a light tint. The point of this screen is the world behind it, and vanilla's blurred
        // background makes it unreadable - do not reinstate it.
        graphics.fill(0, 0, this.width, this.height, DIM);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);

        if (!HologramClient.previewAnchored())
            this.gizmo.render(graphics, this.font, mouseX, mouseY, this.width, this.height,
                    hologramWorldCenter(), this.scale);

        renderLeftPanel(graphics, mouseX, mouseY);
        renderAdjustPanel(graphics, mouseX, mouseY);

        // Subtle viewport hint at bottom-center
        String hint = "Right-drag: look  |  G Move  R Rotate  S Scale  |  Shift: free";
        int hintW = this.font.width(hint);
        int hintX = (this.width - hintW) / 2;
        int hintY = this.height - 14;
        graphics.fill(hintX - 6, hintY - 2, hintX + hintW + 6, hintY + 10, 0xAA0B0D12);
        graphics.drawString(this.font, hint, hintX, hintY, MUTED, false);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, String title) {
        graphics.fill(x, y, x + width, y + PANEL_H, BG);
        graphics.renderOutline(x, y, width, PANEL_H, BORDER);
        graphics.drawString(this.font, title, x + 10, y + 6, ACCENT, false);
    }

    private void renderLeftPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = blockWidth();
        String title = switch (this.leftMode) {
            case LEFT_ENTITIES -> "Entities";
            case LEFT_PARTICLES -> "Particles";
            default -> "Block";
        };
        panel(graphics, this.blockLeft, this.top, width, title);

        // Active mode underline
        int leftTabW = (width - 20 - 6) / 3;
        int activeX = this.blockLeft + 10 + this.leftMode * (leftTabW + 3);
        graphics.fill(activeX, this.top + 33, activeX + leftTabW, this.top + 35, ACCENT);

        int gridX = this.blockLeft + 10;
        int gridY = gridTop();
        int gridW = gridWidth();
        int gridH = gridHeight();
        int maxScroll = maxLeftScroll();
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        if (this.leftMode == LEFT_BLOCKS) {
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
                if (selected)
                    graphics.fill(x, y, x + CELL, y + CELL, 0x80406A80);
                else if (hot)
                    graphics.fill(x, y, x + CELL, y + CELL, ROW_BG);

                graphics.renderItem(new ItemStack(entryBlock.asItem()), x + 1, y + 1);
            }
        } else if (this.leftMode == LEFT_ENTITIES) {
            for (int row = 0; row < LIST_VISIBLE; row++) {
                int entryIdx = this.scroll + row;
                if (entryIdx >= this.filteredEntities.size())
                    break;
                EntityEntry e = this.filteredEntities.get(entryIdx);
                int y = gridY + row * LIST_ROW_H;
                boolean hot = mouseX >= gridX && mouseX < gridX + gridW && mouseY >= y && mouseY < y + LIST_ROW_H - 1;
                boolean selected = e.id().equals(this.selectedEntityId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? 0x80406A80 : (hot ? ROW_BG_HOT : ROW_BG));
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
                boolean selected = p.equals(this.selectedParticleId);
                graphics.fill(gridX, y, gridX + gridW, y + LIST_ROW_H - 1,
                        selected ? 0x80406A80 : (hot ? ROW_BG_HOT : ROW_BG));
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
            graphics.fill(barX, gridY, barX + 4, gridY + gridH, 0x35FFFFFF);
            graphics.fill(barX, barY, barX + 4, barY + barHeight, barHot ? 0xFF66E0FF : 0xAA66E0FF);
        }

        int infoY = gridY + gridH + 6;
        if (this.leftMode == LEFT_BLOCKS) {
            Block currentBlock = HologramStates.blockOf(this.block);
            String name = currentBlock == null ? "missing block" : currentBlock.getName().getString();
            graphics.drawString(this.font, this.font.plainSubstrByWidth(name, width - 20), this.blockLeft + 10, infoY,
                    TEXT, false);

            BlockState state = currentState();
            if (state == null)
                return;

            List<Property<?>> properties = HologramStates.properties(state);
            for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                Property<?> property = properties.get(i);
                int y = infoY + 13 + i * PROP_PITCH;
                boolean hot = mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                        && mouseY >= y && mouseY < y + PROP_PITCH - 2;
                graphics.fill(this.blockLeft + 8, y, this.blockLeft + width - 8, y + PROP_PITCH - 2,
                        hot ? ROW_BG_HOT : ROW_BG);
                graphics.drawString(this.font, property.getName(), this.blockLeft + 12, y + 4, MUTED, false);
                String value = HologramStates.valueOf(state, property);
                int valueWidth = this.font.width(value);
                graphics.drawString(this.font, value, this.blockLeft + width - 12 - valueWidth, y + 4, ACCENT, false);
            }
        } else {
            String picked = this.leftMode == LEFT_ENTITIES ? this.selectedEntityId : this.selectedParticleId;
            graphics.drawString(this.font, this.font.plainSubstrByWidth(picked, width - 20),
                    this.blockLeft + 10, infoY, MUTED, false);

            int btnY = infoY + 14;
            int btnH = 20;
            boolean canAdd = this.parts.size() < HologramPart.MAX_PARTS && !picked.isEmpty();
            boolean hot = canAdd && mouseX >= this.blockLeft + 10 && mouseX < this.blockLeft + width - 10
                    && mouseY >= btnY && mouseY < btnY + btnH;
            graphics.fill(this.blockLeft + 10, btnY, this.blockLeft + width - 10, btnY + btnH,
                    canAdd ? (hot ? ROW_BG_HOT : ROW_BG) : 0x25203A4A);
            graphics.renderOutline(this.blockLeft + 10, btnY, width - 20, btnH, canAdd ? ACCENT : 0x44FFFFFF);
            String addLabel = canAdd
                    ? (this.leftMode == LEFT_ENTITIES ? "+ Add Entity Part" : "+ Add Particle Part")
                    : "Max " + HologramPart.MAX_PARTS + " parts";
            graphics.drawString(this.font, addLabel,
                    this.blockLeft + width / 2 - this.font.width(addLabel) / 2, btnY + 6,
                    canAdd ? TEXT : MUTED, false);

            graphics.drawString(this.font, "Double-click entry to add",
                    this.blockLeft + 10, btnY + 26, MUTED, false);
            graphics.drawString(this.font, "Parts: " + this.parts.size() + " / " + HologramPart.MAX_PARTS,
                    this.blockLeft + 10, btnY + 38, MUTED, false);
        }
    }

    private void renderAdjustPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = adjustWidth();
        panel(graphics, this.adjustLeft, this.top, width, "Adjust");

        int tabX = this.adjustLeft + 10 + this.tab * (tabWidth() + 4);
        graphics.fill(tabX, this.top + 38, tabX + tabWidth(), this.top + 40, ACCENT);

        for (int i = 0; i < this.rows.size(); i++) {
            int y = rowY(i);
            boolean hot = isRowHot(mouseX, mouseY, i);
            Object row = this.rows.get(i);
            boolean selectedRow = (row instanceof PartRow pr && pr.index() == this.selectedPart)
                    || (row instanceof EffectRow er && er.index() == this.selectedEffect);
            graphics.fill(this.adjustLeft + 10, y, this.adjustLeft + width - 10, y + ROW_H - 2,
                    selectedRow ? 0x7030607A : (hot ? ROW_BG_HOT : ROW_BG));

            int right = this.adjustLeft + width - 10;
            if (row instanceof NumRow num) {
                graphics.drawString(this.font, num.label(), this.adjustLeft + 15, y + 7, TEXT, false);
                String value = num.format().apply(num.get().getAsDouble());
                graphics.drawString(this.font, value, right - 54 - this.font.width(value), y + 7, ACCENT, false);
                drawSmallButton(graphics, right - 48, y + 2, "-", mouseX, mouseY);
                drawSmallButton(graphics, right - 24, y + 2, "+", mouseX, mouseY);
            } else if (row instanceof ToggleRow toggle) {
                boolean on = toggle.get().getAsBoolean();
                graphics.drawString(this.font, toggle.label(), this.adjustLeft + 15, y + 7, TEXT, false);
                String value = on ? "on" : "off";
                graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 7,
                        on ? ACCENT : MUTED, false);
            } else if (row instanceof ActionRow action) {
                boolean available = action.available().getAsBoolean();
                graphics.drawString(this.font, action.label(), this.adjustLeft + 15, y + 7,
                        available ? TEXT : MUTED, false);
                String hint = action.hint().get();
                graphics.drawString(this.font, hint, right - 5 - this.font.width(hint), y + 7,
                        available ? ACCENT : MUTED, false);
            } else if (row instanceof EffectRow effectRow) {
                HologramEffect fx = effectRow.effect();
                String label = fx.label();
                graphics.drawString(this.font, label, this.adjustLeft + 15, y + 7, TEXT, false);
                if (fx.type() == HologramEffect.Type.TINT) {
                    String value = String.format(Locale.ROOT, "%.2f", fx.strength());
                    int vx = right - 5 - this.font.width(value);
                    graphics.drawString(this.font, value, vx, y + 7, ACCENT, false);
                    graphics.fill(vx - 14, y + 6, vx - 4, y + 16, 0xFF000000 | fx.color());
                    graphics.renderOutline(vx - 14, y + 6, 10, 10, BORDER);
                } else {
                    String value = String.format(Locale.ROOT, "%.2f  %.1fHz", fx.strength(), fx.speed());
                    graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 7, ACCENT, false);
                }
            } else if (row instanceof AddRow) {
                String label = this.font.plainSubstrByWidth("Add " + addDescription(), width - 64);
                graphics.drawString(this.font, label, this.adjustLeft + 15, y + 7, TEXT, false);
                String hint = this.parts.size() >= HologramPart.MAX_PARTS ? "max" : "click";
                graphics.drawString(this.font, hint, right - 5 - this.font.width(hint), y + 7,
                        this.parts.size() >= HologramPart.MAX_PARTS ? MUTED : ACCENT, false);
            } else if (row instanceof PartRow partRow) {
                String label = this.font.plainSubstrByWidth(partRow.part().label(), width - 86);
                graphics.drawString(this.font, label, this.adjustLeft + 15, y + 7, TEXT, false);
                String value = String.format(Locale.ROOT, "%.1f,%.1f,%.1f",
                        partRow.part().offsetX(), partRow.part().offsetY(), partRow.part().offsetZ());
                graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 7, ACCENT, false);
            }
        }

        // Inline X / Y / Z / Size / Yaw fine-adjust strip for the selected part on the Parts tab
        if (this.tab == TAB_PARTS && this.selectedPart >= 0 && this.selectedPart < this.parts.size()) {
            HologramPart p = this.parts.get(this.selectedPart);
            int stripY = this.top + PANEL_H - 96;
            graphics.drawString(this.font, "Selected part (wheel / click):", this.adjustLeft + 12, stripY, MUTED, false);
            int boxY = stripY + 12;
            int boxW = (width - 20 - 12) / 5;
            String[] labels = {
                    String.format(Locale.ROOT, "X%.1f", p.offsetX()),
                    String.format(Locale.ROOT, "Y%.1f", p.offsetY()),
                    String.format(Locale.ROOT, "Z%.1f", p.offsetZ()),
                    String.format(Locale.ROOT, "S%.1f", p.scale()),
                    String.format(Locale.ROOT, "R%.0f", p.yaw())
            };
            for (int c = 0; c < 5; c++) {
                int bx = this.adjustLeft + 10 + c * (boxW + 3);
                boolean hot = mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20;
                graphics.fill(bx, boxY, bx + boxW, boxY + 20, hot ? ROW_BG_HOT : ROW_BG);
                graphics.drawString(this.font, this.font.plainSubstrByWidth(labels[c], boxW - 4),
                        bx + 2, boxY + 6, ACCENT, false);
            }
        }

        // Inline Strength + Color/Speed fine-adjust strip for the selected effect on the FX tab
        if (this.tab == TAB_FX && this.selectedEffect >= 0 && this.selectedEffect < this.effects.size()) {
            HologramEffect fx = this.effects.get(this.selectedEffect);
            int stripY = this.top + PANEL_H - 96;
            graphics.drawString(this.font, fx.label() + " (wheel / click):", this.adjustLeft + 12, stripY, MUTED, false);
            int boxY = stripY + 12;
            int boxW = (width - 20 - 4) / 2;
            for (int c = 0; c < 2; c++) {
                int bx = this.adjustLeft + 10 + c * (boxW + 4);
                boolean hot = mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20;
                graphics.fill(bx, boxY, bx + boxW, boxY + 20, hot ? ROW_BG_HOT : ROW_BG);
                if (c == 0) {
                    String lbl = String.format(Locale.ROOT, "Str %.2f", fx.strength());
                    graphics.drawString(this.font, lbl, bx + 4, boxY + 6, ACCENT, false);
                } else if (fx.type() == HologramEffect.Type.TINT) {
                    graphics.fill(bx + 4, boxY + 5, bx + 14, boxY + 15, 0xFF000000 | fx.color());
                    graphics.renderOutline(bx + 4, boxY + 5, 10, 10, BORDER);
                    graphics.drawString(this.font, colorName(fx.color()), bx + 18, boxY + 6, ACCENT, false);
                } else {
                    String lbl = String.format(Locale.ROOT, "Spd %.1fHz", fx.speed());
                    graphics.drawString(this.font, lbl, bx + 4, boxY + 6, ACCENT, false);
                }
            }
        }

        if (this.tab == TAB_FX && this.effects.isEmpty())
            graphics.drawString(this.font, "no effects - add a layer", this.adjustLeft + 14, this.top + 190, MUTED, false);
    }

    private void drawSmallButton(GuiGraphics graphics, int x, int y, String label, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + 20 && mouseY >= y && mouseY < y + 18;
        graphics.fill(x, y, x + 20, y + 18, hot ? 0x80FFFFFF : 0x40FFFFFF);
        graphics.drawString(this.font, label, x + 10 - this.font.width(label) / 2, y + 5, TEXT, false);
    }

    // ---------------------------------------------------------------- input

    private boolean isInsidePanel(double mouseX, double mouseY) {
        boolean inLeft = mouseX >= this.blockLeft && mouseX < this.blockLeft + blockWidth()
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        boolean inRight = mouseX >= this.adjustLeft && mouseX < this.adjustLeft + adjustWidth()
                && mouseY >= this.top && mouseY < this.top + PANEL_H;
        return inLeft || inRight;
    }

    private boolean isRowHot(int mouseX, int mouseY, int index) {
        int width = adjustWidth();
        int y = rowY(index);
        return mouseX >= this.adjustLeft + 10 && mouseX < this.adjustLeft + width - 10
                && mouseY >= y && mouseY < y + ROW_H - 2;
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
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        if (clickLeftPanel(mouseX, mouseY, button))
            return true;

        if (clickSelectedPartStrip(mouseX, mouseY, button))
            return true;

        if (clickSelectedEffectStrip(mouseX, mouseY, button))
            return true;

        for (int i = 0; i < this.rows.size(); i++) {
            if (!isRowHot((int) mouseX, (int) mouseY, i))
                continue;

            Object row = this.rows.get(i);
            if (row instanceof ToggleRow toggle) {
                toggle.toggle().run();
                return true;
            }
            if (row instanceof ActionRow action) {
                if (action.available().getAsBoolean())
                    action.action().run();
                return true;
            }
            if (row instanceof EffectRow effectRow) {
                if (button == 1)
                    removeEffect(effectRow.index());
                else if (this.selectedEffect == effectRow.index())
                    setEffectStrength(effectRow.index(),
                            effectRow.effect().strength() + 0.1F * STEPS[this.stepIndex]);
                else
                    this.selectedEffect = effectRow.index();
                return true;
            }
            if (row instanceof AddRow) {
                if (button == 1)
                    cycleAddKind();
                else
                    addPart();
                return true;
            }
            if (row instanceof PartRow partRow) {
                if (button == 1)
                    removePart(partRow.index());
                else
                    this.selectedPart = partRow.index();
                return true;
            }
            if (row instanceof NumRow num) {
                if (button == 1 && num.reset() != null) {
                    num.reset().run();
                    refresh();
                    return true;
                }
                int right = this.adjustLeft + adjustWidth() - 10;
                int y = rowY(i);
                boolean minus = mouseX >= right - 48 && mouseX < right - 28 && mouseY >= y + 2 && mouseY < y + 20;
                adjust(num, minus ? -1 : 1);
                return true;
            }
        }

        // Open 3D viewport interactions (outside both panels)
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

    private boolean clickSelectedPartStrip(double mouseX, double mouseY, int button) {
        if (this.tab != TAB_PARTS || this.selectedPart < 0 || this.selectedPart >= this.parts.size())
            return false;
        int width = adjustWidth();
        int boxY = this.top + PANEL_H - 84;
        int boxW = (width - 20 - 12) / 5;
        for (int c = 0; c < 5; c++) {
            int bx = this.adjustLeft + 10 + c * (boxW + 3);
            if (mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20) {
                int dir = button == 1 ? -1 : 1;
                float step = 0.25F * STEPS[this.stepIndex] * dir;
                adjustPart(this.selectedPart,
                        c == 0 ? step : 0.0F,
                        c == 1 ? step : 0.0F,
                        c == 2 ? step : 0.0F,
                        c == 3 ? 0.1F * STEPS[this.stepIndex] * dir : 0.0F,
                        c == 4 ? 15.0F * STEPS[this.stepIndex] * dir : 0.0F);
                return true;
            }
        }
        return false;
    }

    private boolean clickSelectedEffectStrip(double mouseX, double mouseY, int button) {
        if (this.tab != TAB_FX || this.selectedEffect < 0 || this.selectedEffect >= this.effects.size())
            return false;
        int width = adjustWidth();
        int boxY = this.top + PANEL_H - 84;
        int boxW = (width - 20 - 4) / 2;
        HologramEffect fx = this.effects.get(this.selectedEffect);
        for (int c = 0; c < 2; c++) {
            int bx = this.adjustLeft + 10 + c * (boxW + 4);
            if (mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20) {
                int dir = button == 1 ? -1 : 1;
                if (c == 0)
                    setEffectStrength(this.selectedEffect, fx.strength() + 0.1F * STEPS[this.stepIndex] * dir);
                else if (fx.type() == HologramEffect.Type.TINT)
                    cycleEffectColor(this.selectedEffect, dir);
                else
                    setEffectSpeed(this.selectedEffect, fx.speed() + 0.5F * STEPS[this.stepIndex] * dir);
                return true;
            }
        }
        return false;
    }

    private boolean clickLeftPanel(double mouseX, double mouseY, int button) {
        int gridX = this.blockLeft + 10;
        int gridY = gridTop();
        int gridW = gridWidth();
        int gridH = gridHeight();
        int barX = scrollbarX();

        // Scrollbar click / drag
        if (maxLeftScroll() > 0 && mouseX >= barX - 2 && mouseX <= barX + 8
                && mouseY >= gridY && mouseY < gridY + gridH) {
            this.draggingScrollbar = true;
            updateScrollFromMouseY(mouseY);
            return true;
        }

        if (this.leftMode == LEFT_BLOCKS) {
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
            if (state == null)
                return false;

            List<Property<?>> properties = HologramStates.properties(state);
            int infoY = gridY + gridH + 6;
            int width = blockWidth();
            for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                int y = infoY + 13 + i * PROP_PITCH;
                if (mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                        && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                    cycleProperty(properties.get(i), state, button == 1 ? -1 : 1);
                    return true;
                }
            }
            return false;
        }

        // Entity or Particle list click
        if (mouseX >= gridX && mouseX < gridX + gridW && mouseY >= gridY && mouseY < gridY + gridH) {
            int row = (int) ((mouseY - gridY) / LIST_ROW_H);
            int entryIdx = this.scroll + row;
            long now = System.currentTimeMillis();
            if (this.leftMode == LEFT_ENTITIES && entryIdx >= 0 && entryIdx < this.filteredEntities.size()) {
                String pickedId = this.filteredEntities.get(entryIdx).id();
                boolean doubleClick = pickedId.equals(this.lastListClickId) && (now - this.lastListClickTime) < 350L;
                this.selectedEntityId = pickedId;
                this.addKind = 1;
                this.lastListClickId = pickedId;
                this.lastListClickTime = now;
                if (doubleClick)
                    addSpecificPart(HologramPart.entity(pickedId, this.scale));
                else
                    rebuildRows();
                return true;
            }
            if (this.leftMode == LEFT_PARTICLES && entryIdx >= 0 && entryIdx < this.filteredParticles.size()) {
                String pickedId = this.filteredParticles.get(entryIdx);
                boolean doubleClick = pickedId.equals(this.lastListClickId) && (now - this.lastListClickTime) < 350L;
                this.selectedParticleId = pickedId;
                this.addKind = 2;
                this.lastListClickId = pickedId;
                this.lastListClickTime = now;
                if (doubleClick)
                    addSpecificPart(HologramPart.particle(pickedId, this.scale));
                else
                    rebuildRows();
                return true;
            }
            return false;
        }

        // "+ Add Entity Part" / "+ Add Particle Part" button
        int infoY = gridY + gridH + 6;
        int btnY = infoY + 14;
        int width = blockWidth();
        if (mouseX >= this.blockLeft + 10 && mouseX < this.blockLeft + width - 10
                && mouseY >= btnY && mouseY < btnY + 20) {
            if (this.leftMode == LEFT_ENTITIES && !this.selectedEntityId.isEmpty()) {
                addSpecificPart(HologramPart.entity(this.selectedEntityId, this.scale));
                return true;
            }
            if (this.leftMode == LEFT_PARTICLES && !this.selectedParticleId.isEmpty()) {
                addSpecificPart(HologramPart.particle(this.selectedParticleId, this.scale));
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
        if (this.orbiting) {
            if (this.minecraft != null && this.minecraft.player != null)
                this.minecraft.player.turn(dragX * 4.0, dragY * 4.0);
            return true;
        }
        if (this.gizmo.isDragging()) {
            this.gizmo.updateDrag(mouseX, mouseY, this.width, this.height,
                    STEPS[this.stepIndex], hasShiftDown(), new HologramGizmo.Target() {
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
        if (this.gizmo.isDragging()) {
            this.gizmo.cancelDrag();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && this.gizmo.isDragging()) {
            this.gizmo.cancelDrag();
            undoTransform();
            return true;
        }
        if (!(getFocused() instanceof EditBox)) {
            if (keyCode == GLFW.GLFW_KEY_G) {
                this.gizmo.setMode(HologramGizmo.Mode.MOVE);
                updateGizmoButtonLabels();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_R) {
                this.gizmo.setMode(HologramGizmo.Mode.ROTATE);
                updateGizmoButtonLabels();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_S) {
                this.gizmo.setMode(HologramGizmo.Mode.SCALE);
                updateGizmoButtonLabels();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_Z && hasControlDown()) {
                undoTransform();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = (int) Math.signum(scrollY);
        if (direction == 0)
            return false;

        // Property rows cycle under the wheel in Block mode
        if (this.leftMode == LEFT_BLOCKS) {
            BlockState state = currentState();
            if (state != null) {
                List<Property<?>> properties = HologramStates.properties(state);
                int infoY = gridTop() + gridHeight() + 6;
                int width = blockWidth();
                for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                    int y = infoY + 13 + i * PROP_PITCH;
                    if (mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                            && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                        cycleProperty(properties.get(i), state, direction);
                        return true;
                    }
                }
            }
        }

        // Scrollable grid/list + scrollbar track area
        int gridX = this.blockLeft + 10;
        int gridY = gridTop();
        if (mouseX >= gridX && mouseX < scrollbarX() + 8
                && mouseY >= gridY && mouseY < gridY + gridHeight()) {
            this.scroll = Mth.clamp(this.scroll - direction, 0, maxLeftScroll());
            return true;
        }

        // Selected part fine-adjust strip
        if (this.tab == TAB_PARTS && this.selectedPart >= 0 && this.selectedPart < this.parts.size()) {
            int width = adjustWidth();
            int boxY = this.top + PANEL_H - 84;
            int boxW = (width - 20 - 12) / 5;
            for (int c = 0; c < 5; c++) {
                int bx = this.adjustLeft + 10 + c * (boxW + 3);
                if (mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20) {
                    float step = 0.25F * STEPS[this.stepIndex] * direction;
                    adjustPart(this.selectedPart,
                            c == 0 ? step : 0.0F,
                            c == 1 ? step : 0.0F,
                            c == 2 ? step : 0.0F,
                            c == 3 ? 0.1F * STEPS[this.stepIndex] * direction : 0.0F,
                            c == 4 ? 15.0F * STEPS[this.stepIndex] * direction : 0.0F);
                    return true;
                }
            }
        }

        // Selected effect fine-adjust strip
        if (this.tab == TAB_FX && this.selectedEffect >= 0 && this.selectedEffect < this.effects.size()) {
            int width = adjustWidth();
            int boxY = this.top + PANEL_H - 84;
            int boxW = (width - 20 - 4) / 2;
            HologramEffect fx = this.effects.get(this.selectedEffect);
            for (int c = 0; c < 2; c++) {
                int bx = this.adjustLeft + 10 + c * (boxW + 4);
                if (mouseX >= bx && mouseX < bx + boxW && mouseY >= boxY && mouseY < boxY + 20) {
                    if (c == 0)
                        setEffectStrength(this.selectedEffect, fx.strength() + 0.1F * STEPS[this.stepIndex] * direction);
                    else if (fx.type() == HologramEffect.Type.TINT)
                        cycleEffectColor(this.selectedEffect, direction);
                    else
                        setEffectSpeed(this.selectedEffect, fx.speed() + 0.5F * STEPS[this.stepIndex] * direction);
                    return true;
                }
            }
        }

        for (int i = 0; i < this.rows.size(); i++) {
            if (!isRowHot((int) mouseX, (int) mouseY, i))
                continue;
            Object row = this.rows.get(i);
            if (row instanceof NumRow num)
                adjust(num, direction);
            else if (row instanceof ToggleRow toggle)
                toggle.toggle().run();
            else if (row instanceof EffectRow effectRow)
                setEffectStrength(effectRow.index(),
                        effectRow.effect().strength() + direction * 0.1F * STEPS[this.stepIndex]);
            else if (row instanceof AddRow)
                cyclePick(direction);
            else if (row instanceof PartRow partRow)
                adjustPart(partRow.index(), 0.0F, direction * 0.25F * STEPS[this.stepIndex], 0.0F, 0.0F, 0.0F);
            return true;
        }
        return false;
    }

    private void adjust(NumRow row, int direction) {
        row.set().accept(row.get().getAsDouble() + direction * row.step() * STEPS[this.stepIndex]);
        refresh();
    }

    /** Applies the clipboard's presentation fields to the open editor; Done persists them. */
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

        rebuildRows();
        refresh();
    }

    /** Pastes the clipboard as a brand new hologram a few blocks in front of the player. */
    private void duplicateBesideMe() {
        HologramSpec clip = HologramClipboard.peek();
        var player = this.minecraft == null ? null : this.minecraft.player;
        if (clip == null || player == null)
            return;

        var target = BlockPos.containing(player.getEyePosition().add(player.getViewVector(1.0F).scale(3.0)));
        HologramNetwork.sendCreate(target, clip);
    }

    /** Selects a block for the hologram. Property values are dropped: they belong to the old block. */
    public void pickBlock(Block block) {
        this.block = BuiltInRegistries.BLOCK.getKey(block).toString();
        this.props = new LinkedHashMap<>();
        refresh();
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
        HologramNetwork.sendSave(buildSpec());
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    public void deleteAndClose() {
        HologramNetwork.sendDelete(this.id);
        HologramClient.clearPreview();
        if (this.minecraft != null)
            this.minecraft.setScreen(null);
    }

    @Override
    public void onClose() {
        // Closing without Done keeps whatever the server already has; the preview is only local.
        HologramClient.clearPreview();
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- dev-check hooks

    /** Dev-check hook: the editor's live spec, so a check can assert what a click actually changed. */
    public HologramSpec current() {
        return buildSpec();
    }

    /** Dev-check hook: the properties offered for the current block. */
    public List<Property<?>> currentProperties() {
        BlockState state = currentState();
        return state == null ? List.of() : HologramStates.properties(state);
    }

    /** Dev-check hook: cycles a property exactly as a click on its row would. */
    public void devCycleProperty(int index, int direction) {
        BlockState state = currentState();
        if (state == null)
            return;
        List<Property<?>> properties = HologramStates.properties(state);
        if (index < 0 || index >= properties.size())
            return;
        cycleProperty(properties.get(index), state, direction);
    }

    /** Dev-check hook: the Size control. */
    public void devSetScale(float scale) {
        this.scale = (float) clamp(scale, 0.25, 8.0);
        refresh();
    }

    /** Dev-check hook: the Spin control. */
    public void devSetSpin(float spin) {
        this.spin = (float) clamp(spin, -360.0, 360.0);
        refresh();
    }

    /** Dev-check hook: origin offset control. */
    public void devSetOffset(float x, float y, float z) {
        this.offsetX = (float) clamp(x, -16.0, 16.0);
        this.offsetY = (float) clamp(y, -16.0, 16.0);
        this.offsetZ = (float) clamp(z, -16.0, 16.0);
        refresh();
    }

    /** Dev-check hook: switches left picker mode (0=Block, 1=Entity, 2=Particle). */
    public void devLeftMode(int mode) {
        setLeftMode(mode);
    }

    /** Dev-check hook: current left picker scroll row. */
    public int devScroll() {
        return this.scroll;
    }

    /** Dev-check hook: simulates dragging the scrollbar to a 0..1 fraction. */
    public void devDragScrollbar(double fraction) {
        int gridY = gridTop();
        int gridH = gridHeight();
        double y = gridY + Mth.clamp(fraction, 0.0, 1.0) * gridH;
        this.draggingScrollbar = true;
        updateScrollFromMouseY(y);
        this.draggingScrollbar = false;
    }

    /** Dev-check hook: which tab is showing. */
    public int devTab() {
        return this.tab;
    }

    /** Dev-check hook: switches tab the way the tab button does. */
    public void devTab(int tab) {
        this.tab = Mth.clamp(tab, TAB_LOOK, TAB_SHARE);
        rebuildRows();
    }

    /** Dev-check hook: the Step multiplier, 0-based into {@link #STEPS}. */
    public void devStepIndex(int index) {
        this.stepIndex = Mth.clamp(index, 0, STEPS.length - 1);
        updateLabels();
    }

    /** Dev-check hook: rows on the current tab, so a check can assert the wheel has something to hit. */
    public int devRowCount() {
        return this.rows.size();
    }

    /** Dev-check hook: the mouse wheel path for row {@code index} on the current tab. */
    public boolean devScrollRow(int index, int direction) {
        if (index < 0 || index >= this.rows.size())
            return false;
        Object row = this.rows.get(index);
        if (row instanceof NumRow num)
            adjust(num, direction);
        else if (row instanceof ToggleRow toggle)
            toggle.toggle().run();
        else if (row instanceof ActionRow action && action.available().getAsBoolean())
            action.action().run();
        else if (row instanceof EffectRow effectRow)
            setEffectStrength(effectRow.index(),
                    effectRow.effect().strength() + direction * 0.1F * STEPS[this.stepIndex]);
        else if (row instanceof AddRow)
            cyclePick(direction);
        else if (row instanceof PartRow partRow)
            adjustPart(partRow.index(), 0.0F, direction * 0.25F * STEPS[this.stepIndex], 0.0F, 0.0F, 0.0F);
        return true;
    }

    /** Dev-check hook: cycles tint color or adjusts speed on an effect layer. */
    public void devCycleEffectColor(int index, int direction) {
        cycleEffectColor(index, direction);
    }

    public void devSetEffectSpeed(int index, float speed) {
        setEffectSpeed(index, speed);
    }

    public void devRotatePart(int index, float deltaYaw) {
        adjustPart(index, 0.0F, 0.0F, 0.0F, 0.0F, deltaYaw);
    }

    /** Dev-check hook: adds a part of the current pick, exactly as clicking the Add row does. */
    public void devAddPart(int kind) {
        this.addKind = Mth.clamp(kind, 0, ADD_KINDS.length - 1);
        addPart();
    }

    /** Dev-check hook: adds a specific part, so a check is not at the mercy of registry order. */
    public void devAddPart(int kind, String id) {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;
        float topY = this.scale;
        HologramPart part = switch (Mth.clamp(kind, 0, ADD_KINDS.length - 1)) {
            case 1 -> HologramPart.entity(id, topY);
            case 2 -> HologramPart.particle(id, topY * 0.5F);
            default -> HologramPart.block(id, Map.of(), topY);
        };
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        this.selectedPart = next.size() - 1;
        rebuildRows();
        refresh();
    }

    /** Dev-check hook: right-click removal of a content part. */
    public void devRemovePart(int index) {
        removePart(index);
    }

    /** Dev-check hook: how many content parts are on the open hologram. */
    public int devPartCount() {
        return this.parts.size();
    }

    /** Dev-check hook: right-click removal of an effect layer. */
    public void devRemoveEffect(int index) {
        removeEffect(index);
    }

    /** Dev-check hook: the block grid columns actually used at this GUI scale. */
    public int devGridCols() {
        return this.gridCols;
    }

    /** Dev-check hook: total panel coverage, so a check can assert the middle of the screen stays free. */
    public int devFreeWidth() {
        return this.adjustLeft - (this.blockLeft + blockWidth());
    }
}
