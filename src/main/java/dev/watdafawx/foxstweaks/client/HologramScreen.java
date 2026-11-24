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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <li><b>Two panels, not one.</b> A single window wide enough for a block grid plus every control was
 * covering half the screen at a normal GUI scale. Picking a block and adjusting it are separate jobs,
 * so they are separate panels: block grid and its states on the left, values on the right.
 * <li><b>No blurred menu background.</b> Vanilla's screen background blurs the world, which defeats the
 * point - the preview is the real hologram in the real world.
 * <li><b>Every number takes the mouse wheel</b>, and one Step multiplier scales all of them, because the
 * natural increment differs per field (size 0.25, angles 15 degrees, range 8 blocks).
 * </ul>
 */
public class HologramScreen extends Screen {
    private static final int PANEL_H = 268;
    private static final int CELL = 18;
    private static final int GRID_ROWS = 5;
    private static final int PROP_ROWS = 6;
    private static final int PROP_PITCH = 16;
    private static final int ROW_H = 26;
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

    private static List<Block> ALL_BLOCKS;

    /** A row that behaves like a slider: the wheel and the two buttons all move it by {@code step}. */
    private record NumRow(String label, DoubleSupplier get, DoubleConsumer set, double step,
                          DoubleFunction<String> format) {
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

    /** One content part: wheel moves it vertically, click cycles its side offset, right-click removes it. */
    private record PartRow(int index, HologramPart part) {
    }

    private final String id;
    private final BlockPos pos;
    private final int maxRenderDistance;
    @Nullable
    private final UUID owner;
    private final String ownerName;

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
    private float appearRadius;
    private float appearSeconds;

    private int tab = TAB_LOOK;
    private int stepIndex;
    private int scroll;

    private int blockLeft;
    private int adjustLeft;
    private int top;
    private int gridCols = 9;
    private int adjustPanelWidth = 200;

    private EditBox search;
    private Button stepButton;

    private List<Block> filtered = List.of();
    private List<Object> rows = List.of();

    public HologramScreen(HologramOpenPayload payload) {
        super(Component.translatable("screen.foxstweaks.hologram.title"));
        HologramSpec spec = payload.spec();
        this.id = spec.id();
        this.pos = spec.pos();
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
        return this.gridCols * CELL + 20;
    }

    private int adjustWidth() {
        return this.adjustPanelWidth;
    }

    private int tabWidth() {
        return (this.adjustPanelWidth - 20 - (TAB_COUNT - 1) * 4) / TAB_COUNT;
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
        this.gridCols = Mth.clamp((blockTarget - 20) / CELL, 4, 9);
        int adjustWidth = this.adjustPanelWidth;

        this.blockLeft = 8;
        this.adjustLeft = this.width - adjustWidth - 8;
        this.top = Math.max(6, (this.height - PANEL_H) / 2);

        this.search = new EditBox(this.font, this.blockLeft + 10, this.top + 22, blockWidth() - 20, 14,
                Component.literal("Search"));
        this.search.setHint(Component.literal("search blocks...").withStyle(style -> style.withColor(MUTED)));
        this.search.setResponder(text -> {
            this.scroll = 0;
            refreshFilter();
        });
        addRenderableWidget(this.search);

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
        }).bounds(this.adjustLeft + 10, this.top + PANEL_H - 56, adjustWidth - 20, 18).build());

        int half = (adjustWidth - 20 - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> saveAndClose())
                .bounds(this.adjustLeft + 10, this.top + PANEL_H - 26, half, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Delete"), b -> deleteAndClose())
                .bounds(this.adjustLeft + 10 + half + 4, this.top + PANEL_H - 26, half, 20).build());

        refreshFilter();
        rebuildRows();
        refresh();
    }

    private void rebuildRows() {
        List<Object> built = new ArrayList<>();
        if (this.tab == TAB_LOOK) {
            built.add(new NumRow("Size", () -> this.scale, v -> this.scale = (float) clamp(v, 0.25, 8.0), 0.25,
                    v -> String.format(Locale.ROOT, "%.2fx", v)));
            built.add(new NumRow("Yaw", () -> this.yaw, v -> this.yaw = wrap(v), 15.0, v -> fmtDegrees(v)));
            built.add(new NumRow("Pitch", () -> this.pitch, v -> this.pitch = wrap(v), 15.0, v -> fmtDegrees(v)));
            built.add(new NumRow("Roll", () -> this.roll, v -> this.roll = wrap(v), 15.0, v -> fmtDegrees(v)));
            built.add(new ToggleRow("Glow", () -> this.fullbright, () -> {
                this.fullbright = !this.fullbright;
                refresh();
            }));
            built.add(new NumRow("Range", () -> this.renderDistance,
                    v -> this.renderDistance = (int) clamp(Math.round(v), 8, this.maxRenderDistance), 8.0,
                    v -> (int) v + " blocks"));
        } else if (this.tab == TAB_MOVE) {
            built.add(new NumRow("Spin", () -> this.spin, v -> this.spin = (float) clamp(v, -360.0, 360.0),
                    15.0, v -> v == 0.0 ? "off" : String.format(Locale.ROOT, "%.0f deg/s", v)));
            built.add(new NumRow("Axis", () -> this.spinAxis,
                    v -> this.spinAxis = (int) Mth.clamp(Math.round(v), 0L, 2L), 1.0,
                    v -> switch ((int) v) {
                        case HologramSpec.AXIS_X -> "X";
                        case HologramSpec.AXIS_Z -> "Z";
                        default -> "Y";
                    }));
            built.add(new NumRow("Hover", () -> this.hover, v -> this.hover = (float) clamp(v, 0.0, 4.0),
                    0.25, v -> v <= 0.0 ? "off" : String.format(Locale.ROOT, "%.2f", v)));
            built.add(new NumRow("Period", () -> this.hoverPeriod,
                    v -> this.hoverPeriod = (float) clamp(v, 0.5, 120.0), 1.0,
                    v -> String.format(Locale.ROOT, "%.1f s", v)));
        } else if (this.tab == TAB_PARTS) {
            built.add(new AddRow());
            for (int i = 0; i < this.parts.size(); i++)
                built.add(new PartRow(i, this.parts.get(i)));
        } else if (this.tab == TAB_FX) {
            built.add(new ActionRow("Add effect", this::addEffect, () -> this.effects.size() < MAX_EFFECTS,
                    () -> this.effects.size() < MAX_EFFECTS ? "layer" : "max " + MAX_EFFECTS));
            for (int i = 0; i < this.effects.size(); i++)
                built.add(new EffectRow(i, this.effects.get(i)));
            built.add(new NumRow("Appear radius", () -> this.appearRadius,
                    v -> this.appearRadius = (float) clamp(v, 0.0, 64.0), 2.0,
                    v -> v <= 0.0 ? "always on" : String.format(Locale.ROOT, "%.0f blocks", v)));
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
        }
        this.rows = List.copyOf(built);
        updateLabels();
    }

    private void addEffect() {
        if (this.effects.size() >= MAX_EFFECTS)
            return;
        this.effects = new ArrayList<>(this.effects);
        this.effects.add(HologramEffect.of(HologramEffect.nextType(this.effects)));
        rebuildRows();
        refresh();
    }

    private void removeEffect(int index) {        if (index < 0 || index >= this.effects.size())
            return;
        List<HologramEffect> next = new ArrayList<>(this.effects);
        next.remove(index);
        this.effects = next;
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
        refresh();
    }

    // ---------------------------------------------------------------- content parts

    private static final String[] ADD_KINDS = { "block", "entity", "particle" };
    private static List<String> ENTITY_IDS;
    private static List<String> PARTICLE_IDS;

    private static List<String> entityIds() {
        if (ENTITY_IDS == null) {
            ENTITY_IDS = BuiltInRegistries.ENTITY_TYPE.keySet().stream()
                    .map(ResourceLocation::toString).sorted().toList();
        }
        return ENTITY_IDS;
    }

    private static List<String> particleIds() {
        if (PARTICLE_IDS == null) {
            // Only particles that stand on their own: block/item particles need an options object the
            // editor has no way to fill in yet.
            PARTICLE_IDS = BuiltInRegistries.PARTICLE_TYPE.entrySet().stream()
                    .filter(entry -> entry.getValue() instanceof SimpleParticleType)
                    .map(entry -> entry.getKey().location().toString())
                    .sorted().toList();
        }
        return PARTICLE_IDS;
    }

    private String pickedEntity() {
        List<String> ids = entityIds();
        return ids.isEmpty() ? "" : ids.get(Math.floorMod(this.pickIndex, ids.size()));
    }

    private String pickedParticle() {
        List<String> ids = particleIds();
        return ids.isEmpty() ? "minecraft:crit" : ids.get(Math.floorMod(this.pickIndex, ids.size()));
    }

    private String addDescription() {
        return switch (this.addKind) {
            case 1 -> "entity " + pickedEntity();
            case 2 -> "particle " + pickedParticle();
            default -> "block " + this.block;
        };
    }

    private void cycleAddKind() {
        this.addKind = (this.addKind + 1) % ADD_KINDS.length;
        this.pickIndex = 0;
        rebuildRows();
    }

    private void cyclePick(int direction) {
        if (this.addKind == 0)
            return;
        this.pickIndex += direction;
        rebuildRows();
    }

    private void addPart() {
        if (this.parts.size() >= HologramPart.MAX_PARTS)
            return;

        // New parts start on top of the base, not inside it: a 2.5x hologram swallows anything placed
        // at y=1.
        float top = this.scale;
        HologramPart part = switch (this.addKind) {
            case 1 -> HologramPart.entity(pickedEntity(), top);
            case 2 -> HologramPart.particle(pickedParticle(), top);
            default -> HologramPart.block(this.block, this.props, top);
        };

        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
        rebuildRows();
        refresh();
    }

    private void movePart(int index, float deltaY) {
        if (index < 0 || index >= this.parts.size())
            return;
        HologramPart old = this.parts.get(index);
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.set(index, new HologramPart(old.kind(), old.id(), old.props(), old.offsetX(),
                old.offsetY() + deltaY, old.offsetZ(), old.scale(), old.yaw()));
        this.parts = next;
        refresh();
    }

    /** Left-click cycles a part around the parent: centre, then the four sides. */
    private void cyclePartSide(int index) {
        if (index < 0 || index >= this.parts.size())
            return;
        HologramPart old = this.parts.get(index);
        float[][] spots = { { 0.0F, 0.0F }, { 1.0F, 0.0F }, { -1.0F, 0.0F }, { 0.0F, 1.0F }, { 0.0F, -1.0F } };
        int current = 0;
        for (int i = 0; i < spots.length; i++) {
            if (Math.abs(spots[i][0] - old.offsetX()) < 0.01F && Math.abs(spots[i][1] - old.offsetZ()) < 0.01F)
                current = i;
        }
        int next = (current + 1) % spots.length;
        List<HologramPart> list = new ArrayList<>(this.parts);
        list.set(index, new HologramPart(old.kind(), old.id(), old.props(), spots[next][0], old.offsetY(),
                spots[next][1], old.scale(), old.yaw()));
        this.parts = list;
        refresh();
    }

    private void removePart(int index) {
        if (index < 0 || index >= this.parts.size())
            return;
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.remove(index);
        this.parts = next;
        rebuildRows();
        refresh();
    }

    private void updateLabels() {        if (this.stepButton == null)
            return;
        this.stepButton.setMessage(Component.literal(String.format(Locale.ROOT,
                "Step  x%.0f  (wheel / - +)", STEPS[this.stepIndex])));
    }

    private int rowY(int index) {
        return this.top + 44 + index * ROW_H;
    }

    private int gridWidth() {
        return this.gridCols * CELL;
    }

    private int gridTop() {
        return this.top + 44;
    }

    // ---------------------------------------------------------------- state

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
        return new HologramSpec(this.id, this.pos, this.block, this.props, this.scale, this.yaw, this.pitch,
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
        List<Block> blocks = allBlocks();
        if (query.isEmpty()) {
            this.filtered = blocks;
        } else {
            List<Block> matches = new ArrayList<>();
            for (Block block : blocks) {
                if (BuiltInRegistries.BLOCK.getKey(block).toString().contains(query)
                        || block.getName().getString().toLowerCase(Locale.ROOT).contains(query))
                    matches.add(block);
            }
            this.filtered = matches;
        }
    }

    private static List<Block> allBlocks() {
        if (ALL_BLOCKS == null) {
            ALL_BLOCKS = BuiltInRegistries.BLOCK.stream()
                    .filter(block -> block.asItem() != Items.AIR)
                    // Only blocks with a real model: a block whose look is a block entity renderer
                    // would draw nothing here, and a picker full of invisible entries is worse than
                    // a short one. Tier 2 (BER blocks) is a later phase.
                    // ENTITYBLOCK_ANIMATED blocks (chests, signs, beds, shulker boxes) have no model at\n                    // all: they draw from a block entity, which a hologram now carries the data for.\n                    .filter(block -> block.defaultBlockState().getRenderShape() == RenderShape.MODEL\n                            || block.defaultBlockState().getRenderShape() == RenderShape.ENTITYBLOCK_ANIMATED)
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

        renderBlockPanel(graphics, mouseX, mouseY);
        renderAdjustPanel(graphics, mouseX, mouseY);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void panel(GuiGraphics graphics, int x, int y, int width, String title) {
        graphics.fill(x, y, x + width, y + PANEL_H, BG);
        graphics.renderOutline(x, y, width, PANEL_H, BORDER);
        graphics.drawString(this.font, title, x + 10, y + 7, ACCENT, false);
    }

    private void renderBlockPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = blockWidth();
        panel(graphics, this.blockLeft, this.top, width, "Block");

        int gridX = this.blockLeft + 10;
        int gridY = gridTop();
        int gridW = gridWidth();
        int maxScroll = Math.max(0, (this.filtered.size() + this.gridCols - 1) / this.gridCols - GRID_ROWS);
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        int first = this.scroll * this.gridCols;
        for (int index = 0; index < this.gridCols * GRID_ROWS; index++) {
            int entry = first + index;
            if (entry >= this.filtered.size())
                break;
            Block entryBlock = this.filtered.get(entry);
            int x = gridX + (index % this.gridCols) * CELL;
            int y = gridY + (index / this.gridCols) * CELL;

            boolean hover = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            boolean selected = BuiltInRegistries.BLOCK.getKey(entryBlock).toString().equals(this.block);
            if (selected)
                graphics.fill(x, y, x + CELL, y + CELL, 0x80406A80);
            else if (hover)
                graphics.fill(x, y, x + CELL, y + CELL, ROW_BG);

            graphics.renderItem(new ItemStack(entryBlock.asItem()), x + 1, y + 1);
        }

        if (maxScroll > 0) {
            int barHeight = Math.max(12, GRID_ROWS * CELL * GRID_ROWS
                    / Math.max(1, (this.filtered.size() + this.gridCols - 1) / this.gridCols));
            int barY = gridY + (GRID_ROWS * CELL - barHeight) * this.scroll / maxScroll;
            graphics.fill(gridX + gridW + 2, gridY, gridX + gridW + 4, gridY + GRID_ROWS * CELL, 0x30FFFFFF);
            graphics.fill(gridX + gridW + 2, barY, gridX + gridW + 4, barY + barHeight, 0xAA66E0FF);
        }

        int infoY = gridY + GRID_ROWS * CELL + 8;
        Block block = HologramStates.blockOf(this.block);
        String name = block == null ? "missing block" : block.getName().getString();
        graphics.drawString(this.font, this.font.plainSubstrByWidth(name, width - 20), this.blockLeft + 10, infoY,
                TEXT, false);

        BlockState state = currentState();
        if (state == null)
            return;

        List<Property<?>> properties = HologramStates.properties(state);
        for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
            Property<?> property = properties.get(i);
            int y = infoY + 14 + i * PROP_PITCH;
            boolean hot = mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                    && mouseY >= y && mouseY < y + PROP_PITCH - 2;
            graphics.fill(this.blockLeft + 8, y, this.blockLeft + width - 8, y + PROP_PITCH - 2,
                    hot ? ROW_BG_HOT : ROW_BG);
            graphics.drawString(this.font, property.getName(), this.blockLeft + 12, y + 4, MUTED, false);
            String value = HologramStates.valueOf(state, property);
            int valueWidth = this.font.width(value);
            graphics.drawString(this.font, value, this.blockLeft + width - 12 - valueWidth, y + 4, ACCENT, false);
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
            graphics.fill(this.adjustLeft + 10, y, this.adjustLeft + width - 10, y + ROW_H - 2,
                    hot ? ROW_BG_HOT : ROW_BG);

            Object row = this.rows.get(i);
            int right = this.adjustLeft + width - 10;
            if (row instanceof NumRow num) {
                graphics.drawString(this.font, num.label(), this.adjustLeft + 15, y + 8, TEXT, false);
                String value = num.format().apply(num.get().getAsDouble());
                graphics.drawString(this.font, value, right - 56 - this.font.width(value), y + 8, ACCENT, false);
                drawSmallButton(graphics, right - 50, y + 3, "-", mouseX, mouseY);
                drawSmallButton(graphics, right - 25, y + 3, "+", mouseX, mouseY);
            } else if (row instanceof ToggleRow toggle) {
                boolean on = toggle.get().getAsBoolean();
                graphics.drawString(this.font, toggle.label(), this.adjustLeft + 15, y + 8, TEXT, false);
                String value = on ? "on" : "off";
                graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 8,
                        on ? ACCENT : MUTED, false);
            } else if (row instanceof ActionRow action) {
                boolean available = action.available().getAsBoolean();
                graphics.drawString(this.font, action.label(), this.adjustLeft + 15, y + 8,
                        available ? TEXT : MUTED, false);
                String hint = action.hint().get();
                graphics.drawString(this.font, hint, right - 5 - this.font.width(hint), y + 8,
                        available ? ACCENT : MUTED, false);
            } else if (row instanceof EffectRow effectRow) {
                String label = effectRow.effect().label();
                graphics.drawString(this.font, label, this.adjustLeft + 15, y + 8, TEXT, false);
                String value = String.format(Locale.ROOT, "%.2f", effectRow.effect().strength());
                graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 8, ACCENT, false);
            } else if (row instanceof AddRow) {
                graphics.drawString(this.font, "Add " + addDescription(), this.adjustLeft + 15, y + 8, TEXT, false);
                String hint = this.parts.size() >= HologramPart.MAX_PARTS ? "max" : "click";
                graphics.drawString(this.font, hint, right - 5 - this.font.width(hint), y + 8,
                        this.parts.size() >= HologramPart.MAX_PARTS ? MUTED : ACCENT, false);
            } else if (row instanceof PartRow partRow) {
                graphics.drawString(this.font, partRow.part().label(), this.adjustLeft + 15, y + 8, TEXT, false);
                String value = String.format(Locale.ROOT, "y %.2f", partRow.part().offsetY());
                graphics.drawString(this.font, value, right - 5 - this.font.width(value), y + 8, ACCENT, false);
            }
        }

        if (this.tab == TAB_FX && this.effects.isEmpty())
            graphics.drawString(this.font, "no effects - add a layer", this.adjustLeft + 14, this.top + 190, MUTED, false);
    }

    private void drawSmallButton(GuiGraphics graphics, int x, int y, String label, int mouseX, int mouseY) {
        boolean hot = mouseX >= x && mouseX < x + 21 && mouseY >= y && mouseY < y + 20;
        graphics.fill(x, y, x + 21, y + 20, hot ? 0x80FFFFFF : 0x40FFFFFF);
        graphics.drawString(this.font, label, x + 11 - this.font.width(label) / 2, y + 6, TEXT, false);
    }

    // ---------------------------------------------------------------- input

    private boolean isRowHot(int mouseX, int mouseY, int index) {
        int width = adjustWidth();
        int y = rowY(index);
        return mouseX >= this.adjustLeft + 10 && mouseX < this.adjustLeft + width - 10
                && mouseY >= y && mouseY < y + ROW_H - 2;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button))
            return true;

        if (clickBlockPanel(mouseX, mouseY, button))
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
                else
                    setEffectStrength(effectRow.index(),
                            effectRow.effect().strength() + 0.1F * STEPS[this.stepIndex]);
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
                    cyclePartSide(partRow.index());
                return true;
            }
            if (row instanceof NumRow num) {
                int right = this.adjustLeft + adjustWidth() - 10;
                int y = rowY(i);
                boolean minus = mouseX >= right - 50 && mouseX < right - 29 && mouseY >= y + 3 && mouseY < y + 23;
                adjust(num, minus ? -1 : 1);
                return true;
            }
        }
        return false;
    }

    private boolean clickBlockPanel(double mouseX, double mouseY, int button) {
        int gridX = this.blockLeft + 10;
        int gridY = gridTop();

        if (mouseX >= gridX && mouseX < gridX + gridWidth()
                && mouseY >= gridY && mouseY < gridY + GRID_ROWS * CELL) {
            int col = (int) ((mouseX - gridX) / CELL);
            int row = (int) ((mouseY - gridY) / CELL);
            int entry = (this.scroll + row) * this.gridCols + col;
            if (entry >= 0 && entry < this.filtered.size()) {
                pickBlock(this.filtered.get(entry));
                return true;
            }
            return false;
        }

        BlockState state = currentState();
        if (state == null)
            return false;

        List<Property<?>> properties = HologramStates.properties(state);
        int infoY = gridY + GRID_ROWS * CELL + 8;
        int width = blockWidth();
        for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
            int y = infoY + 14 + i * PROP_PITCH;
            if (mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                    && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                cycleProperty(properties.get(i), state, button == 1 ? -1 : 1);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int direction = (int) Math.signum(scrollY);
        if (direction == 0)
            return false;

        // A property row cycles its value under the wheel, same as clicking it.
        BlockState state = currentState();
        if (state != null) {
            List<Property<?>> properties = HologramStates.properties(state);
            int infoY = gridTop() + GRID_ROWS * CELL + 8;
            int width = blockWidth();
            for (int i = 0; i < Math.min(properties.size(), PROP_ROWS); i++) {
                int y = infoY + 14 + i * PROP_PITCH;
                if (mouseX >= this.blockLeft + 8 && mouseX < this.blockLeft + width - 8
                        && mouseY >= y && mouseY < y + PROP_PITCH - 2) {
                    cycleProperty(properties.get(i), state, direction);
                    return true;
                }
            }
        }

        int gridX = this.blockLeft + 10;
        int gridY = gridTop();
        if (mouseX >= gridX && mouseX < gridX + gridWidth()
                && mouseY >= gridY && mouseY < gridY + GRID_ROWS * CELL) {
            int maxScroll = Math.max(0, (this.filtered.size() + this.gridCols - 1) / this.gridCols - GRID_ROWS);
            this.scroll = Mth.clamp(this.scroll - direction, 0, maxScroll);
            return true;
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
                movePart(partRow.index(), direction * 0.25F * STEPS[this.stepIndex]);
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
            movePart(partRow.index(), direction * 0.25F * STEPS[this.stepIndex]);
        return true;
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
        float top = this.scale;
        HologramPart part = switch (Mth.clamp(kind, 0, ADD_KINDS.length - 1)) {
            case 1 -> HologramPart.entity(id, top);
            case 2 -> HologramPart.particle(id, top * 0.5F);
            default -> HologramPart.block(id, Map.of(), top);
        };
        List<HologramPart> next = new ArrayList<>(this.parts);
        next.add(part);
        this.parts = next;
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
