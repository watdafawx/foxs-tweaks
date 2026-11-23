package dev.watdafawx.foxstweaks.anchor;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Craftable item that places a Spawn Anchor dummy (a tagged, invulnerable vanilla Armor Stand).
 * Right-clicking the placed dummy opens {@code SpawnAnchorScreen} to toggle it ON/OFF, adjust
 * chunk radius with a live chunk-grid preview, or pick it back up.
 */
public class SpawnAnchorItem extends Item {
    public SpawnAnchorItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.foxstweaks.spawn_anchor.desc1").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.foxstweaks.spawn_anchor.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.spawn_anchor.desc3").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.spawn_anchor.desc4").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        if (!ServerConfig.spawnAnchorsEnabled()) {
            if (context.getPlayer() != null) {
                context.getPlayer().displayClientMessage(
                        Component.literal("Spawn anchors are disabled on this server.").withStyle(ChatFormatting.RED), true);
            }
            return InteractionResult.FAIL;
        }

        BlockPos placePos = context.getClickedPos().relative(context.getClickedFace());
        Vec3 pos = Vec3.atBottomCenterOf(placePos);

        ArmorStand dummy = EntityType.ARMOR_STAND.create(serverLevel);
        if (dummy == null) {
            return InteractionResult.FAIL;
        }

        float rot = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 22.5F) / 45.0F) * 45.0F;
        dummy.moveTo(pos.x, pos.y, pos.z, rot, 0.0F);
        dummy.setShowArms(true);
        dummy.setInvulnerable(true);
        dummy.setNoGravity(false);

        int defaultRadius = Math.min(2, ServerConfig.spawnAnchorsMaxRadius());
        String id = "dummy_" + UUID.randomUUID().toString().substring(0, 8);
        // The placer is remembered with the anchor so Apotheosis mob spawns around it can use their
        // world tier while they are away (see AnchorTier).
        SpawnDummyEvents.initDummy(serverLevel, dummy, id, true, defaultRadius, context.getPlayer());

        serverLevel.addFreshEntity(dummy);
        serverLevel.playSound(null, dummy.getX(), dummy.getY(), dummy.getZ(),
                SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.75F, 0.9F);

        if (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.CONSUME;
    }
}
