package dev.mtop.foxstweaks.client.compat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.mojang.datafixers.util.Either;

import dev.mtop.foxstweaks.apotheosis.ToggleAffixPayload;
import dev.mtop.foxstweaks.client.AffixTooltipHandler;
import dev.mtop.foxstweaks.compat.ApothicAffixToggle;
import dev.shadowsoffire.apotheosis.client.AdventureModuleClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.gui.screens.inventory.tooltip.DefaultTooltipPositioner;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Vector2ic;

/**
 * The item's own tooltip, used as the affix-toggle UI. No separate panel: the same lines Apotheosis
 * already draws are the click targets, so there is nothing to keep in sync with a second list
 * (Stoneforming's icon row included - that line is a {@link TooltipComponent}, not text, which is
 * why a string-matched highlight against a custom menu could never find it).
 *
 * <p>Opened on the held item by {@code client.AffixToggleKeyHandler}. Toggling still sends
 * {@link ToggleAffixPayload}; the server is authoritative (see
 * {@code compat.ApothicAffixToggle#setEnabled}). The stack is mutated locally this frame so the
 * tooltip rebuilds immediately rather than waiting on the slot sync.
 */
public class AffixToggleScreen extends Screen {
    /**
     * {@code DefaultTooltipPositioner} offsets by (+12, -12) from the point handed to
     * {@link GuiGraphics#renderTooltip}. Undo that when centering so the finished tooltip sits
     * where we measured it, not up and right of that point.
     */
    private static final int TOOLTIP_POS_X = 12;
    private static final int TOOLTIP_POS_Y = 12;

    /**
     * Vanilla's tooltip renderer adds 2px after the first component (and compensates in the
     * single-component height with a -2). Hit-testing has to use the same rule or every line
     * after the name is two pixels off.
     */
    private static final int FIRST_COMPONENT_GAP = 2;

    private final ItemStack stack;
    private List<AffixLine> lines = List.of();

    public AffixToggleScreen(ItemStack stack) {
        super(Component.translatable("foxstweaks.affix_toggle.title", stack.getHoverName()));

        this.stack = stack;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Empty on purpose. Screen#render always starts with this (blur the framebuffer, then the
        // dim menu overlay) - skipping the call in our own render() is not enough, because
        // super.render() still invokes it. This screen is a small popup over live gameplay
        // (isPauseScreen() is false), so neither effect belongs here.
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        ItemStack preview = liveStack();

        if (preview.isEmpty() || !ApothicAffixToggle.hasAnyAffixes(preview)) {
            onClose();
            return;
        }

        // Measure without last frame's ▶, then set the target so the draw pass (renderTooltip)
        // is the one that actually restyles the hovered line.
        AffixTooltipHandler.highlightIndex = -1;

        Layout layout = measure(preview);

        int originX = (width - layout.width) / 2 - TOOLTIP_POS_X;
        int originY = (height - layout.height) / 2 + TOOLTIP_POS_Y;
        Vector2ic pos = DefaultTooltipPositioner.INSTANCE.positionTooltip(
                width, height, originX, originY, layout.width, layout.height);

        lines = bindAffixes(preview, layout, pos);
        AffixLine hovered = hit(mouseX, mouseY);

        // ▶ is applied inside GatherComponents (see AffixTooltipHandler) by element index, so it
        // also marks Stoneforming's icon row, which is a TooltipComponent rather than text.
        AffixTooltipHandler.highlightIndex = hovered == null ? -1 : hovered.index;

        graphics.drawCenteredString(font, title, width / 2, Math.max(4, pos.y() - 24), 0xFFFFFF);
        graphics.drawCenteredString(font, Component.translatable("foxstweaks.affix_toggle.hint"),
                width / 2, Math.max(16, pos.y() - 12), 0xAAAAAA);

        graphics.renderTooltip(font, preview, originX, originY);

        if (hovered != null)
            graphics.renderOutline(hovered.x - 2, hovered.y - 1, hovered.w + 4, hovered.h + 2, 0xFFFFFFFF);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        AffixLine line = hit(mouseX, mouseY);

        if (line == null)
            return true;

        boolean enable = !line.enabled;

        ApothicAffixToggle.setEnabled(liveStack(), line.affixId, enable);
        PacketDistributor.sendToServer(new ToggleAffixPayload(line.affixId, enable));
        return true;
    }

    @Override
    public void removed() {
        AffixTooltipHandler.highlightIndex = -1;

        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * The held stack as it currently is, not the reference captured when the screen opened. A
     * container-slot sync replaces the inventory object, so the field would otherwise keep showing
     * the affixes from before the last toggle.
     */
    private ItemStack liveStack() {
        if (minecraft != null && minecraft.player != null) {
            ItemStack held = minecraft.player.getMainHandItem();

            if (!held.isEmpty())
                return held;
        }

        return stack;
    }

    private AffixLine hit(double mouseX, double mouseY) {
        for (AffixLine line : lines) {
            if (mouseX >= line.x && mouseX < line.x + line.w && mouseY >= line.y && mouseY < line.y + line.h)
                return line;
        }

        return null;
    }

    /**
     * Same gather path {@code GuiGraphics#renderTooltip} uses (vanilla lines + image +
     * {@link RenderTooltipEvent.GatherComponents}), so the components we hit-test are the ones
     * actually drawn - including Apotheosis' affix block and our disabled-affix lines.
     */
    private Layout measure(ItemStack preview) {
        List<Component> text = getTooltipFromItem(minecraft, preview);
        Optional<TooltipComponent> image = preview.getTooltipImage();
        List<Either<FormattedText, TooltipComponent>> elements = new ArrayList<>(text.size() + 1);

        for (Component line : text)
            elements.add(Either.left(line));

        image.ifPresent(component -> {
            if (elements.isEmpty())
                elements.add(Either.right(component));
            else
                elements.add(1, Either.right(component));
        });

        // Last argument is max tooltip width, not an Optional - -1 means unlimited, matching
        // GuiGraphics#renderTooltip's own gather when nothing has wrapped the lines yet.
        var event = new RenderTooltipEvent.GatherComponents(preview, width, height, elements, -1);

        NeoForge.EVENT_BUS.post(event);

        List<Either<FormattedText, TooltipComponent>> gathered = event.getTooltipElements();
        List<ClientTooltipComponent> components = new ArrayList<>(gathered.size());

        for (var element : gathered) {
            element.ifLeft(left -> components.add(ClientTooltipComponent.create(Language.getInstance().getVisualOrder(left))))
                    .ifRight(right -> components.add(ClientTooltipComponent.create(right)));
        }

        int tooltipWidth = 0;
        int tooltipHeight = components.size() == 1 ? -2 : 0;

        for (ClientTooltipComponent component : components) {
            tooltipWidth = Math.max(tooltipWidth, component.getWidth(font));
            tooltipHeight += component.getHeight();
        }

        return new Layout(gathered, components, tooltipWidth, tooltipHeight);
    }

    private List<AffixLine> bindAffixes(ItemStack preview, Layout layout, Vector2ic pos) {
        var ctx = AdventureModuleClient.tooltipCtx();
        List<ApothicAffixToggle.Row> rows = ApothicAffixToggle.list(preview);
        Set<ResourceLocation> claimed = new HashSet<>();
        List<AffixLine> bound = new ArrayList<>();

        int y = pos.y();

        for (int i = 0; i < layout.components.size(); i++) {
            ClientTooltipComponent component = layout.components.get(i);
            int height = component.getHeight();
            // Index 0 is the item name. Affix name-fragments live inside it, so matching that
            // line would make hovering the title look like hovering an affix.
            ApothicAffixToggle.Row row = i == 0
                    ? null
                    : ApothicTooltip.matchRow(layout.elements.get(i), rows, claimed, ctx);

            if (row != null) {
                claimed.add(row.instance().getAffix().id());
                bound.add(new AffixLine(i, pos.x(), y, layout.width, height,
                        row.instance().getAffix().id(), row.enabled()));
            }

            y += height + (i == 0 ? FIRST_COMPONENT_GAP : 0);
        }

        return bound;
    }

    private record Layout(
            List<Either<FormattedText, TooltipComponent>> elements,
            List<ClientTooltipComponent> components,
            int width,
            int height) {
    }

    private record AffixLine(
            int index,
            int x, int y, int w, int h,
            ResourceLocation affixId, boolean enabled) {
    }
}
