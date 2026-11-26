package dev.watdafawx.foxstweaks.client.web;

import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * The web companion's local server: static files from the mod jar plus one WebSocket endpoint, on one
 * loopback port.
 *
 * <p>Not {@code com.sun.net.httpserver}: it cannot upgrade a connection to a WebSocket, and two servers would
 * need two ports and two origins. A GET-only HTTP/1.1 parser is a few dozen lines, so the whole thing is one
 * {@link ServerSocket} bound to {@code 127.0.0.1}, port chosen by the OS.
 *
 * <p>Security model (the browser is a second, untrusted client of the editor):
 * <ul>
 *     <li>Bound to loopback only; nothing off the machine can connect.</li>
 *     <li>Every URL starts with a random 192-bit token ({@link SecureRandom}), compared in constant time. Without
 *     it every request is a 403 - so another local page cannot guess its way in, and a page from another
 *     origin cannot read the responses anyway.</li>
 *     <li>{@code Host} must be exactly {@code 127.0.0.1:<port>} on every request (blocks DNS rebinding), and the
 *     WebSocket upgrade must also carry {@code Origin: http://127.0.0.1:<port>} (blocks cross-site WebSocket
 *     hijacking, which the token alone would already stop).</li>
 *     <li>Static files come only from {@code assets/foxstweaks/web/} inside the mod file, by a strict name pattern
 *     (no {@code ..}, no {@code %}, no backslash, known extensions only) and a normalised-prefix check.</li>
 *     <li>Inbound WebSocket messages are capped at 1 MB and rate limited ({@link WebSocketConnection}).</li>
 *     <li>The server dies with the editor session; a new session gets a new port and token.</li>
 * </ul>
 */
final class WebServer {
    private static final Logger LOG = LogUtils.getLogger();

    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_CONNECTIONS = 24;
    private static final int MAX_SOCKETS = 4;
    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final Pattern FILE = Pattern.compile(
            "(?:[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}/){0,4}[A-Za-z0-9_-][A-Za-z0-9_.-]{0,63}\\.(html|js|css|svg|png|ico|json|woff2|txt|map)");
    private static final Map<String, String> TYPES = Map.of(
            "html", "text/html; charset=utf-8",
            "js", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "json", "application/json; charset=utf-8",
            "woff2", "font/woff2",
            "txt", "text/plain; charset=utf-8",
            "map", "application/json; charset=utf-8");

    interface Listener {
        void onOpen(WebSocketConnection connection);

        void onMessage(WebSocketConnection connection, String text);

        void onRateLimited(WebSocketConnection connection);

        void onClose(WebSocketConnection connection);
    }

    private final ServerSocket serverSocket;
    private final String token;
    private final byte[] tokenBytes;
    private final int port;
    private final Listener listener;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final List<WebSocketConnection> connections = new CopyOnWriteArrayList<>();
    private final AtomicInteger open = new AtomicInteger();
    private final Map<String, byte[]> cache = new ConcurrentHashMap<>();
    @Nullable
    private final Path root;
    private volatile boolean running = true;

    private WebServer(ServerSocket serverSocket, String token, Listener listener) {
        this.serverSocket = serverSocket;
        this.token = token;
        this.tokenBytes = token.getBytes(StandardCharsets.US_ASCII);
        this.port = serverSocket.getLocalPort();
        this.listener = listener;
        this.root = findRoot();
    }

    static WebServer start(Listener listener) throws IOException {
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(false);
        // The literal IPv4 loopback, not getLoopbackAddress(): that one answers ::1 on a JVM that prefers IPv6,
        // and the URL, the Host check and the CSP all say 127.0.0.1 - the browser would get "connection refused".
        socket.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 16);
        LOG.debug("Hologram web editor bound to {}", socket.getLocalSocketAddress());
        byte[] raw = new byte[24];
        new SecureRandom().nextBytes(raw);
        WebServer server = new WebServer(socket, Base64.getUrlEncoder().withoutPadding().encodeToString(raw), listener);
        Thread.ofPlatform().daemon().name("foxstweaks-hologram-web").start(server::acceptLoop);
        return server;
    }

    int port() {
        return this.port;
    }

    String token() {
        return this.token;
    }

    String origin() {
        return "http://127.0.0.1:" + this.port;
    }

    String url() {
        return origin() + "/" + this.token + "/";
    }

    boolean running() {
        return this.running;
    }

    List<WebSocketConnection> connections() {
        return this.connections;
    }

    void stop() {
        this.running = false;
        try {
            this.serverSocket.close();
        } catch (IOException ignored) {
        }
        for (WebSocketConnection c : this.connections)
            c.close(1001);
        // Give queued close frames a moment, then cut every socket still open.
        Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
            }
            for (Socket s : this.sockets) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        });
    }

    // ---------------------------------------------------------------- accept / HTTP

    private void acceptLoop() {
        while (this.running) {
            Socket s;
            try {
                s = this.serverSocket.accept();
            } catch (IOException e) {
                if (this.running)
                    LOG.warn("Hologram web editor: accept failed", e);
                break;
            }
            if (!s.getInetAddress().isLoopbackAddress() || this.open.get() >= MAX_CONNECTIONS) {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            this.open.incrementAndGet();
            this.sockets.add(s);
            Thread.ofVirtual().name("foxstweaks-hologram-web-conn").start(() -> {
                try {
                    handle(s);
                } catch (SocketException ignored) {
                } catch (Throwable t) {
                    LOG.debug("Hologram web editor: connection error", t);
                } finally {
                    this.sockets.remove(s);
                    this.open.decrementAndGet();
                    try {
                        s.close();
                    } catch (IOException ignored) {
                    }
                }
            });
        }
    }

    private record Request(String method, String target, Map<String, String> headers) {
        String header(String name) {
            return this.headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
        }
    }

    private void handle(Socket s) throws IOException {
        s.setSoTimeout(10_000);
        s.setTcpNoDelay(true);
        InputStream in = new BufferedInputStream(s.getInputStream());
        OutputStream out = new BufferedOutputStream(s.getOutputStream());
        Request req = readRequest(in);
        if (req == null) {
            respond(out, 400, "text/plain; charset=utf-8", "Bad request".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        // DNS rebinding: a page on evil.example resolved to 127.0.0.1 still sends Host: evil.example.
        if (!req.header("host").equals("127.0.0.1:" + this.port)) {
            forbidden(out);
            return;
        }
        if (!req.method.equals("GET")) {
            respond(out, 405, "text/plain; charset=utf-8", "Method not allowed".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        String target = req.target;
        int q = target.indexOf('?');
        if (q >= 0)
            target = target.substring(0, q);
        if (!target.startsWith("/")) {
            forbidden(out);
            return;
        }
        int slash = target.indexOf('/', 1);
        String given = slash < 0 ? target.substring(1) : target.substring(1, slash);
        if (!MessageDigest.isEqual(given.getBytes(StandardCharsets.US_ASCII), this.tokenBytes)) {
            forbidden(out);
            return;
        }
        if (slash < 0) {
            // "/<token>" -> "/<token>/" so the page's relative asset URLs carry the token too.
            respond(out, 302, "text/plain; charset=utf-8", new byte[0], Map.of("Location", "/" + this.token + "/"));
            return;
        }
        String rest = target.substring(slash + 1);

        if (rest.equals("ws")) {
            upgrade(s, in, out, req);
            return;
        }
        if (rest.isEmpty())
            rest = "index.html";
        if (rest.length() > 256 || !FILE.matcher(rest).matches()) {
            forbidden(out);
            return;
        }
        byte[] body = read(rest);
        if (body == null) {
            respond(out, 404, "text/plain; charset=utf-8", "Not found".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        String ext = rest.substring(rest.lastIndexOf('.') + 1);
        Map<String, String> extra = ext.equals("html") ? Map.of("Content-Security-Policy", csp()) : null;
        respond(out, 200, TYPES.getOrDefault(ext, "application/octet-stream"), body, extra);
    }

    private String csp() {
        return "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; "
                // blob: media: a video the page converts itself is played from an object URL, never fetched.
                + "media-src blob:; font-src 'self'; connect-src ws://127.0.0.1:" + this.port + "; base-uri 'none'; "
                + "form-action 'none'; frame-ancestors 'none'";
    }

    @Nullable
    private static Request readRequest(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0)
                return null;
            head.write(b);
            if (head.size() > MAX_HEADER_BYTES)
                return null;
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1, 3 -> b == '\n' ? state + 1 : (b == '\r' ? 1 : 0);
                default -> 0;
            };
        }
        String[] lines = head.toString(StandardCharsets.ISO_8859_1).split("\r\n");
        String[] first = lines[0].split(" ");
        if (first.length != 3 || !first[2].startsWith("HTTP/1."))
            return null;
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon <= 0)
                continue;
            String name = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
            if (headers.containsKey(name) && (name.equals("host") || name.equals("origin")))
                return null; // two Host or Origin headers: refuse rather than guess which one counts
            headers.put(name, lines[i].substring(colon + 1).trim());
        }
        String target = first[1];
        if (target.indexOf('%') >= 0 || target.indexOf('\\') >= 0)
            return new Request(first[0], "/", headers); // no percent-encoding in any path we serve
        return new Request(first[0], target, headers);
    }

    private static void forbidden(OutputStream out) throws IOException {
        respond(out, 403, "text/plain; charset=utf-8", "Forbidden".getBytes(StandardCharsets.UTF_8), null);
    }

    private static void respond(OutputStream out, int status, String type, byte[] body,
                                @Nullable Map<String, String> extra) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        sb.append("Content-Type: ").append(type).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("X-Content-Type-Options: nosniff\r\n");
        sb.append("Referrer-Policy: no-referrer\r\n");
        sb.append("X-Frame-Options: DENY\r\n");
        sb.append("Cross-Origin-Resource-Policy: same-origin\r\n");
        sb.append("Connection: close\r\n");
        if (extra != null)
            extra.forEach((k, v) -> sb.append(k).append(": ").append(v).append("\r\n"));
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        return switch (status) {
            case 101 -> "Switching Protocols";
            case 200 -> "OK";
            case 302 -> "Found";
            case 400 -> "Bad Request";
            case 403 -> "Forbidden";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 426 -> "Upgrade Required";
            case 429 -> "Too Many Requests";
            default -> "Error";
        };
    }

    // ---------------------------------------------------------------- static files

    @Nullable
    private static Path findRoot() {
        try {
            var info = ModList.get().getModFileById(FoxsTweaks.MODID);
            if (info == null)
                return null;
            Path root = info.getFile().findResource("assets", FoxsTweaks.MODID, "web");
            return Files.isDirectory(root) ? root : null;
        } catch (Exception e) {
            LOG.warn("Hologram web editor: cannot find the bundled web app", e);
            return null;
        }
    }

    @Nullable
    private byte[] read(String relative) {
        if (this.root == null)
            return null;
        byte[] cached = this.cache.get(relative);
        if (cached != null)
            return cached;
        try {
            Path file = this.root;
            for (String part : relative.split("/"))
                file = file.resolve(part);
            Path normal = file.normalize();
            if (!normal.startsWith(this.root.normalize()) || !Files.isRegularFile(normal))
                return null;
            byte[] bytes = Files.readAllBytes(normal);
            this.cache.put(relative, bytes);
            return bytes;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- WebSocket upgrade

    private void upgrade(Socket s, InputStream in, OutputStream out, Request req) throws IOException {
        // Cross-site WebSocket hijacking: browsers always send Origin on a WebSocket handshake.
        if (!req.header("origin").equals(origin())) {
            forbidden(out);
            return;
        }
        String key = req.header("sec-websocket-key");
        if (!req.header("upgrade").equalsIgnoreCase("websocket")
                || !req.header("connection").toLowerCase(Locale.ROOT).contains("upgrade")
                || !req.header("sec-websocket-version").equals("13") || key.isEmpty() || key.length() > 64) {
            respond(out, 426, "text/plain; charset=utf-8", "WebSocket upgrade required".getBytes(StandardCharsets.UTF_8),
                    Map.of("Sec-WebSocket-Version", "13"));
            return;
        }
        if (this.connections.size() >= MAX_SOCKETS) {
            respond(out, 429, "text/plain; charset=utf-8", "Too many editor tabs".getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        s.setSoTimeout(0);
        WebSocketConnection conn = new WebSocketConnection(s, in, out);
        this.connections.add(conn);
        Thread writer = Thread.ofVirtual().name("foxstweaks-hologram-web-write").start(conn::writeLoop);
        try {
            this.listener.onOpen(conn);
            conn.readLoop(text -> this.listener.onMessage(conn, text), () -> this.listener.onRateLimited(conn));
        } finally {
            this.connections.remove(conn);
            conn.close(1000);
            this.listener.onClose(conn);
            try {
                writer.join(500);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
