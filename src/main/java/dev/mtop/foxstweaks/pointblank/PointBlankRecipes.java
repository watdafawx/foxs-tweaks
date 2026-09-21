package dev.mtop.foxstweaks.pointblank;

import java.util.HashSet;
import java.util.function.Predicate;

import com.vicmatskiv.pointblank.crafting.PointBlankRecipe;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** The one place besides the mixins that touches Point Blank's own types. Only reached when it is loaded. */
public final class PointBlankRecipes {
    private PointBlankRecipes() {
    }

    /** Every item any printer recipe asks for, tags expanded. */
    public static Predicate<Item> wantedItems(Level level) {
        var items = new HashSet<Item>();
        for (var recipe : PointBlankRecipe.getRecipes(level)) {
            for (var ingredient : recipe.getPointBlankIngredients()) {
                for (var stack : ingredient.getItemStacks())
                    items.add(stack.getItem());
            }
        }

        return items::contains;
    }
}
