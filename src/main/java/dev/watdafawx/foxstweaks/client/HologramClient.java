package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramAnim;
import dev.watdafawx.foxstweaks.hologram.HologramNetwork;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
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

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.List;

/**
 * Client mirror of the server's hologram list for the dimension the player is in.
 *
 * <p>The editor keeps an edited copy here as {@link #setPreview}: the world renderer draws the
 * preview instead of the saved row, so changes are visible in world while the GUI is open without a
 * packet per click. Closing the GUI saves or drops it.
 */
public final class HologramClient {
    private static List<HologramSpec> cached = List.of();
    private static List<HologramSpec> visible = List.of();
    private static ResourceLocation dimension;
    private static HologramSpec preview;
    private static HologramSpec hovered;
    private static boolean previewAnchored;
    private static final Map<String, Float> appear = new HashMap<>();
    private static long lastAppearNanos;

    public static void init() {
        NeoForge.EVENT_BUS.addListener(HologramRenderer::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(HologramClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(HologramClient::onLevelUnload);
    }

    public static void acceptSync(HologramSyncPayload payload) {
        HologramClient.dimension = payload.dimension();
        HologramClient.cached = List.copyOf(payload.specs());
        rebuild();
    }

    /** The holograms to draw this frame, with the editor's unsaved copy merged in. */
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
        rebuild();
    }

    /** The edited copy the open editor is showing, or null when no editor is open. */
    @Nullable
    public static HologramSpec previewSpec() {
        return HologramClient.preview;
    }

    /**
     * When on, the renderer also draws the edited hologram a few blocks in front of the camera, so
     * the editor's preview is visible even if the real one is behind you or far away.
     */
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
            return;
        }

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

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            HologramClient.hovered = null;
            return;
        }
        updateAppear(mc);
        emitParticles(mc);
        if (!holdingWand(mc)) {
            HologramClient.hovered = null;
            return;
        }
        HologramClient.hovered = HologramPick.pick(mc.player.getEyePosition(), mc.player.getViewVector(1.0F),
                Holograms.REACH, HologramClient.visible);
    }

    /**
     * Advances each hologram's grow-in toward whether a player is inside its appear radius.
     *
     * <p>Client-side on purpose: the radius is about who is looking, each client animates its own copy,
     * and none of it needs the server to tick or to know.
     */
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
                var centre = spec.box().getCenter();
                double nearest = Double.MAX_VALUE;
                for (var player : players)
                    nearest = Math.min(nearest, player.distanceToSqr(centre));
                target = HologramAnim.appearTarget(nearest, spec.appearRadius());
            }

            // A hologram nobody has seen yet starts at its target, so walking up to an existing display
            // does not make it pop in the first time it is drawn.
            float current = HologramClient.appear.getOrDefault(spec.id(), target);
            HologramClient.appear.put(spec.id(),
                    HologramAnim.approach(current, target, spec.appearSeconds(), delta));
        }
        HologramClient.appear.keySet().removeIf(id -> !seen.contains(id));
    }

    /** 0..1 grow-in progress for a hologram; 1 for one with no appear radius. */
    public static float appearProgress(String id) {
        Float value = HologramClient.appear.get(id);
        return value == null ? 1.0F : value;
    }

    /**
     * Particle parts spawn here rather than in the render pass: particles are stateful, so adding them
     * once per frame would multiply the count by the frame rate. Every other tick is 10/s per part, which
     * reads as a steady trickle.
     */
    private static void emitParticles(Minecraft mc) {
        if (mc.level.getGameTime() % 2L != 0L)
            return;

        for (HologramSpec spec : HologramClient.visible) {
            if (spec.parts().isEmpty() || appearProgress(spec.id()) <= 0.01F)
                continue;
            if (HologramAnim.flickerHidden(spec.effects(), mc.level.getGameTime() / 20.0))
                continue;

            var centre = spec.box().getCenter();
            if (mc.player.distanceToSqr(centre) > (double) spec.renderDistance() * spec.renderDistance())
                continue;

            for (HologramPart part : spec.parts()) {
                if (part.kind() != HologramPart.Kind.PARTICLE)
                    continue;

                var key = ResourceLocation.tryParse(part.id());
                if (key == null)
                    continue;
                if (!(BuiltInRegistries.PARTICLE_TYPE.get(key) instanceof SimpleParticleType simple))
                    continue;

                double x = centre.x + part.offsetX();
                double y = centre.y + part.offsetY();
                double z = centre.z + part.offsetZ();
                double spread = 0.35 * part.scale() * 4.0;

                mc.level.addParticle(simple,
                        x + (mc.level.random.nextDouble() - 0.5) * spread,
                        y + (mc.level.random.nextDouble() - 0.5) * spread,
                        z + (mc.level.random.nextDouble() - 0.5) * spread,
                        0.0, 0.01, 0.0);
            }
        }
    }

    /** How many holograms currently have an appear state; the dev check asserts it tracks the list. */
    public static int appearTracked() {
        return HologramClient.appear.size();
    }

    private static boolean holdingWand(Minecraft mc) {
        return mc.player != null
                && (mc.player.getMainHandItem().getItem() instanceof dev.watdafawx.foxstweaks.hologram.HologramItem
                || mc.player.getOffhandItem().getItem() instanceof dev.watdafawx.foxstweaks.hologram.HologramItem);
    }

    /**
     * Client half of the wand's sneak-use: paste the copied hologram where the player clicked.
     *
     * <p>Only the client has a clipboard, so the create is sent from here. Aiming at an existing
     * hologram is left alone - that gesture is the server's delete.
     */
    public static void onWandUseOn(net.minecraft.world.item.context.UseOnContext context) {
        var player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown())
            return;

        HologramSpec clip = HologramClipboard.peek();
        if (clip == null) {
            player.displayClientMessage(net.minecraft.network.chat.Component
                    .literal("Clipboard is empty - copy a hologram from its editor's Share tab.")
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
        HologramClient.appear.clear();
        HologramEntities.clear();
        HologramBlockEntities.clear();
        HologramClient.lastAppearNanos = 0L;
    }

    private HologramClient() {
    }
}
