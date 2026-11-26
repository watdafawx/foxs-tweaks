package dev.watdafawx.foxstweaks.client;

import dev.watdafawx.foxstweaks.hologram.HologramEffect;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.monster.Guardian;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Animation-only state sampling. Never invokes tick/aiStep or changes the client world. */
public final class HologramEntityLife {
    private static final ClassValue<List<Field>> STATES = new ClassValue<>() {
        @Override protected List<Field> computeValue(Class<?> type) {
            List<Field> fields = new ArrayList<>();
            for (Field field : type.getFields()) {
                if (field.getType() == AnimationState.class)
                    fields.add(field);
            }
            return List.copyOf(fields);
        }
    };

    public static List<HologramEffect.Type> offered(String id) {
        List<HologramEffect.Type> out = new ArrayList<>(List.of(HologramEffect.Type.WALK,
                HologramEffect.Type.RUN, HologramEffect.Type.ATTACK, HologramEffect.Type.HURT,
                HologramEffect.Type.DEATH, HologramEffect.Type.SNEAK));
        String name = id.substring(id.indexOf(':') + 1);
        if (List.of("bee", "parrot", "bat", "chicken", "phantom", "allay", "ghast", "blaze", "vex").contains(name))
            out.add(HologramEffect.Type.FLY);
        if (List.of("cod", "salmon", "pufferfish", "tropical_fish", "squid", "glow_squid", "dolphin", "axolotl", "guardian", "elder_guardian", "frog").contains(name))
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

    static void apply(LivingEntity entity, HologramEffect movement, HologramEffect special,
                      double movementSeconds, double seconds) {
        boolean flying = movement != null && movement.type() == HologramEffect.Type.FLY;
        boolean swimming = movement != null && movement.type() == HologramEffect.Type.SWIM;
        boolean resting = special != null && special.type() == HologramEffect.Type.REST;
        entity.setOnGround(!flying && !swimming);
        entity.wasTouchingWater = swimming;
        entity.setSwimming(swimming);
        entity.setDeltaMovement(movement == null ? 0 : 0.1, 0, 0);
        // Keep previous positions consistent for renderers that derive movement without walkAnimation.
        entity.xo = entity.getX() - entity.getDeltaMovement().x;
        entity.yo = entity.getY();
        entity.zo = entity.getZ();
        if (entity instanceof Bat bat)
            bat.setResting(resting);
        if (entity instanceof TamableAnimal tame)
            tame.setInSittingPose(resting);
        double fraction = seconds * 20 - Math.floor(seconds * 20);
        float rate = movement == null ? 1 : movement.speed();
        if (movement != null && movement.type() == HologramEffect.Type.RUN)
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
        if (entity instanceof net.minecraft.world.entity.animal.axolotl.Axolotl axolotl)
            axolotl.getModelRotationValues().clear();

        for (Field field : STATES.get(entity.getClass())) {
            try {
                AnimationState state = (AnimationState) field.get(entity);
                if (state == null)
                    continue;

                String name = field.getName().toLowerCase(Locale.ROOT);
                boolean active = name.equals("idle") || name.equals("idleanimationstate")
                        || (name.equals("flyanimationstate") && flying)
                        || (name.equals("restanimationstate") && resting);
                if (special != null) {
                    active |= switch (special.type()) {
                        case REST -> name.equals("sitposeanimationstate");
                        case ROAR -> name.equals("roaranimationstate");
                        case SNIFF -> name.equals("sniffanimationstate") || name.equals("sniffinganimationstate");
                        case CROAK -> name.equals("croakanimationstate");
                        case ROLL -> name.equals("rollupanimationstate");
                        default -> false;
                    };
                }
                state.stop();
                if (active) {
                    double age = special == null ? seconds : seconds * special.speed() + special.phase();
                    state.start(0);
                    state.updateTime((float) (special == null ? age * 20 : age * 20 % 60), 1);
                    state.lastTime = (long) Math.floor(seconds * 1000);
                }
            } catch (IllegalAccessException ignored) {
                // Public vanilla states are supported; inaccessible third-party fields stay untouched.
            }
        }
    }

    private HologramEntityLife() {}
}
