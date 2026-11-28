package dev.watdafawx.foxstweaks.ae2variant;

import appeng.api.config.Actionable;
import appeng.api.crafting.IPatternDetails;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
import appeng.api.networking.IManagedGridNode;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.StorageHelper;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.util.ConfigInventory;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * AE2 BlockEntity that exposes block-variant conversions to the ME crafting network and
 * executes pushed crafting patterns internally without external pattern providers.
 */
public class VariantCrafterBlockEntity extends AENetworkedBlockEntity
        implements ICraftingProvider, IGridTickable {

    public static final int FILTER_SLOTS = 63;
    private static final int MAX_PENDING = 256;
    private static final double POWER_PER_CRAFT = 1.0;

    private final IActionSource actionSource = IActionSource.ofMachine(this);
    private final ConfigInventory filterInv = ConfigInventory.configTypes(FILTER_SLOTS)
            .supportedType(AEKeyType.items())
            .changeListener(this::onFilterChanged)
            .build();

    private VariantRegistry.FilterMode filterMode = VariantRegistry.FilterMode.FAMILY_FILTER;
    private int enabledSources = VariantSource.defaultMask();

    private final List<GenericStack> pendingOutputs = new ArrayList<>();
    private List<IPatternDetails> cachedPatterns = null;
    private int lastStockHash = 0;

    public VariantCrafterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    protected IManagedGridNode createMainNode() {
        return super.createMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(1.0)
                .addService(ICraftingProvider.class, this)
                .addService(IGridTickable.class, this);
    }

    public ConfigInventory getFilterInventory() {
        return filterInv;
    }

    public VariantRegistry.FilterMode getFilterMode() {
        return filterMode;
    }

    public void setFilterMode(VariantRegistry.FilterMode mode) {
        if (this.filterMode == mode) return;
        this.filterMode = mode;
        invalidatePatterns();
        saveChanges();
        wakeTicking();
    }

    public int getEnabledSources() {
        return enabledSources;
    }

    public void toggleSource(VariantSource source) {
        this.enabledSources ^= source.bit();
        invalidatePatterns();
        saveChanges();
    }

    public void clearFilters() {
        boolean changed = false;
        for (int i = 0; i < filterInv.size(); i++) {
            if (filterInv.getKey(i) != null) {
                filterInv.setStack(i, null);
                changed = true;
            }
        }
        if (changed) {
            onFilterChanged();
        }
    }

    public int getActivePatternCount() {
        return getAvailablePatterns().size();
    }

    private void onFilterChanged() {
        invalidatePatterns();
        saveChanges();
    }

    public void invalidatePatterns() {
        this.cachedPatterns = null;
        if (getMainNode().isReady()) {
            ICraftingProvider.requestUpdate(getMainNode());
        }
    }

    private void wakeTicking() {
        IGrid grid = getMainNode().getGrid();
        IGridNode node = getMainNode().getNode();
        if (grid != null && node != null) {
            grid.getTickManager().alertDevice(node);
        }
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        super.onMainNodeStateChanged(reason);
        invalidatePatterns();
        if (filterMode == VariantRegistry.FilterMode.IN_STOCK || !pendingOutputs.isEmpty()) {
            wakeTicking();
        }
    }

    @Override
    public List<IPatternDetails> getAvailablePatterns() {
        if (!getMainNode().isActive() || level == null) {
            return Collections.emptyList();
        }
        List<IPatternDetails> cached = this.cachedPatterns;
        if (cached != null) {
            return cached;
        }

        Set<Item> filterItems = collectFilterItems();
        Set<Item> stockItems = filterMode == VariantRegistry.FilterMode.IN_STOCK
                ? collectStockItems()
                : Collections.emptySet();

        List<VariantRegistry.VariantConversion> conversions =
                VariantRegistry.query(level, enabledSources, filterMode, filterItems, stockItems);

        Item defIcon = Ae2VariantIntegration.BLOCK_ITEM.get();
        List<IPatternDetails> patterns = new ArrayList<>(conversions.size());
        for (VariantRegistry.VariantConversion conv : conversions) {
            patterns.add(new VariantPatternDetails(defIcon, conv));
        }
        this.cachedPatterns = patterns;
        return patterns;
    }

    private Set<Item> collectFilterItems() {
        Set<Item> items = new HashSet<>();
        for (int i = 0; i < filterInv.size(); i++) {
            AEKey key = filterInv.getKey(i);
            if (key instanceof AEItemKey itemKey) {
                items.add(itemKey.getItem());
            }
        }
        return items;
    }

    private Set<Item> collectStockItems() {
        IGrid grid = getMainNode().getGrid();
        if (grid == null) return Collections.emptySet();
        KeyCounter cached = grid.getStorageService().getCachedInventory();
        Set<Item> items = new HashSet<>();
        for (var entry : cached) {
            if (entry.getLongValue() > 0 && entry.getKey() instanceof AEItemKey itemKey) {
                items.add(itemKey.getItem());
            }
        }
        return items;
    }

    @Override
    public boolean pushPattern(IPatternDetails patternDetails, KeyCounter[] inputHolder) {
        if (!getMainNode().isActive() || isBusy()) return false;
        if (!(patternDetails instanceof VariantPatternDetails)) return false;

        IGrid grid = getMainNode().getGrid();
        if (grid == null) return false;

        double extracted = grid.getEnergyService().extractAEPower(
                POWER_PER_CRAFT, Actionable.MODULATE, appeng.api.config.PowerMultiplier.CONFIG);
        if (extracted < POWER_PER_CRAFT * 0.5) {
            return false;
        }

        // Queue outputs to be pushed back into the ME network on the next tick (safe from re-entrancy).
        for (GenericStack out : patternDetails.getOutputs()) {
            if (out != null && out.amount() > 0) {
                pendingOutputs.add(out);
            }
        }
        wakeTicking();
        return true;
    }

    @Override
    public boolean isBusy() {
        return pendingOutputs.size() >= MAX_PENDING;
    }

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 40, pendingOutputs.isEmpty() && filterMode != VariantRegistry.FilterMode.IN_STOCK);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        IGrid grid = getMainNode().getGrid();
        if (grid == null || !getMainNode().isActive()) {
            return TickRateModulation.SLEEP;
        }

        if (!pendingOutputs.isEmpty()) {
            var storage = grid.getStorageService().getInventory();
            var energy = grid.getEnergyService();
            Iterator<GenericStack> it = pendingOutputs.iterator();
            while (it.hasNext()) {
                GenericStack stack = it.next();
                long inserted = StorageHelper.poweredInsert(
                        energy, storage, stack.what(), stack.amount(), actionSource, Actionable.MODULATE);
                if (inserted >= stack.amount()) {
                    it.remove();
                } else if (inserted > 0) {
                    // Replace with remaining amount if storage filled mid-craft.
                    int idx = pendingOutputs.indexOf(stack);
                    if (idx >= 0) {
                        pendingOutputs.set(idx, new GenericStack(stack.what(), stack.amount() - inserted));
                    }
                    break;
                } else {
                    break;
                }
            }
            if (!pendingOutputs.isEmpty()) {
                return TickRateModulation.SLOWER;
            }
        }

        if (filterMode == VariantRegistry.FilterMode.IN_STOCK) {
            int hash = collectStockItems().hashCode();
            if (hash != lastStockHash) {
                lastStockHash = hash;
                invalidatePatterns();
            }
            return TickRateModulation.SLOWER;
        }

        return TickRateModulation.SLEEP;
    }

    @Override
    public void saveAdditional(CompoundTag data, HolderLookup.Provider registries) {
        super.saveAdditional(data, registries);
        data.putInt("FilterMode", filterMode.id);
        data.putInt("EnabledSources", enabledSources);
        filterInv.writeToChildTag(data, "FilterInv", registries);

        if (!pendingOutputs.isEmpty()) {
            ListTag pending = new ListTag();
            for (GenericStack stack : pendingOutputs) {
                CompoundTag tag = GenericStack.writeTag(registries, stack);
                if (tag != null) pending.add(tag);
            }
            data.put("PendingOutputs", pending);
        }
    }

    @Override
    public void loadTag(CompoundTag data, HolderLookup.Provider registries) {
        super.loadTag(data, registries);
        if (data.contains("FilterMode")) {
            filterMode = VariantRegistry.FilterMode.byId(data.getInt("FilterMode"));
        }
        if (data.contains("EnabledSources")) {
            enabledSources = data.getInt("EnabledSources");
        }
        filterInv.readFromChildTag(data, "FilterInv", registries);

        pendingOutputs.clear();
        if (data.contains("PendingOutputs", Tag.TAG_LIST)) {
            ListTag pending = data.getList("PendingOutputs", Tag.TAG_COMPOUND);
            for (int i = 0; i < pending.size(); i++) {
                GenericStack stack = GenericStack.readTag(registries, pending.getCompound(i));
                if (stack != null && stack.amount() > 0) {
                    pendingOutputs.add(stack);
                }
            }
        }
        cachedPatterns = null;
    }
}
