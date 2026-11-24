package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a block name plus picked property values back into a {@link BlockState}, and lists the
 * properties a state has so the editor can offer them.
 *
 * <p>Property values are listed one property at a time rather than as a flat list of every state on
 * purpose: redstone wire has 1296 states and glow lichen 1024. AE2's controller is the shape we care
 * about - {@code state} (offline/online/conflicted) times {@code type} (block/column/inside) - and
 * that is two small dropdowns instead of a list nobody can navigate.
 */
public final class HologramStates {

    /** The registry name of this state's block, or {@code ""} for air. */
    public static String nameOf(BlockState state) {
        if (state.isAir())
            return "";
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * The registry name even for air, which is how an entity-only or particle-only hologram is made:
     * the base block is air (never drawn) and every visible thing is a part.
     */
    public static String blockName(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    @Nullable
    public static Block blockOf(String name) {
        ResourceLocation key = ResourceLocation.tryParse(name);
        if (key == null)
            return null;
        Block block = BuiltInRegistries.BLOCK.get(key);
        return block == null ? null : block;
    }

    /** The saved state, or null when the block is not installed or the saved values no longer fit. */
    @Nullable
    public static BlockState resolve(String block, Map<String, String> props) {
        Block found = blockOf(block);
        if (found == null)
            return null;

        BlockState state = found.defaultBlockState();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            Property<?> property = property(state, entry.getKey());
            if (property != null)
                state = set(state, property, entry.getValue());
        }
        return state;
    }

    @Nullable
    public static Property<?> property(BlockState state, String name) {
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals(name))
                return property;
        }
        return null;
    }

    /** Every property of the state, in the order the editor shows them. */
    public static List<Property<?>> properties(BlockState state) {
        return new ArrayList<>(state.getProperties());
    }

    public static String valueOf(BlockState state, Property<?> property) {
        return valueName(state, property);
    }

    /** The value of one named property on a saved hologram, or {@code ""} when it has no such property. */
    public static String valueOf(HologramSpec spec, String propertyName) {
        BlockState state = resolve(spec.block(), spec.props());
        if (state == null)
            return "";
        Property<?> property = property(state, propertyName);
        return property == null ? "" : valueName(state, property);
    }

    /** The next (or previous) value in the property's own order, wrapping round. */
    public static String cycle(Property<?> property, String current, int direction) {
        Collection<?> values = property.getPossibleValues();
        if (values.isEmpty())
            return current;

        List<String> names = new ArrayList<>(values.size());
        for (Object value : values)
            names.add(nameOf(property, value));

        int index = names.indexOf(current);
        if (index < 0)
            index = 0;
        else
            index = Math.floorMod(index + Integer.signum(direction), names.size());
        return names.get(index);
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static BlockState set(BlockState state, Property property, String name) {
        Optional value = property.getValue(name);
        return value.isPresent() ? state.setValue(property, (Comparable) value.get()) : state;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String valueName(BlockState state, Property<?> property) {
        // Raw holder and property: the property is a wildcard here, and only the erased call compiles.
        StateHolder holder = state;
        return nameOf(property, holder.getValue(property));
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static String nameOf(Property property, Object value) {
        return property.getName((Comparable) value);
    }

    private HologramStates() {
    }
}
