package dev.mtop.foxstweaks.client.compat;

import com.mojang.datafixers.util.Either;
import dev.shadowsoffire.apotheosis.Apoth.Components;
import dev.shadowsoffire.apotheosis.affix.Affix;
import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.affix.AttributeProvidingAffix;
import dev.shadowsoffire.apotheosis.client.AdventureModuleClient;
import dev.shadowsoffire.apotheosis.client.SocketTooltipRenderer;
import dev.shadowsoffire.apotheosis.socket.SocketHelper;
import dev.shadowsoffire.apotheosis.util.ApothMiscUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.neoforge.common.util.AttributeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the Apotheosis half of the second tooltip.
 *
 * <p>Every reference to an Apotheosis class in this addon lives in this file, so the class is only
 * ever loaded once {@code AffixTooltipHandler} has confirmed Apotheosis is installed. Touching it
 * without that check would throw {@link NoClassDefFoundError} on a pack without Apotheosis.
 *
 * <p>Content and order deliberately mirror the tooltip Apotheosis draws itself - name, affixes,
 * attributes, then sockets - and are produced with its own helpers, so the panel stays in step if
 * Apotheosis changes its formatting.
 */
public final class ApothicTooltip {
    private ApothicTooltip() {
    }

    /** True when the stack has anything worth showing: sockets (even empty ones) or affixes. */
    public static boolean hasInfo(ItemStack stack) {
        return SocketHelper.getSockets(stack) > 0 || AffixHelper.hasAffixes(stack);
    }

    public static List<Either<FormattedText, TooltipComponent>> build(ItemStack stack) {
        var ctx = AdventureModuleClient.tooltipCtx();
        List<Either<FormattedText, TooltipComponent>> elements = new ArrayList<>();

        // The panel is detached from the item, so it needs to say what it is describing. The caption
        // beneath the name also identifies the panel itself - it can end up right beside or touching
        // the main tooltip, which uses the same dark styling, and would otherwise be indistinguishable.
        elements.add(Either.left(stack.getHoverName()));
        elements.add(Either.left(Component.translatable("foxstweaks.affix_panel.caption")
                .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC)));

        // Needed up front to dedupe against below: an armor/weapon-slot item bakes its affix
        // attributes into this same static component (so they'd otherwise print twice), while a
        // curio-slot item generally doesn't (see the AttributeProvidingAffix branch below).
        List<Component> attributes = gatherAttributes(stack, ctx);
        Set<String> attributeText = attributes.stream().map(Component::getString).collect(Collectors.toSet());

        AffixHelper.streamAffixes(stack)
                .sorted(Comparator.comparingInt(affix -> affix.getAffix().definition().type().ordinal()))
                .forEach(instance -> {
                    // Apotheosis' built-in "apotheosis:attribute" affix (and any other affix that
                    // grants a raw attribute modifier this way, such as apothic_compats' curio
                    // attribute affixes - experienced, spiritual, gravitational, etc.) always returns
                    // an empty getDescription(). On armor/weapon slots Apotheosis bakes the modifier
                    // into the item's static ATTRIBUTE_MODIFIERS component, so gatherAttributes() below
                    // already shows it; on a curio slot that component never gets it, and
                    // gatherModifierTooltips is the only place the value is exposed at all. Ask for it
                    // unconditionally and skip only the lines gatherAttributes() will print anyway, so
                    // both cases end up covered without duplicating the ones that aren't.
                    if (instance.getAffix() instanceof AttributeProvidingAffix attributeAffix) {
                        attributeAffix.gatherModifierTooltips(instance, ctx, line -> {
                            if (!attributeText.contains(line.getString()))
                                elements.add(Either.left(prefixed(line, instance)));
                        });
                        return;
                    }

                    Component description = instance.getDescription(ctx);

                    if (description.getContents() == PlainTextContents.EMPTY)
                        return;

                    elements.add(Either.left(prefixed(description, instance)));
                });

        if (stack.has(Components.DURABILITY_BONUS) && !stack.has(DataComponents.UNBREAKABLE)) {
            Component durability = Component.translatable("affix.apotheosis:durable.desc",
                    Math.round(100 * stack.get(Components.DURABILITY_BONUS)));

            elements.add(Either.left(ApothMiscUtil.dotPrefix(durability).withStyle(ChatFormatting.YELLOW)));
        }

        if (!attributes.isEmpty()) {
            elements.add(Either.left(Component.empty()));

            attributes.forEach(line -> elements.add(Either.left(line)));
        }

        // Sockets last, matching where Apotheosis puts them.
        if (SocketHelper.getSockets(stack) > 0)
            elements.add(Either.right(new SocketTooltipRenderer.SocketComponent(stack, SocketHelper.getGems(stack))));

        return elements;
    }

    /**
     * Apotheosis stars affixes past the standard maximum level and dots the rest.
     */
    private static Component prefixed(Component line, AffixInstance instance) {
        return instance.level() > Affix.STANDARD_MAX_LEVEL
                ? ApothMiscUtil.starPrefix(line).withStyle(ChatFormatting.YELLOW)
                : ApothMiscUtil.dotPrefix(line).withStyle(ChatFormatting.YELLOW);
    }

    /**
     * The "When on Legs: +6 Armor ..." block, exactly as the normal tooltip would render it.
     *
     * <p>Uses {@code applyModifierTooltips} rather than {@code addAttributeTooltips}: the latter
     * also posts {@code AddAttributeTooltipsEvent}, which is where Apotheosis injects its gem socket
     * marker line - that would duplicate the socket rows added above.
     *
     * <p>This only ever covers the item's static {@code ATTRIBUTE_MODIFIERS} data component. For a
     * standard armor/weapon slot, Apotheosis bakes an {@link AttributeProvidingAffix}'s modifier into
     * that component too, so its line ends up here same as any base stat. A curio slot generally
     * doesn't get that treatment - its modifier only exists dynamically through
     * {@code StackAttributeModifiersEvent} - which is why the affix loop above also asks
     * {@code gatherModifierTooltips} directly, deduping against this method's output as it goes.
     */
    private static List<Component> gatherAttributes(ItemStack stack, net.neoforged.neoforge.common.util.AttributeTooltipContext ctx) {
        ItemAttributeModifiers modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);

        if (!modifiers.showInTooltip())
            return List.of();

        List<Component> lines = new ArrayList<>();

        AttributeUtil.applyModifierTooltips(stack, lines::add, ctx);

        return lines;
    }

    /**
     * Content width of the panel, needed up front to decide whether it still fits to the right of
     * the main tooltip or has to be flipped to its left.
     */
    public static int width(Font font, List<Either<FormattedText, TooltipComponent>> elements) {
        int width = 0;

        for (var element : elements) {
            if (element.left().isPresent())
                width = Math.max(width, font.width(element.left().get()));
            else if (element.right().orElse(null) instanceof SocketTooltipRenderer.SocketComponent socket)
                width = Math.max(width, new SocketTooltipRenderer(socket).getWidth(font));
        }

        return width;
    }
}
