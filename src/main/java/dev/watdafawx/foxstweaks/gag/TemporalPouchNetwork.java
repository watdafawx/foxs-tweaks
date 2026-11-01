package dev.watdafawx.foxstweaks.gag;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Client -> server: use the Temporal Pouch from the sender's Curios slot on the block they are looking at, as if it
 * were right-clicked from the main hand. The server does its own reach-checked ray trace; the packet carries nothing.
 * Registered unconditionally like the other channels; Curios types are only reached through {@link TemporalPouchCurio},
 * behind the same {@code ModList} checks that gate its registration.
 */
public final class TemporalPouchNetwork {
    private TemporalPouchNetwork() {
    }

    public record UsePouchPayload() implements CustomPacketPayload {
        public static final UsePouchPayload INSTANCE = new UsePouchPayload();
        public static final Type<UsePouchPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "use_temporal_pouch"));
        public static final StreamCodec<ByteBuf, UsePouchPayload> CODEC = StreamCodec.unit(INSTANCE);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToServer(UsePouchPayload.TYPE, UsePouchPayload.CODEC, (payload, context) -> context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player)
                        use(player);
                }));
    }

    private static void use(ServerPlayer player) {
        if (!Config.TEMPORAL_POUCH_CURIO.get() || !isAvailable() || player.isSpectator())
            return;

        ItemStack pouch = TemporalPouchCurio.findEquipped(player);

        if (pouch.isEmpty())
            return;

        HitResult hit = player.pick(player.blockInteractionRange(), 1.0F, false);

        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK)
            return;

        if (!player.canInteractWithBlock(blockHit.getBlockPos(), 1.0) || !player.level().mayInteract(player, blockHit.getBlockPos()))
            return;

        // The pouch reads itself from ctx.getItemInHand(), so hand it the curio stack directly; the hand is nominal.
        UseOnContext context = new UseOnContext(player.level(), player, InteractionHand.MAIN_HAND, pouch, blockHit) {
        };
        InteractionResult result = pouch.useOn(context);

        if (result.shouldSwing())
            player.swing(InteractionHand.MAIN_HAND, true);
    }

    public static boolean isAvailable() {
        ModList mods = ModList.get();
        return mods.isLoaded(FoxsTweaks.GAG) && mods.isLoaded(FoxsTweaks.CURIOS);
    }
}
