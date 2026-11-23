package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import dev.watdafawx.foxstweaks.FoxsTweaksItems;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Handles interactions and lifecycle for placed Spawn Anchor dummies (tagged vanilla Armor Stands):
 * right-click opens the config GUI, sneak+left-click (or GUI Pick Up) returns the item, and
 * removal cleans up the chunk-loading anchor automatically.
 */
@EventBusSubscriber(modid = FoxsTweaks.MODID)
public final class SpawnDummyEvents {
    private static final String TAG_ID = "foxstweaks_anchor_id";
    private static final String TAG_ENABLED = "foxstweaks_anchor_enabled";
    private static final String TAG_RADIUS = "foxstweaks_anchor_radius";
    private static final String TAG_OWNER = "foxstweaks_anchor_owner";
    private static final String TAG_OWNER_NAME = "foxstweaks_anchor_owner_name";
    private static final String TAG_TIER = "foxstweaks_anchor_tier";
    private static final String TAG_FOLLOW_OWNER = "foxstweaks_anchor_follow_owner";
    private static final String TAG_HIDE_NAME = "foxstweaks_anchor_hide_name";

    private SpawnDummyEvents() {
    }

    public static boolean isDummy(Entity entity) {
        return entity instanceof ArmorStand && entity.getPersistentData().contains(TAG_ID);
    }

    public static String getId(Entity entity) {
        return entity.getPersistentData().getString(TAG_ID);
    }

    public static boolean isEnabled(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG_ENABLED);
    }

    public static int getRadius(Entity entity) {
        int r = entity.getPersistentData().getInt(TAG_RADIUS);
        return Math.max(1, Math.min(8, r == 0 ? 2 : r));
    }

    public static String getTier(Entity entity) {
        return entity.getPersistentData().getString(TAG_TIER);
    }

    public static boolean followsOwner(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG_FOLLOW_OWNER);
    }

    public static String getOwnerName(Entity entity) {
        return entity.getPersistentData().getString(TAG_OWNER_NAME);
    }

    public static boolean hidesName(Entity entity) {
        return entity.getPersistentData().getBoolean(TAG_HIDE_NAME);
    }

    public static boolean hasOwner(Entity entity) {
        return entity.getPersistentData().hasUUID(TAG_OWNER);
    }

    @Nullable
    public static UUID ownerOf(Entity entity) {
        return entity.getPersistentData().hasUUID(TAG_OWNER) ? entity.getPersistentData().getUUID(TAG_OWNER) : null;
    }

    public static void initDummy(ServerLevel level, ArmorStand dummy, String id, boolean enabled, int radius,
                                 @Nullable Player placer) {
        CompoundTag tag = dummy.getPersistentData();
        tag.putString(TAG_ID, id);
        tag.putBoolean(TAG_ENABLED, enabled);
        tag.putInt(TAG_RADIUS, radius);

        // Captured once, at placement: the owner's Apotheosis tier is what a mob spawning here will be
        // judged against later, when nobody is around. Empty when Apotheosis is not installed.
        String tier = AnchorTier.tierNameOf(placer);
        if (placer != null) {
            tag.putUUID(TAG_OWNER, placer.getUUID());
            tag.putString(TAG_OWNER_NAME, placer.getGameProfile().getName());
            tag.putBoolean(TAG_FOLLOW_OWNER, true);
        }
        if (!tier.isEmpty())
            tag.putString(TAG_TIER, tier);

        applyState(level, dummy, enabled, radius);
    }

    public static void applyState(ServerLevel level, ArmorStand dummy, boolean enabled, int radius) {
        CompoundTag tag = dummy.getPersistentData();
        applyState(level, dummy, enabled, radius, tag.getString(TAG_TIER), tag.getBoolean(TAG_FOLLOW_OWNER),
                tag.getBoolean(TAG_HIDE_NAME));
    }

    public static void applyState(ServerLevel level, ArmorStand dummy, boolean enabled, int radius, String tier,
                                  boolean followOwner, boolean hideName) {
        int clampedRadius = Math.max(1, Math.min(ServerConfig.spawnAnchorsMaxRadius(), radius));
        CompoundTag tag = dummy.getPersistentData();
        String id = tag.getString(TAG_ID);
        tag.putBoolean(TAG_ENABLED, enabled);
        tag.putInt(TAG_RADIUS, clampedRadius);
        tag.putBoolean(TAG_HIDE_NAME, hideName);
        tag.putBoolean(TAG_FOLLOW_OWNER, followOwner && tag.hasUUID(TAG_OWNER));
        if (tier.isEmpty())
            tag.remove(TAG_TIER);
        else
            tag.putString(TAG_TIER, tier);

        int side = clampedRadius * 2 + 1;
        if (enabled && ServerConfig.spawnAnchorsEnabled()) {
            SpawnAnchors.add(level, new SpawnAnchorData.Anchor(id, dummy.getX(), dummy.getY(), dummy.getZ(),
                    clampedRadius, tag.hasUUID(TAG_OWNER) ? tag.getUUID(TAG_OWNER) : null,
                    tag.getString(TAG_OWNER_NAME), tag.getString(TAG_TIER),
                    tag.getBoolean(TAG_FOLLOW_OWNER)));
            dummy.setCustomName(Component.literal("Mob & Chunk Loader ")
                    .withStyle(ChatFormatting.GOLD)
                    .append(Component.literal("[" + side + "x" + side + "]").withStyle(ChatFormatting.GREEN)));
        } else {
            SpawnAnchors.remove(level, id);
            dummy.setCustomName(Component.literal("Mob & Chunk Loader ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal("[OFF]").withStyle(ChatFormatting.RED)));
        }
        dummy.setCustomNameVisible(!hideName);
    }

    public static void pickUpDummy(ServerPlayer player, ArmorStand dummy) {
        if (!(dummy.level() instanceof ServerLevel level))
            return;

        String id = getId(dummy);
        SpawnAnchors.remove(level, id);
        level.playSound(null, dummy.getX(), dummy.getY(), dummy.getZ(),
                SoundEvents.ARMOR_STAND_BREAK, SoundSource.BLOCKS, 0.75F, 1.0F);
        dummy.discard();

        if (!player.getAbilities().instabuild) {
            ItemStack item = new ItemStack(FoxsTweaksItems.SPAWN_ANCHOR.get());
            if (!player.getInventory().add(item)) {
                player.drop(item, false);
            }
        }
    }

    @SubscribeEvent
    public static void onRightClickDummy(PlayerInteractEvent.EntityInteractSpecific event) {
        if (!isDummy(event.getTarget()))
            return;

        event.setCanceled(true);
        event.setCancellationResult(InteractionResult.sidedSuccess(event.getLevel().isClientSide));

        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        ArmorStand dummy = (ArmorStand) event.getTarget();
        if (player.connection.hasChannel(OpenAnchorGuiPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, new OpenAnchorGuiPayload(
                    dummy.getId(),
                    isEnabled(dummy),
                    getRadius(dummy),
                    ServerConfig.spawnAnchorsMaxRadius(),
                    dummy.blockPosition().getX(),
                    dummy.blockPosition().getY(),
                    dummy.blockPosition().getZ(),
                    dummy.level().dimension().location().toString(),
                    getTier(dummy),
                    followsOwner(dummy),
                    getOwnerName(dummy),
                    hidesName(dummy)));
        } else {
            // Fallback for a client without Fox's Tweaks: toggle ON/OFF directly on right-click
            boolean next = !isEnabled(dummy);
            applyState((ServerLevel) dummy.level(), dummy, next, getRadius(dummy));
            player.displayClientMessage(Component.literal("Mob & Chunk Loader " + (next ? "ON" : "OFF")), true);
        }
    }

    @SubscribeEvent
    public static void onAttackDummy(AttackEntityEvent event) {
        if (!isDummy(event.getTarget()))
            return;

        event.setCanceled(true);
        if (!(event.getEntity() instanceof ServerPlayer player))
            return;

        if (player.isShiftKeyDown()) {
            pickUpDummy(player, (ArmorStand) event.getTarget());
        } else {
            player.displayClientMessage(
                    Component.literal("Right-click to configure, or Sneak + Left-click to pick up.").withStyle(ChatFormatting.GRAY), true);
        }
    }

    @SubscribeEvent
    public static void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        Entity entity = event.getEntity();
        if (!isDummy(entity) || !(event.getLevel() instanceof ServerLevel level))
            return;
        if (!level.getServer().isRunning())
            return;

        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason != null && reason.shouldDestroy()) {
            SpawnAnchors.remove(level, getId(entity));
        }
    }
}
