package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compact, self-contained block-structure payload stored inside a {@link HologramLayer.Kind#STRUCTURE}
 * layer's {@code data} tag.
 *
 * <p>Stores a palette of block states (registry id + non-default property strings, resolved through
 * {@link HologramStates}), a compact index array (dense byte grid when small, sparse position+index
 * arrays otherwise), and optional per-position block entity tags. Air is never stored.
 */
public final class HologramStructure {

    public record PaletteEntry(String id, Map<String, String> props) {
        public PaletteEntry {
            id = id == null ? "" : id;
            props = props == null || props.isEmpty() ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(props));
        }

        @Nullable
        public BlockState resolve() {
            return HologramStates.resolve(this.id, this.props);
        }
    }

    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final List<PaletteEntry> palette;
    /** Sorted packed positions {@code (y * sizeZ + z) * sizeX + x} for every non-air block. */
    private final int[] positions;
    /** Palette index matching each entry of {@link #positions}. */
    private final int[] paletteIndices;
    /** Optional block-entity tags keyed by packed position index. */
    private final Map<Integer, CompoundTag> blockEntities;

    public HologramStructure(int sizeX, int sizeY, int sizeZ,
                             List<PaletteEntry> palette,
                             int[] positions,
                             int[] paletteIndices,
                             Map<Integer, CompoundTag> blockEntities) {
        this.sizeX = Math.max(1, sizeX);
        this.sizeY = Math.max(1, sizeY);
        this.sizeZ = Math.max(1, sizeZ);
        this.palette = List.copyOf(palette);
        this.positions = positions.clone();
        this.paletteIndices = paletteIndices.clone();
        if (blockEntities == null || blockEntities.isEmpty()) {
            this.blockEntities = Map.of();
        } else {
            Map<Integer, CompoundTag> copy = new LinkedHashMap<>(blockEntities.size());
            for (var e : blockEntities.entrySet()) {
                if (e.getValue() != null && !e.getValue().isEmpty())
                    copy.put(e.getKey(), e.getValue().copy());
            }
            this.blockEntities = Collections.unmodifiableMap(copy);
        }
    }

    public int sizeX() {
        return this.sizeX;
    }

    public int sizeY() {
        return this.sizeY;
    }

    public int sizeZ() {
        return this.sizeZ;
    }

    public int blockCount() {
        return this.positions.length;
    }

    public int blockEntityCount() {
        return this.blockEntities.size();
    }

    public List<PaletteEntry> palette() {
        return this.palette;
    }

    public int positionAt(int i) {
        return this.positions[i];
    }

    public int paletteIndexAt(int i) {
        return this.paletteIndices[i];
    }

    public Map<Integer, CompoundTag> blockEntities() {
        return this.blockEntities;
    }

    @Nullable
    public CompoundTag blockEntityAt(int posIdx) {
        return this.blockEntities.get(posIdx);
    }

    public int packPos(int x, int y, int z) {
        return (y * this.sizeZ + z) * this.sizeX + x;
    }

    public int unpackX(int posIdx) {
        return posIdx % this.sizeX;
    }

    public int unpackZ(int posIdx) {
        return (posIdx / this.sizeX) % this.sizeZ;
    }

    public int unpackY(int posIdx) {
        return posIdx / (this.sizeX * this.sizeZ);
    }

    public String displayName() {
        return "Structure " + this.sizeX + "x" + this.sizeY + "x" + this.sizeZ;
    }

    /** Resolves each palette entry once into a {@link BlockState} array (air if a mod block is missing). */
    public BlockState[] resolvePalette() {
        BlockState[] states = new BlockState[this.palette.size()];
        for (int i = 0; i < this.palette.size(); i++) {
            BlockState resolved = this.palette.get(i).resolve();
            states[i] = resolved != null ? resolved : Blocks.AIR.defaultBlockState();
        }
        return states;
    }

    /** Fast O(1) metadata check without decoding the block arrays. */
    public static boolean isValidTag(@Nullable CompoundTag tag) {
        return tag != null
                && tag.getInt("w") > 0
                && tag.getInt("h") > 0
                && tag.getInt("d") > 0
                && tag.getInt("count") > 0;
    }

    /** Fast O(1) dimensions lookup from a layer's data tag: {@code [w, h, d]}. */
    public static int[] dimensions(@Nullable CompoundTag tag) {
        if (tag == null)
            return new int[] { 1, 1, 1 };
        int w = Math.max(1, tag.getInt("w"));
        int h = Math.max(1, tag.getInt("h"));
        int d = Math.max(1, tag.getInt("d"));
        return new int[] { w, h, d };
    }

    public static int blockCount(@Nullable CompoundTag tag) {
        return tag == null ? 0 : Math.max(0, tag.getInt("count"));
    }

    public static int blockEntityCount(@Nullable CompoundTag tag) {
        if (tag == null || !tag.contains("be", Tag.TAG_LIST))
            return 0;
        return tag.getList("be", Tag.TAG_COMPOUND).size();
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("w", this.sizeX);
        tag.putInt("h", this.sizeY);
        tag.putInt("d", this.sizeZ);
        tag.putInt("count", this.positions.length);

        ListTag palList = new ListTag();
        for (PaletteEntry entry : this.palette) {
            CompoundTag pt = new CompoundTag();
            pt.putString("id", entry.id());
            if (!entry.props().isEmpty()) {
                CompoundTag propsTag = new CompoundTag();
                entry.props().forEach(propsTag::putString);
                pt.put("props", propsTag);
            }
            palList.add(pt);
        }
        tag.put("palette", palList);

        long volume = (long) this.sizeX * this.sizeY * this.sizeZ;
        if (this.palette.size() <= 255 && volume > 0 && volume <= (long) this.positions.length * 4L && volume <= 1_000_000L) {
            byte[] grid = new byte[(int) volume];
            for (int i = 0; i < this.positions.length; i++) {
                int pos = this.positions[i];
                if (pos >= 0 && pos < grid.length)
                    grid[pos] = (byte) (this.paletteIndices[i] + 1);
            }
            tag.putByteArray("grid", grid);
        } else {
            tag.putIntArray("pos", this.positions);
            if (this.palette.size() <= 256) {
                byte[] palB = new byte[this.paletteIndices.length];
                for (int i = 0; i < this.paletteIndices.length; i++)
                    palB[i] = (byte) this.paletteIndices[i];
                tag.putByteArray("palB", palB);
            } else {
                tag.putIntArray("pal", this.paletteIndices);
            }
        }

        if (!this.blockEntities.isEmpty()) {
            ListTag beList = new ListTag();
            for (var e : this.blockEntities.entrySet()) {
                CompoundTag bt = new CompoundTag();
                bt.putInt("p", e.getKey());
                bt.put("tag", e.getValue().copy());
                beList.add(bt);
            }
            tag.put("be", beList);
        }
        return tag;
    }

    @Nullable
    public static HologramStructure fromTag(@Nullable CompoundTag tag) {
        if (tag == null)
            return null;
        int w = tag.getInt("w");
        int h = tag.getInt("h");
        int d = tag.getInt("d");
        if (w <= 0 || h <= 0 || d <= 0 || w > 128 || h > 128 || d > 128)
            return null;
        long volumeL = (long) w * h * d;
        if (volumeL <= 0 || volumeL > 2_097_152L)
            return null;
        int volume = (int) volumeL;

        ListTag palList = tag.getList("palette", Tag.TAG_COMPOUND);
        if (palList.isEmpty() || palList.size() > volume)
            return null;
        List<PaletteEntry> palette = new ArrayList<>(palList.size());
        for (int i = 0; i < palList.size(); i++) {
            CompoundTag pt = palList.getCompound(i);
            String id = pt.getString("id");
            Map<String, String> props = Map.of();
            if (pt.contains("props", Tag.TAG_COMPOUND)) {
                CompoundTag raw = pt.getCompound("props");
                Map<String, String> parsed = new LinkedHashMap<>();
                for (String k : raw.getAllKeys())
                    parsed.put(k, raw.getString(k));
                props = parsed;
            }
            palette.add(new PaletteEntry(id, props));
        }
        int palSize = palette.size();

        int[] positions;
        int[] paletteIndices;
        if (tag.contains("grid", Tag.TAG_BYTE_ARRAY)) {
            byte[] grid = tag.getByteArray("grid");
            if (grid.length != volume)
                return null;
            int len = Math.min(grid.length, volume);
            int nonAir = 0;
            for (int i = 0; i < len; i++) {
                int v = grid[i] & 0xFF;
                if (v > palSize)
                    return null;
                if (v > 0 && v <= palSize)
                    nonAir++;
            }
            if (nonAir == 0)
                return null;
            positions = new int[nonAir];
            paletteIndices = new int[nonAir];
            int idx = 0;
            for (int i = 0; i < len; i++) {
                int v = grid[i] & 0xFF;
                if (v > 0 && v <= palSize) {
                    positions[idx] = i;
                    paletteIndices[idx] = v - 1;
                    idx++;
                }
            }
        } else if (tag.contains("pos", Tag.TAG_INT_ARRAY)) {
            int[] rawPos = tag.getIntArray("pos");
            int[] rawPal;
            if (tag.contains("palB", Tag.TAG_BYTE_ARRAY)) {
                byte[] palB = tag.getByteArray("palB");
                rawPal = new int[palB.length];
                for (int i = 0; i < palB.length; i++)
                    rawPal[i] = palB[i] & 0xFF;
            } else if (tag.contains("pal", Tag.TAG_INT_ARRAY)) {
                rawPal = tag.getIntArray("pal");
            } else {
                return null;
            }
            if (rawPos.length == 0 || rawPos.length != rawPal.length || rawPos.length > volume)
                return null;
            for (int i = 0; i < rawPos.length; i++) {
                if (rawPos[i] < 0 || rawPos[i] >= volume || rawPal[i] < 0 || rawPal[i] >= palSize
                        || (i > 0 && rawPos[i] <= rawPos[i - 1]))
                    return null;
            }
            positions = rawPos;
            paletteIndices = rawPal;
        } else {
            return null;
        }

        if (tag.getInt("count") != positions.length)
            return null;
        Map<Integer, CompoundTag> bes = new LinkedHashMap<>();
        if (tag.contains("be", Tag.TAG_LIST)) {
            ListTag beList = tag.getList("be", Tag.TAG_COMPOUND);
            for (int i = 0; i < beList.size(); i++) {
                CompoundTag bt = beList.getCompound(i);
                int p = bt.getInt("p");
                if (Arrays.binarySearch(positions, p) < 0 || bes.containsKey(p))
                    return null;
                if (bt.contains("tag", Tag.TAG_COMPOUND)) {
                    CompoundTag inner = bt.getCompound("tag");
                    if (!inner.isEmpty())
                        bes.put(p, inner);
                }
            }
        }

        return new HologramStructure(w, h, d, palette, positions, paletteIndices, bes);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof HologramStructure other))
            return false;
        return this.sizeX == other.sizeX
                && this.sizeY == other.sizeY
                && this.sizeZ == other.sizeZ
                && this.palette.equals(other.palette)
                && Arrays.equals(this.positions, other.positions)
                && Arrays.equals(this.paletteIndices, other.paletteIndices)
                && this.blockEntities.equals(other.blockEntities);
    }

    @Override
    public int hashCode() {
        int r = Objects.hash(this.sizeX, this.sizeY, this.sizeZ, this.palette, this.blockEntities);
        r = 31 * r + Arrays.hashCode(this.positions);
        r = 31 * r + Arrays.hashCode(this.paletteIndices);
        return r;
    }
}
