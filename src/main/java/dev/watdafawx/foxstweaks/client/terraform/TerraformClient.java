package dev.watdafawx.foxstweaks.client.terraform;

import dev.watdafawx.foxstweaks.terraform.ExactBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Client entry point for the Biome Changer and Terrain Flattener tools. */
public final class TerraformClient {
    public static void init() {
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent e) -> {
            if (e.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                TerraformPreviewRenderer.render(e.getPoseStack(), e.getCamera());
            }
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            ExactBiomes.clearClient();
            TerraformPreviewRenderer.clearCache();
            MapPreviewBridge.clear();
        });
    }

    public static void openBiomeScreen(InteractionHand hand, ItemStack stack) {
        Minecraft.getInstance().setScreen(new BiomeWandScreen(hand, stack));
    }

    public static void openFlattenScreen(InteractionHand hand, ItemStack stack) {
        Minecraft.getInstance().setScreen(new FlattenWandScreen(hand, stack));
    }

    /**
     * Clears biome color caches and marks synced chunks + 1-chunk neighbor border dirty so grass,
     * foliage, water, and sky tint update immediately at 1x1x1 borders.
     */
    public static void onExactBiomesSynced(List<ExactBiomes.ChunkPacketData> updates) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || updates.isEmpty()) return;

        level.clearTintCaches();
        int minSec = level.getMinSection();
        int maxSec = level.getMaxSection();

        Set<Long> dirtyChunks = new HashSet<>(updates.size() * 4);
        for (ExactBiomes.ChunkPacketData u : updates) {
            ChunkPos cp = new ChunkPos(u.chunkPos());
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    dirtyChunks.add(ChunkPos.asLong(cp.x + dx, cp.z + dz));
                }
            }
        }
        for (long key : dirtyChunks) {
            ChunkPos cp = new ChunkPos(key);
            if (!level.hasChunk(cp.x, cp.z)) continue;
            for (int sy = minSec; sy < maxSec; sy++) {
                mc.levelRenderer.setSectionDirty(cp.x, sy, cp.z);
            }
        }
    }

    private TerraformClient() {
    }
}
