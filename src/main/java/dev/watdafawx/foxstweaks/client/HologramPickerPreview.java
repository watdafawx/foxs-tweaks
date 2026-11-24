package dev.watdafawx.foxstweaks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import org.joml.Quaternionf;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Lightweight, lazily-cached visual previews for the hologram Content Picker:
 *
 * <ul>
 * <li><b>Particles</b>: resolves {@code assets/<ns>/particles/<path>.json} once per visible entry
 * and blits its actual sprite frames (animated if multi-frame). Zero extra world/engine cost.
 * <li><b>Entities</b>: draws scissored, bounding-box-scaled 3D entities reusing
 * {@link HologramEntities}' cache and crash blacklist.
 * <li><b>Hover zoom card</b>: floating preview card docked beside the left panel for blocks,
 * entities, and particles.
 * </ul>
 */
final class HologramPickerPreview {
    private static final int CARD_BG = 0xF00D1117;
    private static final int CARD_BORDER = 0x774CC9F0;
    private static final int BOX_BG = 0xFF161C24;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;

    private static final Map<String, List<ResourceLocation>> PARTICLE_FRAMES = new HashMap<>();

    static List<ResourceLocation> particleFrames(String particleId) {
        return PARTICLE_FRAMES.computeIfAbsent(particleId, HologramPickerPreview::loadParticleFrames);
    }

    private static List<ResourceLocation> loadParticleFrames(String particleId) {
        ResourceLocation key = ResourceLocation.tryParse(particleId);
        if (key == null)
            return List.of();

        ResourceLocation jsonLoc = ResourceLocation.fromNamespaceAndPath(
                key.getNamespace(), "particles/" + key.getPath() + ".json");
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(jsonLoc);
            if (res.isEmpty())
                return List.of();
            try (Reader reader = res.get().openAsReader()) {
                JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
                JsonArray textures = obj.getAsJsonArray("textures");
                if (textures == null || textures.isEmpty())
                    return List.of();
                List<ResourceLocation> frames = new ArrayList<>(textures.size());
                for (JsonElement el : textures) {
                    if (!el.isJsonPrimitive())
                        continue;
                    ResourceLocation texKey = ResourceLocation.tryParse(el.getAsString());
                    if (texKey == null)
                        continue;
                    frames.add(ResourceLocation.fromNamespaceAndPath(
                            texKey.getNamespace(), "textures/particle/" + texKey.getPath() + ".png"));
                }
                return List.copyOf(frames);
            }
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    /** Draws a particle's actual texture (cycling frames if animated) into an {@code size x size} square. */
    static void drawParticleIcon(GuiGraphics graphics, String particleId, int x, int y, int size, double seconds) {
        List<ResourceLocation> frames = particleFrames(particleId);
        if (frames.isEmpty()) {
            // Subtle fallback spark glyph when a modded particle has no JSON texture
            int cx = x + size / 2;
            int cy = y + size / 2;
            graphics.fill(cx - 2, cy - 2, cx + 2, cy + 2, 0xFF4CC9F0);
            return;
        }
        int frameIdx = frames.size() == 1 ? 0 : (int) ((seconds * 6.0) % frames.size());
        ResourceLocation tex = frames.get(Math.floorMod(frameIdx, frames.size()));
        RenderSystem.enableBlend();
        graphics.blit(tex, x, y, 0.0F, 0.0F, size, size, size, size);
    }

    /**
     * Draws a live 3D entity cleanly scissored and auto-scaled to fit inside {@code (x, y, size, size)}.
     * Returns false if the entity cannot be drawn (caller can fall back to spawn egg).
     */
    static boolean drawEntityIcon(GuiGraphics graphics, String entityId, int x, int y, int size,
                                  float yawDegrees, double seconds) {
        Entity entity = HologramEntities.getById(entityId, 0.0, 0.0, 0.0, 0.0F, (float) seconds);
        if (entity == null)
            return false;

        float bbW = Math.max(0.35F, entity.getBbWidth());
        float bbH = Math.max(0.35F, entity.getBbHeight());
        float maxDim = Math.max(bbW * 1.15F, bbH);
        float scale = (size * 0.72F) / maxDim;

        graphics.enableScissor(x, y, x + size, y + size);
        try {
            var pose = graphics.pose();
            pose.pushPose();
            pose.translate(x + size * 0.5F, y + size * 0.5F + (bbH * scale) * 0.48F, 60.0F);
            pose.scale(scale, -scale, scale);

            Quaternionf rot = new Quaternionf()
                    .rotateX((float) Math.toRadians(14.0))
                    .rotateY((float) Math.toRadians(yawDegrees));
            pose.mulPose(rot);

            Lighting.setupForEntityInInventory();
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.setRenderShadow(false);
            RenderSystem.runAsFancy(() -> dispatcher.render(
                    entity, 0.0, 0.0, 0.0, 0.0F, 1.0F, pose, graphics.bufferSource(), 0xF000F0));
            graphics.flush();
            dispatcher.setRenderShadow(true);
            Lighting.setupFor3DItems();
            pose.popPose();
            return true;
        } catch (Throwable ignored) {
            HologramEntities.markFailed(entityId);
            try {
                graphics.pose().popPose();
            } catch (Throwable ignored2) {
            }
            return false;
        } finally {
            graphics.disableScissor();
        }
    }

    /** Floating hover zoom card for a block cell, docked beside the left panel. */
    static void renderBlockCard(GuiGraphics graphics, Font font, Block block, int cardX, int cardY, int screenH) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String name = block.getName().getString();
        String id = key.toString();

        int boxSize = 52;
        int textW = Math.max(font.width(name), font.width(id));
        int cardW = Math.max(boxSize + 16, Math.min(180, textW + 16));
        int cardH = boxSize + 32;
        int y = Mth.clamp(cardY, 26, Math.max(26, screenH - cardH - 18));

        drawCardFrame(graphics, cardX, y, cardW, cardH);
        int bx = cardX + (cardW - boxSize) / 2;
        int by = y + 6;
        graphics.fill(bx, by, bx + boxSize, by + boxSize, BOX_BG);
        graphics.renderOutline(bx, by, boxSize, boxSize, 0x33FFFFFF);

        graphics.pose().pushPose();
        graphics.pose().translate(bx + 2, by + 2, 0.0F);
        graphics.pose().scale(3.0F, 3.0F, 1.0F);
        graphics.renderItem(new ItemStack(block.asItem()), 0, 0);
        graphics.pose().popPose();

        drawCardLabels(graphics, font, name, id, cardX, by + boxSize + 5, cardW);
    }

    /** Floating hover zoom card for an entity row, showing a spinning 3D preview. */
    static void renderEntityCard(GuiGraphics graphics, Font font, String id, String displayName,
                                 ItemStack fallbackEgg, int cardX, int cardY, int screenH, double seconds) {
        int boxSize = 68;
        int textW = Math.max(font.width(displayName), font.width(id));
        int cardW = Math.max(boxSize + 16, Math.min(180, textW + 16));
        int cardH = boxSize + 32;
        int y = Mth.clamp(cardY, 26, Math.max(26, screenH - cardH - 18));

        drawCardFrame(graphics, cardX, y, cardW, cardH);
        int bx = cardX + (cardW - boxSize) / 2;
        int by = y + 6;
        graphics.fill(bx, by, bx + boxSize, by + boxSize, BOX_BG);
        graphics.renderOutline(bx, by, boxSize, boxSize, 0x33FFFFFF);

        float spinYaw = (float) ((seconds * 55.0) % 360.0);
        if (!drawEntityIcon(graphics, id, bx + 2, by + 2, boxSize - 4, spinYaw, seconds) && !fallbackEgg.isEmpty()) {
            graphics.pose().pushPose();
            graphics.pose().translate(bx + 10, by + 10, 0.0F);
            graphics.pose().scale(3.0F, 3.0F, 1.0F);
            graphics.renderItem(fallbackEgg, 0, 0);
            graphics.pose().popPose();
        }

        drawCardLabels(graphics, font, displayName, id, cardX, by + boxSize + 5, cardW);
    }

    /** Floating hover zoom card for a particle row, showing its animated sprite zoomed in. */
    static void renderParticleCard(GuiGraphics graphics, Font font, String id, int cardX, int cardY,
                                   int screenH, double seconds) {
        int colon = id.indexOf(':');
        String shortName = colon >= 0 ? id.substring(colon + 1) : id;
        int boxSize = 52;
        int textW = Math.max(font.width(shortName), font.width(id));
        int cardW = Math.max(boxSize + 16, Math.min(180, textW + 16));
        int cardH = boxSize + 32;
        int y = Mth.clamp(cardY, 26, Math.max(26, screenH - cardH - 18));

        drawCardFrame(graphics, cardX, y, cardW, cardH);
        int bx = cardX + (cardW - boxSize) / 2;
        int by = y + 6;
        graphics.fill(bx, by, bx + boxSize, by + boxSize, BOX_BG);
        graphics.renderOutline(bx, by, boxSize, boxSize, 0x33FFFFFF);

        drawParticleIcon(graphics, id, bx + 6, by + 6, boxSize - 12, seconds);
        drawCardLabels(graphics, font, shortName, id, cardX, by + boxSize + 5, cardW);
    }

    private static void drawCardFrame(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, CARD_BG);
        graphics.renderOutline(x, y, w, h, CARD_BORDER);
    }

    private static void drawCardLabels(GuiGraphics graphics, Font font, String title, String sub,
                                       int cardX, int y, int cardW) {
        String t = font.plainSubstrByWidth(title, cardW - 12);
        String s = font.plainSubstrByWidth(sub, cardW - 12);
        graphics.drawString(font, t, cardX + (cardW - font.width(t)) / 2, y, TEXT, false);
        graphics.drawString(font, s, cardX + (cardW - font.width(s)) / 2, y + 10, MUTED, false);
    }

    static void clear() {
        PARTICLE_FRAMES.clear();
    }

    private HologramPickerPreview() {
    }
}
