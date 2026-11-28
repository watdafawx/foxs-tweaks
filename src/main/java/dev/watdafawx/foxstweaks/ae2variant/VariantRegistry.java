package dev.watdafawx.foxstweaks.ae2variant;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Discovers and caches block-variant equivalence groups from Chipped, Rechiseled, Chisel,
 * Factory Blocks, vanilla Stonecutter, and user-configured item tags.
 *
 * <p>Zero hard compile-time dependencies on any variant mod: Chipped recipes, Rechiseled's
 * ChiselingRecipeManager, and Chisel's ChiselGroupLookup are queried reflectively when loaded.
 */
public final class VariantRegistry {
    private static final Logger LOG = LoggerFactory.getLogger("foxstweaks/ae2variant");

    public enum FilterMode {
        FAMILY_FILTER(0),
        IN_STOCK(1),
        ALL(2);

        public final int id;

        FilterMode(int id) {
            this.id = id;
        }

        public FilterMode next() {
            FilterMode[] vals = values();
            return vals[(id + 1) % vals.length];
        }

        public static FilterMode byId(int id) {
            FilterMode[] vals = values();
            return id >= 0 && id < vals.length ? vals[id] : FAMILY_FILTER;
        }
    }

    /** A single craftable conversion: input -> output, with optional substitute inputs from the same group. */
    public record VariantConversion(
            VariantSource source,
            int groupId,
            Item output,
            int outputCount,
            Item primaryInput,
            int inputCount,
            List<Item> substitutes) {
    }

    private static final class Snapshot {
        final EnumMap<VariantSource, List<VariantConversion>> bySource = new EnumMap<>(VariantSource.class);
        final EnumMap<VariantSource, Integer> counts = new EnumMap<>(VariantSource.class);
        final Map<Item, Set<Integer>> itemToGroups = new HashMap<>();
        final Map<Integer, Set<Item>> groupToItems = new HashMap<>();
    }

    private static volatile Snapshot cached;
    private static int nextGroupId = 1;

    private VariantRegistry() {
    }

    public static void invalidate() {
        cached = null;
    }

    public static int getSourceCount(Level level, VariantSource source) {
        Snapshot snap = getOrBuild(level);
        return snap.counts.getOrDefault(source, 0);
    }

    /**
     * Returns deduplicated conversions matching the enabled sources, filter mode, and filter/stock sets.
     */
    public static List<VariantConversion> query(
            Level level,
            int sourceMask,
            FilterMode mode,
            Set<Item> filterItems,
            Set<Item> inStockItems) {
        Snapshot snap = getOrBuild(level);

        Set<Integer> allowedGroups = null;
        if (mode == FilterMode.FAMILY_FILTER) {
            if (filterItems.isEmpty()) {
                return List.of();
            }
            allowedGroups = new HashSet<>();
            for (Item item : filterItems) {
                Set<Integer> groups = snap.itemToGroups.get(item);
                if (groups != null) {
                    allowedGroups.addAll(groups);
                }
            }
        }

        // Deduplicate by output item so AE2 never receives two competing patterns for the same block.
        Map<Item, VariantConversion> byOutput = new LinkedHashMap<>();

        for (VariantSource source : VariantSource.values()) {
            if (!source.isEnabled(sourceMask)) continue;
            List<VariantConversion> list = snap.bySource.get(source);
            if (list == null || list.isEmpty()) continue;

            for (VariantConversion conv : list) {
                if (byOutput.containsKey(conv.output())) continue;

                boolean include = switch (mode) {
                    case ALL -> true;
                    case FAMILY_FILTER -> {
                        if (conv.groupId() > 0 && allowedGroups != null && allowedGroups.contains(conv.groupId())) {
                            yield true;
                        }
                        if (source == VariantSource.STONECUTTER) {
                            if (filterItems.contains(conv.primaryInput())) yield true;
                            Set<Integer> inGroups = snap.itemToGroups.get(conv.primaryInput());
                            if (inGroups != null && allowedGroups != null) {
                                for (int g : inGroups) {
                                    if (allowedGroups.contains(g)) yield true;
                                }
                            }
                        }
                        yield false;
                    }
                    case IN_STOCK -> {
                        if (inStockItems.contains(conv.primaryInput())) yield true;
                        for (Item sub : conv.substitutes()) {
                            if (inStockItems.contains(sub)) yield true;
                        }
                        yield false;
                    }
                };

                if (include) {
                    byOutput.put(conv.output(), conv);
                }
            }
        }

        return new ArrayList<>(byOutput.values());
    }

    /** Returns how many variants a single filter item unlocks across the enabled sources. */
    public static int countForFilterItem(Level level, int sourceMask, Item item) {
        if (item == null || item == Items.AIR) return 0;
        return query(level, sourceMask, FilterMode.FAMILY_FILTER, Set.of(item), Set.of()).size();
    }

    private static Snapshot getOrBuild(Level level) {
        Snapshot snap = cached;
        if (snap != null) return snap;
        synchronized (VariantRegistry.class) {
            if (cached != null) return cached;
            snap = build(level);
            cached = snap;
            return snap;
        }
    }

    private static Snapshot build(Level level) {
        Snapshot snap = new Snapshot();
        nextGroupId = 1;
        for (VariantSource s : VariantSource.values()) {
            snap.bySource.put(s, new ArrayList<>());
            snap.counts.put(s, 0);
        }

        loadChipped(level, snap);
        loadRechiseled(level, snap);
        loadChisel(snap);
        loadFactoryBlocks(snap);
        loadStonecutter(level, snap);
        loadCustomTags(snap);

        StringBuilder summary = new StringBuilder("ME Variant Crafter indexed:");
        for (VariantSource s : VariantSource.values()) {
            int n = snap.bySource.get(s).size();
            snap.counts.put(s, n);
            if (n > 0) summary.append(' ').append(s.displayName).append('=').append(n);
        }
        LOG.info(summary.toString());
        return snap;
    }

    /** Adds a 1:1 equivalence group of items, picking a canonical base to prevent AE2 crafting cycles. */
    private static void addEquivalenceGroup(Snapshot snap, VariantSource source, Collection<Item> rawItems) {
        LinkedHashSet<Item> set = new LinkedHashSet<>();
        for (Item item : rawItems) {
            if (item != null && item != Items.AIR) set.add(item);
        }
        if (set.size() < 2) return;

        Item base = pickCanonicalBase(set);
        int groupId = nextGroupId++;
        snap.groupToItems.put(groupId, set);
        for (Item item : set) {
            snap.itemToGroups.computeIfAbsent(item, k -> new HashSet<>()).add(groupId);
        }

        List<VariantConversion> out = snap.bySource.get(source);
        List<Item> all = new ArrayList<>(set);
        for (Item target : all) {
            if (target == base) continue;
            // Substitutes: any other member of the same group (except the output itself),
            // so AE2 uses spare variants in stock without ever recursing into a cycle.
            List<Item> subs = new ArrayList<>(all.size() - 2);
            for (Item other : all) {
                if (other != base && other != target) {
                    subs.add(other);
                }
            }
            out.add(new VariantConversion(source, groupId, target, 1, base, 1, subs));
        }
    }

    private static Item pickCanonicalBase(Collection<Item> items) {
        return items.stream()
                .min(Comparator
                        .comparingInt((Item i) -> {
                            ResourceLocation id = BuiltInRegistries.ITEM.getKey(i);
                            if ("minecraft".equals(id.getNamespace())) return 0;
                            if ("factory_blocks".equals(id.getNamespace()) && "factory".equals(id.getPath())) return 1;
                            if ("chisel".equals(id.getNamespace())) return 3;
                            if ("chipped".equals(id.getNamespace())) return 4;
                            if ("rechiseled".equals(id.getNamespace())) return 4;
                            return 2;
                        })
                        .thenComparingInt(i -> BuiltInRegistries.ITEM.getKey(i).getPath().length())
                        .thenComparing(i -> BuiltInRegistries.ITEM.getKey(i).toString()))
                .orElse(Items.AIR);
    }

    // ---- Source 1: Chipped ----
    private static void loadChipped(Level level, Snapshot snap) {
        if (!ModList.get().isLoaded("chipped")) return;
        try {
            Method ingredientsMethod = null;
            for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
                Recipe<?> recipe = holder.value();
                if (!"earth.terrarium.chipped.common.recipes.ChippedRecipe".equals(recipe.getClass().getName())) {
                    continue;
                }
                if (ingredientsMethod == null) {
                    ingredientsMethod = recipe.getClass().getMethod("ingredients");
                }
                @SuppressWarnings("unchecked")
                List<Ingredient> ingredients = (List<Ingredient>) ingredientsMethod.invoke(recipe);
                if (ingredients == null) continue;
                for (Ingredient ing : ingredients) {
                    ItemStack[] stacks = ing.getItems();
                    if (stacks.length < 2) continue;
                    List<Item> group = new ArrayList<>(stacks.length);
                    for (ItemStack st : stacks) {
                        if (!st.isEmpty()) group.add(st.getItem());
                    }
                    addEquivalenceGroup(snap, VariantSource.CHIPPED, group);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Failed to read Chipped recipes: {}", t.toString());
        }
    }

    // ---- Source 2: Rechiseled (includes Rechiseled:AE2, Rechiseled:Create, Rechiseled:Chipped, etc.) ----
    private static void loadRechiseled(Level level, Snapshot snap) {
        if (!ModList.get().isLoaded("rechiseled")) return;
        try {
            Class<?> mgrClass = Class.forName("com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipeManager");
            Class<?> shapeClass = Class.forName("com.supermartijn642.rechiseled.api.chiseling.ChiselingBlockShape");
            Object mgr = mgrClass.getMethod("get", boolean.class).invoke(null, level.isClientSide());
            if (mgr == null) return;
            @SuppressWarnings("unchecked")
            List<?> recipes = (List<?>) mgrClass.getMethod("getAllRecipes").invoke(mgr);
            Object[] shapes = (Object[]) shapeClass.getMethod("values").invoke(null);
            if (recipes == null || shapes == null) return;

            Class<?> recipeClass = Class.forName("com.supermartijn642.rechiseled.api.chiseling.ChiselingRecipe");
            Class<?> entryClass = Class.forName("com.supermartijn642.rechiseled.api.chiseling.ChiselingEntry");
            Class<?> worthClass = Class.forName("com.supermartijn642.rechiseled.api.chiseling.ItemWithWorth");

            Method entriesM = recipeClass.getMethod("entries");
            Method hasRegM = entryClass.getMethod("hasRegularItem", shapeClass);
            Method getRegM = entryClass.getMethod("getRegularItem", shapeClass);
            Method hasConnM = entryClass.getMethod("hasConnectingItem", shapeClass);
            Method getConnM = entryClass.getMethod("getConnectingItem", shapeClass);
            Method itemM = worthClass.getMethod("item");

            for (Object recipe : recipes) {
                @SuppressWarnings("unchecked")
                List<?> entries = (List<?>) entriesM.invoke(recipe);
                if (entries == null || entries.isEmpty()) continue;

                // Group items by ChiselingBlockShape so BLOCK<->BLOCK, STAIRS<->STAIRS, SLAB<->SLAB stay 1:1.
                for (Object shape : shapes) {
                    List<Item> group = new ArrayList<>();
                    for (Object entry : entries) {
                        if ((Boolean) hasRegM.invoke(entry, shape)) {
                            Object iw = getRegM.invoke(entry, shape);
                            if (iw != null) {
                                Item it = (Item) itemM.invoke(iw);
                                if (it != null && it != Items.AIR) group.add(it);
                            }
                        }
                        if ((Boolean) hasConnM.invoke(entry, shape)) {
                            Object iw = getConnM.invoke(entry, shape);
                            if (iw != null) {
                                Item it = (Item) itemM.invoke(iw);
                                if (it != null && it != Items.AIR) group.add(it);
                            }
                        }
                    }
                    addEquivalenceGroup(snap, VariantSource.RECHISELED, group);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Failed to read Rechiseled recipes: {}", t.toString());
        }
    }

    // ---- Source 3: Chisel Reborn ----
    private static void loadChisel(Snapshot snap) {
        if (!ModList.get().isLoaded("chisel")) return;
        try {
            Class<?> lookup = Class.forName("com.periut.chisel.block.ChiselGroupLookup");
            @SuppressWarnings("unchecked")
            Iterator<String> names = (Iterator<String>) lookup.getMethod("getGroupNameIterator").invoke(null);
            Method getBlocks = lookup.getMethod("getBlocksInGroup", String.class);
            if (names == null) return;
            while (names.hasNext()) {
                String name = names.next();
                @SuppressWarnings("unchecked")
                List<Item> items = (List<Item>) getBlocks.invoke(null, name);
                if (items != null && items.size() >= 2) {
                    addEquivalenceGroup(snap, VariantSource.CHISEL, items);
                }
            }
        } catch (Throwable t) {
            LOG.warn("Failed to read Chisel groups: {}", t.toString());
        }
    }

    // ---- Source 4: Factory Blocks (direct tag support even without Chisel) ----
    private static void loadFactoryBlocks(Snapshot snap) {
        if (!ModList.get().isLoaded("factory_blocks")) return;
        TagKey<Item> factoryTag = TagKey.create(
                Registries.ITEM, ResourceLocation.fromNamespaceAndPath("factory_blocks", "factory"));
        BuiltInRegistries.ITEM.getTag(factoryTag).ifPresent(named -> {
            List<Item> items = new ArrayList<>();
            for (Holder<Item> h : named) {
                items.add(h.value());
            }
            addEquivalenceGroup(snap, VariantSource.FACTORY_BLOCKS, items);
        });
    }

    // ---- Source 5: Vanilla Stonecutter ----
    private static void loadStonecutter(Level level, Snapshot snap) {
        List<VariantConversion> out = snap.bySource.get(VariantSource.STONECUTTER);
        for (RecipeHolder<StonecutterRecipe> holder : level.getRecipeManager().getAllRecipesFor(RecipeType.STONECUTTING)) {
            StonecutterRecipe recipe = holder.value();
            ItemStack result = recipe.getResultItem(level.registryAccess());
            if (result.isEmpty()) continue;
            if (recipe.getIngredients().isEmpty()) continue;
            ItemStack[] inputs = recipe.getIngredients().get(0).getItems();
            if (inputs.length == 0) continue;
            Item primary = inputs[0].getItem();
            if (primary == Items.AIR || primary == result.getItem()) continue;
            List<Item> subs = new ArrayList<>();
            for (int i = 1; i < inputs.length; i++) {
                Item it = inputs[i].getItem();
                if (it != Items.AIR && it != primary && it != result.getItem()) {
                    subs.add(it);
                }
            }
            out.add(new VariantConversion(
                    VariantSource.STONECUTTER,
                    0,
                    result.getItem(),
                    result.getCount(),
                    primary,
                    1,
                    subs));
        }
    }

    // ---- Source 6: User-configured custom item tags ----
    private static void loadCustomTags(Snapshot snap) {
        List<? extends String> patterns = Config.AE2_VARIANT_CUSTOM_TAGS.get();
        if (patterns == null || patterns.isEmpty()) return;

        BuiltInRegistries.ITEM.getTagNames().forEach(tagKey -> {
            String tagStr = tagKey.location().toString();
            if (!matchesAnyPattern(tagStr, patterns)) return;
            BuiltInRegistries.ITEM.getTag(tagKey).ifPresent(named -> {
                List<Item> items = new ArrayList<>();
                for (Holder<Item> h : named) {
                    items.add(h.value());
                }
                addEquivalenceGroup(snap, VariantSource.CUSTOM_TAGS, items);
            });
        });
    }

    private static boolean matchesAnyPattern(String tagStr, List<? extends String> patterns) {
        for (String p : patterns) {
            if (p == null || p.isEmpty()) continue;
            String clean = p.startsWith("#") ? p.substring(1) : p;
            if (clean.endsWith("*")) {
                if (tagStr.startsWith(clean.substring(0, clean.length() - 1))) return true;
            } else if (tagStr.equals(clean)) {
                return true;
            }
        }
        return false;
    }
}
