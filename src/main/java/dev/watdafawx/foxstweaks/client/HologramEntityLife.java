package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.piglin.Piglin;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Animation-only state sampling. Never invokes tick/aiStep or changes the client world. */
public final class HologramEntityLife {

    private static final ClassValue<Map<String, Field>> FIELD_CACHE = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> map = new HashMap<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (map.containsKey(f.getName()))
                        continue;
                    try {
                        f.setAccessible(true);
                        map.put(f.getName(), f);
                    } catch (Throwable ignored) {
                    }
                }
            }
            return Map.copyOf(map);
        }
    };

    static void setFloat(Object target, String name, float value) {
        Field f = FIELD_CACHE.get(target.getClass()).get(name);
        if (f != null) {
            try {
                f.setFloat(target, value);
            } catch (Throwable ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void setSpellId(SpellcasterIllager caster, byte spellId) {
        Field f = FIELD_CACHE.get(SpellcasterIllager.class).get("DATA_SPELL_CASTING_ID");
        if (f != null) {
            try {
                var acc = (net.minecraft.network.syncher.EntityDataAccessor<Byte>) f.get(null);
                caster.getEntityData().set(acc, spellId);
            } catch (Throwable ignored) {
            }
        }
    }

    static void setInt(Object target, String name, int value) {
        Field f = FIELD_CACHE.get(target.getClass()).get(name);
        if (f != null) {
            try {
                f.setInt(target, value);
            } catch (Throwable ignored) {
            }
        }
    }

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
                      @Nullable String clip, float clipSpeed, Map<String, String> props,
                      double movementSeconds, double seconds) {
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

        // Keep tick-interpolated visual state fields active for static props (since hologram entities never tick).
        applyStaticStateVisuals(entity, props, seconds);

        // Procedural clip animations for mobs that animate via fields/setters rather than AnimationState.
        if (!activeClip.isEmpty()) {
            applyProceduralClip(entity, activeClip, seconds * clipSpeed);
        }

        // State-driven AnimationState mapping (Sniffer/Armadillo/Camel sitting).
        String stateClip = stateDrivenClip(entity, props);

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
                boolean matchedState = !stateClip.isEmpty() && stateClip.equals(clipName);
                boolean matchedSpecial = special != null && matchesSpecial(special.type(), clipName);
                boolean active = clipName.equals("idle")
                        || (clipName.equals("fly") && flying)
                        || (clipName.equals("rest") && resting)
                        || matchedSpecial
                        || matchedClip
                        || matchedState;

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
                    float ticks = (matchedClip || matchedSpecial || matchedState)
                            ? (float) ((age * 20.0) % 80.0) : (float) (age * 20.0);
                    state.updateTime(ticks, 1.0F);
                    state.lastTime = (long) Math.floor(seconds * 1000.0);
                }
            } catch (IllegalAccessException ignored) {
            }
        }
    }

    private static void applyStaticStateVisuals(LivingEntity entity, Map<String, String> props, double seconds) {
        if (props == null || props.isEmpty())
            return;
        if (entity instanceof Creeper && "true".equalsIgnoreCase(props.get("swelling"))) {
            setInt(entity, "swell", 24);
            setInt(entity, "oldSwell", 24);
        }
        if (entity instanceof Shulker && props.containsKey("peek")) {
            float amt = Math.max(0.0F, Math.min(1.0F, parseFloat(props.get("peek"), 0.0F) / 100.0F));
            setFloat(entity, "currentPeekAmount", amt);
            setFloat(entity, "currentPeekAmountO", amt);
        }
        if (entity instanceof PolarBear && "true".equalsIgnoreCase(props.get("standing"))) {
            setFloat(entity, "clientSideStandAnimation", 6.0F);
            setFloat(entity, "clientSideStandAnimationO", 6.0F);
        }
        if (entity instanceof Panda) {
            if ("true".equalsIgnoreCase(props.get("sitting"))) {
                setFloat(entity, "sitAmount", 1.0F);
                setFloat(entity, "sitAmountO", 1.0F);
            }
            if ("true".equalsIgnoreCase(props.get("on_back"))) {
                setFloat(entity, "onBackAmount", 1.0F);
                setFloat(entity, "onBackAmountO", 1.0F);
            }
            if ("true".equalsIgnoreCase(props.get("rolling"))) {
                float p = (float) ((seconds * 1.2) % 1.0);
                setFloat(entity, "rollAmount", p);
                setFloat(entity, "rollAmountO", p);
            }
        }
        if (entity instanceof Fox) {
            if ("true".equalsIgnoreCase(props.get("crouching"))) {
                setFloat(entity, "crouchAmount", 3.0F);
                setFloat(entity, "crouchAmountO", 3.0F);
            }
            if ("true".equalsIgnoreCase(props.get("pouncing"))) {
                setFloat(entity, "interestedAngle", 1.0F);
                setFloat(entity, "interestedAngleO", 1.0F);
            }
        }
        if (entity instanceof Cat && "true".equalsIgnoreCase(props.get("lying"))) {
            setFloat(entity, "lieDownAmount", 1.0F);
            setFloat(entity, "lieDownAmountO", 1.0F);
        }
        if (entity instanceof Allay && "true".equalsIgnoreCase(props.get("dancing"))) {
            float spin = (float) ((seconds * 20.0) % 55.0);
            setFloat(entity, "spinningAnimationTicks", spin);
            setFloat(entity, "spinningAnimationTicks0", spin);
        }
    }

    private static String stateDrivenClip(LivingEntity entity, @Nullable Map<String, String> props) {
        if (props == null || props.isEmpty())
            return "";
        if (entity instanceof Camel && "true".equalsIgnoreCase(props.get("sitting")))
            return "sit_pose";
        if (entity instanceof Sniffer && props.containsKey("state")) {
            return switch (props.get("state")) {
                case "feeling_happy" -> "feeling_happy";
                case "scenting" -> "scenting";
                case "sniffing" -> "sniffing";
                case "searching" -> "searching";
                case "digging" -> "digging";
                case "rising" -> "rising";
                default -> "";
            };
        }
        if (entity instanceof Armadillo && props.containsKey("state")) {
            return switch (props.get("state")) {
                case "rolling" -> "roll_up";
                case "scared" -> "peek";
                case "unrolling" -> "roll_out";
                default -> "";
            };
        }
        return "";
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
        float wave = (float) (0.5 - 0.5 * Math.cos(phase * Math.PI * 2.0));

        if (entity instanceof EnderDragon dragon && "flap".equals(clip)) {
            float p = (float) ((t * 0.6) - Math.floor(t * 0.6));
            dragon.oFlapTime = p;
            dragon.flapTime = p;
        }
        if (entity instanceof Creeper creeper && "swell".equals(clip)) {
            int s = Math.round(wave * 28.0F);
            creeper.setSwellDir(1);
            setInt(creeper, "swell", s);
            setInt(creeper, "oldSwell", s);
        }
        if (entity instanceof IronGolem golem) {
            if ("golem_attack".equals(clip)) {
                int atk = (int) Math.round((1.0 - phase) * 10.0);
                setInt(golem, "attackAnimationTick", atk);
            } else if ("offer_flower".equals(clip)) {
                golem.offerFlower(true);
            }
        }
        if (entity instanceof Ravager ravager) {
            if ("bite".equals(clip)) {
                setInt(ravager, "attackTick", (int) Math.round((1.0 - phase) * 10.0));
            } else if ("stun".equals(clip)) {
                setInt(ravager, "stunnedTick", (int) Math.round((1.0 - phase) * 40.0));
            } else if ("ravager_roar".equals(clip)) {
                setInt(ravager, "roarTick", (int) Math.round((1.0 - phase) * 20.0));
            }
        }
        if (entity instanceof SpellcasterIllager caster && "cast_spell".equals(clip)) {
            setSpellId(caster, (byte) 1);
        }
        if (entity instanceof Shulker shulker && "peek".equals(clip)) {
            setFloat(shulker, "currentPeekAmount", wave);
            setFloat(shulker, "currentPeekAmountO", wave);
        }
        if (entity instanceof AbstractHorse horse) {
            if ("rear".equals(clip)) {
                horse.setStanding(true);
                setFloat(horse, "standAnim", wave);
                setFloat(horse, "standAnimO", wave);
            } else if ("eat".equals(clip)) {
                horse.setEating(true);
                setFloat(horse, "eatAnim", wave);
                setFloat(horse, "eatAnimO", wave);
            }
        }
        if (entity instanceof Sheep sheep && "graze".equals(clip)) {
            int eatTick = 4 + (int) Math.round(phase * 36.0);
            setInt(sheep, "eatAnimationTick", eatTick);
        }
        if (entity instanceof Panda panda) {
            if ("roll".equals(clip)) {
                panda.roll(true);
                setFloat(panda, "rollAmount", (float) phase);
                setFloat(panda, "rollAmountO", (float) phase);
            } else if ("sneeze".equals(clip)) {
                panda.sneeze(true);
                setInt(panda, "sneezeCounter", 1 + (int) Math.round(phase * 19.0));
            } else if ("sit".equals(clip)) {
                panda.sit(true);
                setFloat(panda, "sitAmount", 1.0F);
                setFloat(panda, "sitAmountO", 1.0F);
            }
        }
        if (entity instanceof Fox fox) {
            if ("sleep".equals(clip) && !fox.isSleeping()) {
                net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
                fox.saveWithoutId(tag);
                tag.putBoolean("Sleeping", true);
                fox.load(tag);
            } else if ("crouch".equals(clip)) {
                fox.setIsCrouching(true);
                setFloat(fox, "crouchAmount", 3.0F * wave);
                setFloat(fox, "crouchAmountO", 3.0F * wave);
            }
        }
        if (entity instanceof Allay allay && "dance".equals(clip)) {
            allay.setDancing(true);
            float spin = (float) ((t * 20.0) % 55.0);
            setFloat(allay, "spinningAnimationTicks", spin);
            setFloat(allay, "spinningAnimationTicks0", spin);
        }
        if (entity instanceof Piglin piglin && "dance".equals(clip)) {
            piglin.setDancing(true);
        }
        if (entity instanceof EnderMan enderman && "scream".equals(clip)) {
            enderman.setBeingStaredAt();
        }
        if (entity instanceof PolarBear bear && "stand".equals(clip)) {
            bear.setStanding(true);
            setFloat(bear, "clientSideStandAnimation", 6.0F * wave);
            setFloat(bear, "clientSideStandAnimationO", 6.0F * wave);
        }
        if (entity instanceof Wolf wolf && "beg".equals(clip)) {
            wolf.setIsInterested(true);
            setFloat(wolf, "interestedAngle", wave);
            setFloat(wolf, "interestedAngleO", wave);
        }
        if ("sneak".equals(clip)) {
            entity.setPose(Pose.CROUCHING);
        }
    }

    private static float parseFloat(@Nullable String s, float def) {
        if (s == null || s.isEmpty())
            return def;
        try {
            return Float.parseFloat(s);
        } catch (NumberFormatException ignored) {
            return def;
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
