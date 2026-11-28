package dev.watdafawx.foxstweaks;

import dev.watdafawx.foxstweaks.anchor.SpawnAnchorItem;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mod items registered unconditionally. */
public final class FoxsTweaksItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FoxsTweaks.MODID);

    private static ItemAttributeModifiers terraformReach() {
        return ItemAttributeModifiers.builder()
                .add(
                        Attributes.BLOCK_INTERACTION_RANGE,
                        new AttributeModifier(
                                ResourceLocation.fromNamespaceAndPath(FoxsTweaks.MODID, "terraform_reach"),
                                28.0,
                                AttributeModifier.Operation.ADD_VALUE
                        ),
                        EquipmentSlotGroup.MAINHAND
                )
                .build();
    }

    public static final DeferredItem<Item> SPAWN_ANCHOR = ITEMS.register("spawn_anchor",
            () -> new SpawnAnchorItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<Item> HOLOGRAM_WAND = ITEMS.register("hologram_wand",
            () -> new dev.watdafawx.foxstweaks.hologram.HologramItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> BIOME_WAND = ITEMS.register("biome_wand",
            () -> new dev.watdafawx.foxstweaks.terraform.BiomeWandItem(new Item.Properties().stacksTo(1).attributes(terraformReach())));

    public static final DeferredItem<Item> TERRAIN_FLATTENER = ITEMS.register("terrain_flattener",
            () -> new dev.watdafawx.foxstweaks.terraform.FlattenWandItem(new Item.Properties().stacksTo(1).attributes(terraformReach())));

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(FoxsTweaksItems::onCreativeTabs);
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SPAWN_ANCHOR.get());
            event.accept(HOLOGRAM_WAND.get());
            event.accept(BIOME_WAND.get());
            event.accept(TERRAIN_FLATTENER.get());
        }
    }

    private FoxsTweaksItems() {
    }
}
