package dev.watdafawx.foxstweaks.ae2variant;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Dynamic AE2 {@link IPatternDetails} representing one block-variant conversion.
 *
 * <p>Primary input is always the group's canonical base block (so AE2 recurses cleanly without
 * cycles), while other items in the same variant group are accepted as substitutes if already
 * present in ME storage.
 */
public final class VariantPatternDetails implements IPatternDetails {
    private static final int MAX_LISTED_SUBS = 48;

    private final AEItemKey definition;
    private final AEItemKey primaryInput;
    private final AEItemKey output;
    private final int inputCount;
    private final int outputCount;
    private final IInput[] inputs;
    private final List<GenericStack> outputs;

    public VariantPatternDetails(Item definitionIcon, VariantRegistry.VariantConversion conv) {
        this.primaryInput = AEItemKey.of(conv.primaryInput());
        this.output = AEItemKey.of(conv.output());
        this.inputCount = conv.inputCount();
        this.outputCount = conv.outputCount();

        // Unique virtual definition key per output+input pair so AE2's pattern maps never collide.
        ItemStack defStack = new ItemStack(definitionIcon);
        String keyTag = conv.source().name() + ":"
                + BuiltInRegistries.ITEM.getKey(conv.primaryInput()) + "->"
                + BuiltInRegistries.ITEM.getKey(conv.output());
        defStack.set(DataComponents.CUSTOM_NAME, Component.literal(keyTag));
        this.definition = AEItemKey.of(defStack);

        Set<AEKey> validSet = new HashSet<>();
        validSet.add(this.primaryInput);
        for (Item sub : conv.substitutes()) {
            validSet.add(AEItemKey.of(sub));
        }

        // Only list the canonical base block in possibleInputs so AE2's crafting calculator
        // never branches into cyclic sub-crafts across other variants in the same group.
        GenericStack[] possible = new GenericStack[]{new GenericStack(this.primaryInput, this.inputCount)};

        this.inputs = new IInput[]{new VariantInput(possible, this.inputCount, validSet)};
        this.outputs = Collections.singletonList(new GenericStack(this.output, this.outputCount));
    }

    @Override
    public AEItemKey getDefinition() {
        return definition;
    }

    @Override
    public IInput[] getInputs() {
        return inputs;
    }

    @Override
    public List<GenericStack> getOutputs() {
        return outputs;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VariantPatternDetails other)) return false;
        return inputCount == other.inputCount
                && outputCount == other.outputCount
                && Objects.equals(primaryInput, other.primaryInput)
                && Objects.equals(output, other.output);
    }

    @Override
    public int hashCode() {
        return Objects.hash(primaryInput, output, inputCount, outputCount);
    }

    private record VariantInput(
            GenericStack[] possibleInputs,
            long multiplier,
            Set<AEKey> validKeys) implements IInput {

        @Override
        public GenericStack[] getPossibleInputs() {
            return possibleInputs;
        }

        @Override
        public long getMultiplier() {
            return multiplier;
        }

        @Override
        public boolean isValid(AEKey input, Level level) {
            return validKeys.contains(input);
        }

        @Override
        public AEKey getRemainingKey(AEKey template) {
            return null;
        }
    }
}
