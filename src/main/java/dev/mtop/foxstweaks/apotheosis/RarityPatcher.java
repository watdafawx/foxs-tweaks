package dev.mtop.foxstweaks.apotheosis;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;

import dev.mtop.foxstweaks.Config;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;

/**
 * Rewrites Apotheosis affix JSON in memory, just before Placebo decodes it, so affix packs written
 * for one set of rarities work with rarities added by other mods.
 *
 * <p>An Apotheosis affix only exists at a rarity it lists a value for, so a rarity-adding mod
 * (Apothic Ascension, Ancient Reforging) is invisible to every affix pack that predates it. Two
 * patches, both driven by {@code DynamicRegistryMixin}:
 *
 * <ul>
 *   <li><b>Ragnarok -> Ancient Reforging.</b> Ragnarok's gun affixes only know its own
 *       {@code apotheosis_modern_ragnarok:ancient}; copied to {@code ancientreforging:ancient}, a gun
 *       reforged at the Ancient Reforging table gets affixes instead of none.
 *   <li><b>Any affix pack -> Apothic Ascension.</b> Ascension hand-tunes values for the vanilla
 *       affixes only. Every other affix gets values for its 13 rarities extrapolated from the pack's
 *       own top tier, on the same curve Ascension uses for its own data.
 * </ul>
 *
 * <p>Patching the loaded JSON rather than shipping replacement files means nothing is redistributed,
 * nothing goes stale when a pack updates, and a user's own datapack overrides are patched too.
 */
public final class RarityPatcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MYTHIC = "apotheosis:mythic";
    private static final String RAGNAROK_ANCIENT = "apotheosis_modern_ragnarok:ancient";
    private static final String AR_ANCIENT = "ancientreforging:ancient";

    /** Ascension's rarities in ascending order, paired with how far each is along the curve to the last. */
    static final String[] ASCENSION = {
            "legendary", "ancient", "forgotten", "primal", "stellar", "divine", "esoteric",
            "cataclysmic", "abyssal", "empyrean", "paracausal", "transcendent", "apotheotic" };

    /**
     * Fraction of the way from a pack's top tier to its Ascension endpoint, read back out of
     * Ascension's own affix data (the same for every affix it ships, bar deliberate outliers).
     */
    static final double[] CURVE = {
            0.1237, 0.2409, 0.3514, 0.4549, 0.5512, 0.6399, 0.7208,
            0.7933, 0.857, 0.911, 0.9544, 0.9855, 1.0 };

    /** Endpoint multipliers for a range's {min, max}: Ascension's median per attribute operation. */
    private static final double[] ADD_VALUE = { 2.67, 4.0 };
    private static final double[] ADD_BASE = { 3.9, 5.9 };
    private static final double[] ADD_TOTAL = { 1.9, 2.5 };

    private static final double DURATION_GAIN = 5.5;
    private static final double COOLDOWN_CUT = 0.925;
    private static final int AMPLIFIER_GAIN = 3;
    private static final int LEVEL_GAIN = 3;
    private static final double FRACTION_CAP = 0.95;

    /** Keys whose values are counts or tick lengths, so must stay integers. */
    private static final Set<String> WHOLE_KEYS = Set.of("cooldown", "duration", "amplifier", "level", "rolls", "targets");
    private static final Set<String> PROPORTION_KEYS = Set.of("chance", "rate");

    private static Boolean ascensionLoaded;
    private static Boolean ancientReforgingLoaded;
    private static Boolean ragnarokAncientLoaded;

    private RarityPatcher() {
    }

    public static void patch(String registryPath, Map<ResourceLocation, JsonElement> data) {
        if (!registryPath.equals("affixes"))
            return;

        if (ascensionLoaded == null) {
            var mods = ModList.get();
            ascensionLoaded = mods.isLoaded("apothic_ascension");
            ancientReforgingLoaded = mods.isLoaded("ancientreforging");
            ragnarokAncientLoaded = mods.isLoaded("apotheosis_modern_ragnarok") && ancientReforgingLoaded;
        }

        boolean ascension = ascensionLoaded && Config.ASCENSION_COMPAT.get();
        boolean ragnarok = ragnarokAncientLoaded && Config.RAGNAROK_ANCIENT_REFORGING.get();
        boolean ancient = ancientReforgingLoaded && Config.ANCIENT_REFORGING_COMPAT.get();
        if (!ascension && !ragnarok && !ancient)
            return;

        int aliased = 0;
        int extended = 0;

        for (var entry : data.entrySet()) {
            if (!(entry.getValue() instanceof JsonObject affix))
                continue;

            if (ragnarok && alias(affix, RAGNAROK_ANCIENT, AR_ANCIENT))
                aliased++;
            if ((ascension || ancient) && extend(affix, ascension, ancient))
                extended++;
        }

        if (aliased > 0)
            LOGGER.info("Ragnarok x Ancient Reforging: {} affixes now accept {}", aliased, AR_ANCIENT);
        if (extended > 0)
            LOGGER.info("Rarity compat: extended {} third-party affixes (Apothic Ascension: {}, Ancient Reforging: {})", extended, ascension, ancient);
    }

    // --- Ragnarok -> Ancient Reforging ---------------------------------------------------------

    /** Wherever {@code src} appears as a rarity (a map key or a list entry), make {@code dst} appear too. */
    static boolean alias(JsonElement node, String src, String dst) {
        boolean changed = false;

        if (node instanceof JsonObject obj) {
            if (obj.has(src) && !obj.has(dst)) {
                obj.add(dst, obj.get(src).deepCopy());
                changed = true;
            }
            for (var child : Set.copyOf(obj.keySet()))
                changed |= alias(obj.get(child), src, dst);
        }
        else if (node instanceof JsonArray arr) {
            var copy = new JsonPrimitive(dst);
            for (int i = 0; i < arr.size(); i++) {
                if (arr.get(i).equals(new JsonPrimitive(src)) && !arr.contains(copy)) {
                    arr.add(copy);
                    changed = true;
                    break;
                }
            }
            for (var child : arr)
                changed |= alias(child, src, dst);
        }

        return changed;
    }

    // --- Apothic Ascension ---------------------------------------------------------------------

    /** {@code operation} of the enclosing affix picks the endpoint multipliers; absent means add_value. */
    static boolean extend(JsonObject affix, boolean ascension, boolean ancient) {
        var op = affix.has("operation") && affix.get("operation").isJsonPrimitive() ? affix.get("operation").getAsString() : "";
        double[] factors = switch (op) {
            case "add_multiplied_base" -> ADD_BASE;
            case "add_multiplied_total" -> ADD_TOTAL;
            default -> ADD_VALUE;
        };
        // Values that are not attribute modifiers and never exceed 1 are proportions (proc chances,
        // mana-cost cuts, ammo saved); scaling those past 100% is meaningless, so they get capped.
        var type = affix.has("type") ? affix.get("type").getAsString() : "";
        boolean fractions = !affix.has("operation") && !type.contains("attribute");
        return extend(affix, new Scaling(factors, fractions, ascension, ancient));
    }

    /**
     * {@code factors}: endpoint multipliers for {min, max}. {@code fractions}: cap sub-1 values at
     * {@link #FRACTION_CAP}. {@code ascension} / {@code ancient}: which of Ascension's 13 rarities and
     * Ancient Reforging's Ancient to add.
     */
    private record Scaling(double[] factors, boolean fractions, boolean ascension, boolean ancient) {
    }

    private static boolean extend(JsonElement node, Scaling scaling) {
        boolean changed = false;

        if (node instanceof JsonObject obj) {
            var top = obj.has(RAGNAROK_ANCIENT) ? RAGNAROK_ANCIENT : MYTHIC;
            if (obj.has(top) && !obj.has(ascension(0))) {
                boolean added = false;
                if (scaling.ascension()) {
                    for (int i = 0; i < ASCENSION.length; i++)
                        obj.add(ascension(i), scale(null, obj.get(top), CURVE[i], scaling));
                    added = true;
                }
                // Ancient Reforging's Ancient is level with Ascension's Legendary (both sort index 800).
                if (scaling.ancient() && !obj.has(AR_ANCIENT)) {
                    obj.add(AR_ANCIENT, scale(null, obj.get(top), CURVE[0], scaling));
                    added = true;
                }
                return added;
            }
            for (var child : Set.copyOf(obj.keySet()))
                changed |= extend(obj.get(child), scaling);
        }
        else if (node instanceof JsonArray arr && isRarityList(arr, scaling)) {
            if (scaling.ancient() && !arr.contains(new JsonPrimitive(AR_ANCIENT)))
                arr.add(new JsonPrimitive(AR_ANCIENT));
            if (scaling.ascension()) {
                for (int i = 0; i < ASCENSION.length; i++)
                    arr.add(new JsonPrimitive(ascension(i)));
            }
            return true;
        }
        else if (node instanceof JsonArray arr) {
            for (var child : arr)
                changed |= extend(child, scaling);
        }

        return changed;
    }

    private static String ascension(int i) {
        return "apothic_ascension:" + ASCENSION[i];
    }

    /** A list of rarity ids that reaches the top tier but stops short of the tiers being added. */
    private static boolean isRarityList(JsonArray arr, Scaling scaling) {
        var ids = List.of(new JsonPrimitive(MYTHIC), new JsonPrimitive(RAGNAROK_ANCIENT));
        if (ids.stream().noneMatch(arr::contains))
            return false;

        return (scaling.ascension() && !arr.contains(new JsonPrimitive(ascension(0))))
                || (scaling.ancient() && !arr.contains(new JsonPrimitive(AR_ANCIENT)));
    }

    /**
     * Scales one rarity's value tree to curve position {@code t}. What a number means depends on the
     * key it sits under: a cooldown shrinks where a duration grows, amplifiers and levels step up
     * rather than multiply, and proportions never pass {@link #FRACTION_CAP}. Anything unrecognised -
     * strings, flags, structured data - is copied as it was, which at worst leaves the affix
     * available at the new rarity without a boost.
     */
    private static JsonElement scale(String key, JsonElement node, double t, Scaling scaling) {
        if (node instanceof JsonObject obj) {
            if (isRange(obj))
                return scaleRange(key, obj, t, scaling);

            var out = new JsonObject();
            for (var e : obj.entrySet())
                out.add(e.getKey(), scale(e.getKey(), e.getValue(), t, scaling));
            return out;
        }

        if (node instanceof JsonArray arr) {
            var out = new JsonArray();
            for (var e : arr)
                out.add(scale(key, e, t, scaling));
            return out;
        }

        if (node.isJsonPrimitive() && node.getAsJsonPrimitive().isNumber()) {
            boolean whole = isIntegerLiteral(node) || (key != null && WHOLE_KEYS.contains(key));
            return number(scaleNumber(key, node.getAsDouble(), t, scaling), whole);
        }

        return node.deepCopy();
    }

    private static double scaleNumber(String key, double v, double t, Scaling scaling) {
        if (key == null)
            key = "";

        return switch (key) {
            case "cooldown" -> Math.max(1, Math.round(v * (1 - COOLDOWN_CUT * t)));
            case "duration" -> v * (1 + DURATION_GAIN * t);
            case "amplifier" -> v + Math.round(AMPLIFIER_GAIN * t);
            case "level" -> v + Math.round(LEVEL_GAIN * t);
            default -> capFraction(key, v, v * lerp(scaling.factors()[1], t), scaling);
        };
    }

    /** Proportions stay below 100%: always for a key that names one, and for any sub-1 value of a non-attribute affix. */
    private static double capFraction(String key, double from, double to, Scaling scaling) {
        boolean proportion = PROPORTION_KEYS.contains(key) || (scaling.fractions() && Math.abs(from) <= 1);
        return proportion ? Math.min(FRACTION_CAP, to) : to;
    }

    private static boolean isRange(JsonObject obj) {
        return obj.has("min") && obj.get("min").isJsonPrimitive() && (obj.has("max") || obj.has("steps"));
    }

    /**
     * A range is {min, max, step} or {min, steps, step}; either way min and the implied max scale
     * independently, so a range widens as it climbs. Integer-stepped ranges stay on whole numbers.
     */
    private static JsonElement scaleRange(String key, JsonObject range, double t, Scaling scaling) {
        if (key == null)
            key = "";

        double min = range.get("min").getAsDouble();
        double step = range.has("step") ? range.get("step").getAsDouble() : 0;
        double max = range.has("max") ? range.get("max").getAsDouble() : min + range.get("steps").getAsDouble() * step;

        double newMin;
        double newMax;
        switch (key) {
            case "duration" -> {
                newMin = min * (1 + DURATION_GAIN * t);
                newMax = max * (1 + DURATION_GAIN * t);
            }
            case "amplifier" -> {
                newMin = min + Math.round(AMPLIFIER_GAIN * t);
                newMax = max + Math.round(AMPLIFIER_GAIN * t);
            }
            case "level" -> {
                newMin = min + Math.round(LEVEL_GAIN * t);
                newMax = max + Math.round(LEVEL_GAIN * t);
            }
            default -> {
                newMin = capFraction(key, Math.max(Math.abs(min), Math.abs(max)), min * lerp(scaling.factors()[0], t), scaling);
                newMax = capFraction(key, Math.max(Math.abs(min), Math.abs(max)), max * lerp(scaling.factors()[1], t), scaling);
            }
        }

        boolean explicitMax = range.has("max");
        boolean whole = isIntegral(range.get("min")) && step >= 1 && step == Math.rint(step);
        if (whole) {
            newMin = Math.rint(newMin);
            newMax = newMin + Math.max(0, Math.rint((newMax - newMin) / step)) * step;
        }

        // Placebo insists (max - min) is a whole number of steps, to within 1e-4, and defaults an absent
        // step to 0.01. So keep the pack's number of levels and let the step size follow the new range.
        double newStep = step;
        if (!whole && explicitMax && newMax != newMin) {
            // A pack's {min: 3, max: 3} is one level; scaling widens it, and it must stay exactly one step wide.
            double levels = max == min ? 1 : Math.max(1, Math.rint((max - min) / (step != 0 ? step : 0.01)));
            newStep = (newMax - newMin) / levels;
        }
        else if (!whole && !explicitMax && step != 0 && max != min) {
            newStep = step * (newMax - newMin) / (max - min);
        }

        var out = new JsonObject();
        for (var e : range.entrySet()) {
            switch (e.getKey()) {
                case "min" -> out.add("min", number(newMin, whole));
                case "max" -> out.add("max", number(newMax, whole));
                case "step" -> out.add("step", whole ? number(newStep, true) : significant(newStep));
                case "steps" -> out.add("steps", number(whole && step != 0 ? (newMax - newMin) / step : e.getValue().getAsDouble(), true));
                default -> out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        if (explicitMax && !range.has("step") && newStep != step)
            out.add("step", significant(newStep));
        return out;
    }

    /** Ten significant digits: a step of 0.0005 must survive, where {@link #number}'s six decimals would not. */
    private static JsonPrimitive significant(double v) {
        return new JsonPrimitive(new java.math.BigDecimal(v).round(new java.math.MathContext(10)).doubleValue());
    }

    private static double lerp(double factor, double t) {
        return 1 + (factor - 1) * t;
    }

    /** A literal like {@code 8}, not {@code 8.0}: the pack means an int, and a fractional result would fail to decode. */
    private static boolean isIntegerLiteral(JsonElement e) {
        return !e.getAsString().matches(".*[.eE].*");
    }

    private static boolean isIntegral(JsonElement e) {
        return e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber() && e.getAsDouble() == Math.rint(e.getAsDouble());
    }

    private static JsonPrimitive number(double v, boolean whole) {
        return whole ? new JsonPrimitive((long) Math.rint(v)) : new JsonPrimitive(Math.round(v * 1_000_000d) / 1_000_000d);
    }
}
