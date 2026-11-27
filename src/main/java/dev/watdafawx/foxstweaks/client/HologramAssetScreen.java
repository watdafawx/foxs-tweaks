package dev.watdafawx.foxstweaks.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.watdafawx.foxstweaks.client.asset.HologramAssetImport;
import dev.watdafawx.foxstweaks.client.asset.HologramAssetSearch;
import dev.watdafawx.foxstweaks.client.asset.HologramAssets;
import dev.watdafawx.foxstweaks.client.asset.HologramPackZip;
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
 * The world's asset library (pictures, GIFs, converted videos, 3D models) plus keyless online CDN lookup
 * (Openverse Images/GIFs, Khronos glTF-Sample-Assets, Poly Haven CC0 3D models).
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

    private static final String[] TABS = { "World", "Images", "GIFs", "3D" };

    private record Asset(String id, String name, String type, int width, int height, int frames) {
    }

    private final HologramScreen editor;
    private final Screen returnTo;
    private final List<Asset> assets = new ArrayList<>();
    private final String firstImport;

    private int activeTab = 0; // 0 = World, 1 = Images, 2 = GIFs, 3 = 3D
    private HologramPackZip.Pack activePack = null;
    private List<HologramAssetSearch.Result> onlineResults = List.of();
    private boolean onlineLoading = false;
    private String lastOnlineKey = "";

    private EditBox source;
    private EditBox search;
    private String pendingDelete = "";
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
        int w = Math.min(this.width - 16, 404);
        int h = this.height - 24;
        this.px0 = (this.width - w) / 2;
        this.py0 = 12;
        this.px1 = this.px0 + w;
        this.py1 = this.py0 + h;
        this.gridTop = this.py0 + 24;
        this.gridBottom = this.py1 - 50;
        this.cols = Math.max(1, (w - 12 + GAP) / (CELL + GAP));

        String keep = this.source == null ? "" : this.source.getValue();
        this.source = new EditBox(this.font, this.px0 + 8, this.py1 - 36, w - 16 - 104, 14, Component.literal("Path or address"));
        this.source.setMaxLength(1024);
        this.source.setHint(Component.literal("File path or http(s) address"));
        this.source.setValue(keep);
        this.addRenderableWidget(this.source);

        int searchX = tabEndX() + 6;
        int goW = 24;
        int searchW = Math.max(60, this.px1 - 8 - goW - 4 - searchX);
        String keepSearch = this.search == null ? "" : this.search.getValue();
        this.search = new EditBox(this.font, searchX, this.py0 + 4, searchW, 14, Component.literal("Search"));
        this.search.setMaxLength(64);
        this.search.setHint(Component.literal("Search"));
        this.search.setValue(keepSearch);
        this.search.setResponder(v -> {
            if (this.activeTab == 0)
                this.scroll = 0;
        });
        this.addRenderableWidget(this.search);

        if (!this.opened) {
            this.opened = true;
            reload();
            if (this.firstImport != null && !this.firstImport.isEmpty())
                startImport(this.firstImport);
        }
    }

    private int tabX(int idx) {
        int x = this.px0 + 8;
        for (int i = 0; i < idx; i++)
            x += tabW(i) + 3;
        return x;
    }

    private int tabW(int idx) {
        return this.font.width(TABS[idx]) + 10;
    }

    private int tabEndX() {
        return tabX(TABS.length - 1) + tabW(TABS.length - 1);
    }

    private int goBtnX() {
        return this.px1 - 8 - 24;
    }

    private void selectTab(int tab) {
        this.activePack = null;
        if (tab == this.activeTab)
            return;
        this.activeTab = tab;
        this.scroll = 0;
        this.pendingDelete = "";
        if (tab > 0)
            triggerOnlineSearch(false);
    }

    private HologramAssetSearch.Kind onlineKind() {
        return switch (this.activeTab) {
            case 2 -> HologramAssetSearch.Kind.GIF;
            case 3 -> HologramAssetSearch.Kind.MODEL;
            default -> HologramAssetSearch.Kind.IMAGE;
        };
    }

    private void triggerOnlineSearch(boolean force) {
        if (this.activeTab == 0)
            return;
        HologramAssetSearch.Kind kind = onlineKind();
        String q = this.search == null ? "" : this.search.getValue().trim();
        String key = kind.name() + ":" + q.toLowerCase(Locale.ROOT);
        if (!force && key.equals(this.lastOnlineKey) && !this.onlineResults.isEmpty())
            return;
        this.lastOnlineKey = key;
        this.onlineLoading = true;
        this.onlineResults = List.of();
        this.scroll = 0;
        say("Searching " + kind.label + "...", MUTED);
        HologramAssetSearch.search(kind, q, results -> {
            if (!key.equals(this.lastOnlineKey))
                return;
            this.onlineLoading = false;
            this.onlineResults = results;
            say(results.isEmpty() ? "No online results for '" + q + "'."
                    : results.size() + " results. Click to import, Shift+click to stream.", MUTED);
        }, err -> {
            if (!key.equals(this.lastOnlineKey))
                return;
            this.onlineLoading = false;
            say("Search failed: " + err, BAD);
        });
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
            if (json.has("dimension"))
                HologramAssetImport.serverLimits(json.get("maxBytes").getAsInt(), json.get("dimension").getAsInt(),
                        json.get("fps").getAsInt(), json.get("seconds").getAsInt());
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

    private HologramAssets.UploadListener uploadListener(boolean switchToWorld) {
        return new HologramAssets.UploadListener() {
            @Override
            public void progress(int percent, String stage) {
                say(stage.equals("downloading") ? "Downloading..." : "Uploading " + percent + "% (" + stage + ")", MUTED);
            }

            @Override
            public void done(boolean ok, String message) {
                say(ok ? "Added to the library. Click it to use it." : message, ok ? GOOD : BAD);
                if (ok) {
                    if (HologramAssetScreen.this.source != null)
                        HologramAssetScreen.this.source.setValue("");
                    if (switchToWorld)
                        HologramAssetScreen.this.activeTab = 0;
                    reload();
                }
            }
        };
    }

    private void startImport(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\""))
            trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
        if (trimmed.toLowerCase(Locale.ROOT).endsWith(".zip") && !trimmed.toLowerCase(Locale.ROOT).startsWith("http")) {
            try {
                Path zp = trimmed.toLowerCase(Locale.ROOT).startsWith("file:") ? Path.of(java.net.URI.create(trimmed)) : Path.of(trimmed);
                if (java.nio.file.Files.isRegularFile(zp)) {
                    openZipPack(zp);
                    return;
                }
            } catch (RuntimeException ignored) {
            }
        }
        boolean started = HologramAssetImport.start(text, uploadListener(true));
        if (!started)
            say("Another upload is still running", BAD);
    }

    private void openZipPack(Path zipPath) {
        say("Scanning " + zipPath.getFileName() + "...", MUTED);
        Thread t = new Thread(() -> {
            try {
                HologramPackZip.Pack pack = HologramPackZip.scan(zipPath);
                net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (pack.entries().isEmpty()) {
                        say("No .glb, .gltf, .obj, or .fbx models found in " + pack.packName(), BAD);
                    } else {
                        this.activePack = pack;
                        this.scroll = 0;
                        if (this.search != null)
                            this.search.setValue("");
                        say(pack.entries().size() + " models in " + pack.packName() + ". Click any model to import.", GOOD);
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                net.minecraft.client.Minecraft.getInstance().execute(() -> say("Could not open zip: " + msg, BAD));
            }
        }, "FoxsTweaks zip scan");
        t.setDaemon(true);
        t.start();
    }

    private List<HologramPackZip.Entry> visiblePackEntries() {
        if (this.activePack == null)
            return List.of();
        String q = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty())
            return this.activePack.entries();
        List<HologramPackZip.Entry> out = new ArrayList<>();
        for (HologramPackZip.Entry e : this.activePack.entries()) {
            if (e.name().toLowerCase(Locale.ROOT).contains(q) || e.format().label.toLowerCase(Locale.ROOT).contains(q))
                out.add(e);
        }
        return out;
    }

    private HologramPackZip.Entry packTileAt(double mx, double my) {
        if (this.activePack == null || my < this.gridTop || my >= this.gridBottom)
            return null;
        List<HologramPackZip.Entry> list = visiblePackEntries();
        for (int i = 0; i < list.size(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL + LABEL)
                return list.get(i);
        }
        return null;
    }

    private void pickOnlineResult(HologramAssetSearch.Result r) {
        if (Screen.hasShiftDown() && !r.polyHaven()) {
            startStream(r.downloadUrl());
            return;
        }
        if (!this.canUpload) {
            if (!r.polyHaven()) {
                startStream(r.downloadUrl());
            } else {
                say("Uploads are disabled on this server.", BAD);
            }
            return;
        }
        if (r.polyHaven()) {
            boolean started = HologramAssetSearch.importPolyHaven(r, uploadListener(true));
            if (!started)
                say("Another upload is still running", BAD);
        } else {
            startImport(r.downloadUrl());
        }
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

    private int streamX() {
        return uploadX() - 52;
    }

    private void startStream(String raw) {
        String url = raw == null ? "" : raw.trim();
        if (!dev.watdafawx.foxstweaks.hologram.HologramNetwork.isExternalUrl(url)) {
            say("Enter an http:// or https:// URL to stream directly.", BAD);
            return;
        }
        String clean = url.split("\\?")[0].split("#")[0].toLowerCase(Locale.ROOT);
        boolean model = clean.endsWith(".glb") || clean.endsWith(".gltf") || clean.endsWith(".bbmodel") || clean.endsWith(".json");
        if (this.editor != null)
            this.editor.creatorAddLayer(model ? HologramLayer.model(url, 0.0F) : HologramLayer.image(url, 0.0F));
        onClose();
    }

    private List<Asset> visible() {
        String q = this.search == null ? "" : this.search.getValue().trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty())
            return this.assets;
        List<Asset> out = new ArrayList<>();
        for (Asset a : this.assets) {
            if (a.name().toLowerCase(Locale.ROOT).contains(q) || a.type().toLowerCase(Locale.ROOT).contains(q)
                    || a.id().startsWith(q))
                out.add(a);
        }
        return out;
    }

    private int tileX(int index) {
        return this.px0 + 8 + (index % this.cols) * (CELL + GAP);
    }

    private int tileY(int index) {
        return this.gridTop + (index / this.cols) * (CELL + LABEL + GAP) - (int) this.scroll;
    }

    private int contentHeight() {
        int count = this.activePack != null ? visiblePackEntries().size() : (this.activeTab == 0 ? visible().size() : this.onlineResults.size());
        int rows = (count + this.cols - 1) / this.cols;
        return rows * (CELL + LABEL + GAP);
    }

    private Asset tileAt(double mx, double my) {
        if (this.activeTab != 0 || my < this.gridTop || my >= this.gridBottom)
            return null;
        List<Asset> list = visible();
        for (int i = 0; i < list.size(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL + LABEL)
                return list.get(i);
        }
        return null;
    }

    private HologramAssetSearch.Result onlineTileAt(double mx, double my) {
        if (this.activeTab == 0 || my < this.gridTop || my >= this.gridBottom)
            return null;
        for (int i = 0; i < this.onlineResults.size(); i++) {
            int x = tileX(i);
            int y = tileY(i);
            if (mx >= x && mx < x + CELL && my >= y && my < y + CELL + LABEL)
                return this.onlineResults.get(i);
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button))
            return true;
        if (button == 1 && this.activeTab == 0) {
            Asset d = tileAt(mx, my);
            if (d == null)
                return false;
            String label = d.name().isEmpty() ? d.id().substring(0, 8) : d.name();
            if (!d.id().equals(this.pendingDelete)) {
                this.pendingDelete = d.id();
                say("Delete " + label + "? Right-click it again to confirm.", BAD);
            } else {
                this.pendingDelete = "";
                HologramAssets.requestDelete(d.id(), false, err -> {
                    if (err == null) {
                        say("Deleted " + label + ".", GOOD);
                        reload();
                    } else {
                        say(err, BAD);
                    }
                });
            }
            return true;
        }
        if (button != 0)
            return false;
        // Tab bar
        if (my >= this.py0 + 3 && my < this.py0 + 19) {
            for (int i = 0; i < TABS.length; i++) {
                int tx = tabX(i);
                if (mx >= tx && mx < tx + tabW(i)) {
                    selectTab(i);
                    return true;
                }
            }
            if (this.activeTab > 0 && mx >= goBtnX() && mx < goBtnX() + 24) {
                triggerOnlineSearch(true);
                return true;
            }
        }
        if (mx >= streamX() && mx < streamX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21) {
            startStream(this.source.getValue());
            return true;
        }
        if (mx >= uploadX() && mx < uploadX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21) {
            if (this.canUpload)
                startImport(this.source.getValue());
            else
                say("This server does not let you upload", BAD);
            return true;
        }
        if (this.activePack != null) {
            HologramPackZip.Entry pe = packTileAt(mx, my);
            if (pe != null) {
                if (!this.canUpload) {
                    say("This server does not let you upload", BAD);
                } else if (!HologramPackZip.importEntry(this.activePack, pe, uploadListener(false))) {
                    say("Another upload is still running", BAD);
                }
                return true;
            }
            return false;
        }
        if (this.activeTab == 0) {
            Asset a = tileAt(mx, my);
            if (a != null) {
                if (this.editor != null)
                    this.editor.creatorAddLayer("MODEL".equals(a.type()) ? HologramLayer.model(a.id(), 0.0F) : HologramLayer.image(a.id(), 0.0F));
                onClose();
                return true;
            }
        } else {
            HologramAssetSearch.Result r = onlineTileAt(mx, my);
            if (r != null) {
                pickOnlineResult(r);
                return true;
            }
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
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (this.source.isFocused()) {
                if (this.canUpload)
                    startImport(this.source.getValue());
                return true;
            }
            if (this.search.isFocused() && this.activeTab > 0) {
                triggerOnlineSearch(true);
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        HologramAssetSearch.clearThumbnails();
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
        // Drawn manually at the start of render() so super.render() never double-dims the panel & thumbnails.
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float partial) {
        g.fill(0, 0, this.width, this.height, DIM);
        g.fill(this.px0, this.py0, this.px1, this.py1, PANEL);
        g.renderOutline(this.px0, this.py0, this.px1 - this.px0, this.py1 - this.py0, PANEL_EDGE);

        // Tab strip
        for (int i = 0; i < TABS.length; i++) {
            int tx = tabX(i);
            int tw = tabW(i);
            boolean active = i == this.activeTab;
            boolean hov = mx >= tx && mx < tx + tw && my >= this.py0 + 3 && my < this.py0 + 19;
            g.fill(tx, this.py0 + 3, tx + tw, this.py0 + 19, active ? 0xFF1F6A86 : hov ? TILE_HOVER : TILE);
            if (active)
                g.renderOutline(tx, this.py0 + 3, tw, 16, ACCENT);
            g.drawCenteredString(this.font, TABS[i], tx + tw / 2, this.py0 + 7, active ? TEXT : MUTED);
        }

        // Go button (for online search) or world count
        if (this.activeTab > 0) {
            int gx = goBtnX();
            boolean overGo = mx >= gx && mx < gx + 24 && my >= this.py0 + 4 && my < this.py0 + 18;
            g.fill(gx, this.py0 + 4, gx + 24, this.py0 + 18, overGo ? 0xFF2A7FA0 : 0xFF1F6A86);
            g.drawCenteredString(this.font, "Go", gx + 12, this.py0 + 7, TEXT);
        } else {
            String count = this.loading ? "..." : String.valueOf(this.total);
            g.drawCenteredString(this.font, count, goBtnX() + 12, this.py0 + 7, FAINT);
        }

        g.enableScissor(this.px0 + 1, this.gridTop, this.px1 - 1, this.gridBottom);
        if (this.activePack != null) {
            HologramPackZip.Entry hover = packTileAt(mx, my);
            List<HologramPackZip.Entry> shown = visiblePackEntries();
            for (int i = 0; i < shown.size(); i++) {
                int y = tileY(i);
                if (y + CELL + LABEL < this.gridTop || y > this.gridBottom)
                    continue;
                drawPackTile(g, shown.get(i), tileX(i), y, shown.get(i) == hover);
            }
        } else if (this.activeTab == 0) {
            Asset hover = tileAt(mx, my);
            List<Asset> shown = visible();
            for (int i = 0; i < shown.size(); i++) {
                int y = tileY(i);
                if (y + CELL + LABEL < this.gridTop || y > this.gridBottom)
                    continue;
                drawTile(g, shown.get(i), tileX(i), y, shown.get(i) == hover);
            }
        } else {
            HologramAssetSearch.Result hover = onlineTileAt(mx, my);
            for (int i = 0; i < this.onlineResults.size(); i++) {
                int y = tileY(i);
                if (y + CELL + LABEL < this.gridTop || y > this.gridBottom)
                    continue;
                drawOnlineTile(g, this.onlineResults.get(i), tileX(i), y, this.onlineResults.get(i) == hover);
            }
        }
        g.disableScissor();

        if (this.activePack != null) {
            if (visiblePackEntries().isEmpty())
                g.drawCenteredString(this.font, "No models in pack match that search.", (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
        } else if (this.activeTab == 0) {
            if (!this.assets.isEmpty() && visible().isEmpty() && !this.loading) {
                g.drawCenteredString(this.font, "Nothing matches that search.", (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
            }
            if (this.assets.isEmpty() && !this.loading) {
                String hint = "Nothing here yet. Drop a file, paste a URL,";
                String hint2 = "or switch to Images / GIFs / 3D above.";
                g.drawCenteredString(this.font, hint, (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
                g.drawCenteredString(this.font, hint2, (this.px0 + this.px1) / 2, this.gridTop + 42, MUTED);
            }
        } else if (this.onlineLoading) {
            g.drawCenteredString(this.font, "Searching public CDN...", (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
        } else if (this.onlineResults.isEmpty()) {
            g.drawCenteredString(this.font, "Type a keyword above and press Enter or Go.", (this.px0 + this.px1) / 2, this.gridTop + 30, MUTED);
        }

        double max = Math.max(0, contentHeight() - (this.gridBottom - this.gridTop));
        if (max > 0) {
            int trackH = this.gridBottom - this.gridTop;
            int barH = Math.max(12, (int) (trackH * trackH / (double) contentHeight()));
            int barY = this.gridTop + (int) ((trackH - barH) * (this.scroll / max));
            g.fill(this.px1 - 5, barY, this.px1 - 3, barY + barH, FAINT);
        }

        g.drawString(this.font, this.canUpload ? "Drop image, 3D model (.glb/.gltf/.obj/.fbx/.bbmodel), or pack .zip here, or paste path/URL" : "Uploads are not allowed for you here",
                this.px0 + 8, this.py1 - 48, MUTED, false);
        super.render(g, mx, my, partial);
        String rawUrl = this.source == null ? "" : this.source.getValue().trim();
        boolean validUrl = dev.watdafawx.foxstweaks.hologram.HologramNetwork.isExternalUrl(rawUrl);
        boolean overStream = mx >= streamX() && mx < streamX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21;
        g.fill(streamX(), this.py1 - 37, streamX() + 48, this.py1 - 21,
                !validUrl ? 0xFF1A222D : overStream ? 0xFF2E8B57 : 0xFF226B42);
        g.drawCenteredString(this.font, "Stream", streamX() + 24, this.py1 - 33, validUrl ? TEXT : FAINT);

        boolean overUpload = mx >= uploadX() && mx < uploadX() + 48 && my >= this.py1 - 37 && my < this.py1 - 21;
        boolean busy = HologramAssetImport.busy();
        g.fill(uploadX(), this.py1 - 37, uploadX() + 48, this.py1 - 21,
                !this.canUpload || busy ? 0xFF1A222D : overUpload ? 0xFF2A7FA0 : 0xFF1F6A86);
        g.drawCenteredString(this.font, "Upload", uploadX() + 24, this.py1 - 33, this.canUpload && !busy ? TEXT : FAINT);

        String fallbackHint = this.activeTab == 0
                ? "Click an item to add it. Right-click twice to delete. Esc closes."
                : "Click to import into world. Shift+click to stream URL. Enter searches.";
        String line = this.font.plainSubstrByWidth(this.status.isEmpty() ? fallbackHint : this.status,
                this.px1 - this.px0 - 16);
        g.drawString(this.font, line, this.px0 + 8, this.py1 - 15, this.status.isEmpty() ? FAINT : this.statusColour, false);
    }

    private void drawTile(GuiGraphics g, Asset a, int x, int y, boolean hover) {
        g.fill(x, y, x + CELL, y + CELL, hover ? TILE_HOVER : TILE);
        if (hover)
            g.renderOutline(x, y, CELL, CELL, ACCENT);

        boolean model = "MODEL".equals(a.type());
        ResourceLocation tex = model ? null : HologramAssets.texture(a.id(), 0.0);
        if (model) {
            g.drawCenteredString(this.font, "3D", x + CELL / 2, y + CELL / 2 - 4, ACCENT);
        } else if (tex != null && a.width() > 0 && a.height() > 0) {
            double s = Math.min((CELL - 4) / (double) a.width(), (CELL - 4) / (double) a.height());
            int w = Math.max(1, (int) Math.round(a.width() * s));
            int h = Math.max(1, (int) Math.round(a.height() * s));
            g.blit(tex, x + (CELL - w) / 2, y + (CELL - h) / 2, w, h, 0.0F, 0.0F, a.width(), a.height(), a.width(), a.height());
        } else {
            g.drawCenteredString(this.font, "...", x + CELL / 2, y + CELL / 2 - 4, FAINT);
        }
        if (a.frames() > 1 || model && a.frames() > 0) {
            String badge = model ? a.frames() + (a.frames() == 1 ? " anim" : " anims") : a.frames() + "f";
            g.fill(x + CELL - this.font.width(badge) - 5, y + 2, x + CELL - 2, y + 12, 0xAA000000);
            g.drawString(this.font, badge, x + CELL - this.font.width(badge) - 3, y + 3, ACCENT, false);
        }
        String name = a.name().isEmpty() ? a.id().substring(0, Math.min(8, a.id().length())) : a.name();
        g.drawString(this.font, this.font.plainSubstrByWidth(name, CELL), x, y + CELL + 2, TEXT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(
                model ? "model" : a.width() + "x" + a.height() + (a.type().isEmpty() ? "" : " " + a.type().toLowerCase(Locale.ROOT)), CELL),
                x, y + CELL + 11, FAINT, false);
    }

    private void drawOnlineTile(GuiGraphics g, HologramAssetSearch.Result r, int x, int y, boolean hover) {
        g.fill(x, y, x + CELL, y + CELL, hover ? TILE_HOVER : TILE);
        if (hover)
            g.renderOutline(x, y, CELL, CELL, ACCENT);

        ResourceLocation tex = HologramAssetSearch.thumbnail(r.thumbUrl());
        if (tex != null) {
            g.blit(tex, x + 2, y + 2, CELL - 4, CELL - 4, 0.0F, 0.0F, 128, 128, 128, 128);
        } else {
            g.drawCenteredString(this.font, r.kind() == HologramAssetSearch.Kind.MODEL ? "3D" : "...",
                    x + CELL / 2, y + CELL / 2 - 4, r.kind() == HologramAssetSearch.Kind.MODEL ? ACCENT : FAINT);
        }
        if (r.kind() == HologramAssetSearch.Kind.GIF) {
            g.fill(x + CELL - 20, y + 2, x + CELL - 2, y + 12, 0xAA000000);
            g.drawString(this.font, "GIF", x + CELL - 18, y + 3, ACCENT, false);
        } else if (r.kind() == HologramAssetSearch.Kind.MODEL) {
            String badge = r.polyHaven() ? "CC0" : "GLB";
            g.fill(x + CELL - 22, y + 2, x + CELL - 2, y + 12, 0xAA000000);
            g.drawString(this.font, badge, x + CELL - 20, y + 3, ACCENT, false);
        }
        g.drawString(this.font, this.font.plainSubstrByWidth(r.title(), CELL), x, y + CELL + 2, TEXT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(r.subtitle(), CELL), x, y + CELL + 11, FAINT, false);
    }

    private void drawPackTile(GuiGraphics g, HologramPackZip.Entry e, int x, int y, boolean hover) {
        g.fill(x, y, x + CELL, y + CELL, hover ? TILE_HOVER : TILE);
        if (hover)
            g.renderOutline(x, y, CELL, CELL, ACCENT);
        g.drawCenteredString(this.font, "3D", x + CELL / 2, y + CELL / 2 - 4, ACCENT);
        String badge = e.format().label;
        g.fill(x + CELL - this.font.width(badge) - 5, y + 2, x + CELL - 2, y + 12, 0xAA000000);
        g.drawString(this.font, badge, x + CELL - this.font.width(badge) - 3, y + 3, ACCENT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(e.name(), CELL), x, y + CELL + 2, TEXT, false);
        g.drawString(this.font, this.font.plainSubstrByWidth(badge + " pack", CELL), x, y + CELL + 11, FAINT, false);
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

    public void devSearch(String text) {
        this.search.setValue(text);
    }

    public int devVisibleCount() {
        return visible().size();
    }

    /** Dev checks: right-click the first tile, as a user would. */
    public boolean devRightClickFirst() {
        if (visible().isEmpty())
            return false;
        return mouseClicked(tileX(0) + 4, tileY(0) + 4, 1);
    }

    public void devImport(String text) {
        startImport(text);
    }

    /** Dev checks: click the first tile, as a user would. */
    public boolean devPickFirst() {
        if (visible().isEmpty())
            return false;
        return mouseClicked(tileX(0) + 4, tileY(0) + 4, 0);
    }
}
