package dev.mtop.foxstweaks.client.compat;

import com.mojang.datafixers.util.Either;
import dev.mtop.foxstweaks.compat.ApothicAffixToggle;
import dev.shadowsoffire.apotheosis.Apoth.Components;
import dev.shadowsoffire.apotheosis.affix.Affix;
import dev.shadowsoffire.apotheosis.affix.AffixHelper;
import dev.shadowsoffire.apotheosis.affix.AffixInstance;
import dev.shadowsoffire.apotheosis.affix.AttributeProvidingAffix;
import dev.shadowsoffire.apotheosis.client.AdventureModuleClient;
import dev.shadowsoffire.apotheosis.client.SocketTooltipRenderer;
import dev.shadowsoffire.apotheosis.client.StoneformingTooltipRenderer;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.util.AttributeTooltipContext;

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

    /** True when the stack has anything worth showing: sockets (even empty ones) or affixes, active or disabled. */
    public static boolean hasInfo(ItemStack stack) {
        return SocketHelper.getSockets(stack) > 0 || ApothicAffixToggle.hasAnyAffixes(stack);
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

                    // getAugmentingText(), not getDescription() - a few affixes (StoneformingAffix,
                    // for one) return a sentinel from getDescription() that Apotheosis' own tooltip
                    // code swaps for a custom icon component; getAugmentingText() is the real text,
                    // which is exactly what a plain line here needs. See AffixToggleScreen for the
                    // same fix, in more detail.
                    Component description = instance.getAugmentingText(ctx);

                    if (description.getContents() == PlainTextContents.EMPTY)
                        return;

                    elements.add(Either.left(prefixed(description, instance)));
                });

        elements.addAll(disabledAffixLines(stack));

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
     * Disabled-affix lines, one per affix the overlay pulled off the item. Uses the same description
     * the live tooltip showed ("Enemies below 20% max health will be executed"), not
     * {@code getName(true)} ("Executing") - that name is a fragment Apotheosis stitches into the
     * item title, and reading it as a standalone row was a real bug here once.
     *
     * <p>Only {@link #build} (the Ctrl panel) uses this list as-is, after its live affix block. The
     * item's own tooltip places each line where its affix used to be - see {@link #insertDisabledLines}.
     */
    public static List<Either<FormattedText, TooltipComponent>> disabledAffixLines(ItemStack stack) {
        List<Either<FormattedText, TooltipComponent>> lines = new ArrayList<>();
        var ctx = AdventureModuleClient.tooltipCtx();

        ApothicAffixToggle.list(stack).stream().filter(row -> !row.enabled()).forEach(row ->
                lines.add(Either.left(disabledLine(row.instance(), ctx))));

        return lines;
    }

    /**
     * The live tooltip's own wording for this affix, with a red ❌ prefix. Attribute affixes have
     * no description (their line is a modifier); Stoneforming's {@code getDescription()} is a
     * sentinel swapped for an icon row, so {@code getAugmentingText()} is the readable fallback
     * once that component is gone.
     */
    public static Component disabledLine(AffixInstance instance, AttributeTooltipContext ctx) {
        Component body = tooltipBody(instance, ctx);
        Component marked = Component.literal("❌ ").withStyle(ChatFormatting.RED)
                .append(body.copy().withStyle(ChatFormatting.RED));

        return ApothMiscUtil.dotPrefix(marked);
    }

    public static Component tooltipBody(AffixInstance instance, AttributeTooltipContext ctx) {
        if (instance.getAffix() instanceof AttributeProvidingAffix attributeAffix) {
            List<Component> modifiers = new ArrayList<>();

            attributeAffix.gatherModifierTooltips(instance, ctx, modifiers::add);

            if (!modifiers.isEmpty())
                return modifiers.getFirst();
        } else if (!isStoneformingMarker(instance, ctx)) {
            Component description = instance.getDescription(ctx);

            if (description.getContents() != PlainTextContents.EMPTY)
                return description;
        }

        Component augmenting = instance.getAugmentingText(ctx);

        if (augmenting.getContents() != PlainTextContents.EMPTY)
            return augmenting;

        return instance.getName(true);
    }

    /**
     * Map one gathered tooltip element back onto an affix. Text lines match the real description
     * (or an attribute affix's modifier line, or our ❌ disabled line). Stoneforming's real tooltip
     * line is a {@link StoneformingTooltipRenderer.StoneformingComponent} swapped in for the
     * {@code APOTH_STONEFORMING_MARKER} sentinel - match that by component type, not by text.
     */
    public static ApothicAffixToggle.Row matchRow(
            Either<FormattedText, TooltipComponent> element,
            List<ApothicAffixToggle.Row> rows,
            Set<ResourceLocation> claimed,
            AttributeTooltipContext ctx) {
        if (element.right().isPresent()) {
            TooltipComponent component = element.right().get();

            if (component instanceof SocketTooltipRenderer.SocketComponent)
                return null;

            if (component instanceof StoneformingTooltipRenderer.StoneformingComponent) {
                for (ApothicAffixToggle.Row row : rows) {
                    ResourceLocation id = row.instance().getAffix().id();

                    if (claimed.contains(id) || !row.enabled())
                        continue;

                    if (isStoneformingMarker(row.instance(), ctx))
                        return row;
                }
            }

            return null;
        }

        String line = element.left().map(FormattedText::getString).orElse("");

        if (line.isEmpty())
            return null;

        for (ApothicAffixToggle.Row row : rows) {
            ResourceLocation id = row.instance().getAffix().id();

            if (claimed.contains(id))
                continue;

            AffixInstance instance = row.instance();

            if (!row.enabled()) {
                if (line.contains("❌") && matchCandidates(instance, false, ctx).stream().anyMatch(line::contains))
                    return row;

                continue;
            }

            if (matchCandidates(instance, true, ctx).stream().anyMatch(line::contains))
                return row;
        }

        return null;
    }

    public static List<String> matchCandidates(AffixInstance instance, boolean enabled, AttributeTooltipContext ctx) {
        List<String> candidates = new ArrayList<>();

        if (!enabled) {
            // The live description ("+7.75 Fire Damage"), never getName(true) ("Infernal") -
            // that fragment is stitched into the item title, so contains() would light up the
            // name line instead of the affix we are actually hovering.
            candidates.add(tooltipBody(instance, ctx).getString());
            return candidates;
        }

        if (instance.getAffix() instanceof AttributeProvidingAffix attributeAffix) {
            attributeAffix.gatherModifierTooltips(instance, ctx, line -> candidates.add(line.getString()));
            return candidates;
        }

        if (isStoneformingMarker(instance, ctx))
            return candidates;

        Component description = instance.getDescription(ctx);

        if (description.getContents() != PlainTextContents.EMPTY)
            candidates.add(description.getString());

        return candidates;
    }

    public static boolean isStoneformingMarker(AffixInstance instance, AttributeTooltipContext ctx) {
        return instance.getDescription(ctx).getString().contains("APOTH_STONEFORMING");
    }

    /**
     * Puts a "❌" line for each disabled affix where that affix's own line would be, rather than
     * appending them all at the end.
     *
     * <p>Where "would be" is found by building the tooltip of a copy with the disabled affixes put
     * back (the ghost), finding each affix's line in it, and walking up to the nearest line above it
     * that also exists in the real tooltip - the ❌ goes right after that anchor. This keeps working
     * for affix lines in the attribute block ("+3.25 Luck"), not just the description block, without
     * hardcoding Apotheosis' ordering. Anything with no anchor falls back to the end.
     *
     * <p>The ghost's lines are cached on the stack's hash - this runs every frame the tooltip is up.
     */
    public static void insertDisabledLines(ItemStack stack, List<Either<FormattedText, TooltipComponent>> elements) {
        List<ApothicAffixToggle.Row> disabled = ApothicAffixToggle.list(stack).stream().filter(row -> !row.enabled()).toList();

        if (disabled.isEmpty())
            return;

        var ctx = AdventureModuleClient.tooltipCtx();
        List<String> ghost = ghostLines(stack, disabled);

        record Placed(int ghostIndex, Either<FormattedText, TooltipComponent> line) {
        }

        List<Placed> placed = new ArrayList<>();
        Set<Integer> disabledIndices = new HashSet<>();

        for (ApothicAffixToggle.Row row : disabled) {
            int index = ghostIndexOf(ghost, row.instance(), ctx);

            if (index >= 0)
                disabledIndices.add(index);

            placed.add(new Placed(index < 0 ? Integer.MAX_VALUE : index, Either.left(disabledLine(row.instance(), ctx))));
        }

        placed.sort(Comparator.comparingInt(Placed::ghostIndex));

        List<Either<FormattedText, TooltipComponent>> inserted = new ArrayList<>();

        for (Placed p : placed) {
            int at = -1;

            for (int k = Math.min(p.ghostIndex(), ghost.size()) - 1; k >= 0 && at < 0; k--) {
                String anchor = ghost.get(k);

                if (anchor.isBlank() || disabledIndices.contains(k))
                    continue;

                int found = find(elements, anchor, inserted);

                if (found >= 0)
                    at = found + 1;
            }

            if (at < 0)
                at = elements.size();

            // Earlier ❌ lines already sitting after the same anchor came first in the ghost too.
            while (at < elements.size() && containsIdentity(inserted, elements.get(at)))
                at++;

            elements.add(at, p.line());
            inserted.add(p.line());
        }
    }

    private static int cachedGhostKey;
    private static List<String> cachedGhost = List.of();

    private static List<String> ghostLines(ItemStack stack, List<ApothicAffixToggle.Row> disabled) {
        int key = ItemStack.hashItemAndComponents(stack);

        if (key == cachedGhostKey && !cachedGhost.isEmpty())
            return cachedGhost;

        ItemStack ghost = stack.copy();

        for (ApothicAffixToggle.Row row : disabled)
            ApothicAffixToggle.setEnabled(ghost, row.instance().getAffix().id(), true);

        cachedGhost = Screen.getTooltipFromItem(Minecraft.getInstance(), ghost).stream().map(Component::getString).toList();
        cachedGhostKey = key;

        return cachedGhost;
    }

    /** The affix's line in the ghost tooltip (which is raw lines - Stoneforming is still its marker text there). */
    private static int ghostIndexOf(List<String> ghost, AffixInstance instance, AttributeTooltipContext ctx) {
        List<String> keys = isStoneformingMarker(instance, ctx)
                ? List.of(STONEFORMING_MARKER)
                : matchCandidates(instance, true, ctx);

        // From 1: the title carries affix name fragments.
        for (int i = 1; i < ghost.size(); i++) {
            String line = ghost.get(i);

            if (keys.stream().anyMatch(key -> !key.isEmpty() && line.contains(key)))
                return i;
        }

        return -1;
    }

    /** Real-tooltip index of a ghost line. Apotheosis' markers have become components by now, so match those by type. */
    private static int find(List<Either<FormattedText, TooltipComponent>> elements, String anchor,
            List<Either<FormattedText, TooltipComponent>> inserted) {
        for (int i = 0; i < elements.size(); i++) {
            var element = elements.get(i);

            if (containsIdentity(inserted, element))
                continue;

            if (element.left().isPresent()) {
                if (element.left().get().getString().equals(anchor))
                    return i;
            } else if (element.right().get() instanceof StoneformingTooltipRenderer.StoneformingComponent) {
                if (anchor.contains(STONEFORMING_MARKER))
                    return i;
            } else if (element.right().get() instanceof SocketTooltipRenderer.SocketComponent) {
                if (anchor.contains(SOCKET_MARKER))
                    return i;
            }
        }

        return -1;
    }

    private static boolean containsIdentity(List<?> list, Object value) {
        for (Object o : list) {
            if (o == value)
                return true;
        }

        return false;
    }

    /** Apotheosis' private placeholder text, swapped for a component in its own GatherComponents listener. */
    private static final String STONEFORMING_MARKER = "APOTH_STONEFORMING_MARKER";
    private static final String SOCKET_MARKER = "APOTH_SOCKET_MARKER";

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
