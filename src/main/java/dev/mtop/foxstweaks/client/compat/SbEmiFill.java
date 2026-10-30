package dev.mtop.foxstweaks.client.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.emi.emi.api.recipe.EmiCraftingRecipe;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.VanillaEmiRecipeCategories;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.mtop.foxstweaks.Config;
import net.minecraft.world.item.ItemStack;

/**
 * Sophisticated Core shows its crafting recipes in EMI split into pieces (one lists only the ingredients,
 * another only the result), so EMI's "+" in a crafting table finds no handler: "Current workstation does
 * not support recipe". For filling, this hands EMI a plain 3x3 copy of the full recipe instead.
 *
 * <p>Self-disabling: only recipes that still lack inputs or outputs ({@code supportsRecipeTree() == false})
 * are swapped. Once Sophisticated Core lists both, nothing here changes them. Anything unexpected (fields
 * renamed, grid not 3x3) leaves the recipe as it is.
 */
public final class SbEmiFill {
    private static final Logger LOG = LogUtils.getLogger();
    private static final String PKG = "net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.emi.";
    private static final Object NONE = new Object();
    private static final Map<EmiRecipe, Object> CACHE = new WeakHashMap<>();
    private static boolean warned;

    private SbEmiFill() {
    }

    public static EmiRecipe forFill(EmiRecipe recipe) {
        if (recipe == null || recipe.getCategory() != VanillaEmiRecipeCategories.CRAFTING
                || !recipe.getClass().getName().startsWith(PKG) || recipe.supportsRecipeTree()
                || !Config.enabled(Config.SB_EMI_FILL)) {
            return recipe;
        }
        Object full = CACHE.computeIfAbsent(recipe, SbEmiFill::build);
        return full instanceof EmiRecipe r ? r : recipe;
    }

    private static Object build(EmiRecipe recipe) {
        try {
            String name = recipe.getClass().getSimpleName();
            List<EmiIngredient> grid;
            List<?> results;
            if (name.equals("CraftingSpecEmiRecipe")) {
                grid = cast(field(recipe, "gridInputs"));
                Object spec = field(recipe, "spec");
                Object variants = field(recipe, "variants");
                Method out = spec.getClass().getMethod("getOutputStacks", List.class);
                results = (List<?>) out.invoke(spec, variants);
            } else if (name.equals("GroupedCraftingEmiRecipe")) {
                Object holder = field(recipe, "recipeHolder");
                Object grouped = holder.getClass().getMethod("value").invoke(holder);
                List<?> slots = (List<?>) grouped.getClass().getMethod("getInputSlots").invoke(grouped);
                grid = slots.stream().map(s -> {
                    List<?> stacks = (List<?>) s;
                    return stacks.isEmpty() ? (EmiIngredient) EmiStack.EMPTY
                            : EmiIngredient.of(stacks.stream().map(i -> EmiStack.of((ItemStack) i)).toList());
                }).toList();
                results = (List<?>) grouped.getClass().getMethod("getResultStacks").invoke(grouped);
            } else {
                return NONE;
            }
            if (grid == null || grid.size() != 9 || results == null || results.isEmpty()
                    || grid.stream().allMatch(EmiIngredient::isEmpty)) {
                return NONE;
            }
            return new EmiCraftingRecipe(grid, EmiStack.of((ItemStack) results.get(0)), recipe.getId(), false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (!warned) {
                warned = true;
                LOG.warn("[foxstweaks] sbEmiFill: Sophisticated Core's EMI recipes changed shape, leaving them alone: {}", e.toString());
            }
            return NONE;
        }
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        Field f = owner.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(owner);
    }

    @SuppressWarnings("unchecked")
    private static List<EmiIngredient> cast(Object o) {
        return (List<EmiIngredient>) o;
    }
}
