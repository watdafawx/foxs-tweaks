package dev.watdafawx.foxstweaks.client.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import dev.watdafawx.foxstweaks.hologram.HologramImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.watermedia.api.player.PlayerAPI;
import org.watermedia.api.player.videolan.VideoPlayer;

import java.net.URI;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional WATERMeDIA bridge for URL-streamed video & live-stream hologram layers.
 * Only classloaded when {@code watermedia} is installed.
 */
public final class WaterMediaBridge {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_PLAYERS = 8;
    private static final long IDLE_RELEASE_MS = 5_000L;

    private static final class Slot {
        final String url;
        final VideoPlayer player;
        long lastUsedMs;
        double aspect = Double.NaN;

        Slot(String url, VideoPlayer player) {
            this.url = url;
            this.player = player;
            this.lastUsedMs = System.currentTimeMillis();
        }
    }

    private static final Map<String, Slot> PLAYERS = new LinkedHashMap<>(16, 0.75F, true);
    private static final Map<String, Double> ASPECTS = new LinkedHashMap<>(32, 0.75F, true);

    private WaterMediaBridge() {
    }

    public static double aspectRatio(String url) {
        Double a = ASPECTS.get(url);
        return a == null ? Double.NaN : a;
    }

    public static boolean draw(PoseStack pose, String hologramId, String url, HologramImage image,
                               float x0, float y0, float x1, float y1, double distSq, double maxDist) {
        if (!PlayerAPI.isReady())
            return false;

        String key = hologramId + "\0" + url;
        Slot slot = PLAYERS.get(key);
        if (slot == null) {
            if (PLAYERS.size() >= MAX_PLAYERS) {
                var it = PLAYERS.entrySet().iterator();
                if (it.hasNext()) {
                    var eldest = it.next();
                    releaseSafe(eldest.getValue().player);
                    it.remove();
                }
            }
            try {
                VideoPlayer vp = new VideoPlayer(Minecraft.getInstance());
                vp.setRepeatMode(image.playback() == HologramImage.Playback.LOOP);
                vp.setVolume(0);
                vp.start(URI.create(url));
                slot = new Slot(url, vp);
                PLAYERS.put(key, slot);
            } catch (Throwable t) {
                LOG.warn("WATERMeDIA failed to open '{}': {}", url, t.getMessage());
                return false;
            }
        }

        slot.lastUsedMs = System.currentTimeMillis();
        VideoPlayer vp = slot.player;
        if (vp.isBroken())
            return false;

        // Distance-attenuated volume following Minecraft's Master & Records sliders.
        Minecraft mc = Minecraft.getInstance();
        float gain = mc.options.getSoundSourceVolume(SoundSource.MASTER)
                * mc.options.getSoundSourceVolume(SoundSource.RECORDS);
        double dist = Math.sqrt(Math.max(0.0, distSq));
        double range = Math.max(4.0, Math.min(maxDist, 32.0));
        double atten = Mth.clamp(1.0 - dist / range, 0.0, 1.0);
        int vol = Mth.clamp((int) Math.round(gain * atten * 100.0), 0, 100);
        if (vp.getVolume() != vol)
            vp.setVolume(vol);

        int tex = vp.preRender();
        int vw = vp.width();
        int vh = vp.height();
        if (tex <= 0 || vw <= 0 || vh <= 0)
            return false;

        double aspect = (double) vh / (double) vw;
        slot.aspect = aspect;
        ASPECTS.put(url, aspect);

        Matrix4f mat = pose.last().pose();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, tex);

        BufferBuilder bb = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        bb.addVertex(mat, x0, y0, 0.0F).setUv(0.0F, 1.0F).setColor(255, 255, 255, 255);
        bb.addVertex(mat, x1, y0, 0.0F).setUv(1.0F, 1.0F).setColor(255, 255, 255, 255);
        bb.addVertex(mat, x1, y1, 0.0F).setUv(1.0F, 0.0F).setColor(255, 255, 255, 255);
        bb.addVertex(mat, x0, y1, 0.0F).setUv(0.0F, 0.0F).setColor(255, 255, 255, 255);
        BufferUploader.drawWithShader(bb.buildOrThrow());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        return true;
    }

    public static void tick() {
        if (PLAYERS.isEmpty())
            return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Slot>> it = PLAYERS.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (now - entry.getValue().lastUsedMs > IDLE_RELEASE_MS) {
                releaseSafe(entry.getValue().player);
                it.remove();
            }
        }
    }

    public static void clear() {
        for (Slot s : PLAYERS.values())
            releaseSafe(s.player);
        PLAYERS.clear();
        ASPECTS.clear();
    }

    private static void releaseSafe(VideoPlayer vp) {
        try {
            vp.release();
        } catch (Throwable ignored) {
        }
    }
}
