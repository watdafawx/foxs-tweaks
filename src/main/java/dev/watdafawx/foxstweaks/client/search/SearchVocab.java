package dev.watdafawx.foxstweaks.client.search;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.neoforged.fml.ModList;

/**
 * Words the player is likely to search for: names of items, mobs, effects and mods, in the current language.
 * Built on first use (and again after a language or resource reload, which makes a new {@link Language}) and
 * used by {@link SearchAssist} for grey autocomplete and spelling suggestions. Render thread only.
 */
public final class SearchVocab {
    private static final int MIN_WORD = 3;
    private static final int SCAN_CAP = 3000;

    /** Sorted lower-case full names, for prefix completion of a whole query. */
    private static String[] phrases = new String[0];
    /** Sorted lower-case words and how many names each appears in. */
    private static String[] words = new String[0];
    private static int[] counts = new int[0];
    private static Language builtFor;

    private static final Map<String, String> CORRECTIONS = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > 512;
        }
    };

    private SearchVocab() {
    }

    private static void ensureBuilt() {
        Language now = Language.getInstance();
        if (now == builtFor) {
            return;
        }
        builtFor = now;
        CORRECTIONS.clear();
        TreeSet<String> names = new TreeSet<>();
        Map<String, Integer> tally = new HashMap<>();
        try {
            BuiltInRegistries.ITEM.forEach(item -> add(names, tally, item.getDescription().getString()));
            BuiltInRegistries.ENTITY_TYPE.forEach(type -> add(names, tally, type.getDescription().getString()));
            BuiltInRegistries.MOB_EFFECT.forEach(effect -> add(names, tally, effect.getDisplayName().getString()));
            if (ModList.get() != null) {
                ModList.get().getMods().forEach(mod -> add(names, tally, mod.getDisplayName()));
            }
        } catch (RuntimeException e) {
            // A broken name from one mod must not take the search box down: keep what was read.
        }
        phrases = names.toArray(new String[0]);
        String[] sorted = tally.keySet().toArray(new String[0]);
        Arrays.sort(sorted);
        words = sorted;
        counts = new int[sorted.length];
        for (int i = 0; i < sorted.length; i++) {
            counts[i] = tally.get(sorted[i]);
        }
    }

    private static void add(TreeSet<String> names, Map<String, Integer> tally, String raw) {
        if (raw == null) {
            return;
        }
        String name = ChatFormatting.stripFormatting(raw);
        if (name == null) {
            return;
        }
        name = name.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        if (name.length() < 2) {
            return;
        }
        names.add(name);
        StringBuilder word = new StringBuilder();
        for (int i = 0; i <= name.length(); i++) {
            char c = i < name.length() ? name.charAt(i) : ' ';
            if (Character.isLetterOrDigit(c)) {
                word.append(c);
            } else {
                if (word.length() >= MIN_WORD) {
                    tally.merge(word.toString(), 1, Integer::sum);
                }
                word.setLength(0);
            }
        }
    }

    private static int lowerBound(String[] sorted, String key) {
        int i = Arrays.binarySearch(sorted, key);
        return i >= 0 ? i : -i - 1;
    }

    /** The text to add after {@code value} to finish a name, or null. */
    public static String complete(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.length() < 2 || lower.endsWith(" ") || !plainText(lower)) {
            return null;
        }
        ensureBuilt();
        String best = null;
        int from = lowerBound(phrases, lower);
        for (int i = from; i < phrases.length && i < from + SCAN_CAP && phrases[i].startsWith(lower); i++) {
            if (phrases[i].length() > lower.length() && (best == null || phrases[i].length() < best.length())) {
                best = phrases[i];
            }
        }
        if (best != null) {
            return best.substring(lower.length());
        }
        String token = lower.substring(lower.lastIndexOf(' ') + 1);
        if (token.length() < 2) {
            return null;
        }
        int bestCount = 0;
        String bestWord = null;
        from = lowerBound(words, token);
        for (int i = from; i < words.length && i < from + SCAN_CAP && words[i].startsWith(token); i++) {
            if (words[i].length() > token.length() && counts[i] > bestCount) {
                bestCount = counts[i];
                bestWord = words[i];
            }
        }
        return bestWord == null ? null : bestWord.substring(token.length());
    }

    /**
     * {@code value} with each misspelled word replaced by the closest known one, or null when nothing needed
     * fixing. Only plain words of 4+ letters are touched: search syntax (@mod, #tag, quotes, slashes, minus),
     * numbers, and a last word that is still a prefix of something are left alone.
     */
    public static String correct(String value) {
        if (value.length() < 4 || !plainText(value)) {
            return null;
        }
        ensureBuilt();
        String[] tokens = value.split(" ", -1);
        boolean changed = false;
        for (int i = 0; i < tokens.length; i++) {
            String fixed = fixWord(tokens[i], i == tokens.length - 1);
            if (fixed != null) {
                tokens[i] = fixed;
                changed = true;
            }
        }
        return changed ? String.join(" ", tokens) : null;
    }

    /** Search syntax anywhere in the text means the box is doing something cleverer than matching names. */
    private static boolean plainText(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\'' && !Character.isLetterOrDigit(c)) {
                return false;
            }
        }
        return true;
    }

    private static String fixWord(String token, boolean last) {
        if (token.length() < 4) {
            return null;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isLetter(token.charAt(i))) {
                return null;
            }
        }
        String lower = token.toLowerCase(Locale.ROOT);
        if (Arrays.binarySearch(words, lower) >= 0) {
            return null;
        }
        if (last) {
            int i = lowerBound(words, lower);
            if (i < words.length && words[i].startsWith(lower)) {
                return null; // still being typed
            }
        }
        String cached = CORRECTIONS.get(lower);
        if (cached == null) {
            cached = closest(lower);
            CORRECTIONS.put(lower, cached == null ? "" : cached);
        }
        if (cached.isEmpty()) {
            return null;
        }
        return Character.isUpperCase(token.charAt(0)) ? Character.toUpperCase(cached.charAt(0)) + cached.substring(1) : cached;
    }

    private static String closest(String word) {
        int max = word.length() >= 8 ? 2 : 1;
        char first = word.charAt(0);
        int from = lowerBound(words, String.valueOf(first));
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        int bestCount = 0;
        for (int i = from; i < words.length && words[i].charAt(0) == first; i++) {
            String candidate = words[i];
            if (Math.abs(candidate.length() - word.length()) > max) {
                continue;
            }
            int d = distance(word, candidate, max);
            if (d <= max && (d < bestDist || (d == bestDist && counts[i] > bestCount))) {
                best = candidate;
                bestDist = d;
                bestCount = counts[i];
            }
        }
        return best;
    }

    /** Optimal string alignment distance (swapping two neighbours costs 1); gives up above {@code max}. */
    static int distance(String a, String b, int max) {
        int n = a.length();
        int m = b.length();
        int[] prev2 = new int[m + 1];
        int[] prev = new int[m + 1];
        int[] cur = new int[m + 1];
        for (int j = 0; j <= m; j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, prev2[j - 2] + 1);
                }
                cur[j] = v;
                rowMin = Math.min(rowMin, v);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] t = prev2;
            prev2 = prev;
            prev = cur;
            cur = t;
        }
        return prev[m];
    }
}
