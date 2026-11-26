package dev.watdafawx.foxstweaks.client.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.watdafawx.foxstweaks.client.HologramBlockNames;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code hello} payload's registries and limits: everything the browser needs to offer only valid choices.
 * Block properties are deduplicated into one table ({@code properties}) that blocks refer to by index, because
 * thousands of blocks share the same few dozen ({@code facing}, {@code waterlogged}, ...).
 */
final class HologramWebRegistries {
    private static JsonObject cached;

    private HologramWebRegistries() {
    }

    static synchronized JsonObject get() {
        if (cached == null)
            cached = build();
        return cached;
    }

    private static JsonObject build() {
        JsonObject o = new JsonObject();

        // ---- blocks (same filter as the Studio's grid) ----
        JsonArray propTable = new JsonArray();
        Map<String, Integer> propIndex = new HashMap<>();
        JsonArray blocks = new JsonArray();
        List<Block> sorted = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.asItem() == Items.AIR)
                continue;
            RenderShape shape = block.defaultBlockState().getRenderShape();
            if (shape == RenderShape.MODEL || shape == RenderShape.ENTITYBLOCK_ANIMATED || block instanceof EntityBlock)
                sorted.add(block);
        }
        sorted.sort(Comparator.comparing(b -> BuiltInRegistries.BLOCK.getKey(b).toString()));
        Map<String, String> plainNames = new LinkedHashMap<>();
        for (Block block : sorted)
            plainNames.put(BuiltInRegistries.BLOCK.getKey(block).toString(), block.getName().getString());
        Map<String, String> distinctNames = HologramBlockNames.disambiguate(plainNames);
        for (Block block : sorted) {
            JsonObject b = new JsonObject();
            String blockId = BuiltInRegistries.BLOCK.getKey(block).toString();
            b.addProperty("id", blockId);
            b.addProperty("name", distinctNames.getOrDefault(blockId, plainNames.get(blockId)));
            JsonArray props = new JsonArray();
            JsonObject defaults = new JsonObject();
            for (Property<?> p : block.defaultBlockState().getProperties()) {
                List<String> values = valueNames(p);
                String sig = p.getName() + "=" + String.join(",", values);
                Integer idx = propIndex.get(sig);
                if (idx == null) {
                    idx = propTable.size();
                    propIndex.put(sig, idx);
                    JsonObject def = new JsonObject();
                    def.addProperty("name", p.getName());
                    JsonArray vals = new JsonArray();
                    values.forEach(vals::add);
                    def.add("values", vals);
                    propTable.add(def);
                }
                props.add(idx);
                defaults.addProperty(p.getName(), valueName(block.defaultBlockState().getValue(p), p));
            }
            if (!props.isEmpty()) {
                b.add("props", props);
                b.add("defaults", defaults);
            }
            if (block instanceof EntityBlock)
                b.addProperty("blockEntity", true);
            blocks.add(b);
        }
        o.add("properties", propTable);
        o.add("blocks", blocks);

        // ---- entities and particles ----
        JsonArray entities = new JsonArray();
        BuiltInRegistries.ENTITY_TYPE.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().location().toString()))
                .forEach(e -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("id", e.getKey().location().toString());
                    j.addProperty("name", e.getValue().getDescription().getString());
                    entities.add(j);
                });
        o.add("entities", entities);

        JsonArray particles = new JsonArray();
        BuiltInRegistries.PARTICLE_TYPE.entrySet().stream()
                .filter(e -> e.getValue() instanceof SimpleParticleType)
                .map(e -> e.getKey().location().toString())
                .sorted()
                .forEach(particles::add);
        o.add("particles", particles);

        // ---- effects ----
        JsonArray effects = new JsonArray();
        for (HologramEffect.Type t : HologramEffect.Type.values()) {
            HologramEffect def = HologramEffect.of(t);
            JsonObject j = new JsonObject();
            j.addProperty("type", t.name());
            j.addProperty("label", def.label());
            j.addProperty("short", t.shortName());
            j.addProperty("family", t.isFx() ? "fx" : t.isMotion() ? "motion" : "entity");
            j.addProperty("usesAxis", t.usesAxis());
            j.addProperty("usesAmount", t.usesAmount());
            j.addProperty("usesPhase", t.usesPhase());
            j.addProperty("usesSpeed", t.usesSpeed());
            j.addProperty("usesColor", t == HologramEffect.Type.TINT || t == HologramEffect.Type.GRADIENT);
            j.addProperty("usesColor2", t == HologramEffect.Type.GRADIENT);
            j.addProperty("min", t.isFx() ? 0.0F : t.minAmount());
            j.addProperty("max", t.isFx() ? 2.0F : t.maxAmount());
            j.addProperty("step", t.amountStep());
            j.addProperty("decimals", t.amountDecimals());
            j.add("defaults", HologramWebJson.effect(def));
            effects.add(j);
        }
        o.add("effects", effects);

        JsonArray triggers = new JsonArray();
        for (HologramEffect.Trigger t : HologramEffect.Trigger.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", t.name());
            j.addProperty("label", t.label());
            j.addProperty("pulse", t.isPulse());
            triggers.add(j);
        }
        o.add("triggers", triggers);

        JsonArray presets = new JsonArray();
        for (String name : HologramEffect.PRESETS) {
            JsonObject j = new JsonObject();
            j.addProperty("name", name);
            j.add("effects", HologramWebJson.effects(HologramEffect.preset(name)));
            presets.add(j);
        }
        o.add("presets", presets);

        JsonArray reveals = new JsonArray();
        for (String name : new String[] { "Rise", "Pop", "Unfold", "Spin", "Drop", "Glitch" })
            reveals.add(name);
        o.add("revealStyles", reveals);

        JsonObject limits = new JsonObject();
        limits.addProperty("maxLayers", HologramLayer.MAX_LAYERS);
        limits.addProperty("maxGroups", HologramGroup.MAX_GROUPS);
        limits.addProperty("maxEffects", HologramSpec.MAX_EFFECTS);
        limits.addProperty("maxNameLength", 32);
        limits.add("offset", range(-16, 16));
        limits.add("rootScale", range(0.25F, 8));
        limits.add("groupScale", range(0.1F, 8));
        limits.add("layerScale", range(0.05F, 8));
        limits.add("spin", range(-360, 360));
        limits.add("hover", range(0, 4));
        limits.add("hoverPeriod", range(0.5F, 120));
        limits.add("speed", range(0.05F, 20));
        limits.add("revealRadius", range(0, 64));
        limits.add("revealSeconds", range(0, 10));
        limits.add("renderDistance", range(8, 512));
        limits.add("particleRate", range(1, 40));
        limits.add("particleSpeed", range(-0.5F, 0.5F));
        o.add("limits", limits);

        // Timeline vocabulary; the per-server caps on tracks and keys come with hello as timelineCaps.
        JsonObject timeline = new JsonObject();
        timeline.add("length", range(dev.watdafawx.foxstweaks.hologram.HologramTimeline.MIN_LENGTH,
                dev.watdafawx.foxstweaks.hologram.HologramTimeline.MAX_LENGTH));
        JsonArray modes = new JsonArray();
        for (var m : dev.watdafawx.foxstweaks.hologram.HologramTimeline.Mode.values())
            modes.add(m.name());
        timeline.add("modes", modes);
        JsonArray eases = new JsonArray();
        for (var e : dev.watdafawx.foxstweaks.hologram.HologramTimeline.Ease.values())
            eases.add(e.name());
        timeline.add("eases", eases);
        JsonArray props = new JsonArray();
        for (var p : dev.watdafawx.foxstweaks.hologram.HologramTimeline.Prop.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", p.name());
            j.addProperty("min", p.min());
            j.addProperty("max", p.max());
            j.addProperty("stepped", p.stepped());
            props.add(j);
        }
        timeline.add("props", props);
        o.add("timeline", timeline);
        return o;
    }

    private static JsonArray range(float min, float max) {
        JsonArray a = new JsonArray();
        a.add(min);
        a.add(max);
        return a;
    }

    private static <T extends Comparable<T>> List<String> valueNames(Property<T> p) {
        List<String> out = new ArrayList<>();
        for (T v : p.getPossibleValues())
            out.add(p.getName(v));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Comparable<T>> String valueName(Comparable<?> value, Property<T> p) {
        return p.getName((T) value);
    }
}
