package dev.watdafawx.foxstweaks.anchor;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reports, on server start and to operators on join, when spawn anchors cannot carry an Apotheosis world
 * tier: the feature is switched off, Apotheosis is missing, or the installed Apotheosis no longer has the
 * internals the hooks patch.
 *
 * <p>That last case is the one this exists for. Every hook is {@code require = 0} so a moved target cannot
 * crash a server, but it also means nothing would otherwise say a word when an Apotheosis update quietly
 * turns the feature off. The warning is repeated once per operator per session, never to ordinary players.
 *
 * <p>Holds no Apotheosis type itself - {@link AnchorTier} does, behind a load check.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class AnchorTierNotice {
    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<UUID> NOTIFIED = new HashSet<>();

    /** What to tell operators, or {@code null} when there is nothing wrong. */
    @Nullable
    private static String warning;

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        NOTIFIED.clear();
        warning = null;

        MinecraftServer server = event.getServer();
        int carrying = anchorsCarryingTier(server);

        if (!ServerConfig.spawnAnchorsEnabled()) {
            // Anchors are off entirely; nothing about their tier matters.
            return;
        }

        if (!ServerConfig.spawnAnchorsApotheosisTier()) {
            if (carrying > 0)
                note("spawnAnchorsApotheosisTier is off, so " + carrying
                        + " anchor(s) carry a world tier that is not used.", false);
            return;
        }

        if (!AnchorTier.loaded()) {
            if (carrying > 0)
                note("Apotheosis is not installed, so the world tier on " + carrying
                        + " anchor(s) is ignored.", false);
            return;
        }

        List<String> problems = AnchorTier.probe();
        if (problems.isEmpty()) {
            LOG.info("Spawn anchor Apotheosis tier support fits Apotheosis {} ({} anchor(s) carrying a tier).",
                    AnchorTier.apotheosisVersion(), carrying);
            return;
        }

        note("Apotheosis " + AnchorTier.apotheosisVersion()
                + " no longer fits the anchor tier support: " + String.join("; ", problems)
                + ". Mobs near anchors spawn as if nobody were there. See the Mob & Chunk Loader section"
                + " in AGENTS.md; /foxstweaks anchor list shows which anchors carry a tier.", true);
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        NOTIFIED.clear();
        warning = null;
    }

    @SubscribeEvent
    public static void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (warning == null)
            return;
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;
        if (!player.hasPermissions(2))
            return;
        if (!NOTIFIED.add(player.getUUID()))
            return;

        player.sendSystemMessage(Component.literal("[Fox's Tweaks] ").withStyle(ChatFormatting.GOLD)
                .append(Component.literal(warning).withStyle(ChatFormatting.YELLOW)));
    }

    private static int anchorsCarryingTier(MinecraftServer server) {
        int carrying = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (SpawnAnchorData.Anchor anchor : SpawnAnchorData.get(level).all()) {
                if (anchor.carriesTier())
                    carrying++;
            }
        }
        return carrying;
    }

    private static void note(String message, boolean broken) {
        warning = message;
        if (broken)
            LOG.warn("Spawn anchors: {}", message);
        else
            LOG.info("Spawn anchors: {}", message);
    }

    private AnchorTierNotice() {
    }
}
