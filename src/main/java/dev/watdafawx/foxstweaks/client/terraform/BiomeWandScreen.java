package dev.watdafawx.foxstweaks.client.terraform;

import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.terraform.BiomeWandConfig;
import dev.watdafawx.foxstweaks.terraform.BiomeWandItem;
import dev.watdafawx.foxstweaks.terraform.TerraformNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Config + biome picker + confirm screen for the Biome Changer Wand.
 * Shows live grass & sky color swatches next to every registered biome.
 */
public final class BiomeWandScreen extends Screen {
    private record BiomeEntry(ResourceLocation id, String display, int grassRgb, int skyRgb) {
    }

    private final InteractionHand hand;
    private BiomeWandConfig cfg;
    private final List<BiomeEntry> allBiomes = new ArrayList<>();
    private final List<BiomeEntry> filtered = new ArrayList<>();

    private EditBox searchBox;
    private int scrollOffset = 0;

    private int panelX, panelY, panelW = 360, panelH = 232;
    private int listX, listY, listW = 166, listH = 160;
    private static final int ROW_H = 16;

    public BiomeWandScreen(InteractionHand hand, ItemStack stack) {
        super(Component.translatable("screen.foxstweaks.biome_wand.title"));
        this.hand = hand;
        this.cfg = BiomeWandItem.getConfig(stack);
    }

    @Override
    protected void init() {
        panelX = 12;
        panelY = Math.max(8, (height - panelH) / 2);
        listX = panelX + 10;
        listY = panelY + 44;

        if (allBiomes.isEmpty()) {
            loadBiomes();
        }

        String prevSearch = searchBox != null ? searchBox.getValue() : "";
        searchBox = new EditBox(font, listX, panelY + 22, listW, 18, Component.literal("Search biomes"));
        searchBox.setHint(Component.literal("Search biomes..."));
        searchBox.setValue(prevSearch);
        searchBox.setResponder(s -> {
            scrollOffset = 0;
            refilter(s);
        });
        addRenderableWidget(searchBox);
        refilter(prevSearch);

        int rx = panelX + 186;
        int rw = 164;
        int ry = panelY + 22;

        // Mode cycle
        addRenderableWidget(Button.builder(Component.literal("Mode: " + cfg.mode().label), b -> {
            cfg = cfg.withMode(cfg.mode().next());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(rx, ry, rw, 20).build());
        ry += 24;

        // Radius slider (Brush or Flood-Fill)
        int maxR = Math.max(8, ServerConfig.biomeWandMaxRadius());
        if (cfg.mode() == BiomeWandConfig.Mode.BRUSH || cfg.mode() == BiomeWandConfig.Mode.FLOOD_FILL) {
            boolean isBrush = cfg.mode() == BiomeWandConfig.Mode.BRUSH;
            int curR = Mth.clamp(isBrush ? cfg.brushRadius() : cfg.floodRadius(), 4, maxR);
            addRenderableWidget(new IntSlider(rx, ry, rw, 20, isBrush ? "Brush Radius" : "Flood Radius", 4, maxR, curR, val -> {
                cfg = isBrush ? cfg.withBrushRadius(val) : cfg.withFloodRadius(val);
                syncConfig(false);
            }));
            ry += 24;
        }

        // Height mode: Surface (Keep Caves) / Full Y Column / Custom Y-Slice
        String heightLabel = switch (cfg.heightMode()) {
            case SURFACE -> "Height: Surface (Keep Caves)";
            case FULL -> "Height: Full Y Column";
            case SLICE -> "Height: Y-Slice (" + cfg.sliceMinY() + ".." + cfg.sliceMaxY() + ")";
        };
        addRenderableWidget(Button.builder(Component.literal(heightLabel), b -> {
            cfg = cfg.withHeightMode(cfg.heightMode().next());
            syncConfig(false);
            rebuildWidgets();
        }).bounds(rx, ry, rw, 20).build());
        ry += 24;

        if (cfg.heightMode() == BiomeWandConfig.HeightMode.SLICE) {
            int hw = (rw - 4) / 2;
            addRenderableWidget(Button.builder(Component.literal("Min Y: " + cfg.sliceMinY()), b -> {
                int next = hasShiftDown() ? cfg.sliceMinY() - 8 : cfg.sliceMinY() + 8;
                cfg = cfg.withSliceY(Mth.clamp(next, -64, 319), cfg.sliceMaxY());
                syncConfig(false);
                rebuildWidgets();
            }).bounds(rx, ry, hw, 18).build());
            addRenderableWidget(Button.builder(Component.literal("Max Y: " + cfg.sliceMaxY()), b -> {
                int next = hasShiftDown() ? cfg.sliceMaxY() - 8 : cfg.sliceMaxY() + 8;
                cfg = cfg.withSliceY(cfg.sliceMinY(), Mth.clamp(next, -64, 319));
                syncConfig(false);
                rebuildWidgets();
            }).bounds(rx + hw + 4, ry, hw, 18).build());
            ry += 22;
        }

        // 1x1 Exact Edges toggle
        addRenderableWidget(Button.builder(
                Component.literal("1x1 Exact Edges: " + (cfg.exactEdges() ? "ON" : "OFF")),
                b -> {
                    cfg = cfg.withExactEdges(!cfg.exactEdges());
                    syncConfig(false);
                    rebuildWidgets();
                }).bounds(rx, ry, rw, 20).build());
        ry += 24;

        // Clear Snow/Ice toggle
        addRenderableWidget(Button.builder(
                Component.literal("Clear Snow & Ice: " + (cfg.clearSnow() ? "ON" : "OFF")),
                b -> {
                    cfg = cfg.withClearSnow(!cfg.clearSnow());
                    syncConfig(false);
                    rebuildWidgets();
                }).bounds(rx, ry, rw, 20).build());

        // Bottom action bar
        int by = panelY + panelH - 28;
        Button applyBtn = Button.builder(Component.literal("Confirm & Apply"), b -> {
            syncConfig(true);
            onClose();
        }).bounds(rx, by - 24, rw, 20).build();
        applyBtn.active = cfg.hasSelection();
        addRenderableWidget(applyBtn);

        int bw = (rw - 4) / 2;
        addRenderableWidget(Button.builder(Component.literal("Clear Sel"), b -> {
            cfg = cfg.cleared();
            syncConfig(false);
            TerraformPreviewRenderer.clearCache();
            MapPreviewBridge.clear();
            rebuildWidgets();
        }).bounds(rx, by, bw, 20).build());

        addRenderableWidget(Button.builder(Component.literal("Undo Last"), b -> {
            PacketDistributor.sendToServer(new TerraformNetwork.TerraformUndoPayload(true));
            onClose();
        }).bounds(rx + bw + 4, by, bw, 20).build());
    }

    private void loadBiomes() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        Registry<Biome> reg = mc.level.registryAccess().registryOrThrow(Registries.BIOME);
        for (Holder.Reference<Biome> ref : reg.holders().toList()) {
            ResourceLocation id = ref.key().location();
            Biome b = ref.value();
            int grass = b.getGrassColor(0.0, 0.0) & 0xFFFFFF;
            int sky = b.getSkyColor() & 0xFFFFFF;
            String display = id.getNamespace().equals("minecraft") ? id.getPath() : id.toString();
            allBiomes.add(new BiomeEntry(id, display, grass, sky));
        }
        allBiomes.sort(Comparator.comparing(BiomeEntry::display));
    }

    private void refilter(String query) {
        filtered.clear();
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        for (BiomeEntry e : allBiomes) {
            if (q.isEmpty() || e.display.toLowerCase(Locale.ROOT).contains(q) || e.id.toString().contains(q)) {
                filtered.add(e);
            }
        }
    }

    private void syncConfig(boolean applyNow) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            ItemStack held = mc.player.getItemInHand(hand);
            if (held.getItem() instanceof BiomeWandItem) {
                BiomeWandItem.setConfig(held, cfg);
            }
        }
        PacketDistributor.sendToServer(new TerraformNetwork.UpdateBiomeWandPayload(hand == InteractionHand.MAIN_HAND, cfg, applyNow));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // No fullscreen dim so the live 3D biome preview stays fully visible while adjusting settings
        g.fill(panelX, panelY, panelX + panelW, panelY + panelH, 0xD8161920);
        g.renderOutline(panelX, panelY, panelW, panelH, 0xFF3B4354);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawString(font, title, panelX + 10, panelY + 8, 0xFFEAECEF);

        // Biome list box
        g.fill(listX, listY, listX + listW, listY + listH, 0xCC0D1016);
        g.renderOutline(listX, listY, listW, listH, 0xFF2D3442);

        int visible = listH / ROW_H;
        int maxScroll = Math.max(0, filtered.size() - visible);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);

        for (int i = 0; i < visible && (scrollOffset + i) < filtered.size(); i++) {
            BiomeEntry entry = filtered.get(scrollOffset + i);
            int ry = listY + i * ROW_H;
            boolean selected = entry.id.equals(cfg.targetBiome());
            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= ry && mouseY < ry + ROW_H;

            if (selected) {
                g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0xFF2B4D36);
            } else if (hovered) {
                g.fill(listX + 1, ry, listX + listW - 1, ry + ROW_H, 0x6640485A);
            }

            // Grass swatch + Sky swatch
            g.fill(listX + 4, ry + 3, listX + 12, ry + 13, 0xFF000000 | entry.grassRgb);
            g.fill(listX + 14, ry + 3, listX + 22, ry + 13, 0xFF000000 | entry.skyRgb);

            String label = font.plainSubstrByWidth(entry.display, listW - 28);
            g.drawString(font, label, listX + 26, ry + 4, selected ? 0xFFFFFFFF : 0xFFD0D6E0);
        }

        // Legend under list
        g.drawString(font, "Swatches: Grass | Sky", listX, listY + listH + 6, 0xFF8892A4);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            int row = (int) ((mouseY - listY) / ROW_H);
            int idx = scrollOffset + row;
            if (idx >= 0 && idx < filtered.size()) {
                cfg = cfg.withTargetBiome(filtered.get(idx).id);
                syncConfig(false);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= listX && mouseX < listX + listW && mouseY >= listY && mouseY < listY + listH) {
            scrollOffset -= (int) Math.signum(scrollY) * 2;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static final class IntSlider extends AbstractSliderButton {
        private final String label;
        private final int min, max;
        private final java.util.function.IntConsumer onChange;

        IntSlider(int x, int y, int w, int h, String label, int min, int max, int cur, java.util.function.IntConsumer onChange) {
            super(x, y, w, h, Component.empty(), (double) (cur - min) / Math.max(1, max - min));
            this.label = label;
            this.min = min;
            this.max = max;
            this.onChange = onChange;
            updateMessage();
        }

        private int currentVal() {
            return Mth.clamp((int) Math.round(min + value * (max - min)), min, max);
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(label + ": " + currentVal()));
        }

        @Override
        protected void applyValue() {
            onChange.accept(currentVal());
        }
    }
}
