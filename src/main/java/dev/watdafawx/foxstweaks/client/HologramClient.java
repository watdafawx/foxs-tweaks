package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramGroup;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramPick;
import dev.watdafawx.foxstweaks.hologram.HologramSpec;
import dev.watdafawx.foxstweaks.hologram.HologramSyncPayload;
import dev.watdafawx.foxstweaks.hologram.Holograms;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Client mirror of the server's hologram list for the current dimension, plus editor-local
 * preview overlay and layer/group solo filter.
 */
public final class HologramClient {
    private static List<HologramSpec> cached = List.of();
    private static List<HologramSpec> visible = List.of();
    private static ResourceLocation dimension;
    private static HologramSpec preview;
    private static HologramSpec hovered;
    private static boolean previewAnchored;

    // Editor-local solo filter (never hides layers for other players).
    @Nullable
    private static String soloHologramId;
    private static int soloLayerIndex = -1;
    @Nullable
    private static String soloGroupId;

    private static final Map<String, Float> appear = new HashMap<>();
    private static long lastAppearNanos;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(HologramRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HologramClient::onClientTick);
        HologramEditorScale.init();
        NeoForge.EVENT_BUS.addListener(HologramClient::onLevelUnload);
        HologramTriggers.install();
        dev.watdafawx.foxstweaks.client.audio.AudioHub.install();
        HologramGrab.init();
        dev.watdafawx.foxstweaks.client.web.HologramWebSession.init();
    }

    public static void acceptSync(HologramSyncPayload payload) {
        HologramClient.dimension = payload.dimension();
        HologramClient.cached = List.copyOf(payload.specs());
        rebuild();
    }

    public static List<HologramSpec> specs() {
        return HologramClient.visible;
    }

    @Nullable
    public static HologramSpec hovered() {
        return HologramClient.hovered;
    }

    @Nullable
    public static ResourceLocation dimension() {
        return HologramClient.dimension;
    }

    public static void setPreview(HologramSpec spec) {
        HologramClient.preview = spec;
        rebuild();
    }

    public static void clearPreview() {
        HologramClient.preview = null;
        HologramClient.hovered = null;
        clearSolo();
        rebuild();
    }

    public static void setSoloLayer(@Nullable String hologramId, int layerIndex) {
        HologramClient.soloHologramId = hologramId;
        HologramClient.soloLayerIndex = layerIndex;
        HologramClient.soloGroupId = null;
    }

    public static void setSoloGroup(@Nullable String hologramId, @Nullable String groupId) {
        HologramClient.soloHologramId = hologramId;
        HologramClient.soloLayerIndex = -1;
        HologramClient.soloGroupId = groupId;
    }

    public static void clearSolo() {
        HologramClient.soloHologramId = null;
        HologramClient.soloLayerIndex = -1;
        HologramClient.soloGroupId = null;
    }

    /** True if this layer passes the editor-local solo filter (or solo is off for this hologram). */
    public static boolean passesSolo(String hologramId, int layerIndex, HologramLayer layer) {
        if (soloHologramId == null || !soloHologramId.equals(hologramId))
            return true;
        if (soloLayerIndex >= 0)
            return layerIndex == soloLayerIndex;
        if (soloGroupId != null)
            return soloGroupId.equals(layer.groupId());
        return true;
    }

    @Nullable
    public static HologramSpec previewSpec() {
        return HologramClient.preview;
    }

    public static boolean previewAnchored() {
        return HologramClient.previewAnchored;
    }

    public static void setPreviewAnchored(boolean anchored) {
        HologramClient.previewAnchored = anchored;
    }

    private static void rebuild() {
        HologramSpec preview = HologramClient.preview;
        if (preview == null) {
            HologramClient.visible = HologramClient.cached;
        } else {
            List<HologramSpec> merged = new ArrayList<>(HologramClient.cached.size() + 1);
            boolean replaced = false;
            for (HologramSpec spec : HologramClient.cached) {
                if (spec.id().equals(preview.id())) {
                    merged.add(preview);
                    replaced = true;
                } else {
                    merged.add(spec);
                }
            }
            if (!replaced)
                merged.add(preview);
            HologramClient.visible = List.copyOf(merged);
        }

        Set<String> activeIds = new HashSet<>(HologramClient.visible.size());
        for (HologramSpec spec : HologramClient.visible)
            activeIds.add(spec.id());
        HologramBlockEntities.prune(activeIds);
        HologramStructureMesh.prune(HologramClient.visible);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            HologramClient.hovered = null;
            return;
        }
        updateAppear(mc);
        HologramBlockEntities.tick(mc);
        emitParticles(mc);
        if (net.neoforged.fml.ModList.get().isLoaded("watermedia"))
            dev.watdafawx.foxstweaks.client.compat.WaterMediaBridge.tick();
        while (FoxsTweaksKeys.OPEN_HOLOGRAMS.consumeClick()) {
            if (mc.screen == null)
                mc.setScreen(new HologramNearbyScreen());
        }
        if (!holdingWand(mc)) {
            HologramClient.hovered = null;
            return;
        }
        HologramClient.hovered = HologramPick.pick(mc.level, mc.player.getEyePosition(), mc.player.getViewVector(1.0F),
                Holograms.REACH, HologramClient.visible);
    }

    private static void updateAppear(Minecraft mc) {
        long now = System.nanoTime();
        double delta = lastAppearNanos == 0L ? 0.05 : Math.min(0.25, (now - lastAppearNanos) / 1.0E9);
        lastAppearNanos = now;

        var players = mc.level.players();
        Set<String> seen = new HashSet<>();
        for (HologramSpec spec : HologramClient.visible) {
            seen.add(spec.id());

            float target = 1.0F;
            if (spec.appearRadius() > 0.0F) {
                var box = spec.box(mc.level, 1.0F);
                if (box == null) {
                    target = 0.0F;
                } else {
                    var centre = box.getCenter();
                    double nearest = Double.MAX_VALUE;
                    for (var player : players)
                        nearest = Math.min(nearest, player.distanceToSqr(centre));
                    target = HologramAnim.appearTarget(nearest, spec.appearRadius());
                }
            }

            float current = HologramClient.appear.getOrDefault(spec.id(), target);
            HologramClient.appear.put(spec.id(),
                    HologramAnim.approach(current, target, spec.appearSeconds(), delta));
        }
        HologramClient.appear.keySet().removeIf(id -> !seen.contains(id));
    }

    public static float appearProgress(String id) {
        Float value = HologramClient.appear.get(id);
        return value == null ? 1.0F : value;
    }

    /**
     * Emits particles from exact transformed layer world positions (root -> group -> layer + hover),
     * respecting per-layer rate, spread, rise speed, visibility, solo, and group/layer flicker.
     */
    private static void emitParticles(Minecraft mc) {
        double seconds = HologramRenderer.animSeconds();
        Vector3f posBuf = new Vector3f();

        for (HologramSpec spec : HologramClient.visible) {
            if (!spec.enabled() || spec.layers().isEmpty() || appearProgress(spec.id()) <= 0.01F)
                continue;
            if (spec.attach() != null && spec.attach().hideFirstPerson()
                    && spec.attach().playerUuid().equals(mc.player.getUUID())
                    && mc.options.getCameraType().isFirstPerson() && !HologramCamera.isActive())
                continue;

            var box = spec.box(mc.level, 1.0F);
            if (box == null)
                continue;
            if (spec.path() != null && spec.path().active())
                box = spec.path().expand(box);
            var centre = box.getCenter();
            if (mc.player.distanceToSqr(centre) > (double) spec.renderDistance() * spec.renderDistance())
                continue;

            Matrix4f rootMat = null;
            List<HologramLayer> layers = spec.layers();
            for (int i = 0; i < layers.size(); i++) {
                HologramLayer layer = layers.get(i);
                if (layer.kind() != HologramLayer.Kind.PARTICLE || !layer.visible())
                    continue;
                if (!passesSolo(spec.id(), i, layer))
                    continue;

                HologramGroup group = spec.groupById(layer.groupId());
                List<HologramEffect> gFx = group == null ? List.of()
                        : HologramTriggers.resolve(group.effects(), spec, seconds);
                List<HologramEffect> lFx = HologramTriggers.resolve(layer.effects(), spec, seconds);
                if (group != null) {
                    if (!group.visible() || HologramAnim.flickerHidden(gFx, seconds))
                        continue;
                }
                if (HologramAnim.flickerHidden(lFx, seconds))
                    continue;

                var key = ResourceLocation.tryParse(layer.id());
                if (key == null)
                    continue;
                int tintRgb = -1;
                for (HologramEffect fx : lFx) {
                    if (fx.type() == HologramEffect.Type.TINT || fx.type() == HologramEffect.Type.GRADIENT) {
                        tintRgb = fx.color();
                        break;
                    }
                }
                var particleOpt = HologramParticles.resolve(key, tintRgb);
                if (particleOpt == null)
                    continue;

                // Expected particles this tick = rate / 20.0
                float perTick = layer.particleRate() / 20.0F;
                int count = (int) perTick;
                if (mc.level.random.nextFloat() < (perTick - count))
                    count++;
                if (count <= 0)
                    continue;

                if (rootMat == null) {
                    rootMat = spec.rootMatrix(mc.level, 1.0F);
                    if (rootMat == null)
                        break;
                    if (spec.path() != null && spec.path().active()) {
                        var ps = spec.path().sample(seconds);
                        rootMat = new Matrix4f().translate(ps.x(), ps.y(), ps.z()).mul(rootMat);
                    }
                }

                Matrix4f m = new Matrix4f(rootMat);
                if (group != null) {
                    float gHover = HologramRenderer.hoverOffset(group.hover(), group.hoverPeriod(), seconds);
                    float gSpin = HologramRenderer.spinAngle(group.spin(), seconds);
                    float gJump = HologramAnim.jumpOffset(gFx, seconds);
                    HologramAnim.Motion gm = HologramAnim.motion(gFx, seconds);
                    m.translate(group.offsetX() + gm.dx(), group.offsetY() + gHover + gJump + gm.dy(), group.offsetZ() + gm.dz())
                            .rotateY((float) Math.toRadians(group.yaw() + gm.ry() + (group.spinAxis() == HologramSpec.AXIS_Y ? gSpin : 0.0F)))
                            .rotateX((float) Math.toRadians(group.pitch() + gm.rx() + (group.spinAxis() == HologramSpec.AXIS_X ? gSpin : 0.0F)))
                            .rotateZ((float) Math.toRadians(group.roll() + gm.rz() + (group.spinAxis() == HologramSpec.AXIS_Z ? gSpin : 0.0F)))
                            .scale(group.scale() * gm.scale());
                }

                float lHover = HologramRenderer.hoverOffset(layer.hover(), layer.hoverPeriod(), seconds);
                float lJump = HologramAnim.jumpOffset(lFx, seconds);
                HologramAnim.Motion lm = HologramAnim.motion(lFx, seconds);
                posBuf.set(layer.offsetX() + lm.dx(), layer.offsetY() + lHover + lJump + lm.dy(), layer.offsetZ() + lm.dz());
                m.transformPosition(posBuf);

                double spread = layer.scale() * spec.scale() * (group != null ? group.scale() : 1.0F);
                double vy = layer.particleSpeed();

                for (int p = 0; p < count; p++) {
                    mc.level.addParticle(particleOpt,
                            posBuf.x + (mc.level.random.nextDouble() - 0.5) * 2.0 * spread,
                            posBuf.y + (mc.level.random.nextDouble() - 0.5) * 2.0 * spread,
                            posBuf.z + (mc.level.random.nextDouble() - 0.5) * 2.0 * spread,
                            0.0, vy, 0.0);
                }
            }
        }
    }

    public static int appearTracked() {
        return HologramClient.appear.size();
    }

    private static boolean holdingWand(Minecraft mc) {
        return mc.player != null
                && (mc.player.getMainHandItem().getItem() instanceof dev.watdafawx.foxstweaks.hologram.HologramItem
                || mc.player.getOffhandItem().getItem() instanceof dev.watdafawx.foxstweaks.hologram.HologramItem);
    }

    public static void onWandUseAir() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || HologramClient.hovered != null)
            return;
        mc.setScreen(new HologramNearbyScreen());
    }

    public static void onWandUseOn(net.minecraft.world.item.context.UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown())
            return;

        HologramSpec clip = HologramClipboard.peek();
        if (clip == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component
                    .literal("Clipboard is empty - copy a hologram from its editor's Layers tab.")
                    .withStyle(net.minecraft.ChatFormatting.GRAY), true);
            return;
        }
        if (HologramClient.hovered != null)
            return;

        HologramNetwork.sendCreate(context.getClickedPos().relative(context.getClickedFace()), clip);
    }

    private static void onLevelUnload(LevelEvent.Unload event) {
        if (!event.getLevel().isClientSide())
            return;
        HologramClient.cached = List.of();
        HologramClient.visible = List.of();
        HologramClient.preview = null;
        HologramClient.hovered = null;
        HologramClient.dimension = null;
        HologramCamera.end();
        HologramGrab.clear();
        clearSolo();
        HologramClient.appear.clear();
        HologramEntities.clear();
        HologramBlockEntities.clear();
        HologramStructureMesh.clear();
        HologramModels.clear();
        if (net.neoforged.fml.ModList.get().isLoaded("watermedia"))
            dev.watdafawx.foxstweaks.client.compat.WaterMediaBridge.clear();
        HologramPickerPreview.clear();
        HologramTimelinePlayer.clear();
        HologramClient.lastAppearNanos = 0L;
    }

    private HologramClient() {
    }
}
