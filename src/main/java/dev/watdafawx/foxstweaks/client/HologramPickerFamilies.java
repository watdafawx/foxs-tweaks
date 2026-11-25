package dev.watdafawx.foxstweaks.client;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups blocks by the words in their registry path, with no per-mod knowledge.
 *
 * <p>{@code light_blue_stained_glass} is the tokens {@code light, blue, stained, glass}. Two blocks share a
 * <b>shape</b> when their paths end in the same word ({@code oak_stairs}, {@code spruce_stairs}), and share a
 * <b>material</b> when they begin with the same words ({@code light_blue_wool}, {@code light_blue_concrete}).
 * That covers every colour, wood and stone family of vanilla and of any mod that names blocks the same way.
 */
final class HologramPickerFamilies {
    private static final int MIN_MATERIAL_MEMBERS = 5;
    private static final int MAX_RESULTS = 400;

    private static final Map<String, List<Block>> BY_SUFFIX = new HashMap<>();
    private static final Map<String, List<Block>> BY_PREFIX = new HashMap<>();
    private static int indexedFor = -1;

    /** Blocks that end in the same last word, closest relatives (most shared trailing words) first. */
    static List<Block> sameShape(Block block, List<Block> all) {
        ensureIndex(all);
        String[] tokens = tokens(block);
        List<Block> candidates = BY_SUFFIX.get(tokens[tokens.length - 1]);
        if (candidates == null)
            return List.of();

        List<Block> out = new ArrayList<>();
        for (Block other : candidates) {
            if (other != block)
                out.add(other);
        }
        out.sort(Comparator
                .comparingInt((Block other) -> -sharedTrailing(tokens, tokens(other)))
                .thenComparing(other -> BuiltInRegistries.BLOCK.getKey(other).toString()));
        return out.size() > MAX_RESULTS ? out.subList(0, MAX_RESULTS) : out;
    }

    /** Blocks that start with the same words, using the longest start shared by enough blocks. */
    static List<Block> sameMaterial(Block block, List<Block> all) {
        ensureIndex(all);
        String[] tokens = tokens(block);
        for (int length = tokens.length - 1; length >= 1; length--) {
            List<Block> candidates = BY_PREFIX.get(join(tokens, 0, length));
            if (candidates == null)
                continue;
            List<Block> out = new ArrayList<>();
            for (Block other : candidates) {
                if (other != block)
                    out.add(other);
            }
            if (out.size() + 1 < MIN_MATERIAL_MEMBERS)
                continue;
            out.sort(Comparator.comparing(other -> BuiltInRegistries.BLOCK.getKey(other).toString()));
            return out.size() > MAX_RESULTS ? out.subList(0, MAX_RESULTS) : out;
        }
        return List.of();
    }

    /**
     * Everything but the shape word: {@code dark_oak} for {@code dark_oak_stairs}. Dropping only the words shared
     * with the block it is compared to would call it just {@code dark} next to {@code oak_stairs}.
     */
    static String label(Block block, Block relativeTo) {
        String[] mine = tokens(block);
        if (mine.length <= 1)
            return mine.length == 0 ? "" : mine[0];
        return join(mine, 0, mine.length - 1);
    }

    /** What follows the shared start of the path: {@code planks} for {@code oak_planks} next to {@code oak_stairs}. */
    static String tail(Block block, Block relativeTo) {
        String[] mine = tokens(block);
        String[] theirs = tokens(relativeTo);
        int shared = 0;
        while (shared < mine.length - 1 && shared < theirs.length && mine[shared].equals(theirs[shared]))
            shared++;
        return join(mine, shared, mine.length);
    }

    private static void ensureIndex(List<Block> all) {
        if (indexedFor == all.size())
            return;
        indexedFor = all.size();
        BY_SUFFIX.clear();
        BY_PREFIX.clear();
        for (Block block : all) {
            String[] tokens = tokens(block);
            // The last word alone is the key a lookup uses; the whole path counts as its own suffix so a plain
            // "glass" joins the family of "tinted_glass".
            BY_SUFFIX.computeIfAbsent(tokens[tokens.length - 1], k -> new ArrayList<>()).add(block);
            for (int length = 1; length <= tokens.length; length++)
                BY_PREFIX.computeIfAbsent(join(tokens, 0, length), k -> new ArrayList<>()).add(block);
        }
    }

    private static String[] tokens(Block block) {
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(block);
        String[] tokens = key.getPath().split("_");
        return tokens.length == 0 ? new String[] { key.getPath() } : tokens;
    }

    private static int sharedTrailing(String[] a, String[] b) {
        int shared = 0;
        while (shared < a.length && shared < b.length
                && a[a.length - 1 - shared].equals(b[b.length - 1 - shared]))
            shared++;
        return shared;
    }

    private static String join(String[] tokens, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (i > from)
                sb.append('_');
            sb.append(tokens[i]);
        }
        return sb.toString();
    }

    private HologramPickerFamilies() {
    }
}
