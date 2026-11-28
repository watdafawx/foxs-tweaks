package dev.watdafawx.foxstweaks.ae2variant.client;

import appeng.api.config.ActionItems;
import appeng.client.gui.AEBaseScreen;
import appeng.client.gui.Icon;
import appeng.client.gui.style.PaletteColor;
import appeng.client.gui.style.ScreenStyle;
import appeng.client.gui.widgets.ActionButton;
import appeng.client.gui.widgets.IconButton;
import dev.watdafawx.foxstweaks.ae2variant.VariantCrafterMenu;
import dev.watdafawx.foxstweaks.ae2variant.VariantRegistry;
import dev.watdafawx.foxstweaks.ae2variant.VariantSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Native AE2-styled screen for the ME Variant Crafter.
 *
 * <p>Uses AE2's {@link AEBaseScreen}, {@code storagebus.png}, {@code states.png}, and
 * {@code palette.json} so AE2 resource packs (including AE2 Darkmode) theme it automatically.
 */
public class VariantCrafterScreen extends AEBaseScreen<VariantCrafterMenu> {
    private final ModeButton modeButton;
    private final ActionButton clearButton;

    public VariantCrafterScreen(
            VariantCrafterMenu menu, Inventory playerInventory, Component title, ScreenStyle style) {
        super(menu, playerInventory, title, style);

        this.modeButton = addToLeftToolbar(new ModeButton(btn -> menu.cycleMode()));
        addToLeftToolbar(new SourceToggleButton(
                VariantSource.CHIPPED,
                resolveIcon("chipped:mason_table", Items.BRICKS)));
        addToLeftToolbar(new SourceToggleButton(
                VariantSource.RECHISELED,
                resolveIcon("rechiseled:chisel", Items.CHISELED_STONE_BRICKS)));
        addToLeftToolbar(new SourceToggleButton(
                VariantSource.CHISEL,
                resolveIcon("chisel:chisel", Items.IRON_INGOT)));
        addToLeftToolbar(new SourceToggleButton(
                VariantSource.FACTORY_BLOCKS,
                resolveIcon("factory_blocks:factory", Items.IRON_BLOCK)));
        addToLeftToolbar(new SourceToggleButton(
                VariantSource.STONECUTTER,
                Items.STONECUTTER));
        this.clearButton = addToLeftToolbar(new ActionButton(ActionItems.CLOSE, menu::clearFilters));
    }

    private static Item resolveIcon(String id, Item fallback) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl != null && BuiltInRegistries.ITEM.containsKey(rl)) {
            Item item = BuiltInRegistries.ITEM.get(rl);
            if (item != Items.AIR) return item;
        }
        return fallback;
    }

    private static final ResourceLocation STORAGEBUS_TEX =
            ResourceLocation.fromNamespaceAndPath("ae2", "textures/guis/storagebus.png");

    @Override
    protected void updateBeforeRender() {
        super.updateBeforeRender();
        clearButton.setVisibility(menu.getFilterMode() == VariantRegistry.FilterMode.FAMILY_FILTER);
    }

    @Override
    public void drawBG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY, float partialTicks) {
        super.drawBG(guiGraphics, offsetX, offsetY, mouseX, mouseY, partialTicks);
        // storagebus.png only bakes slot boxes for the top 2 rows (AE2 draws rows 3-7 only with Capacity Cards).
        // Repeat the exact 9-slot row strip from the active storagebus.png so all 63 slots match AE2 / Darkmode.
        for (int row = 2; row < 7; row++) {
            guiGraphics.blit(STORAGEBUS_TEX, offsetX + 7, offsetY + 28 + row * 18, 7, 28, 162, 18);
        }
    }

    @Override
    public void drawFG(GuiGraphics guiGraphics, int offsetX, int offsetY, int mouseX, int mouseY) {
        super.drawFG(guiGraphics, offsetX, offsetY, mouseX, mouseY);

        int mutedColor = style.getColor(PaletteColor.MUTED_TEXT_COLOR).toARGB();
        VariantRegistry.FilterMode mode = menu.getFilterMode();
        String modeLabel = switch (mode) {
            case FAMILY_FILTER -> "Filter";
            case IN_STOCK -> "In-Stock";
            case ALL -> "All";
        };
        String status = modeLabel + " \u2022 " + menu.activePatternCount + " patterns";
        guiGraphics.drawString(font, status, 8, 17, mutedColor, false);

        // When not in Family Filter mode, subtly dim the 9x7 filter grid so it's obvious it isn't gating patterns.
        if (mode != VariantRegistry.FilterMode.FAMILY_FILTER) {
            guiGraphics.fill(7, 28, 7 + 9 * 18, 28 + 7 * 18, 0x55000000);
            Component hint = Component.translatable(
                    mode == VariantRegistry.FilterMode.IN_STOCK
                            ? "gui.foxstweaks.variant_crafter.overlay.in_stock"
                            : "gui.foxstweaks.variant_crafter.overlay.all");
            int textW = font.width(hint);
            int cx = 7 + (9 * 18 - textW) / 2;
            int cy = 28 + (7 * 18 - font.lineHeight) / 2;
            guiGraphics.fill(cx - 4, cy - 3, cx + textW + 4, cy + font.lineHeight + 3, 0xB0181820);
            guiGraphics.drawString(font, hint, cx, cy, 0xFFE6E6EE, false);
        }
    }

    private final class ModeButton extends IconButton {
        ModeButton(OnPress onPress) {
            super(onPress);
        }

        @Override
        protected Icon getIcon() {
            return switch (menu.getFilterMode()) {
                case FAMILY_FILTER -> null;
                case IN_STOCK -> Icon.VIEW_MODE_STORED;
                case ALL -> Icon.VIEW_MODE_ALL;
            };
        }

        @Override
        protected Item getItemOverlay() {
            return menu.getFilterMode() == VariantRegistry.FilterMode.FAMILY_FILTER ? Items.HOPPER : null;
        }

        @Override
        public List<Component> getTooltipMessage() {
            List<Component> lines = new ArrayList<>();
            VariantRegistry.FilterMode mode = menu.getFilterMode();
            lines.add(Component.translatable("gui.foxstweaks.variant_crafter.mode.title"));
            lines.add(Component.translatable("gui.foxstweaks.variant_crafter.mode." + mode.name().toLowerCase())
                    .withStyle(ChatFormatting.GRAY));
            lines.add(Component.translatable("gui.foxstweaks.variant_crafter.mode.hint")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
    }

    private final class SourceToggleButton extends IconButton {
        private final VariantSource source;
        private final Item iconItem;

        SourceToggleButton(VariantSource source, Item iconItem) {
            super(btn -> menu.toggleSource(source.index));
            this.source = source;
            this.iconItem = iconItem;
        }

        @Override
        protected Icon getIcon() {
            return null;
        }

        @Override
        protected Item getItemOverlay() {
            return iconItem;
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partial) {
            super.renderWidget(guiGraphics, mouseX, mouseY, partial);
            if (!visible) return;
            boolean enabled = menu.isSourceEnabled(source);
            boolean loaded = source.isModLoaded();
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0, 0, 250);
            if (!enabled || !loaded) {
                guiGraphics.fill(getX() + 1, getY() + 1, getX() + width - 1, getY() + height - 1, 0x88000000);
                guiGraphics.fill(getX() + width - 5, getY() + height - 5, getX() + width - 2, getY() + height - 2, 0xFFE05252);
            } else {
                guiGraphics.fill(getX() + width - 5, getY() + height - 5, getX() + width - 2, getY() + height - 2, 0xFF4AD97A);
            }
            guiGraphics.pose().popPose();
        }

        @Override
        public List<Component> getTooltipMessage() {
            List<Component> lines = new ArrayList<>();
            boolean enabled = menu.isSourceEnabled(source);
            boolean loaded = source.isModLoaded();
            int count = menu.getSourceCount(source);

            lines.add(Component.literal(source.displayName + ": " + (enabled ? "ON" : "OFF"))
                    .withStyle(enabled ? ChatFormatting.GREEN : ChatFormatting.RED));
            if (!loaded) {
                lines.add(Component.literal("Mod not installed").withStyle(ChatFormatting.DARK_GRAY));
            } else {
                lines.add(Component.literal(count + " variant patterns available").withStyle(ChatFormatting.GRAY));
            }
            lines.add(Component.literal("Click to toggle").withStyle(ChatFormatting.DARK_GRAY));
            return lines;
        }
    }
}
