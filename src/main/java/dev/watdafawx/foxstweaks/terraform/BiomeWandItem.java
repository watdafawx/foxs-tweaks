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
 * Survival-craftable Biome Changer Wand.
 *
 * <ul>
 *   <li>Right-click block: set selection (Pos1/Pos2 in Box mode, center in Brush / Flood-Fill mode).</li>
 *   <li>Right-click air (with selection): confirm and apply biome change live.</li>
 *   <li>Sneak + Right-click (air or block): open biome picker & settings screen.</li>
 * </ul>
 */
public class BiomeWandItem extends Item {
    public BiomeWandItem(Properties properties) {
        super(properties);
    }

    public static BiomeWandConfig getConfig(ItemStack stack) {
        return stack.getOrDefault(FoxsTweaksComponents.BIOME_WAND.get(), BiomeWandConfig.DEFAULT);
    }

    public static void setConfig(ItemStack stack, BiomeWandConfig cfg) {
        stack.set(FoxsTweaksComponents.BIOME_WAND.get(), cfg);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return getConfig(stack).hasSelection();
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BiomeWandConfig cfg = getConfig(stack);
        tooltip.add(Component.literal("Target: " + cfg.targetBiome().getPath() + "  [" + cfg.mode().label + "]")
                .withStyle(ChatFormatting.GREEN));
        tooltip.add(Component.translatable("item.foxstweaks.biome_wand.desc1").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.biome_wand.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.biome_wand.desc3").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();

        if (player.isShiftKeyDown()) {
            if (level.isClientSide() && FMLEnvironment.dist.isClient()) {
                dev.watdafawx.foxstweaks.client.terraform.TerraformClient.openBiomeScreen(context.getHand(), stack);
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
        BiomeWandConfig cfg = getConfig(stack);
        BiomeWandConfig updated;
        String msg;

        switch (cfg.mode()) {
            case BOX -> {
                if (cfg.pos1().isEmpty() || cfg.pos2().isPresent()) {
                    updated = cfg.withPositions(Optional.of(clicked), Optional.empty());
                    msg = "Pos 1 set (" + clicked.toShortString() + "). Right-click Pos 2.";
                } else {
                    updated = cfg.withPositions(cfg.pos1(), Optional.of(clicked));
                    msg = "Pos 2 set. Right-click air to confirm, Shift+Right-click for settings.";
                }
            }
            case BRUSH -> {
                updated = cfg.withPositions(Optional.of(clicked), Optional.empty());
                msg = "Brush center set (r=" + cfg.brushRadius() + "). Right-click air to confirm.";
            }
            case FLOOD_FILL -> {
                updated = cfg.withPositions(Optional.of(clicked), Optional.empty());
                String fromBiome = level.getBiome(clicked).unwrapKey().map(k -> k.location().getPath()).orElse("biome");
                msg = "Selected " + fromBiome + " -> " + cfg.targetBiome().getPath() + ". Right-click air to confirm.";
            }
            default -> {
                updated = cfg;
                msg = "";
            }
        }

        setConfig(stack, updated);
        level.playSound(null, clicked, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.5F, 1.3F);
        player.displayClientMessage(Component.literal(msg).withStyle(ChatFormatting.AQUA), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            if (level.isClientSide() && FMLEnvironment.dist.isClient()) {
                dev.watdafawx.foxstweaks.client.terraform.TerraformClient.openBiomeScreen(hand, stack);
            }
            return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
        }

        BiomeWandConfig cfg = getConfig(stack);
        if (!cfg.hasSelection()) {
            if (!level.isClientSide()) {
                player.displayClientMessage(
                        Component.literal("Right-click a block to select area, or Shift+Right-click for settings.")
                                .withStyle(ChatFormatting.GRAY), true);
            }
            return InteractionResultHolder.pass(stack);
        }

        if (!level.isClientSide() && player instanceof ServerPlayer sp) {
            BiomeChanger.apply(sp, cfg);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide());
    }
}
