package dev.watdafawx.foxstweaks.terraform;

import dev.watdafawx.foxstweaks.FoxsTweaksComponents;
import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;
import java.util.Optional;

/**
 * Survival-craftable Terrain Flattener Wand.
 *
 * <ul>
 *   <li>Right-click block: set Pos1/Pos2 (Box) or center + target height (Brush).</li>
 *   <li>Right-click air (with selection): confirm and gradually flatten terrain.</li>
 *   <li>Sneak + Right-click (air or block): open settings screen (flatness, smoothness, feather, trees).</li>
 * </ul>
 */
public class FlattenWandItem extends Item {
    public FlattenWandItem(Properties properties) {
        super(properties);
    }

    public static FlattenWandConfig getConfig(ItemStack stack) {
        return stack.getOrDefault(FoxsTweaksComponents.FLATTEN_WAND.get(), FlattenWandConfig.DEFAULT);
    }

    public static void setConfig(ItemStack stack, FlattenWandConfig cfg) {
        stack.set(FoxsTweaksComponents.FLATTEN_WAND.get(), cfg);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getConfig(stack).hasSelection();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        FlattenWandConfig cfg = getConfig(stack);
        tooltip.add(Component.literal("Flatness: " + Math.round(cfg.flatness() * 100) + "%  Smooth: "
                        + Math.round(cfg.smoothness() * 100) + "%  [" + cfg.mode().label + "]")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.foxstweaks.terrain_flattener.desc1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.terrain_flattener.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.terrain_flattener.desc3").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();

        if (player.isShiftKeyDown()) {
            if (level.isClientSide() && FMLEnvironment.dist.isClient()) {
                dev.watdafawx.foxstweaks.client.terraform.TerraformClient.openFlattenScreen(context.getHand(), stack);
            }
            return InteractionResult.sidedSuccess(level.isClientSide());
        }

        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!ServerConfig.terraformEnabled()) {
            player.displayClientMessage(Component.literal("Terraform tools are disabled on this server.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        BlockPos clicked = context.getClickedPos();
        FlattenWandConfig cfg = getConfig(stack);
        FlattenWandConfig updated;
        String msg;

        if (cfg.mode() == FlattenWandConfig.Mode.BOX) {
            if (cfg.pos1().isEmpty() || cfg.pos2().isPresent()) {
                updated = cfg.withPositions(Optional.of(clicked), Optional.empty());
                msg = "Pos 1 set (" + clicked.toShortString() + "). Right-click Pos 2.";
            } else {
                updated = cfg.withPositions(cfg.pos1(), Optional.of(clicked));
                msg = "Area set. Right-click air to flatten, Shift+Right-click for settings.";
            }
        } else {
            updated = cfg.withPositions(Optional.of(clicked), Optional.empty());
            msg = "Center set (Y=" + clicked.getY() + ", r=" + cfg.radius() + "). Right-click air to flatten.";
        }

        setConfig(stack, updated);
        level.playSound(null, clicked, SoundEvents.STONE_PLACE, SoundSource.PLAYERS, 0.6F, 1.2F);
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.GOLD), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide() && FMLEnvironment.dist.isClient()) {
                dev.watdafawx.foxstweaks.client.terraform.TerraformClient.openFlattenScreen(hand, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        FlattenWandConfig cfg = getConfig(stack);
        if (!cfg.hasSelection()) {
            if (!level.isClientSide()) {
                player.displayClientMessage(
                        Component.literal("Right-click a block to set area, or Shift+Right-click for settings.")
                                .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.pass(stack);
        }

        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            TerrainFlattener.enqueue(sp, cfg);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
