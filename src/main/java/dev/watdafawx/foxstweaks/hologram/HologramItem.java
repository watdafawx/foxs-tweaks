package dev.watdafawx.foxstweaks.hologram;

import dev.watdafawx.foxstweaks.ServerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.loading.FMLEnvironment;

import java.util.List;

/**
 * The Hologram Wand: reusable, never consumed. Right-click a block to project a hologram of it on
 * the touched face and open the editor; right-click an existing hologram to edit it; sneak and
 * right-click one to remove it.
 *
 * <p>Which hologram was clicked is decided on the server by re-running {@link HologramPick} against
 * the saved list with the player's eye and look direction - there is no marker entity to hit, so the
 * server has to do that test itself rather than trust a client-sent id.
 */
public class HologramItem extends Item {
    public HologramItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.foxstweaks.hologram_wand.desc1").withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("item.foxstweaks.hologram_wand.desc2").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.hologram_wand.desc3").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.foxstweaks.hologram_wand.desc4").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null)
            return InteractionResult.PASS;

        if (level.isClientSide()) {
            // Pasting is client-driven: the clipboard is client state, so the client has to send the
            // create. Guarded by dist because HologramClient is a client-only class.
            if (FMLEnvironment.dist.isClient())
                dev.watdafawx.foxstweaks.client.HologramClient.onWandUseOn(context);
            return InteractionResult.SUCCESS;
        }

        if (!(level instanceof ServerLevel server))
            return InteractionResult.SUCCESS;

        if (!ServerConfig.hologramsEnabled()) {
            player.displayClientMessage(
                    Component.literal("Holograms are disabled on this server.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        HologramSpec hit = pick(server, player);
        if (hit != null)
            return useOnHologram(server, player, hit);

        // Sneak + right-click a block is the paste gesture, and only the client knows whether it has a
        // clipboard; the create arrives as its own payload. Never also project the clicked block here.
        if (player.isShiftKeyDown())
            return InteractionResult.CONSUME;

        BlockPos target = context.getClickedPos().relative(context.getClickedFace());
        BlockState shown = server.getBlockState(context.getClickedPos());
        if (HologramStates.nameOf(shown).isEmpty())
            return InteractionResult.FAIL;

        if (Holograms.capacityLeft(server) <= 0) {
            player.displayClientMessage(
                    Component.literal("This dimension has reached its hologram limit.").withStyle(ChatFormatting.RED), true);
            return InteractionResult.FAIL;
        }

        // The clicked block is the data source: a chest keeps its contents and open state, a sign its text.
        HologramSpec created = Holograms.create(server, target, shown, context.getClickedPos(), player);
        server.playSound(null, target, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.BLOCKS, 0.7F, 1.2F);
        HologramNetwork.openEditor((ServerPlayer) player, created);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel server))
            return InteractionResultHolder.success(player.getItemInHand(hand));

        HologramSpec hit = pick(server, player);
        if (hit == null)
            return InteractionResultHolder.pass(player.getItemInHand(hand));

        useOnHologram(server, player, hit);
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }

    private static InteractionResult useOnHologram(ServerLevel server, Player player, HologramSpec spec) {
        if (player.isShiftKeyDown()) {
            Holograms.remove(server, spec.id());
            server.playSound(null, spec.pos(), SoundEvents.BEACON_DEACTIVATE, SoundSource.BLOCKS, 0.7F, 1.35F);
            player.displayClientMessage(
                    Component.literal("Removed hologram '" + spec.id() + "'.").withStyle(ChatFormatting.GRAY), true);
            return InteractionResult.CONSUME;
        }

        HologramNetwork.openEditor((ServerPlayer) player, spec);
        return InteractionResult.CONSUME;
    }

    private static HologramSpec pick(ServerLevel server, Player player) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getViewVector(1.0F);
        return HologramPick.pick(eye, look, Holograms.REACH, Holograms.list(server));
    }
}
