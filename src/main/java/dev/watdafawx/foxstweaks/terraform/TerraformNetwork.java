package dev.watdafawx.foxstweaks.terraform;

import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.List;

/**
 * Network payloads + lifecycle hooks for the Biome Changer and Terrain Flattener tools.
 */
public final class TerraformNetwork {
    public static final String PROTOCOL = "1";

    public record ExactBiomeSyncPayload(
            ResourceKey<Level> dimension,
            boolean fullReplace,
            List<ExactBiomes.ChunkPacketData> chunks
    ) implements CustomPacketPayload {
        public static final Type<ExactBiomeSyncPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "exact_biomes_sync"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ExactBiomeSyncPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ResourceKey.streamCodec(Registries.DIMENSION), ExactBiomeSyncPayload::dimension,
                        ByteBufCodecs.BOOL, ExactBiomeSyncPayload::fullReplace,
                        ExactBiomes.ChunkPacketData.STREAM_CODEC.apply(ByteBufCodecs.list()), ExactBiomeSyncPayload::chunks,
                        ExactBiomeSyncPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UpdateBiomeWandPayload(boolean mainHand, BiomeWandConfig config, boolean applyNow) implements CustomPacketPayload {
        public static final Type<UpdateBiomeWandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "update_biome_wand"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateBiomeWandPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, UpdateBiomeWandPayload::mainHand,
                        BiomeWandConfig.STREAM_CODEC, UpdateBiomeWandPayload::config,
                        ByteBufCodecs.BOOL, UpdateBiomeWandPayload::applyNow,
                        UpdateBiomeWandPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record UpdateFlattenWandPayload(boolean mainHand, FlattenWandConfig config, boolean applyNow) implements CustomPacketPayload {
        public static final Type<UpdateFlattenWandPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "update_flatten_wand"));

        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateFlattenWandPayload> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.BOOL, UpdateFlattenWandPayload::mainHand,
                        FlattenWandConfig.STREAM_CODEC, UpdateFlattenWandPayload::config,
                        ByteBufCodecs.BOOL, UpdateFlattenWandPayload::applyNow,
                        UpdateFlattenWandPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record TerraformUndoPayload(boolean isBiome) implements CustomPacketPayload {
        public static final Type<TerraformUndoPayload> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "terraform_undo"));

        public static final StreamCodec<RegistryFriendlyByteBuf, TerraformUndoPayload> STREAM_CODEC =
                StreamCodec.composite(ByteBufCodecs.BOOL, TerraformUndoPayload::isBiome, TerraformUndoPayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void initEvents() {
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> TerrainFlattener.onServerTick());
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) syncAllToPlayer(sp);
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer sp) syncAllToPlayer(sp);
        });
        NeoForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel sl) {
                ExactBiomes.clearServerDimension(sl.dimension());
            }
        });
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar reg = event.registrar(PROTOCOL);
        reg.playToClient(ExactBiomeSyncPayload.TYPE, ExactBiomeSyncPayload.STREAM_CODEC, TerraformNetwork::handleExactSync);
        reg.playToServer(UpdateBiomeWandPayload.TYPE, UpdateBiomeWandPayload.STREAM_CODEC, TerraformNetwork::handleUpdateBiomeWand);
        reg.playToServer(UpdateFlattenWandPayload.TYPE, UpdateFlattenWandPayload.STREAM_CODEC, TerraformNetwork::handleUpdateFlattenWand);
        reg.playToServer(TerraformUndoPayload.TYPE, TerraformUndoPayload.STREAM_CODEC, TerraformNetwork::handleUndo);
    }

    public static void syncAllToPlayer(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        List<ExactBiomes.ChunkPacketData> all = ExactBiomes.get(level).allPacketData();
        PacketDistributor.sendToPlayer(player, new ExactBiomeSyncPayload(level.dimension(), true, all));
    }

    public static void broadcastExactBiomes(ServerLevel level, boolean fullReplace, List<ExactBiomes.ChunkPacketData> updates) {
        PacketDistributor.sendToPlayersInDimension(level, new ExactBiomeSyncPayload(level.dimension(), fullReplace, updates));
    }

    private static void handleExactSync(ExactBiomeSyncPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            ExactBiomes.applyClientSync(payload.dimension(), payload.fullReplace(), payload.chunks());
            if (FMLEnvironment.dist.isClient()) {
                dev.watdafawx.foxstweaks.client.terraform.TerraformClient.onExactBiomesSynced(payload.chunks());
            }
        });
    }

    private static void handleUpdateBiomeWand(UpdateBiomeWandPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = sp.getItemInHand(hand);
            if (!(stack.getItem() instanceof BiomeWandItem)) return;
            BiomeWandItem.setConfig(stack, payload.config());
            if (payload.applyNow()) {
                BiomeChanger.apply(sp, payload.config());
            }
        });
    }

    private static void handleUpdateFlattenWand(UpdateFlattenWandPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            InteractionHand hand = payload.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
            ItemStack stack = sp.getItemInHand(hand);
            if (!(stack.getItem() instanceof FlattenWandItem)) return;
            FlattenWandItem.setConfig(stack, payload.config());
            if (payload.applyNow()) {
                TerrainFlattener.enqueue(sp, payload.config());
            }
        });
    }

    private static void handleUndo(TerraformUndoPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (payload.isBiome()) {
                BiomeChanger.undo(sp);
            } else {
                TerrainFlattener.undo(sp);
            }
        });
    }

    private TerraformNetwork() {
    }
}
