package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramStates;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.IntConsumer;

/**
 * Full-screen asset browser for the hologram editor: blocks, mobs and particles with search, colour and shape
 * filters, favourites and recents, a rotating 3D preview and generic blockstate variant controls.
 *
 * <p>Opened from {@link HologramScreen#openPicker()}; the choice goes back through
 * {@link HologramScreen#applyPickerChoice} and the same editor instance is shown again. Everything is drawn in
 * immediate mode: each frame {@link #render} rebuilds {@link #hits}, and clicks, wheel and drags are routed to
 * the last-drawn rectangle under the cursor. The dev check clicks through the very same list by label.
 */
public class HologramPickerScreen extends Screen {
    private static final int PANEL = 0xF0141619;
    private static final int BORDER = 0xFF323744;
    private static final int ACCENT = 0xFF3EE6FF;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int DIMMED = 0xFF5A6372;
    private static final int STAR = 0xFFFFD84D;
    private static final int CHIP = 0xFF1E222A;
    private static final int CHIP_HOT = 0xFF2C333F;
    private static final int CHIP_ON = 0xFF174852;
    private static final int TILE_BG = 0xFF1B1F27;
    private static final int WELL = 0xFF0E1013;

    private static final int TILE = 28;
    private static final int PITCH = 30;
    private static final int SIDE_W = 104;

    private static final int F_FULL = 1;
    private static final int F_STAIR = 2;
    private static final int F_ANIM = 4;
    private static final int F_LIGHT = 8;
    private static final String[] SHAPE_LABELS = { "Full block", "Slabs/stairs", "Animated", "Has light" };

    private enum Collection {
        ALL("All"), FAVOURITES("★ Favourites"), RECENT("◷ Recent"), IN_HOLOGRAM("⊞ In hologram");

        final String label;

        Collection(String label) {
            this.label = label;
        }
    }

    private record Entry(HologramLayer.Kind kind, String id, String name, String ns, @Nullable Block block,
                         @Nullable EntityType<?> type, ItemStack icon, String lower) {
    }

    private interface Drag {
        void drag(double mx, double my, double dx, double dy);
    }

    private static final class Hit {
        final int x, y, w, h;
        final int cx0, cy0, cx1, cy1;
        final String label;
        final Runnable click;
        @Nullable
        Runnable right;
        @Nullable
        IntConsumer wheel;
        @Nullable
        Drag drag;

        Hit(int x, int y, int w, int h, int[] clip, String label, Runnable click) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.cx0 = clip[0];
            this.cy0 = clip[1];
            this.cx1 = clip[2];
            this.cy1 = clip[3];
            this.label = label;
            this.click = click;
        }

        boolean contains(double mx, double my) {
            return mx >= x && mx < x + w && my >= y && my < y + h
                    && mx >= cx0 && mx < cx1 && my >= cy0 && my < cy1;
        }
    }

    private record Chip(String label, String hit, boolean on, Runnable click) {
    }

    // ---- data shared by every opening
    private static List<Entry> BLOCKS;
    private static List<Entry> MOBS;
    private static List<Entry> PARTICLES;
    private static List<Block> BLOCK_LIST;
    private static final Map<String, Entry> INDEX = new HashMap<>();
    private static final Map<Block, Integer> SHAPE_FLAGS = new HashMap<>();
    private static final Map<String, String> MOD_NAMES = new HashMap<>();
    private static final Map<HologramLayer.Kind, TreeMap<String, Integer>> MOD_COUNTS = new HashMap<>();

    // ---- identity
    private final HologramScreen editor;
    @Nullable
    private final HologramLayer current;
    /** Where Esc/choose go back to: the Studio, or the Creator driving it. */
    private Screen returnTo;
    private final Map<HologramLayer.Kind, List<String>> inHologram = new HashMap<>();
    private final int editorLayerCount;

    // ---- view state
    private HologramLayer.Kind kind;
    private Collection collection = Collection.ALL;
    @Nullable
    private String modFilter;
    @Nullable
    private HologramBlockColors.Hue hue;
    private int shapeMask;
    private String searchText = "";
    private EditBox search;
    private String suggestion = "";
    private List<Entry> results = List.of();
    @Nullable
    private Entry selected;
    @Nullable
    private Entry hovered;
    private int scroll;
    private int cols = 8;
    private int visRows = 6;
    private int frame;

    // ---- block variant state: only values that differ from the block's default state
    private final Map<String, String> props = new LinkedHashMap<>();
    @Nullable
    private Map<String, String> hoverNow;
    @Nullable
    private Map<String, String> hoverNext;
    private int rightScroll;
    private int rightMax;
    private int famPage;
    private int swapPage;

    // ---- preview
    private float yaw = 225.0F;
    private float pitch = 30.0F;
    private boolean orbiting;
    private long lastNanos = System.nanoTime();

    // ---- dropdown
    private boolean dropdownOpen;
    private int dropdownScroll;
    private int popupX, popupY, popupW, popupH;

    // ---- click memory
    @Nullable
    private String lastClickKey;
    private long lastClickMs;

    // ---- layout, set in init()
    private int px0, py0, px1, py1;
    private int sideX, midX, midW, rightX, rightW;
    private int gridX, gridY, gridW, gridH;
    private int pvH;
    private int regionX, regionY, regionW, regionH;

    private final List<Hit> hits = new ArrayList<>();
    @Nullable
    private Hit dragHit;
    private int[] clip = { 0, 0, 100000, 100000 };
    private boolean backgroundDrawn;

    public HologramPickerScreen(HologramScreen editor, int mode, @Nullable HologramLayer current) {
        super(Component.literal("Browse"));
        ensureData();
        this.editor = editor;
        this.returnTo = editor;
        this.current = current;
        this.kind = switch (mode) {
            case HologramScreen.PICK_ENTITIES -> HologramLayer.Kind.ENTITY;
            case HologramScreen.PICK_PARTICLES -> HologramLayer.Kind.PARTICLE;
            default -> HologramLayer.Kind.BLOCK;
        };

        var layers = editor.current().layers();
        this.editorLayerCount = layers.size();
        for (HologramLayer layer : layers) {
            List<String> ids = this.inHologram.computeIfAbsent(layer.kind(), k -> new ArrayList<>());
            if (!ids.contains(layer.id()))
                ids.add(layer.id());
        }
        preselectCurrent();
    }

    // ================================================================ data

    private static void ensureData() {
        if (BLOCKS != null)
            return;

        List<Entry> blocks = new ArrayList<>();
        List<Block> blockList = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.asItem() == Items.AIR)
                continue;
            RenderShape shape = block.defaultBlockState().getRenderShape();
            if (shape != RenderShape.MODEL && shape != RenderShape.ENTITYBLOCK_ANIMATED && !(block instanceof EntityBlock))
                continue;
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
            String name = block.getName().getString();
            blocks.add(new Entry(HologramLayer.Kind.BLOCK, key.toString(), name, key.getNamespace(), block, null,
                    new ItemStack(block.asItem()), name.toLowerCase(Locale.ROOT)));
        }
        blocks.sort(Comparator.comparing(Entry::id));
        blocks = disambiguated(blocks);
        for (Entry e : blocks)
            blockList.add(e.block());

        List<Entry> mobs = new ArrayList<>();
        for (var entry : BuiltInRegistries.ENTITY_TYPE.entrySet()) {
            ResourceLocation key = entry.getKey().location();
            EntityType<?> type = entry.getValue();
            Item egg = SpawnEggItem.byId(type);
            String name = type.getDescription().getString();
            mobs.add(new Entry(HologramLayer.Kind.ENTITY, key.toString(), name, key.getNamespace(), null, type,
                    egg != null ? new ItemStack(egg) : ItemStack.EMPTY, name.toLowerCase(Locale.ROOT)));
        }
        mobs.sort(Comparator.comparing(Entry::id));

        List<Entry> particles = new ArrayList<>();
        for (var entry : BuiltInRegistries.PARTICLE_TYPE.entrySet()) {
            if (!(entry.getValue() instanceof SimpleParticleType))
                continue;
            ResourceLocation key = entry.getKey().location();
            String name = key.getPath().replace('_', ' ');
            particles.add(new Entry(HologramLayer.Kind.PARTICLE, key.toString(), name, key.getNamespace(), null, null,
                    ItemStack.EMPTY, name.toLowerCase(Locale.ROOT)));
        }
        particles.sort(Comparator.comparing(Entry::id));

        for (List<Entry> list : List.of(blocks, mobs, particles)) {
            for (Entry e : list)
                INDEX.put(HologramPickerFavorites.key(e.kind(), e.id()), e);
        }
        BLOCK_LIST = List.copyOf(blockList);
        MOBS = List.copyOf(mobs);
        PARTICLES = List.copyOf(particles);
        BLOCKS = List.copyOf(blocks);
    }

    /** Gives blocks that share a display name a tag (see {@link HologramBlockNames}). */
    private static List<Entry> disambiguated(List<Entry> blocks) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Entry e : blocks)
            names.put(e.id(), e.name());

        Map<String, String> changed = HologramBlockNames.disambiguate(names);
        if (changed.isEmpty())
            return blocks;

        List<Entry> out = new ArrayList<>(blocks.size());
        for (Entry e : blocks) {
            String name = changed.get(e.id());
            out.add(name == null ? e : new Entry(e.kind(), e.id(), name, e.ns(), e.block(), e.type(), e.icon(),
                    name.toLowerCase(Locale.ROOT)));
        }
        return out;
    }

    private static List<Entry> all(HologramLayer.Kind kind) {
        return switch (kind) {
            case BLOCK -> BLOCKS;
            case ENTITY -> MOBS;
            case PARTICLE -> PARTICLES;
            case STRUCTURE, IMAGE, MODEL -> List.of();
        };
    }

    @Nullable
    private static Entry entryOf(HologramLayer.Kind kind, String id) {
        return INDEX.get(HologramPickerFavorites.key(kind, id));
    }

    private static String modName(String ns) {
        return MOD_NAMES.computeIfAbsent(ns, key -> ModList.get().getModContainerById(key)
                .map(container -> container.getModInfo().getDisplayName()).orElse(key));
    }

    private static TreeMap<String, Integer> modCounts(HologramLayer.Kind kind) {
        return MOD_COUNTS.computeIfAbsent(kind, k -> {
            TreeMap<String, Integer> counts = new TreeMap<>();
            for (Entry e : all(k))
                counts.merge(e.ns(), 1, Integer::sum);
            return counts;
        });
    }

    private static int flags(Block block) {
        return SHAPE_FLAGS.computeIfAbsent(block, b -> {
            BlockState state = b.defaultBlockState();
            int f = 0;
            try {
                if (state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))
                    f |= F_FULL;
            } catch (Throwable ignored) {
            }
            if (b instanceof SlabBlock || b instanceof StairBlock)
                f |= F_STAIR;
            if (state.getRenderShape() != RenderShape.MODEL || b instanceof EntityBlock)
                f |= F_ANIM;
            if (state.getLightEmission() > 0)
                f |= F_LIGHT;
            return f;
        });
    }

    private void preselectCurrent() {
        this.selected = null;
        this.props.clear();
        if (this.current != null && this.current.kind() == this.kind) {
            Entry e = entryOf(this.kind, this.current.id());
            if (e != null) {
                this.selected = e;
                if (this.kind == HologramLayer.Kind.BLOCK)
                    this.props.putAll(this.current.props());
                normalise();
            }
        }
    }

    // ================================================================ lifecycle

    @Override
    protected void init() {
        this.px0 = 8;
        this.py0 = 8;
        this.px1 = this.width - 8;
        this.py1 = this.height - 8;
        this.sideX = this.px0;
        this.rightW = Mth.clamp(this.width * 27 / 100, 164, 220);
        this.rightX = this.px1 - this.rightW;
        this.midX = this.sideX + SIDE_W + 4;
        this.midW = this.rightX - 4 - this.midX;

        int panelH = this.py1 - this.py0;
        this.pvH = Mth.clamp(panelH * 34 / 100, 70, 140);

        this.search = new EditBox(this.font, this.midX + 9, this.py0 + 22, this.midW - 18, 10, Component.literal("Query"));
        this.search.setBordered(false);
        this.search.setTextColor(TEXT);
        this.search.setMaxLength(80);
        this.search.setValue(this.searchText);
        this.search.setResponder(text -> {
            this.searchText = text;
            this.scroll = 0;
            refilter(true);
        });
        updateHint();
        addRenderableWidget(this.search);

        HologramBlockColors.queue(BLOCK_LIST);
        refilter(true);
        if (this.selected != null)
            ensureVisible(this.results.indexOf(this.selected));
    }

    @Override
    public void onClose() {
        if (this.minecraft != null)
            this.minecraft.setScreen(this.returnTo);
    }

    /** Come back to {@code screen} instead of the Studio (the Creator opens the browser too). */
    public HologramPickerScreen returnTo(Screen screen) {
        this.returnTo = screen;
        return this;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.backgroundDrawn)
            return;
        this.backgroundDrawn = true;
        graphics.fill(0, 0, this.width, this.height, 0x8C000000);
    }

    private void updateHint() {
        String hint = switch (this.kind) {
            case ENTITY -> "search mobs (@mod $tag)...";
            case PARTICLE -> "search particles (@mod)...";
            default -> "search blocks (@mod #tip $tag)...";
        };
        this.search.setHint(Component.literal(hint).withStyle(style -> style.withColor(DIMMED)));
    }

    // ================================================================ filtering

    private List<Entry> baseList() {
        switch (this.collection) {
            case FAVOURITES:
                return byIds(HologramPickerFavorites.favourites(this.kind));
            case RECENT:
                return byIds(HologramPickerFavorites.recent(this.kind));
            case IN_HOLOGRAM:
                return byIds(this.inHologram.getOrDefault(this.kind, List.of()));
            default:
                return all(this.kind);
        }
    }

    private List<Entry> byIds(List<String> ids) {
        List<Entry> out = new ArrayList<>();
        for (String id : ids) {
            Entry e = entryOf(this.kind, id);
            if (e != null)
                out.add(e);
        }
        return out;
    }

    private void refilter(boolean requeue) {
        HologramSearch query = HologramSearch.parse(this.searchText);
        List<Entry> out = new ArrayList<>();
        List<Entry> preHue = new ArrayList<>();
        for (Entry e : baseList()) {
            if (this.modFilter != null && !this.modFilter.equals(e.ns()))
                continue;
            boolean ok = query.isEmpty() || switch (e.kind()) {
                case BLOCK -> query.matchesBlock(e.block());
                case ENTITY -> query.matchesEntity(e.type(), e.id(), e.name());
                case PARTICLE -> query.matchesParticle(e.id());
                case STRUCTURE, IMAGE, MODEL -> false;
            };
            if (!ok)
                continue;
            if (this.kind == HologramLayer.Kind.BLOCK && this.shapeMask != 0
                    && (flags(e.block()) & this.shapeMask) != this.shapeMask)
                continue;
            preHue.add(e);
        }

        // A search ranks by how well the name answers it; an empty one keeps the list's own (id) order.
        // The sort is stable, so equal ranks stay in id order.
        if (!query.isEmpty()) {
            preHue.sort(Comparator.<Entry>comparingInt(e -> query.rank(e.lower()))
                    .thenComparingInt(e -> e.lower().length()));
        }

        if (this.kind == HologramLayer.Kind.BLOCK && this.hue != null) {
            if (requeue) {
                List<Block> blocks = new ArrayList<>(preHue.size());
                for (Entry e : preHue)
                    blocks.add(e.block());
                HologramBlockColors.queue(blocks);
            }
            for (Entry e : preHue) {
                if (HologramBlockColors.hueOf(e.block()) == this.hue)
                    out.add(e);
            }
        } else {
            out = preHue;
        }

        this.results = out;
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
        updateSuggestion();
    }

    private void updateSuggestion() {
        this.suggestion = "";
        if (this.search == null)
            return;
        // An older completion must not linger on the box once the text it completed is gone.
        this.search.setSuggestion(null);
        String raw = this.searchText;
        if (raw.isEmpty())
            return;
        for (int i = 0; i < raw.length(); i++) {
            if (" @#$&-|\"".indexOf(raw.charAt(i)) >= 0) {
                this.search.setSuggestion("");
                return;
            }
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        for (Entry e : this.results) {
            if (e.lower().startsWith(lower) && e.name().length() > raw.length()) {
                this.suggestion = e.name().substring(raw.length());
                break;
            }
        }
        this.search.setSuggestion(this.suggestion.isEmpty() ? null : this.suggestion);
    }

    private int maxScroll() {
        int rows = (this.results.size() + this.cols - 1) / Math.max(1, this.cols);
        return Math.max(0, rows - this.visRows);
    }

    private void ensureVisible(int index) {
        if (index < 0)
            return;
        int row = index / Math.max(1, this.cols);
        if (row < this.scroll)
            this.scroll = row;
        else if (row >= this.scroll + this.visRows)
            this.scroll = row - this.visRows + 1;
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());
    }

    // ================================================================ state changes

    private void setKind(HologramLayer.Kind next) {
        if (next == this.kind)
            return;
        this.kind = next;
        this.modFilter = null;
        this.hue = null;
        this.shapeMask = 0;
        this.scroll = 0;
        this.rightScroll = 0;
        this.dropdownOpen = false;
        preselectCurrent();
        updateHint();
        refilter(true);
        if (next == HologramLayer.Kind.BLOCK)
            HologramBlockColors.queue(BLOCK_LIST);
    }

    private void setCollection(Collection next) {
        this.collection = next;
        this.scroll = 0;
        refilter(true);
    }

    private void setHue(@Nullable HologramBlockColors.Hue next) {
        this.hue = this.hue == next ? null : next;
        this.scroll = 0;
        refilter(true);
        if (this.hue == null)
            HologramBlockColors.queue(BLOCK_LIST);
    }

    private void toggleShape(int bit) {
        this.shapeMask ^= bit;
        this.scroll = 0;
        refilter(true);
    }

    private void setMod(@Nullable String ns) {
        this.modFilter = ns;
        this.dropdownOpen = false;
        this.scroll = 0;
        refilter(true);
    }

    private void select(Entry entry) {
        this.selected = entry;
        this.props.clear();
        if (this.current != null && this.current.kind() == entry.kind() && this.current.id().equals(entry.id())
                && entry.kind() == HologramLayer.Kind.BLOCK)
            this.props.putAll(this.current.props());
        normalise();
        this.famPage = 0;
        this.swapPage = 0;
        this.rightScroll = 0;
    }

    /** Switches block while keeping every property the new block also has, with a value it accepts. */
    private void swapBlock(Entry entry) {
        if (entry.block() == null)
            return;
        Map<String, String> old = new LinkedHashMap<>(this.props);
        this.selected = entry;
        this.props.clear();
        BlockState next = entry.block().defaultBlockState();
        for (Map.Entry<String, String> p : old.entrySet()) {
            Property<?> property = HologramStates.property(next, p.getKey());
            if (property != null && property.getValue(p.getValue()).isPresent())
                this.props.put(p.getKey(), p.getValue());
        }
        normalise();
        this.famPage = 0;
        int index = this.results.indexOf(entry);
        if (index >= 0)
            ensureVisible(index);
    }

    private void normalise() {
        if (this.selected == null || this.selected.block() == null) {
            this.props.clear();
            return;
        }
        BlockState def = this.selected.block().defaultBlockState();
        this.props.entrySet().removeIf(p -> {
            Property<?> property = HologramStates.property(def, p.getKey());
            return property == null || property.getValue(p.getValue()).isEmpty()
                    || HologramStates.valueOf(def, property).equals(p.getValue());
        });
    }

    private void setProp(String name, String value) {
        if (this.selected == null || this.selected.block() == null)
            return;
        this.props.put(name, value);
        normalise();
    }

    private Map<String, String> merged(Map<String, String> over) {
        Map<String, String> out = new LinkedHashMap<>(this.props);
        out.putAll(over);
        if (this.selected != null && this.selected.block() != null) {
            BlockState def = this.selected.block().defaultBlockState();
            out.entrySet().removeIf(p -> {
                Property<?> property = HologramStates.property(def, p.getKey());
                return property == null || HologramStates.valueOf(def, property).equals(p.getValue());
            });
        }
        return out;
    }

    private void toggleFavourite(@Nullable Entry entry) {
        if (entry == null)
            return;
        HologramPickerFavorites.toggleFavourite(entry.kind(), entry.id());
        if (this.collection == Collection.FAVOURITES)
            refilter(false);
    }

    private void apply(boolean asNew) {
        Entry entry = this.selected;
        if (entry == null || this.minecraft == null)
            return;
        boolean structureSelected = this.current != null && this.current.kind() == HologramLayer.Kind.STRUCTURE;
        if (!asNew && structureSelected)
            return;
        boolean forceNew = asNew || this.current == null;
        if (forceNew && this.editorLayerCount >= HologramLayer.MAX_LAYERS)
            return;
        HologramPickerFavorites.addRecent(entry.kind(), entry.id());
        Map<String, String> chosen = entry.kind() == HologramLayer.Kind.BLOCK
                ? new LinkedHashMap<>(this.props) : Map.of();
        this.editor.applyPickerChoice(entry.kind(), entry.id(), chosen, forceNew);
        this.minecraft.setScreen(this.returnTo);
    }

    // ================================================================ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (this.dropdownOpen && !inPopup(mx, my)) {
            this.dropdownOpen = false;
            return true;
        }
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (!hit.contains(mx, my))
                continue;
            if (this.search != null) {
                this.search.setFocused(false);
                setFocused(null);
            }
            if (button == 1 && hit.right != null) {
                hit.right.run();
                return true;
            }
            if (button == 0) {
                hit.click.run();
                if (hit.drag != null) {
                    this.dragHit = hit;
                    hit.drag.drag(mx, my, 0.0, 0.0);
                }
                return true;
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        this.dragHit = null;
        this.orbiting = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (this.dragHit != null && button == 0 && this.dragHit.drag != null) {
            this.dragHit.drag.drag(mx, my, dx, dy);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        int dir = sy > 0 ? -1 : (sy < 0 ? 1 : 0);
        if (dir == 0)
            return false;
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.wheel != null && hit.contains(mx, my)) {
                hit.wheel.accept(dir);
                return true;
            }
        }
        if (mx >= this.regionX && mx < this.regionX + this.regionW && my >= this.regionY && my < this.regionY + this.regionH) {
            this.rightScroll = Mth.clamp(this.rightScroll + dir * 16, 0, this.rightMax);
            return true;
        }
        if (mx >= this.gridX && mx < this.gridX + this.gridW + 8 && my >= this.gridY && my < this.gridY + this.gridH) {
            this.scroll = Mth.clamp(this.scroll + dir * (hasShiftDown() ? 3 : 1), 0, maxScroll());
            return true;
        }
        return super.mouseScrolled(mx, my, sx, sy);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (this.dropdownOpen) {
                this.dropdownOpen = false;
                return true;
            }
            onClose();
            return true;
        }
        boolean typing = this.search != null && this.search.isFocused();
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            apply(hasShiftDown());
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            if (typing && !this.suggestion.isEmpty()) {
                this.search.setValue(this.searchText + this.suggestion);
                this.search.moveCursorToEnd(false);
            } else {
                setFocused(this.search);
                this.search.setFocused(true);
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_UP || keyCode == GLFW.GLFW_KEY_DOWN) {
            move(0, keyCode == GLFW.GLFW_KEY_UP ? -1 : 1);
            return true;
        }
        if (!typing) {
            if (keyCode == GLFW.GLFW_KEY_LEFT || keyCode == GLFW.GLFW_KEY_RIGHT) {
                move(keyCode == GLFW.GLFW_KEY_LEFT ? -1 : 1, 0);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_F) {
                toggleFavourite(this.selected);
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_SLASH) {
                setFocused(this.search);
                this.search.setFocused(true);
                return true;
            }
        }
        if (keyCode == GLFW.GLFW_KEY_F && hasControlDown()) {
            setFocused(this.search);
            this.search.setFocused(true);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char c, int modifiers) {
        if (this.search != null && !this.search.isFocused() && Character.isLetterOrDigit(c)
                && c != 'f' && c != 'F') {
            setFocused(this.search);
            this.search.setFocused(true);
            return this.search.charTyped(c, modifiers);
        }
        if (this.search != null && !this.search.isFocused() && (c == 'f' || c == 'F' || c == '/'))
            return true;
        return super.charTyped(c, modifiers);
    }

    private void move(int dx, int dy) {
        if (this.results.isEmpty())
            return;
        int index = this.selected == null ? -1 : this.results.indexOf(this.selected);
        int next;
        if (index < 0)
            next = 0;
        else
            next = Mth.clamp(index + dx + dy * this.cols, 0, this.results.size() - 1);
        Entry entry = this.results.get(next);
        if (entry != this.selected)
            select(entry);
        ensureVisible(next);
    }

    private boolean inPopup(double mx, double my) {
        return mx >= this.popupX && mx < this.popupX + this.popupW && my >= this.popupY && my < this.popupY + this.popupH;
    }

    // ================================================================ hit helpers

    private Hit addHit(int x, int y, int w, int h, String label, Runnable click) {
        Hit hit = new Hit(x, y, w, h, this.clip, label, click);
        this.hits.add(hit);
        return hit;
    }

    private void setClip(int x0, int y0, int x1, int y1) {
        this.clip = new int[] { x0, y0, x1, y1 };
    }

    private void clearClip() {
        this.clip = new int[] { 0, 0, 100000, 100000 };
    }

    private static boolean over(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private Hit chip(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String label, String hitLabel,
                     boolean on, Runnable click) {
        boolean hot = over(mx, my, x, y, w, h) && over(mx, my, this.clip[0], this.clip[1],
                this.clip[2] - this.clip[0], this.clip[3] - this.clip[1]);
        g.fill(x, y, x + w, y + h, on ? CHIP_ON : (hot ? CHIP_HOT : CHIP));
        g.renderOutline(x, y, w, h, on ? ACCENT : (hot ? 0x66FFFFFF : 0x33FFFFFF));
        String text = this.font.plainSubstrByWidth(label, w - 4);
        g.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y + (h - 8) / 2 + 1,
                on ? TEXT : (hot ? TEXT : MUTED), false);
        return addHit(x, y, w, h, hitLabel, click);
    }

    private void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        g.renderOutline(x, y, w, h, BORDER);
    }

    private void tiny(GuiGraphics g, String text, float x, float y, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(0.5F, 0.5F, 1.0F);
        g.drawString(this.font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    // ================================================================ render

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        long now = System.nanoTime();
        float dt = Math.min(0.1F, (now - this.lastNanos) / 1.0E9F);
        this.lastNanos = now;
        if (!this.orbiting)
            this.yaw = (this.yaw + dt * 28.0F) % 360.0F;
        this.frame++;
        this.backgroundDrawn = false;
        HologramCamera.setMovementBlocked(true);

        this.hits.clear();
        clearClip();
        this.hovered = null;
        this.hoverNow = this.hoverNext;
        this.hoverNext = null;

        if (!HologramBlockColors.done()) {
            int done = HologramBlockColors.step(3_000_000L);
            if (done > 0 && this.hue != null && this.kind == HologramLayer.Kind.BLOCK && this.frame % 6 == 0)
                refilter(false);
        }

        renderBackground(g, mx, my, partial);
        double secs = HologramRenderer.animSeconds();

        drawSidebar(g, mx, my);
        drawCentre(g, mx, my, secs);
        drawRight(g, mx, my, secs);
        if (this.dropdownOpen)
            drawDropdown(g, mx, my);

        super.render(g, mx, my, partial);
    }

    // ---------------------------------------------------------------- sidebar

    private void drawSidebar(GuiGraphics g, int mx, int my) {
        int x = this.sideX;
        int w = SIDE_W;
        panel(g, x, this.py0, w, this.py1 - this.py0);
        g.drawString(this.font, "Browse", x + 8, this.py0 + 6, ACCENT, false);

        int y = this.py0 + 20;
        g.drawString(this.font, "TYPE", x + 8, y, DIMMED, false);
        y += 11;
        HologramLayer.Kind[] kinds = {
                HologramLayer.Kind.BLOCK, HologramLayer.Kind.ENTITY, HologramLayer.Kind.PARTICLE
        };
        String[] names = { "Blocks", "Mobs", "Particles" };
        for (int i = 0; i < kinds.length; i++) {
            HologramLayer.Kind k = kinds[i];
            String count = String.valueOf(all(k).size());
            row(g, mx, my, x + 4, y, w - 8, names[i], count, this.kind == k, "tab:" + k.name(), () -> setKind(k));
            y += 16;
        }

        y += 6;
        g.drawString(this.font, "COLLECTIONS", x + 8, y, DIMMED, false);
        y += 11;
        for (Collection c : Collection.values()) {
            String count = switch (c) {
                case ALL -> String.valueOf(all(this.kind).size());
                case FAVOURITES -> String.valueOf(byIds(HologramPickerFavorites.favourites(this.kind)).size());
                case RECENT -> String.valueOf(byIds(HologramPickerFavorites.recent(this.kind)).size());
                case IN_HOLOGRAM -> String.valueOf(this.inHologram.getOrDefault(this.kind, List.of()).size());
            };
            row(g, mx, my, x + 4, y, w - 8, c.label, count, this.collection == c, "col:" + c.name(),
                    () -> setCollection(c));
            y += 16;
        }

        y += 6;
        g.drawString(this.font, "MOD", x + 8, y, DIMMED, false);
        y += 11;
        String modLabel = this.modFilter == null ? "All mods" : modName(this.modFilter);
        chip(g, mx, my, x + 4, y, w - 8, 15, modLabel + " ▾", "mod", this.modFilter != null,
                () -> {
                    this.dropdownOpen = !this.dropdownOpen;
                    this.dropdownScroll = 0;
                });
        this.popupX = x + 4;
        this.popupY = y + 17;
    }

    private void row(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String count, boolean on,
                     String hitLabel, Runnable click) {
        boolean hot = over(mx, my, x, y, w, 15);
        g.fill(x, y, x + w, y + 15, on ? CHIP_ON : (hot ? CHIP_HOT : 0x00000000));
        if (on)
            g.fill(x, y, x + 2, y + 15, ACCENT);
        int countW = this.font.width(count);
        String text = this.font.plainSubstrByWidth(label, w - countW - 12);
        g.drawString(this.font, text, x + 6, y + 4, on ? TEXT : (hot ? TEXT : MUTED), false);
        g.drawString(this.font, count, x + w - countW - 4, y + 4, DIMMED, false);
        addHit(x, y, w, 15, hitLabel, click);
    }

    private void drawDropdown(GuiGraphics g, int mx, int my) {
        List<String> options = new ArrayList<>();
        options.add(null);
        TreeMap<String, Integer> counts = modCounts(this.kind);
        List<String> namespaces = new ArrayList<>(counts.keySet());
        namespaces.sort(Comparator.comparing(ns -> modName(ns).toLowerCase(Locale.ROOT)));
        options.addAll(namespaces);

        int rowH = 12;
        int fit = Math.max(3, (this.py1 - this.popupY - 6) / rowH);
        int rows = Math.min(options.size(), fit);
        this.dropdownScroll = Mth.clamp(this.dropdownScroll, 0, Math.max(0, options.size() - rows));
        this.popupW = 176;
        this.popupH = rows * rowH + 4;

        // Added first so the option rows (added after it) win the click and the backdrop only takes the wheel.
        Hit wheel = addHit(this.popupX, this.popupY, this.popupW, this.popupH, "popup", () -> {
        });
        wheel.wheel = dir -> this.dropdownScroll = Mth.clamp(this.dropdownScroll + dir * 2, 0,
                Math.max(0, options.size() - rows));

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F);
        g.fill(this.popupX, this.popupY, this.popupX + this.popupW, this.popupY + this.popupH, 0xFF0F1115);
        g.renderOutline(this.popupX, this.popupY, this.popupW, this.popupH, ACCENT);
        for (int i = 0; i < rows; i++) {
            String ns = options.get(this.dropdownScroll + i);
            int y = this.popupY + 2 + i * rowH;
            boolean hot = over(mx, my, this.popupX + 2, y, this.popupW - 4, rowH);
            boolean on = ns == null ? this.modFilter == null : ns.equals(this.modFilter);
            if (hot || on)
                g.fill(this.popupX + 2, y, this.popupX + this.popupW - 2, y + rowH, on ? CHIP_ON : CHIP_HOT);
            String label = ns == null ? "All mods" : modName(ns);
            String count = ns == null ? String.valueOf(all(this.kind).size()) : String.valueOf(counts.get(ns));
            int countW = this.font.width(count);
            g.drawString(this.font, this.font.plainSubstrByWidth(label, this.popupW - countW - 16),
                    this.popupX + 6, y + 2, hot || on ? TEXT : MUTED, false);
            g.drawString(this.font, count, this.popupX + this.popupW - countW - 6, y + 2, DIMMED, false);
            addHit(this.popupX + 2, y, this.popupW - 4, rowH, "modopt:" + (ns == null ? "" : ns), () -> setMod(ns));
        }
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- centre

    private void drawCentre(GuiGraphics g, int mx, int my, double secs) {
        panel(g, this.midX, this.py0, this.midW, this.py1 - this.py0);
        String title = switch (this.kind) {
            case BLOCK -> "Blocks";
            case ENTITY -> "Mobs";
            case PARTICLE -> "Particles";
            case STRUCTURE -> "Structures";
            case IMAGE -> "Images";
            case MODEL -> "Models";
        };
        g.drawString(this.font, title, this.midX + 8, this.py0 + 6, ACCENT, false);
        String closeLabel = "Close (Esc)";
        int closeW = this.font.width(closeLabel) + 10;
        chip(g, mx, my, this.midX + this.midW - closeW - 6, this.py0 + 3, closeW, 13, closeLabel, "close", false, this::onClose);

        // search
        int sy = this.py0 + 18;
        g.fill(this.midX + 6, sy, this.midX + this.midW - 6, sy + 15, WELL);
        g.renderOutline(this.midX + 6, sy, this.midW - 12, 15,
                this.search != null && this.search.isFocused() ? ACCENT : 0x33FFFFFF);

        int y = sy + 19;
        if (this.kind == HologramLayer.Kind.BLOCK) {
            int x = this.midX + 6;
            int swatch = 13;
            chip(g, mx, my, x, y, 24, swatch, "All", "hue:all", this.hue == null, () -> setHue(null));
            x += 28;
            for (HologramBlockColors.Hue h : HologramBlockColors.Hue.values()) {
                boolean hot = over(mx, my, x, y, swatch, swatch);
                g.fill(x, y, x + swatch, y + swatch, h.swatch);
                g.renderOutline(x - 1, y - 1, swatch + 2, swatch + 2,
                        this.hue == h ? ACCENT : (hot ? 0xAAFFFFFF : 0x44000000));
                if (this.hue == h)
                    g.renderOutline(x - 2, y - 2, swatch + 4, swatch + 4, ACCENT);
                addHit(x, y, swatch, swatch, "hue:" + h.label, () -> setHue(h));
                x += swatch + 4;
            }
            y += 17;
            x = this.midX + 6;
            for (int i = 0; i < SHAPE_LABELS.length; i++) {
                int bit = 1 << i;
                int cw = this.font.width(SHAPE_LABELS[i]) + 10;
                chip(g, mx, my, x, y, cw, 13, SHAPE_LABELS[i], "shape:" + bit, (this.shapeMask & bit) != 0,
                        () -> toggleShape(bit));
                x += cw + 4;
            }
            y += 17;
        }

        this.gridX = this.midX + 6;
        this.gridY = y + 1;
        // The hint wraps onto a second line on a narrow centre column rather than being cut off.
        String foot = this.results.size() + " results · arrows move · Enter replace · F favourite · Shift+click add as new layer";
        if (this.kind == HologramLayer.Kind.BLOCK && this.hue != null && !HologramBlockColors.done())
            foot = this.results.size() + " results · colouring "
                    + Math.round(HologramBlockColors.progress() * 100.0F) + "%";
        var footLines = this.font.split(Component.literal(foot), Math.max(40, this.midW - 16));
        int footN = Math.max(1, Math.min(2, footLines.size()));
        int footer = this.py1 - 5 - 10 * footN;
        this.gridW = Math.max(PITCH, ((this.midW - 12 - 8) / PITCH) * PITCH);
        this.cols = Math.max(1, this.gridW / PITCH);
        this.gridH = footer - 3 - this.gridY;
        this.visRows = Math.max(1, this.gridH / PITCH);
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll());

        g.fill(this.gridX - 1, this.gridY - 1, this.gridX + this.gridW + 1, this.gridY + this.visRows * PITCH + 1, WELL);

        // tiles
        int first = this.scroll * this.cols;
        g.enableScissor(this.gridX, this.gridY, this.gridX + this.gridW, this.gridY + this.visRows * PITCH);
        setClip(this.gridX, this.gridY, this.gridX + this.gridW, this.gridY + this.visRows * PITCH);
        for (int i = 0; i < this.cols * this.visRows; i++) {
            int index = first + i;
            if (index >= this.results.size())
                break;
            Entry e = this.results.get(index);
            int tx = this.gridX + (i % this.cols) * PITCH + 1;
            int ty = this.gridY + (i / this.cols) * PITCH + 1;
            boolean hot = over(mx, my, tx, ty, TILE, TILE)
                    && over(mx, my, this.gridX, this.gridY, this.gridW, this.visRows * PITCH);
            if (hot)
                this.hovered = e;
            drawTile(g, e, tx, ty, e == this.selected, hot, secs);
            Hit hit = addHit(tx, ty, TILE, TILE, "tile:" + e.id(), () -> clickTile(e));
            hit.right = () -> toggleFavourite(e);
        }
        g.flush();
        g.disableScissor();
        clearClip();

        if (this.results.isEmpty()) {
            String none = this.collection == Collection.ALL ? "Nothing matches" : "Nothing here yet";
            g.drawString(this.font, none, this.gridX + 8, this.gridY + 8, MUTED, false);
        }

        // scrollbar
        int max = maxScroll();
        int barX = this.gridX + this.gridW + 3;
        int barH = this.visRows * PITCH;
        g.fill(barX, this.gridY, barX + 4, this.gridY + barH, 0x22FFFFFF);
        if (max > 0) {
            int total = (this.results.size() + this.cols - 1) / this.cols;
            int thumb = Math.max(14, barH * this.visRows / Math.max(1, total));
            int thumbY = this.gridY + (barH - thumb) * this.scroll / max;
            g.fill(barX, thumbY, barX + 4, thumbY + thumb, ACCENT);
            int top = this.gridY;
            int travel = Math.max(1, barH - thumb);
            Hit bar = addHit(barX - 2, this.gridY, 8, barH, "scrollbar", () -> {
            });
            bar.drag = (dx, dy, ddx, ddy) -> this.scroll = Mth.clamp(
                    (int) Math.round((dy - top - thumb / 2.0) / travel * max), 0, max);
        }

        // footer
        for (int i = 0; i < footN && i < footLines.size(); i++)
            g.drawString(this.font, footLines.get(i), this.midX + 8, footer + 3 + i * 10, MUTED, false);
    }

    private void clickTile(Entry e) {
        long now = System.currentTimeMillis();
        String key = e.kind() + ":" + e.id();
        boolean twice = key.equals(this.lastClickKey) && now - this.lastClickMs < 350L;
        this.lastClickKey = key;
        this.lastClickMs = now;
        if (this.selected != e)
            select(e);
        if (hasShiftDown())
            apply(true);
        else if (twice)
            apply(false);
    }

    private void drawTile(GuiGraphics g, Entry e, int x, int y, boolean sel, boolean hot, double secs) {
        g.fill(x, y, x + TILE, y + TILE, sel ? 0xFF16363C : (hot ? CHIP_HOT : TILE_BG));
        if (sel) {
            g.fill(x, y, x + TILE, y + 2, ACCENT);
            g.fill(x, y + TILE - 2, x + TILE, y + TILE, ACCENT);
            g.fill(x, y, x + 2, y + TILE, ACCENT);
            g.fill(x + TILE - 2, y, x + TILE, y + TILE, ACCENT);
        }

        switch (e.kind()) {
            case BLOCK -> drawStack(g, e.icon(), x + 4, y + 2);
            case ENTITY -> {
                if (!e.icon().isEmpty()) {
                    drawStack(g, e.icon(), x + 4, y + 2);
                } else {
                    String letters = e.name().length() >= 2 ? e.name().substring(0, 2) : e.name();
                    g.drawString(this.font, letters, x + (TILE - this.font.width(letters)) / 2, y + 8, MUTED, false);
                }
            }
            case PARTICLE -> HologramPickerPreview.drawParticleIcon(g, e.id(), x + 4, y + 2, 20, secs);
            case STRUCTURE -> {
            }
        }

        String sub = e.kind() == HologramLayer.Kind.BLOCK
                ? ("minecraft".equals(e.ns()) ? "" : e.ns())
                : e.name();
        if (!sub.isEmpty()) {
            String cut = this.font.plainSubstrByWidth(sub, (TILE - 2) * 2);
            tiny(g, cut, x + 2, y + TILE - 6, DIMMED);
        }

        if (HologramPickerFavorites.isFavourite(e.kind(), e.id())) {
            g.pose().pushPose();
            g.pose().translate(0.0F, 0.0F, 300.0F);
            g.drawString(this.font, "★", x + TILE - 9, y + 2, STAR, true);
            g.pose().popPose();
        }
    }

    private void drawStack(GuiGraphics g, ItemStack stack, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(1.25F, 1.25F, 1.0F);
        g.renderItem(stack, 0, 0);
        g.pose().popPose();
    }

    // ---------------------------------------------------------------- right panel

    private void drawRight(GuiGraphics g, int mx, int my, double secs) {
        panel(g, this.rightX, this.py0, this.rightW, this.py1 - this.py0);
        Entry shown = this.hovered != null ? this.hovered : this.selected;

        int bx = this.rightX + 6;
        int bw = this.rightW - 12;
        int by = this.py0 + 6;
        g.fill(bx, by, bx + bw, by + this.pvH, WELL);
        g.renderOutline(bx, by, bw, this.pvH, 0x33FFFFFF);

        if (shown == null) {
            g.drawString(this.font, "Nothing selected", bx + 8, by + 8, MUTED, false);
        } else {
            drawPreview(g, shown, bx, by, bw, this.pvH, secs);
        }
        Hit orbit = addHit(bx, by, bw, this.pvH, "preview", () -> this.orbiting = true);
        orbit.drag = (dx, dy, ddx, ddy) -> {
            this.yaw += (float) ddx * 1.2F;
            this.pitch = Mth.clamp(this.pitch + (float) ddy * 0.8F, -10.0F, 70.0F);
        };
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 300.0F);
        tiny(g, "drag to orbit", bx + 4, by + this.pvH - 8, DIMMED);
        g.pose().popPose();

        int y = by + this.pvH + 5;
        Entry target = this.selected;
        if (shown != null) {
            g.drawString(this.font, this.font.plainSubstrByWidth(shown.name(), bw), bx, y, TEXT, false);
            g.drawString(this.font, this.font.plainSubstrByWidth(shown.id(), bw), bx, y + 10, MUTED, false);
        }
        y += 23;

        // buttons
        boolean fav = target != null && HologramPickerFavorites.isFavourite(target.kind(), target.id());
        int star = 18;
        Hit starHit = chip(g, mx, my, bx, y, star, 15, "★", "btn:fav", fav, () -> toggleFavourite(this.selected));
        if (fav) {
            g.drawString(this.font, "★", bx + (star - this.font.width("★")) / 2, y + 4, STAR, false);
        }
        starHit.right = starHit.click;
        int rest = bw - star - 4;
        int replaceW = rest * 40 / 100;
        boolean canReplace = target != null && this.current != null
                && this.current.kind() != HologramLayer.Kind.STRUCTURE;
        boolean canAdd = target != null && this.editorLayerCount < HologramLayer.MAX_LAYERS;
        drawAction(g, mx, my, bx + star + 2, y, replaceW, "Replace", "btn:replace", canReplace, () -> apply(false));
        drawAction(g, mx, my, bx + star + 4 + replaceW, y, rest - replaceW, "+ Add layer", "btn:add", canAdd,
                () -> apply(true));
        y += 20;

        this.regionX = bx;
        this.regionY = y;
        this.regionW = bw;
        this.regionH = Math.max(0, this.py1 - 5 - y);
        if (this.regionH < 12)
            return;

        g.fill(this.regionX, this.regionY - 1, this.regionX + bw, this.regionY, 0x33FFFFFF);
        g.enableScissor(this.regionX, this.regionY, this.regionX + this.regionW, this.regionY + this.regionH);
        setClip(this.regionX, this.regionY, this.regionX + this.regionW, this.regionY + this.regionH);
        int end = this.regionY - this.rightScroll;
        if (this.selected != null && this.kind == HologramLayer.Kind.BLOCK && this.selected.block() != null)
            end = drawBlockControls(g, mx, my, this.regionX, this.regionY + 3 - this.rightScroll, this.regionW - 6);
        else if (this.selected != null)
            end = drawNote(g, this.regionX, this.regionY + 3 - this.rightScroll, this.regionW - 6);
        g.flush();
        g.disableScissor();
        clearClip();

        int content = end - (this.regionY - this.rightScroll);
        this.rightMax = Math.max(0, content - this.regionH + 4);
        this.rightScroll = Mth.clamp(this.rightScroll, 0, this.rightMax);
        if (this.rightMax > 0) {
            int barX = this.regionX + this.regionW - 3;
            int thumb = Math.max(12, this.regionH * this.regionH / Math.max(1, content));
            int thumbY = this.regionY + (this.regionH - thumb) * this.rightScroll / this.rightMax;
            g.fill(barX, this.regionY, barX + 3, this.regionY + this.regionH, 0x22FFFFFF);
            g.fill(barX, thumbY, barX + 3, thumbY + thumb, ACCENT);
        }
    }

    private void drawAction(GuiGraphics g, int mx, int my, int x, int y, int w, String label, String hitLabel,
                            boolean enabled, Runnable click) {
        if (!enabled) {
            g.fill(x, y, x + w, y + 15, 0x55161C24);
            g.renderOutline(x, y, w, 15, 0x22FFFFFF);
            String text = this.font.plainSubstrByWidth(label, w - 4);
            g.drawString(this.font, text, x + (w - this.font.width(text)) / 2, y + 4, 0xFF556070, false);
            return;
        }
        chip(g, mx, my, x, y, w, 15, label, hitLabel, true, click);
    }

    private int drawNote(GuiGraphics g, int x, int y, int w) {
        String note = this.kind == HologramLayer.Kind.ENTITY
                ? "Mobs have no variants. Use Replace to swap the selected layer, or + Add layer."
                : "Particles have no variants. Use Replace to swap the selected layer, or + Add layer.";
        for (var line : this.font.split(Component.literal(note), w)) {
            g.drawString(this.font, line, x, y, MUTED, false);
            y += 10;
        }
        return y;
    }

    // ---------------------------------------------------------------- preview

    private void drawPreview(GuiGraphics g, Entry e, int x, int y, int w, int h, double secs) {
        int size = Math.min(w, h) - 6;
        switch (e.kind()) {
            case BLOCK -> {
                Map<String, String> p = this.hoverNow != null && e == this.selected ? this.hoverNow
                        : (e == this.selected ? this.props : Map.of());
                BlockState state = HologramStates.resolve(e.id(), p);
                if (state != null)
                    drawBlock3d(g, state, e.block(), x + w / 2.0F, y + h / 2.0F, size * 0.58F, this.yaw, this.pitch);
            }
            case ENTITY -> {
                // A little inside the box: wide models (squids, dragons) otherwise touch the scissor edge.
                int fit = Math.round(size * 0.86F);
                int ex = x + (w - fit) / 2;
                int ey = y + (h - fit) / 2;
                if (!HologramPickerPreview.drawEntityIcon(g, e.id(), ex, ey, fit, this.yaw, secs)
                        && !e.icon().isEmpty()) {
                    g.pose().pushPose();
                    g.pose().translate(x + w / 2.0F - 24, y + h / 2.0F - 24, 0.0F);
                    g.pose().scale(3.0F, 3.0F, 1.0F);
                    g.renderItem(e.icon(), 0, 0);
                    g.pose().popPose();
                }
            }
            case PARTICLE -> {
                int ps = Math.min(size, 80);
                HologramPickerPreview.drawParticleIcon(g, e.id(), x + (w - ps) / 2, y + (h - ps) / 2, ps, secs);
            }
            case STRUCTURE -> {
            }
        }
    }

    /**
     * Draws a block the way the inventory draws one: a unit cube scaled to {@code size} pixels, tilted and turned,
     * lit from the same direction as GUI items. Blocks with no baked model (chests, signs) fall back to their item.
     */
    private void drawBlock3d(GuiGraphics g, BlockState state, Block block, float cx, float cy, float size,
                             float yawDeg, float pitchDeg) {
        if (state.getRenderShape() != RenderShape.MODEL) {
            float s = size * 1.35F / 16.0F;
            g.pose().pushPose();
            g.pose().translate(cx - 8.0F * s, cy - 8.0F * s, 0.0F);
            g.pose().scale(s, s, 1.0F);
            g.renderItem(new ItemStack(block.asItem()), 0, 0);
            g.pose().popPose();
            return;
        }
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(cx, cy, 120.0F);
        pose.scale(size, -size, size);
        pose.mulPose(new Quaternionf().rotationXYZ((float) Math.toRadians(pitchDeg), (float) Math.toRadians(yawDeg), 0.0F));
        pose.translate(-0.5F, -0.5F, -0.5F);
        Lighting.setupFor3DItems();
        try {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, g.bufferSource(), 0xF000F0,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        } catch (Throwable ignored) {
        }
        g.flush();
        pose.popPose();
    }

    // ---------------------------------------------------------------- block controls

    private int drawBlockControls(GuiGraphics g, int mx, int my, int x, int y, int w) {
        Entry sel = this.selected;
        Block block = sel.block();
        BlockState state = HologramStates.resolve(sel.id(), this.props);
        if (state == null)
            return y;

        // --- family: same material (start of the path), other shapes
        List<Block> material = HologramPickerFamilies.sameMaterial(block, BLOCK_LIST);
        if (!material.isEmpty()) {
            y = section(g, x, y, "Also in this family");
            y = chipsFor(g, mx, my, x, y, w, material, b -> HologramPickerFamilies.tail(b, block), "fam:",
                    this.famPage, 8, () -> this.famPage++);
        }

        // --- per property controls
        List<Property<?>> properties = HologramStates.properties(state);
        if (!properties.isEmpty()) {
            y = section(g, x, y, "Variants");
            for (Property<?> property : properties)
                y = drawProperty(g, mx, my, x, y, w, state, property);
        }

        // --- thumbnails of the first one or two properties
        List<Map<String, String>> variants = variantMaps(properties);
        if (variants.size() > 1) {
            y = section(g, x, y, "All variants");
            int cell = 26;
            int perRow = Math.max(1, w / cell);
            for (int i = 0; i < variants.size(); i++) {
                int cx = x + (i % perRow) * cell;
                int cy = y + (i / perRow) * cell;
                Map<String, String> vp = variants.get(i);
                boolean on = vp.equals(this.props);
                boolean hot = over(mx, my, cx, cy, cell - 2, cell - 2)
                        && over(mx, my, this.clip[0], this.clip[1], this.clip[2] - this.clip[0], this.clip[3] - this.clip[1]);
                if (hot)
                    this.hoverNext = vp;
                if (cy + cell >= this.regionY && cy <= this.regionY + this.regionH) {
                    g.fill(cx, cy, cx + cell - 2, cy + cell - 2, on ? CHIP_ON : (hot ? CHIP_HOT : TILE_BG));
                    g.renderOutline(cx, cy, cell - 2, cell - 2, on ? ACCENT : 0x22FFFFFF);
                    BlockState st = HologramStates.resolve(sel.id(), vp);
                    if (st != null)
                        drawBlock3d(g, st, block, cx + (cell - 2) / 2.0F, cy + (cell - 2) / 2.0F, (cell - 2) * 0.5F, 225.0F, 30.0F);
                }
                addHit(cx, cy, cell - 2, cell - 2, "var:" + i, () -> {
                    this.props.clear();
                    this.props.putAll(vp);
                    normalise();
                });
            }
            y += ((variants.size() + perRow - 1) / perRow) * cell + 2;
        }

        // --- same shape, other material
        List<Block> shape = HologramPickerFamilies.sameShape(block, BLOCK_LIST);
        if (!shape.isEmpty()) {
            y = section(g, x, y, "Swap material, keep shape");
            y = chipsFor(g, mx, my, x, y, w, shape, b -> HologramPickerFamilies.label(b, block), "swap:",
                    this.swapPage, 6, () -> this.swapPage++);
        }
        return y + 4;
    }

    private int section(GuiGraphics g, int x, int y, String title) {
        g.drawString(this.font, title, x, y + 2, ACCENT, false);
        return y + 13;
    }

    private int chipsFor(GuiGraphics g, int mx, int my, int x, int y, int w, List<Block> blocks,
                         java.util.function.Function<Block, String> label, String prefix, int page, int pageSize,
                         Runnable more) {
        int pages = (blocks.size() + pageSize - 1) / pageSize;
        int from = (page % Math.max(1, pages)) * pageSize;
        List<Chip> chips = new ArrayList<>();
        for (int i = from; i < Math.min(blocks.size(), from + pageSize); i++) {
            Block b = blocks.get(i);
            Entry entry = entryOf(HologramLayer.Kind.BLOCK, BuiltInRegistries.BLOCK.getKey(b).toString());
            if (entry == null)
                continue;
            chips.add(new Chip(label.apply(b).replace('_', ' '), prefix + entry.id(), false, () -> swapBlock(entry)));
        }
        if (pages > 1)
            chips.add(new Chip("+" + (blocks.size() - pageSize) + " more", prefix + "more", false, more));
        return flow(g, mx, my, x, y, w, chips);
    }

    private int flow(GuiGraphics g, int mx, int my, int x, int y, int w, List<Chip> chips) {
        int cx = x;
        int cy = y;
        for (Chip c : chips) {
            int cw = Math.min(w, this.font.width(c.label()) + 8);
            if (cx != x && cx + cw > x + w) {
                cx = x;
                cy += 15;
            }
            if (cy + 13 >= this.regionY && cy <= this.regionY + this.regionH)
                chip(g, mx, my, cx, cy, cw, 13, c.label(), c.hit(), c.on(), c.click());
            cx += cw + 3;
        }
        return cy + 17;
    }

    private int drawProperty(GuiGraphics g, int mx, int my, int x, int y, int w, BlockState state, Property<?> property) {
        String name = property.getName();
        String now = HologramStates.valueOf(state, property);
        List<String> values = values(property);

        g.drawString(this.font, name, x, y, MUTED, false);
        y += 10;

        if (property instanceof IntegerProperty && values.size() > 2) {
            int n = values.size();
            int sx = x;
            int sw = w - 2;
            g.fill(sx, y, sx + sw, y + 11, WELL);
            g.renderOutline(sx, y, sw, 11, 0x33FFFFFF);
            int idx = Math.max(0, values.indexOf(now));
            int fill = (int) Math.round((sw - 2) * (n <= 1 ? 1.0 : idx / (double) (n - 1)));
            g.fill(sx + 1, y + 1, sx + 1 + Math.max(1, fill), y + 10, 0x663EE6FF);
            String label = now;
            g.drawString(this.font, label, sx + sw / 2 - this.font.width(label) / 2, y + 2, TEXT, false);
            Hit hit = addHit(sx, y, sw, 11, "prop:" + name, () -> {
            });
            hit.drag = (dx, dy, ddx, ddy) -> {
                double f = Mth.clamp((dx - sx) / (double) sw, 0.0, 1.0);
                setProp(name, values.get((int) Math.round(f * (n - 1))));
            };
            hit.wheel = dir -> setProp(name, values.get(Mth.clamp(values.indexOf(now) + dir, 0, n - 1)));
            return y + 15;
        }

        if (values.size() > 6 && !(property instanceof DirectionProperty)) {
            int arrow = 16;
            int vw = w - arrow * 2 - 6;
            chip(g, mx, my, x, y, arrow, 13, "<", "prop:" + name + ":prev", false,
                    () -> setProp(name, HologramStates.cycle(property, now, -1)));
            Hit mid = chip(g, mx, my, x + arrow + 3, y, vw, 13, now, "prop:" + name, false, () -> setProp(name,
                    HologramStates.cycle(property, now, 1)));
            mid.wheel = dir -> setProp(name, HologramStates.cycle(property, now, dir));
            chip(g, mx, my, x + arrow + vw + 6, y, arrow, 13, ">", "prop:" + name + ":next", false,
                    () -> setProp(name, HologramStates.cycle(property, now, 1)));
            return y + 17;
        }

        List<Chip> chips = new ArrayList<>();
        boolean bool = property instanceof BooleanProperty;
        boolean direction = property instanceof DirectionProperty;
        for (String value : values) {
            String label = bool ? ("true".equals(value) ? "On" : "Off") : (direction ? compass(value) : value);
            chips.add(new Chip(label, "prop:" + name + "=" + value, value.equals(now), () -> setProp(name, value)));
        }
        return flow(g, mx, my, x, y, w, chips);
    }

    private static String compass(String direction) {
        return switch (direction) {
            case "north" -> "N";
            case "east" -> "E";
            case "south" -> "S";
            case "west" -> "W";
            case "up" -> "Up";
            case "down" -> "Down";
            default -> direction;
        };
    }

    /** Every state of the first one or two properties (at most 16), other properties kept as chosen. */
    private List<Map<String, String>> variantMaps(List<Property<?>> properties) {
        List<Map<String, String>> out = new ArrayList<>();
        if (properties.isEmpty())
            return out;
        Property<?> p0 = properties.get(0);
        List<String> v0 = values(p0);
        Property<?> p1 = properties.size() > 1 ? properties.get(1) : null;
        List<String> v1 = p1 == null ? List.of() : values(p1);
        boolean both = p1 != null && v0.size() * v1.size() <= 16 && v1.size() > 1;
        if (both) {
            for (String a : v0) {
                for (String b : v1)
                    out.add(merged(Map.of(p0.getName(), a, p1.getName(), b)));
            }
        } else {
            for (int i = 0; i < Math.min(16, v0.size()); i++)
                out.add(merged(Map.of(p0.getName(), v0.get(i))));
        }
        return out;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String valueName(Property property, Object value) {
        return property.getName((Comparable) value);
    }

    private static List<String> values(Property<?> property) {
        List<String> out = new ArrayList<>();
        for (Object value : property.getPossibleValues())
            out.add(valueName(property, value));
        return out;
    }

    // ================================================================ dev-check hooks

    public boolean devClick(String label) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.label.equals(label)) {
                hit.click.run();
                return true;
            }
        }
        return false;
    }

    public boolean devRightClick(String label) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.label.equals(label) && hit.right != null) {
                hit.right.run();
                return true;
            }
        }
        return false;
    }

    public boolean devHasHit(String label) {
        for (Hit hit : this.hits) {
            if (hit.label.equals(label))
                return true;
        }
        return false;
    }

    public List<String> devLabels(String prefix) {
        List<String> out = new ArrayList<>();
        for (Hit hit : this.hits) {
            if (hit.label.startsWith(prefix))
                out.add(hit.label);
        }
        return out;
    }

    public void devSetSearch(String text) {
        if (this.search != null)
            this.search.setValue(text);
    }

    public boolean devKey(int keyCode) {
        return keyPressed(keyCode, 0, 0);
    }

    public int devResultCount() {
        return this.results.size();
    }

    public List<String> devResultIds(int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, this.results.size()); i++)
            out.add(this.results.get(i).id());
        return out;
    }

    /** Display names of the first {@code max} results, in the order the grid shows them. */
    public List<String> devResultNames(int max) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(max, this.results.size()); i++)
            out.add(this.results.get(i).name());
        return out;
    }

    /** Block names that appear more than once inside one mod, as "mod|name" (empty once disambiguated). */
    public static List<String> devDuplicateBlockNames() {
        ensureData();
        Map<String, Integer> seen = new HashMap<>();
        for (Entry e : BLOCKS)
            seen.merge(e.ns() + "|" + e.lower(), 1, Integer::sum);
        List<String> out = new ArrayList<>();
        seen.forEach((name, count) -> {
            if (count > 1)
                out.add(name);
        });
        return out;
    }

    public static String devNameOf(String blockId) {
        ensureData();
        Entry e = entryOf(HologramLayer.Kind.BLOCK, blockId);
        return e == null ? "" : e.name();
    }

    public String devSelectedId() {
        return this.selected == null ? "" : this.selected.id();
    }

    public Map<String, String> devProps() {
        return new LinkedHashMap<>(this.props);
    }

    public String devKind() {
        return this.kind.name();
    }

    public void devApply(boolean asNew) {
        apply(asNew);
    }

    public int devRightMax() {
        return this.rightMax;
    }

    public void devScrollRight(int amount) {
        this.rightScroll = Mth.clamp(this.rightScroll + amount, 0, this.rightMax);
    }

    public int devColourPercent() {
        return Math.round(HologramBlockColors.progress() * 100.0F);
    }

    public boolean devColourDone() {
        return HologramBlockColors.done();
    }

    public int devGridCols() {
        return this.cols;
    }

    public int devFreeWidth() {
        return this.midW;
    }

    public boolean devDropdownOpen() {
        return this.dropdownOpen;
    }

    public Set<String> devModOptions() {
        return new LinkedHashSet<>(modCounts(this.kind).keySet());
    }

    public Set<String> devHueOf(String blockId) {
        Entry e = entryOf(HologramLayer.Kind.BLOCK, blockId);
        Set<String> out = new HashSet<>();
        if (e != null && e.block() != null) {
            var h = HologramBlockColors.hueOf(e.block());
            if (h != null)
                out.add(h.label);
        }
        return out;
    }

    /** Presses a slider/drag control at {@code fraction} (0..1) of its width. */
    public boolean devDrag(String label, double fraction) {
        for (int i = this.hits.size() - 1; i >= 0; i--) {
            Hit hit = this.hits.get(i);
            if (hit.label.equals(label) && hit.drag != null) {
                hit.drag.drag(hit.x + fraction * hit.w, hit.y + hit.h / 2.0, 0.0, 0.0);
                return true;
            }
        }
        return false;
    }

    public static java.nio.file.Path devStoreFile() {
        return HologramPickerFavorites.file();
    }

    /** Drops the in-memory favourites so the next read comes from the file. */
    public static void devForgetStore() {
        HologramPickerFavorites.forgetMemory();
    }

    public static boolean devIsFavourite(HologramLayer.Kind kind, String id) {
        return HologramPickerFavorites.isFavourite(kind, id);
    }

    public void devSelect(String id) {
        Entry e = entryOf(this.kind, id);
        if (e != null) {
            select(e);
            ensureVisible(this.results.indexOf(e));
        }
    }
}
