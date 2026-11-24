package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.InteractionHand;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
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

    /** The instance for {@code layer}, positioned and aged for this frame, or null when it cannot exist. */
    @Nullable
    public static Entity get(HologramLayer layer, double x, double y, double z, float yawDegrees, float seconds) {
        return getById(layer.id(), x, y, z, yawDegrees, seconds);
    }

    /** Cached entity instance by registry id, shared by the world renderer and the picker preview. */
    @Nullable
    public static Entity getById(String id, double x, double y, double z, float yawDegrees, float seconds) {
        if (FAILED.contains(id))
            return null;

        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key))
            return null;

        Entity entity = CACHE.get(id);
        if (entity == null) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
            if (type == null)
                return null;
            try {
                entity = type.create(Minecraft.getInstance().level);
            } catch (Throwable t) {
                FAILED.add(id);
                return null;
            }
            if (entity == null) {
                FAILED.add(id);
                return null;
            }
            // Not in the level: no AI, no collisions, no despawn, nothing to save.
            entity.setNoGravity(true);
            entity.setInvulnerable(true);
            CACHE.put(id, entity);
        }

        entity.setPos(x, y, z);
        entity.setYRot(yawDegrees);
        entity.setYHeadRot(yawDegrees);
        entity.setXRot(0.0F);
        entity.tickCount = (int) (seconds * 20.0F);
        return entity;
    }

    /** Last synthetic tick each walk animation was advanced to, so it steps once per game tick, not per frame. */
    private static final Map<Entity, Integer> WALK_TICK = new IdentityHashMap<>();

    /**
     * Drives the entity's own model animations from WALK / ATTACK / HURT / DEATH / SNEAK entries, taken from
     * the layer and, when it sits in one, its group. Idle animations (breathing, wing flaps, tail wags) need
     * nothing: they already run off the synthetic {@code tickCount}.
     *
     * <p>The instance is shared per entity type, so every field touched here is reset each call - one layer's
     * pose must never leak into another layer of the same type.
     */
    public static void applyAnimations(Entity entity, List<HologramEffect> layerEffects,
                                       List<HologramEffect> groupEffects, double seconds) {
        if (!(entity instanceof LivingEntity living))
            return;

        float walk = -1.0F;
        float attackHz = 0.0F;
        float hurtHz = 0.0F;
        float deathHz = 0.0F;
        float attackPhase = 0.0F;
        float hurtPhase = 0.0F;
        float deathPhase = 0.0F;
        boolean sneak = false;
        for (int pass = 0; pass < 2; pass++) {
            for (HologramEffect effect : pass == 0 ? groupEffects : layerEffects) {
                switch (effect.type()) {
                    case WALK -> walk = Math.max(walk, effect.strength());
                    case ATTACK -> {
                        attackHz = effect.speed();
                        attackPhase = effect.phase();
                    }
                    case HURT -> {
                        hurtHz = effect.speed();
                        hurtPhase = effect.phase();
                    }
                    case DEATH -> {
                        deathHz = effect.speed();
                        deathPhase = effect.phase();
                    }
                    case SNEAK -> sneak = true;
                    default -> {
                    }
                }
            }
        }

        // Walk cycle: advance it the way the game does, once per tick.
        int tick = living.tickCount;
        if (walk >= 0.0F) {
            int last = WALK_TICK.getOrDefault(living, tick);
            int steps = Math.min(4, Math.max(0, tick - last));
            for (int i = 0; i < steps; i++)
                living.walkAnimation.update(Math.min(1.0F, walk), 0.4F);
            WALK_TICK.put(living, tick);
        } else if (WALK_TICK.remove(living) != null) {
            { living.walkAnimation.update(0.0F, 1.0F); living.walkAnimation.update(0.0F, 1.0F); }
        }

        float swing = 0.0F;
        if (attackHz > 0.0F) {
            double c = seconds * attackHz + attackPhase;
            c -= Math.floor(c);
            swing = c < 0.5 ? (float) (c / 0.5) : 0.0F;
        }
        living.swingingArm = InteractionHand.MAIN_HAND;
        living.attackAnim = swing;
        living.oAttackAnim = swing;

        int hurt = 0;
        if (hurtHz > 0.0F) {
            double c = seconds * hurtHz + hurtPhase;
            c -= Math.floor(c);
            hurt = c < 0.3 ? 10 : 0;
        }
        living.hurtDuration = 10;
        living.hurtTime = hurt;

        int death = 0;
        if (deathHz > 0.0F) {
            double c = seconds * deathHz + deathPhase;
            c -= Math.floor(c);
            // Topple over for the first half of the cycle, lie there, then pop back up.
            death = c < 0.85 ? 1 + (int) (Math.min(1.0, c / 0.5) * 20.0) : 0;
        }
        living.deathTime = death;

        if (sneak)
            living.setPose(Pose.CROUCHING);
        else if (living.getPose() == Pose.CROUCHING)
            living.setPose(Pose.STANDING);
    }

    static void markFailed(String id) {
        FAILED.add(id);
        CACHE.remove(id);
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
        WALK_TICK.clear();
        CACHE.clear();
        FAILED.clear();
    }

    private HologramEntities() {
    }
}
