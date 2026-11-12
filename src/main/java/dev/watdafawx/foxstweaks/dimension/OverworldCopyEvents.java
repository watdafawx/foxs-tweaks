package dev.watdafawx.foxstweaks.dimension;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.MapCodec;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.conditions.ICondition;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Lighting and dyeing the portals to the overworld copy, plus the datapack condition and the colour
 * sync. Holds no optional-mod types, so it is wired up unconditionally from {@code FoxsTweaks}.
 */
public final class OverworldCopyEvents {
    private static final DeferredRegister<MapCodec<? extends ICondition>> CONDITIONS =
            DeferredRegister.create(NeoForgeRegistries.CONDITION_SERIALIZERS, FoxsTweaks.MODID);

    /** {@code {"type": "foxstweaks:overworld_copy"}}: true while the overworldCopy config is on. */
    public record EnabledCondition() implements ICondition {
        public static final MapCodec<EnabledCondition> CODEC = MapCodec.unit(new EnabledCondition());

        @Override
        public boolean test(IContext context) {
            return OverworldCopy.enabled();
        }

        @Override
        public MapCodec<? extends ICondition> codec() {
            return CODEC;
        }
    }

    public static void init(IEventBus modBus) {
        CONDITIONS.register("overworld_copy", () -> EnabledCondition.CODEC);
        CONDITIONS.register(modBus);
        modBus.addListener(OverworldCopyEvents::registerPayloads);
        NeoForge.EVENT_BUS.register(OverworldCopyEvents.class);
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .optional()
                .playToClient(PortalColorsPayload.TYPE, PortalColorsPayload.CODEC,
                        (payload, context) -> context.enqueueWork(() -> ClientPortalColors.receive(payload)));
    }

    // ---------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onRightClick(PlayerInteractEvent.RightClickBlock event) {
        if (!OverworldCopy.enabled()) {
            return;
        }

        ItemStack stack = event.getItemStack();
        Level level = event.getLevel();

        if (stack.getItem() instanceof DyeItem dye && Config.OVERWORLD_COPY_DYE.get()) {
            var shape = CopyPortals.shapeAround(level, event.getPos(), level.getBlockState(event.getPos()));

            if (shape.isPresent()) {
                if (level instanceof ServerLevel server) {
                    int id = dye.getDyeColor().getId();
                    var colors = PortalColors.get(server);

                    if (colors.dyeAt(event.getPos()) != id) {
                        colors.set(shape.get(), id);
                        sync(server);
                        level.playSound(null, event.getPos(), SoundEvents.DYE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);

                        if (!event.getEntity().getAbilities().instabuild) {
                            stack.shrink(1);
                        }
                    }
                }

                consume(event, level);
                return;
            }
        }

        boolean flint = stack.is(Items.FLINT_AND_STEEL);

        if ((!flint && !stack.is(Items.FIRE_CHARGE)) || event.getFace() == null) {
            return;
        }

        BlockPos fire = event.getPos().relative(event.getFace());

        if (!level.getBlockState(fire).isAir()) {
            return;
        }

        Optional<CopyShape> shape = CopyShape.find(level, fire, Direction.Axis.X);

        if (shape.isEmpty()) {
            return;
        }

        if (level instanceof ServerLevel server) {
            CopyPortals.fill(server, shape.get());
            PortalColors.get(server).set(shape.get(), -1);
            sync(server);
            level.playSound(null, fire, flint ? SoundEvents.FLINTANDSTEEL_USE : SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 1.0F,
                    level.getRandom().nextFloat() * 0.4F + 0.8F);

            if (flint) {
                stack.hurtAndBreak(1, event.getEntity(), LivingEntity.getSlotForHand(event.getHand()));
            } else if (!event.getEntity().getAbilities().instabuild) {
                stack.shrink(1);
            }
        }

        consume(event, level);
    }

    private static void consume(PlayerInteractEvent.RightClickBlock event, Level level) {
        event.setCancellationResult(InteractionResult.sidedSuccess(level.isClientSide));
        event.setCanceled(true);
    }

    // ---------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sendTo(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sendTo(event.getEntity());
    }

    @SubscribeEvent
    public static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        sendTo(event.getEntity());
    }

    private static void sendTo(net.minecraft.world.entity.player.Player player) {
        if (player instanceof ServerPlayer sp && OverworldCopy.enabled()) {
            send(sp, payload(sp.serverLevel()));
        }
    }

    /** Pushes the dimension's dyed portals to everyone in it. */
    public static void sync(ServerLevel level) {
        PortalColorsPayload payload = payload(level);

        for (ServerPlayer player : level.players()) {
            send(player, payload);
        }
    }

    private static PortalColorsPayload payload(ServerLevel level) {
        PortalColors colors = PortalColors.get(level);
        colors.prune(level);
        return new PortalColorsPayload(level.dimension(), List.copyOf(colors.entries()));
    }

    private static void send(ServerPlayer player, PortalColorsPayload payload) {
        if (player.connection.hasChannel(PortalColorsPayload.TYPE)) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    private OverworldCopyEvents() {
    }
}
