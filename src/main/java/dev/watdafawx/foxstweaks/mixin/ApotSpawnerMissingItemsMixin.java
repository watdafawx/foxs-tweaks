package dev.watdafawx.foxstweaks.mixin;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * ApotSpawner keeps a spawner's stored loot and drop statistics as raw NBT and re-parses every entry
 * each time it is read - and its item handler reads it constantly when pipes or hoppers are attached.
 * An entry whose item came from a since-removed mod never parses, is never dropped, and logs
 * {@code Tried to load invalid item} on every read (tens of thousands of lines an hour).
 *
 * <p>The first time a spawner's data is read this session, every entry that fails to parse is removed,
 * the same as vanilla does for a chest: the item is already lost, this just stops it being retried.
 * Against ApotSpawner 1.2.0 - re-check on update.
 */
@Mixin(targets = "com.beyondtheorder.apotspawner.storage.SpawnerStorage", remap = false)
public abstract class ApotSpawnerMissingItemsMixin {
    private static final Set<BlockEntity> foxstweaks$scrubbed = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Shadow @Final private BlockEntity blockEntity;

    @Inject(method = "root", at = @At("RETURN"))
    private void foxstweaks$dropMissingItems(CallbackInfoReturnable<CompoundTag> cir) {
        if (!Config.APOTSPAWNER_DROP_MISSING_ITEMS.get() || blockEntity.getLevel() == null || !foxstweaks$scrubbed.add(blockEntity))
            return;
        HolderLookup.Provider registries = blockEntity.getLevel().registryAccess();
        CompoundTag root = cir.getReturnValue();
        int removed = foxstweaks$scrub(root.getList("items", Tag.TAG_COMPOUND), registries)
                + foxstweaks$scrub(root.getCompound("statistics").getList("items", Tag.TAG_COMPOUND), registries);
        // root() hands back the live tag when the spawner has data, so the removals are already in place.
        if (removed > 0)
            blockEntity.setChanged();
    }

    private static int foxstweaks$scrub(ListTag entries, HolderLookup.Provider registries) {
        int before = entries.size();
        entries.removeIf(entry -> ItemStack.parse(registries, ((CompoundTag) entry).getCompound("stack")).isEmpty());
        return before - entries.size();
    }
}
