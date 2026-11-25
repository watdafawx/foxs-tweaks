package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry in a layer's or group's effect list. Entries stack: a red {@code TINT} plus a
 * {@code FLICKER} plus a {@code TREMOR} reads as a failing projector, and a {@code SPIN} on Y plus a
 * {@code SPIN} on X plus a {@code BOB} is a tumbling, floating object.
 *
 * <p>The list holds three families, told apart by {@link Type#isFx()}, {@link Type#isMotion()} and
 * {@link Type#isEntityAnim()}: visual FX (the FX tab), motion animations (the Anim tab), and entity
 * animations that drive a mob's own model (also the Anim tab, only meaningful on entity layers).
 *
 * <p>Every effect here is pure maths on the draw call - no shaders, no framebuffers, no post-processing
 * pass. That is deliberate: a real distortion pass needs an off-screen target per hologram, which
 * collides head-on with shader packs and costs a full-screen copy. These are cheap, look right, and work
 * inside Iris' world pass.
 *
 * <p>{@code speed} is in Hz (cycles per second) and each type has its own natural default, because
 * "speed 1" means completely different things for a 6 Hz tremor and a 0.6 Hz wobble. {@code axis} is only
 * read by the types for which {@link Type#usesAxis()} is true.
 */
public record HologramEffect(Type type, float strength, float speed, int color, int axis, float phase,
                             Trigger trigger) {

    /** When an effect plays. Everything but {@code ALWAYS} is decided on the client from the world around it. */
    public enum Trigger {
        ALWAYS("Always", "Any"),
        DAY("Daytime", "Day"),
        NIGHT("Night", "Night"),
        RAIN("Raining", "Rain"),
        THUNDER("Thunder", "Thndr"),
        JUKEBOX("Jukebox", "Juke"),
        MUSIC("Music", "Music"),
        MOB_DIED("Mob died", "Death");

        private final String label;
        private final String shortLabel;

        Trigger(String label, String shortLabel) {
            this.label = label;
            this.shortLabel = shortLabel;
        }

        /** Fits a 30px button. */
        public String shortLabel() {
            return this.shortLabel;
        }

        public String label() {
            return this.label;
        }

        /** Plays one cycle from the moment something happens, instead of following a condition. */
        public boolean isPulse() {
            return this == MOB_DIED;
        }

        /** The next trigger in the editor's cycle. */
        public Trigger next() {
            Trigger[] all = values();
            return all[(this.ordinal() + 1) % all.length];
        }
    }

    public enum Type {
        // ---- Visual FX (the FX tab) ----
        /** Multiplies the block's colours. The stored {@code color} is the tint. */
        TINT,
        /** Sweeping horizontal sci-fi hologram scanline bands. */
        SCANLINE,
        /** Cyber-glitch bursts: brief horizontal offset snaps + chromatic flash. */
        GLITCH,
        /** Smooth rhythmic energy brightness pulse. */
        PULSE,
        /** Smooth HSV rainbow chroma cycle. */
        RAINBOW,
        /** Hides the hologram for a few frames at a time. Works on solid render types, unlike alpha. */
        FLICKER,
        /** Small, fast rotation jitter. */
        TREMOR,
        /** Squash-and-stretch breathing. */
        WOBBLE,
        /** Periodic vertical snap, like a mistimed projection. */
        JUMP,

        // ---- Motion animations (the Anim tab). They stack, and one type may be added once per axis. ----
        /** Endless rotation on one axis; strength is degrees per second (negative turns the other way). */
        SPIN,
        /** Rocks back and forth on one axis; strength is the swing in degrees each side. */
        SWAY,
        /** Floats along one axis and back; strength is the distance in blocks. */
        BOB,
        /** Circles around an axis; strength is the radius in blocks, Hz is laps per second. */
        ORBIT,
        /** Glides to one side and back along an axis, easing at both ends. */
        SLIDE,
        /** Uniform grow/shrink; strength is the fraction of the size. */
        BREATHE,
        /** Double thump, like a heartbeat; strength is the fraction of the size. */
        HEARTBEAT,
        /** A full turn on one axis, then a rest, like a spinning coin; strength is degrees per flip. */
        FLIP,
        /** Hops along an axis and lands again; strength is the height in blocks. */
        BOUNCE,

        // ---- Entity animations: drive an entity's own model animations. Ignored by blocks. ----
        /** Walk cycle; strength is the stride amount. */
        WALK,
        /** Arm swing / attack, Hz times per second. */
        ATTACK,
        /** Red hurt flash, Hz times per second. */
        HURT,
        /** Falls over and pops back up, Hz cycles per second. */
        DEATH,
        /** Crouched pose. */
        SNEAK;

        public boolean isFx() {
            return this.ordinal() <= JUMP.ordinal();
        }

        public boolean isMotion() {
            return this.ordinal() >= SPIN.ordinal() && this.ordinal() <= BOUNCE.ordinal();
        }

        public boolean isEntityAnim() {
            return this.ordinal() >= WALK.ordinal();
        }

        /** Anything that belongs on the Anim tab rather than the FX tab. */
        public boolean isAnim() {
            return !isFx();
        }

        public boolean usesAxis() {
            return switch (this) {
                case SPIN, SWAY, BOB, ORBIT, SLIDE, FLIP, BOUNCE -> true;
                default -> false;
            };
        }

        public boolean usesAmount() {
            return this != ATTACK && this != HURT && this != DEATH && this != SNEAK;
        }

        /** Whether a phase offset means anything: every motion, plus the timed entity animations. */
        public boolean usesPhase() {
            return isMotion() || this == ATTACK || this == HURT || this == DEATH;
        }

        public boolean usesSpeed() {
            return this != SPIN && this != WALK && this != SNEAK;
        }

        public float minAmount() {
            return switch (this) {
                case SPIN, FLIP, SWAY -> -720.0F;
                case BOB, ORBIT, SLIDE, BOUNCE -> -8.0F;
                case BREATHE, HEARTBEAT -> -0.9F;
                default -> 0.0F;
            };
        }

        public float maxAmount() {
            return switch (this) {
                case SPIN, FLIP, SWAY -> 720.0F;
                case BOB, ORBIT, SLIDE, BOUNCE -> 8.0F;
                case BREATHE, HEARTBEAT -> 2.0F;
                case WALK -> 1.5F;
                default -> 2.0F;
            };
        }

        public float amountStep() {
            return switch (this) {
                case SPIN, FLIP, SWAY -> 15.0F;
                case BOB, ORBIT, SLIDE, BOUNCE -> 0.25F;
                case BREATHE, HEARTBEAT -> 0.05F;
                default -> 0.1F;
            };
        }

        public int amountDecimals() {
            return switch (this) {
                case SPIN, FLIP, SWAY -> 0;
                default -> 2;
            };
        }

        /** Short pill label for the editor. */
        public String shortName() {
            return switch (this) {
                case TINT -> "Tint";
                case SCANLINE -> "Scan";
                case GLITCH -> "Gltch";
                case PULSE -> "Pulse";
                case RAINBOW -> "Chrma";
                case FLICKER -> "Flkr";
                case TREMOR -> "Trmr";
                case WOBBLE -> "Wbl";
                case JUMP -> "Jump";
                case SPIN -> "Spin";
                case SWAY -> "Sway";
                case BOB -> "Bob";
                case ORBIT -> "Orbit";
                case SLIDE -> "Slide";
                case BREATHE -> "Breath";
                case HEARTBEAT -> "Beat";
                case FLIP -> "Flip";
                case BOUNCE -> "Bounce";
                case WALK -> "Walk";
                case ATTACK -> "Swing";
                case HURT -> "Hurt";
                case DEATH -> "Die";
                case SNEAK -> "Sneak";
            };
        }

        public static List<Type> fxTypes() {
            return filtered(0);
        }

        public static List<Type> motionTypes() {
            return filtered(1);
        }

        public static List<Type> entityTypes() {
            return filtered(2);
        }

        private static List<Type> filtered(int family) {
            List<Type> out = new ArrayList<>();
            for (Type t : values()) {
                boolean keep = switch (family) {
                    case 0 -> t.isFx();
                    case 1 -> t.isMotion();
                    default -> t.isEntityAnim();
                };
                if (keep)
                    out.add(t);
            }
            return out;
        }
    }

    /** The default hologram cyan, used by {@link Type#TINT}. */
    public static final int DEFAULT_COLOR = 0x66E0FF;

    /** The axis a new animation starts on (Y, the vertical one). */
    public static final int DEFAULT_AXIS = HologramSpec.AXIS_Y;

    public HologramEffect {
        strength = type.isFx()
                ? Mth.clamp(strength, 0.0F, 2.0F)
                : Mth.clamp(strength, type.minAmount(), type.maxAmount());
        speed = Mth.clamp(speed, 0.05F, 20.0F);
        color &= 0xFFFFFF;
        axis = Mth.clamp(axis, 0, 2);
        phase = phase - (float) Math.floor(phase);
        if (trigger == null)
            trigger = Trigger.ALWAYS;
    }

    public HologramEffect(Type type, float strength, float speed, int color, int axis, float phase) {
        this(type, strength, speed, color, axis, phase, Trigger.ALWAYS);
    }

    public HologramEffect(Type type, float strength, float speed, int color, int axis) {
        this(type, strength, speed, color, axis, 0.0F);
    }

    /** FX have no axis, so this keeps older call sites (and saved data) meaning what they did. */
    public HologramEffect(Type type, float strength, float speed, int color) {
        this(type, strength, speed, color, DEFAULT_AXIS);
    }

    public HologramEffect withStrength(float strength) {
        return new HologramEffect(this.type, strength, this.speed, this.color, this.axis, this.phase, this.trigger);
    }

    public HologramEffect withSpeed(float speed) {
        return new HologramEffect(this.type, this.strength, speed, this.color, this.axis, this.phase, this.trigger);
    }

    public HologramEffect withColor(int color) {
        return new HologramEffect(this.type, this.strength, this.speed, color, this.axis, this.phase, this.trigger);
    }

    public HologramEffect withAxis(int axis) {
        return new HologramEffect(this.type, this.strength, this.speed, this.color, axis, this.phase, this.trigger);
    }

    /** Where in its cycle this animation starts, 0..1. Lets two copies of one animation run out of step. */
    public HologramEffect withPhase(float phase) {
        return new HologramEffect(this.type, this.strength, this.speed, this.color, this.axis, phase, this.trigger);
    }

    public HologramEffect withTrigger(Trigger trigger) {
        return new HologramEffect(this.type, this.strength, this.speed, this.color, this.axis, this.phase, trigger);
    }

    public String axisLabel() {
        return switch (this.axis) {
            case HologramSpec.AXIS_X -> "X";
            case HologramSpec.AXIS_Z -> "Z";
            default -> "Y";
        };
    }

    /** The defaults a new layer starts with: strong enough to see, gentle enough to keep. */
    public static HologramEffect of(Type type) {
        return switch (type) {
            case TINT -> new HologramEffect(type, 0.65F, 1.0F, DEFAULT_COLOR);
            case SCANLINE -> new HologramEffect(type, 0.75F, 1.8F, DEFAULT_COLOR);
            case GLITCH -> new HologramEffect(type, 0.7F, 2.5F, DEFAULT_COLOR);
            case PULSE -> new HologramEffect(type, 0.65F, 1.5F, DEFAULT_COLOR);
            case RAINBOW -> new HologramEffect(type, 0.85F, 0.4F, DEFAULT_COLOR);
            case FLICKER -> new HologramEffect(type, 0.5F, 7.0F, DEFAULT_COLOR);
            case TREMOR -> new HologramEffect(type, 0.6F, 5.0F, DEFAULT_COLOR);
            case WOBBLE -> new HologramEffect(type, 0.6F, 1.2F, DEFAULT_COLOR);
            case JUMP -> new HologramEffect(type, 0.55F, 1.0F, DEFAULT_COLOR);
            case SPIN -> new HologramEffect(type, 45.0F, 1.0F, DEFAULT_COLOR);
            case SWAY -> new HologramEffect(type, 20.0F, 0.5F, DEFAULT_COLOR);
            case BOB -> new HologramEffect(type, 0.25F, 0.25F, DEFAULT_COLOR);
            case ORBIT -> new HologramEffect(type, 1.0F, 0.25F, DEFAULT_COLOR);
            case SLIDE -> new HologramEffect(type, 1.0F, 0.2F, DEFAULT_COLOR, HologramSpec.AXIS_X);
            case BREATHE -> new HologramEffect(type, 0.15F, 0.5F, DEFAULT_COLOR);
            case HEARTBEAT -> new HologramEffect(type, 0.2F, 1.2F, DEFAULT_COLOR);
            case FLIP -> new HologramEffect(type, 360.0F, 0.4F, DEFAULT_COLOR, HologramSpec.AXIS_X);
            case BOUNCE -> new HologramEffect(type, 0.5F, 1.0F, DEFAULT_COLOR);
            case WALK -> new HologramEffect(type, 1.0F, 1.0F, DEFAULT_COLOR);
            case ATTACK -> new HologramEffect(type, 1.0F, 1.5F, DEFAULT_COLOR);
            case HURT -> new HologramEffect(type, 1.0F, 0.8F, DEFAULT_COLOR);
            case DEATH -> new HologramEffect(type, 1.0F, 0.25F, DEFAULT_COLOR);
            case SNEAK -> new HologramEffect(type, 1.0F, 1.0F, DEFAULT_COLOR);
        };
    }

    /** One-click animation combos for the Anim tab, in the order the editor lists them. */
    public static final String[] PRESETS = { "Float", "Coin", "Orbit", "Roll", "Beat", "Hop" };

    /** The motion entries of a preset (empty for an unknown name). */
    public static List<HologramEffect> preset(String name) {
        return switch (name) {
            case "Float" -> List.of(
                    of(Type.BOB).withStrength(0.15F).withSpeed(0.25F),
                    of(Type.SWAY).withStrength(12.0F).withSpeed(0.2F));
            case "Coin" -> List.of(
                    of(Type.SPIN).withStrength(90.0F),
                    of(Type.BOB).withStrength(0.2F).withSpeed(0.3F));
            case "Orbit" -> List.of(
                    of(Type.ORBIT).withStrength(1.0F).withSpeed(0.2F),
                    of(Type.SPIN).withStrength(60.0F));
            case "Roll" -> List.of(
                    of(Type.SPIN).withStrength(40.0F),
                    of(Type.SPIN).withAxis(HologramSpec.AXIS_X).withStrength(25.0F),
                    of(Type.SPIN).withAxis(HologramSpec.AXIS_Z).withStrength(15.0F));
            case "Beat" -> List.of(of(Type.HEARTBEAT).withStrength(0.15F).withSpeed(1.2F));
            case "Hop" -> List.of(
                    of(Type.BOUNCE).withStrength(0.4F).withSpeed(1.0F),
                    of(Type.SWAY).withAxis(HologramSpec.AXIS_Z).withStrength(8.0F).withSpeed(1.0F));
            default -> List.of();
        };
    }

    /**
     * A new animation of {@code type}: the defaults, on the first axis that type is not already using, so
     * adding Spin three times gives Y, X, Z instead of three identical spins on top of each other.
     */
    public static HologramEffect addable(Type type, List<HologramEffect> existing) {
        HologramEffect base = of(type);
        if (!type.usesAxis())
            return base;
        int[] order = { base.axis(), HologramSpec.AXIS_Y, HologramSpec.AXIS_X, HologramSpec.AXIS_Z };
        for (int axis : order) {
            boolean used = false;
            for (HologramEffect effect : existing) {
                if (effect.type() == type && effect.axis() == axis) {
                    used = true;
                    break;
                }
            }
            if (!used)
                return base.withAxis(axis);
        }
        return base;
    }

    /** The next unused type, so "Add effect" never stacks two identical layers by accident. */
    public static Type nextType(List<HologramEffect> existing) {
        for (Type type : Type.values()) {
            boolean used = false;
            for (HologramEffect effect : existing) {
                if (effect.type() == type) {
                    used = true;
                    break;
                }
            }
            if (!used)
                return type;
        }
        return Type.TINT;
    }

    public String label() {
        return this.type.name().charAt(0) + this.type.name().substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putString("type", this.type.name());
        tag.putFloat("strength", this.strength);
        tag.putFloat("speed", this.speed);
        tag.putInt("color", this.color);
        if (this.axis != DEFAULT_AXIS)
            tag.putByte("axis", (byte) this.axis);
        if (this.phase != 0.0F)
            tag.putFloat("phase", this.phase);
        if (this.trigger != Trigger.ALWAYS)
            tag.putString("when", this.trigger.name());
        return tag;
    }

    public static ListTag listToTag(List<HologramEffect> effects) {
        ListTag list = new ListTag();
        for (HologramEffect effect : effects)
            list.add(effect.toTag());
        return list;
    }

    public static List<HologramEffect> listFromTag(ListTag list) {
        List<HologramEffect> effects = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag tag = list.getCompound(i);
            Type type;
            try {
                type = Type.valueOf(tag.getString("type"));
            } catch (IllegalArgumentException e) {
                continue;
            }
            effects.add(new HologramEffect(type, tag.getFloat("strength"), tag.getFloat("speed"),
                    tag.contains("color") ? tag.getInt("color") : DEFAULT_COLOR,
                    tag.contains("axis") ? tag.getByte("axis") : DEFAULT_AXIS,
                    tag.contains("phase") ? tag.getFloat("phase") : 0.0F,
                    triggerOf(tag.getString("when"))));
        }
        return effects;
    }

    private static Trigger triggerOf(String name) {
        if (name.isEmpty())
            return Trigger.ALWAYS;
        try {
            return Trigger.valueOf(name);
        } catch (IllegalArgumentException e) {
            return Trigger.ALWAYS;
        }
    }

    /** True when this tag is one of ours; used to skip junk in a saved list. */
    public static boolean looksValid(Tag tag) {
        return tag instanceof CompoundTag compound && !compound.getString("type").isEmpty();
    }
}
