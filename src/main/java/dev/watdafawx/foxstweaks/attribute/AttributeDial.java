package dev.watdafawx.foxstweaks.attribute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Attribute dial: lets a player turn any of their own attributes down to 0..100% of what their gear gives them.
 *
 * <p>Gear, affixes and effects stay exactly as they are. The dial is one extra {@code ADD_MULTIPLIED_TOTAL}
 * modifier per attribute, which vanilla applies after every other modifier, so the final value is
 * {@code gearValue * ratio}. The ratio itself lives in a player attachment (copied on death) and the modifier is
 * transient, so removing this mod leaves nothing behind in the save.
 *
 * <p>The ratio is relative to the attribute's <em>clamped</em> value: an attribute whose raw total is above its maximum
 * (a 200 reach on a 64 cap) would otherwise ignore a 50% dial, because the clamp runs after our multiply. The amount is
 * therefore recomputed ({@link #refresh}) whenever gear changes; {@link #onPlayerTick} does that every 10 ticks.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class AttributeDial {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, FoxsTweaks.MODID);

    /** Attribute id -> ratio in [0, 1). Absent = untouched. */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Map<ResourceLocation, Double>>> RATIOS =
            ATTACHMENTS.register("attribute_dial", () -> AttachmentType.<Map<ResourceLocation, Double>>builder(() -> Map.of())
                    .serialize(Codec.unboundedMap(ResourceLocation.CODEC, Codec.DOUBLE))
                    .copyOnDeath()
                    .build());

    /** Below this the dial counts as "full" and the entry is dropped. */
    private static final double FULL = 0.9999;

    private AttributeDial() {
    }

    /** One row of what the server tells the dial screen. */
    public record Entry(ResourceLocation id, double base, double gear, double ratio, double value) {
    }

    public static ResourceLocation modifierId(ResourceLocation attribute) {
        return ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "dial/" + attribute.getNamespace() + "/" + attribute.getPath());
    }

    /** The attribute's total the way vanilla computes it, leaving out {@code skip}. {@code clamp} applies the attribute's min/max. */
    public static double total(AttributeInstance inst, ResourceLocation skip, boolean clamp) {
        double base = inst.getBaseValue();

        for (AttributeModifier m : inst.getModifiers())
            if (m.operation() == AttributeModifier.Operation.ADD_VALUE && !m.id().equals(skip))
                base += m.amount();

        double value = base;

        for (AttributeModifier m : inst.getModifiers())
            if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_BASE && !m.id().equals(skip))
                value += base * m.amount();

        for (AttributeModifier m : inst.getModifiers())
            if (m.operation() == AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL && !m.id().equals(skip))
                value *= 1.0 + m.amount();

        return clamp ? inst.getAttribute().value().sanitizeValue(value) : value;
    }

    /** What gear alone gives, ignoring the dial. */
    public static double gearValue(AttributeInstance inst, ResourceLocation attribute) {
        return total(inst, modifierId(attribute), true);
    }

    public static Map<ResourceLocation, Double> ratios(ServerPlayer player) {
        return player.getData(RATIOS.get());
    }

    /**
     * Sets one attribute's dial. A ratio of 1 (or more) removes it.
     *
     * @return false when the attribute does not exist or the player does not have it
     */
    public static boolean set(ServerPlayer player, ResourceLocation attribute, double ratio) {
        var holder = BuiltInRegistries.ATTRIBUTE.getHolder(attribute).orElse(null);
        AttributeInstance inst = holder == null ? null : player.getAttribute(holder);

        if (inst == null || Double.isNaN(ratio))
            return false;

        ratio = Math.max(0.0, ratio);
        Map<ResourceLocation, Double> map = new HashMap<>(ratios(player));

        if (ratio >= FULL) {
            map.remove(attribute);
            inst.removeModifier(modifierId(attribute));
        } else {
            map.put(attribute, ratio);
            refresh(inst, attribute, ratio);
        }

        player.setData(RATIOS.get(), Map.copyOf(map));
        return true;
    }

    /** Clears every dial. */
    public static void resetAll(ServerPlayer player) {
        for (ResourceLocation id : ratios(player).keySet()) {
            var holder = BuiltInRegistries.ATTRIBUTE.getHolder(id).orElse(null);
            AttributeInstance inst = holder == null ? null : player.getAttribute(holder);

            if (inst != null)
                inst.removeModifier(modifierId(id));
        }

        player.setData(RATIOS.get(), Map.of());
    }

    /** Puts the modifier back in line with the current gear. Cheap: skips the write when nothing changed. */
    private static void refresh(AttributeInstance inst, ResourceLocation attribute, double ratio) {
        ResourceLocation modId = modifierId(attribute);
        double raw = total(inst, modId, false);
        double gear = inst.getAttribute().value().sanitizeValue(raw);
        double amount = raw == 0.0 ? 0.0 : gear * ratio / raw - 1.0;

        if (!Double.isFinite(amount))
            amount = 0.0;

        amount = Math.max(amount, -1.0);
        AttributeModifier existing = inst.getModifier(modId);

        if (existing != null && Math.abs(existing.amount() - amount) < 1e-9)
            return;

        inst.addOrUpdateTransientModifier(new AttributeModifier(modId, amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** (Re)applies every stored dial, or removes them all while the feature is switched off. */
    public static void reapply(ServerPlayer player) {
        boolean on = Config.ATTRIBUTE_DIAL.get();

        for (Map.Entry<ResourceLocation, Double> e : ratios(player).entrySet()) {
            var holder = BuiltInRegistries.ATTRIBUTE.getHolder(e.getKey()).orElse(null);
            AttributeInstance inst = holder == null ? null : player.getAttribute(holder);

            if (inst == null)
                continue;

            if (on)
                refresh(inst, e.getKey(), e.getValue());
            else
                inst.removeModifier(modifierId(e.getKey()));
        }
    }

    /** Every attribute the player has, with what gear gives and the dial's ratio (1 = untouched). */
    public static List<Entry> snapshot(ServerPlayer player) {
        Map<ResourceLocation, Double> ratios = ratios(player);
        List<Entry> out = new ArrayList<>();

        BuiltInRegistries.ATTRIBUTE.holders().forEach(holder -> {
            AttributeInstance inst = player.getAttribute(holder);

            if (inst == null)
                return;

            ResourceLocation id = holder.key().location();
            out.add(new Entry(id, inst.getBaseValue(), gearValue(inst, id), ratios.getOrDefault(id, 1.0), inst.getValue()));
        });

        return out;
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            reapply(player);
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            reapply(player);
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % 10 == 0 && !ratios(player).isEmpty())
            reapply(player);
    }
}
