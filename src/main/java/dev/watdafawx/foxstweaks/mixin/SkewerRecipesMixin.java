package dev.watdafawx.foxstweaks.mixin;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Kaleidoscope Grilling rebuilds its whole skewer recipe list ({@code recipes()}) from the skewer map on every
 * lookup, and {@code usesGeneratedModel} runs that lookup from {@code ItemRenderer.getModel} for every item drawn
 * (drawer contents, hotbar, item frames, held items). Both are cached here:
 *
 * <ul>
 *   <li>{@code recipes()} by the identity of the display map it is built from. With
 *       {@link GrillingDataManagerMixin} that map is a stable instance until a datapack reload or a recipe sync
 *       replaces the underlying data, which gives a new map and so a rebuild. The list is immutable
 *       ({@code Stream.toList}), so sharing it is safe.</li>
 *   <li>{@code usesGeneratedModel} per {@link Item}, for as long as the recipe list is the same instance. The
 *       answer depends only on the item's registry id and the recipe list.</li>
 * </ul>
 */
@Mixin(targets = "cn.breezeth.kaleidoscope_grilling.skewer.SkewerRecipes", remap = false)
public abstract class SkewerRecipesMixin {
    /** {display map, recipe list}, replaced whole. */
    @Unique private static volatile Object[] foxstweaks$recipes;
    /** {recipe list, per-item answers}, replaced whole. */
    @Unique private static volatile Object[] foxstweaks$generated;

    @Shadow
    private static List<?> recipes() {
        throw new AssertionError();
    }

    @WrapMethod(method = "recipes")
    private static List<?> foxstweaks$cachedRecipes(Operation<List<?>> original) {
        if (!Config.GRILLING_SKEWER_CACHE.get()) {
            return original.call();
        }
        // Read the key first: the original reads the map again itself, so the list is never older than the key,
        // and a newer map on the next call just means one more rebuild.
        Map<String, ?> display = GrillingDataManagerInvoker.foxstweaks$skewersForDisplay();
        Object[] cached = foxstweaks$recipes;
        if (cached != null && cached[0] == display) {
            return (List<?>) cached[1];
        }
        List<?> list = original.call();
        foxstweaks$recipes = new Object[] {display, list};
        return list;
    }

    @WrapMethod(method = "usesGeneratedModel")
    private static boolean foxstweaks$cachedGeneratedModel(ItemStack stack, Operation<Boolean> original) {
        if (!Config.GRILLING_SKEWER_CACHE.get()) {
            return original.call(stack);
        }
        List<?> list = recipes();
        Object[] cached = foxstweaks$generated;
        if (cached == null || cached[0] != list) {
            cached = new Object[] {list, new ConcurrentHashMap<Item, Boolean>()};
            foxstweaks$generated = cached;
        }
        @SuppressWarnings("unchecked")
        Map<Item, Boolean> answers = (Map<Item, Boolean>) cached[1];
        Item item = stack.getItem();
        Boolean known = answers.get(item);
        if (known != null) {
            return known;
        }
        boolean result = original.call(stack);
        answers.put(item, result);
        return result;
    }
}
