package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramAttach;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Quick picker for holograms attached to the player or nearby in the world.
 * Opened by right-clicking the air with the Hologram Wand or pressing the Open Hologram Picker key.
 */
public final class HologramNearbyScreen extends Screen {
    private static final int DIM = 0x99000000;
    private static final int PANEL = 0xF0141820;
    private static final int EDGE = 0xFF2B3544;
    private static final int ROW_BG = 0xFF1A202B;
    private static final int ROW_HOVER = 0xFF232C3B;
    private static final int ACCENT = 0xFF4CC3E8;
    private static final int TEXT = 0xFFE6ECF2;
    private static final int MUTED = 0xFF8C9AAD;
    private static final int FAINT = 0xFF5C697A;
    private static final int BAD = 0xFFE06C75;

    private static final double NEARBY_RANGE = 32.0;
    private static final int ROW_H = 26;
    private static final long DELETE_ARM_MS = 3000L;

    private record Entry(HologramSpec spec, boolean attachedToSelf, double dist) {
    }

    private EditBox search;
    private boolean showAll;
    private double scroll;
    private int px0, py0, px1, py1, listTop, listBottom;
    private String armedDeleteId = "";
    private long armedDeleteUntil;

    public HologramNearbyScreen() {
        super(Component.literal("Holograms"));
    }

    @Override
    protected void init() {
        int w = Math.min(this.width - 20, 380);
        int h = Math.min(this.height - 24, 280);
        this.px0 = (this.width - w) / 2;
        this.py0 = (this.height - h) / 2;
        this.px1 = this.px0 + w;
        this.py1 = this.py0 + h;
        this.listTop = this.py0 + 44;
        this.listBottom = this.py1 - 22;

        String keep = this.search == null ? "" : this.search.getValue();
        this.search = new EditBox(this.font, this.px0 + 8, this.py0 + 22, w - 16 - 78, 15, Component.literal("Search"));
        this.search.setMaxLength(64);
        this.search.setHint(Component.literal("Search holograms..."));
        this.search.setValue(keep);
        this.search.setResponder(v -> this.scroll = 0);
        this.addRenderableWidget(this.search);
    }

    private List<Entry> entries() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null)
            return List.of();
        UUID self = mc.player.getUUID();
        Vec3 eye = mc.player.getEyePosition();
        String q = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);

        List<Entry> out = new ArrayList<>();
        for (HologramSpec spec : HologramClient.specs()) {
            boolean attachedSelf = spec.attach() != null && self.equals(spec.attach().playerUuid());
            AABB box = spec.box(mc.level, 1.0F);
            double dist = box == null ? Double.POSITIVE_INFINITY : Math.sqrt(eye.distanceToSqr(box.getCenter()));
            if (!attachedSelf && !this.showAll && dist > NEARBY_RANGE)
                continue;
            if (!q.isEmpty()) {
                String hay = (spec.id() + " " + spec.primaryId() + " " + spec.ownerName()
                        + (spec.attach() != null ? " " + spec.attach().playerName() : "")).toLowerCase(Locale.ROOT);
                if (!hay.contains(q))
                    continue;
            }
            out.add(new Entry(spec, attachedSelf, dist));
        }

        out.sort(Comparator.comparing((Entry e) -> !e.attachedToSelf())
                .thenComparingDouble(Entry::dist)
                .thenComparing(e -> e.spec().id()));
        return out;
    }

    private int filterX() {
        return this.px1 - 8 - 74;
    }

    @Override
    public void renderBackground(GuiGraphics g, int mx, int my, float pt) {
        g.fill(0, 0, this.width, this.height, DIM);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g, mx, my, pt);
        g.fill(this.px0, this.py0, this.px1, this.py1, PANEL);
        g.renderOutline(this.px0, this.py0, this.px1 - this.px0, this.py1 - this.py0, EDGE);

        List<Entry> list = entries();
        g.drawString(this.font, "HOLOGRAMS", this.px0 + 8, this.py0 + 7, ACCENT, false);
        String count = list.size() + (this.showAll ? " in dimension" : " nearby / attached");
        g.drawString(this.font, count, this.px1 - 8 - this.font.width(count), this.py0 + 7, FAINT, false);

        // Filter toggle button next to search box
        int fx = filterX();
        int fy = this.py0 + 22;
        boolean overFilter = mx >= fx && mx < fx + 74 && my >= fy && my < fy + 15;
        g.fill(fx, fy, fx + 74, fy + 15, this.showAll ? 0xFF1F5D75 : overFilter ? 0xFF283244 : 0xFF1D2430);
        g.renderOutline(fx, fy, 74, 15, this.showAll ? ACCENT : EDGE);
        g.drawCenteredString(this.font, this.showAll ? "All in dim" : "Nearby 32m", fx + 37, fy + 4, TEXT);

        super.render(g, mx, my, pt);

        int viewH = this.listBottom - this.listTop;
        int contentH = list.size() * ROW_H;
        double maxScroll = Math.max(0, contentH - viewH);
        this.scroll = Mth.clamp(this.scroll, 0, maxScroll);

        g.enableScissor(this.px0 + 1, this.listTop, this.px1 - 1, this.listBottom);
        if (list.isEmpty()) {
            g.drawCenteredString(this.font, "No holograms found.", (this.px0 + this.px1) / 2, this.listTop + 24, MUTED);
            g.drawCenteredString(this.font, "Right-click a block with the wand to create one.",
                    (this.px0 + this.px1) / 2, this.listTop + 36, FAINT);
        } else {
            long now = System.currentTimeMillis();
            if (now > this.armedDeleteUntil)
                this.armedDeleteId = "";

            int rowX = this.px0 + 8;
            int rowW = (this.px1 - this.px0) - 16 - (maxScroll > 0 ? 6 : 0);
            for (int i = 0; i < list.size(); i++) {
                int y = this.listTop + i * ROW_H - (int) this.scroll;
                if (y + ROW_H < this.listTop || y > this.listBottom)
                    continue;
                Entry e = list.get(i);
                HologramSpec spec = e.spec();
                boolean hover = mx >= rowX && mx < rowX + rowW && my >= y && my < y + ROW_H - 2
                        && my >= this.listTop && my < this.listBottom;
                g.fill(rowX, y, rowX + rowW, y + ROW_H - 2, hover ? ROW_HOVER : ROW_BG);
                if (e.attachedToSelf())
                    g.fill(rowX, y, rowX + 2, y + ROW_H - 2, ACCENT);

                int delW = 30;
                int attW = 44;
                int copyW = 32;
                int onW = 24;
                int editW = 30;
                int delX = rowX + rowW - 4 - delW;
                int attX = delX - 3 - attW;
                int copyX = attX - 3 - copyW;
                int onX = copyX - 3 - onW;
                int editX = onX - 3 - editW;
                int btnY = y + 4;
                int btnH = 16;

                int textMax = editX - rowX - 10;
                String title = spec.id() + (spec.enabled() ? "" : " (off)");
                g.drawString(this.font, this.font.plainSubstrByWidth(title, textMax), rowX + 6, y + 4,
                        spec.enabled() ? TEXT : FAINT, false);

                String sub;
                if (spec.attach() != null) {
                    HologramAttach a = spec.attach();
                    String who = e.attachedToSelf() ? "you" : (a.playerName().isEmpty() ? "player" : a.playerName());
                    sub = "Attached: " + who + " · " + a.follow().label + " · " + spec.layers().size() + "L";
                } else {
                    String distStr = Double.isFinite(e.dist()) ? String.format(Locale.ROOT, "%.1fm", e.dist()) : "?";
                    sub = distStr + " · " + spec.layers().size() + "L · " + spec.primaryId();
                }
                g.drawString(this.font, this.font.plainSubstrByWidth(sub, textMax), rowX + 6, y + 14,
                        e.attachedToSelf() ? ACCENT : MUTED, false);

                // Edit button
                boolean overEdit = hover && mx >= editX && mx < editX + editW && my >= btnY && my < btnY + btnH;
                g.fill(editX, btnY, editX + editW, btnY + btnH, overEdit ? 0xFF2A7FA0 : 0xFF1F6A86);
                g.drawCenteredString(this.font, "Edit", editX + editW / 2, btnY + 4, TEXT);

                // On/Off visibility toggle
                boolean overOn = hover && mx >= onX && mx < onX + onW && my >= btnY && my < btnY + btnH;
                g.fill(onX, btnY, onX + onW, btnY + btnH,
                        spec.enabled() ? (overOn ? 0xFF28503C : 0xFF1E3B2D) : (overOn ? 0xFF3A3038 : 0xFF252028));
                g.drawCenteredString(this.font, spec.enabled() ? "On" : "Off", onX + onW / 2, btnY + 4,
                        spec.enabled() ? 0xFF66E890 : FAINT);

                // Copy button
                boolean overCopy = hover && mx >= copyX && mx < copyX + copyW && my >= btnY && my < btnY + btnH;
                g.fill(copyX, btnY, copyX + copyW, btnY + btnH, overCopy ? 0xFF2E4054 : 0xFF212C3B);
                g.drawCenteredString(this.font, "Copy", copyX + copyW / 2, btnY + 4, TEXT);

                // Attach / Detach button
                boolean overAtt = hover && mx >= attX && mx < attX + attW && my >= btnY && my < btnY + btnH;
                boolean isAttached = spec.isAttached();
                g.fill(attX, btnY, attX + attW, btnY + btnH,
                        isAttached ? (overAtt ? 0xFF395066 : 0xFF28394A) : (overAtt ? 0xFF2E4054 : 0xFF212C3B));
                g.drawCenteredString(this.font, isAttached ? "Detach" : "Attach", attX + attW / 2, btnY + 4, TEXT);

                // Delete button (2-click confirm)
                boolean armed = spec.id().equals(this.armedDeleteId);
                boolean overDel = hover && mx >= delX && mx < delX + delW && my >= btnY && my < btnY + btnH;
                g.fill(delX, btnY, delX + delW, btnY + btnH,
                        armed ? 0xFFA82C36 : overDel ? 0xFF6B242B : 0xFF3A1D22);
                g.drawCenteredString(this.font, armed ? "Sure?" : "Del", delX + delW / 2, btnY + 4, armed ? TEXT : BAD);
            }
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(14, (int) (viewH * (double) viewH / contentH));
            int barY = this.listTop + (int) ((viewH - barH) * (this.scroll / maxScroll));
            g.fill(this.px1 - 6, barY, this.px1 - 4, barY + barH, FAINT);
        }

        g.drawString(this.font, "Click any hologram to edit · Esc closes", this.px0 + 8, this.py1 - 14, FAINT, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button))
            return true;
        if (button != 0)
            return false;

        int fx = filterX();
        int fy = this.py0 + 22;
        if (mx >= fx && mx < fx + 74 && my >= fy && my < fy + 15) {
            this.showAll = !this.showAll;
            this.scroll = 0;
            return true;
        }

        if (my < this.listTop || my >= this.listBottom)
            return false;

        List<Entry> list = entries();
        int viewH = this.listBottom - this.listTop;
        double maxScroll = Math.max(0, list.size() * ROW_H - viewH);
        int rowX = this.px0 + 8;
        int rowW = (this.px1 - this.px0) - 16 - (maxScroll > 0 ? 6 : 0);

        for (int i = 0; i < list.size(); i++) {
            int y = this.listTop + i * ROW_H - (int) this.scroll;
            if (mx < rowX || mx >= rowX + rowW || my < y || my >= y + ROW_H - 2)
                continue;
            HologramSpec spec = list.get(i).spec();

            int delW = 30;
            int attW = 44;
            int copyW = 32;
            int onW = 24;
            int delX = rowX + rowW - 4 - delW;
            int attX = delX - 3 - attW;
            int copyX = attX - 3 - copyW;
            int onX = copyX - 3 - onW;
            int btnY = y + 4;
            int btnH = 16;

            if (mx >= delX && mx < delX + delW && my >= btnY && my < btnY + btnH) {
                if (spec.id().equals(this.armedDeleteId) && System.currentTimeMillis() <= this.armedDeleteUntil) {
                    this.armedDeleteId = "";
                    HologramNetwork.sendDelete(spec.id());
                } else {
                    this.armedDeleteId = spec.id();
                    this.armedDeleteUntil = System.currentTimeMillis() + DELETE_ARM_MS;
                }
                return true;
            }
            if (mx >= attX && mx < attX + attW && my >= btnY && my < btnY + btnH) {
                runCmd(spec.isAttached()
                        ? "foxstweaks hologram detach " + spec.id()
                        : "foxstweaks hologram attach " + spec.id());
                return true;
            }
            if (mx >= copyX && mx < copyX + copyW && my >= btnY && my < btnY + btnH) {
                HologramClipboard.copy(spec);
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(Component.literal(
                            "Copied '" + spec.id() + "' — Shift+RClick a block with Wand to paste."), true);
                }
                onClose();
                return true;
            }
            if (mx >= onX && mx < onX + onW && my >= btnY && my < btnY + btnH) {
                HologramNetwork.sendSave(spec.withEnabled(!spec.enabled()));
                return true;
            }

            // Clicking Edit or anywhere else on the row opens the editor
            onClose();
            runCmd("foxstweaks hologram edit " + spec.id());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        List<Entry> list = entries();
        int viewH = this.listBottom - this.listTop;
        double maxScroll = Math.max(0, list.size() * ROW_H - viewH);
        this.scroll = Mth.clamp(this.scroll - dy * 24.0, 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key != GLFW.GLFW_KEY_ESCAPE && !(getFocused() instanceof EditBox)
                && FoxsTweaksKeys.OPEN_HOLOGRAMS.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private static void runCmd(String command) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.player.connection != null)
            mc.player.connection.sendCommand(command);
    }
}
