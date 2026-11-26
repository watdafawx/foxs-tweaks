package dev.watdafawx.foxstweaks.client.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramCreatorScreen;
import dev.watdafawx.foxstweaks.client.HologramGrab;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.HologramTimelinePlayer;
import dev.watdafawx.foxstweaks.ServerConfig;
import dev.watdafawx.foxstweaks.client.HologramWebBridge;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One browser editing session for one open hologram editor: the protocol in docs/hologram-web-protocol.md.
 *
 * <p>The in-game editor stays the owner of everything (live preview, undo history, save/cancel); the browser is
 * a second view of the same editing session. Browser messages are parsed on the connection's thread and applied
 * on the client main thread. Changes flow back by comparison, not by hooks: each client tick, while a browser is
 * connected, the editor's spec is serialised and compared with what was last sent, so an edit made in game, in
 * the Creator, by undo or by the browser itself all reach the browser the same way (at most 20 times a second).
 *
 * <p>The server lives exactly as long as the session: it is started lazily by the first "Web" press and stopped,
 * with its token, when the editor saves, cancels or closes, the world is left, or the game shuts down.
 */
public final class HologramWebSession {
    private static final Logger LOG = LogUtils.getLogger();

    /**
     * 2: layer uids, the timeline, and the preview-only playhead message. 3: image layers and assets. 4: audio
     * modulations and the meter. 5: block, entity and particle icons rendered by the game ({@link HologramWebIcons}).
     */
    public static final int PROTOCOL = 5;
    /** Ticks with no screen at all (and no grab mode) after which the editor is treated as gone. */
    private static final int ORPHAN_TICKS = 100;
    private static final int MAX_DATA_KEYS = 128;

    /** Dev checks set this so a press starts the session without launching a browser. */
    public static boolean devNoBrowser;

    @Nullable
    private static HologramWebSession current;
    /** A web open requested by the client command, waiting for the editor to be on screen. */
    @Nullable
    private static String pendingId;
    private static boolean pendingAny;
    private static long pendingUntil;

    private final WebEditor editor;
    private final WebServer server;
    private final HologramWebAssets assets = new HologramWebAssets((c, o) -> c.send(o.toString()));
    private final HologramWebIcons icons = new HologramWebIcons();
    private boolean ended;
    private String endReason = "closed";

    private final Map<String, CompoundTag> dataKeys = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CompoundTag> eldest) {
            return size() > MAX_DATA_KEYS;
        }
    };
    private final Map<String, String> snbt = new LinkedHashMap<>();

    @Nullable
    private String lastSpec;
    private boolean lastUndo;
    private boolean lastRedo;
    @Nullable
    private WebEditor.Selection lastSelection;
    private boolean forceState;
    private int rev;
    @Nullable
    private String lastGesture;
    private int orphanTicks;

    // ---- counters the dev check reads ----
    private int editsApplied;
    private int editsRejected;

    private HologramWebSession(WebEditor editor) throws IOException {
        this.editor = editor;
        this.server = WebServer.start(new Listener());
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(HologramWebSession::onTick);
        NeoForge.EVENT_BUS.addListener(HologramWebSession::onLogout);
        NeoForge.EVENT_BUS.addListener(HologramWebSession::onShutdown);
        NeoForge.EVENT_BUS.addListener(HologramWebIcons::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(HologramWebSession::onRegisterClientCommands);
    }

    // ---------------------------------------------------------------- lifecycle

    @Nullable
    public static HologramWebSession current() {
        return current;
    }

    /** Starts a session for {@code editor} (or reuses the one already serving it) and returns its URL. */
    @Nullable
    public static String start(WebEditor editor) {
        if (current != null && !current.ended && current.editor.equals(editor))
            return current.server.url();
        stopCurrent("replaced");
        try {
            current = new HologramWebSession(editor);
            LOG.info("Hologram web editor listening on 127.0.0.1:{}", current.server.port());
            return current.server.url();
        } catch (IOException e) {
            LOG.error("Hologram web editor: could not start the local server", e);
            editor.notifyPlayer("Web editor: could not start the local server (" + e.getMessage() + ")");
            return null;
        }
    }

    /** The "Web" buttons: start or reuse the session, open the browser, put the link on the clipboard. */
    public static void openInBrowser(WebEditor editor) {
        String url = start(editor);
        if (url == null)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (!devNoBrowser) {
            mc.keyboardHandler.setClipboard(url);
            Util.getPlatform().openUri(url);
        }
        // Action bar, not chat: chat lines are written to the log, and the link carries the session token.
        editor.notifyPlayer("Web editor opened in your browser (link copied to the clipboard)");
    }

    public static void stopCurrent(String reason) {
        HologramWebSession s = current;
        current = null;
        if (s != null)
            s.end(reason);
    }

    private void end(String reason) {
        if (this.ended)
            return;
        this.ended = true;
        this.endReason = reason;
        this.assets.close();
        this.icons.close();
        HologramTimelinePlayer.clearPlayhead();
        JsonObject closed = new JsonObject();
        closed.addProperty("type", "closed");
        closed.addProperty("reason", reason);
        broadcast(closed.toString());
        this.server.stop();
        LOG.info("Hologram web editor stopped ({})", reason);
        if (current == this)
            current = null;
    }

    HologramWebIcons icons() {
        return this.icons;
    }

    public WebEditor editor() {
        return this.editor;
    }

    public String url() {
        return this.server.url();
    }

    public int port() {
        return this.server.port();
    }

    public String token() {
        return this.server.token();
    }

    public boolean running() {
        return !this.ended && this.server.running();
    }

    public String endReason() {
        return this.endReason;
    }

    public int clients() {
        return this.server.connections().size();
    }

    public int editsApplied() {
        return this.editsApplied;
    }

    public int editsRejected() {
        return this.editsRejected;
    }

    // ---------------------------------------------------------------- game-thread events

    private static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        tickPending(mc);
        HologramWebSession s = current;
        if (s == null)
            return;
        if (!s.editor.alive()) {
            s.end("editor closed");
            return;
        }
        if (mc.level == null) {
            s.end("left the world");
            return;
        }
        // An editor can be dropped without a close (death screen, a mod forcing setScreen(null)): treat a long
        // stretch with no screen as the end, except in grab mode, which hides the editor on purpose.
        if (mc.screen == null && !HologramGrab.isActive()) {
            if (++s.orphanTicks > ORPHAN_TICKS) {
                s.end("editor closed");
                return;
            }
        } else {
            s.orphanTicks = 0;
        }
        s.pushChanges();
    }

    private static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        stopCurrent("left the world");
    }

    private static void onShutdown(GameShuttingDownEvent event) {
        stopCurrent("game closed");
    }

    private void pushChanges() {
        List<WebSocketConnection> conns = this.server.connections();
        if (conns.isEmpty()) {
            this.lastSpec = null;
            this.lastSelection = null;
            return;
        }
        String spec = specJson();
        boolean undo = this.editor.canUndo();
        boolean redo = this.editor.canRedo();
        if (this.forceState || !spec.equals(this.lastSpec) || undo != this.lastUndo || redo != this.lastRedo) {
            this.forceState = false;
            if (!spec.equals(this.lastSpec))
                this.rev++;
            this.lastSpec = spec;
            this.lastUndo = undo;
            this.lastRedo = redo;
            for (WebSocketConnection c : conns)
                c.send(stateMessage(c, spec, undo, redo));
        }
        WebEditor.Selection sel = this.editor.selection();
        if (!sel.equals(this.lastSelection)) {
            this.lastSelection = sel;
            broadcast(selectionMessage(sel));
        }
        pushMeter(conns);
    }

    private final float[] meterLevels = new float[5];

    /**
     * Levels only, once per client tick (20 Hz) and only to tabs that asked: the browser never receives samples,
     * so system audio stays on this PC even with the editor open in a browser.
     */
    private void pushMeter(List<WebSocketConnection> conns) {
        for (WebSocketConnection c : conns) {
            if (!c.meter)
                continue;
            dev.watdafawx.foxstweaks.client.audio.AudioHub.touchDemand();
            dev.watdafawx.foxstweaks.client.audio.AudioHub.meter(c.meterSource, this.meterLevels);
            JsonObject o = new JsonObject();
            o.addProperty("type", "meter");
            o.addProperty("source", c.meterSource.name());
            o.addProperty("level", round3(this.meterLevels[0]));
            o.addProperty("bass", round3(this.meterLevels[1]));
            o.addProperty("mid", round3(this.meterLevels[2]));
            o.addProperty("high", round3(this.meterLevels[3]));
            o.addProperty("beat", round3(this.meterLevels[4]));
            o.addProperty("system", dev.watdafawx.foxstweaks.client.audio.SystemAudio.status());
            c.send(o.toString());
        }
    }

    private static float round3(float v) {
        return Math.round(v * 1000.0F) / 1000.0F;
    }

    /** {"type":"meter","on":bool,"source":"AUTO|JUKEBOX|MUSIC|SYSTEM"}: start or stop the level stream. */
    private void meter(WebSocketConnection c, JsonObject msg, long seq) {
        c.meter = msg.has("on") && msg.get("on").isJsonPrimitive() && msg.get("on").getAsJsonPrimitive().isBoolean()
                && msg.get("on").getAsBoolean();
        String src = str(msg, "source");
        if (!src.isEmpty()) {
            try {
                c.meterSource = dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Source.valueOf(src);
            } catch (IllegalArgumentException e) {
                error(c, seq, "invalid", "unknown meter source '" + truncate(src) + "'");
                return;
            }
        }
        if (seq > c.ack)
            c.ack = seq;
    }

    private String specJson() {
        HologramSpec spec = this.editor.spec();
        return HologramWebJson.write(spec, this.editor.maxRenderDistance(), this.dataKeys,
                key -> this.snbt.computeIfAbsent(key, k -> {
                    CompoundTag tag = this.dataKeys.get(k);
                    return tag == null ? "" : tag.toString();
                })).toString();
    }

    private String stateMessage(WebSocketConnection c, String spec, boolean undo, boolean redo) {
        // Hand-built so the (already serialised) spec is not parsed and written again per client.
        return "{\"type\":\"state\",\"rev\":" + this.rev + ",\"ack\":" + c.ack
                + ",\"canUndo\":" + undo + ",\"canRedo\":" + redo + ",\"spec\":" + spec + "}";
    }

    private static String selectionMessage(WebEditor.Selection sel) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "selection");
        o.addProperty("kind", sel.kind());
        o.addProperty("layer", sel.layer());
        o.addProperty("group", sel.group());
        o.addProperty("soloLayer", sel.soloLayer());
        o.addProperty("soloGroup", sel.soloGroup());
        return o.toString();
    }

    private void broadcast(String text) {
        for (WebSocketConnection c : this.server.connections())
            c.send(text);
    }

    // ---------------------------------------------------------------- browser messages (game thread)

    private void onOpen(WebSocketConnection c) {
        JsonObject hello = new JsonObject();
        hello.addProperty("type", "hello");
        hello.addProperty("protocol", PROTOCOL);
        hello.addProperty("mod", FoxsTweaks.MODID);
        hello.addProperty("modVersion", ModList.get().getModContainerById(FoxsTweaks.MODID)
                .map(m -> m.getModInfo().getVersion().toString()).orElse("?"));
        hello.addProperty("minecraft", net.minecraft.SharedConstants.getCurrentVersion().getName());
        hello.addProperty("hologram", this.editor.spec().id());
        hello.add("registries", HologramWebRegistries.get());
        // Per server, so not part of the cached registries.
        JsonObject caps = new JsonObject();
        caps.addProperty("maxTracks", ServerConfig.hologramTimelineMaxTracks());
        caps.addProperty("maxKeys", ServerConfig.hologramTimelineMaxKeys());
        hello.add("timelineCaps", caps);
        hello.add("assetCaps", HologramWebAssets.capabilities());
        hello.add("iconCaps", HologramWebIcons.capabilities());
        c.send(hello.toString());

        String spec = specJson();
        c.send(stateMessage(c, spec, this.editor.canUndo(), this.editor.canRedo()));
        c.send(selectionMessage(this.editor.selection()));
    }

    private void onMessage(WebSocketConnection c, JsonObject msg) {
        String type = msg.has("type") && msg.get("type").isJsonPrimitive() ? msg.get("type").getAsString() : "";
        long seq = msg.has("seq") && msg.get("seq").isJsonPrimitive() && msg.get("seq").getAsJsonPrimitive().isNumber()
                ? msg.get("seq").getAsLong() : 0L;
        switch (type) {
            case "edit" -> edit(c, msg, seq);
            case "select" -> {
                this.editor.select(str(msg, "kind"), intOf(msg, "layer", -1), nullableStr(msg, "group"));
                ack(c, seq);
            }
            case "solo" -> {
                this.editor.solo(intOf(msg, "layer", -1), nullableStr(msg, "group"));
                this.lastSelection = null;
                ack(c, seq);
            }
            case "undo" -> {
                this.lastGesture = null;
                this.editor.undo();
                ack(c, seq);
            }
            case "redo" -> {
                this.lastGesture = null;
                this.editor.redo();
                ack(c, seq);
            }
            case "save" -> {
                this.editor.save();
                end("saved");
            }
            case "cancel" -> {
                this.editor.cancel();
                end("cancelled");
            }
            case "playhead" -> playhead(c, msg, seq);
            case "meter" -> meter(c, msg, seq);
            case "ping" -> {
                JsonObject pong = new JsonObject();
                pong.addProperty("type", "pong");
                pong.addProperty("seq", seq);
                c.send(pong.toString());
            }
            case "hello" -> {
            }
            default -> {
                if (!this.assets.handle(c, type, msg, seq) && !this.icons.handle(c, type, msg, seq, this::error))
                    error(c, seq, "unknown", "unknown message type '" + truncate(type) + "'");
            }
        }
    }

    private void edit(WebSocketConnection c, JsonObject msg, long seq) {
        if (!msg.has("spec") || !msg.get("spec").isJsonObject()) {
            error(c, seq, "invalid", "edit needs a spec object");
            return;
        }
        HologramSpec next;
        try {
            next = HologramWebJson.read(msg.getAsJsonObject("spec"), this.editor.spec(),
                    this.editor.maxRenderDistance(), this.dataKeys);
        } catch (HologramWebJson.Invalid e) {
            this.editsRejected++;
            error(c, seq, "invalid", e.getMessage());
            return;
        }
        // One undo step per user gesture: a scrub sends many edits under one gesture id.
        String gesture = msg.has("gesture") && msg.get("gesture").isJsonPrimitive()
                ? truncate(msg.get("gesture").getAsString()) : null;
        boolean newStep = gesture == null || !gesture.equals(this.lastGesture);
        this.lastGesture = gesture;
        this.editor.apply(next, newStep);
        this.editsApplied++;
        ack(c, seq);
    }

    /**
     * Preview-only playhead: the game draws this hologram's timeline paused at {@code time} or playing from it.
     * Nothing is saved; "live" (or the session ending) hands the timeline back to the normal game-time clock.
     */
    private void playhead(WebSocketConnection c, JsonObject msg, long seq) {
        String mode = str(msg, "mode");
        HologramTimelinePlayer.PlayheadMode m = switch (mode) {
            case "play" -> HologramTimelinePlayer.PlayheadMode.PLAY;
            case "pause" -> HologramTimelinePlayer.PlayheadMode.PAUSE;
            case "live" -> HologramTimelinePlayer.PlayheadMode.LIVE;
            default -> null;
        };
        if (m == null) {
            error(c, seq, "invalid", "playhead mode must be play, pause or live");
            return;
        }
        JsonElement el = msg.get("time");
        double time = el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isNumber() ? el.getAsDouble() : 0.0;
        if (!Double.isFinite(time) || time < 0.0 || time > 600.0) {
            error(c, seq, "invalid", "playhead time must be 0..600 seconds");
            return;
        }
        String id = this.editor.spec().id();
        HologramTimelinePlayer.setPlayhead(id, m, time);
        JsonObject o = new JsonObject();
        o.addProperty("type", "playhead");
        o.addProperty("seq", seq);
        o.addProperty("mode", mode);
        o.addProperty("time", time);
        c.send(o.toString());
        // No ack: a scrub sends a stream of these and none of them changes the spec, so no state echo is due.
        if (seq > c.ack)
            c.ack = seq;
    }

    private void ack(WebSocketConnection c, long seq) {
        if (seq > c.ack)
            c.ack = seq;
        this.forceState = true;
    }

    private void error(WebSocketConnection c, long seq, String code, String message) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "error");
        o.addProperty("seq", seq);
        o.addProperty("code", code);
        o.addProperty("message", message);
        c.send(o.toString());
        ack(c, seq); // the state that follows is the truth the browser falls back to
    }

    private static String truncate(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    private static String str(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() ? truncate(el.getAsString()) : "";
    }

    @Nullable
    private static String nullableStr(JsonObject o, String key) {
        JsonElement el = o.get(key);
        return el != null && el.isJsonPrimitive() && el.getAsJsonPrimitive().isString() ? truncate(el.getAsString()) : null;
    }

    private static int intOf(JsonObject o, String key, int def) {
        JsonElement el = o.get(key);
        if (el == null || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber())
            return def;
        double d = el.getAsDouble();
        return Double.isFinite(d) ? (int) Math.max(-1, Math.min(1024, d)) : def;
    }

    /** Server threads -> game thread. Every callback re-checks that this session is still the live one. */
    private final class Listener implements WebServer.Listener {
        @Override
        public void onOpen(WebSocketConnection connection) {
            Minecraft.getInstance().execute(() -> {
                if (current == HologramWebSession.this && !HologramWebSession.this.ended)
                    HologramWebSession.this.onOpen(connection);
            });
        }

        @Override
        public void onMessage(WebSocketConnection connection, String text) {
            JsonObject msg;
            try {
                JsonElement el = JsonParser.parseString(text);
                if (!el.isJsonObject()) {
                    connection.send("{\"type\":\"error\",\"seq\":0,\"code\":\"invalid\",\"message\":\"not a JSON object\"}");
                    return;
                }
                msg = el.getAsJsonObject();
            } catch (RuntimeException | StackOverflowError e) {
                connection.send("{\"type\":\"error\",\"seq\":0,\"code\":\"invalid\",\"message\":\"malformed JSON\"}");
                return;
            }
            Minecraft.getInstance().execute(() -> {
                if (current != HologramWebSession.this || HologramWebSession.this.ended || !connection.isOpen())
                    return;
                try {
                    HologramWebSession.this.onMessage(connection, msg);
                } catch (RuntimeException e) {
                    LOG.warn("Hologram web editor: message failed", e);
                    HologramWebSession.this.error(connection, 0, "internal", "the game could not apply that");
                }
            });
        }

        @Override
        public void onRateLimited(WebSocketConnection connection) {
            connection.send("{\"type\":\"error\",\"seq\":0,\"code\":\"rate\",\"message\":\"too many messages; some were dropped\"}");
        }

        @Override
        public void onClose(WebSocketConnection connection) {
        }
    }

    // ---------------------------------------------------------------- /foxstweaks hologram web [id]

    private static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal(FoxsTweaks.MODID)
                .then(Commands.literal("hologram")
                        .then(Commands.literal("web")
                                .executes(ctx -> {
                                    requestOpen(null);
                                    return 1;
                                })
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                                HologramClient.specs().stream().map(HologramSpec::id), b))
                                        .executes(ctx -> {
                                            requestOpen(StringArgumentType.getString(ctx, "id"));
                                            return 1;
                                        })))));
    }

    /**
     * Opens the web editor for the editor that is (or is about to be) on screen. With an id that is not the
     * open editor's, asks the server to open that hologram's editor first, through the normal edit command.
     */
    private static void requestOpen(@Nullable String id) {
        Minecraft mc = Minecraft.getInstance();
        HologramWebSession live = current;
        if (live != null && !live.ended && (id == null || id.equals(live.editor.spec().id()))) {
            mc.execute(() -> openInBrowser(live.editor));
            return;
        }
        // The command usually runs from a chat screen that closes right after, so the editor (if any) shows up
        // a tick or two later: wait for it instead of looking now.
        pendingId = id;
        pendingAny = id == null;
        pendingUntil = System.currentTimeMillis() + (id == null ? 2_000L : 10_000L);
        if (id != null && mc.player != null) {
            HologramScreen open = editorOnScreen(mc);
            if (open == null || !id.equals(open.current().id()))
                mc.player.connection.sendCommand("foxstweaks hologram edit " + id);
        }
    }

    private static void tickPending(Minecraft mc) {
        if (pendingId == null && !pendingAny)
            return;
        if (System.currentTimeMillis() > pendingUntil) {
            if (pendingAny && mc.player != null)
                mc.player.displayClientMessage(Component.literal(
                        "Open a hologram's editor first, or use /foxstweaks hologram web <id>."), false);
            pendingId = null;
            pendingAny = false;
            return;
        }
        HologramScreen editor = editorOnScreen(mc);
        if (editor == null || (pendingId != null && !Objects.equals(pendingId, editor.current().id())))
            return;
        pendingId = null;
        pendingAny = false;
        HologramWebBridge.open(editor);
    }

    @Nullable
    private static HologramScreen editorOnScreen(Minecraft mc) {
        if (mc.screen instanceof HologramScreen s)
            return s;
        if (mc.screen instanceof HologramCreatorScreen c)
            return c.editor();
        return null;
    }

    /** Lists every connection's last acknowledged edit; dev checks only. */
    public List<Long> devAcks() {
        List<Long> out = new ArrayList<>();
        for (WebSocketConnection c : this.server.connections())
            out.add(c.ack);
        return out;
    }
}
