package dev.watdafawx.foxstweaks.client.terraform;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.terraform.BiomeSelection;
import dev.watdafawx.foxstweaks.terraform.BiomeWandConfig;
import dev.watdafawx.foxstweaks.terraform.BiomeWandItem;
import dev.watdafawx.foxstweaks.terraform.FlattenWandConfig;
import dev.watdafawx.foxstweaks.terraform.FlattenWandItem;
import dev.watdafawx.foxstweaks.terraform.TerrainFlattener;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Performance-friendly cached 3D preview renderer for both the Biome Changer Wand and the Terrain
 * Flattener Wand.
 *
 * <ul>
 *   <li><b>Biome Wand:</b> Caches merged outer perimeter edges (never per-block boxes) projected
 *       onto surface height, tinted with the target biome's actual grass/foliage color.</li>
 *   <li><b>Terrain Flattener:</b> Caches the predicted heightmap and draws a lightweight wireframe
 *       grid (step 2–4 blocks) color-coded by raise (green) vs lower (orange) vs feathered (cyan).</li>
 * </ul>
 */
public final class TerraformPreviewRenderer {
    private record Edge3D(float x1, float y1, float z1, float x2, float y2, float z2) {
    }

    @Nullable
    private static BiomeWandConfig cachedBiomeCfg;
    @Nullable
    private static BiomeSelection cachedBiomeSel;
    private static List<Edge3D> cachedBiomeEdges = List.of();
    private static int cachedBiomeColor = 0x55EE77;
    @Nullable
    private static volatile dev.watdafawx.foxstweaks.terraform.TerraformNetwork.WholeBiomePreviewPayload wholeBiomePreview;

    @Nullable
    private static FlattenWandConfig cachedFlattenCfg;
    @Nullable
    private static TerrainFlattener.Plan cachedFlattenPlan;

    public static void setWholeBiomePreview(@Nullable dev.watdafawx.foxstweaks.terraform.TerraformNetwork.WholeBiomePreviewPayload payload) {
        wholeBiomePreview = payload;
        cachedBiomeCfg = null; // force map polygon refresh
    }

    @Nullable
    public static dev.watdafawx.foxstweaks.terraform.TerraformNetwork.WholeBiomePreviewPayload wholeBiomePreview() {
        return wholeBiomePreview;
    }

    public static void clearCache() {
        cachedBiomeCfg = null;
        cachedBiomeSel = null;
        cachedBiomeEdges = List.of();
        wholeBiomePreview = null;
        cachedFlattenCfg = null;
        cachedFlattenPlan = null;
    }

    public static void render(PoseStack poseStack, Camera camera) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) return;

        ItemStack held = findHeldWand(player);
        if (held.isEmpty()) {
            MapPreviewBridge.clear();
            return;
        }

        Vec3 camPos = camera.getPosition();
        poseStack.pushPose();
        poseStack.translate(-camPos.x, -camPos.y, -camPos.z);

        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (held.getItem() instanceof BiomeWandItem) {
            renderBiomePreview(level, held, poseStack, buffers);
        } else if (held.getItem() instanceof FlattenWandItem) {
            renderFlattenPreview(level, held, poseStack, buffers);
        }

        buffers.endBatch(RenderType.lines());
        poseStack.popPose();
    }

    private static ItemStack findHeldWand(LocalPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof BiomeWandItem || main.getItem() instanceof FlattenWandItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof BiomeWandItem || off.getItem() instanceof FlattenWandItem) return off;
        return ItemStack.EMPTY;
    }

    private static void renderBiomePreview(ClientLevel level, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers) {
        BiomeWandConfig cfg = BiomeWandItem.getConfig(stack);
        if (!cfg.hasSelection()) {
            // If Box mode with only Pos1 set, draw a single marker box
            if (cfg.mode() == BiomeWandConfig.Mode.BOX && cfg.pos1().isPresent()) {
                drawBlockOutline(poseStack, buffers.getBuffer(RenderType.lines()), cfg.pos1().get(), 0.3F, 1.0F, 0.5F, 0.9F);
            }
            MapPreviewBridge.clear();
            return;
        }

        if (!Objects.equals(cfg, cachedBiomeCfg) || cachedBiomeSel == null) {
            cachedBiomeCfg = cfg;
            cachedBiomeSel = BiomeSelection.compute(level, cfg, ServerConfig.biomeWandMaxRadius());
            cachedBiomeColor = resolveBiomePreviewColor(level, cfg);
            cachedBiomeEdges = buildCachedEdges3D(level, cachedBiomeSel);
            var wb = wholeBiomePreview;
            if (cfg.mode() == BiomeWandConfig.Mode.WHOLE_BIOME
                    && wb != null
                    && wb.dimension().equals(level.dimension())
                    && cfg.pos1().isPresent()
                    && wb.seed().equals(cfg.pos1().get())
                    && wb.polygon().size() >= 3) {
                MapPreviewBridge.update(level.dimension(), wb.polygon(), cachedBiomeColor,
                        "Whole " + wb.sourceBiome() + " -> " + cfg.targetBiome().getPath()
                                + " (" + wb.totalChunks() + " chunks)");
            } else if (cachedBiomeSel != null) {
                int refY = cfg.pos1().map(BlockPos::getY).orElse(64);
                List<BlockPos> poly = cachedBiomeSel.buildMapOutlinePolygon(refY);
                MapPreviewBridge.update(level.dimension(), poly, cachedBiomeColor,
                        "Biome -> " + cfg.targetBiome().getPath() + " (" + cachedBiomeSel.selectedColumns + " cols)");
            } else {
                MapPreviewBridge.clear();
            }
        }

        if (cachedBiomeEdges.isEmpty()) return;

        float r = ((cachedBiomeColor >> 16) & 0xFF) / 255.0F;
        float g = ((cachedBiomeColor >> 8) & 0xFF) / 255.0F;
        float b = (cachedBiomeColor & 0xFF) / 255.0F;
        // Brighten slightly so outline pops against terrain
        r = Math.min(1.0F, r * 0.7F + 0.35F);
        g = Math.min(1.0F, g * 0.7F + 0.35F);
        b = Math.min(1.0F, b * 0.7F + 0.35F);

        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        for (Edge3D e : cachedBiomeEdges) {
            // Surface contour line
            line(vc, mat, pose, e.x1, e.y1, e.z1, e.x2, e.y2, e.z2, r, g, b, 0.95F);
            // Elevated ribbon line (+2.5 blocks) so perimeter is visible over grass/bushes
            line(vc, mat, pose, e.x1, e.y1 + 2.5F, e.z1, e.x2, e.y2 + 2.5F, e.z2, r, g, b, 0.55F);
            // Corner/segment post
            line(vc, mat, pose, e.x1, e.y1, e.z1, e.x1, e.y1 + 2.5F, e.z1, r, g, b, 0.4F);
        }

        // If Custom Y-slice mode, also draw subtle top/bottom corner bounds
        if (cfg.heightMode() == BiomeWandConfig.HeightMode.SLICE && cachedBiomeSel != null) {
            drawYBandBox(poseStack, vc, cachedBiomeSel.minX, cachedBiomeSel.minY, cachedBiomeSel.minZ,
                    cachedBiomeSel.maxX() + 1, cachedBiomeSel.maxY + 1, cachedBiomeSel.maxZ() + 1,
                    r, g, b, 0.35F);
        }
    }

    private static List<Edge3D> buildCachedEdges3D(ClientLevel level, @Nullable BiomeSelection sel) {
        if (sel == null) return List.of();
        List<BiomeSelection.Edge> edges2d = sel.buildPerimeterEdges();
        List<Edge3D> out = new ArrayList<>(edges2d.size());
        for (BiomeSelection.Edge e : edges2d) {
            // Subdivide long edges every 4 blocks so the outline hugs hills smoothly
            int dx = e.x2() - e.x1();
            int dz = e.z2() - e.z1();
            int len = Math.max(Math.abs(dx), Math.abs(dz));
            int steps = Math.max(1, (len + 3) / 4);
            for (int i = 0; i < steps; i++) {
                float t0 = (float) i / steps;
                float t1 = (float) (i + 1) / steps;
                float x0 = e.x1() + dx * t0;
                float z0 = e.z1() + dz * t0;
                float x1 = e.x1() + dx * t1;
                float z1 = e.z1() + dz * t1;
                float y0 = surfaceY(level, Math.round(x0), Math.round(z0)) + 0.15F;
                float y1 = surfaceY(level, Math.round(x1), Math.round(z1)) + 0.15F;
                out.add(new Edge3D(x0, y0, z0, x1, y1, z1));
            }
        }
        return out;
    }

    private static void renderFlattenPreview(ClientLevel level, ItemStack stack, PoseStack poseStack, MultiBufferSource buffers) {
        FlattenWandConfig cfg = FlattenWandItem.getConfig(stack);
        if (!cfg.hasSelection()) {
            if (cfg.mode() == FlattenWandConfig.Mode.BOX && cfg.pos1().isPresent()) {
                drawBlockOutline(poseStack, buffers.getBuffer(RenderType.lines()), cfg.pos1().get(), 1.0F, 0.75F, 0.2F, 0.9F);
            }
            MapPreviewBridge.clear();
            return;
        }

        if (!Objects.equals(cfg, cachedFlattenCfg) || cachedFlattenPlan == null) {
            cachedFlattenCfg = cfg;
            cachedFlattenPlan = TerrainFlattener.computePlan(level, cfg, ServerConfig.flattenWandMaxRadius());
            if (cachedFlattenPlan != null) {
                List<BlockPos> poly = buildFlattenMapPolygon(cachedFlattenPlan);
                MapPreviewBridge.update(level.dimension(), poly, 0xFFAA33,
                        "Flatten " + Math.round(cfg.flatness() * 100) + "% (Y=" + cachedFlattenPlan.targetY() + ")");
            } else {
                MapPreviewBridge.clear();
            }
        }

        TerrainFlattener.Plan plan = cachedFlattenPlan;
        if (plan == null) return;

        VertexConsumer vc = buffers.getBuffer(RenderType.lines());
        PoseStack.Pose pose = poseStack.last();
        Matrix4f mat = pose.pose();

        int step = (plan.width() * plan.depth() > 2500) ? 4 : 2;
        for (int lz = 0; lz < plan.depth(); lz += step) {
            for (int lx = 0; lx < plan.width(); lx += step) {
                int idx = plan.index(lx, lz);
                if (!plan.inMask()[idx]) continue;

                float x = plan.minX() + lx + 0.5F;
                float z = plan.minZ() + lz + 0.5F;
                float y = plan.newH()[idx] + 1.08F;
                int delta = plan.newH()[idx] - plan.oldH()[idx];

                float r, g, b;
                if (delta > 0) {
                    r = 0.25F; g = 0.95F; b = 0.45F; // Raising: green
                } else if (delta < 0) {
                    r = 1.0F; g = 0.55F; b = 0.20F;  // Lowering: orange
                } else {
                    r = 0.35F; g = 0.80F; b = 1.0F;  // Unchanged/feathered: cyan
                }

                // Connect to +X neighbor
                if (lx + step < plan.width()) {
                    int nIdx = plan.index(lx + step, lz);
                    if (plan.inMask()[nIdx]) {
                        line(vc, mat, pose, x, y, z, x + step, plan.newH()[nIdx] + 1.08F, z, r, g, b, 0.85F);
                    }
                }
                // Connect to +Z neighbor
                if (lz + step < plan.depth()) {
                    int nIdx = plan.index(lx, lz + step);
                    if (plan.inMask()[nIdx]) {
                        line(vc, mat, pose, x, y, z, x, plan.newH()[nIdx] + 1.08F, z + step, r, g, b, 0.85F);
                    }
                }
            }
        }
    }

    private static List<BlockPos> buildFlattenMapPolygon(TerrainFlattener.Plan plan) {
        int y = plan.targetY();
        int step = Math.max(1, plan.depth() / 24);
        List<BlockPos> left = new ArrayList<>();
        List<BlockPos> right = new ArrayList<>();
        for (int lz = 0; lz < plan.depth(); lz += step) {
            int firstX = -1, lastX = -1;
            for (int lx = 0; lx < plan.width(); lx++) {
                if (plan.inMask()[plan.index(lx, lz)]) {
                    if (firstX < 0) firstX = lx;
                    lastX = lx;
                }
            }
            if (firstX >= 0) {
                left.add(new BlockPos(plan.minX() + firstX, y, plan.minZ() + lz));
                right.add(new BlockPos(plan.minX() + lastX + 1, y, plan.minZ() + lz));
            }
        }
        List<BlockPos> poly = new ArrayList<>(left.size() + right.size());
        poly.addAll(left);
        for (int i = right.size() - 1; i >= 0; i--) poly.add(right.get(i));
        return poly;
    }

    private static int resolveBiomePreviewColor(ClientLevel level, BiomeWandConfig cfg) {
        try {
            Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
            Optional<Holder.Reference<Biome>> holder = reg.getHolder(ResourceKey.create(Registries.BIOME, cfg.targetBiome()));
            if (holder.isPresent()) {
                return holder.get().value().getGrassColor(0.0, 0.0) & 0xFFFFFF;
            }
        } catch (Exception ignored) {
        }
        return 0x55EE77;
    }

    private static int surfaceY(ClientLevel level, int wx, int wz) {
        if (!level.hasChunk(wx >> 4, wz >> 4)) return 64;
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
    }

    private static void drawBlockOutline(PoseStack poseStack, VertexConsumer vc, BlockPos p, float r, float g, float b, float a) {
        drawYBandBox(poseStack, vc, p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, r, g, b, a);
    }

    private static void drawYBandBox(PoseStack poseStack, VertexConsumer vc,
                                     float x0, float y0, float z0, float x1, float y1, float z1,
                                     float r, float g, float b, float a) {
        PoseStack.Pose pose = poseStack.last();
        Matrix4f m = pose.pose();
        // Bottom
        line(vc, m, pose, x0, y0, z0, x1, y0, z0, r, g, b, a);
        line(vc, m, pose, x1, y0, z0, x1, y0, z1, r, g, b, a);
        line(vc, m, pose, x1, y0, z1, x0, y0, z1, r, g, b, a);
        line(vc, m, pose, x0, y0, z1, x0, y0, z0, r, g, b, a);
        // Top
        line(vc, m, pose, x0, y1, z0, x1, y1, z0, r, g, b, a);
        line(vc, m, pose, x1, y1, z0, x1, y1, z1, r, g, b, a);
        line(vc, m, pose, x1, y1, z1, x0, y1, z1, r, g, b, a);
        line(vc, m, pose, x0, y1, z1, x0, y1, z0, r, g, b, a);
        // Verticals
        line(vc, m, pose, x0, y0, z0, x0, y1, z0, r, g, b, a);
        line(vc, m, pose, x1, y0, z0, x1, y1, z0, r, g, b, a);
        line(vc, m, pose, x1, y0, z1, x1, y1, z1, r, g, b, a);
        line(vc, m, pose, x0, y0, z1, x0, y1, z1, r, g, b, a);
    }

    private static void line(VertexConsumer vc, Matrix4f mat, PoseStack.Pose pose,
                             float x1, float y1, float z1, float x2, float y2, float z2,
                             float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-5F) len = 1.0F;
        float nx = dx / len, ny = dy / len, nz = dz / len;
        vc.addVertex(mat, x1, y1, z1).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
        vc.addVertex(mat, x2, y2, z2).setColor(r, g, b, a).setNormal(pose, nx, ny, nz);
    }

    private TerraformPreviewRenderer() {
    }
}
