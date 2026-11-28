package dev.watdafawx.foxstweaks.terraform;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Disk-backed, GZIP-compressed undo store under {@code <world>/data/foxstweaks_undo/<player-uuid>/}.
 *
 * <p>Keeps <b>zero bytes</b> of undo history in server RAM once an operation finishes, survives
 * server/world restarts, and auto-prunes to the newest {@link #MAX_UNDO} files per player & tool.
 */
public final class TerraformUndoStore {
    private static final Logger LOG = LogUtils.getLogger();
    public static final int MAX_UNDO = 5;
    private static final AtomicLong SEQ = new AtomicLong();

    public record QuartEntry(int qx, int qy, int qz, Holder<Biome> biome) {
    }

    public record BiomeUndo(
            ResourceKey<Level> dimension,
            Map<Long, List<QuartEntry>> vanillaQuarts,
            Map<Long, ExactBiomes.ChunkSnapshot> prevExact,
            Map<BlockPos, BlockState> prevSnow
    ) {
    }

    public record FlattenUndo(
            ResourceKey<Level> dimension,
            Map<BlockPos, BlockState> previous
    ) {
    }

    private static Path playerDir(MinecraftServer server, UUID playerId) {
        return server.getWorldPath(LevelResource.ROOT)
                .resolve("data")
                .resolve("foxstweaks_undo")
                .resolve(playerId.toString());
    }

    public static void saveFlatten(ServerLevel level, UUID playerId, Map<BlockPos, BlockState> backup) {
        if (backup.isEmpty()) return;
        try {
            Path dir = playerDir(level.getServer(), playerId);
            Files.createDirectories(dir);

            CompoundTag root = new CompoundTag();
            root.putString("dim", level.dimension().location().toString());
            writeBlockMap(root, "blocks", backup);

            Path file = dir.resolve(nextFilename("flatten"));
            NbtIo.writeCompressed(root, file);
            pruneOld(dir, "flatten-");
        } catch (IOException ex) {
            LOG.error("Failed to write terrain flatten undo snapshot", ex);
        }
    }

    @Nullable
    public static FlattenUndo popFlatten(ServerLevel level, UUID playerId) {
        Path dir = playerDir(level.getServer(), playerId);
        Path latest = findLatest(dir, "flatten-");
        if (latest == null) return null;

        try {
            CompoundTag root = NbtIo.readCompressed(latest, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(root.getString("dim")));
            if (!level.dimension().equals(dim)) {
                return new FlattenUndo(dim, Map.of());
            }
            Map<BlockPos, BlockState> blocks = readBlockMap(root, "blocks", level.holderLookup(Registries.BLOCK));
            Files.deleteIfExists(latest);
            return new FlattenUndo(dim, blocks);
        } catch (IOException ex) {
            LOG.error("Failed to read terrain flatten undo snapshot {}", latest, ex);
            return null;
        }
    }

    public static void saveBiome(ServerLevel level, UUID playerId,
                                 Map<Long, List<QuartEntry>> vanillaQuarts,
                                 Map<Long, ExactBiomes.ChunkSnapshot> prevExact,
                                 Map<BlockPos, BlockState> prevSnow) {
        try {
            Path dir = playerDir(level.getServer(), playerId);
            Files.createDirectories(dir);

            CompoundTag root = new CompoundTag();
            root.putString("dim", level.dimension().location().toString());

            // 1. Vanilla quart backups (paletted by biome id)
            List<String> biomePal = new ArrayList<>();
            Map<String, Integer> biomePalIdx = new HashMap<>();
            ListTag qChunks = new ListTag();
            for (Map.Entry<Long, List<QuartEntry>> e : vanillaQuarts.entrySet()) {
                List<QuartEntry> list = e.getValue();
                if (list.isEmpty()) continue;
                CompoundTag ct = new CompoundTag();
                ct.putLong("c", e.getKey());
                int n = list.size();
                int[] xs = new int[n], ys = new int[n], zs = new int[n], bs = new int[n];
                for (int i = 0; i < n; i++) {
                    QuartEntry q = list.get(i);
                    xs[i] = q.qx;
                    ys[i] = q.qy;
                    zs[i] = q.qz;
                    String id = q.biome.unwrapKey().map(k -> k.location().toString()).orElse("minecraft:plains");
                    bs[i] = biomePalIdx.computeIfAbsent(id, k -> {
                        biomePal.add(k);
                        return biomePal.size() - 1;
                    });
                }
                ct.putIntArray("x", xs);
                ct.putIntArray("y", ys);
                ct.putIntArray("z", zs);
                ct.putIntArray("b", bs);
                qChunks.add(ct);
            }
            ListTag bpTag = new ListTag();
            for (String s : biomePal) bpTag.add(StringTag.valueOf(s));
            root.put("bpal", bpTag);
            root.put("quarts", qChunks);

            // 2. Exact 1x1x1 chunk snapshots
            ListTag exactList = new ListTag();
            for (Map.Entry<Long, ExactBiomes.ChunkSnapshot> e : prevExact.entrySet()) {
                CompoundTag ct = new CompoundTag();
                ct.putLong("c", e.getKey());
                ExactBiomes.ChunkSnapshot s = e.getValue();
                if (s != null && !s.isEmpty()) {
                    ListTag pTag = new ListTag();
                    for (ResourceLocation rl : s.palette) pTag.add(StringTag.valueOf(rl.toString()));
                    ct.put("p", pTag);
                    if (s.columns != null) {
                        int[] cols = new int[256];
                        for (int i = 0; i < 256; i++) cols[i] = s.columns[i];
                        ct.putIntArray("col", cols);
                    }
                    if (s.columnMinY != null) {
                        int[] cmy = new int[256];
                        for (int i = 0; i < 256; i++) cmy[i] = s.columnMinY[i];
                        ct.putIntArray("cmy", cmy);
                    }
                    if (s.blocks != null && !s.blocks.isEmpty()) {
                        int[] bk = new int[s.blocks.size()];
                        int[] bv = new int[s.blocks.size()];
                        int idx = 0;
                        for (Int2IntMap.Entry be : s.blocks.int2IntEntrySet()) {
                            bk[idx] = be.getIntKey();
                            bv[idx] = be.getIntValue();
                            idx++;
                        }
                        ct.putIntArray("bk", bk);
                        ct.putIntArray("bv", bv);
                    }
                }
                exactList.add(ct);
            }
            root.put("exact", exactList);

            // 3. Snow/ice backups
            if (!prevSnow.isEmpty()) {
                writeBlockMap(root, "snow", prevSnow);
            }

            Path file = dir.resolve(nextFilename("biome"));
            NbtIo.writeCompressed(root, file);
            pruneOld(dir, "biome-");
        } catch (IOException ex) {
            LOG.error("Failed to write biome undo snapshot", ex);
        }
    }

    @Nullable
    public static BiomeUndo popBiome(ServerLevel level, UUID playerId) {
        Path dir = playerDir(level.getServer(), playerId);
        Path latest = findLatest(dir, "biome-");
        if (latest == null) return null;

        try {
            CompoundTag root = NbtIo.readCompressed(latest, net.minecraft.nbt.NbtAccounter.unlimitedHeap());
            ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(root.getString("dim")));
            if (!level.dimension().equals(dim)) {
                return new BiomeUndo(dim, Map.of(), Map.of(), Map.of());
            }

            Registry<Biome> biomeReg = level.registryAccess().registryOrThrow(Registries.BIOME);
            ListTag bpTag = root.getList("bpal", Tag.TAG_STRING);
            List<Holder<Biome>> biomePal = new ArrayList<>(bpTag.size());
            for (int i = 0; i < bpTag.size(); i++) {
                ResourceLocation id = ResourceLocation.parse(bpTag.getString(i));
                Optional<Holder.Reference<Biome>> h = biomeReg.getHolder(ResourceKey.create(Registries.BIOME, id));
                biomePal.add(h.orElse(null));
            }

            Map<Long, List<QuartEntry>> quarts = new HashMap<>();
            ListTag qChunks = root.getList("quarts", Tag.TAG_COMPOUND);
            for (int i = 0; i < qChunks.size(); i++) {
                CompoundTag ct = qChunks.getCompound(i);
                long cKey = ct.getLong("c");
                int[] xs = ct.getIntArray("x");
                int[] ys = ct.getIntArray("y");
                int[] zs = ct.getIntArray("z");
                int[] bs = ct.getIntArray("b");
                List<QuartEntry> list = new ArrayList<>(xs.length);
                for (int j = 0; j < xs.length; j++) {
                    Holder<Biome> h = (bs[j] >= 0 && bs[j] < biomePal.size()) ? biomePal.get(bs[j]) : null;
                    if (h != null) list.add(new QuartEntry(xs[j], ys[j], zs[j], h));
                }
                if (!list.isEmpty()) quarts.put(cKey, list);
            }

            Map<Long, ExactBiomes.ChunkSnapshot> prevExact = new HashMap<>();
            ListTag exactList = root.getList("exact", Tag.TAG_COMPOUND);
            for (int i = 0; i < exactList.size(); i++) {
                CompoundTag ct = exactList.getCompound(i);
                long cKey = ct.getLong("c");
                if (!ct.contains("p", Tag.TAG_LIST)) {
                    prevExact.put(cKey, new ExactBiomes.ChunkSnapshot(new ResourceLocation[0], null, null));
                    continue;
                }
                ListTag pTag = ct.getList("p", Tag.TAG_STRING);
                ResourceLocation[] pal = new ResourceLocation[pTag.size()];
                for (int j = 0; j < pTag.size(); j++) pal[j] = ResourceLocation.parse(pTag.getString(j));
                short[] cols = null;
                if (ct.contains("col", Tag.TAG_INT_ARRAY)) {
                    int[] ci = ct.getIntArray("col");
                    if (ci.length == 256) {
                        cols = new short[256];
                        for (int j = 0; j < 256; j++) cols[j] = (short) ci[j];
                    }
                }
                short[] cmy = null;
                if (ct.contains("cmy", Tag.TAG_INT_ARRAY)) {
                    int[] mi = ct.getIntArray("cmy");
                    if (mi.length == 256) {
                        cmy = new short[256];
                        for (int j = 0; j < 256; j++) cmy[j] = (short) mi[j];
                    }
                }
                Int2IntOpenHashMap blocks = null;
                if (ct.contains("bk", Tag.TAG_INT_ARRAY) && ct.contains("bv", Tag.TAG_INT_ARRAY)) {
                    int[] bk = ct.getIntArray("bk");
                    int[] bv = ct.getIntArray("bv");
                    int n = Math.min(bk.length, bv.length);
                    if (n > 0) {
                        blocks = new Int2IntOpenHashMap(n);
                        blocks.defaultReturnValue(0);
                        for (int j = 0; j < n; j++) blocks.put(bk[j], bv[j]);
                    }
                }
                prevExact.put(cKey, new ExactBiomes.ChunkSnapshot(pal, cols, cmy, blocks));
            }

            Map<BlockPos, BlockState> prevSnow = root.contains("snow", Tag.TAG_COMPOUND)
                    ? readBlockMap(root, "snow", level.holderLookup(Registries.BLOCK))
                    : Map.of();

            Files.deleteIfExists(latest);
            return new BiomeUndo(dim, quarts, prevExact, prevSnow);
        } catch (IOException ex) {
            LOG.error("Failed to read biome undo snapshot {}", latest, ex);
            return null;
        }
    }

    private static void writeBlockMap(CompoundTag parent, String key, Map<BlockPos, BlockState> map) {
        CompoundTag tag = new CompoundTag();
        List<BlockState> pal = new ArrayList<>();
        Map<BlockState, Integer> palIdx = new HashMap<>();
        long[] positions = new long[map.size()];
        int[] states = new int[map.size()];
        int i = 0;
        for (Map.Entry<BlockPos, BlockState> e : map.entrySet()) {
            positions[i] = e.getKey().asLong();
            states[i] = palIdx.computeIfAbsent(e.getValue(), s -> {
                pal.add(s);
                return pal.size() - 1;
            });
            i++;
        }
        ListTag palTag = new ListTag();
        for (BlockState st : pal) palTag.add(NbtUtils.writeBlockState(st));
        tag.put("pal", palTag);
        tag.putLongArray("pos", positions);
        tag.putIntArray("st", states);
        parent.put(key, tag);
    }

    private static Map<BlockPos, BlockState> readBlockMap(CompoundTag parent, String key, HolderLookup<net.minecraft.world.level.block.Block> lookup) {
        CompoundTag tag = parent.getCompound(key);
        ListTag palTag = tag.getList("pal", Tag.TAG_COMPOUND);
        List<BlockState> pal = new ArrayList<>(palTag.size());
        for (int i = 0; i < palTag.size(); i++) {
            pal.add(NbtUtils.readBlockState(lookup, palTag.getCompound(i)));
        }
        long[] positions = tag.getLongArray("pos");
        int[] states = tag.getIntArray("st");
        int n = Math.min(positions.length, states.length);
        Map<BlockPos, BlockState> map = new HashMap<>(n);
        for (int i = 0; i < n; i++) {
            int si = states[i];
            if (si >= 0 && si < pal.size()) {
                map.put(BlockPos.of(positions[i]), pal.get(si));
            }
        }
        return map;
    }

    private static String nextFilename(String prefix) {
        return String.format("%s-%016d-%06d.undo.gz", prefix, System.currentTimeMillis(), SEQ.incrementAndGet() % 1_000_000);
    }

    @Nullable
    private static Path findLatest(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) return null;
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".undo.gz"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElse(null);
        } catch (IOException ex) {
            return null;
        }
    }

    private static void pruneOld(Path dir, String prefix) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.list(dir)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.getFileName().toString().endsWith(".undo.gz"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
            int excess = files.size() - MAX_UNDO;
            for (int i = 0; i < excess; i++) {
                Files.deleteIfExists(files.get(i));
            }
        } catch (IOException ignored) {
        }
    }

    private TerraformUndoStore() {
    }
}
