package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.anchor.AnchorNetwork;
import dev.watdafawx.foxstweaks.anchor.OpenAnchorGuiPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Clean configuration GUI for a placed Spawn Anchor dummy: toggle ON/OFF, adjust loaded chunk
 * radius with a live visual chunk-grid preview, or pick the anchor back up.
 */
public class SpawnAnchorScreen extends Screen {
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 228;
    private static final int BG_COLOR = 0xD80B0D12;
    private static final int BORDER_COLOR = 0x66FFFFFF;
    private static final int ACCENT_ON = 0xFF55FF55;
    private static final int ACCENT_OFF = 0xFFFF5555;
    private static final int GOLD = 0xFFFFAA00;
    private static final int MUTED = 0xFF9AA5B4;

    private final int entityId;
    private final int maxRadius;
    private final int blockX;
    private final int blockY;
    private final int blockZ;
    private final String dimension;

    private boolean enabled;
    private int radius;

    private Button toggleBtn;
    private Button minusBtn;
    private Button plusBtn;

    private int left;
    private int top;

    public SpawnAnchorScreen(OpenAnchorGuiPayload p) {
        super(Component.translatable("screen.foxstweaks.spawn_anchor.title"));
        this.entityId = p.entityId();
        this.enabled = p.enabled();
        this.radius = p.radius();
        this.maxRadius = Math.max(1, Math.min(8, p.maxRadius()));
        this.blockX = p.blockX();
        this.blockY = p.blockY();
        this.blockZ = p.blockZ();
        this.dimension = p.dimension();
    }

    public static void open(OpenAnchorGuiPayload payload) {
        Minecraft.getInstance().setScreen(new SpawnAnchorScreen(payload));
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - PANEL_H) / 2;

        int btnY = top + 156;
        toggleBtn = addRenderableWidget(Button.builder(toggleLabel(), b -> {
            enabled = !enabled;
            refreshButtons();
            AnchorNetwork.sendUpdate(entityId, enabled, radius, false);
        }).bounds(left + 14, btnY, 94, 20).build());

        minusBtn = addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            if (radius > 1) {
                radius--;
                refreshButtons();
                AnchorNetwork.sendUpdate(entityId, enabled, radius, false);
            }
        }).bounds(left + 116, btnY, 24, 20).build());

        plusBtn = addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            if (radius < maxRadius) {
                radius++;
                refreshButtons();
                AnchorNetwork.sendUpdate(entityId, enabled, radius, false);
            }
        }).bounds(left + PANEL_W - 14 - 24, btnY, 24, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("screen.foxstweaks.spawn_anchor.pickup"), b -> {
            AnchorNetwork.sendUpdate(entityId, false, radius, true);
            onClose();
        }).bounds(left + 14, top + 186, 114, 20).build());

        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + PANEL_W - 14 - 80, top + 186, 80, 20).build());

        refreshButtons();
    }

    private void refreshButtons() {
        if (toggleBtn != null)
            toggleBtn.setMessage(toggleLabel());
        if (minusBtn != null)
            minusBtn.active = radius > 1;
        if (plusBtn != null)
            plusBtn.active = radius < maxRadius;
    }

    private Component toggleLabel() {
        return Component.literal(enabled ? "Status: ON" : "Status: OFF")
                .withStyle(st -> st.withColor((enabled ? ACCENT_ON : ACCENT_OFF) & 0xFFFFFF));
    }

    @Override
    public void renderBackground(GuiGraphics gg, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(gg, mouseX, mouseY, partialTick);

        int accent = enabled ? ACCENT_ON : ACCENT_OFF;
        gg.fill(left, top, left + PANEL_W, top + PANEL_H, BG_COLOR);
        gg.renderOutline(left, top, PANEL_W, PANEL_H, BORDER_COLOR);
        gg.fill(left + 1, top + 1, left + PANEL_W - 1, top + 3, accent);

        // Header
        gg.drawCenteredString(font, title, left + PANEL_W / 2, top + 10, GOLD);
        String loc = blockX + ", " + blockY + ", " + blockZ + "  (" + shortDim(dimension) + ")";
        gg.drawCenteredString(font, loc, left + PANEL_W / 2, top + 22, MUTED);
        gg.drawCenteredString(font, Component.translatable("screen.foxstweaks.spawn_anchor.subtitle"),
                left + PANEL_W / 2, top + 34, 0xFFB8C4D4);

        // Visual chunk grid (shows (2*gridR+1)^2 cells; active cells in green, center in gold)
        int gridR = Math.max(4, maxRadius);
        int gridSide = gridR * 2 + 1;
        int cellSize = gridSide <= 9 ? 9 : 7;
        int gap = 1;
        int gridPx = gridSide * (cellSize + gap) - gap;
        int gx = left + (PANEL_W - gridPx) / 2;
        int gy = top + 48;

        gg.fill(gx - 3, gy - 3, gx + gridPx + 3, gy + gridPx + 3, 0x66000000);
        gg.renderOutline(gx - 3, gy - 3, gridPx + 6, gridPx + 6, 0x33FFFFFF);

        for (int dz = -gridR; dz <= gridR; dz++) {
            for (int dx = -gridR; dx <= gridR; dx++) {
                int cx = gx + (dx + gridR) * (cellSize + gap);
                int cy = gy + (dz + gridR) * (cellSize + gap);
                int cheb = Math.max(Math.abs(dx), Math.abs(dz));
                int col;
                if (dx == 0 && dz == 0) {
                    col = enabled ? GOLD : 0xFF886622;
                } else if (cheb <= radius) {
                    col = enabled ? 0xCC33CC55 : 0x55AA4444;
                } else {
                    col = 0x28FFFFFF;
                }
                gg.fill(cx, cy, cx + cellSize, cy + cellSize, col);
            }
        }

        // Chunk count summary under grid
        int side = radius * 2 + 1;
        int totalChunks = side * side;
        int infoY = gy + gridPx + 7;
        String summary = enabled
                ? "Loading " + side + "x" + side + " (" + totalChunks + " chunks)"
                : "Paused (" + side + "x" + side + " / " + totalChunks + " chunks)";
        gg.drawCenteredString(font, summary, left + PANEL_W / 2, infoY, enabled ? 0xFFE8EDF5 : MUTED);

        // Radius label centered between - and + buttons
        String radStr = "R=" + radius;
        int midX = left + 116 + 24 + ((PANEL_W - 14 - 24) - (116 + 24)) / 2;
        gg.drawCenteredString(font, radStr, midX, top + 162, 0xFFE8EDF5);

        // Footer hint
        gg.drawCenteredString(font, "Sneak + Left-click dummy to pick up anytime", left + PANEL_W / 2, top + 212, 0xFF778090);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static String shortDim(String dim) {
        int idx = dim.indexOf(':');
        return idx >= 0 ? dim.substring(idx + 1) : dim;
    }
}
