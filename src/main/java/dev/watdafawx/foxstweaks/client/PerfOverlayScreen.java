package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.perf.PerfOverlayRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Drag-to-place screen for the Tab performance cards, opened by the "Move Performance Overlay" key.
 *
 * <p>Shows a stand-in the size of the card block: drag it anywhere and releasing snaps the placement to the
 * nearest edge/corner preset and stores the remainder as a pixel offset, so the config stays readable instead
 * of holding absolute coordinates. The placement button cycles all seven presets, including {@code flank}
 * (which ignores offsets and follows the player list), and Done saves to the config file.
 */
public class PerfOverlayScreen extends Screen {
    private static final List<String> PLACEMENTS = List.of(
            "flank", "topLeft", "topCenter", "topRight", "bottomLeft", "bottomCenter", "bottomRight");

    private static final int CARD_H = PerfOverlayRenderer.clientCardHeight();
    private static final int GAP = PerfOverlayRenderer.cardGap();

    private final Screen parent;
    private String placement;
    private int offsetX;
    private int offsetY;

    private int blockX;
    private int blockY;
    private boolean dragging;
    private int dragGrabX;
    private int dragGrabY;

    private Button placementBtn;

    public PerfOverlayScreen(Screen parent) {
        super(Component.translatable("screen.foxstweaks.perf_placement.title"));
        this.parent = parent;
        this.placement = Config.perfOverlayPlacement();
        this.offsetX = Config.perfOverlayOffsetX();
        this.offsetY = Config.perfOverlayOffsetY();
    }

    private int blockW() {
        return PerfOverlayRenderer.cardWidth();
    }

    private int blockH() {
        return CARD_H * 2 + GAP;
    }

    @Override
    protected void init() {
        snapBlockToConfig();

        placementBtn = addRenderableWidget(Button.builder(placementLabel(), b -> {
            int index = PLACEMENTS.indexOf(placement);
            placement = PLACEMENTS.get((index + 1) % PLACEMENTS.size());
            offsetX = 0;
            offsetY = 0;
            snapBlockToConfig();
            placementBtn.setMessage(placementLabel());
        }).bounds(width / 2 - 150, height - 54, 200, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.foxstweaks.perf_placement.reset"), b -> {
            offsetX = 0;
            offsetY = 0;
            snapBlockToConfig();
        }).bounds(width / 2 + 56, height - 54, 94, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 150 + 202, height - 30, 148, 20).build());
    }

    /** Where the block sits for the current placement + offsets. */
    private void snapBlockToConfig() {
        int totalH = blockH();
        if ("flank".equals(placement)) {
            // Flank has no fixed anchor; preview it centred so the drag starts somewhere visible.
            blockX = (width - blockW()) / 2;
            blockY = Math.max(4, Math.min(height / 2 - totalH / 2, height - totalH - 4));
            return;
        }
        blockX = PerfOverlayRenderer.clampX(PerfOverlayRenderer.anchorX(placement, width) + offsetX, width);
        blockY = Math.max(2, Math.min(PerfOverlayRenderer.anchorY(placement, height, totalH) + offsetY,
                height - totalH - 40));
    }

    private Component placementLabel() {
        return Component.translatable("screen.foxstweaks.perf_placement.placement",
                Component.translatable("screen.foxstweaks.perf_placement." + placement));
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);

        int w = blockW();
        int h = CARD_H;
        boolean overBlock = isOverBlock(mouseX, mouseY);
        int border = dragging ? 0xFFFFAA00 : overBlock ? 0xAAFFFFFF : 0x55FFFFFF;

        // Stand-ins for the two cards: the real ones need a server snapshot, which only exists while the
        // overlay is subscribed, and this screen is about where they go, not what they say.
        gg.fill(blockX, blockY, blockX + w, blockY + h, 0xCC0B0D12);
        gg.renderOutline(blockX, blockY, w, h, border);
        gg.drawCenteredString(font, Component.translatable("screen.foxstweaks.perf_placement.server"),
                blockX + w / 2, blockY + h / 2 - 4, 0xFFFFAA00);

        int cy = blockY + h + GAP;
        gg.fill(blockX, cy, blockX + w, cy + h, 0xCC0B0D12);
        gg.renderOutline(blockX, cy, w, h, border);
        gg.drawCenteredString(font, Component.translatable("screen.foxstweaks.perf_placement.client"),
                blockX + w / 2, cy + h / 2 - 4, 0xFF55DDFF);

        gg.drawCenteredString(font, Component.translatable("screen.foxstweaks.perf_placement.hint"),
                width / 2, Math.max(8, blockY - 14), 0xFFBFC8D6);

        if ("flank".equals(placement)) {
            gg.drawCenteredString(font, Component.translatable("screen.foxstweaks.perf_placement.flank_note"),
                    width / 2, height - 74, 0xFFFFAA00);
        }
    }

    private boolean isOverBlock(double mouseX, double mouseY) {
        return mouseX >= blockX && mouseX <= blockX + blockW()
                && mouseY >= blockY && mouseY <= blockY + blockH();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isOverBlock(mouseX, mouseY)) {
            dragging = true;
            dragGrabX = (int) mouseX - blockX;
            dragGrabY = (int) mouseY - blockY;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging) {
            blockX = (int) Math.max(2, Math.min(mouseX - dragGrabX, width - blockW() - 2));
            blockY = (int) Math.max(2, Math.min(mouseY - dragGrabY, height - blockH() - 40));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging) {
            dragging = false;
            commitFromBlock();
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Arrow keys nudge here: in a screen they cannot move the player, so they are free for fine tuning.
        int step = Screen.hasShiftDown() ? 1 : 5;
        int dx = 0;
        int dy = 0;
        if (keyCode == GLFW.GLFW_KEY_LEFT) dx = -step;
        if (keyCode == GLFW.GLFW_KEY_RIGHT) dx = step;
        if (keyCode == GLFW.GLFW_KEY_UP) dy = -step;
        if (keyCode == GLFW.GLFW_KEY_DOWN) dy = step;

        if (dx != 0 || dy != 0) {
            blockX = (int) Math.max(2, Math.min(blockX + dx, width - blockW() - 2));
            blockY = (int) Math.max(2, Math.min(blockY + dy, height - blockH() - 40));
            commitFromBlock();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Turns the block's position into the nearest preset plus the leftover as a pixel offset. */
    private void commitFromBlock() {
        int centreX = blockX + blockW() / 2;
        int centreY = blockY + blockH() / 2;

        String vertical = centreY < height / 2 ? "top" : "bottom";
        String horizontal;
        if (centreX < width / 3)
            horizontal = "Left";
        else if (centreX > width * 2 / 3)
            horizontal = "Right";
        else
            horizontal = "Center";

        placement = vertical + horizontal;
        offsetX = blockX - PerfOverlayRenderer.anchorX(placement, width);
        offsetY = blockY - PerfOverlayRenderer.anchorY(placement, height, blockH());
        if (placementBtn != null)
            placementBtn.setMessage(placementLabel());
    }

    @Override
    public void onClose() {
        Config.setPerfOverlayPosition(placement, offsetX, offsetY);
        minecraft.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
