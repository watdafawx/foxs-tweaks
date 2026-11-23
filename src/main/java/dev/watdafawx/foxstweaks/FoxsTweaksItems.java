package dev.watdafawx.foxstweaks;

import dev.watdafawx.foxstweaks.anchor.SpawnAnchorItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Mod items registered unconditionally. */
public final class FoxsTweaksItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(FoxsTweaks.MODID);

    public static final DeferredItem<Item> SPAWN_ANCHOR = ITEMS.register("spawn_anchor",
            () -> new SpawnAnchorItem(new Item.Properties().stacksTo(16)));

    public static void init(IEventBus modBus) {
        ITEMS.register(modBus);
        modBus.addListener(FoxsTweaksItems::onCreativeTabs);
    }

    private static void onCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(SPAWN_ANCHOR.get());
        }
    }

    private FoxsTweaksItems() {
    }
}
