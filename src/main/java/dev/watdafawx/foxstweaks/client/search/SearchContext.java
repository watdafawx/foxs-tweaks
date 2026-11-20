package dev.watdafawx.foxstweaks.client.search;

import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.TreeSet;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.client.compat.Ae2SearchContext;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.neoforged.fml.ModList;

/**
 * What the open screen can actually show: names of the items in an AE2 terminal's network, split into stock
 * and auto-craftable. {@link SearchVocab} tries these before the whole game's vocabulary, so typing "ing" in
 * a terminal completes to what the network holds, not to the first ingot alphabetically. Render thread only.
 * Holds no AE2 type; {@link Ae2SearchContext} (reached only with AE2 loaded) fills it.
 */
public final class SearchContext {
    private static final String[] NONE = new String[0];

    /** Sorted lower-case names with stock in the network. */
    private static String[] stored = NONE;
    /** Sorted lower-case names the network can craft but holds none of. */
    private static String[] craftable = NONE;
    /** Sorted lower-case words of all of the above, for word completion and spelling. */
    private static String[] words = NONE;

    private SearchContext() {
    }

    public static boolean active() {
        return words.length > 0;
    }

    /** Re-reads the open screen when it is a known storage terminal; clears the context otherwise. */
    public static void refresh() {
        if (!Config.enabled(Config.SEARCH_ASSIST_CONTEXT)) {
            set(NONE, NONE);
            return;
        }
        if (ModList.get() == null || !ModList.get().isLoaded("ae2")) {
            return;
        }
        try {
            Ae2SearchContext.refresh(Minecraft.getInstance().screen);
        } catch (RuntimeException | LinkageError e) {
            set(NONE, NONE); // an AE2 update moved something: lose the feature, keep the box working
        }
    }

    /** Called by the readers; names need not be lower-cased or sorted. */
    public static void set(Collection<String> storedNames, Collection<String> craftableNames) {
        stored = normalise(storedNames, null);
        craftable = normalise(craftableNames, stored);
        TreeSet<String> w = new TreeSet<>();
        for (String[] list : new String[][] { stored, craftable }) {
            for (String name : list) {
                for (String part : name.split("[^\\p{L}\\p{N}]+")) {
                    if (part.length() >= 3) {
                        w.add(part);
                    }
                }
            }
        }
        words = w.toArray(NONE);
    }

    private static void set(String[] none, String[] none2) {
        stored = none;
        craftable = none2;
        words = NONE;
    }

    private static String[] normalise(Collection<String> raw, String[] skip) {
        TreeSet<String> out = new TreeSet<>();
        for (String s : raw) {
            String name = ChatFormatting.stripFormatting(s);
            if (name == null) {
                continue;
            }
            name = name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
            if (name.length() >= 2 && (skip == null || Arrays.binarySearch(skip, name) < 0)) {
                out.add(name);
            }
        }
        return out.toArray(NONE);
    }

    /** Shortest name starting with {@code prefix}: stock first, then craftable. Null when none. */
    static String complete(String prefix) {
        String best = shortest(stored, prefix);
        return best != null ? best : shortest(craftable, prefix);
    }

    private static String shortest(String[] sorted, String prefix) {
        int at = Arrays.binarySearch(sorted, prefix);
        int from = at >= 0 ? at : -at - 1;
        String best = null;
        for (int i = from; i < sorted.length && sorted[i].startsWith(prefix); i++) {
            if (sorted[i].length() > prefix.length() && (best == null || sorted[i].length() < best.length())) {
                best = sorted[i];
            }
        }
        return best;
    }

    /** The first context word that {@code token} starts, minus {@code token}; null when none. */
    static String completeWord(String token) {
        int at = Arrays.binarySearch(words, token);
        int from = at >= 0 ? at : -at - 1;
        for (int i = from; i < words.length && words[i].startsWith(token); i++) {
            if (words[i].length() > token.length()) {
                return words[i].substring(token.length());
            }
        }
        return null;
    }

    static boolean knows(String word) {
        return Arrays.binarySearch(words, word) >= 0;
    }

    /** Closest context word within {@code max} edits, or null. */
    static String closest(String word, int max) {
        String best = null;
        int bestDist = max + 1;
        for (String candidate : words) {
            if (candidate.charAt(0) != word.charAt(0) || Math.abs(candidate.length() - word.length()) > max) {
                continue;
            }
            int d = SearchVocab.distance(word, candidate, max);
            if (d < bestDist) {
                best = candidate;
                bestDist = d;
            }
        }
        return best;
    }
}
