package dev.watdafawx.foxstweaks.emi;

import java.util.concurrent.atomic.AtomicLong;

import com.google.common.collect.Interner;
import com.google.common.collect.Interners;

import net.minecraft.core.component.DataComponentPatch;

/**
 * Identical component patches on EMI item stacks share one object. Weak interner, so patches nobody uses any more
 * can still be collected. Reset on every EMI reload, so patches never outlive the registries they point into.
 */
public final class PatchInterner {
    private static volatile Interner<DataComponentPatch> interner = Interners.newWeakInterner();
    private static final AtomicLong calls = new AtomicLong();
    private static final AtomicLong replaced = new AtomicLong();
    private static final AtomicLong errors = new AtomicLong();

    public static DataComponentPatch intern(DataComponentPatch patch) {
        if (!EmiSlim.internPatches || patch == null || patch == DataComponentPatch.EMPTY) {
            return patch;
        }

        if (patch.isEmpty()) {
            return DataComponentPatch.EMPTY;
        }

        calls.incrementAndGet();
        try {
            var canon = interner.intern(patch);
            if (canon != patch) {
                replaced.incrementAndGet();
            }

            return canon;
        } catch (Throwable t) {
            // A component with a broken equals/hashCode: keep the original.
            if (errors.getAndIncrement() < 3) {
                EmiSlim.LOG.warn("[foxstweaks] EMI Slim: could not intern component patch {}", patch, t);
            }

            return patch;
        }
    }

    static void reset() {
        interner = Interners.newWeakInterner();
    }

    static String stats() {
        return "patches interned " + calls.get() + ", replaced by shared copy " + replaced.get() + ", errors " + errors.get();
    }

    private PatchInterner() {
    }
}
