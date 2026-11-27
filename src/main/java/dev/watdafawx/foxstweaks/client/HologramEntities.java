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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Entity parts: a real entity instance that is never added to the level.
 *
 * <p>Bounded per-owner/layer cache keeps renderer and animation controller state isolated.
 */
public final class HologramEntities {
    private static final CompoundTag EMPTY_DATA = new CompoundTag();

    private record Entry(String id, CompoundTag data, Map<String, String> props, Entity entity) {}

    private static final Map<Object, Entry> CACHE = new java.util.LinkedHashMap<>(256, 0.75F, true);
    private static final Set<Entity> FAILED_INSTANCES = Collections.newSetFromMap(new IdentityHashMap<>());

    @Nullable
    public static Entity get(HologramLayer layer, double x, double y, double z, float yawDegrees, float seconds) {
        return get(layer, layer.id(), layer.data(), layer.props(), x, y, z, yawDegrees, seconds);
    }

    /** World layers use owner + layer uid, so duplicated holograms never share animation controllers. */
    @Nullable
    public static Entity get(String owner, HologramLayer layer, double x, double y, double z, float yawDegrees, float seconds) {
        return get(owner + ":" + layer.uid(), layer.id(), layer.data(), layer.props(), x, y, z, yawDegrees, seconds);
    }

    @Nullable
    public static Entity getById(String id, double x, double y, double z, float yawDegrees, float seconds) {
        return get(id, id, EMPTY_DATA, Map.of(), x, y, z, yawDegrees, seconds);
    }

    @Nullable
    private static Entity get(Object key, String id, CompoundTag data, Map<String, String> props,
                              double x, double y, double z, float yaw, float seconds) {
        Entry entry = CACHE.get(key);
        if (entry == null || !entry.id().equals(id) || !entry.data().equals(data) || !entry.props().equals(props)) {
            if (entry != null)
                FAILED_INSTANCES.remove(entry.entity());

            Entity entity = createEntity(id);
            if (entity != null && !data.isEmpty())
                applyCapturedData(entity, data);
            if (entity != null && !props.isEmpty())
                applyEntityProps(entity, props);

            if (CACHE.size() >= 256) {
                Object oldest = CACHE.keySet().iterator().next();
                FAILED_INSTANCES.remove(CACHE.remove(oldest).entity());
            }
            entry = new Entry(id, data.copy(), Map.copyOf(props), entity);
            CACHE.put(key, entry);
        }
        if (entry.entity() == null || FAILED_INSTANCES.contains(entry.entity()))
            return null;

        return prepareFrame(entry.entity(), x, y, z, yaw, seconds);
    }

    private static void applyEntityProps(Entity entity, Map<String, String> props) {
        if ("true".equalsIgnoreCase(props.get("invisible")))
            entity.setInvisible(true);
        String effectRaw = props.get("effect");
        if (effectRaw != null && !effectRaw.isEmpty() && entity instanceof LivingEntity living) {
            ResourceLocation loc = ResourceLocation.tryParse(effectRaw);
            if (loc != null) {
                BuiltInRegistries.MOB_EFFECT.getHolder(loc).ifPresent(holder -> {
                    try {
                        living.forceAddEffect(new net.minecraft.world.effect.MobEffectInstance(
                                holder, 20 * 60 * 60, 0, false, true, true), null);
                    } catch (Throwable ignored) {
                    }
                });
            }
        }
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
        entity.setSilent(true);
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

    /** Pure clock sampling: no entity tick, AI, movement, sounds or particle events. */
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
        HologramEffect locomotion = null;
        HologramEffect special = null;
        for (int pass = 0; pass < 2; pass++) {
            for (HologramEffect effect : pass == 0 ? groupEffects : layerEffects) {
                switch (effect.type()) {
                    case WALK, RUN, FLY, SWIM -> {
                        locomotion = effect;
                        walk = effect.strength();
                    }
                    case REST, ROAR, SNIFF, CROAK, ROLL -> special = effect;
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

        float stride = Math.max(0, Math.min(1, walk));
        double animationSeconds = locomotion == null ? seconds : seconds * locomotion.speed() + locomotion.phase();
        if (locomotion != null && locomotion.type() == HologramEffect.Type.RUN)
            animationSeconds *= 1.8;

        // Vanilla interpolates position - speed * (1-partial). Set both endpoints, including on seeks.
        living.walkAnimation.speedOld = stride;
        living.walkAnimation.setSpeed(stride);
        living.walkAnimation.position = (float) ((animationSeconds * 20 - (seconds * 20 - Math.floor(seconds * 20)) + 1) * stride);
        HologramEntityLife.apply(living, locomotion, special, animationSeconds, seconds);

        float swing = 0.0F;
        if (attackHz > 0.0F) {
            double c = seconds * attackHz + attackPhase;
            c -= Math.floor(c);
            swing = c < 0.5 ? (float) (c / 0.5) : 0.0F;
        }
        living.swingingArm = InteractionHand.MAIN_HAND;
        living.attackAnim = swing;
        living.oAttackAnim = swing;
        living.swinging = swing > 0;
        living.swingTime = (int) (swing * 6);

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
        living.setHealth(death > 0 ? 0 : living.getMaxHealth());

        if (sneak)
            living.setPose(Pose.CROUCHING);
        else if (living.getPose() == Pose.CROUCHING)
            living.setPose(Pose.STANDING);
    }

    static void markFailed(String id) {
        Entry entry = CACHE.get(id);
        if (entry != null) {
            FAILED_INSTANCES.remove(entry.entity());
            CACHE.put(id, new Entry(id, entry.data(), entry.props(), null));
        }
    }

    public static void render(PoseStack pose, MultiBufferSource buffer, Entity entity, float partialTick,
                              int light, double x, double y, double z, float yawDegrees) {
        try {
            EntityRenderDispatcher dispatcher = Minecraft.getInstance().getEntityRenderDispatcher();
            dispatcher.render(entity, x, y, z, yawDegrees, partialTick, pose, buffer, light);
        } catch (Throwable ignored) {
            // Exotic modded entities whose renderer expects world/owner state must not crash the frame.
            FAILED_INSTANCES.add(entity);
        }
    }

    /** Drops the cached instances (dimension change, or the level's renderers were rebuilt). */
    public static void clear() {
        CACHE.clear();
        FAILED_INSTANCES.clear();
    }

    private HologramEntities() {
    }
}
