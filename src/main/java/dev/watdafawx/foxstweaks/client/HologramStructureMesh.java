package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import dev.watdafawx.foxstweaks.hologram.HologramStructure;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.neoforge.client.ChunkRenderTypeSet;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles each {@link HologramStructure} layer's block models ONCE into per-{@link RenderType}
 * GPU {@link VertexBuffer}s with internal neighbour culling across the structure's own blocks.
 *
 * <p>Drawing a cached structure costs one {@link VertexBuffer#drawWithShader} per used render type
 * (typically 1-3 draw calls regardless of whether the structure holds 10 or 4,096 blocks). Uniform
 * colour FX (TINT, PULSE, RAINBOW, GLITCH) are applied cheaply via {@link RenderSystem#setShaderColor};
 * per-vertex Y-dependent FX (SCANLINE, GRADIENT) fall back to immediate emission in
 * {@link HologramRenderer}. Block entities inside the structure are collected once at compile time
 * and rendered through {@link HologramBlockEntities}.
 */
public final class HologramStructureMesh {

    record CompiledBuffer(RenderType renderType, VertexBuffer vbo) {
        void close() {
            this.vbo.close();
        }
    }

    record BeSlot(int posIdx, int x, int y, int z, BlockState state, @Nullable CompoundTag data) {
    }

    static final class CompiledStructure {
        final HologramStructure structure;
        final BlockState[] resolvedPalette;
        final List<CompiledBuffer> buffers;
        final List<BeSlot> blockEntities;
        final int contentHash;
        final int packedLight;
        CompoundTag lastTag;

        CompiledStructure(HologramStructure structure, BlockState[] resolvedPalette,
                          List<CompiledBuffer> buffers, List<BeSlot> blockEntities,
                          int contentHash, int packedLight, CompoundTag lastTag) {
            this.structure = structure;
            this.resolvedPalette = resolvedPalette;
            this.buffers = buffers;
            this.blockEntities = blockEntities;
            this.contentHash = contentHash;
            this.packedLight = packedLight;
            this.lastTag = lastTag;
        }

        void draw(PoseStack pose, Matrix4f view, Matrix4f projection, float r, float g, float b) {
            if (this.buffers.isEmpty())
                return;
            Matrix4f mv = new Matrix4f(view).mul(pose.last().pose());
            for (int i = 0; i < this.buffers.size(); i++) {
                CompiledBuffer cb = this.buffers.get(i);
                cb.renderType.setupRenderState();
                ShaderInstance shader = RenderSystem.getShader();
                try {
                    if (shader != null) {
                        RenderSystem.setShaderColor(r, g, b, 1.0F);
                        if (shader.CHUNK_OFFSET != null)
                            shader.CHUNK_OFFSET.set(0.0F, 0.0F, 0.0F);
                        cb.vbo.bind();
                        cb.vbo.drawWithShader(mv, projection, shader);
                        VertexBuffer.unbind();
                    }
                } finally {
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                    cb.renderType.clearRenderState();
                }
            }
        }

        void close() {
            for (CompiledBuffer cb : this.buffers) {
                try {
                    cb.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static final Map<String, CompiledStructure> CACHE = new HashMap<>();
    private static int buildCount;

    private HologramStructureMesh() {
    }

    public static void registerReload(net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener) manager -> {
            clear();
            HologramBlockEntities.clear();
        });
    }

    /** Total number of VBO builds performed since last clear (for dev checks / benchmarks). */
    public static int buildCount() {
        return buildCount;
    }

    /** Number of currently cached compiled structures. */
    public static int cacheSize() {
        return CACHE.size();
    }

    @Nullable
    static CompiledStructure get(String specId, int layerIndex, CompoundTag data, int packedLight, BlockPos anchorPos) {
        if (data == null || data.isEmpty())
            return null;
        String key = specId + "#layer" + layerIndex;
        CompiledStructure existing = CACHE.get(key);
        if (existing != null && existing.packedLight == packedLight) {
            if (existing.lastTag == data)
                return existing;
            int h = data.hashCode();
            if (h == existing.contentHash && existing.lastTag.equals(data)) {
                existing.lastTag = data;
                return existing;
            }
        }

        if (existing != null) {
            closeOnRenderThread(existing);
            HologramBlockEntities.removeStructureLayer(key);
            CACHE.remove(key);
        }

        HologramStructure structure = HologramStructure.fromTag(data);
        if (structure == null)
            return null;

        CompiledStructure built = compile(specId, layerIndex, structure, data, packedLight, anchorPos);
        CACHE.put(key, built);
        buildCount++;
        return built;
    }

    private static CompiledStructure compile(String specId, int layerIndex, HologramStructure structure,
                                             CompoundTag sourceTag, int packedLight, BlockPos anchorPos) {
        Minecraft mc = Minecraft.getInstance();
        BlockRenderDispatcher dispatcher = mc.getBlockRenderer();
        ModelBlockRenderer modelRenderer = dispatcher.getModelRenderer();

        int w = structure.sizeX();
        int h = structure.sizeY();
        int d = structure.sizeZ();
        int volume = w * h * d;
        BlockState[] resolvedPalette = structure.resolvePalette();
        BlockState[] grid = new BlockState[volume];
        int count = structure.blockCount();
        for (int i = 0; i < count; i++) {
            int posIdx = structure.positionAt(i);
            int palIdx = structure.paletteIndexAt(i);
            if (posIdx >= 0 && posIdx < volume && palIdx >= 0 && palIdx < resolvedPalette.length)
                grid[posIdx] = resolvedPalette[palIdx];
        }

        // Collect BE slots and instantiate cached BEs so models that read ModelData get it during tesselation.
        List<BeSlot> beSlots = new ArrayList<>();
        Map<Integer, BlockEntity> localBes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            int posIdx = structure.positionAt(i);
            BlockState state = grid[posIdx];
            if (state == null || state.isAir())
                continue;
            CompoundTag beTag = structure.blockEntityAt(posIdx);
            if (state.getBlock() instanceof EntityBlock) {
                int x = structure.unpackX(posIdx);
                int y = structure.unpackY(posIdx);
                int z = structure.unpackZ(posIdx);
                BlockEntity be = HologramBlockEntities.getForStructureBlock(
                        specId, layerIndex, posIdx, anchorPos.offset(x, y, z), state, beTag);
                if (be != null) {
                    localBes.put(posIdx, be);
                    beSlots.add(new BeSlot(posIdx, x, y, z, state, beTag));
                }
            }
        }

        StructureBlockGetter getter = new StructureBlockGetter(mc, w, h, d, grid, localBes, anchorPos, packedLight);
        Map<RenderType, ByteBufferBuilder> byteBuffers = new LinkedHashMap<>();
        Map<RenderType, BufferBuilder> builders = new LinkedHashMap<>();
        List<RenderType> orderedTypes = RenderType.chunkBufferLayers();

        PoseStack pose = new PoseStack();
        RandomSource random = RandomSource.create(42L);
        BlockPos.MutableBlockPos localPos = new BlockPos.MutableBlockPos();
        double halfW = w * 0.5;
        double halfD = d * 0.5;

        ModelBlockRenderer.enableCaching();
        try {
            for (int i = 0; i < count; i++) {
                int posIdx = structure.positionAt(i);
                BlockState state = grid[posIdx];
                if (state == null || state.isAir() || state.getRenderShape() != RenderShape.MODEL)
                    continue;

                int x = structure.unpackX(posIdx);
                int y = structure.unpackY(posIdx);
                int z = structure.unpackZ(posIdx);
                localPos.set(x, y, z);

                BakedModel model = dispatcher.getBlockModel(state);
                BlockEntity be = localBes.get(posIdx);
                ModelData modelData = be != null ? be.getModelData() : ModelData.EMPTY;
                long seed = state.getSeed(localPos);
                random.setSeed(seed);
                ChunkRenderTypeSet types = model.getRenderTypes(state, random, modelData);

                pose.pushPose();
                pose.translate(x - halfW, y, z - halfD);
                for (RenderType rt : types) {
                    BufferBuilder builder = builders.get(rt);
                    if (builder == null) {
                        ByteBufferBuilder bb = new ByteBufferBuilder(Math.min(262144, Math.max(4096, count * 256)));
                        byteBuffers.put(rt, bb);
                        builder = new BufferBuilder(bb, rt.mode(), rt.format());
                        builders.put(rt, builder);
                    }
                    random.setSeed(seed);
                    modelRenderer.tesselateBlock(getter, model, state, localPos, pose, builder,
                            true, random, seed, OverlayTexture.NO_OVERLAY, modelData, rt);
                }
                pose.popPose();
            }

            List<CompiledBuffer> compiled = new ArrayList<>(builders.size());
            // Keep canonical chunk render order: solid -> cutoutMipped -> cutout -> translucent -> tripwire.
            for (RenderType rt : orderedTypes) {
                BufferBuilder builder = builders.remove(rt);
                if (builder != null)
                    uploadBuffer(rt, builder, compiled);
            }
            for (var leftover : builders.entrySet())
                uploadBuffer(leftover.getKey(), leftover.getValue(), compiled);

            return new CompiledStructure(structure, resolvedPalette, List.copyOf(compiled),
                    List.copyOf(beSlots), sourceTag.hashCode(), packedLight, sourceTag);
        } finally {
            ModelBlockRenderer.clearCache();
            for (ByteBufferBuilder bb : byteBuffers.values()) {
                try {
                    bb.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void uploadBuffer(RenderType rt, BufferBuilder builder, List<CompiledBuffer> out) {
        MeshData meshData = builder.build();
        if (meshData == null)
            return;
        VertexBuffer vbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vbo.bind();
        vbo.upload(meshData);
        VertexBuffer.unbind();
        out.add(new CompiledBuffer(rt, vbo));
    }

    public static void prune(List<HologramSpec> specs) {
        if (CACHE.isEmpty())
            return;
        Map<String, CompoundTag> active = new HashMap<>();
        for (HologramSpec spec : specs) {
            for (int i = 0; i < spec.layers().size(); i++) {
                HologramLayer layer = spec.layers().get(i);
                if (layer.kind() == HologramLayer.Kind.STRUCTURE)
                    active.put(spec.id() + "#layer" + i, layer.data());
            }
        }
        var it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            CompoundTag data = active.get(entry.getKey());
            if (data == null || !entry.getValue().lastTag.equals(data)) {
                closeOnRenderThread(entry.getValue());
                HologramBlockEntities.removeStructureLayer(entry.getKey());
                it.remove();
            }
        }
    }

    public static void clear() {
        if (CACHE.isEmpty())
            return;
        List<CompiledStructure> toClose = new ArrayList<>(CACHE.values());
        CACHE.clear();
        buildCount = 0;
        for (CompiledStructure cs : toClose)
            closeOnRenderThread(cs);
    }

    private static void closeOnRenderThread(CompiledStructure cs) {
        if (RenderSystem.isOnRenderThread())
            cs.close();
        else
            RenderSystem.recordRenderCall(cs::close);
    }

    /** Virtual {@link BlockAndTintGetter} backed by one structure so internal faces cull cleanly. */
    private static final class StructureBlockGetter implements BlockAndTintGetter {
        private final Minecraft mc;
        private final int w, h, d;
        private final BlockState[] grid;
        private final Map<Integer, BlockEntity> bes;
        private final BlockPos anchor;
        private final int blockLight;
        private final int skyLight;

        StructureBlockGetter(Minecraft mc, int w, int h, int d, BlockState[] grid,
                             Map<Integer, BlockEntity> bes, BlockPos anchor, int packedLight) {
            this.mc = mc;
            this.w = w;
            this.h = h;
            this.d = d;
            this.grid = grid;
            this.bes = bes;
            this.anchor = anchor;
            this.blockLight = LightTexture.block(packedLight);
            this.skyLight = LightTexture.sky(packedLight);
        }

        private int index(BlockPos pos) {
            int x = pos.getX();
            int y = pos.getY();
            int z = pos.getZ();
            if (x < 0 || x >= this.w || y < 0 || y >= this.h || z < 0 || z >= this.d)
                return -1;
            return (y * this.d + z) * this.w + x;
        }

        @Override
        public float getShade(Direction direction, boolean shade) {
            return this.mc.level != null ? this.mc.level.getShade(direction, shade) : 1.0F;
        }

        @Override
        public LevelLightEngine getLightEngine() {
            return this.mc.level.getLightEngine();
        }

        @Override
        public int getBrightness(LightLayer lightType, BlockPos blockPos) {
            return lightType == LightLayer.BLOCK ? this.blockLight : this.skyLight;
        }

        @Override
        public int getBlockTint(BlockPos blockPos, ColorResolver colorResolver) {
            if (this.mc.level == null)
                return -1;
            return this.mc.level.getBlockTint(this.anchor.offset(blockPos), colorResolver);
        }

        @Nullable
        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            int idx = index(pos);
            return idx < 0 ? null : this.bes.get(idx);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            int idx = index(pos);
            if (idx < 0)
                return Blocks.AIR.defaultBlockState();
            BlockState s = this.grid[idx];
            return s != null ? s : Blocks.AIR.defaultBlockState();
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }

        @Override
        public int getHeight() {
            return Math.max(16, this.h);
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    }
}
