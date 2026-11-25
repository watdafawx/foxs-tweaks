package dev.watdafawx.foxstweaks.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import dev.watdafawx.foxstweaks.hologram.HologramLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity parts: a real entity instance that is never added to the level.
 *
 * <p>Plain entity layers reuse one cached instance per type. Layers carrying NBT {@code data}
 * (e.g. a grabbed red sheep, librarian villager, or baby zombie) are cached per {@code (type, data)}
 * behind a zero-allocation identity fast-path keyed by the layer's immutable {@link CompoundTag}
 * reference, so unchanged layers never allocate per frame.
 */
public final class HologramEntities {
    private static final int MAX_DATA_CACHE = 128;
    private static final int MAX_FAST_CACHE = 256;

    private record DataKey(String id, int hash, CompoundTag tag) {
    }

    private record FastEntry(String id, @Nullable Entity entity) {
    }

    private static final Map<String, Entity> CACHE = new HashMap<>();
    private static final Set<String> FAILED = new HashSet<>();
    private static final Map<DataKey, Entity> DATA_CACHE = new HashMap<>();
    private static final Set<DataKey> DATA_FAILED = new HashSet<>();
    private static final Map<CompoundTag, FastEntry> FAST_DATA_CACHE = new IdentityHashMap<>();
    private static final Set<Entity> FAILED_INSTANCES = Collections.newSetFromMap(new IdentityHashMap<>());

    /** The instance for {@code layer}, positioned and aged for this frame, or null when it cannot exist. */
    @Nullable
    public static Entity get(HologramLayer layer, double x, double y, double z, float yawDegrees, float seconds) {
        CompoundTag data = layer.data();
        if (data == null || data.isEmpty())
            return getById(layer.id(), x, y, z, yawDegrees, seconds);
        return getWithData(layer.id(), data, x, y, z, yawDegrees, seconds);
    }

    /** Cached entity instance by registry id, shared by the world renderer and the picker preview. */
    @Nullable
    public static Entity getById(String id, double x, double y, double z, float yawDegrees, float seconds) {
        if (FAILED.contains(id))
            return null;

        Entity entity = CACHE.get(id);
        if (entity == null) {
            entity = createEntity(id);
            if (entity == null) {
                FAILED.add(id);
                return null;
            }
            CACHE.put(id, entity);
        }

        if (FAILED_INSTANCES.contains(entity))
            return null;
        return prepareFrame(entity, x, y, z, yawDegrees, seconds);
    }

    @Nullable
    private static Entity getWithData(String id, CompoundTag data,
                                      double x, double y, double z, float yawDegrees, float seconds) {
        if (FAILED.contains(id))
            return null;

        // L1: zero-allocation identity lookup for unchanged layer CompoundTag references.
        FastEntry fast = FAST_DATA_CACHE.get(data);
        if (fast != null && fast.id().equals(id)) {
            Entity cached = fast.entity();
            if (cached == null || FAILED_INSTANCES.contains(cached))
                return null;
            return prepareFrame(cached, x, y, z, yawDegrees, seconds);
        }

        // L2: content-based lookup keyed by (type, hash, tag) for reconstructed specs with equal NBT.
        DataKey key = new DataKey(id, data.hashCode(), data);
        if (DATA_FAILED.contains(key)) {
            putFast(data, new FastEntry(id, null));
            return null;
        }

        Entity entity = DATA_CACHE.get(key);
        if (entity == null) {
            entity = createEntity(id);
            if (entity == null) {
                DATA_FAILED.add(key);
                putFast(data, new FastEntry(id, null));
                return null;
            }
            applyCapturedData(entity, data);
            if (DATA_CACHE.size() >= MAX_DATA_CACHE) {
                DATA_CACHE.clear();
                FAST_DATA_CACHE.clear();
            }
            DATA_CACHE.put(new DataKey(id, key.hash(), data.copy()), entity);
        }

        putFast(data, new FastEntry(id, entity));
        if (FAILED_INSTANCES.contains(entity))
            return null;
        return prepareFrame(entity, x, y, z, yawDegrees, seconds);
    }

    private static void putFast(CompoundTag data, FastEntry entry) {
        if (FAST_DATA_CACHE.size() >= MAX_FAST_CACHE)
            FAST_DATA_CACHE.clear();
        FAST_DATA_CACHE.put(data, entry);
    }

    @Nullable
    private static Entity createEntity(String id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key))
            return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
        if (type == null)
            return null;
        Entity entity;
        try {
            entity = type.create(mc.level);
        } catch (Throwable t) {
            return null;
        }
        if (entity == null)
            return null;
        applyHologramDefaults(entity);
        return entity;
    }

    private static void applyCapturedData(Entity entity, CompoundTag data) {
        try {
            entity.load(data.copy());
        } catch (Throwable ignored) {
            // Keep partially loaded / default entity rather than crashing the frame.
        }
        if (entity instanceof AgeableMob ageable && data.contains("Age") && data.getInt("Age") < 0)
            ageable.setBaby(true);
        applyHologramDefaults(entity);
    }

    private static void applyHologramDefaults(Entity entity) {
        // Not in the level: no AI, no gravity, no collisions, no despawn, full health.
        entity.setNoGravity(true);
        entity.setInvulnerable(true);
        entity.setDeltaMovement(0.0, 0.0, 0.0);
        if (entity instanceof Mob mob)
            mob.setNoAi(true);
        if (entity instanceof LivingEntity living) {
            living.setHealth(living.getMaxHealth());
            living.hurtTime = 0;
            living.deathTime = 0;
        }
    }

    private static Entity prepareFrame(Entity entity, double x, double y, double z, float yawDegrees, float seconds) {
        entity.setPos(x, y, z);
        entity.setYRot(yawDegrees);
        entity.yRotO = yawDegrees;
        entity.setYHeadRot(yawDegrees);
        entity.setXRot(0.0F);
        entity.xRotO = 0.0F;
        if (entity instanceof LivingEntity living) {
            living.yBodyRot = yawDegrees;
            living.yBodyRotO = yawDegrees;
            living.yHeadRot = yawDegrees;
            living.yHeadRotO = yawDegrees;
        }
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
            FAILED_INSTANCES.add(entity);
            ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
            if (key != null && CACHE.get(key.toString()) == entity) {
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
        DATA_CACHE.clear();
        DATA_FAILED.clear();
        FAST_DATA_CACHE.clear();
        FAILED_INSTANCES.clear();
    }

    private HologramEntities() {
    }
}
