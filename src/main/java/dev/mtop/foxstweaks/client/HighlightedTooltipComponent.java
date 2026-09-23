package dev.mtop.foxstweaks.client;

import org.joml.Matrix4f;

import dev.mtop.foxstweaks.FoxsTweaks;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;

/**
 * Wraps any other tooltip component with the same "▶ " a hovered text line gets in the affix
 * overlay. Needed because some affix lines are not text at all - Apotheosis swaps Stoneforming's
 * line for an icon-drawing component - so restyling a string can never mark them.
 */
public record HighlightedTooltipComponent(TooltipComponent inner) implements TooltipComponent {
    public static final Component PREFIX = Component.literal("▶ ").withStyle(ChatFormatting.YELLOW, ChatFormatting.BOLD);

    private record Renderer(ClientTooltipComponent inner) implements ClientTooltipComponent {
        @Override
        public int getHeight() {
            return inner.getHeight();
        }

        @Override
        public int getWidth(Font font) {
            return font.width(PREFIX) + inner.getWidth(font);
        }

        @Override
        public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource bufferSource) {
            font.drawInBatch(PREFIX, x, y, -1, true, matrix, bufferSource, Font.DisplayMode.NORMAL, 0, 15728880);
            inner.renderText(font, x + font.width(PREFIX), y, matrix, bufferSource);
        }

        @Override
        public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
            inner.renderImage(font, x + font.width(PREFIX), y, graphics);
        }
    }

    @SuppressWarnings("removal")
    @EventBusSubscriber(modid = FoxsTweaks.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void onRegister(RegisterClientTooltipComponentFactoriesEvent event) {
            event.register(HighlightedTooltipComponent.class, c -> new Renderer(ClientTooltipComponent.create(c.inner())));
        }
    }
}
