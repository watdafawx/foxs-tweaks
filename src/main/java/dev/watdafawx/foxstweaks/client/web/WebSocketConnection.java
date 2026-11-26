package dev.watdafawx.foxstweaks.client.web;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * One accepted RFC 6455 connection after the handshake: text frames in, text frames out, nothing else.
 *
 * <p>Deliberately minimal. No extensions are negotiated (a browser's {@code permessage-deflate} offer is simply
 * not answered, which RFC 6455 allows), binary frames are refused, client frames must be masked, and a
 * message may not exceed {@link #MAX_MESSAGE} bytes summed over its fragments. Writes go through a queue on
 * their own thread so the game thread never blocks on a slow or stalled browser.
 */
final class WebSocketConnection {
    private static final Logger LOG = LogUtils.getLogger();

    /** Largest inbound message, all fragments together. A full hologram spec is a few KB. */
    static final int MAX_MESSAGE = 1 << 20;
    /** Outbound frames queued before the connection is considered dead. */
    private static final int MAX_QUEUE = 512;
    /** Token bucket: sustained inbound messages per second, and the burst allowed on top. */
    private static final double RATE_PER_SEC = 120.0;
    private static final double RATE_BURST = 240.0;
    /** Dropped messages after which the connection is closed instead of only warned. */
    private static final int MAX_DROPS = 600;

    private static final byte[] CLOSE_SENTINEL = new byte[0];

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    private double tokens = RATE_BURST;
    private long lastRefill = System.nanoTime();
    private int drops;
    private long lastRateWarning;

    /** Last edit sequence number applied for this client; written and read on the game thread only. */
    long ack;
    /** Asked for the audio meter (a "meter" message with on=true); game thread only. */
    boolean meter;
    /** Source the meter reads; game thread only. */
    dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Source meterSource =
            dev.watdafawx.foxstweaks.hologram.HologramAudioMod.Source.AUTO;

    WebSocketConnection(Socket socket, InputStream in, OutputStream out) {
        this.socket = socket;
        this.in = in;
        this.out = out;
    }

    boolean isOpen() {
        return !this.closed;
    }

    /** Queues a text frame; never blocks. A client that lets the queue fill up is cut off. */
    void send(String text) {
        if (this.closed)
            return;
        if (this.queue.size() >= MAX_QUEUE) {
            LOG.warn("Hologram web editor: browser is not reading, closing the connection");
            close(1008);
            return;
        }
        this.queue.offer(frame(0x1, text.getBytes(StandardCharsets.UTF_8)));
    }

    void close(int code) {
        if (this.closed)
            return;
        this.closed = true;
        byte[] payload = { (byte) (code >> 8), (byte) code };
        this.queue.offer(frame(0x8, payload));
        this.queue.offer(CLOSE_SENTINEL);
    }

    /** Closes the socket at once, without a close frame. */
    void kill() {
        this.closed = true;
        this.queue.offer(CLOSE_SENTINEL);
        try {
            this.socket.close();
        } catch (IOException ignored) {
        }
    }

    void writeLoop() {
        try {
            while (true) {
                byte[] next = this.queue.poll(30, TimeUnit.SECONDS);
                if (next == null) {
                    if (this.closed)
                        break;
                    this.out.write(frame(0x9, new byte[0])); // ping keeps idle proxies and the browser honest
                    this.out.flush();
                    continue;
                }
                if (next == CLOSE_SENTINEL)
                    break;
                this.out.write(next);
                if (this.queue.isEmpty())
                    this.out.flush();
            }
            this.out.flush();
        } catch (IOException | InterruptedException ignored) {
        } finally {
            this.closed = true;
            try {
                this.socket.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Reads messages until the peer closes or breaks the protocol. Runs on the connection's own thread.
     * {@code onRateLimited} is told when a message was dropped by the rate limiter.
     */
    void readLoop(Consumer<String> onMessage, Runnable onRateLimited) throws IOException {
        ByteArrayOutputStream message = null;
        while (!this.closed) {
            int b0 = this.in.read();
            if (b0 < 0)
                return;
            int b1 = readByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            if ((b0 & 0x70) != 0) {
                close(1002); // no extension was negotiated, so no reserved bit may be set
                return;
            }
            if ((b1 & 0x80) == 0) {
                close(1002); // RFC 6455 5.1: a server must close on an unmasked client frame
                return;
            }
            long len = b1 & 0x7F;
            if (len == 126)
                len = (readByte() << 8) | readByte();
            else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++)
                    len = (len << 8) | readByte();
            }
            boolean control = (opcode & 0x8) != 0;
            if (control && (len > 125 || !fin)) {
                close(1002);
                return;
            }
            int already = message == null ? 0 : message.size();
            if (len < 0 || len + already > MAX_MESSAGE) {
                close(1009);
                return;
            }
            byte[] mask = { (byte) readByte(), (byte) readByte(), (byte) readByte(), (byte) readByte() };
            byte[] payload = this.in.readNBytes((int) len);
            if (payload.length != len)
                throw new EOFException();
            for (int i = 0; i < payload.length; i++)
                payload[i] ^= mask[i & 3];

            switch (opcode) {
                case 0x8 -> { // close: echo the code and stop
                    int code = payload.length >= 2 ? ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF) : 1000;
                    close(code == 1005 || code == 1006 || code == 1015 ? 1000 : code);
                    return;
                }
                case 0x9 -> this.queue.offer(frame(0xA, payload)); // ping -> pong
                case 0xA -> {
                }
                case 0x1 -> {
                    if (message != null) {
                        close(1002); // a new message before the last one finished
                        return;
                    }
                    message = new ByteArrayOutputStream();
                    message.write(payload);
                }
                case 0x0 -> {
                    if (message == null) {
                        close(1002);
                        return;
                    }
                    message.write(payload);
                }
                case 0x2 -> {
                    close(1003); // binary: nothing in this protocol is binary (yet)
                    return;
                }
                default -> {
                    close(1002);
                    return;
                }
            }

            if (fin && message != null && (opcode == 0x1 || opcode == 0x0)) {
                String text;
                try {
                    text = StandardCharsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(message.toByteArray())).toString();
                } catch (CharacterCodingException e) {
                    close(1007);
                    return;
                }
                message = null;
                if (takeToken()) {
                    onMessage.accept(text);
                } else {
                    this.drops++;
                    if (this.drops > MAX_DROPS) {
                        close(1008);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (now - this.lastRateWarning > 1000) {
                        this.lastRateWarning = now;
                        onRateLimited.run();
                    }
                }
            }
        }
    }

    private boolean takeToken() {
        long now = System.nanoTime();
        this.tokens = Math.min(RATE_BURST, this.tokens + (now - this.lastRefill) / 1.0E9 * RATE_PER_SEC);
        this.lastRefill = now;
        if (this.tokens < 1.0)
            return false;
        this.tokens -= 1.0;
        return true;
    }

    private int readByte() throws IOException {
        int b = this.in.read();
        if (b < 0)
            throw new EOFException();
        return b;
    }

    /** One unmasked, unfragmented server frame. */
    static byte[] frame(int opcode, byte[] payload) {
        int len = payload.length;
        int header = len < 126 ? 2 : (len <= 0xFFFF ? 4 : 10);
        byte[] f = new byte[header + len];
        f[0] = (byte) (0x80 | opcode);
        if (len < 126) {
            f[1] = (byte) len;
        } else if (len <= 0xFFFF) {
            f[1] = 126;
            f[2] = (byte) (len >> 8);
            f[3] = (byte) len;
        } else {
            f[1] = 127;
            long l = len;
            for (int i = 0; i < 8; i++)
                f[2 + i] = (byte) (l >> (56 - 8 * i));
        }
        System.arraycopy(payload, 0, f, header, len);
        return f;
    }
}
