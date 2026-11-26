package dev.watdafawx.foxstweaks.client.dev;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramClient;
import dev.watdafawx.foxstweaks.client.HologramCreatorScreen;
import dev.watdafawx.foxstweaks.client.HologramEditorPrefs;
import dev.watdafawx.foxstweaks.client.HologramScreen;
import dev.watdafawx.foxstweaks.client.HologramWebBridge;
import dev.watdafawx.foxstweaks.client.web.HologramWebSession;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Dev-only check of the hologram web companion ({@code client/web}). It drives the real thing end to end: the
 * Studio's and the Creator's "Web" buttons and the client command start sessions; raw sockets probe the HTTP
 * side (token, Host check, traversal, the bundled page and its JS); a {@code java.net.http.WebSocket} client
 * speaks the protocol (hello, edits with gestures, undo/redo, select, refused input, rate and frame limits,
 * save); and the session must be gone, port closed, once the editor closes. Screenshots are of the in-game side.
 *
 * <p>Run with {@code build.bat runHologramWebCheck} on rig. Report:
 * {@code run-hologram-web/foxstweaks-hologram-web-check.txt}.
 */
public final class HologramWebCheck {
    private static final Logger LOG = LogUtils.getLogger();

    private record Step(int waitTicks, Runnable run) {
    }

    private record Resp(int status, Map<String, String> headers, byte[] body) {
        String text() {
            return new String(this.body, StandardCharsets.UTF_8);
        }
    }

    private static final List<Step> STEPS = new ArrayList<>();
    private static final List<String> REPORT = new ArrayList<>();
    private static int failures;
    private static boolean worldRequested;
    private static int tick;
    private static int nextAt;
    private static int index;

    private static String hologramId = "";
    private static HologramScreen editor;
    private static int port;
    private static String token = "";
    private static String origin = "";
    private static int firstPort;
    private static String firstToken = "";
    private static Client ws;
    private static Client extra;
    private static long seq;
    private static int statesBeforeIngameEdit;

    public static void install() {
        HologramWebSession.devNoBrowser = true;
        NeoForge.EVENT_BUS.addListener(HologramWebCheck::onTick);
        scenarios();
    }

    // ---------------------------------------------------------------- the scenario

    private static void scenarios() {
        add(40, () -> onServer(player -> {
            player.serverLevel().setDayTime(6000);
            player.teleportTo(player.serverLevel(), 0.5, -60.0, 5.5, 180.0F, 0.0F);
            return null;
        }));

        add(20, () -> {
            command("foxstweaks hologram add minecraft:amethyst_block");
            hologramId = onServer(player -> {
                List<HologramSpec> all = Holograms.list(player.serverLevel());
                return all.isEmpty() ? "" : all.get(0).id();
            });
            check("hologram created", !hologramId.isEmpty(), "none");
        });

        // ---- Studio: the Web button starts a session ----
        add(10, () -> {
            HologramEditorPrefs.setStartInCreator(false);
            guiScale(2);
            // The movement tutorial toast covers the Creator's top bar in the screenshots.
            Minecraft.getInstance().options.tutorialStep = net.minecraft.client.tutorial.TutorialSteps.NONE;
            Minecraft.getInstance().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
            Minecraft.getInstance().getToasts().clear();
            command("foxstweaks hologram edit " + hologramId);
        });
        add(10, () -> {
            editor = Minecraft.getInstance().screen instanceof HologramScreen s ? s : null;
            check("edit opens the Studio", editor != null, String.valueOf(Minecraft.getInstance().screen));
            check("no session before Web is pressed", HologramWebSession.current() == null, "already running");
            check("Studio has a Web button", editor != null && editor.devHasButton("Web"), "missing");
            boolean clicked = editor != null && editor.devClick("Web");
            check("Web button clickable", clicked, "click failed");
            HologramWebSession s = HologramWebSession.current();
            check("Web button starts a session", s != null && s.running(), String.valueOf(s));
            if (s == null)
                return;
            check("session serves this editor", HologramWebBridge.activeFor(editor), "other editor");
            port = s.port();
            token = s.token();
            origin = "http://127.0.0.1:" + port;
            firstPort = port;
            firstToken = token;
            check("URL is loopback with the token in the path", s.url().equals(origin + "/" + token + "/"), s.url());
            check("token is at least 128 bits of base64url", token.length() >= 22 && token.matches("[A-Za-z0-9_-]+"),
                    "token length " + token.length());
        });
        // The Studio toolbar is wider than 854px at GUI scale 2, so show the Web button at scale 1.
        add(2, () -> guiScale(1));
        add(6, () -> shot("web-studio-button.png"));
        add(2, () -> guiScale(2));

        // ---- HTTP: page, assets, token, Host, traversal ----
        add(2, () -> {
            Resp index = get("/" + token + "/");
            check("GET /<token>/ = 200 html", index.status == 200
                    && index.headers.getOrDefault("content-type", "").startsWith("text/html"), describe(index));
            check("page carries a CSP", index.headers.getOrDefault("content-security-policy", "")
                    .contains("connect-src ws://127.0.0.1:" + port), String.valueOf(index.headers));
            check("page references assets/app.js", index.text().contains("assets/app.js"), index.text());
            check("page sends no-store and nosniff", index.headers.getOrDefault("cache-control", "").contains("no-store")
                    && "nosniff".equals(index.headers.get("x-content-type-options")), String.valueOf(index.headers));
            Resp html = get("/" + token + "/index.html");
            check("GET index.html = 200", html.status == 200, describe(html));
            Resp js = get("/" + token + "/assets/app.js");
            check("JS asset served", js.status == 200 && js.body.length > 1000
                    && js.headers.getOrDefault("content-type", "").startsWith("text/javascript"), describe(js));
            Resp css = get("/" + token + "/assets/app.css");
            check("CSS asset served", css.status == 200 && css.body.length > 100, describe(css));
            Resp redirect = get("/" + token);
            check("/<token> redirects to /<token>/", redirect.status == 302, describe(redirect));
            Resp missing = get("/" + token + "/assets/nothing.js");
            check("missing file = 404", missing.status == 404, describe(missing));
        });
        add(2, () -> {
            String wrong = (token.charAt(0) == 'A' ? "B" : "A") + token.substring(1);
            expect("wrong token = 403", get("/" + wrong + "/"), 403);
            expect("no token = 403", get("/"), 403);
            expect("token as a file name = 403", get("/index.html"), 403);
            expect("Host localhost (DNS rebinding) = 403", http("GET", "/" + token + "/", "localhost:" + port), 403);
            expect("foreign Host = 403", http("GET", "/" + token + "/", "evil.example"), 403);
            expect("POST = 405", http("POST", "/" + token + "/", "127.0.0.1:" + port), 405);
        });
        add(2, () -> {
            String[] traversal = {
                    "/" + token + "/../../../../etc/passwd",
                    "/" + token + "/assets/../../../foxstweaks.mixins.json",
                    "/" + token + "/%2e%2e/%2e%2e/pack.mcmeta",
                    "/" + token + "/..%2f..%2fpack.mcmeta",
                    "/" + token + "/..\\..\\pack.mcmeta",
                    "/" + token + "/assets//app.js",
                    "/" + token + "/.hidden.txt",
                    "/" + token + "/META-INF/neoforge.mods.toml",
                    "/" + token + "/../" + token + "/index.html",
                    "/" + token + "/C:/Windows/win.ini",
                    "/" + token + "/foxstweaks.mixins.json",
            };
            for (String path : traversal) {
                Resp r = get(path);
                check("traversal refused: " + path.replace(token, "<token>"),
                        r.status == 403 || r.status == 404, describe(r));
            }
        });

        // ---- WebSocket: wrong token / Origin refused, then a real client ----
        add(2, () -> {
            // A refusal only counts as a 403 from the live server; "connection refused" would pass for anything.
            int wrongToken = handshakeStatus("ws://127.0.0.1:" + port + "/notthetoken/ws", origin);
            check("WS with a wrong token refused (403)", wrongToken == 403, "status " + wrongToken);
            int evil = handshakeStatus(wsUrl(), "http://evil.example");
            check("WS with a foreign Origin refused (403)", evil == 403, "status " + evil);
            int localhost = handshakeStatus(wsUrl(), "http://localhost:" + port);
            check("WS with Origin localhost refused (403)", localhost == 403, "status " + localhost);
            int none = handshakeStatus(wsUrl(), null);
            check("WS without Origin refused (403)", none == 403, "status " + none);
            ws = connect(wsUrl(), origin);
            check("WS with token + Origin accepted", ws != null, "refused");
            if (ws == null)
                index = STEPS.size() - 1; // everything after needs the socket: go straight to the report
        });
        add(10, () -> {
            if (ws == null)
                return;
            JsonObject hello = ws.last("hello");
            check("hello received", hello != null, ws.types());
            if (hello != null) {
                check("hello protocol 1", hello.get("protocol").getAsInt() == HologramWebSession.PROTOCOL, hello.get("protocol").toString());
                check("hello names the hologram", hologramId.equals(hello.get("hologram").getAsString()), hello.get("hologram").toString());
                JsonObject reg = hello.getAsJsonObject("registries");
                JsonArray blocks = reg.getAsJsonArray("blocks");
                boolean stone = false, amethyst = false;
                for (JsonElement b : blocks) {
                    String id = b.getAsJsonObject().get("id").getAsString();
                    stone |= id.equals("minecraft:stone");
                    amethyst |= id.equals("minecraft:amethyst_block");
                }
                check("hello blocks include stone and amethyst", stone && amethyst, blocks.size() + " blocks");
                check("hello has every effect type", reg.getAsJsonArray("effects").size() == HologramEffect.Type.values().length,
                        String.valueOf(reg.getAsJsonArray("effects").size()));
                check("hello has every trigger", reg.getAsJsonArray("triggers").size() == HologramEffect.Trigger.values().length,
                        String.valueOf(reg.getAsJsonArray("triggers").size()));
                check("hello has entities and particles", reg.getAsJsonArray("entities").size() > 50
                        && reg.getAsJsonArray("particles").size() > 20, "too few");
                JsonObject limits = reg.getAsJsonObject("limits");
                check("hello limits match the mod", limits.get("maxLayers").getAsInt() == HologramLayer.MAX_LAYERS
                        && limits.get("maxEffects").getAsInt() == HologramSpec.MAX_EFFECTS, limits.toString());
                log("info hello size " + hello.toString().length() + " chars, " + blocks.size() + " blocks");
            }
            JsonObject state = ws.last("state");
            check("initial state received", state != null && hologramId.equals(spec(state).get("id").getAsString()),
                    ws.types());
            check("initial state has one layer", state != null && layers(state).size() == 1, String.valueOf(state));
            check("selection received", ws.last("selection") != null, ws.types());
        });

        // ---- edit from the browser: scale, add effect, add layer ----
        add(2, () -> {
            if (ws == null)
                return;
            JsonObject spec = spec(ws.last("state")).deepCopy();
            JsonObject l0 = spec.getAsJsonArray("layers").get(0).getAsJsonObject();
            l0.addProperty("scale", 2.5);
            JsonObject spin = effectDefaults("SPIN");
            l0.getAsJsonArray("effects").add(spin);
            JsonObject glow = l0.deepCopy();
            glow.addProperty("id", "minecraft:glowstone");
            glow.add("props", new JsonObject());
            glow.add("effects", new JsonArray());
            glow.addProperty("scale", 1.0);
            JsonArray off = new JsonArray();
            off.add(0);
            off.add(2.5);
            off.add(0);
            glow.add("offset", off);
            spec.getAsJsonArray("layers").add(glow);
            sendEdit("gA", spec);
        });
        add(6, () -> {
            HologramSpec cur = editor.current();
            check("browser edit reaches the editor: 2 layers", cur.layers().size() == 2, cur.layers().size() + " layers");
            check("browser edit reaches the editor: scale 2.5", Math.abs(cur.layers().get(0).scale() - 2.5F) < 1e-3,
                    "scale " + cur.layers().get(0).scale());
            check("browser edit reaches the editor: SPIN added", cur.layers().get(0).effects().stream()
                    .anyMatch(e -> e.type() == HologramEffect.Type.SPIN), cur.layers().get(0).effects().toString());
            check("new layer is glowstone", cur.layers().size() == 2
                    && cur.layers().get(1).id().equals("minecraft:glowstone"), cur.layers().toString());
            HologramSpec preview = HologramClient.previewSpec();
            check("live preview shows the edit", preview != null && preview.layers().size() == 2
                    && Math.abs(preview.layers().get(0).scale() - 2.5F) < 1e-3, String.valueOf(preview));
            JsonObject state = ws.last("state");
            check("state acknowledges the edit", state != null && state.get("ack").getAsLong() >= seq
                    && layers(state).size() == 2, String.valueOf(state == null ? null : state.get("ack")));
            check("state reports undo available", state != null && state.get("canUndo").getAsBoolean(), "canUndo false");
        });
        add(10, () -> shot("web-edit-ingame.png"));

        // ---- gestures: two edits under one gesture are one undo step ----
        add(2, () -> {
            sendEdit("gB", withScale(3.0));
            sendEdit("gB", withScale(3.5));
        });
        add(6, () -> {
            check("scrub edits applied", Math.abs(layer0Scale() - 3.5F) < 1e-3, "scale " + layer0Scale());
            send("{\"type\":\"undo\",\"seq\":" + (++seq) + "}");
        });
        add(6, () -> {
            check("one undo reverts the whole gesture", Math.abs(layer0Scale() - 2.5F) < 1e-3, "scale " + layer0Scale());
            check("undo keeps the earlier edit", editor.current().layers().size() == 2, "layers " + editor.current().layers().size());
            JsonObject state = ws.last("state");
            check("state after undo: redo available, acked", state != null && state.get("canRedo").getAsBoolean()
                    && state.get("ack").getAsLong() >= seq, String.valueOf(state));
            send("{\"type\":\"redo\",\"seq\":" + (++seq) + "}");
        });
        add(6, () -> {
            check("redo over WS", Math.abs(layer0Scale() - 3.5F) < 1e-3, "scale " + layer0Scale());
            send("{\"type\":\"undo\",\"seq\":" + (++seq) + "}");
        });

        // ---- an in-game edit is pushed to the browser ----
        add(6, () -> {
            statesBeforeIngameEdit = (int) ws.count("state");
            editor.devSetScale(1.75F);
        });
        add(6, () -> {
            JsonObject state = ws.last("state");
            double scale = state == null ? -1 : layers(state).get(0).getAsJsonObject().get("scale").getAsDouble();
            check("in-game edit pushed to the browser", ws.count("state") > statesBeforeIngameEdit
                    && Math.abs(scale - 1.75) < 1e-3, "scale " + scale);
        });

        // ---- select from the browser ----
        add(2, () -> send("{\"type\":\"select\",\"kind\":\"layer\",\"layer\":1,\"seq\":" + (++seq) + "}"));
        add(6, () -> {
            var sel = HologramWebSession.current().editor().selection();
            check("select over WS moves the editor's selection", sel.kind().equals("layer") && sel.layer() == 1,
                    String.valueOf(sel));
            JsonObject msg = ws.last("selection");
            check("selection pushed back", msg != null && msg.get("layer").getAsInt() == 1, String.valueOf(msg));
            send("{\"type\":\"select\",\"kind\":\"layer\",\"layer\":0,\"seq\":" + (++seq) + "}");
        });

        // ---- refused input ----
        add(4, () -> {
            JsonObject bad = spec(ws.last("state")).deepCopy();
            bad.getAsJsonArray("layers").get(1).getAsJsonObject().addProperty("id", "minecraft:definitely_not_a_block");
            sendEdit("gBad1", bad);
            JsonObject badProp = spec(ws.last("state")).deepCopy();
            JsonObject p = new JsonObject();
            p.addProperty("facing", "north");
            badProp.getAsJsonArray("layers").get(0).getAsJsonObject().add("props", p);
            sendEdit("gBad2", badProp);
            JsonObject wrongType = spec(ws.last("state")).deepCopy();
            wrongType.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("scale", "huge");
            sendEdit("gBad3", wrongType);
            JsonObject tooMany = spec(ws.last("state")).deepCopy();
            JsonArray ls = tooMany.getAsJsonArray("layers");
            while (ls.size() <= HologramLayer.MAX_LAYERS)
                ls.add(ls.get(0).deepCopy());
            sendEdit("gBad4", tooMany);
            send("this is not json");
        });
        add(8, () -> {
            long errors = ws.msgs.stream().filter(m -> "error".equals(type(m))).count();
            check("invalid edits answered with errors", errors >= 5, errors + " errors: " + ws.types());
            HologramSpec cur = editor.current();
            check("invalid edits change nothing", cur.layers().size() == 2
                    && cur.layers().get(1).id().equals("minecraft:glowstone")
                    && cur.layers().get(0).props().isEmpty()
                    && Math.abs(cur.layers().get(0).scale() - 1.75F) < 1e-3, cur.layers().toString());
            // Data NBT can only come from the game: an invented key is dropped, not trusted.
            JsonObject forged = spec(ws.last("state")).deepCopy();
            JsonObject data = new JsonObject();
            data.addProperty("key", "deadbeef-40");
            data.addProperty("snbt", "{Items:[{id:\"minecraft:diamond\",count:64}]}");
            forged.getAsJsonArray("layers").get(1).getAsJsonObject().add("data", data);
            JsonObject huge = forged.getAsJsonArray("layers").get(0).getAsJsonObject();
            huge.addProperty("scale", 1.0E9);
            sendEdit("gForge", forged);
        });
        add(6, () -> {
            HologramSpec cur = editor.current();
            check("forged data key carries no NBT", cur.layers().get(1).data().isEmpty(), cur.layers().get(1).data().toString());
            check("out-of-range numbers are clamped", Math.abs(cur.layers().get(0).scale() - 8.0F) < 1e-3,
                    "scale " + cur.layers().get(0).scale());
            send("{\"type\":\"undo\",\"seq\":" + (++seq) + "}");
        });

        // ---- limits: message rate and frame size, on a second connection ----
        add(4, () -> {
            check("undo after the forged edit", Math.abs(layer0Scale() - 1.75F) < 1e-3, "scale " + layer0Scale());
            extra = connect(wsUrl(), origin);
            check("second client connects", extra != null, "refused");
        });
        add(6, () -> {
            if (extra == null)
                return;
            try {
                for (int i = 0; i < 700; i++)
                    extra.ws.sendText("{\"type\":\"ping\",\"seq\":" + (i + 1) + "}", true).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                check("burst of pings sent", false, e.toString());
            }
        });
        add(10, () -> {
            if (extra == null)
                return;
            long pongs = extra.count("pong");
            boolean rate = extra.msgs.stream().anyMatch(m -> "error".equals(type(m)) && "rate".equals(m.get("code").getAsString()));
            check("message rate is limited", rate && pongs < 700, "pongs " + pongs + ", rate error " + rate);
            log("info burst of 700 pings: " + pongs + " answered");
            try {
                extra.ws.sendText("x".repeat((1 << 20) + 4096), true).get(3, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the server may already be closing
            }
        });
        add(10, () -> {
            if (extra == null)
                return;
            check("oversized frame closes with 1009", extra.closeCode == 1009, "close " + extra.closeCode);
            check("first client unaffected", ws.closeCode < 0, "close " + ws.closeCode);
        });

        // ---- save from the browser ----
        add(2, () -> send("{\"type\":\"save\",\"seq\":" + (++seq) + "}"));
        add(10, () -> {
            JsonObject closed = ws.last("closed");
            check("save ends the session", closed != null && "saved".equals(closed.get("reason").getAsString()),
                    String.valueOf(closed));
            check("save closes the in-game editor", Minecraft.getInstance().screen == null,
                    String.valueOf(Minecraft.getInstance().screen));
            check("no session after save", HologramWebSession.current() == null, "still running");
            HologramSpec saved = onServer(p -> Holograms.get(p.serverLevel(), hologramId));
            check("server stored the hologram", saved != null, "null");
            if (saved != null) {
                check("server has both layers", saved.layers().size() == 2
                        && saved.layers().get(1).id().equals("minecraft:glowstone"), saved.layers().toString());
                check("server has the in-game scale", Math.abs(saved.layers().get(0).scale() - 1.75F) < 1e-3,
                        "scale " + saved.layers().get(0).scale());
                check("server has the browser's SPIN", saved.layers().get(0).effects().stream()
                        .anyMatch(e -> e.type() == HologramEffect.Type.SPIN), saved.layers().get(0).effects().toString());
            }
        });
        add(4, () -> {
            check("port closed after the session", !reachable(firstPort), "port " + firstPort + " still open");
            check("socket closed after save", ws.closeCode > 0 || ws.ws.isInputClosed(), "close " + ws.closeCode);
        });
        add(20, () -> shot("web-saved-world.png"));

        // ---- Creator: its Web button, a fresh token, and closing the editor stops the server ----
        add(4, () -> {
            HologramEditorPrefs.setStartInCreator(true);
            command("foxstweaks hologram edit " + hologramId);
        });
        add(10, () -> {
            var c = Minecraft.getInstance().screen instanceof HologramCreatorScreen cs ? cs : null;
            check("edit opens the Creator", c != null, String.valueOf(Minecraft.getInstance().screen));
            if (c == null)
                return;
            boolean clicked = c.devClick("Open the advanced editor", 0);
            check("Creator Web button clickable", clicked, "no button");
            HologramWebSession s = HologramWebSession.current();
            check("Creator Web button starts a session", s != null && HologramWebBridge.activeFor(c.editor()), String.valueOf(s));
            if (s == null)
                return;
            port = s.port();
            token = s.token();
            origin = "http://127.0.0.1:" + port;
            check("new session gets a new token", !token.equals(firstToken), "same token");
            expect("old token refused by the new session", get("/" + firstToken + "/"), 403);
            ws = connect(wsUrl(), origin);
            check("WS connects to the Creator's session", ws != null, "refused");
        });
        add(8, () -> {
            check("Creator session says hello", ws != null && ws.last("hello") != null, ws == null ? "-" : ws.types());
            shot("web-creator-button.png");
        });
        add(4, () -> {
            var screen = Minecraft.getInstance().screen;
            if (screen != null)
                screen.onClose(); // Esc
        });
        add(10, () -> {
            check("closing the editor ends the session", HologramWebSession.current() == null, "still running");
            check("browser told the session closed", ws != null && ws.last("closed") != null, ws == null ? "-" : ws.types());
            check("port closed after the editor closed", !reachable(port), "port " + port + " still open");
        });

        // ---- client command: /foxstweaks hologram web <id> opens the editor and the session ----
        add(4, () -> Minecraft.getInstance().player.connection.sendCommand("foxstweaks hologram web " + hologramId));
        add(30, () -> {
            HologramWebSession s = HologramWebSession.current();
            var screen = Minecraft.getInstance().screen;
            check("command opens the editor", screen instanceof HologramScreen || screen instanceof HologramCreatorScreen,
                    String.valueOf(screen));
            check("command starts a session for that hologram", s != null && hologramId.equals(s.editor().spec().id()),
                    String.valueOf(s));
            if (s == null)
                return;
            port = s.port();
            token = s.token();
            origin = "http://127.0.0.1:" + port;
            ws = connect(wsUrl(), origin);
        });
        add(8, () -> send("{\"type\":\"cancel\",\"seq\":1}"));
        add(10, () -> {
            JsonObject closed = ws == null ? null : ws.last("closed");
            check("cancel over WS ends the session", closed != null && "cancelled".equals(closed.get("reason").getAsString()),
                    String.valueOf(closed));
            check("cancel closes the editor", Minecraft.getInstance().screen == null, String.valueOf(Minecraft.getInstance().screen));
            check("port closed after cancel", !reachable(port), "port " + port + " still open");
            HologramSpec saved = onServer(p -> Holograms.get(p.serverLevel(), hologramId));
            check("cancel keeps the saved hologram", saved != null && saved.layers().size() == 2, String.valueOf(saved));
        });

        add(6, () -> {
            log("done: " + (failures == 0 ? "ALL PASS" : failures + " FAILURE(S)"));
            try {
                Files.write(Minecraft.getInstance().gameDirectory.toPath()
                        .resolve("foxstweaks-hologram-web-check.txt"), REPORT);
            } catch (IOException e) {
                LOG.error("report", e);
            }
            Minecraft.getInstance().stop();
        });
    }

    // ---------------------------------------------------------------- protocol helpers

    private static String wsUrl() {
        return "ws://127.0.0.1:" + port + "/" + token + "/ws";
    }

    private static JsonObject spec(@Nullable JsonObject state) {
        return state == null ? new JsonObject() : state.getAsJsonObject("spec");
    }

    private static JsonArray layers(JsonObject state) {
        return spec(state).getAsJsonArray("layers");
    }

    private static float layer0Scale() {
        return editor.current().layers().get(0).scale();
    }

    private static JsonObject withScale(double scale) {
        JsonObject spec = spec(ws.last("state")).deepCopy();
        spec.getAsJsonArray("layers").get(0).getAsJsonObject().addProperty("scale", scale);
        return spec;
    }

    private static JsonObject effectDefaults(String type) {
        JsonObject hello = ws.last("hello");
        for (JsonElement e : hello.getAsJsonObject("registries").getAsJsonArray("effects")) {
            if (e.getAsJsonObject().get("type").getAsString().equals(type))
                return e.getAsJsonObject().getAsJsonObject("defaults").deepCopy();
        }
        throw new IllegalStateException("no effect " + type);
    }

    private static void sendEdit(String gesture, JsonObject spec) {
        JsonObject msg = new JsonObject();
        msg.addProperty("type", "edit");
        msg.addProperty("seq", ++seq);
        msg.addProperty("gesture", gesture);
        msg.add("spec", spec);
        send(msg.toString());
    }

    private static void send(String text) {
        if (ws == null) {
            check("client connected", false, "no client");
            return;
        }
        try {
            ws.ws.sendText(text, true).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            check("send " + text.substring(0, Math.min(40, text.length())), false, e.toString());
        }
    }

    @Nullable
    private static String type(JsonObject m) {
        return m.has("type") ? m.get("type").getAsString() : null;
    }

    /** A java.net.http WebSocket that keeps every message it receives. */
    private static final class Client implements WebSocket.Listener {
        final List<JsonObject> msgs = new CopyOnWriteArrayList<>();
        private final StringBuilder buf = new StringBuilder();
        volatile int closeCode = -1;
        WebSocket ws;

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            this.buf.append(data);
            if (last) {
                try {
                    this.msgs.add(JsonParser.parseString(this.buf.toString()).getAsJsonObject());
                } catch (RuntimeException e) {
                    LOG.warn("HologramWebCheck: unparsable message {}", this.buf);
                }
                this.buf.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            this.closeCode = statusCode;
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (this.closeCode < 0)
                this.closeCode = 0;
        }

        @Nullable
        JsonObject last(String type) {
            for (int i = this.msgs.size() - 1; i >= 0; i--) {
                if (type.equals(type(this.msgs.get(i))))
                    return this.msgs.get(i);
            }
            return null;
        }

        long count(String type) {
            return this.msgs.stream().filter(m -> type.equals(type(m))).count();
        }

        String types() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (JsonObject m : this.msgs)
                counts.merge(String.valueOf(type(m)), 1, Integer::sum);
            return counts.toString();
        }
    }

    @Nullable
    private static Client connect(String url, @Nullable String originHeader) {
        Client c = new Client();
        try {
            WebSocket.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .newWebSocketBuilder();
            if (originHeader != null)
                b.header("Origin", originHeader);
            c.ws = b.buildAsync(URI.create(url), c).get(5, TimeUnit.SECONDS);
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** HTTP status the server answered a WebSocket handshake with: 101 when it upgraded, -1 on no answer. */
    private static int handshakeStatus(String url, @Nullable String originHeader) {
        try {
            WebSocket.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
                    .newWebSocketBuilder();
            if (originHeader != null)
                b.header("Origin", originHeader);
            WebSocket socket = b.buildAsync(URI.create(url), new Client()).get(5, TimeUnit.SECONDS);
            socket.abort();
            return 101;
        } catch (java.util.concurrent.ExecutionException e) {
            return e.getCause() instanceof java.net.http.WebSocketHandshakeException h ? h.getResponse().statusCode() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- raw HTTP

    private static Resp get(String path) {
        return http("GET", path, "127.0.0.1:" + port);
    }

    /** One request over a plain socket, so the Host header and odd paths go out exactly as written. */
    private static Resp http(String method, String path, String host) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(3000);
            OutputStream out = s.getOutputStream();
            out.write((method + " " + path + " HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n"
                    + (method.equals("POST") ? "Content-Length: 0\r\n" : "") + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            while (state < 4) {
                int b = in.read();
                if (b < 0)
                    break;
                head.write(b);
                state = (b == '\r' && (state == 0 || state == 2)) || (b == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            }
            String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
            String[] first = lines[0].split(" ");
            int status = first.length > 1 ? Integer.parseInt(first[1]) : -1;
            Map<String, String> headers = new LinkedHashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0)
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("content-length", "0"));
            byte[] body = in.readNBytes(length);
            return new Resp(status, headers, body);
        } catch (IOException | RuntimeException e) {
            return new Resp(-1, Map.of(), e.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean reachable(int p) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", p), 1000);
            return true;
        } catch (ConnectException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private static String describe(Resp r) {
        String text = r.text();
        return r.status + " " + r.headers.getOrDefault("content-type", "") + " " + r.body.length + "B "
                + text.substring(0, Math.min(80, text.length())).replace('\n', ' ');
    }

    private static void expect(String name, Resp r, int status) {
        check(name, r.status == status, describe(r));
    }

    // ---------------------------------------------------------------- harness

    private static void command(String cmd) {
        onServer(player -> {
            player.server.getCommands().performPrefixedCommand(player.createCommandSourceStack().withPermission(4), cmd);
            return null;
        });
    }

    private static void shot(String name) {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
        });
    }

    private static void guiScale(int scale) {
        Minecraft mc = Minecraft.getInstance();
        mc.options.guiScale().set(scale);
        mc.resizeDisplay();
    }

    private static void add(int waitTicks, Runnable run) {
        STEPS.add(new Step(waitTicks, run));
    }

    private static <T> T onServer(Function<ServerPlayer, T> fn) {
        var mc = Minecraft.getInstance();
        var server = mc.getSingleplayerServer();
        return server.submit(() -> fn.apply(server.getPlayerList().getPlayer(mc.player.getUUID()))).join();
    }

    private static void log(String line) {
        LOG.info("HologramWebCheck {}", line);
        REPORT.add(line);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok)
            failures++;
        log((ok ? "PASS " : "FAIL ") + name + (ok ? "" : "  -> " + detail));
    }

    private static void onTick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        mc.options.pauseOnLostFocus = false;
        mc.options.getSoundSourceOptionInstance(SoundSource.MASTER).set(0.0);

        if (!worldRequested && mc.getOverlay() == null
                && (mc.screen instanceof TitleScreen || mc.screen instanceof AccessibilityOnboardingScreen)) {
            worldRequested = true;
            createWorld(mc);
        }

        if (mc.player == null || mc.getSingleplayerServer() == null || index >= STEPS.size())
            return;

        tick++;
        if (nextAt == 0)
            nextAt = tick + STEPS.get(0).waitTicks;
        if (tick < nextAt)
            return;

        var step = STEPS.get(index++);
        try {
            step.run.run();
        } catch (Throwable t) {
            LOG.error("HologramWebCheck crashed", t);
            check("no exception", false, t.toString());
            index = STEPS.size() - 1;
            nextAt = tick;
            return;
        }
        if (index < STEPS.size())
            nextAt = tick + STEPS.get(index).waitTicks;
    }

    private static void createWorld(Minecraft mc) {
        var world = "foxstweaks-webcheck";
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(world);
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        var settings = new LevelSettings(world, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        mc.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(0L, false, false),
                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                        .value().createWorldDimensions(),
                new TitleScreen());
    }

    private HologramWebCheck() {
    }
}
