package dev.watdafawx.foxstweaks.client.audio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.neoforged.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The player's own audio settings, kept in {@code config/foxstweaks-audio.json} on this client only: they are a
 * personal choice (whether this PC's sound card may be listened to), so they are never in a synced config and
 * never sent anywhere. System audio is off until the player switches it on.
 */
public final class AudioPrefs {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static boolean loaded;
    private static boolean systemEnabled;
    private static String deviceId = "";
    private static boolean listenAtPlayer;

    private AudioPrefs() {
    }

    private static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foxstweaks-audio.json");
    }

    private static void load() {
        if (loaded)
            return;
        loaded = true;
        try {
            Path f = file();
            if (!Files.exists(f))
                return;
            JsonObject o = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), JsonObject.class);
            if (o == null)
                return;
            systemEnabled = o.has("systemAudio") && o.get("systemAudio").getAsBoolean();
            deviceId = o.has("device") ? o.get("device").getAsString() : "";
            listenAtPlayer = o.has("listenAtPlayer") && o.get("listenAtPlayer").getAsBoolean();
        } catch (Exception e) {
            AudioHub.warnOnce("could not read foxstweaks-audio.json", e);
        }
    }

    private static void save() {
        JsonObject o = new JsonObject();
        o.addProperty("systemAudio", systemEnabled);
        o.addProperty("device", deviceId);
        o.addProperty("listenAtPlayer", listenAtPlayer);
        try {
            Files.writeString(file(), GSON.toJson(o), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AudioHub.warnOnce("could not write foxstweaks-audio.json", e);
        }
    }

    public static boolean systemEnabled() {
        load();
        return systemEnabled;
    }

    public static void setSystemEnabled(boolean on) {
        load();
        systemEnabled = on;
        save();
    }

    /** Device id from {@link SystemAudio#devices()}; empty = the default output (loopback). */
    public static String deviceId() {
        load();
        return deviceId;
    }

    public static void setDeviceId(String id) {
        load();
        deviceId = id == null ? "" : id;
        save();
    }

    /** True: distance to a jukebox is measured from the player; false (default): from each hologram. */
    public static boolean listenAtPlayer() {
        load();
        return listenAtPlayer;
    }

    public static void setListenAtPlayer(boolean atPlayer) {
        load();
        listenAtPlayer = atPlayer;
        save();
    }
}
