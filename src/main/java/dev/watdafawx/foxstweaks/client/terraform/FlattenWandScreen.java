package dev.watdafawx.foxstweaks.client.terraform;

import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.terraform.FlattenWandConfig;
import dev.watdafawx.foxstweaks.terraform.FlattenWandItem;
import dev.watdafawx.foxstweaks.terraform.TerraformNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Settings & confirmation screen for the Terrain Flattener Wand:
 * configurable gradual flatness, smoothness, edge feathering, target height, tree mode, and undo.
 */
public final class FlattenWandScreen extends Screen {
    private final InteractionHand hand;
    private FlattenWandConfig cfg;

    private int panelX, panelY, panelW = 248, panelH = 226;

    public FlattenWandScreen(InteractionHand hand, ItemStack stack) {
        super(Component.translatable("screen.foxstweaks.terrain_flattener.title"));
        this.hand = hand;
        this.cfg = FlattenWandItem.getConfig(stack);
    }

    @Override
    protected void init() {
        panelX = 12;
        panelY = Math.max(8, (height - panelH) / 2);
        int x = panelX + 12;
        int w = panelW - 24;
        int y = panelY + 20;
        int h = 18;
        int step = 20;

        // Mode
        addRenderableWidget(Button.builder(Component.literal("Mode: " + cfg.mode().label), b -> {
            cfg = cfg.withMode(cfg.mode().next());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(x, y, w, h).build());
        y += step;

        // Radius (Brush mode)
        int maxR = Math.max(8, ServerConfig.flattenWandMaxRadius());
        if (cfg.mode() == FlattenWandConfig.Mode.BRUSH) {
            addRenderableWidget(new PercentOrIntSlider(x, y, w, h, "Radius", 4, maxR, Mth.clamp(cfg.radius(), 4, maxR), false, v -> {
                cfg = cfg.withRadius((int) Math.round(v));
                syncConfig(false);
            }));
            y += step;
        }

        // Flatness (0..100%)
        addRenderableWidget(new PercentOrIntSlider(x, y, w, h, "Flatness", 0, 100, Math.round(cfg.flatness() * 100), true, v -> {
            cfg = cfg.withFlatness((float) (v / 100.0));
            syncConfig(false);
        }));
        y += step;

        // Smoothness (0..100%)
        addRenderableWidget(new PercentOrIntSlider(x, y, w, h, "Smoothness", 0, 100, Math.round(cfg.smoothness() * 100), true, v -> {
            cfg = cfg.withSmoothness((float) (v / 100.0));
            syncConfig(false);
        }));
        y += step;

        // Edge Feathering (0..100%)
        addRenderableWidget(new PercentOrIntSlider(x, y, w, h, "Edge Feather", 0, 100, Math.round(cfg.feather() * 100), true, v -> {
            cfg = cfg.withFeather((float) (v / 100.0));
            syncConfig(false);
        }));
        y += step;

        // Target Y Mode
        String targetLabel = cfg.targetYMode() == FlattenWandConfig.TargetYMode.CUSTOM
                ? "Target Y: Custom (" + cfg.customY() + ")"
                : "Target Y: " + cfg.targetYMode().label;
        if (cfg.targetYMode() == FlattenWandConfig.TargetYMode.CUSTOM) {
            addRenderableWidget(Button.builder(Component.literal(targetLabel), b -> {
                cfg = cfg.withTargetYMode(cfg.targetYMode().next());
                syncConfig(false);
                rebuildWidgets();
            }).bounds(x, y, w - 44, h).build());
            addRenderableWidget(Button.builder(Component.literal("-"), b -> {
                cfg = cfg.withCustomY(Mth.clamp(cfg.customY() - (hasShiftDown() ? 5 : 1), -60, 315));
                syncConfig(false);
                rebuildWidgets();
            }).bounds(x + w - 40, y, 18, h).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                cfg = cfg.withCustomY(Mth.clamp(cfg.customY() + (hasShiftDown() ? 5 : 1), -60, 315));
                syncConfig(false);
                rebuildWidgets();
            }).bounds(x + w - 18, y, 18, h).build());
        } else {
            addRenderableWidget(Button.builder(Component.literal(targetLabel), b -> {
                cfg = cfg.withTargetYMode(cfg.targetYMode().next());
                syncConfig(false);
                rebuildWidgets();
            }).bounds(x, y, w, h).build());
        }
        y += step;

        // Tree & Water handling options (side-by-side compact row)
        int halfW = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Trees: " + shortTreeLabel(cfg.treeMode())), b -> {
            cfg = cfg.withTreeMode(cfg.treeMode().next());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(x, y, halfW, h).build());

        addRenderableWidget(Button.builder(Component.literal("Water: " + cfg.waterMode().label), b -> {
            cfg = cfg.withWaterMode(cfg.waterMode().next());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(x + halfW + 4, y, halfW, h).build());
        y += step;

        // Natural blocks only & Protect block entities (side-by-side compact row)
        addRenderableWidget(Button.builder(Component.literal("Natural: " + (cfg.naturalOnly() ? "ON" : "OFF")), b -> {
            cfg = cfg.withNaturalOnly(!cfg.naturalOnly());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(x, y, halfW, h).build());

        addRenderableWidget(Button.builder(Component.literal("Protect BEs: " + (cfg.protectBlockEntities() ? "ON" : "OFF")), b -> {
            cfg = cfg.withProtectBlockEntities(!cfg.protectBlockEntities());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(x + halfW + 4, y, halfW, h).build());

        // Bottom actions
        int by = panelY + panelH - 22;
        Button applyBtn = Button.builder(Component.literal("Confirm & Flatten"), b -> {
            syncConfig(true);
            onClose();
        }).bounds(x, by - 20, w, h).build();
        applyBtn.active = cfg.hasSelection();
        addRenderableWidget(applyBtn);

        int bw = (w - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Clear Sel"), b -> {
            cfg = cfg.cleared();
            syncConfig(false);
            TerraformPreviewRenderer.clearCache();
            MapPreviewBridge.clear();
            rebuildWidgets();
        }).bounds(x, by, bw, h).build());

        addRenderableWidget(Button.builder(Component.literal("Undo Last"), b -> {
            PacketDistributor.sendToServer(new TerraformNetwork.TerraformUndoPayload(false));
            onClose();
        }).bounds(x + bw + 4, by, bw, h).build());
    }

    private static String shortTreeLabel(FlattenWandConfig.TreeMode m) {
        return switch (m) {
            case REMOVE -> "Remove";
            case KEEP -> "Keep";
            case REPLANT -> "Replant";
        };
    }

    private void syncConfig(boolean applyNow) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack held = mc.player.getItemInHand(hand);
            if (held.getItem() instanceof FlattenWandItem) {
                FlattenWandItem.setConfig(held, cfg);
            }
        }
        PacketDistributor.sendToServer(new TerraformNetwork.UpdateFlattenWandPayload(hand == InteractionHand.MAIN_HAND, cfg, applyNow));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No fullscreen dim so the live 3D terrain wireframe stays fully visible while dragging sliders
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xD8161920);
        g.renderOutline(panelX, panelY, panelW, panelH, 0xFF3B4354);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, panelX + 12, panelY + 7, 0xFFEAECEF);
        if (!cfg.protectBlockEntities()) {
            g.drawCenteredString(font, "Warning: Chests/machines lose inventory on Undo!", panelX + panelW / 2, panelY + panelH + 4, 0xFFFF6655);
        } else if (!cfg.naturalOnly()) {
            g.drawCenteredString(font, "Note: Player blocks may be overwritten (BEs protected)", panelX + panelW / 2, panelY + panelH + 4, 0xFFFFCC55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class PercentOrIntSlider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final boolean percent;
        private final java.util.function.DoubleConsumer onChange;

        PercentOrIntSlider(int x, int y, int w, int h, String label, int min, int max, int cur, boolean percent, java.util.function.DoubleConsumer onChange) {
            super(x, y, w, h, Component.empty(), (double) (cur - min) / Math.max(1, max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.percent = percent;
            this.onChange = onChange;
            updateMessage();
        }

        private int currentVal() {
            return Mth.clamp((int) Math.round(min + value * (max - min)), min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + currentVal() + (percent ? "%" : "")));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentVal());
        }
    }
}
