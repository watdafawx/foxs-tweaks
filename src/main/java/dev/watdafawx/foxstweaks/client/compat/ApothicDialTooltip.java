package dev.watdafawx.foxstweaks.client.compat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.shadowsoffire.apothic_attributes.ApothicAttributes;
import dev.shadowsoffire.apothic_attributes.client.AttributeModifierComponent;
import dev.shadowsoffire.apothic_attributes.client.ModifierSource;
import dev.shadowsoffire.apothic_attributes.client.ModifierSourceType;
import dev.watdafawx.foxstweaks.attribute.AttributeDial;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.RangedAttribute;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.TooltipFlag;

/**
 * Optional Apothic Attributes bridge for {@code AttributeDialScreen}. Only classloaded when
 * {@code apothic_attributes} is installed; renders the same item/curio/effect source icons and
 * formatted attribute values/modifiers as Apothic's own attributes panel.
 */
public final class ApothicDialTooltip {
    private static final int MAX_MODIFIERS = 14;

    private ApothicDialTooltip() {
    }

    public static String formatValue(ResourceLocation id, double value, String fallback) {
        Attribute attr = BuiltInRegistries.ATTRIBUTE.get(id);
        if (attr == null)
            return fallback;
        try {
            return attr.toValueComponent(null, value, ApothicAttributes.getTooltipFlag()).getString();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    @SuppressWarnings("deprecation")
    public static boolean renderTooltip(GuiGraphics g, Font font, Player player,
                                        AttributeDial.Entry entry, Component name,
                                        double currentVal, double ratio,
                                        int mouseX, int mouseY, int screenWidth) {
        var holder = BuiltInRegistries.ATTRIBUTE.getHolder(entry.id()).orElse(null);
        if (holder == null)
            return false;

        Attribute attr = holder.value();
        TooltipFlag flag = ApothicAttributes.getTooltipFlag();
        int maxW = Math.max(160, Math.min(260, screenWidth - 40));

        List<ClientTooltipComponent> out = new ArrayList<>();
        addLines(out, font, maxW, name.copy().withStyle(Style.EMPTY.withColor(ChatFormatting.GOLD).withUnderlined(true)));

        String descKey = attr.getDescriptionId() + ".desc";
        if (I18n.exists(descKey))
            addLines(out, font, maxW, Component.translatable(descKey).withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));

        out.add(ClientTooltipComponent.create(CommonComponents.EMPTY.getVisualOrderText()));

        Component curComp = attr.toValueComponent(null, currentVal, flag).withStyle(
                ratio < 1.0 ? ChatFormatting.GOLD
                        : Math.abs(entry.gear() - entry.base()) < 1e-9 ? ChatFormatting.GRAY : ChatFormatting.GREEN);
        addLines(out, font, maxW, Component.translatable("foxstweaks.dial.tip.current", curComp).withStyle(ChatFormatting.GRAY));

        Component baseComp = attr.toValueComponent(null, entry.base(), flag).withStyle(ChatFormatting.GRAY);
        if (attr instanceof RangedAttribute ranged) {
            Component minComp = attr.toValueComponent(null, ranged.getMinValue(), flag);
            Component maxComp = attr.toValueComponent(null, ranged.getMaxValue(), flag);
            addLines(out, font, maxW, Component.literal("Base: ").append(baseComp)
                    .append(" \u2507 Min: ").append(minComp)
                    .append(" \u2507 Max: ").append(maxComp)
                    .withStyle(ChatFormatting.GRAY));
        } else {
            addLines(out, font, maxW, Component.translatable("foxstweaks.dial.tip.base", baseComp).withStyle(ChatFormatting.GRAY));
        }

        var inst = player.getAttribute(holder);
        if (inst != null) {
            ResourceLocation dialId = AttributeDial.modifierId(entry.id());
            List<AttributeModifier> mods = new ArrayList<>();
            for (AttributeModifier m : inst.getModifiers()) {
                if (!m.id().equals(dialId) && m.amount() != 0.0)
                    mods.add(m);
            }

            if (!mods.isEmpty()) {
                Map<ResourceLocation, ModifierSource<?>> sources = new HashMap<>();
                for (ModifierSourceType<?> type : ModifierSourceType.getTypes()) {
                    try {
                        type.extract(player, (modif, source) -> sources.put(modif.id(), source));
                    } catch (Throwable ignored) {
                    }
                }
                mods.sort(ModifierSourceType.compareBySource(sources));

                out.add(ClientTooltipComponent.create(CommonComponents.EMPTY.getVisualOrderText()));
                addLines(out, font, maxW, Component.translatable("foxstweaks.dial.tip.modifiers").withStyle(ChatFormatting.GOLD));

                int shown = 0;
                for (AttributeModifier m : mods) {
                    if (shown++ == MAX_MODIFIERS) {
                        addLines(out, font, maxW, Component.translatable("foxstweaks.dial.tip.more", mods.size() - MAX_MODIFIERS)
                                .withStyle(ChatFormatting.DARK_GRAY));
                        break;
                    }
                    Component comp = attr.toComponent(m, flag);
                    ModifierSource<?> src = sources.get(m.id());
                    out.add(new AttributeModifierComponent(src, comp, font, maxW));
                }
            }
        }

        if (ratio < 1.0) {
            String floorStr = formatValue(entry.id(), entry.floor(), Double.toString(entry.floor()));
            String gearStr = formatValue(entry.id(), entry.gear(), Double.toString(entry.gear()));
            out.add(ClientTooltipComponent.create(CommonComponents.EMPTY.getVisualOrderText()));
            addLines(out, font, maxW, Component.translatable("foxstweaks.dial.tip.dial",
                    Math.round(ratio * 100), floorStr, gearStr).withStyle(ChatFormatting.GOLD));
        }

        ((dev.watdafawx.foxstweaks.mixin.GuiGraphicsTooltipInvoker) g)
                .foxstweaks$renderTooltipInternal(font, out, mouseX, mouseY, DefaultTooltipPositioner.INSTANCE);
        return true;
    }

    private static void addLines(List<ClientTooltipComponent> out, Font font, int maxW, Component comp) {
        if (comp == CommonComponents.EMPTY) {
            out.add(ClientTooltipComponent.create(comp.getVisualOrderText()));
            return;
        }
        for (FormattedText line : font.getSplitter().splitLines(comp, maxW, comp.getStyle()))
            out.add(ClientTooltipComponent.create(Language.getInstance().getVisualOrder(line)));
    }
}
