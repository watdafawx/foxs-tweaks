package dev.watdafawx.foxstweaks.emi;

import java.util.AbstractList;
import java.util.List;
import java.util.RandomAccess;

import dev.emi.emi.api.stack.EmiStack;

/**
 * The one stack list per tag that every TagEmiIngredient of that tag points to. Read-only for everyone else;
 * {@link TagShare} can swap a slot when a stack in it is about to be changed (see {@link TagShare#beforeChange}).
 */
public final class SharedTagList extends AbstractList<EmiStack> implements RandomAccess {
    private final EmiStack[] stacks;

    SharedTagList(List<EmiStack> source) {
        this.stacks = source.toArray(new EmiStack[0]);
        for (var s : stacks) {
            ((SharedStack) s).foxstweaks$setHome(this);
        }
    }

    @Override
    public EmiStack get(int index) {
        return stacks[index];
    }

    @Override
    public int size() {
        return stacks.length;
    }

    int indexOfIdentity(EmiStack s) {
        for (var i = 0; i < stacks.length; i++) {
            if (stacks[i] == s) {
                return i;
            }
        }

        return -1;
    }

    EmiStack[] snapshot() {
        return stacks.clone();
    }

    void swap(int index, EmiStack replacement) {
        stacks[index] = replacement;
    }
}
