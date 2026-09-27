package dev.mtop.foxstweaks.mixin;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * TACZ's attachment tooltip only says which stats an attachment changes ("+ ADS Time", "- Vertical Recoil"), never
 * by how much. Each modifier type builds those lines once in its {@code JsonProperty.initComponents()}, and
 * {@code ClientAttachmentItemTooltip} reads them back through {@code getComponents()}. This appends the numbers from
 * the property's raw modifier to each line, in the units TACZ's own refit screen uses: "+ ADS Time (+0.1s)",
 * "- Vertical Recoil (-10%)".
 *
 * <p>A line is matched by its translation key, {@code tooltip.tacz.attachment.<stat>.increase|decrease}, and the
 * stat picks the modifier out of the property's value: a TACZ {@code Modifier} (addend / percent / multiplier /
 * function) on its own, one side of a {@code Pair} (recoil pitch/yaw, sound distance), one entry of the inaccuracy
 * map, or one field of {@code MoveSpeed}. TACZ types are only touched reflectively, so there is no TACZ compile
 * dependency; anything unrecognised is left as it was.
 */
@Mixin(targets = "com.tacz.guns.api.modifier.JsonProperty", remap = false)
public abstract class TaczAttachmentTooltipMixin {
    @Unique private static final Pattern foxstweaks$KEY = Pattern.compile("tooltip\\.tacz\\.attachment\\.([a-z_]+)\\.(?:increase|decrease)");

    @Shadow protected List<Component> components;
    @Shadow public abstract Object getValue();

    @Unique private List<Component> foxstweaks$decorated;
    @Unique private int foxstweaks$sourceSize = -1;

    @Inject(method = "getComponents", at = @At("RETURN"), cancellable = true)
    private void foxstweaks$withNumbers(CallbackInfoReturnable<List<Component>> cir) {
        if (!Config.TACZ_ATTACHMENT_TOOLTIP_NUMBERS.get() || components.isEmpty()) {
            return;
        }
        if (foxstweaks$decorated == null || foxstweaks$sourceSize != components.size()) {
            List<Component> decorated = new ArrayList<>(components.size());
            for (Component line : components) {
                decorated.add(foxstweaks$decorate(line));
            }
            foxstweaks$decorated = decorated;
            foxstweaks$sourceSize = components.size();
        }
        cir.setReturnValue(foxstweaks$decorated);
    }

    @Unique
    private Component foxstweaks$decorate(Component line) {
        if (!(line.getContents() instanceof TranslatableContents contents)) {
            return line;
        }
        Matcher matcher = foxstweaks$KEY.matcher(contents.getKey());
        if (!matcher.matches()) {
            return line;
        }
        try {
            String text = foxstweaks$describe(matcher.group(1), getValue());
            return text == null ? line : line.copy().append(" (" + text + ")");
        } catch (ReflectiveOperationException | RuntimeException e) {
            return line;
        }
    }

    /**
     * The change for one stat, or null when the value isn't a shape we know.
     */
    @Unique
    private static String foxstweaks$describe(String stat, Object value) throws ReflectiveOperationException {
        if (value == null) {
            return null;
        }
        if (value instanceof Pair<?, ?> pair) {
            // recoil is (pitch, yaw); silence is (sound distance, silenced)
            return foxstweaks$modifier(stat, "yaw".equals(stat) ? pair.right() : pair.left());
        }
        if (value instanceof Map<?, ?> map) {
            String type = switch (stat) {
                case "inaccuracy" -> "STAND";
                case "aim_inaccuracy" -> "AIM";
                case "sneak_inaccuracy" -> "SNEAK";
                case "lie_inaccuracy" -> "LIE";
                default -> null;
            };
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof Enum<?> key && key.name().equals(type)) {
                    return foxstweaks$modifier(stat, entry.getValue());
                }
            }
            return null;
        }
        String moveSpeedGetter = switch (stat) {
            case "movement_speed" -> "getBaseMultiplier";
            case "aim_speed" -> "getAimMultiplier";
            case "reload_speed" -> "getReloadMultiplier";
            default -> null;
        };
        if (moveSpeedGetter != null) {
            // added to the movement speed attribute as ADD_MULTIPLIED_TOTAL, so 0.1 is +10%
            return foxstweaks$signed(foxstweaks$number(value, moveSpeedGetter) * 100) + "%";
        }
        return foxstweaks$modifier(stat, value);
    }

    /**
     * A TACZ {@code Modifier}: TACZ computes {@code (base + addend) * (1 + percent) * multiplier}, then any function.
     */
    @Unique
    private static String foxstweaks$modifier(String stat, Object modifier) throws ReflectiveOperationException {
        if (modifier == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        double addend = foxstweaks$number(modifier, "getAddend");
        double percent = foxstweaks$number(modifier, "getPercent");
        double multiplier = foxstweaks$number(modifier, "getMultiplier");
        Object function = modifier.getClass().getMethod("getFunction").invoke(modifier);
        if (addend != 0) {
            parts.add(foxstweaks$addend(stat, addend));
        }
        if (percent != 0) {
            parts.add(foxstweaks$signed(percent * 100) + "%");
        }
        if (multiplier != 1) {
            parts.add(foxstweaks$signed((multiplier - 1) * 100) + "%");
        }
        if (function instanceof String s && !s.isEmpty()) {
            parts.add("scripted");
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /**
     * Flat change in the stat's own unit, matching TACZ's refit screen.
     */
    @Unique
    private static String foxstweaks$addend(String stat, double addend) {
        return switch (stat) {
            case "ads" -> foxstweaks$signed(addend) + "s";
            case "weight" -> foxstweaks$signed(addend) + "kg";
            case "effective_range", "sound_distance" -> foxstweaks$signed(addend) + "m";
            case "ammo_speed" -> foxstweaks$signed(addend) + "m/s";
            case "rpm" -> foxstweaks$signed(addend) + " rpm";
            case "armor_ignore" -> foxstweaks$signed(addend * 100) + "%";
            case "head_shot" -> foxstweaks$signed(addend) + "x";
            default -> foxstweaks$signed(addend);
        };
    }

    @Unique
    private static double foxstweaks$number(Object target, String getter) throws ReflectiveOperationException {
        return ((Number) target.getClass().getMethod(getter).invoke(target)).doubleValue();
    }

    /**
     * Signed, at most two decimals, no trailing zeros: +0.1, -10, +2.25.
     */
    @Unique
    private static String foxstweaks$signed(double value) {
        String number = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return value > 0 ? "+" + number : number;
    }
}
