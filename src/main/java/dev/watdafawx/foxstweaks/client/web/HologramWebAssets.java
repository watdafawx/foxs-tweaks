package dev.watdafawx.foxstweaks.client.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.hologram.asset.AssetFrames;
import dev.watdafawx.foxstweaks.hologram.asset.AssetLimits;
import dev.watdafawx.foxstweaks.hologram.asset.AssetPayload;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * The web editor's asset messages: the library listing, thumbnails and uploads. Game thread only.
 *
 * <p>The browser never talks to the server. An upload arrives here in base64 chunks (a WebSocket frame is capped
 * at 1 MB), is reassembled in memory and then handed to {@link HologramAssets#uploadBytes}, which sends it over
 * the normal asset payload; the server sanitises it and answers with the hash. Every chunk is acknowledged before
 * the browser sends the next, so a slow game never sees a burst it would have to rate-limit.
 */
final class HologramWebAssets {
    /** Raw bytes per chunk: base64 makes this ~700 KB, safely under the 1 MB frame limit. */
    static final int CHUNK_BYTES = 512 * 1024;

    interface Sender {
        void send(WebSocketConnection c, JsonObject message);
    }

    private final Sender sender;
    private int nextUpload = 1;
    @Nullable
    private Incoming incoming;

    private static final class Incoming {
        final int id;
        final WebSocketConnection connection;
        final String name;
        final int size;
        final int mode;
        final ByteArrayOutputStream data;
        int next;
        boolean submitted;

        Incoming(int id, WebSocketConnection connection, String name, int size, int mode) {
            this.id = id;
            this.connection = connection;
            this.name = name;
            this.size = size;
            this.mode = mode;
            this.data = new ByteArrayOutputStream(size);
        }
    }

    HologramWebAssets(Sender sender) {
        this.sender = sender;
    }

    /** What the hello message tells the page about uploads. */
    static JsonObject capabilities() {
        JsonObject caps = new JsonObject();
        caps.addProperty("available", HologramAssets.available());
        caps.addProperty("chunkBytes", CHUNK_BYTES);
        caps.addProperty("maxUploadBytes", AssetLimits.MAX_BYTES);
        caps.addProperty("maxImageLayers", dev.watdafawx.foxstweaks.ServerConfig.hologramImageMaxLayers());

        // The browser's own video conversion (WebCodecs) must land inside the same limits the server enforces,
        // or the server would reject a bundle the page spent a minute building.
        AssetLimits limits = AssetLimits.current();
        caps.addProperty("maxFileBytes", limits.fileBytes());
        caps.addProperty("maxDimension", limits.dimension());
        caps.addProperty("maxFps", limits.fps());
        caps.addProperty("maxSeconds", limits.seconds());
        return caps;
    }

    /** True when the message was an asset message (handled here). */
    boolean handle(WebSocketConnection c, String type, JsonObject msg, long seq) {
        switch (type) {
            case "assets" -> listing(c, msg, seq);
            case "assetThumb" -> thumbnail(c, msg, seq);
            case "assetDelete" -> delete(c, msg, seq);
            case "upload" -> begin(c, msg, seq);
            case "uploadChunk" -> chunk(c, msg, seq);
            case "uploadCancel" -> cancel(c, seq);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The session ended or the browser left: nothing half-sent may reach the server. */
    void close() {
        Incoming in = this.incoming;
        this.incoming = null;
        if (in != null && !in.submitted)
            in.data.reset();
    }

    // ---------------------------------------------------------------- library

    private void listing(WebSocketConnection c, JsonObject msg, long seq) {
        int offset = Math.max(0, intOf(msg, "offset", 0));
        HologramAssets.requestListing(offset, page -> {
            JsonObject o = page.deepCopy();
            o.addProperty("type", "assets");
            o.addProperty("seq", seq);
            send(c, o);
        });
    }

    private void delete(WebSocketConnection c, JsonObject msg, long seq) {
        String id = str(msg, "id", 64);
        if (!AssetFrames.validId(id)) {
            fail(c, seq, "assetDelete", "bad asset id");
            return;
        }
        boolean force = msg.has("force") && msg.get("force").isJsonPrimitive() && msg.get("force").getAsBoolean();
        HologramAssets.requestDelete(id, force, error -> {
            if (error != null) {
                fail(c, seq, "assetDelete", error);
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "assetDeleted");
            o.addProperty("id", id);
            o.addProperty("seq", seq);
            send(c, o);
        });
    }

    private void thumbnail(WebSocketConnection c, JsonObject msg, long seq) {
        String id = str(msg, "id", 64);
        if (!AssetFrames.validId(id)) {
            fail(c, seq, "assetThumb", "bad asset id");
            return;
        }
        HologramAssets.thumbnail(id, png -> {
            if (png == null) {
                fail(c, seq, "assetThumb", "no preview");
                return;
            }
            JsonObject o = new JsonObject();
            o.addProperty("type", "assetThumb");
            o.addProperty("id", id);
            o.addProperty("data", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
            send(c, o);
        });
    }

    // ---------------------------------------------------------------- uploads

    private void begin(WebSocketConnection c, JsonObject msg, long seq) {
        if (this.incoming != null || HologramAssets.uploading()) {
            fail(c, seq, "upload", "another upload is still running");
            return;
        }
        if (!HologramAssets.available()) {
            fail(c, seq, "upload", "this server has no asset store (Fox's Tweaks missing or too old)");
            return;
        }
        int size = intOf(msg, "size", -1);
        if (size < 8 || size > AssetLimits.MAX_BYTES) {
            fail(c, seq, "upload", "files must be 8 bytes to 16 MiB");
            return;
        }
        int mode = switch (str(msg, "mode", 16)) {
            case "image" -> AssetPayload.MODE_IMAGE;
            case "video" -> AssetPayload.MODE_VIDEO;
            case "bundle" -> AssetPayload.MODE_BUNDLE;
            default -> -1;
        };
        if (mode < 0) {
            fail(c, seq, "upload", "mode must be image, video or bundle");
            return;
        }
        String name = str(msg, "name", 128).replaceAll("[\\p{Cntrl}/\\\\]", "_");
        Incoming in = new Incoming(this.nextUpload++, c, name.isBlank() ? "upload" : name, size, mode);
        this.incoming = in;

        JsonObject o = new JsonObject();
        o.addProperty("type", "uploadStarted");
        o.addProperty("seq", seq);
        o.addProperty("upload", in.id);
        o.addProperty("chunkBytes", CHUNK_BYTES);
        send(c, o);
    }

    private void chunk(WebSocketConnection c, JsonObject msg, long seq) {
        Incoming in = this.incoming;
        if (in == null || in.connection != c || in.submitted || intOf(msg, "upload", -1) != in.id) {
            fail(c, seq, "upload", "no such upload");
            return;
        }
        if (intOf(msg, "index", -1) != in.next) {
            abort(in, seq, "chunks arrived out of order");
            return;
        }
        byte[] bytes;
        try {
            JsonElement data = msg.get("data");
            bytes = data != null && data.isJsonPrimitive() ? Base64.getDecoder().decode(data.getAsString()) : new byte[0];
        } catch (IllegalArgumentException e) {
            abort(in, seq, "chunk is not base64");
            return;
        }
        if (bytes.length == 0 || bytes.length > CHUNK_BYTES || in.data.size() + bytes.length > in.size) {
            abort(in, seq, "chunk size is wrong");
            return;
        }
        in.data.write(bytes, 0, bytes.length);
        in.next++;

        progress(in, (int) (in.data.size() * 100L / in.size), "sending to the game");
        JsonObject ack = new JsonObject();
        ack.addProperty("type", "uploadAck");
        ack.addProperty("seq", seq);
        ack.addProperty("upload", in.id);
        ack.addProperty("index", in.next - 1);
        send(c, ack);

        if (in.data.size() == in.size)
            submit(in);
    }

    private void submit(Incoming in) {
        in.submitted = true;
        byte[] bytes = in.data.toByteArray();
        in.data.reset();
        try {
            HologramAssets.uploadBytes(in.name, bytes, in.mode, new HologramAssets.UploadListener() {
                @Override
                public void progress(int percent, String stage) {
                    if (HologramWebAssets.this.incoming == in)
                        HologramWebAssets.this.progress(in, percent, stage);
                }

                @Override
                public void done(boolean ok, String message) {
                    if (HologramWebAssets.this.incoming == in)
                        HologramWebAssets.this.incoming = null;
                    finished(in, ok, message);
                }
            });
        } catch (IOException e) {
            this.incoming = null;
            finished(in, false, e.getMessage());
        }
    }

    private void cancel(WebSocketConnection c, long seq) {
        Incoming in = this.incoming;
        // Once handed to the server it cannot be called back; the result still arrives as uploadDone.
        if (in != null && in.connection == c && !in.submitted)
            abort(in, seq, "cancelled");
    }

    private void abort(Incoming in, long seq, String message) {
        this.incoming = null;
        finished(in, false, message);
    }

    private void progress(Incoming in, int percent, String stage) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "uploadProgress");
        o.addProperty("upload", in.id);
        o.addProperty("percent", Math.max(0, Math.min(100, percent)));
        o.addProperty("stage", stage);
        o.addProperty("server", in.submitted);
        send(in.connection, o);
    }

    private void finished(Incoming in, boolean ok, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "uploadDone");
        o.addProperty("upload", in.id);
        o.addProperty("ok", ok);
        if (ok && AssetFrames.validId(message))
            o.addProperty("hash", message);
        else
            o.addProperty("message", message == null ? "upload failed" : message);
        send(in.connection, o);
    }

    private void fail(WebSocketConnection c, long seq, String what, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("seq", seq);
        o.addProperty("code", what);
        o.addProperty("message", message);
        send(c, o);
    }

    private void send(WebSocketConnection c, JsonObject o) {
        if (c.isOpen())
            this.sender.send(c, o);
    }

    private static String str(JsonObject o, String key, int max) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive())
            return "";
        String s = el.getAsString();
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static int intOf(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
            return def;
        double d = el.getAsDouble();
        return Double.isFinite(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE ? (int) d : def;
    }
}
