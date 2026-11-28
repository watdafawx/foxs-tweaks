package dev.watdafawx.foxstweaks.hologram;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;

/**
 * Attachment of a hologram to a player: follows that player across dimensions and relogs,
 * hiding automatically while they are offline.
 */
public record HologramAttach(
        UUID playerUuid,
        String playerName,
        Follow follow,
        boolean hideFirstPerson,
        boolean scaleWithPlayer
) {
    public enum Follow {
        POSITION("Position"),
        BODY_YAW("Body"),
        HEAD_LOOK("Head"),
        RIGHT_HAND("R-Hand"),
        LEFT_HAND("L-Hand"),
        RIGHT_LEG("R-Leg"),
        LEFT_LEG("L-Leg");

        public final String label;

        Follow(String label) {
            this.label = label;
        }

        public Follow next() {
            Follow[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public static Follow parse(@Nullable String raw) {
            if (raw == null || raw.isEmpty())
                return BODY_YAW;
            try {
                return valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return BODY_YAW;
            }
        }
    }

    @FunctionalInterface
    public interface LivePoseLookup {
        @Nullable
        Matrix4f lookup(Player player, Follow follow, boolean scaleWithPlayer);
    }

    public HologramAttach {
        if (playerName == null)
            playerName = "";
        if (follow == null)
            follow = Follow.BODY_YAW;
    }

    /** Backwards-compatible 4-arg constructor: defaults {@code scaleWithPlayer} to true. */
    public HologramAttach(UUID playerUuid, String playerName, Follow follow, boolean hideFirstPerson) {
        this(playerUuid, playerName, follow, hideFirstPerson, true);
    }

    public static HologramAttach of(Player player) {
        return new HologramAttach(player.getUUID(), player.getGameProfile().getName(), Follow.BODY_YAW, true, true);
    }

    public HologramAttach withFollow(Follow follow) {
        return new HologramAttach(this.playerUuid, this.playerName, follow, this.hideFirstPerson, this.scaleWithPlayer);
    }

    public HologramAttach withHideFirstPerson(boolean hideFirstPerson) {
        return new HologramAttach(this.playerUuid, this.playerName, this.follow, hideFirstPerson, this.scaleWithPlayer);
    }

    public HologramAttach withScaleWithPlayer(boolean scaleWithPlayer) {
        return new HologramAttach(this.playerUuid, this.playerName, this.follow, this.hideFirstPerson, scaleWithPlayer);
    }

    public HologramAttach withPlayer(UUID uuid, String name) {
        return new HologramAttach(uuid, name, this.follow, this.hideFirstPerson, this.scaleWithPlayer);
    }

    /** Client-installed hook providing live animated bone pose (Emotecraft / playerAnimator / PAL). */
    @Nullable
    public static volatile LivePoseLookup livePoseLookup;

    /**
     * World-space base transform for a player-attached hologram before the hologram's own
     * {@code offset/yaw/pitch/roll/scale}. In {@link Follow#HEAD_LOOK}, pitch pivots around the
     * player's eyes so hats/visors tilt naturally while keeping zero jump at pitch 0.
     */
    public Matrix4f anchorMatrix(Player player, float partialTick) {
        if (this.follow != Follow.POSITION && player.level().isClientSide()) {
            var lookup = livePoseLookup;
            if (lookup != null) {
                Matrix4f live = lookup.lookup(player, this.follow, this.scaleWithPlayer);
                if (live != null)
                    return live;
            }
        }
        Vec3 pos = partialTick >= 1.0F ? player.position() : player.getPosition(partialTick);
        Matrix4f m = new Matrix4f().translate((float) pos.x, (float) pos.y, (float) pos.z);
        float pScale = player.getScale();
        if (this.scaleWithPlayer && Math.abs(pScale - 1.0F) > 0.001F)
            m.scale(pScale, pScale, pScale);

        if (this.follow == Follow.POSITION)
            return m;

        float crouchY = player.isCrouching() ? -0.1875F : 0.0F;
        if (this.follow == Follow.HEAD_LOOK) {
            float headYaw = partialTick >= 1.0F
                    ? player.yHeadRot
                    : Mth.rotLerp(partialTick, player.yHeadRotO, player.yHeadRot);
            float headPitch = partialTick >= 1.0F
                    ? player.getXRot()
                    : Mth.lerp(partialTick, player.xRotO, player.getXRot());
            float eyeH = player.getEyeHeight() / Math.max(0.05F, this.scaleWithPlayer ? pScale : 1.0F);
            m.translate(0.0F, crouchY, 0.0F);
            m.rotateY((float) Math.toRadians(-headYaw));
            m.translate(0.0F, eyeH, 0.0F);
            m.rotateX((float) Math.toRadians(headPitch));
            m.translate(0.0F, -eyeH, 0.0F);
            return m;
        }

        float bodyYaw = partialTick >= 1.0F
                ? player.yBodyRot
                : Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot);
        m.rotateY((float) Math.toRadians(-bodyYaw));
        if (this.follow == Follow.BODY_YAW || this.follow == Follow.RIGHT_HAND || this.follow == Follow.LEFT_HAND) {
            m.translate(0.0F, crouchY, 0.0F);
        }
        return m;
    }

    public CompoundTag toTag() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("uuid", this.playerUuid);
        if (!this.playerName.isEmpty())
            tag.putString("name", this.playerName);
        tag.putString("follow", this.follow.name());
        tag.putBoolean("hideFirstPerson", this.hideFirstPerson);
        if (!this.scaleWithPlayer)
            tag.putBoolean("scaleWithPlayer", false);
        return tag;
    }

    @Nullable
    public static HologramAttach fromTag(CompoundTag tag) {
        if (!tag.hasUUID("uuid"))
            return null;
        UUID uuid = tag.getUUID("uuid");
        String name = tag.getString("name");
        Follow follow = Follow.parse(tag.getString("follow"));
        boolean hideFirstPerson = !tag.contains("hideFirstPerson") || tag.getBoolean("hideFirstPerson");
        boolean scaleWithPlayer = !tag.contains("scaleWithPlayer") || tag.getBoolean("scaleWithPlayer");
        return new HologramAttach(uuid, name, follow, hideFirstPerson, scaleWithPlayer);
    }
}
