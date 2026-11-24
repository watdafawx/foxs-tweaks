package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.hologram.HologramPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity parts: a real entity instance that is never added to the level.
 *
 * <p>One instance is kept per (type) and reused every frame - creating an entity per draw would allocate
 * and, worse, let mods' constructors run at frame rate. The instance is given the hologram's position and
 * a synthetic {@code tickCount}, which is what idle animations read, so it animates without being ticked
 * (no AI, no gravity, no side effects). Some renderers want a level; the real client level is used, which
 * is harmless because the entity is never in it.
 */
public final class HologramEntities {
    private static final Map<String, Entity> CACHE = new HashMap<>();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    /** The instance for {@code part}, positioned and aged for this frame, or null when it cannot exist. */
    @Nullable
    public static Entity get(HologramPart part, double x, double y, double z, float yawDegrees, float seconds) {
        if (FAILED.contains(part.id()))
            return null;

        ResourceLocation key = ResourceLocation.tryParse(part.id());
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key))
            return null;

        Entity entity = CACHE.get(part.id());
        if (entity == null) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (type == null)
                return null;
            try {
                entity = type.create(Minecraft.getInstance().level);
            } catch (Throwable t) {
                FAILED.add(part.id());
                return null;
            }
            if (entity == null) {
                FAILED.add(part.id());
                return null;
            }
            // Not in the level: no AI, no collisions, no despawn, nothing to save.
            entity.setNoGravity(true);
            entity.setInvulnerable(true);
            CACHE.put(part.id(), entity);
        }

        entity.setPos(x, y, z);
        entity.setYRot(yawDegrees);
        entity.setYHeadRot(yawDegrees);
        entity.setXRot(0.0F);
        entity.tickCount = (int) (seconds * 20.0F);
        return entity;
    }

    public static void render(PoseStack pose, MultiBufferSource buffer, Entity entity, float partialTick,
                              int light, double x, double y, double z, float yawDegrees) {
        try {
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.render(entity, x, y, z, yawDegrees, partialTick, pose, buffer, light);
        } catch (Throwable ignored) {
            // Exotic modded entities whose renderer expects world/owner state must not crash the frame.
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key != null) {
                FAILED.add(key.toString());
                CACHE.remove(key.toString());
            }
        }
    }

    /** Drops the cached instances (dimension change, or the level's renderers were rebuilt). */
    public static void clear() {
        CACHE.clear();
        FAILED.clear();
    }

    private HologramEntities() {
    }
}
