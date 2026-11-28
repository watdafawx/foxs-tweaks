package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.piglin.Piglin;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Animation-only state sampling. Never invokes tick/aiStep or changes the client world. */
public final class HologramEntityLife {

    public static List<HologramEffect.Type> offered(String id) {
        List<HologramEffect.Type> out = new ArrayList<>(List.of(HologramEffect.Type.WALK,
                HologramEffect.Type.RUN, HologramEffect.Type.ATTACK, HologramEffect.Type.HURT,
                HologramEffect.Type.DEATH, HologramEffect.Type.SNEAK));
        String name = id.substring(id.indexOf(':') + 1);
        List<String> clips = HologramEntityStates.animations(id);
        if (clips.contains("fly") || List.of("bee", "parrot", "bat", "chicken", "phantom", "allay", "ghast", "blaze", "vex").contains(name))
            out.add(HologramEffect.Type.FLY);
        if (clips.contains("swim") || List.of("cod", "salmon", "pufferfish", "tropical_fish", "squid", "glow_squid", "dolphin", "axolotl", "guardian", "elder_guardian", "frog").contains(name))
            out.add(HologramEffect.Type.SWIM);
        if (List.of("bat", "camel", "wolf", "cat", "parrot").contains(name))
            out.add(HologramEffect.Type.REST);
        if (name.equals("warden"))
            out.add(HologramEffect.Type.ROAR);
        if (List.of("warden", "sniffer").contains(name))
            out.add(HologramEffect.Type.SNIFF);
        if (name.equals("frog"))
            out.add(HologramEffect.Type.CROAK);
        if (name.equals("armadillo"))
            out.add(HologramEffect.Type.ROLL);
        return out;
    }

    static void apply(LivingEntity entity, @Nullable HologramEffect movement, @Nullable HologramEffect special,
                      @Nullable String clip, float clipSpeed, double movementSeconds, double seconds) {
        String activeClip = clip == null ? "" : clip;
        boolean flying = (movement != null && movement.type() == HologramEffect.Type.FLY) || "fly".equals(activeClip);
        boolean swimming = (movement != null && movement.type() == HologramEffect.Type.SWIM) || "swim".equals(activeClip);
        boolean resting = special != null && special.type() == HologramEffect.Type.REST;

        entity.setOnGround(!flying && !swimming);
        entity.wasTouchingWater = swimming;
        entity.setSwimming(swimming);
        boolean moving = movement != null || "walk".equals(activeClip) || "run".equals(activeClip) || flying || swimming;
        entity.setDeltaMovement(moving ? 0.1 : 0, 0, 0);
        // Keep previous positions consistent for renderers that derive movement without walkAnimation.
        entity.xo = entity.getX() - entity.getDeltaMovement().x;
        entity.yo = entity.getY();
        entity.zo = entity.getZ();

        if (entity instanceof Bat bat && resting)
            bat.setResting(true);
        if (entity instanceof TamableAnimal tame && resting)
            tame.setInSittingPose(true);

        double fraction = seconds * 20 - Math.floor(seconds * 20);
        float rate = movement != null ? movement.speed() : clipSpeed;
        if ((movement != null && movement.type() == HologramEffect.Type.RUN) || "run".equals(activeClip))
            rate *= 1.8F;
        float previous = (float) (movementSeconds * 20 - fraction * rate);
        float tick = previous + rate;

        if (entity instanceof Parrot parrot) {
            parrot.flap = tick * 1.8F;
            parrot.oFlap = previous * 1.8F;
            parrot.flapSpeed = parrot.oFlapSpeed = flying ? 1 : 0;
        }
        if (entity instanceof Chicken chicken) {
            chicken.flap = tick * 1.8F;
            chicken.oFlap = previous * 1.8F;
            chicken.flapSpeed = chicken.oFlapSpeed = flying ? 1 : 0;
        }
        if (entity instanceof Squid squid) {
            squid.tentacleAngle = (float) (Math.sin(tick * 0.15) * 0.7);
            squid.oldTentacleAngle = (float) (Math.sin(previous * 0.15) * 0.7);
        }
        if (entity instanceof Guardian guardian) {
            guardian.clientSideTailAnimation = tick * 0.5F;
            guardian.clientSideTailAnimationO = previous * 0.5F;
            guardian.clientSideSpikesAnimation = guardian.clientSideSpikesAnimationO = 1;
        }
        if (entity instanceof Axolotl axolotl)
            axolotl.getModelRotationValues().clear();

        // Procedural clip animations for mobs that animate via public fields/setters rather than AnimationState.
        if (!activeClip.isEmpty()) {
            applyProceduralClip(entity, activeClip, seconds * clipSpeed);
        }

        // Dynamic AnimationState playback: supports every vanilla & modded AnimationState field.
        Map<String, Field> states = HologramEntityStates.animationStateFields(entity.getClass());
        for (Map.Entry<String, Field> e : states.entrySet()) {
            String clipName = e.getKey();
            Field field = e.getValue();
            try {
                AnimationState state = (AnimationState) field.get(entity);
                if (state == null)
                    continue;

                boolean matchedClip = !activeClip.isEmpty() && activeClip.equals(clipName);
                boolean matchedSpecial = special != null && matchesSpecial(special.type(), clipName);
                boolean active = clipName.equals("idle")
                        || (clipName.equals("fly") && flying)
                        || (clipName.equals("rest") && resting)
                        || matchedSpecial
                        || matchedClip;

                state.stop();
                if (active) {
                    double age;
                    if (matchedClip) {
                        age = seconds * clipSpeed;
                    } else if (matchedSpecial && special != null) {
                        age = seconds * special.speed() + special.phase();
                    } else {
                        age = seconds;
                    }
                    state.start(0);
                    float ticks = (matchedClip || matchedSpecial) ? (float) ((age * 20.0) % 80.0) : (float) (age * 20.0);
                    state.updateTime(ticks, 1.0F);
                    state.lastTime = (long) Math.floor(seconds * 1000.0);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    /** Non-living entity procedural animations (e.g. EnderDragon). */
    static void applyNonLiving(Entity entity, @Nullable String clip, float clipSpeed, double seconds) {
        if (entity instanceof EnderDragon dragon) {
            if ("flap".equals(clip)) {
                double t = seconds * clipSpeed * 0.6;
                float phase = (float) (t - Math.floor(t));
                dragon.oFlapTime = phase;
                dragon.flapTime = phase;
            }
        }
    }

    private static void applyProceduralClip(LivingEntity entity, String clip, double t) {
        double phase = t - Math.floor(t);
        if (entity instanceof EnderDragon dragon && "flap".equals(clip)) {
            float p = (float) ((t * 0.6) - Math.floor(t * 0.6));
            dragon.oFlapTime = p;
            dragon.flapTime = p;
        }
        if (entity instanceof Creeper creeper && "swell".equals(clip)) {
            creeper.setSwellDir(phase < 0.7 ? 1 : -1);
        }
        if (entity instanceof AbstractHorse horse) {
            if ("rear".equals(clip))
                horse.setStanding(phase < 0.65);
            else if ("eat".equals(clip))
                horse.setEating(phase < 0.7);
        }
        if (entity instanceof Panda panda) {
            if ("roll".equals(clip))
                panda.roll(true);
            else if ("sneeze".equals(clip))
                panda.sneeze(phase < 0.5);
            else if ("sit".equals(clip))
                panda.sit(true);
        }
        if (entity instanceof Fox fox) {
            if ("sleep".equals(clip) && !fox.isSleeping()) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                fox.saveWithoutId(tag);
                tag.putBoolean("Sleeping", true);
                fox.load(tag);
            } else if ("crouch".equals(clip)) {
                fox.setIsCrouching(true);
            }
        }
        if (entity instanceof Allay allay && "dance".equals(clip)) {
            allay.setDancing(true);
        }
        if (entity instanceof Piglin piglin && "dance".equals(clip)) {
            piglin.setDancing(true);
        }
        if (entity instanceof EnderMan enderman && "scream".equals(clip)) {
            enderman.setBeingStaredAt();
        }
        if (entity instanceof PolarBear bear && "stand".equals(clip)) {
            bear.setStanding(phase < 0.65);
        }
        if (entity instanceof Wolf wolf && "beg".equals(clip)) {
            wolf.setIsInterested(true);
        }
        if ("sneak".equals(clip)) {
            entity.setPose(Pose.CROUCHING);
        }
    }

    private static boolean matchesSpecial(HologramEffect.Type type, String clipName) {
        return switch (type) {
            case REST -> clipName.equals("sit_pose") || clipName.equals("rest");
            case ROAR -> clipName.equals("roar");
            case SNIFF -> clipName.equals("sniff") || clipName.equals("sniffing");
            case CROAK -> clipName.equals("croak");
            case ROLL -> clipName.equals("roll_up") || clipName.equals("roll");
            default -> false;
        };
    }

    private HologramEntityLife() {
    }
}
