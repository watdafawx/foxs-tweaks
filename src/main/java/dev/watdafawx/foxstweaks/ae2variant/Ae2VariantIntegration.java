package dev.watdafawx.foxstweaks.ae2variant;

import appeng.api.AECapabilities;
import appeng.block.AEBaseBlockItem;
import appeng.menu.implementations.MenuTypeBuilder;
import dev.watdafawx.foxstweaks.FoxsTweaks;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Optional AE2 integration entry point. Only loaded when {@code ae2} is installed.
 */
public final class Ae2VariantIntegration {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(FoxsTweaks.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FoxsTweaks.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, FoxsTweaks.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, FoxsTweaks.MODID);

    public static final DeferredBlock<VariantCrafterBlock> BLOCK =
            BLOCKS.register("me_variant_crafter", VariantCrafterBlock::new);

    public static final DeferredItem<Item> BLOCK_ITEM =
            ITEMS.register("me_variant_crafter",
                    () -> new AEBaseBlockItem(BLOCK.get(), new Item.Properties()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<VariantCrafterBlockEntity>> BLOCK_ENTITY =
            BLOCK_ENTITIES.register("me_variant_crafter", () ->
                    BlockEntityType.Builder.of(
                            (pos, state) -> new VariantCrafterBlockEntity(Ae2VariantIntegration.BLOCK_ENTITY.get(), pos, state),
                            BLOCK.get()
                    ).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<VariantCrafterMenu>> MENU_TYPE =
            MENUS.register("me_variant_crafter", () ->
                    MenuTypeBuilder.create(VariantCrafterMenu::new, VariantCrafterBlockEntity.class)
                            .buildUnregistered(ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "me_variant_crafter")));

    private Ae2VariantIntegration() {
    }

    public static void init(IEventBus modBus) {
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);

        modBus.addListener(Ae2VariantIntegration::onCommonSetup);
        modBus.addListener(Ae2VariantIntegration::onRegisterCapabilities);
        modBus.addListener(Ae2VariantIntegration::onCreativeTabs);

        NeoForge.EVENT_BUS.addListener((TagsUpdatedEvent e) -> VariantRegistry.invalidate());
        NeoForge.EVENT_BUS.addListener((OnDatapackSyncEvent e) -> VariantRegistry.invalidate());
        NeoForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> VariantRegistry.invalidate());

        if (FMLEnvironment.dist.isClient()) {
            dev.watdafawx.foxstweaks.ae2variant.client.Ae2VariantClient.init(modBus);
        }
    }

    private static void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> BLOCK.get().setBlockEntity(
                VariantCrafterBlockEntity.class,
                BLOCK_ENTITY.get(),
                null,
                null));
    }

    private static void onRegisterCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(
                AECapabilities.IN_WORLD_GRID_NODE_HOST,
                BLOCK_ENTITY.get(),
                (be, ctx) -> be);
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(BLOCK_ITEM.get());
        }
    }
}
