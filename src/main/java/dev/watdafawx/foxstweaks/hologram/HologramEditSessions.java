package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Which hologram a player is editing right now, as far as the server knows.
 *
 * <p>The save, delete and preview packets are gated by how far the player stands from the hologram. That
 * gate is right for a stray packet, but wrong for someone who is in the editor: the studio camera, Grab
 * mode and a big structure all move the player away, and Done then failed. While a session for that exact
 * hologram is open the gate widens to {@code hologramsEditRange}; without one it stays at
 * {@link #BASE_LIMIT}.
 *
 * <p>A session starts when the server opens the editor (wand, command, create, recapture) or when the first
 * live preview for a hologram arrives from a player who is within the base limit. It ends on Done, Delete,
 * Cancel, logout, a dimension change or {@link #IDLE_TICKS} without any accepted packet.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class HologramEditSessions {

    /** How far from a hologram an action is allowed with no editor open: reach plus a little slack. */
    public static final double BASE_LIMIT = Holograms.REACH + 8.0;

    /** Ten minutes of server ticks without a save, delete or preview. */
    public static final int IDLE_TICKS = 10 * 60 * 20;

    private record Session(ResourceLocation dimension, String id, int lastTick) {
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private HologramEditSessions() {
    }

    public static void open(ServerPlayer player, String id) {
        SESSIONS.put(player.getUUID(), new Session(player.serverLevel().dimension().location(), id, now(player)));
    }

    public static void close(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    /** True while the player has this hologram open in the editor, in the dimension they are standing in. */
    public static boolean isOpen(ServerPlayer player, String id) {
        var session = SESSIONS.get(player.getUUID());
        if (session == null)
            return false;

        if (now(player) - session.lastTick() > IDLE_TICKS) {
            SESSIONS.remove(player.getUUID());
            return false;
        }
        return session.id().equals(id) && session.dimension().equals(player.serverLevel().dimension().location());
    }

    /** The farthest the player may be from this hologram for an action to be accepted. */
    public static double limit(ServerPlayer player, String id) {
        return isOpen(player, id) ? Math.max(BASE_LIMIT, ServerConfig.hologramsEditRange()) : BASE_LIMIT;
    }

    /** An accepted packet keeps the session from timing out. */
    public static void touch(ServerPlayer player, String id) {
        if (isOpen(player, id))
            open(player, id);
    }

    private static int now(ServerPlayer player) {
        return player.server.getTickCount();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        SESSIONS.clear();
    }
}
