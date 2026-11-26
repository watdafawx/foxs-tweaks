package dev.watdafawx.foxstweaks.client.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.client.HologramPickerPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Web protocol 5: block, entity and particle icons for the web editor's lists, drawn by the game.
 *
 * <p>The page asks for the ids it is showing ({@code icons}); the game draws each one into a cell of one sheet and
 * answers with a PNG data URL plus the id -> cell mapping, exactly like {@code assetThumb}. Nothing is streamed:
 * an icon is drawn once per request and the page keeps it for the session.
 *
 * <p>Blocks and entities are drawn on the render thread into an off-screen {@link TextureTarget} the size of the
 * whole sheet (one viewport per cell, one read-back per request), after the frame has been drawn
 * ({@link RenderFrameEvent.Post}), so nothing here touches the world render. The work is budgeted: a few icons
 * per frame and a time cap, so a 240-icon page spreads over several frames instead of hitching one. Particles are
 * a pure pixel copy of the first sprite in the particle atlas and need no GL at all.
 *
 * <p>Requests run one after another (the target holds the sheet between frames); a connection may have at most
 * {@link #MAX_QUEUED} waiting. PNG encoding happens on a worker thread.
 */
final class HologramWebIcons {
    private static final Logger LOG = LogUtils.getLogger();

    /** Pixels per icon cell. The in-game item is 16 GUI units, so every GUI pixel is a 4x4 block. */
    static final int SIZE = 64;
    static final int MAX_IDS = 256;
    static final int MAX_COLS = 16;
    private static final int MAX_QUEUED = 4;
    /** Icons drawn per frame. Entities are far heavier than items (model baking on first use). */
    private static final int PER_FRAME_BLOCK = 32;
    private static final int PER_FRAME_ENTITY = 6;
    private static final int PER_FRAME_PARTICLE = 128;
    /** Even under the per-frame count, stop starting new icons once this much time went into the frame. */
    private static final long FRAME_BUDGET_NANOS = 4_000_000L;
    /** The entity icon's turn: the Studio's own start angle, a three-quarter view. */
    private static final float ENTITY_YAW = 225.0F;
    /** GUI projection depth, the same range the game's own GUI uses. */
    private static final float GUI_NEAR = 1000.0F;
    private static final float GUI_FAR = 21000.0F;

    /** Types we already logged a failure for, so one broken mod does not fill the log. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "foxstweaks-icon-encoder");
        t.setDaemon(true);
        return t;
    });

    private enum Kind {
        BLOCK(PER_FRAME_BLOCK), ENTITY(PER_FRAME_ENTITY), PARTICLE(PER_FRAME_PARTICLE);

        final int perFrame;

        Kind(int perFrame) {
            this.perFrame = perFrame;
        }
    }

    private static final class Request {
        final WebSocketConnection connection;
        final long seq;
        final Kind kind;
        final List<String> ids;
        final int cols;
        final int rows;
        /** id -> cell index, -1 for "no icon, keep the letter badge". */
        final Map<String, Integer> cells = new LinkedHashMap<>();
        int next;
        int drawn;
        @Nullable
        NativeImage sheet;
        @Nullable
        TextureTarget target;

        Request(WebSocketConnection connection, long seq, Kind kind, List<String> ids) {
            this.connection = connection;
            this.seq = seq;
            this.kind = kind;
            this.ids = ids;
            this.cols = Math.max(1, Math.min(MAX_COLS, ids.size()));
            this.rows = (ids.size() + this.cols - 1) / this.cols;
        }

        int width() {
            return this.cols * SIZE;
        }

        int height() {
            return this.rows * SIZE;
        }

        void free() {
            if (this.target != null) {
                this.target.destroyBuffers();
                this.target = null;
            }
            if (this.sheet != null) {
                this.sheet.close();
                this.sheet = null;
            }
        }
    }

    private final Deque<Request> queue = new ArrayDeque<>();

    /** What the hello message tells the page about icons. */
    static JsonObject capabilities() {
        JsonObject caps = new JsonObject();
        caps.addProperty("available", true);
        caps.addProperty("size", SIZE);
        caps.addProperty("maxIds", MAX_IDS);
        caps.addProperty("maxCols", MAX_COLS);
        JsonArray kinds = new JsonArray();
        for (Kind k : Kind.values())
            kinds.add(k.name());
        caps.add("kinds", kinds);
        return caps;
    }

    /** True when the message was an icon message (handled here). */
    boolean handle(WebSocketConnection c, String type, JsonObject msg, long seq, ErrorSink errors) {
        if (!type.equals("icons"))
            return false;
        Kind kind;
        try {
            JsonElement k = msg.get("kind");
            kind = Kind.valueOf(k != null && k.isJsonPrimitive() ? k.getAsString() : "");
        } catch (IllegalArgumentException e) {
            errors.error(c, seq, "icons", "icons: kind must be BLOCK, ENTITY or PARTICLE");
            return true;
        }
        JsonElement idsEl = msg.get("ids");
        if (idsEl == null || !idsEl.isJsonArray() || idsEl.getAsJsonArray().isEmpty()) {
            errors.error(c, seq, "icons", "icons: ids must be a non-empty array");
            return true;
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonElement el : idsEl.getAsJsonArray()) {
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString())
                continue;
            String id = el.getAsString();
            if (id.length() <= 128 && ResourceLocation.tryParse(id) != null)
                ids.add(id);
            if (ids.size() >= MAX_IDS)
                break;
        }
        if (ids.isEmpty()) {
            errors.error(c, seq, "icons", "icons: no valid ids");
            return true;
        }
        int waiting = 0;
        for (Request r : this.queue)
            if (r.connection == c)
                waiting++;
        if (waiting >= MAX_QUEUED) {
            errors.error(c, seq, "icons", "icons: too many requests waiting; ask again when some arrived");
            return true;
        }
        this.queue.add(new Request(c, seq, kind, new ArrayList<>(ids)));
        return true;
    }

    /** Where a refused request reports; the session's own error writer. */
    interface ErrorSink {
        void error(WebSocketConnection c, long seq, String code, String message);
    }

    /** The session ended or the browser left: free whatever is half drawn. */
    void close() {
        for (Request r : this.queue)
            r.free();
        this.queue.clear();
    }

    // ---------------------------------------------------------------- frame loop (render thread)

    static void onRenderFrame(RenderFrameEvent.Post event) {
        HologramWebSession s = HologramWebSession.current();
        if (s != null && s.running())
            s.icons().frame();
    }

    void frame() {
        Request r = this.queue.peek();
        if (r == null)
            return;
        if (!r.connection.isOpen()) {
            this.queue.poll();
            r.free();
            return;
        }
        long start = System.nanoTime();
        try {
            if (r.kind == Kind.PARTICLE) {
                if (r.sheet == null)
                    r.sheet = new NativeImage(r.width(), r.height(), true);
                int budget = r.kind.perFrame;
                while (r.next < r.ids.size() && budget-- > 0)
                    particle(r, r.ids.get(r.next++));
            } else {
                drawGl(r, start);
            }
            if (r.next >= r.ids.size())
                finish(r);
        } catch (Throwable t) {
            LOG.warn("Hologram web editor: icon request failed", t);
            this.queue.poll();
            r.free();
            // Whatever was not drawn is simply "no icon": the page keeps its badges.
            for (int i = r.next; i < r.ids.size(); i++)
                r.cells.putIfAbsent(r.ids.get(i), -1);
            sendEmpty(r);
        }
    }

    private void finish(Request r) throws IOException {
        this.queue.poll();
        NativeImage sheet = r.sheet;
        if (r.kind != Kind.PARTICLE && r.target != null && r.drawn > 0) {
            sheet = new NativeImage(r.width(), r.height(), false);
            r.target.bindRead();
            sheet.downloadTexture(0, false);
            r.target.unbindRead();
            sheet.flipY();
        } else if (r.kind != Kind.PARTICLE) {
            sheet = null;
        }
        if (r.target != null) {
            r.target.destroyBuffers();
            r.target = null;
        }
        if (r.sheet != null && r.sheet != sheet)
            r.sheet.close();
        r.sheet = null;
        if (sheet == null || r.drawn == 0) {
            if (sheet != null)
                sheet.close();
            sendEmpty(r);
            return;
        }
        NativeImage toEncode = sheet;
        ENCODER.execute(() -> {
            try {
                byte[] png = toEncode.asByteArray();
                JsonObject o = reply(r);
                o.addProperty("sheet", "data:image/png;base64," + Base64.getEncoder().encodeToString(png));
                r.connection.send(o.toString());
            } catch (IOException | RuntimeException e) {
                LOG.warn("Hologram web editor: could not encode an icon sheet", e);
            } finally {
                toEncode.close();
            }
        });
    }

    private void sendEmpty(Request r) {
        r.connection.send(reply(r).toString());
    }

    private static JsonObject reply(Request r) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "icons");
        o.addProperty("seq", r.seq);
        o.addProperty("kind", r.kind.name());
        o.addProperty("size", SIZE);
        o.addProperty("cols", r.cols);
        o.addProperty("width", r.width());
        o.addProperty("height", r.height());
        JsonObject cells = new JsonObject();
        r.cells.forEach(cells::addProperty);
        o.add("cells", cells);
        return o;
    }

    // ---------------------------------------------------------------- blocks and entities

    private void drawGl(Request r, long frameStart) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            // Entities and block items need a world; nothing can be drawn on the title screen.
            while (r.next < r.ids.size())
                r.cells.put(r.ids.get(r.next++), -1);
            return;
        }
        boolean fresh = r.target == null;
        if (fresh) {
            r.target = new TextureTarget(r.width(), r.height(), true, Minecraft.ON_OSX);
            r.target.setClearColor(0.0F, 0.0F, 0.0F, 0.0F);
        }
        TextureTarget target = r.target;
        Matrix4f projection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting sorting = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        ByteBufferBuilder buffer = new ByteBufferBuilder(256 * 1024);
        try {
            modelView.translation(0.0F, 0.0F, 10000.0F - GUI_FAR);
            RenderSystem.applyModelViewMatrix();
            // RenderTarget#clear ends with unbindWrite (framebuffer 0), so it must come first or the draws land in the window.
            if (fresh)
                target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            MultiBufferSource.BufferSource source = MultiBufferSource.immediate(buffer);
            GuiGraphics g = new GuiGraphics(mc, source);
            // Entities are laid out in a 32-unit box, items in a 16-unit one: both fill the same 64 px cell.
            float units = r.kind == Kind.ENTITY ? 32.0F : 16.0F;
            RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0.0F, units, units, 0.0F, GUI_NEAR, GUI_FAR),
                    VertexSorting.ORTHOGRAPHIC_Z);
            Lighting.setupFor3DItems();
            RenderSystem.enableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            int count = 0;
            while (r.next < r.ids.size() && count < r.kind.perFrame
                    && (count == 0 || System.nanoTime() - frameStart < FRAME_BUDGET_NANOS)) {
                String id = r.ids.get(r.next++);
                count++;
                int cell = r.drawn;
                int col = cell % r.cols;
                int row = cell / r.cols;
                // GL's origin is the bottom-left; the read-back is flipped, so cell (col,row) lands top-down.
                RenderSystem.viewport(col * SIZE, r.height() - (row + 1) * SIZE, SIZE, SIZE);
                RenderSystem.clear(256, Minecraft.ON_OSX); // depth only: the colour of earlier cells stays
                boolean ok = r.kind == Kind.BLOCK ? drawBlock(g, id) : drawEntity(g, id);
                source.endBatch();
                if (ok) {
                    r.cells.put(id, r.drawn++);
                } else {
                    r.cells.put(id, -1);
                    // The next icon reuses this cell, so wipe whatever a failed draw left in it (scissored to the cell).
                    GlStateManager._enableScissorTest();
                    GlStateManager._scissorBox(col * SIZE, r.height() - (row + 1) * SIZE, SIZE, SIZE);
                    RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
                    RenderSystem.clear(16384, Minecraft.ON_OSX);
                    GlStateManager._disableScissorTest();
                }
            }
        } finally {
            buffer.close();
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(projection, sorting);
            mc.getMainRenderTarget().bindWrite(true);
            Lighting.setupFor3DItems();
        }
    }

    private static boolean drawBlock(GuiGraphics g, String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        Block block = key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
        if (block == null)
            return false;
        Item item = block.asItem();
        if (item == Items.AIR)
            return false;
        try {
            g.renderItem(new ItemStack(item), 0, 0);
            return true;
        } catch (Throwable t) {
            warnOnce("block " + id, t);
            return false;
        }
    }

    private static boolean drawEntity(GuiGraphics g, String id) {
        return HologramPickerPreview.drawEntityUnclipped(g, id, 0, 0, 32, ENTITY_YAW, 0.0,
                t -> warnOnce("entity " + id, t));
    }

    private static void warnOnce(String what, Throwable t) {
        if (WARNED.add(what))
            LOG.warn("Hologram web editor: could not draw the icon for {} (the page keeps its badge)", what, t);
    }

    // ---------------------------------------------------------------- particles

    /** Copies the particle's first atlas sprite frame into its cell, scaled up with nearest-neighbour. */
    private static void particle(Request r, String id) {
        NativeImage sheet = r.sheet;
        try {
            List<ResourceLocation> frames = HologramPickerPreview.particleFrames(id);
            if (frames.isEmpty() || sheet == null) {
                r.cells.put(id, -1);
                return;
            }
            // particleFrames gives textures/particle/<name>.png; the atlas knows the sprite as <ns>:<name>.
            ResourceLocation tex = frames.get(0);
            String path = tex.getPath();
            String prefix = "textures/particle/";
            if (!path.startsWith(prefix) || !path.endsWith(".png")) {
                r.cells.put(id, -1);
                return;
            }
            ResourceLocation spriteName = ResourceLocation.fromNamespaceAndPath(tex.getNamespace(),
                    path.substring(prefix.length(), path.length() - 4));
            // The particle atlas belongs to the ParticleEngine, not to the model manager's AtlasSet that
            // Minecraft#getTextureAtlas reads (which only knows block, item and similar atlases).
            if (!(Minecraft.getInstance().getTextureManager().getTexture(TextureAtlas.LOCATION_PARTICLES) instanceof TextureAtlas atlas)) {
                r.cells.put(id, -1);
                return;
            }
            TextureAtlasSprite sprite = atlas.getSprite(spriteName);
            SpriteContents contents = sprite.contents();
            if (contents.name().equals(MissingTextureAtlasSprite.getLocation())) {
                r.cells.put(id, -1);
                return;
            }
            NativeImage src = contents.getOriginalImage();
            int fw = Math.min(contents.width(), src.getWidth());
            int fh = Math.min(contents.height(), src.getHeight());
            if (fw <= 0 || fh <= 0) {
                r.cells.put(id, -1);
                return;
            }
            // A margin so the sprite does not touch the cell edge; integer steps keep pixel art crisp.
            int box = SIZE - 8;
            int dim = Math.max(fw, fh);
            int k = Math.max(1, box / dim);
            int dw = dim <= box ? fw * k : Math.max(1, fw * box / dim);
            int dh = dim <= box ? fh * k : Math.max(1, fh * box / dim);
            int cell = r.drawn;
            int ox = (cell % r.cols) * SIZE + (SIZE - dw) / 2;
            int oy = (cell / r.cols) * SIZE + (SIZE - dh) / 2;
            boolean any = false;
            for (int y = 0; y < dh; y++) {
                for (int x = 0; x < dw; x++) {
                    int sx = Math.min(fw - 1, x * fw / dw);
                    int sy = Math.min(fh - 1, y * fh / dh);
                    int px = src.getPixelRGBA(sx, sy);
                    if ((px >>> 24) != 0)
                        any = true;
                    sheet.setPixelRGBA(ox + x, oy + y, px);
                }
            }
            if (!any) {
                r.cells.put(id, -1);
                return;
            }
            r.cells.put(id, r.drawn++);
        } catch (Throwable t) {
            warnOnce("particle " + id, t);
            r.cells.put(id, -1);
        }
    }
}
