package dev.mtop.foxstweaks.client;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.FoxsTweaks;
import com.mojang.datafixers.util.Either;
import dev.mtop.foxstweaks.client.compat.ApothicTooltip;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import org.joml.Vector2ic;

import java.util.List;

/**
 * Draws a second tooltip beside the main one listing an item's Apotheosis sockets and affixes,
 * while {@link FoxsTweaksKeys#SHOW_AFFIX_INFO} is held. The main tooltip is never modified.
 *
 * <p>Apotheosis hides affix-granted attribute lines from the normal tooltip (through
 * {@code GatherSkippedAttributeTooltipsEvent}) because it renders its own affix block instead, and
 * that block is easy to miss. Rather than fight over the same tooltip, this puts the information in
 * its own panel next to it.
 *
 * <p>This applies to any item with affixes or sockets, from any mod - there is no relic check.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID, value = Dist.CLIENT)
public class AffixTooltipHandler {
    /** Gap between the main tooltip and the panel. */
    private static final int GAP = 8;

    /** The default tooltip positioner offsets by (+12, -12) from the position handed to it. */
    private static final int POSITIONER_X_OFFSET = 12;
    private static final int POSITIONER_Y_OFFSET = 12;

    /**
     * Roughly the border and padding vanilla draws around tooltip content, plus slack for the
     * decorative rarity-tier border Apotheosis draws outside that content box (the ornate corners
     * on a mythic/ancient item's tooltip). {@code mainWidth}/{@code mainHeight} below only measure
     * the plain content box, so without this slack a rarity border can still visually touch the
     * panel even though the boxes themselves do not overlap.
     */
    private static final int FRAME = 14;

    /**
     * Drawing a tooltip fires this same event again, so without this the first hover would recurse
     * until the stack overflows.
     */
    private static boolean rendering;

    private static Boolean apotheosisLoaded;

    /**
     * Index, in the gathered tooltip elements, of the affix line {@code AffixToggleScreen} is
     * hovering, or -1. An index rather than text so it works for lines that aren't text at all
     * (Stoneforming's icon row). Valid because the overlay measures with this at -1 and then draws
     * the same stack, so both gathers produce the same list.
     */
    public static int highlightIndex = -1;

    @SubscribeEvent
    public static void onRenderTooltipPre(RenderTooltipEvent.Pre event) {
        if (rendering || !Config.AFFIX_TOOLTIP_PANEL.get() || !isApotheosisLoaded() || !FoxsTweaksKeys.isShowAffixInfoHeld())
            return;

        ItemStack stack = event.getItemStack();

        if ((stack == null || stack.isEmpty()) && Config.CURIOS_TOOLTIP_WORKAROUND.get())
            stack = recoverHoveredStack();

        if (stack == null || stack.isEmpty() || !ApothicTooltip.hasInfo(stack))
            return;

        var elements = ApothicTooltip.build(stack);

        if (elements.isEmpty())
            return;

        Font font = event.getFont();
        List<ClientTooltipComponent> components = event.getComponents();

        // Reproduce vanilla's own measurement and positioning (GuiGraphics#renderTooltipInternal),
        // using the very positioner it is about to use, so the panel lands against the real edge of
        // the tooltip rather than an estimate of it. NeoForge exposes no post-render tooltip event
        // in 1.21.1, so this is the only way to know the final geometry without a mixin.
        int mainWidth = 0;
        int mainHeight = components.size() == 1 ? -2 : 0;

        for (ClientTooltipComponent component : components) {
            mainWidth = Math.max(mainWidth, component.getWidth(font));
            mainHeight += component.getHeight();
        }

        Vector2ic anchor = event.getTooltipPositioner().positionTooltip(
                event.getScreenWidth(), event.getScreenHeight(),
                event.getX(), event.getY(),
                mainWidth, mainHeight);

        int width = ApothicTooltip.width(font, elements);
        int x = anchor.x() + mainWidth + GAP;

        // Flip to the left of the main tooltip rather than letting the positioner shove the panel
        // back inside the screen, where it would land on top of the tooltip it belongs to.
        if (x + width + FRAME > event.getScreenWidth())
            x = anchor.x() - width - FRAME - GAP;

        rendering = true;

        try {
            event.getGraphics().renderComponentTooltipFromElements(
                    font, elements,
                    x - POSITIONER_X_OFFSET,
                    anchor.y() + POSITIONER_Y_OFFSET,
                    stack);
        } finally {
            rendering = false;
        }
    }

    /**
     * A disabled affix (see {@code compat.ApothicAffixToggle}) is actually removed from Apotheosis'
     * own affix component, so it disappears from the item's normal tooltip entirely - not just from
     * this panel. Appends the same "❌ " lines {@link ApothicTooltip#disabledAffixLines} builds for
     * the panel to the real tooltip too, unconditionally (no key needed), so a disabled affix still
     * reads as "present but off" rather than vanishing outright.
     */
    // LOWEST: after Apotheosis' own GatherComponents listener has swapped its marker lines for
    // components, so the list we splice into and index into is the one that actually gets drawn.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onGatherComponents(RenderTooltipEvent.GatherComponents event) {
        if (!Config.AFFIX_TOGGLE_GUI.get() || !isApotheosisLoaded())
            return;

        ItemStack stack = event.getItemStack();

        if (stack.isEmpty())
            return;

        List<Either<FormattedText, TooltipComponent>> elements = event.getTooltipElements();

        ApothicTooltip.insertDisabledLines(stack, elements);

        if (highlightIndex > 0 && highlightIndex < elements.size())
            elements.set(highlightIndex, highlighted(elements.get(highlightIndex)));
    }

    private static Either<FormattedText, TooltipComponent> highlighted(Either<FormattedText, TooltipComponent> element) {
        if (element.right().isPresent())
            return Either.right(new HighlightedTooltipComponent(element.right().get()));

        return Either.left(Component.literal("▶ ")
                .append(Component.literal(element.left().get().getString()))
                .withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD));
    }

    private static boolean isApotheosisLoaded() {
        if (apotheosisLoaded == null)
            apotheosisLoaded = ModList.get().isLoaded(FoxsTweaks.APOTHEOSIS);

        return apotheosisLoaded;
    }

    /**
     * Some screens forget to pass the hovered {@link ItemStack} into their {@code GuiGraphics}
     * tooltip call, which leaves {@link RenderTooltipEvent.Pre#getItemStack()} empty even though a
     * real item is being hovered. Curios' own inventory screen does this for every populated curio
     * slot - {@code CuriosScreen#renderTooltip} calls the {@code GuiGraphics#renderTooltip} overload
     * that takes no stack, instead of the one vanilla's own {@link AbstractContainerScreen} uses
     * (TheIllusiveC4/Curios#536) - so this panel would otherwise never appear there.
     *
     * <p>Reads the generic {@link AbstractContainerScreen#hoveredSlot} field (widened by the access
     * transformer) rather than anything Curios-specific, so it fixes any screen with the same bug,
     * not just Curios', and needs no reference to a Curios type. It cannot recover Apotheosis' own
     * tooltip content, which reads the same broken event from Apotheosis' own code.
     */
    private static ItemStack recoverHoveredStack() {
        if (Minecraft.getInstance().screen instanceof AbstractContainerScreen<?> screen && screen.hoveredSlot != null)
            return screen.hoveredSlot.getItem();

        return ItemStack.EMPTY;
    }
}
