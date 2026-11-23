package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.compat.ApothicAnchorTier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Answers "which world tier does a mob spawning here see?" for spawn anchors.
 *
 * <p>Apotheosis takes the tier from the nearest player in the dimension, so a loaded farm with nobody
 * around either gets nothing at all (no player in that dimension) or the wrong player's tier, and its
 * invader (boss) spawns are rejected as too far from that player. Handing Apotheosis a stand-in player
 * that carries the anchor's tier makes a loaded farm behave as if its owner were standing there.
 *
 * <p>This class holds no Apotheosis type itself: {@link ApothicAnchorTier} does, and is only reached
 * once {@link #loaded()} says Apotheosis is installed.
 */
public final class AnchorTier {
    /** Dev-check mutation: {@code -Dfoxstweaks.anchorTier.off=true} must make every tier assertion fail. */
    private static final boolean FORCED_OFF = Boolean.getBoolean("foxstweaks.anchorTier.off");
    private static Boolean apotheosisLoaded;
    private static int replacements;

    /** How many spawn lookups this session has answered with an anchor's stand-in player. */
    public static int replacements() {
        return replacements;
    }

    /** True when Apotheosis is installed; cached, since this is asked on every natural spawn. */
    public static boolean loaded() {
        Boolean known = apotheosisLoaded;
        if (known == null) {
            known = ModList.get().isLoaded("apotheosis");
            apotheosisLoaded = known;
        }
        return known;
    }

    /**
     * The tier name of a player about to place an anchor, or {@code ""} when there is nothing to
     * capture (no Apotheosis, or a command run from the console).
     */
    public static String tierNameOf(@Nullable Player player) {
        if (player == null || !loaded())
            return "";
        return ApothicAnchorTier.tierNameOf(player);
    }

    /** The canonical spelling of a tier name, or {@code ""} when it is not one Apotheosis knows. */
    public static String normaliseTier(String name) {
        return loaded() ? ApothicAnchorTier.canonical(name) : "";
    }

    /**
     * Parts of the Apotheosis integration that no longer fit the installed build, or an empty list when the
     * hooks should still apply. Used at server start to warn instead of failing silently.
     */
    public static java.util.List<String> probe() {
        if (!loaded())
            return java.util.List.of("Apotheosis is not installed");
        // Dev-check mutation, like FORCED_OFF: proves the warning and the join message actually render
        // without waiting for a real Apotheosis update to break something.
        if (Boolean.getBoolean("foxstweaks.anchorTier.fakeBreak"))
            return java.util.List.of("simulated: ApothMobEvents#finalizeMobSpawns changed or moved");
        return dev.watdafawx.foxstweaks.compat.ApothicHookProbe.problems();
    }

    /** The installed Apotheosis version, for the warning text. */
    public static String apotheosisVersion() {
        return ModList.get().getModContainerById("apotheosis")
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("unknown");
    }

    /**
     * The player a mob spawn at {@code pos} should be judged against: the real one when they are
     * close enough to the spawn to be the one that would have counted anyway, else the anchor's
     * stand-in.
     */
    @Nullable
    public static Player replace(@Nullable Player original, ServerLevel level, Vec3 pos) {
        if (!enabled())
            return original;

        if (original != null) {
            int range = ServerConfig.spawnAnchorsTierPlayerRange();
            if (range > 0 && original.distanceToSqr(pos) <= (double) range * range)
                return original;
        }

        SpawnAnchorData.Anchor anchor = SpawnAnchors.nearestTierAnchor(level, pos.x, pos.y, pos.z);
        if (anchor == null)
            return original;

        Player stand = ApothicAnchorTier.forAnchor(level, anchor, pos);
        if (stand != null)
            replacements++;
        return stand != null ? stand : original;
    }

    /**
     * The same, for a mob that recorded its owner's UUID at spawn time and is being resolved later
     * (Apotheosis transforms elites on the entity join, by UUID). Only fills in when the real player
     * cannot be found; a live owner always keeps their own tier.
     */
    @Nullable
    public static Player replaceOwner(@Nullable Player original, ServerLevel level, UUID owner) {
        if (original != null || !enabled())
            return original;

        SpawnAnchorData.Anchor anchor = SpawnAnchors.anchorForOwner(level, owner);
        if (anchor == null)
            return original;

        Player stand = ApothicAnchorTier.forAnchor(level, anchor, new Vec3(anchor.x(), anchor.y(), anchor.z()));
        return stand != null ? stand : original;
    }

    private static boolean enabled() {
        return !FORCED_OFF
                && loaded()
                && ServerConfig.spawnAnchorsEnabled()
                && ServerConfig.spawnAnchorsApotheosisTier();
    }

    private AnchorTier() {
    }
}
