package dev.watdafawx.foxstweaks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.watdafawx.foxstweaks.client.asset.HologramAssetImport;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The world's asset library (pictures, GIFs, converted videos) as a grid, opened from the Creator. A click adds the
 * picture as an image layer. New files come in three ways, all through the one upload path the web editor uses:
 * drag a file onto the window, or type a file path or http(s) address and press Upload.
 *
 * <p>It owns no hologram state: it only calls {@link HologramScreen#creatorAddLayer} and goes back to its caller.
 */
public final class HologramAssetScreen extends Screen {
    private static final int PANEL = 0xF00B1118;
    private static final int PANEL_EDGE = 0xFF1E2733;
    private static final int TILE = 0xFF121A24;
    private static final int TILE_HOVER = 0xFF1B2735;
    private static final int ACCENT = 0xFF4CC9F0;
    private static final int TEXT = 0xFFE8EDF5;
    private static final int MUTED = 0xFF8B97A8;
    private static final int FAINT = 0xFF5A6575;
    private static final int BAD = 0xFFFF6B6B;
    private static final int GOOD = 0xFF7DDC8B;
    private static final int DIM = 0x99000000;

    private static final int CELL = 64;
    private static final int GAP = 6;
    private static final int LABEL = 20;

    private record Asset(String id, String name, String type, int width, int height, int frames) {
    }

    private final HologramScreen editor;
    private final Screen returnTo;
    private final List<Asset> assets = new ArrayList<>();
    private final String firstImport;

    private EditBox source;
    private boolean canUpload = true;
    private boolean loading = true;
    private int total;
    private String status = "";
    private int statusColour = MUTED;
    private double scroll;
    private int px0, py0, px1, py1, gridTop, gridBottom, cols;
    private boolean opened;

    /** {@code firstImport}: a path to upload as soon as the screen is up (a file dropped on the Creator). May be null. */
    public HologramAssetScreen(HologramScreen editor, Screen returnTo, String firstImport) {
        super(Component.literal("Pictures"));
        this.editor = editor;
        this.returnTo = returnTo;
        this.firstImport = firstImport;
    }

    @Override
    protected void init() {
        int w = Math.min(this.width - 16, 372);
        int h = this.height - 24;
        this.px0 = (this.width - w) / 2;
        this.py0 = 12;
        this.px1 = this.px0 + w;
        this.py1 = this.py0 + h;
        this.gridTop = this.py0 + 22;
        this.gridBottom = this.py1 - 50;
        this.cols = Math.max(1, (w - 12 + GAP) / (CELL + GAP));

        String keep = this.source == null ? "" : this.source.getValue();
        this.source = new EditBox(this.font, this.px0 + 8, this.py1 - 36, w - 16 - 52, 14, Component.literal("Path or address"));
        this.source.setMaxLength(1024);
        this.source.setHint(Component.literal("File path or http(s) address"));
        this.source.setValue(keep);
        this.addRenderableWidget(this.source);

        if (!this.opened) {
            this.opened = true;
            reload();
            if (this.firstImport != null && !this.firstImport.isEmpty())
                startImport(this.firstImport);
        }
    }

    private void reload() {
        this.assets.clear();
        this.loading = true;
        page(0);
    }

    private void page(int offset) {
        HologramAssets.requestListing(offset, json -> {
            if (json.has("error")) {
                this.loading = false;
                say(json.get("error").getAsString(), BAD);
                return;
            }
            this.canUpload = !json.has("canUpload") || json.get("canUpload").getAsBoolean();
            this.total = json.has("count") ? json.get("count").getAsInt() : 0;
            JsonArray entries = json.has("entries") ? json.getAsJsonArray("entries") : new JsonArray();
            for (JsonElement e : entries) {
                JsonObject o = e.getAsJsonObject();
                this.assets.add(new Asset(o.get("id").getAsString(), o.has("name") ? o.get("name").getAsString() : "",
                        o.has("type") ? o.get("type").getAsString() : "", o.has("width") ? o.get("width").getAsInt() : 0,
                        o.has("height") ? o.get("height").getAsInt() : 0, o.has("frames") ? o.get("frames").getAsInt() : 1));
            }
            if (!entries.isEmpty() && this.assets.size() < this.total)
                page(this.assets.size());
            else
                this.loading = false;
        });
    }

    private void say(String text, int colour) {
        this.status = text;
        this.statusColour = colour;
    }

    private void startImport(String text) {
        boolean started = HologramAssetImport.start(text, new HologramAssets.UploadListener() {
            @Override
            public void progress(int percent, String stage) {
                say(stage.equals("downloading") ? "Downloading..." : "Uploading " + percent + "% (" + stage + ")", MUTED);
            }

            @Override
            public void done(boolean ok, String message) {
                say(ok ? "Added to the library. Click it to use it." : message, ok ? GOOD : BAD);
                if (ok) {
                    HologramAssetScreen.this.source.setValue("");
                    reload();
                }
            }
        });
        if (!started)
            say("Another upload is still running", BAD);
    }

    // ---------------------------------------------------------------- input

    @Override
    public void onFilesDrop(List<Path> files) {
        if (!files.isEmpty())
            startImport(files.get(0).toString());
    }

    private int uploadX() {
        return this.px1 - 8 - 48;
    }

    private int tileX(int index) {
        return this.px0 + 8 + (index % this.cols) * (CELL + GAP);
    }

    private int tileY(int index) {
        return this.gridTop + (index / this.cols) * (CELL + LABEL + GAP) - (int) this.scroll;
    }

    private int contentHeight() {
        int rows = (this.assets.size() + this.cols - 1) / this.cols;
        return rows * (CELL + LABEL + GAP);
    }

    private Asset tileAt(double mx, double my) {
        if (my < this.gridTop || my >= this.gridBottom)
            return null;
        for (int i = 0; i < this.assets.size(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL + LABEL)
                return this.assets.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button))
            return true;
        if (button != 0)
            return false;
        if (mx >= uploadX() && mx < uploadX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21) {
            if (this.canUpload)
                startImport(this.source.getValue());
            else
                say("This server does not let you upload", BAD);
            return true;
        }
        Asset a = tileAt(mx, my);
        if (a != null) {
            if (this.editor != null)
                this.editor.creatorAddLayer(HologramLayer.image(a.id(), 0.0F));
            onClose();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        double max = Math.max(0, contentHeight() - (this.gridBottom - this.gridTop));
        this.scroll = Mth.clamp(this.scroll - dy * 24, 0, max);
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && this.source.isFocused()) {
            if (this.canUpload)
                startImport(this.source.getValue());
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null)
            this.minecraft.setScreen(this.returnTo);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------------- render

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, this.width, this.height, DIM);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        renderBackground(g, mx, my, partial);
        g.fill(this.px0, this.py0, this.px1, this.py1, PANEL);
        g.renderOutline(this.px0, this.py0, this.px1 - this.px0, this.py1 - this.py0, PANEL_EDGE);
        g.drawString(this.font, "PICTURES", this.px0 + 8, this.py0 + 7, ACCENT, false);
        String count = this.loading ? "loading..." : this.total + (this.total == 1 ? " in this world" : " in this world");
        g.drawString(this.font, count, this.px1 - 8 - this.font.width(count), this.py0 + 7, FAINT, false);

        g.enableScissor(this.px0 + 1, this.gridTop, this.px1 - 1, this.gridBottom);
        Asset hover = tileAt(mx, my);
        for (int i = 0; i < this.assets.size(); i++) {
            int y = tileY(i);
            if (y + CELL + LABEL < this.gridTop || y > this.gridBottom)
                continue;
            drawTile(g, this.assets.get(i), tileX(i), y, this.assets.get(i) == hover);
        }
        g.disableScissor();

        if (this.assets.isEmpty() && !this.loading) {
            String hint = "Nothing here yet. Drop a file on the window,";
            String hint2 = "or type a path or address below.";
            g.drawCenteredString(this.font, hint, (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
            g.drawCenteredString(this.font, hint2, (this.px0 + this.px1) / 2, this.gridTop + 42, MUTED);
        }
        double max = Math.max(0, contentHeight() - (this.gridBottom - this.gridTop));
        if (max > 0) {
            int trackH = this.gridBottom - this.gridTop;
            int barH = Math.max(12, (int) (trackH * trackH / (double) contentHeight()));
            int barY = this.gridTop + (int) ((trackH - barH) * (this.scroll / max));
            g.fill(this.px1 - 5, barY, this.px1 - 3, barY + barH, FAINT);
        }

        g.drawString(this.font, this.canUpload ? "Add a picture: drop a file here, or type a path / address" : "Uploads are not allowed for you here",
                this.px0 + 8, this.py1 - 48, MUTED, false);
        super.render(g, mx, my, partial);
        boolean overUpload = mx >= uploadX() && mx < uploadX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21;
        boolean busy = HologramAssetImport.busy();
        g.fill(uploadX(), this.py1 - 37, uploadX() + 48, this.py1 - 21,
                !this.canUpload || busy ? 0xFF1A222D : overUpload ? 0xFF2A7FA0 : 0xFF1F6A86);
        g.drawCenteredString(this.font, "Upload", uploadX() + 24, this.py1 - 33, this.canUpload && !busy ? TEXT : FAINT);

        String line = this.font.plainSubstrByWidth(this.status.isEmpty() ? "Click a picture to add it as a layer. Esc closes." : this.status,
                this.px1 - this.px0 - 16);
        g.drawString(this.font, line, this.px0 + 8, this.py1 - 15, this.status.isEmpty() ? FAINT : this.statusColour, false);
    }

    private void drawTile(GuiGraphics g, Asset a, int x, int y, boolean hover) {
        g.fill(x, y, x + CELL, y + CELL, hover ? TILE_HOVER : TILE);
        if (hover)
            g.renderOutline(x, y, CELL, CELL, ACCENT);

        // The first frame of the asset; it loads on demand, so scrolling to a tile is what starts its download.
        ResourceLocation tex = HologramAssets.texture(a.id(), 0.0);
        if (tex != null && a.width() > 0 && a.height() > 0) {
            double s = Math.min((CELL - 4) / (double) a.width(), (CELL - 4) / (double) a.height());
            int w = Math.max(1, (int) Math.round(a.width() * s));
            int h = Math.max(1, (int) Math.round(a.height() * s));
            g.blit(tex, x + (CELL - w) / 2, y + (CELL - h) / 2, w, h, 0.0F, 0.0F, a.width(), a.height(), a.width(), a.height());
        } else {
            g.drawCenteredString(this.font, "...", x + CELL / 2, y + CELL / 2 - 4, FAINT);
        }
        if (a.frames() > 1) {
            String badge = a.frames() + "f";
            g.fill(x + CELL - this.font.width(badge) - 5, y + 2, x + CELL - 2, y + 12, 0xAA000000);
            g.drawString(this.font, badge, x + CELL - this.font.width(badge) - 3, y + 3, ACCENT, false);
        }
        String name = a.name().isEmpty() ? a.id().substring(0, Math.min(8, a.id().length())) : a.name();
        g.drawString(this.font, this.font.plainSubstrByWidth(name, CELL), x, y + CELL + 2, TEXT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(
                a.width() + "x" + a.height() + (a.type().isEmpty() ? "" : " " + a.type().toLowerCase(Locale.ROOT)), CELL),
                x, y + CELL + 11, FAINT, false);
    }

    // ---------------------------------------------------------------- dev checks

    /** Dev checks: how many pictures the library page list holds. */
    public int devAssetCount() {
        return this.assets.size();
    }

    public boolean devLoading() {
        return this.loading;
    }

    public String devStatus() {
        return this.status;
    }

    public void devImport(String text) {
        startImport(text);
    }

    /** Dev checks: click the first tile, as a user would. */
    public boolean devPickFirst() {
        if (this.assets.isEmpty())
            return false;
        return mouseClicked(tileX(0) + 4, tileY(0) + 4, 0);
    }
}
