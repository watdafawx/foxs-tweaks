package dev.watdafawx.foxstweaks.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.AnimationState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.Saddleable;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ambient.Bat;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Dolphin;
import net.minecraft.world.entity.animal.Fox;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.MushroomCow;
import net.minecraft.world.entity.animal.Panda;
import net.minecraft.world.entity.animal.Parrot;
import net.minecraft.world.entity.animal.PolarBear;
import net.minecraft.world.entity.animal.Pufferfish;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.SnowGolem;
import net.minecraft.world.entity.animal.Squid;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.entity.animal.Turtle;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.animal.allay.Allay;
import net.minecraft.world.entity.animal.armadillo.Armadillo;
import net.minecraft.world.entity.animal.axolotl.Axolotl;
import net.minecraft.world.entity.animal.camel.Camel;
import net.minecraft.world.entity.animal.frog.Frog;
import net.minecraft.world.entity.animal.goat.Goat;
import net.minecraft.world.entity.animal.horse.AbstractChestedHorse;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.entity.animal.horse.Horse;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.animal.sniffer.Sniffer;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Phantom;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Slime;
import net.minecraft.world.entity.monster.SpellcasterIllager;
import net.minecraft.world.entity.monster.Strider;
import net.minecraft.world.entity.monster.Vex;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Editable visual states and animation clips for {@link dev.watdafawx.foxstweaks.hologram.HologramLayer.Kind#ENTITY}
 * layers, stored as plain strings in {@code HologramLayer.props()}.
 *
 * <p>Properties are discovered from a prototype entity of each {@link EntityType}, so modded subclasses of
 * vanilla mobs (and any modded entity using vanilla {@link AnimationState} fields) are picked up automatically.
 */
public final class HologramEntityStates {

    public record Prop(String key, String label, List<String> values, String defaultValue) {
    }

    public static final String ANIM_KEY = "anim";
    public static final String ANIM_SPEED_KEY = "anim_speed";

    private static final List<String> BOOL_FALSE = List.of("false", "true");
    private static final List<String> BOOL_TRUE = List.of("true", "false");
    private static final List<String> DYE_COLORS = buildDyeColors(false);
    private static final List<String> DYE_COLORS_NONE = buildDyeColors(true);
    private static final List<String> SHEEP_COLORS = buildSheepColors();
    private static final List<String> POSES = List.of(
            "standing", "crouching", "swimming", "sleeping", "fall_flying", "sitting", "dying");
    private static final List<String> HORSE_COLORS = List.of(
            "white", "creamy", "chestnut", "brown", "black", "gray", "dark_brown");
    private static final List<String> HORSE_MARKINGS = List.of(
            "none", "white", "white_field", "white_dots", "black_dots");
    private static final List<String> LLAMA_VARIANTS = List.of("creamy", "white", "brown", "gray");
    private static final List<String> PARROT_VARIANTS = List.of("red_blue", "blue", "green", "yellow_blue", "gray");
    private static final List<String> RABBIT_VARIANTS = List.of(
            "brown", "white", "black", "white_splotched", "gold", "salt", "evil", "toast");
    private static final List<String> PANDA_GENES = List.of(
            "normal", "lazy", "worried", "playful", "brown", "weak", "aggressive");
    private static final List<String> AXOLOTL_VARIANTS = List.of("lucy", "wild", "gold", "cyan", "blue");
    private static final List<String> FOX_VARIANTS = List.of("red", "snow");
    private static final List<String> MOOSHROOM_VARIANTS = List.of("red", "brown");
    private static final List<String> IRON_GOLEM_CRACKS = List.of("none", "low", "medium", "high");
    private static final List<String> SLIME_SIZES = List.of("1", "2", "3", "4", "6", "8");
    private static final List<String> PHANTOM_SIZES = List.of("0", "1", "2", "4", "8");
    private static final List<String> PUFFER_STATES = List.of("0", "1", "2");
    private static final List<String> SHULKER_PEEKS = List.of("0", "25", "50", "75", "100");
    private static final List<String> SPELLS = List.of(
            "none", "summon_vex", "fangs", "wololo", "disappear", "blindness");
    private static final List<String> SNIFFER_STATES = List.of(
            "idling", "feeling_happy", "scenting", "sniffing", "searching", "digging", "rising");
    private static final List<String> ARMADILLO_STATES = List.of("idle", "rolling", "scared", "unrolling");
    private static final List<String> WARDEN_ANGER = List.of("0", "40", "80", "150");
    private static final List<String> VILLAGER_LEVELS = List.of("1", "2", "3", "4", "5");

    private static final Map<String, List<Prop>> PROP_CACHE = new HashMap<>();
    private static final Map<String, List<String>> ANIM_CACHE = new HashMap<>();
    private static final ClassValue<Map<String, Field>> ANIM_STATE_FIELDS = new ClassValue<>() {
        @Override
        protected Map<String, Field> computeValue(Class<?> type) {
            Map<String, Field> out = new LinkedHashMap<>();
            for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.getType() != AnimationState.class)
                        continue;
                    try {
                        f.setAccessible(true);
                    } catch (Throwable ignored) {
                        if (!Modifier.isPublic(f.getModifiers()))
                            continue;
                    }
                    String clip = normalizeClipName(f.getName());
                    out.putIfAbsent(clip, f);
                }
            }
            return Map.copyOf(out);
        }
    };

    private HologramEntityStates() {
    }

    public static void clear() {
        PROP_CACHE.clear();
        ANIM_CACHE.clear();
    }

    /** All editable state properties for this entity type (empty if unknown or level not loaded). */
    public static List<Prop> properties(String entityId) {
        if (entityId == null || entityId.isEmpty())
            return List.of();
        List<Prop> cached = PROP_CACHE.get(entityId);
        if (cached != null)
            return cached;
        Entity proto = createPrototype(entityId);
        if (proto == null)
            return List.of();
        List<Prop> built = List.copyOf(discoverProps(proto));
        PROP_CACHE.put(entityId, built);
        return built;
    }

    /** All animation clip names supported by this entity type (not including empty/idle). */
    public static List<String> animations(String entityId) {
        if (entityId == null || entityId.isEmpty())
            return List.of();
        List<String> cached = ANIM_CACHE.get(entityId);
        if (cached != null)
            return cached;
        Entity proto = createPrototype(entityId);
        if (proto == null)
            return List.of();
        List<String> built = List.copyOf(discoverAnimations(proto));
        ANIM_CACHE.put(entityId, built);
        return built;
    }

    /** Map of clip name -> AnimationState field for an entity class (cached). */
    static Map<String, Field> animationStateFields(Class<?> entityClass) {
        return ANIM_STATE_FIELDS.get(entityClass);
    }

    public static String valueOf(Map<String, String> props, Prop prop) {
        String v = props.get(prop.key());
        return (v != null && prop.values().contains(v)) ? v : prop.defaultValue();
    }

    public static String cycle(Prop prop, String current, int direction) {
        List<String> values = prop.values();
        if (values.isEmpty())
            return current;
        int idx = values.indexOf(current);
        if (idx < 0)
            idx = 0;
        int next = Math.floorMod(idx + direction, values.size());
        return values.get(next);
    }

    public static float animSpeed(Map<String, String> props) {
        String raw = props.get(ANIM_SPEED_KEY);
        if (raw == null || raw.isEmpty())
            return 1.0F;
        try {
            float v = Float.parseFloat(raw);
            return Float.isFinite(v) ? Math.max(0.1F, Math.min(4.0F, v)) : 1.0F;
        } catch (NumberFormatException ignored) {
            return 1.0F;
        }
    }

    @Nullable
    public static Pose configuredPose(Map<String, String> props) {
        String raw = props.get("pose");
        if (raw == null || raw.isEmpty() || "standing".equals(raw))
            return null;
        return parsePose(raw);
    }

    /** Applies all state props to a freshly created hologram entity instance. */
    public static void applyProps(Entity entity, Map<String, String> props) {
        if (props == null || props.isEmpty())
            return;

        // 1. NBT-backed properties (batched into one save/load pass so private SynchedEntityData stays clean).
        CompoundTag nbtPatch = new CompoundTag();
        collectNbtPatch(entity, props, nbtPatch);
        if (!nbtPatch.isEmpty()) {
            try {
                CompoundTag full = new CompoundTag();
                entity.saveWithoutId(full);
                for (String k : nbtPatch.getAllKeys()) {
                    full.put(k, nbtPatch.get(k).copy());
                }
                entity.load(full);
            } catch (Throwable ignored) {
                // Keep entity renderable if a modded subclass rejects a vanilla tag.
            }
        }

        // 2. Direct public setters (applied after NBT load so they always win).
        try {
            if (isTrue(props, "invisible"))
                entity.setInvisible(true);
            if (isTrue(props, "on_fire"))
                entity.setSharedFlagOnFire(true);

            String poseRaw = props.get("pose");
            if (poseRaw != null && !poseRaw.isEmpty()) {
                Pose p = parsePose(poseRaw);
                if (p != null)
                    entity.setPose(p);
            }

            if (props.containsKey("baby")) {
                boolean baby = isTrue(props, "baby");
                if (entity instanceof AgeableMob ageable)
                    ageable.setBaby(baby);
                else if (entity instanceof Zombie zombie)
                    zombie.setBaby(baby);
                else if (entity instanceof Piglin piglin)
                    piglin.setBaby(baby);
                else if (entity instanceof Zoglin zoglin)
                    zoglin.setBaby(baby);
            }

            if (entity instanceof TamableAnimal tame) {
                if (props.containsKey("tamed"))
                    tame.setTame(isTrue(props, "tamed"), false);
                if (props.containsKey("sitting")) {
                    boolean sit = isTrue(props, "sitting");
                    tame.setOrderedToSit(sit);
                    tame.setInSittingPose(sit);
                }
            }

            if (entity instanceof NeutralMob neutral && props.containsKey("angry")) {
                neutral.setRemainingPersistentAngerTime(isTrue(props, "angry") ? 400 : 0);
            }

            if (entity instanceof Mob mob && props.containsKey("aggressive")) {
                mob.setAggressive(isTrue(props, "aggressive"));
            }

            if (entity instanceof Sheep sheep) {
                if (props.containsKey("color")) {
                    String col = props.get("color");
                    if ("rainbow".equals(col)) {
                        sheep.setCustomName(Component.literal("jeb_"));
                    } else {
                        DyeColor dye = parseDye(col);
                        if (dye != null)
                            sheep.setColor(dye);
                    }
                }
                if (props.containsKey("sheared"))
                    sheep.setSheared(isTrue(props, "sheared"));
            }

            if (entity instanceof Creeper creeper && isTrue(props, "swelling")) {
                creeper.setSwellDir(1);
            }

            if (entity instanceof Cat cat && props.containsKey("lying")) {
                cat.setLying(isTrue(props, "lying"));
            }

            if (entity instanceof Fox fox) {
                if (props.containsKey("crouching"))
                    fox.setIsCrouching(isTrue(props, "crouching"));
                if (props.containsKey("pouncing"))
                    fox.setIsInterested(isTrue(props, "pouncing"));
            }

            if (entity instanceof Axolotl axolotl && props.containsKey("playing_dead")) {
                axolotl.setPlayingDead(isTrue(props, "playing_dead"));
            }

            if (entity instanceof Rabbit rabbit && "toast".equals(props.get("variant"))) {
                rabbit.setCustomName(Component.literal("Toast"));
            }

            if (entity instanceof Panda panda) {
                if (props.containsKey("rolling"))
                    panda.roll(isTrue(props, "rolling"));
                if (props.containsKey("sitting"))
                    panda.sit(isTrue(props, "sitting"));
                if (props.containsKey("on_back"))
                    panda.setOnBack(isTrue(props, "on_back"));
            }

            if (entity instanceof SnowGolem golem && props.containsKey("pumpkin")) {
                golem.setPumpkin(isTrue(props, "pumpkin"));
            }

            if (entity instanceof IronGolem golem) {
                if (props.containsKey("cracks")) {
                    float hp = switch (props.get("cracks")) {
                        case "low" -> golem.getMaxHealth() * 0.7F;
                        case "medium" -> golem.getMaxHealth() * 0.45F;
                        case "high" -> golem.getMaxHealth() * 0.2F;
                        default -> golem.getMaxHealth();
                    };
                    golem.setHealth(hp);
                }
                if (isTrue(props, "flower"))
                    golem.offerFlower(true);
            }

            if (entity instanceof Pufferfish puffer && props.containsKey("puff")) {
                puffer.setPuffState(parseInt(props.get("puff"), 0));
            }

            if (entity instanceof EnderMan enderman && props.containsKey("screaming")) {
                if (isTrue(props, "screaming"))
                    enderman.setBeingStaredAt();
            }

            if (entity instanceof Ghast ghast && props.containsKey("charging")) {
                ghast.setCharging(isTrue(props, "charging"));
            }

            if (entity instanceof Vex vex && props.containsKey("charging")) {
                vex.setIsCharging(isTrue(props, "charging"));
            }

            if (entity instanceof Pillager pillager && props.containsKey("charging")) {
                pillager.setChargingCrossbow(isTrue(props, "charging"));
            }

            if (entity instanceof Strider strider && props.containsKey("suffocating")) {
                strider.setSuffocating(isTrue(props, "suffocating"));
            }

            if (entity instanceof Bat bat && props.containsKey("resting")) {
                bat.setResting(isTrue(props, "resting"));
            }

            if (entity instanceof PolarBear bear && props.containsKey("standing")) {
                bear.setStanding(isTrue(props, "standing"));
            }

            if (entity instanceof Camel camel) {
                if (isTrue(props, "sitting"))
                    camel.standUpInstantly(); // reset first
                if (isTrue(props, "sitting"))
                    camel.sitDown();
                if (props.containsKey("dashing"))
                    camel.setDashing(isTrue(props, "dashing"));
            }

            if (entity instanceof Armadillo armadillo && props.containsKey("state")) {
                Armadillo.ArmadilloState st = switch (props.get("state")) {
                    case "rolling" -> Armadillo.ArmadilloState.ROLLING;
                    case "scared" -> Armadillo.ArmadilloState.SCARED;
                    case "unrolling" -> Armadillo.ArmadilloState.UNROLLING;
                    default -> Armadillo.ArmadilloState.IDLE;
                };
                armadillo.switchToState(st);
            }

            if (entity instanceof Sniffer sniffer && props.containsKey("state")) {
                Sniffer.State st = switch (props.get("state")) {
                    case "feeling_happy" -> Sniffer.State.FEELING_HAPPY;
                    case "scenting" -> Sniffer.State.SCENTING;
                    case "sniffing" -> Sniffer.State.SNIFFING;
                    case "searching" -> Sniffer.State.SEARCHING;
                    case "digging" -> Sniffer.State.DIGGING;
                    case "rising" -> Sniffer.State.RISING;
                    default -> Sniffer.State.IDLING;
                };
                sniffer.transitionTo(st);
            }

            if (entity instanceof Allay allay && props.containsKey("dancing")) {
                allay.setDancing(isTrue(props, "dancing"));
            }

            if (entity instanceof Boat boat && props.containsKey("variant")) {
                boat.setVariant(Boat.Type.byName(props.get("variant")));
            }
        } catch (Throwable ignored) {
        }

        // 3. MobEffect overlay (used by Effects picker and manual props).
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

    private static void collectNbtPatch(Entity entity, Map<String, String> props, CompoundTag nbt) {
        if (entity instanceof Creeper && props.containsKey("powered")) {
            nbt.putBoolean("powered", isTrue(props, "powered"));
        }
        if (entity instanceof Saddleable && props.containsKey("saddled")) {
            boolean sad = isTrue(props, "saddled");
            if (entity instanceof AbstractHorse) {
                if (sad) {
                    CompoundTag item = new CompoundTag();
                    item.putString("id", "minecraft:saddle");
                    item.putInt("count", 1);
                    nbt.put("SaddleItem", item);
                } else {
                    nbt.put("SaddleItem", new CompoundTag());
                }
            } else {
                nbt.putBoolean("Saddle", sad);
            }
        }
        if (entity instanceof AbstractChestedHorse && props.containsKey("chest")) {
            nbt.putBoolean("ChestedHorse", isTrue(props, "chest"));
        }
        if (entity instanceof Wolf) {
            if (props.containsKey("variant"))
                nbt.putString("variant", ensureNamespace(props.get("variant")));
            if (props.containsKey("collar")) {
                DyeColor dye = parseDye(props.get("collar"));
                if (dye != null)
                    nbt.putByte("CollarColor", (byte) dye.getId());
            }
        }
        if (entity instanceof Cat) {
            if (props.containsKey("variant"))
                nbt.putString("variant", ensureNamespace(props.get("variant")));
            if (props.containsKey("collar")) {
                DyeColor dye = parseDye(props.get("collar"));
                if (dye != null)
                    nbt.putByte("CollarColor", (byte) dye.getId());
            }
        }
        if (entity instanceof Frog && props.containsKey("variant")) {
            nbt.putString("variant", ensureNamespace(props.get("variant")));
        }
        if (entity instanceof Axolotl && props.containsKey("variant")) {
            int idx = AXOLOTL_VARIANTS.indexOf(props.get("variant"));
            if (idx >= 0)
                nbt.putInt("Variant", idx);
        }
        if (entity instanceof Fox) {
            if (props.containsKey("variant"))
                nbt.putString("Type", props.get("variant"));
            if (props.containsKey("sleeping"))
                nbt.putBoolean("Sleeping", isTrue(props, "sleeping"));
        }
        if (entity instanceof Horse && (props.containsKey("color") || props.containsKey("markings"))) {
            int col = Math.max(0, HORSE_COLORS.indexOf(props.getOrDefault("color", "white")));
            int mark = Math.max(0, HORSE_MARKINGS.indexOf(props.getOrDefault("markings", "none")));
            nbt.putInt("Variant", (col & 0xFF) | ((mark & 0xFF) << 8));
        }
        if (entity instanceof Llama) {
            if (props.containsKey("variant")) {
                int idx = LLAMA_VARIANTS.indexOf(props.get("variant"));
                if (idx >= 0)
                    nbt.putInt("Variant", idx);
            }
            if (props.containsKey("carpet")) {
                String c = props.get("carpet");
                if ("none".equals(c)) {
                    nbt.put("body_armor_item", new CompoundTag());
                } else {
                    CompoundTag item = new CompoundTag();
                    item.putString("id", "minecraft:" + c + "_carpet");
                    item.putInt("count", 1);
                    nbt.put("body_armor_item", item);
                }
            }
        }
        if (entity instanceof Parrot && props.containsKey("variant")) {
            int idx = PARROT_VARIANTS.indexOf(props.get("variant"));
            if (idx >= 0)
                nbt.putInt("Variant", idx);
        }
        if (entity instanceof Rabbit && props.containsKey("variant")) {
            String v = props.get("variant");
            int type = switch (v) {
                case "white" -> 1;
                case "black" -> 2;
                case "white_splotched" -> 3;
                case "gold" -> 4;
                case "salt" -> 5;
                case "evil" -> 99;
                default -> 0;
            };
            nbt.putInt("RabbitType", type);
        }
        if (entity instanceof Panda && props.containsKey("gene")) {
            String g = props.get("gene");
            nbt.putString("MainGene", g);
            nbt.putString("HiddenGene", g);
        }
        if (entity instanceof MushroomCow && props.containsKey("variant")) {
            nbt.putString("Type", props.get("variant"));
        }
        if ((entity instanceof Villager || entity instanceof ZombieVillager)
                && (props.containsKey("profession") || props.containsKey("biome") || props.containsKey("level"))) {
            CompoundTag vd = new CompoundTag();
            vd.putString("profession", ensureNamespace(props.getOrDefault("profession", "none")));
            vd.putString("type", ensureNamespace(props.getOrDefault("biome", "plains")));
            vd.putInt("level", parseInt(props.get("level"), 1));
            nbt.put("VillagerData", vd);
        }
        if (entity instanceof Bee) {
            if (props.containsKey("nectar"))
                nbt.putBoolean("HasNectar", isTrue(props, "nectar"));
            if (props.containsKey("stung"))
                nbt.putBoolean("HasStung", isTrue(props, "stung"));
        }
        if (entity instanceof Goat) {
            if (props.containsKey("left_horn"))
                nbt.putBoolean("HasLeftHorn", isTrue(props, "left_horn"));
            if (props.containsKey("right_horn"))
                nbt.putBoolean("HasRightHorn", isTrue(props, "right_horn"));
            if (props.containsKey("screaming"))
                nbt.putBoolean("IsScreamingGoat", isTrue(props, "screaming"));
        }
        if (entity instanceof Slime && props.containsKey("size")) {
            // Slime NBT Size is 0-based (0 = size 1).
            int size = Math.max(1, parseInt(props.get("size"), 2));
            nbt.putInt("Size", size - 1);
        }
        if (entity instanceof Phantom && props.containsKey("size")) {
            nbt.putInt("Size", Math.max(0, parseInt(props.get("size"), 0)));
        }
        if (entity instanceof Shulker) {
            if (props.containsKey("color")) {
                String c = props.get("color");
                DyeColor dye = "none".equals(c) ? null : parseDye(c);
                nbt.putByte("Color", dye == null ? (byte) 16 : (byte) dye.getId());
            }
            if (props.containsKey("peek")) {
                nbt.putByte("Peek", (byte) parseInt(props.get("peek"), 0));
            }
        }
        if (entity instanceof SpellcasterIllager && props.containsKey("spell")) {
            int spellId = switch (props.get("spell")) {
                case "summon_vex" -> 1;
                case "fangs" -> 2;
                case "wololo" -> 3;
                case "disappear" -> 4;
                case "blindness" -> 5;
                default -> 0;
            };
            nbt.putInt("SpellTicks", spellId > 0 ? 40 : 0);
        }
        if (entity instanceof TropicalFish
                && (props.containsKey("pattern") || props.containsKey("base_color") || props.containsKey("pattern_color"))) {
            TropicalFish.Pattern pat = parseFishPattern(props.getOrDefault("pattern", "kob"));
            DyeColor base = parseDyeOrDefault(props.get("base_color"), DyeColor.WHITE);
            DyeColor patCol = parseDyeOrDefault(props.get("pattern_color"), DyeColor.ORANGE);
            int packed = pat.getPackedId() | (base.getId() << 16) | (patCol.getId() << 24);
            nbt.putInt("Variant", packed);
        }
        if (entity instanceof ArmorStand) {
            if (props.containsKey("small"))
                nbt.putBoolean("Small", isTrue(props, "small"));
            if (props.containsKey("arms"))
                nbt.putBoolean("ShowArms", isTrue(props, "arms"));
            if (props.containsKey("no_base"))
                nbt.putBoolean("NoBasePlate", isTrue(props, "no_base"));
        }
    }

    private static List<Prop> discoverProps(Entity entity) {
        List<Prop> out = new ArrayList<>();

        // Mob-specific visual variants first (most useful at the top).
        if (entity instanceof Sheep) {
            out.add(new Prop("color", "Color", SHEEP_COLORS, "white"));
            out.add(new Prop("sheared", "Sheared", BOOL_FALSE, "false"));
        }
        if (entity instanceof Creeper) {
            out.add(new Prop("powered", "Charged", BOOL_FALSE, "false"));
            out.add(new Prop("swelling", "Swelling", BOOL_FALSE, "false"));
        }
        if (entity instanceof Wolf) {
            List<String> variants = registryKeys(Registries.WOLF_VARIANT, List.of(
                    "pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped"));
            out.add(new Prop("variant", "Variant", variants, variants.get(0)));
            out.add(new Prop("collar", "Collar", DYE_COLORS, "red"));
        }
        if (entity instanceof Cat) {
            List<String> variants = registryKeys(Registries.CAT_VARIANT, List.of(
                    "tabby", "black", "red", "siamese", "british_shorthair", "calico",
                    "persian", "ragdoll", "white", "jellie", "all_black"));
            out.add(new Prop("variant", "Variant", variants, variants.get(0)));
            out.add(new Prop("collar", "Collar", DYE_COLORS, "red"));
            out.add(new Prop("lying", "Lying", BOOL_FALSE, "false"));
        }
        if (entity instanceof Frog) {
            List<String> variants = registryKeys(Registries.FROG_VARIANT, List.of("temperate", "warm", "cold"));
            out.add(new Prop("variant", "Variant", variants, variants.get(0)));
        }
        if (entity instanceof Axolotl) {
            out.add(new Prop("variant", "Variant", AXOLOTL_VARIANTS, "lucy"));
            out.add(new Prop("playing_dead", "Play Dead", BOOL_FALSE, "false"));
        }
        if (entity instanceof Fox) {
            out.add(new Prop("variant", "Variant", FOX_VARIANTS, "red"));
            out.add(new Prop("sleeping", "Sleeping", BOOL_FALSE, "false"));
            out.add(new Prop("crouching", "Crouching", BOOL_FALSE, "false"));
            out.add(new Prop("pouncing", "Interested", BOOL_FALSE, "false"));
        }
        if (entity instanceof Horse) {
            out.add(new Prop("color", "Color", HORSE_COLORS, "white"));
            out.add(new Prop("markings", "Markings", HORSE_MARKINGS, "none"));
        }
        if (entity instanceof Llama) {
            out.add(new Prop("variant", "Variant", LLAMA_VARIANTS, "creamy"));
            out.add(new Prop("carpet", "Carpet", DYE_COLORS_NONE, "none"));
        }
        if (entity instanceof Parrot) {
            out.add(new Prop("variant", "Variant", PARROT_VARIANTS, "red_blue"));
        }
        if (entity instanceof Rabbit) {
            out.add(new Prop("variant", "Variant", RABBIT_VARIANTS, "brown"));
        }
        if (entity instanceof Panda) {
            out.add(new Prop("gene", "Personality", PANDA_GENES, "normal"));
            out.add(new Prop("sitting", "Sitting", BOOL_FALSE, "false"));
            out.add(new Prop("rolling", "Rolling", BOOL_FALSE, "false"));
            out.add(new Prop("on_back", "On Back", BOOL_FALSE, "false"));
        }
        if (entity instanceof MushroomCow) {
            out.add(new Prop("variant", "Variant", MOOSHROOM_VARIANTS, "red"));
        }
        if (entity instanceof Villager || entity instanceof ZombieVillager) {
            List<String> profs = BuiltInRegistries.VILLAGER_PROFESSION.keySet().stream()
                    .map(HologramEntityStates::shortKey).sorted().toList();
            List<String> biomes = BuiltInRegistries.VILLAGER_TYPE.keySet().stream()
                    .map(HologramEntityStates::shortKey).sorted().toList();
            if (!profs.isEmpty())
                out.add(new Prop("profession", "Job", profs, profs.contains("none") ? "none" : profs.get(0)));
            if (!biomes.isEmpty())
                out.add(new Prop("biome", "Biome", biomes, biomes.contains("plains") ? "plains" : biomes.get(0)));
            out.add(new Prop("level", "Level", VILLAGER_LEVELS, "1"));
        }
        if (entity instanceof Bee) {
            out.add(new Prop("nectar", "Pollen", BOOL_FALSE, "false"));
            out.add(new Prop("stung", "Stung", BOOL_FALSE, "false"));
        }
        if (entity instanceof Goat) {
            out.add(new Prop("left_horn", "Left Horn", BOOL_TRUE, "true"));
            out.add(new Prop("right_horn", "Right Horn", BOOL_TRUE, "true"));
            out.add(new Prop("screaming", "Screaming", BOOL_FALSE, "false"));
        }
        if (entity instanceof SnowGolem) {
            out.add(new Prop("pumpkin", "Pumpkin", BOOL_TRUE, "true"));
        }
        if (entity instanceof IronGolem) {
            out.add(new Prop("cracks", "Cracks", IRON_GOLEM_CRACKS, "none"));
            out.add(new Prop("flower", "Flower", BOOL_FALSE, "false"));
        }
        if (entity instanceof Slime) {
            out.add(new Prop("size", "Size", SLIME_SIZES, "2"));
        }
        if (entity instanceof Phantom) {
            out.add(new Prop("size", "Size", PHANTOM_SIZES, "0"));
        }
        if (entity instanceof Pufferfish) {
            out.add(new Prop("puff", "Puff", PUFFER_STATES, "0"));
        }
        if (entity instanceof Shulker) {
            out.add(new Prop("color", "Color", DYE_COLORS_NONE, "none"));
            out.add(new Prop("peek", "Peek", SHULKER_PEEKS, "0"));
        }
        if (entity instanceof EnderMan) {
            out.add(new Prop("screaming", "Screaming", BOOL_FALSE, "false"));
        }
        if (entity instanceof Ghast || entity instanceof Vex || entity instanceof Pillager) {
            out.add(new Prop("charging", "Charging", BOOL_FALSE, "false"));
        }
        if (entity instanceof SpellcasterIllager) {
            out.add(new Prop("spell", "Spell", SPELLS, "none"));
        }
        if (entity instanceof Strider) {
            out.add(new Prop("suffocating", "Cold", BOOL_FALSE, "false"));
        }
        if (entity instanceof Sniffer) {
            out.add(new Prop("state", "State", SNIFFER_STATES, "idling"));
        }
        if (entity instanceof Armadillo) {
            out.add(new Prop("state", "State", ARMADILLO_STATES, "idle"));
        }
        if (entity instanceof Bat) {
            out.add(new Prop("resting", "Hanging", BOOL_FALSE, "false"));
        }
        if (entity instanceof PolarBear) {
            out.add(new Prop("standing", "Standing", BOOL_FALSE, "false"));
        }
        if (entity instanceof Camel) {
            out.add(new Prop("sitting", "Sitting", BOOL_FALSE, "false"));
            out.add(new Prop("dashing", "Dashing", BOOL_FALSE, "false"));
        }
        if (entity instanceof Allay) {
            out.add(new Prop("dancing", "Dancing", BOOL_FALSE, "false"));
        }
        if (entity instanceof TropicalFish) {
            List<String> patterns = new ArrayList<>();
            for (TropicalFish.Pattern p : TropicalFish.Pattern.values())
                patterns.add(p.getSerializedName());
            out.add(new Prop("pattern", "Pattern", List.copyOf(patterns), "kob"));
            out.add(new Prop("base_color", "Base", DYE_COLORS, "white"));
            out.add(new Prop("pattern_color", "Stripe", DYE_COLORS, "orange"));
        }
        if (entity instanceof ArmorStand) {
            out.add(new Prop("small", "Small", BOOL_FALSE, "false"));
            out.add(new Prop("arms", "Arms", BOOL_FALSE, "false"));
            out.add(new Prop("no_base", "No Base", BOOL_FALSE, "false"));
        }
        if (entity instanceof Boat) {
            List<String> types = new ArrayList<>();
            for (Boat.Type t : Boat.Type.values())
                types.add(t.getName());
            out.add(new Prop("variant", "Wood", List.copyOf(types), "oak"));
        }

        // Category properties
        if (entity instanceof AgeableMob || entity instanceof Zombie || entity instanceof Piglin || entity instanceof Zoglin) {
            out.add(new Prop("baby", "Baby", BOOL_FALSE, "false"));
        }
        if (entity instanceof TamableAnimal) {
            out.add(new Prop("tamed", "Tamed", BOOL_FALSE, "false"));
            out.add(new Prop("sitting", "Sitting", BOOL_FALSE, "false"));
        }
        if (entity instanceof NeutralMob) {
            out.add(new Prop("angry", "Angry", BOOL_FALSE, "false"));
        }
        if (entity instanceof Saddleable) {
            out.add(new Prop("saddled", "Saddled", BOOL_FALSE, "false"));
        }
        if (entity instanceof AbstractChestedHorse) {
            out.add(new Prop("chest", "Chest", BOOL_FALSE, "false"));
        }
        if (entity instanceof Zombie || entity instanceof Vindicator || entity instanceof AbstractPiglin) {
            out.add(new Prop("aggressive", "Arms Up", BOOL_FALSE, "false"));
        }
        if (entity instanceof LivingEntity) {
            out.add(new Prop("pose", "Pose", POSES, "standing"));
        }
        out.add(new Prop("on_fire", "On Fire", BOOL_FALSE, "false"));
        out.add(new Prop("invisible", "Invisible", BOOL_FALSE, "false"));
        return out;
    }

    private static List<String> discoverAnimations(Entity entity) {
        Set<String> out = new LinkedHashSet<>();
        // 1. Dynamically discovered AnimationState fields (vanilla + modded).
        for (String clip : animationStateFields(entity.getClass()).keySet()) {
            if (!"idle".equals(clip))
                out.add(clip);
        }
        // 2. Mob-specific procedural animations.
        if (entity instanceof EnderDragon)
            out.add("flap");
        if (entity instanceof Creeper)
            out.add("swell");
        if (entity instanceof IronGolem) {
            out.add("golem_attack");
            out.add("offer_flower");
        }
        if (entity instanceof Ravager) {
            out.add("bite");
            out.add("stun");
            out.add("ravager_roar");
        }
        if (entity instanceof SpellcasterIllager)
            out.add("cast_spell");
        if (entity instanceof Shulker)
            out.add("peek");
        if (entity instanceof AbstractHorse) {
            out.add("rear");
            out.add("eat");
        }
        if (entity instanceof Sheep)
            out.add("graze");
        if (entity instanceof Panda) {
            out.add("roll");
            out.add("sneeze");
            out.add("sit");
        }
        if (entity instanceof Fox) {
            out.add("sleep");
            out.add("crouch");
        }
        if (entity instanceof Allay || entity instanceof Piglin)
            out.add("dance");
        if (entity instanceof EnderMan)
            out.add("scream");
        if (entity instanceof PolarBear)
            out.add("stand");
        if (entity instanceof Wolf)
            out.add("beg");
        if (entity instanceof Bee || entity instanceof Parrot || entity instanceof Bat
                || entity instanceof Chicken || entity instanceof Phantom || entity instanceof Allay
                || entity instanceof Ghast || entity instanceof Blaze || entity instanceof Vex)
            out.add("fly");
        if (entity instanceof AbstractFish || entity instanceof Squid || entity instanceof Dolphin
                || entity instanceof Axolotl || entity instanceof Guardian || entity instanceof Frog
                || entity instanceof Turtle)
            out.add("swim");
        if (entity instanceof LivingEntity) {
            out.add("walk");
            out.add("run");
            out.add("swing");
            out.add("hurt");
            out.add("die");
            out.add("sneak");
        }
        return new ArrayList<>(out);
    }

    @Nullable
    private static Entity createPrototype(String entityId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return null;
        ResourceLocation key = ResourceLocation.tryParse(entityId);
        if (key == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(key))
            return null;
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(key);
        if (type == null)
            return null;
        try {
            return type.create(mc.level);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static <T> List<String> registryKeys(net.minecraft.resources.ResourceKey<net.minecraft.core.Registry<T>> key,
                                                 List<String> fallback) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null)
            return fallback;
        try {
            var reg = mc.level.registryAccess().registry(key);
            if (reg.isPresent() && !reg.get().keySet().isEmpty()) {
                return reg.get().keySet().stream().map(HologramEntityStates::shortKey).sorted().toList();
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private static String shortKey(ResourceLocation loc) {
        return "minecraft".equals(loc.getNamespace()) ? loc.getPath() : loc.toString();
    }

    private static String ensureNamespace(String raw) {
        return raw.contains(":") ? raw : "minecraft:" + raw;
    }

    static String normalizeClipName(String fieldName) {
        String s = fieldName;
        if (s.endsWith("AnimationState"))
            s = s.substring(0, s.length() - "AnimationState".length());
        else if (s.endsWith("State"))
            s = s.substring(0, s.length() - "State".length());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && sb.charAt(sb.length() - 1) != '_')
                sb.append('_');
            sb.append(Character.toLowerCase(c));
        }
        String out = sb.toString();
        return out.isEmpty() ? fieldName.toLowerCase(Locale.ROOT) : out;
    }

    private static boolean isTrue(Map<String, String> props, String key) {
        return "true".equalsIgnoreCase(props.get(key));
    }

    private static int parseInt(@Nullable String s, int def) {
        if (s == null || s.isEmpty())
            return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    @Nullable
    private static DyeColor parseDye(@Nullable String name) {
        if (name == null || name.isEmpty())
            return null;
        return DyeColor.byName(name, null);
    }

    private static DyeColor parseDyeOrDefault(@Nullable String name, DyeColor def) {
        DyeColor d = parseDye(name);
        return d != null ? d : def;
    }

    private static TropicalFish.Pattern parseFishPattern(String name) {
        for (TropicalFish.Pattern p : TropicalFish.Pattern.values()) {
            if (p.getSerializedName().equals(name))
                return p;
        }
        return TropicalFish.Pattern.KOB;
    }

    @Nullable
    private static Pose parsePose(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "standing" -> Pose.STANDING;
            case "crouching" -> Pose.CROUCHING;
            case "swimming" -> Pose.SWIMMING;
            case "sleeping" -> Pose.SLEEPING;
            case "fall_flying" -> Pose.FALL_FLYING;
            case "sitting" -> Pose.SITTING;
            case "dying" -> Pose.DYING;
            default -> null;
        };
    }

    private static List<String> buildDyeColors(boolean includeNone) {
        List<String> out = new ArrayList<>();
        if (includeNone)
            out.add("none");
        for (DyeColor c : DyeColor.values())
            out.add(c.getName());
        return List.copyOf(out);
    }

    private static List<String> buildSheepColors() {
        List<String> out = new ArrayList<>();
        for (DyeColor c : DyeColor.values())
            out.add(c.getName());
        out.add("rainbow");
        return List.copyOf(out);
    }
}
