package dev.watdafawx.foxstweaks.terraform;

import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Exact 1x1x1 per-block biome overrides, solving vanilla's 4x4x4 quart biome limit at selection
 * borders.
 *
 * <p>Interior 4x4x4 quart cells are written directly to vanilla's {@code LevelChunkSection} biome
 * palette so standard world systems see them with zero overhead. Only partial border cells (or
 * exact Y-slice boundaries) need entries here.
 *
 * <p>Reads from {@link #getOverride(Level, BlockPos)} happen on both the server thread and client
 * chunk-meshing worker threads, so the live lookup maps are {@link ConcurrentHashMap}s holding
 * immutable {@link ChunkSnapshot} records swapped atomically on edit.
 */
public final class ExactBiomes extends SavedData {
    private static final String DATA_NAME = "foxstweaks_exact_biomes";
    private static final int Y_BIAS = 2048;

    private static final Map<ResourceKey<Level>, ConcurrentHashMap<Long, ChunkSnapshot>> SERVER_LIVE =
            new ConcurrentHashMap<>();
    private static volatile ResourceKey<Level> clientDim;
    private static final ConcurrentHashMap<Long, ChunkSnapshot> CLIENT_LIVE = new ConcurrentHashMap<>();

    /** Immutable per-chunk lookup snapshot, safe to read concurrently from any worker thread. */
    public static final class ChunkSnapshot {
        public final ResourceLocation[] palette;
        /** Length 256 (localZ << 4 | localX), 1-based palette index, 0 = none. Null if no column overrides. */
        @Nullable
        public final short[] columns;
        /** Packed ((y + Y_BIAS) << 8) | (localZ << 4) | localX -> 1-based palette index. Null if empty. */
        @Nullable
        public final Int2IntMap blocks;
        /** Lazily resolved biome holders matching {@link #palette}. */
        @Nullable
        private volatile Holder<Biome>[] resolved;

        public ChunkSnapshot(ResourceLocation[] palette, @Nullable short[] columns, @Nullable Int2IntMap blocks) {
            this.palette = palette;
            this.columns = columns;
            this.blocks = blocks;
        }

        public boolean isEmpty() {
            return palette.length == 0 || (columns == null && (blocks == null || blocks.isEmpty()));
        }

        @Nullable
        public Holder<Biome> lookup(Level level, BlockPos pos) {
            int localX = pos.getX() & 15;
            int localZ = pos.getZ() & 15;
            int idx = 0;
            if (blocks != null) {
                int key = packBlock(localX, pos.getY(), localZ);
                idx = blocks.get(key);
            }
            if (idx == 0 && columns != null) {
                idx = columns[(localZ << 4) | localX];
            }
            if (idx <= 0 || idx > palette.length) {
                return null;
            }
            return resolve(level, idx - 1);
        }

        @SuppressWarnings("unchecked")
        @Nullable
        private Holder<Biome> resolve(Level level, int paletteIndex) {
            Holder<Biome>[] arr = this.resolved;
            if (arr == null) {
                Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
                arr = new Holder[palette.length];
                for (int i = 0; i < palette.length; i++) {
                    Optional<Holder.Reference<Biome>> holder = reg.getHolder(ResourceKey.create(Registries.BIOME, palette[i]));
                    arr[i] = holder.orElse(null);
                }
                this.resolved = arr;
            }
            return arr[paletteIndex];
        }
    }

    /** Mutable working copy for edits inside a single chunk. */
    public static final class MutableChunk {
        private final List<ResourceLocation> palette = new ArrayList<>();
        private final Map<ResourceLocation, Integer> paletteIndex = new HashMap<>();
        @Nullable
        private short[] columns;
        @Nullable
        private Int2IntOpenHashMap blocks;

        public MutableChunk() {
        }

        public static MutableChunk from(@Nullable ChunkSnapshot snap) {
            MutableChunk m = new MutableChunk();
            if (snap == null) return m;
            for (int i = 0; i < snap.palette.length; i++) {
                m.palette.add(snap.palette[i]);
                m.paletteIndex.put(snap.palette[i], i + 1);
            }
            if (snap.columns != null) {
                m.columns = Arrays.copyOf(snap.columns, 256);
            }
            if (snap.blocks != null && !snap.blocks.isEmpty()) {
                m.blocks = new Int2IntOpenHashMap(snap.blocks);
                m.blocks.defaultReturnValue(0);
            }
            return m;
        }

        private int intern(ResourceLocation biomeId) {
            Integer existing = paletteIndex.get(biomeId);
            if (existing != null) return existing;
            int id = palette.size() + 1;
            palette.add(biomeId);
            paletteIndex.put(biomeId, id);
            return id;
        }

        public void setColumn(int localX, int localZ, @Nullable ResourceLocation biomeId) {
            int colIdx = ((localZ & 15) << 4) | (localX & 15);
            if (biomeId == null) {
                if (columns != null) {
                    columns[colIdx] = 0;
                }
            } else {
                if (columns == null) columns = new short[256];
                columns[colIdx] = (short) intern(biomeId);
            }
            // Clear any 3D overrides in this column when overriding the full column.
            if (blocks != null && !blocks.isEmpty()) {
                blocks.int2IntEntrySet().removeIf(e -> (e.getIntKey() & 0xFF) == colIdx);
            }
        }

        public void setBlock(int localX, int y, int localZ, @Nullable ResourceLocation biomeId) {
            int key = packBlock(localX & 15, y, localZ & 15);
            if (biomeId == null) {
                if (blocks != null) blocks.remove(key);
            } else {
                if (blocks == null) {
                    blocks = new Int2IntOpenHashMap();
                    blocks.defaultReturnValue(0);
                }
                blocks.put(key, intern(biomeId));
            }
        }

        public void clearColumn(int localX, int localZ) {
            setColumn(localX, localZ, null);
        }

        public ChunkSnapshot freeze() {
            boolean hasCol = false;
            if (columns != null) {
                for (short s : columns) {
                    if (s != 0) {
                        hasCol = true;
                        break;
                    }
                }
            }
            boolean hasBlocks = blocks != null && !blocks.isEmpty();
            if (!hasCol && !hasBlocks) {
                return new ChunkSnapshot(new ResourceLocation[0], null, null);
            }
            // Compact unused palette entries
            int[] remap = new int[palette.size() + 1];
            List<ResourceLocation> compact = new ArrayList<>();
            short[] outCols = hasCol ? new short[256] : null;
            if (hasCol) {
                for (int i = 0; i < 256; i++) {
                    int old = columns[i];
                    if (old > 0 && old <= palette.size()) {
                        if (remap[old] == 0) {
                            compact.add(palette.get(old - 1));
                            remap[old] = compact.size();
                        }
                        outCols[i] = (short) remap[old];
                    }
                }
            }
            Int2IntOpenHashMap outBlocks = null;
            if (hasBlocks) {
                outBlocks = new Int2IntOpenHashMap(blocks.size());
                outBlocks.defaultReturnValue(0);
                for (Int2IntMap.Entry e : blocks.int2IntEntrySet()) {
                    int old = e.getIntValue();
                    if (old > 0 && old <= palette.size()) {
                        if (remap[old] == 0) {
                            compact.add(palette.get(old - 1));
                            remap[old] = compact.size();
                        }
                        outBlocks.put(e.getIntKey(), remap[old]);
                    }
                }
                if (outBlocks.isEmpty()) outBlocks = null;
            }
            return new ChunkSnapshot(compact.toArray(ResourceLocation[]::new), outCols, outBlocks);
        }
    }

    /** Wire entry for syncing one chunk's exact biome overrides to clients. */
    public record ChunkPacketData(long chunkPos, ChunkSnapshot snapshot) {
        public static final StreamCodec<RegistryFriendlyByteBuf, ChunkPacketData> STREAM_CODEC =
                StreamCodec.of(ChunkPacketData::encode, ChunkPacketData::decode);

        private static void encode(RegistryFriendlyByteBuf buf, ChunkPacketData data) {
            buf.writeLong(data.chunkPos);
            ChunkSnapshot s = data.snapshot;
            buf.writeVarInt(s.palette.length);
            for (ResourceLocation rl : s.palette) {
                buf.writeResourceLocation(rl);
            }
            boolean hasCols = s.columns != null;
            buf.writeBoolean(hasCols);
            if (hasCols) {
                for (int i = 0; i < 256; i++) {
                    buf.writeVarInt(s.columns[i]);
                }
            }
            int blockCount = s.blocks == null ? 0 : s.blocks.size();
            buf.writeVarInt(blockCount);
            if (s.blocks != null) {
                for (Int2IntMap.Entry e : s.blocks.int2IntEntrySet()) {
                    buf.writeInt(e.getIntKey());
                    buf.writeVarInt(e.getIntValue());
                }
            }
        }

        private static ChunkPacketData decode(RegistryFriendlyByteBuf buf) {
            long chunkPos = buf.readLong();
            int palLen = buf.readVarInt();
            ResourceLocation[] pal = new ResourceLocation[palLen];
            for (int i = 0; i < palLen; i++) {
                pal[i] = buf.readResourceLocation();
            }
            short[] cols = null;
            if (buf.readBoolean()) {
                cols = new short[256];
                for (int i = 0; i < 256; i++) {
                    cols[i] = (short) buf.readVarInt();
                }
            }
            int blockCount = buf.readVarInt();
            Int2IntOpenHashMap blocks = null;
            if (blockCount > 0) {
                blocks = new Int2IntOpenHashMap(blockCount);
                blocks.defaultReturnValue(0);
                for (int i = 0; i < blockCount; i++) {
                    int k = buf.readInt();
                    int v = buf.readVarInt();
                    blocks.put(k, v);
                }
            }
            return new ChunkPacketData(chunkPos, new ChunkSnapshot(pal, cols, blocks));
        }
    }

    private final Map<Long, ChunkSnapshot> chunks = new HashMap<>();

    public static int packBlock(int localX, int y, int localZ) {
        return ((y + Y_BIAS) << 8) | ((localZ & 15) << 4) | (localX & 15);
    }

    public static ExactBiomes get(ServerLevel level) {
        ExactBiomes data = level.getDataStorage().computeIfAbsent(
                new Factory<>(ExactBiomes::new, ExactBiomes::load, null), DATA_NAME);
        // Ensure live map is populated once loaded from disk
        ConcurrentHashMap<Long, ChunkSnapshot> live = SERVER_LIVE.computeIfAbsent(
                level.dimension(), k -> new ConcurrentHashMap<>());
        if (live.isEmpty() && !data.chunks.isEmpty()) {
            live.putAll(data.chunks);
        }
        return data;
    }

    @Nullable
    public static Holder<Biome> getOverride(Level level, BlockPos pos) {
        ConcurrentHashMap<Long, ChunkSnapshot> map;
        if (level.isClientSide()) {
            if (CLIENT_LIVE.isEmpty() || !level.dimension().equals(clientDim)) {
                return null;
            }
            map = CLIENT_LIVE;
        } else {
            map = SERVER_LIVE.get(level.dimension());
            if (map == null || map.isEmpty()) {
                return null;
            }
        }
        long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkSnapshot snap = map.get(key);
        return snap == null ? null : snap.lookup(level, pos);
    }

    @Nullable
    public ChunkSnapshot getChunk(long chunkPos) {
        return chunks.get(chunkPos);
    }

    public void setChunk(ServerLevel level, long chunkPos, ChunkSnapshot snap) {
        ConcurrentHashMap<Long, ChunkSnapshot> live = SERVER_LIVE.computeIfAbsent(
                level.dimension(), k -> new ConcurrentHashMap<>());
        if (snap.isEmpty()) {
            chunks.remove(chunkPos);
            live.remove(chunkPos);
        } else {
            chunks.put(chunkPos, snap);
            live.put(chunkPos, snap);
        }
        setDirty();
    }

    public List<ChunkPacketData> allPacketData() {
        List<ChunkPacketData> list = new ArrayList<>(chunks.size());
        for (Map.Entry<Long, ChunkSnapshot> e : chunks.entrySet()) {
            list.add(new ChunkPacketData(e.getKey(), e.getValue()));
        }
        return list;
    }

    public static void clearServerDimension(ResourceKey<Level> dim) {
        SERVER_LIVE.remove(dim);
    }

    public static void clearClient() {
        clientDim = null;
        CLIENT_LIVE.clear();
    }

    public static void applyClientSync(ResourceKey<Level> dim, boolean fullReplace, List<ChunkPacketData> updates) {
        if (fullReplace || !dim.equals(clientDim)) {
            CLIENT_LIVE.clear();
            clientDim = dim;
        }
        for (ChunkPacketData u : updates) {
            if (u.snapshot().isEmpty()) {
                CLIENT_LIVE.remove(u.chunkPos());
            } else {
                CLIENT_LIVE.put(u.chunkPos(), u.snapshot());
            }
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<Long, ChunkSnapshot> entry : chunks.entrySet()) {
            ChunkSnapshot s = entry.getValue();
            if (s.isEmpty()) continue;
            CompoundTag ct = new CompoundTag();
            ct.putLong("c", entry.getKey());
            ListTag pal = new ListTag();
            for (ResourceLocation rl : s.palette) {
                pal.add(StringTag.valueOf(rl.toString()));
            }
            ct.put("p", pal);
            if (s.columns != null) {
                int[] colInts = new int[256];
                for (int i = 0; i < 256; i++) colInts[i] = s.columns[i];
                ct.putIntArray("col", colInts);
            }
            if (s.blocks != null && !s.blocks.isEmpty()) {
                int[] keys = new int[s.blocks.size()];
                int[] vals = new int[s.blocks.size()];
                int i = 0;
                for (Int2IntMap.Entry e : s.blocks.int2IntEntrySet()) {
                    keys[i] = e.getIntKey();
                    vals[i] = e.getIntValue();
                    i++;
                }
                ct.putIntArray("bk", keys);
                ct.putIntArray("bv", vals);
            }
            list.add(ct);
        }
        tag.put("chunks", list);
        return tag;
    }

    private static ExactBiomes load(CompoundTag tag, HolderLookup.Provider registries) {
        ExactBiomes data = new ExactBiomes();
        ListTag list = tag.getList("chunks", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag ct = list.getCompound(i);
            long chunkKey = ct.getLong("c");
            ListTag palTag = ct.getList("p", Tag.TAG_STRING);
            ResourceLocation[] pal = new ResourceLocation[palTag.size()];
            for (int j = 0; j < palTag.size(); j++) {
                pal[j] = ResourceLocation.parse(palTag.getString(j));
            }
            short[] cols = null;
            if (ct.contains("col", Tag.TAG_INT_ARRAY)) {
                int[] colInts = ct.getIntArray("col");
                if (colInts.length == 256) {
                    cols = new short[256];
                    for (int j = 0; j < 256; j++) cols[j] = (short) colInts[j];
                }
            }
            Int2IntOpenHashMap blocks = null;
            if (ct.contains("bk", Tag.TAG_INT_ARRAY) && ct.contains("bv", Tag.TAG_INT_ARRAY)) {
                int[] keys = ct.getIntArray("bk");
                int[] vals = ct.getIntArray("bv");
                int n = Math.min(keys.length, vals.length);
                if (n > 0) {
                    blocks = new Int2IntOpenHashMap(n);
                    blocks.defaultReturnValue(0);
                    for (int j = 0; j < n; j++) {
                        blocks.put(keys[j], vals[j]);
                    }
                }
            }
            ChunkSnapshot snap = new ChunkSnapshot(pal, cols, blocks);
            if (!snap.isEmpty()) {
                data.chunks.put(chunkKey, snap);
            }
        }
        return data;
    }
}
