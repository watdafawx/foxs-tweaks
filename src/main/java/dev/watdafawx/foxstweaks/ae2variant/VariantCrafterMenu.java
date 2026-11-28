package dev.watdafawx.foxstweaks.ae2variant;

import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantics;
import appeng.menu.guisync.GuiSync;
import appeng.menu.slot.FakeSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.Level;

/**
 * AE2 menu for the ME Variant Crafter. Exposes 63 fake config slots (matching AE2's Storage Bus
 * layout), live synced mode/source/pattern counters, and client-to-server button actions.
 */
public class VariantCrafterMenu extends AEBaseMenu {
    private static final String ACTION_CYCLE_MODE = "cycleMode";
    private static final String ACTION_TOGGLE_SOURCE = "toggleSource";
    private static final String ACTION_CLEAR = "clearFilters";

    private final VariantCrafterBlockEntity host;

    @GuiSync(0)
    public int filterModeId = 0;
    @GuiSync(1)
    public int enabledSources = VariantSource.defaultMask();
    @GuiSync(2)
    public int activePatternCount = 0;
    @GuiSync(3)
    public int chippedCount = 0;
    @GuiSync(4)
    public int rechiseledCount = 0;
    @GuiSync(5)
    public int chiselCount = 0;
    @GuiSync(6)
    public int factoryCount = 0;
    @GuiSync(7)
    public int stonecutterCount = 0;

    public VariantCrafterMenu(int id, Inventory playerInventory, VariantCrafterBlockEntity host) {
        super(Ae2VariantIntegration.MENU_TYPE.get(), id, playerInventory, host);
        this.host = host;

        var configWrapper = host.getFilterInventory().createMenuWrapper();
        for (int i = 0; i < VariantCrafterBlockEntity.FILTER_SLOTS; i++) {
            addSlot(new FakeSlot(configWrapper, i), SlotSemantics.CONFIG);
        }
        createPlayerInventorySlots(playerInventory);

        registerClientAction(ACTION_CYCLE_MODE, this::cycleMode);
        registerClientAction(ACTION_TOGGLE_SOURCE, Integer.class, this::toggleSource);
        registerClientAction(ACTION_CLEAR, this::clearFilters);
    }

    @Override
    public void broadcastChanges() {
        if (isServerSide()) {
            filterModeId = host.getFilterMode().id;
            enabledSources = host.getEnabledSources();
            activePatternCount = host.getActivePatternCount();
            Level lvl = host.getLevel();
            if (lvl != null) {
                chippedCount = VariantRegistry.getSourceCount(lvl, VariantSource.CHIPPED);
                rechiseledCount = VariantRegistry.getSourceCount(lvl, VariantSource.RECHISELED);
                chiselCount = VariantRegistry.getSourceCount(lvl, VariantSource.CHISEL);
                factoryCount = VariantRegistry.getSourceCount(lvl, VariantSource.FACTORY_BLOCKS);
                stonecutterCount = VariantRegistry.getSourceCount(lvl, VariantSource.STONECUTTER);
            }
        }
        super.broadcastChanges();
    }

    public VariantRegistry.FilterMode getFilterMode() {
        return VariantRegistry.FilterMode.byId(filterModeId);
    }

    public boolean isSourceEnabled(VariantSource source) {
        return source.isEnabled(enabledSources);
    }

    public int getSourceCount(VariantSource source) {
        return switch (source) {
            case CHIPPED -> chippedCount;
            case RECHISELED -> rechiseledCount;
            case CHISEL -> chiselCount;
            case FACTORY_BLOCKS -> factoryCount;
            case STONECUTTER -> stonecutterCount;
            case CUSTOM_TAGS -> 0;
        };
    }

    public void cycleMode() {
        if (isClientSide()) {
            sendClientAction(ACTION_CYCLE_MODE);
            return;
        }
        host.setFilterMode(host.getFilterMode().next());
    }

    public void toggleSource(int sourceIndex) {
        if (isClientSide()) {
            sendClientAction(ACTION_TOGGLE_SOURCE, sourceIndex);
            return;
        }
        host.toggleSource(VariantSource.byIndex(sourceIndex));
    }

    public void clearFilters() {
        if (isClientSide()) {
            sendClientAction(ACTION_CLEAR);
            return;
        }
        host.clearFilters();
    }
}
