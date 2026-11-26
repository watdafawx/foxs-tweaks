package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.client.audio.AudioHub;
import dev.watdafawx.foxstweaks.client.audio.AudioPrefs;
import dev.watdafawx.foxstweaks.client.audio.SystemAudio;
import dev.watdafawx.foxstweaks.hologram.HologramAudioMod;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * This player's own audio settings for music-reactive holograms: whether this PC's sound may be listened to (off
 * until switched on), which device, and where a jukebox's distance is measured from. Opened from the ♪ panel and
 * with {@code /foxstweaks audio}. The settings live in {@code config/foxstweaks-audio.json} on this PC only.
 *
 * <p>Never the vanilla blur, like every hologram screen: it can be opened over the editor.
 */
public final class HologramAudioSettingsScreen extends Screen {

    private static final int PANEL_BG = 0xF00D1117;
    private static final int PANEL_BORDER = 0x40FFFFFF;
    private static final int SURFACE = 0xFF161C24;
    private static final int SURFACE_HOT = 0xFF212B38;
    private static final int SURFACE_ACTIVE = 0x404CC9F0;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int WARN = 0xFFF5C062;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int DANGER_TEXT = 0xFFFF7A85;

    private static final String PRIVACY = "System audio lets holograms react to what this PC plays (Spotify, a browser, "
            + "anything through your speakers) or to a microphone. It is off until you switch it on, it applies to "
            + "you only, and nothing is recorded, stored or sent anywhere: the sound is turned into five levels "
            + "(level, bass, mid, high, beat) on this PC and thrown away. Capture runs only while a hologram that "
            + "listens to it is in view, or this screen is open.";

    private record Hit(int x, int y, int w, int h, String label, Runnable click) {
        boolean contains(double mx, double my) {
            return mx >= this.x && mx < this.x + this.w && my >= this.y && my < this.y + this.h;
        }
    }

    @Nullable
    private final Screen returnTo;
    private final List<Hit> hits = new ArrayList<>();
    private final float[] meter = new float[5];
    private int deviceScroll;

    public HologramAudioSettingsScreen(@Nullable Screen returnTo) {
        super(Component.literal("Hologram audio"));
        this.returnTo = returnTo;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Drawn by render() itself: Screen#render would otherwise paint it again over the panel.
    }

    @Override
    public void tick() {
        if (this.returnTo instanceof HologramAudioScreen || this.returnTo instanceof HologramScreen
                || this.returnTo instanceof HologramCreatorScreen)
            this.returnTo.tick(); // keeps the editor's live preview flowing
    }

    @Override
    public void onClose() {
        if (this.minecraft != null)
            this.minecraft.setScreen(this.returnTo);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, this.width, this.height, 0x60000000);
        this.hits.clear();
        int w = Math.min(320, this.width - 20);
        int x0 = (this.width - w) / 2;
        int x = x0 + 8;
        int iw = w - 16;
        boolean on = AudioPrefs.systemEnabled();
        var privacy = this.font.split(Component.literal(PRIVACY), iw);
        // Height from the content (the device list only shows while system audio is on), so nothing spills.
        int deviceRows = on ? Math.min(5, Math.max(1, SystemAudio.devices().size())) : 0;
        int h = 7 + 13 + privacy.size() * 9 + 5 + 19 + (on ? 16 + deviceRows * 14 + 4 : 0) + 11 + 23 + 15 + 26;
        int y0 = Math.max(6, (this.height - h) / 2);
        int y1 = Math.min(this.height - 6, y0 + h);
        g.fill(x0, y0, x0 + w, y1, PANEL_BG);
        g.renderOutline(x0, y0, w, y1 - y0, PANEL_BORDER);
        int y = y0 + 7;
        g.drawString(this.font, "♪ Hologram audio", x, y, WARN, false);
        y += 13;
        for (var line : privacy) {
            g.drawString(this.font, line, x, y, MUTED, false);
            y += 9;
        }
        y += 5;

        button(g, mx, my, x, y, iw, 15, "System audio: " + (on ? "On" : "Off"), "system", on ? ACCENT : TEXT, () -> {
            AudioPrefs.setSystemEnabled(!AudioPrefs.systemEnabled());
            SystemAudio.reset();
        });
        y += 19;

        if (on) {
            String status = SystemAudio.status();
            g.drawString(this.font, ellipsize(status, iw - 60), x, y + 3, SystemAudio.failed() ? DANGER_TEXT : MUTED, false);
            button(g, mx, my, x + iw - 56, y, 56, 13, SystemAudio.failed() ? "Retry" : "Refresh", "refresh", TEXT, () -> {
                SystemAudio.refreshDevices();
                SystemAudio.reset();
            });
            y += 16;
            List<SystemAudio.Device> devices = SystemAudio.devices();
            int rows = Math.min(5, Math.max(1, devices.size()));
            this.deviceScroll = Mth.clamp(this.deviceScroll, 0, Math.max(0, devices.size() - rows));
            String chosen = AudioPrefs.deviceId();
            if (devices.isEmpty())
                g.drawString(this.font, "Looking for devices...", x, y + 3, MUTED, false);
            for (int r = 0; r < rows && r + this.deviceScroll < devices.size(); r++) {
                SystemAudio.Device d = devices.get(r + this.deviceScroll);
                boolean sel = d.id().equals(chosen);
                boolean hot = inside(mx, my, x, y + r * 14, iw, 13);
                g.fill(x, y + r * 14, x + iw, y + r * 14 + 13, sel ? SURFACE_ACTIVE : hot ? SURFACE_HOT : SURFACE);
                g.drawString(this.font, ellipsize((d.output() ? "▶ " : "● ") + d.name(), iw - 6), x + 3, y + r * 14 + 3,
                        sel ? TEXT : MUTED, false);
                this.hits.add(new Hit(x, y + r * 14, iw, 13, "device " + d.name(), () -> {
                    AudioPrefs.setDeviceId(d.id());
                    SystemAudio.reset();
                }));
            }
            y += rows * 14 + 4;
        }

        // Meter: what holograms set to "Any music" hear right now, from where you stand.
        AudioHub.meter(on ? HologramAudioMod.Source.SYSTEM : HologramAudioMod.Source.AUTO, this.meter);
        String[] names = { "Level", "Bass", "Mid", "High", "Beat" };
        g.drawString(this.font, on ? "System audio now:" : "Music now:", x, y, MUTED, false);
        y += 11;
        int bw = (iw - 4 * 4) / 5;
        for (int i = 0; i < 5; i++) {
            int bx = x + i * (bw + 4);
            g.fill(bx, y, bx + bw, y + 8, SURFACE);
            int fw = Math.round(Mth.clamp(this.meter[i], 0.0F, 1.0F) * bw);
            g.fill(bx, y, bx + fw, y + 8, i == 4 ? WARN : ACCENT);
            g.drawCenteredString(this.font, names[i], bx + bw / 2, y + 10, MUTED);
        }
        y += 23;

        boolean atPlayer = AudioPrefs.listenAtPlayer();
        button(g, mx, my, x, y, iw, 15, "Jukebox distance from: " + (atPlayer ? "me" : "each hologram"), "listen", TEXT,
                () -> AudioPrefs.setListenAtPlayer(!AudioPrefs.listenAtPlayer()));

        button(g, mx, my, x0 + w - 8 - 60, y1 - 19, 60, 14, "Done", "done", ACCENT, this::onClose);
        super.render(g, mx, my, partial);
    }

    private void button(GuiGraphics g, int mx, int my, int x, int y, int w, int h, String text, String key, int color,
                        Runnable click) {
        boolean hot = inside(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, hot ? SURFACE_HOT : SURFACE);
        g.drawCenteredString(this.font, text, x + w / 2, y + (h - 8) / 2, color);
        this.hits.add(new Hit(x, y, w, h, key, click));
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private String ellipsize(String s, int w) {
        if (this.font.width(s) <= w)
            return s;
        return this.font.plainSubstrByWidth(s, Math.max(0, w - this.font.width("..."))) + "...";
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (Hit h : this.hits) {
                if (h.contains(mx, my)) {
                    h.click().run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        this.deviceScroll -= (int) Math.signum(sy);
        return true;
    }

    /** Presses the control whose key starts with {@code prefix}, as laid out last frame. */
    public boolean devClick(String prefix) {
        for (Hit h : this.hits) {
            if (h.label().startsWith(prefix)) {
                h.click().run();
                return true;
            }
        }
        return false;
    }
}
