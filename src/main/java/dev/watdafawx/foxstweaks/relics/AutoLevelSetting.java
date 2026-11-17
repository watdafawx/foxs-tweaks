package dev.watdafawx.foxstweaks.relics;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Which abilities a relic levels up by itself, and how the upgrade points are shared between them.
 * Holds ids only, no Relics type, so it is safe to register whether or not Relics is installed.
 *
 * @param abilities  marked ability ids, in priority order
 * @param roundRobin {@code false}: finish the first ability, then the next. {@code true}: one upgrade
 *                   each in turn.
 * @param cursor     round robin only: index of the ability that gets the next upgrade, so a leftover
 *                   point does not always land on the first one
 */
public record AutoLevelSetting(List<String> abilities, boolean roundRobin, int cursor) {
    public static final AutoLevelSetting EMPTY = new AutoLevelSetting(List.of(), false, 0);

    public static final Codec<AutoLevelSetting> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.listOf().fieldOf("abilities").forGetter(AutoLevelSetting::abilities),
            Codec.BOOL.optionalFieldOf("round_robin", false).forGetter(AutoLevelSetting::roundRobin),
            Codec.INT.optionalFieldOf("cursor", 0).forGetter(AutoLevelSetting::cursor)
    ).apply(i, AutoLevelSetting::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, AutoLevelSetting> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), AutoLevelSetting::abilities,
            ByteBufCodecs.BOOL, AutoLevelSetting::roundRobin,
            ByteBufCodecs.VAR_INT, AutoLevelSetting::cursor,
            AutoLevelSetting::new);

    public AutoLevelSetting {
        abilities = List.copyOf(abilities);
    }

    /** What the stack has now. Relics marked before this existed carry one id in the old component. */
    public static AutoLevelSetting of(ItemStack stack) {
        var setting = stack.get(FoxsTweaksComponents.AUTO_LEVEL.get());

        if (setting != null)
            return setting;

        String legacy = stack.get(FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get());

        return legacy == null || legacy.isEmpty() ? EMPTY : new AutoLevelSetting(List.of(legacy), false, 0);
    }

    /** Stores the setting, drops the old single-ability component, and clears everything when empty. */
    public void applyTo(ItemStack stack) {
        stack.remove(FoxsTweaksComponents.AUTO_LEVEL_ABILITY.get());

        if (abilities.isEmpty())
            stack.remove(FoxsTweaksComponents.AUTO_LEVEL.get());
        else
            stack.set(FoxsTweaksComponents.AUTO_LEVEL.get(), this);
    }

    public boolean has(String ability) {
        return abilities.contains(ability);
    }

    /** Marks the ability, or unmarks it when it already is. */
    public AutoLevelSetting toggled(String ability) {
        List<String> next = new ArrayList<>(abilities);

        if (!next.remove(ability))
            next.add(ability);

        return new AutoLevelSetting(next, roundRobin, 0);
    }

    public AutoLevelSetting withRoundRobin(boolean value) {
        return new AutoLevelSetting(abilities, value, 0);
    }

    public AutoLevelSetting withCursor(int value) {
        return value == cursor ? this : new AutoLevelSetting(abilities, roundRobin, value);
    }
}
