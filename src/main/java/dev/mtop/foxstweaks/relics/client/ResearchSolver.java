package dev.mtop.foxstweaks.relics.client;

import com.google.common.collect.Multimap;
import it.hurts.sskirillss.relics.api.relics.IRelicItem;
import it.hurts.sskirillss.relics.api.relics.data.AbilityData;
import it.hurts.sskirillss.relics.client.screen.description.research.AbilityResearchScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Works out which links have to change to turn the player's current scribble into the
 * constellation the relic actually wants.
 *
 * <p>The answer is not hidden from the client: a relic declares its constellation in its ability
 * template, which is part of the item definition and therefore present on both sides. That is how
 * Relics' own hint button knows which line to reveal. Reading it from {@link IRelicItem} rather
 * than from a table of known relics means relics added by Relics itself, by an addon, or by a
 * future update are all handled the same way with no per-relic data.
 */
public final class ResearchSolver {
    private ResearchSolver() {
    }

    /** The link edits that turn the current pattern into the correct one. */
    public record Plan(List<Pair<Integer, Integer>> toRemove, List<Pair<Integer, Integer>> toAdd) {
        public static final Plan NOTHING = new Plan(List.of(), List.of());

        public boolean isEmpty() {
            return toRemove.isEmpty() && toAdd.isEmpty();
        }
    }

    @Nullable
    public static AbilityData abilityData(AbilityResearchScreen screen) {
        Player player = Minecraft.getInstance().player;

        if (player == null || screen.stack == null || !(screen.stack.getItem() instanceof IRelicItem relic))
            return null;

        return relic.getRelicData(player, screen.stack).getAbilitiesData().getAbilityData(screen.ability);
    }

    /** True when this ability has a constellation that is not finished yet. */
    public static boolean isSolvable(AbilityResearchScreen screen) {
        AbilityData data = abilityData(screen);

        return data != null
                && data.getTemplate() != null
                && !data.getTemplate().getResearchTemplate().getStars().isEmpty()
                && !data.getResearchData().isResearched();
    }

    public static Plan plan(AbilityResearchScreen screen) {
        AbilityData data = abilityData(screen);

        if (data == null || data.getTemplate() == null)
            return Plan.NOTHING;

        Multimap<Integer, Integer> schema = data.getTemplate().getResearchTemplate().getLinks();
        Multimap<Integer, Integer> current = data.getResearchData().getLinks();

        Set<Pair<Integer, Integer>> wanted = new LinkedHashSet<>();

        for (var entry : schema.entries())
            wanted.add(undirected(entry.getKey(), entry.getValue()));

        List<Pair<Integer, Integer>> toRemove = new ArrayList<>();
        Set<Pair<Integer, Integer>> keeping = new HashSet<>();

        for (var entry : current.entries()) {
            Pair<Integer, Integer> link = undirected(entry.getKey(), entry.getValue());

            // Drop links that are not part of the answer, and any second copy of one that is:
            // the stored links are a list multimap, so the same pair can legitimately appear twice.
            // Removal is sent in the stored orientation because the server removes an exact entry.
            if (!wanted.contains(link) || !keeping.add(link))
                toRemove.add(Pair.of(entry.getKey(), entry.getValue()));
        }

        List<Pair<Integer, Integer>> toAdd = new ArrayList<>();

        for (var entry : schema.entries())
            if (!keeping.contains(undirected(entry.getKey(), entry.getValue())))
                toAdd.add(Pair.of(entry.getKey(), entry.getValue()));

        return new Plan(toRemove, toAdd);
    }

    /**
     * Sends the plan through the screen's own link methods, so the packets, the particle trails and
     * the sounds are identical to drawing every line by hand. Wrong links go first: Relics marks the
     * ability researched on whichever added link completes the pattern, so the additions must come last.
     */
    public static void apply(AbilityResearchScreen screen) {
        Plan plan = plan(screen);

        for (var link : plan.toRemove())
            screen.removeLink(link.getLeft(), link.getRight());

        for (var link : plan.toAdd())
            screen.addLink(link.getLeft(), link.getRight());
    }

    /** Links are stored in either orientation but mean the same edge, so compare them normalised. */
    private static Pair<Integer, Integer> undirected(int first, int second) {
        return first <= second ? Pair.of(first, second) : Pair.of(second, first);
    }
}
