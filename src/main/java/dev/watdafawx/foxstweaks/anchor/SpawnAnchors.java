package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime manager for virtual spawn anchors: registers forced-ticking chunk tickets on level load,
 * answers hot-path proximity queries from {@code ChunkMap}, {@code ServerLevel}, and
 * {@code NaturalSpawner}, and prevents mobs near anchors from despawning.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class SpawnAnchors {
    public static final TicketType<ChunkPos> ANCHOR_TICKET =
            TicketType.create("foxstweaks_anchor", Comparator.comparingLong(ChunkPos::toLong));

    private static final Map<ResourceKey<Level>, List<SpawnAnchorData.Anchor>> CACHE = new ConcurrentHashMap<>();

    private SpawnAnchors() {
    }

    public static void add(ServerLevel level, SpawnAnchorData.Anchor anchor) {
        SpawnAnchorData data = SpawnAnchorData.get(level);
        SpawnAnchorData.Anchor prev = data.put(anchor);
        if (prev != null) {
            releaseTicket(level, prev);
        }
        applyTicket(level, anchor);
        refreshCache(level, data);
    }

    public static SpawnAnchorData.Anchor remove(ServerLevel level, String name) {
        SpawnAnchorData data = SpawnAnchorData.get(level);
        SpawnAnchorData.Anchor removed = data.remove(name);
        if (removed != null) {
            releaseTicket(level, removed);
            refreshCache(level, data);
        }
        return removed;
    }

    public static List<SpawnAnchorData.Anchor> list(ServerLevel level) {
        return CACHE.getOrDefault(level.dimension(), List.of());
    }

    public static boolean isChunkNearAnchor(ServerLevel level, ChunkPos pos) {
        if (!ServerConfig.spawnAnchorsEnabled())
            return false;
        List<SpawnAnchorData.Anchor> anchors = CACHE.get(level.dimension());
        if (anchors == null || anchors.isEmpty())
            return false;

        for (int i = 0; i < anchors.size(); i++) {
            SpawnAnchorData.Anchor a = anchors.get(i);
            ChunkPos ac = a.chunkPos();
            if (Math.max(Math.abs(pos.x - ac.x), Math.abs(pos.z - ac.z)) <= a.chunkRadius()) {
                return true;
            }
        }
        return false;
    }

    public static boolean hasAnchorWithin(ServerLevel level, double x, double y, double z, double range) {
        if (!ServerConfig.spawnAnchorsEnabled())
            return false;
        List<SpawnAnchorData.Anchor> anchors = CACHE.get(level.dimension());
        if (anchors == null || anchors.isEmpty())
            return false;

        double maxSq = range < 0.0 ? Double.MAX_VALUE : range * range;
        for (int i = 0; i < anchors.size(); i++) {
            if (anchors.get(i).distSq(x, y, z) < maxSq) {
                return true;
            }
        }
        return false;
    }

    public static double nearestAnchorDistSq(ServerLevel level, double x, double y, double z) {
        if (!ServerConfig.spawnAnchorsEnabled())
            return Double.MAX_VALUE;
        List<SpawnAnchorData.Anchor> anchors = CACHE.get(level.dimension());
        if (anchors == null || anchors.isEmpty())
            return Double.MAX_VALUE;

        double best = Double.MAX_VALUE;
        for (int i = 0; i < anchors.size(); i++) {
            double d = anchors.get(i).distSq(x, y, z);
            if (d < best)
                best = d;
        }
        return best;
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level))
            return;
        SpawnAnchorData data = SpawnAnchorData.get(level);
        if (ServerConfig.spawnAnchorsEnabled()) {
            for (SpawnAnchorData.Anchor a : data.all()) {
                applyTicket(level, a);
            }
        }
        refreshCache(level, data);
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            CACHE.remove(level.dimension());
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        CACHE.clear();
    }

    @SubscribeEvent
    public static void onMobDespawn(MobDespawnEvent event) {
        if (!ServerConfig.spawnAnchorsEnabled())
            return;
        if (!(event.getLevel() instanceof ServerLevel level))
            return;

        Mob mob = event.getEntity();
        double anchorDistSq = nearestAnchorDistSq(level, mob.getX(), mob.getY(), mob.getZ());
        if (anchorDistSq == Double.MAX_VALUE)
            return;

        int despawnDist = mob.getType().getCategory().getDespawnDistance();
        double despawnSq = (double) despawnDist * despawnDist;
        if (anchorDistSq >= despawnSq)
            return;

        // If a real player is already closer than the despawn distance, let vanilla handle it normally.
        Player nearestPlayer = level.getNearestPlayer(mob, -1.0);
        if (nearestPlayer != null && nearestPlayer.distanceToSqr(mob) < despawnSq)
            return;

        // Mirror vanilla despawn rules relative to the anchor: never despawn within noDespawnDistance (32),
        // and between 32..128 allow vanilla's 1-in-800 idle despawn so stray mobs don't pile up forever.
        int noDespawnDist = mob.getType().getCategory().getNoDespawnDistance();
        double noDespawnSq = (double) noDespawnDist * noDespawnDist;
        if (anchorDistSq < noDespawnSq) {
            mob.setNoActionTime(0);
            event.setResult(MobDespawnEvent.Result.DENY);
        } else if (mob.getNoActionTime() <= 600 || level.random.nextInt(800) != 0) {
            event.setResult(MobDespawnEvent.Result.DENY);
        }
    }

    public static int totalCount(MinecraftServer server) {
        int n = 0;
        for (ServerLevel level : server.getAllLevels()) {
            n += list(level).size();
        }
        return n;
    }

    private static void applyTicket(ServerLevel level, SpawnAnchorData.Anchor anchor) {
        ChunkPos center = anchor.chunkPos();
        // Ticket level 31 = entity ticking; radius+1 ensures the full (2*radius+1)^2 square is entity-ticking.
        level.getChunkSource().addRegionTicket(ANCHOR_TICKET, center, anchor.chunkRadius() + 1, center, true);
    }

    private static void releaseTicket(ServerLevel level, SpawnAnchorData.Anchor anchor) {
        ChunkPos center = anchor.chunkPos();
        level.getChunkSource().removeRegionTicket(ANCHOR_TICKET, center, anchor.chunkRadius() + 1, center, true);
    }

    private static void refreshCache(ServerLevel level, SpawnAnchorData data) {
        if (data.isEmpty()) {
            CACHE.remove(level.dimension());
        } else {
            CACHE.put(level.dimension(), List.copyOf(new ArrayList<>(data.all())));
        }
    }
}
