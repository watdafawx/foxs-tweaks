package dev.watdafawx.foxstweaks.compat;

import com.mojang.authlib.GameProfile;
import dev.shadowsoffire.apotheosis.Apoth;
import dev.shadowsoffire.apotheosis.tiers.WorldTier;
import dev.watdafawx.foxstweaks.anchor.SpawnAnchorData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The only Apotheosis-aware part of the spawn anchor tier support: turns an anchor into a stand-in
 * {@link Player} that carries its world tier. Reached only through {@link dev.watdafawx.foxstweaks.anchor.AnchorTier},
 * which checks {@code ModList.isLoaded("apotheosis")} first, so no Apotheosis type is ever loaded on a
 * pack without it.
 *
 * <p>Apotheosis resolves a tier through {@link WorldTier#getTier(Player)}, which for a fake player
 * looks up the real player with the same UUID first: while the owner is online their <em>live</em>
 * tier is used, and only when they are offline does the attachment set here matter. That is why the
 * profile carries the owner's UUID rather than the anchor's.
 */
public final class ApothicAnchorTier {

    private static final String PROFILE_NAME = "[Fox's Tweaks Anchor]";

    /** The tier named by {@code name}, or {@code null} if it is not one of Apotheosis' tiers. */
    @Nullable
    public static WorldTier parse(String name) {
        for (WorldTier tier : WorldTier.values()) {
            if (tier.getSerializedName().equalsIgnoreCase(name))
                return tier;
        }
        return null;
    }

    /** The tier name of a live player, or {@code ""} if nothing sensible can be read. */
    public static String tierNameOf(Player player) {
        try {
            return WorldTier.getTier(player).getSerializedName();
        }
        catch (RuntimeException | LinkageError ex) {
            return "";
        }
    }

    /** The canonical spelling of a tier name, or {@code ""} when this Apotheosis has no such tier. */
    public static String canonical(String name) {
        WorldTier tier = parse(name);
        return tier == null ? "" : tier.getSerializedName();
    }

    /**
     * A stand-in player standing at {@code pos} that reports this anchor's tier. Never added to the
     * level; NeoForge caches one per (level, profile), so repeated calls are cheap.
     *
     * @return {@code null} when the anchor names no tier this Apotheosis version knows
     */
    @Nullable
    public static Player forAnchor(ServerLevel level, SpawnAnchorData.Anchor anchor, Vec3 pos) {
        WorldTier fixed = parse(anchor.tier());
        if (fixed == null)
            return null;

        UUID owner = anchor.owner();
        ServerPlayer online = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);

        // Following the owner means using their UUID, because WorldTier.getTier(FakePlayer) looks the
        // real player up by UUID and overwrites whatever is stored here. A tier pinned with
        // /foxstweaks anchor tier therefore gets an anchor-derived UUID: otherwise a pinned Pinnacle
        // would silently be replaced by an online owner's Haven.
        boolean follow = anchor.followOwner() && owner != null;
        GameProfile profile = new GameProfile(follow ? owner : offlineId(anchor), PROFILE_NAME);
        FakePlayer stand = FakePlayerFactory.get(level, profile);

        // Sit exactly on the spawn so Apotheosis' own distance checks (boss "canSpawn", and the
        // 8-block vertical slice test for underground spawns) pass instead of rejecting the spawn.
        stand.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);

        // AdventureConfig.bossAutoAggro makes a boss target "the player"; targeting something that is
        // not in the level leaves it chasing nothing. Creative short-circuits that branch.
        stand.getAbilities().instabuild = true;

        WorldTier tier = follow && online != null ? WorldTier.getTier(online) : fixed;
        stand.setData(Apoth.Attachments.WORLD_TIER, tier);

        // Share the owner's boss cooldown instead of handing every anchor a fresh one, so an offline
        // farm cannot spawn invaders faster than the owner could while standing there.
        if (online != null)
            stand.setData(Apoth.Attachments.INVADER_COOLDOWN, online.getData(Apoth.Attachments.INVADER_COOLDOWN));

        return stand;
    }

    /** A stable UUID for an anchor nobody owns, so its stand-in player is not shared with any other. */
    private static UUID offlineId(SpawnAnchorData.Anchor anchor) {
        return UUID.nameUUIDFromBytes(("foxstweaks:anchor:" + anchor.name()).getBytes(StandardCharsets.UTF_8));
    }

    private ApothicAnchorTier() {
    }
}
