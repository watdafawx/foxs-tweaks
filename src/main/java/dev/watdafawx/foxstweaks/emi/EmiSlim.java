package dev.watdafawx.foxstweaks.emi;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.stack.ListEmiIngredient;
import dev.emi.emi.api.stack.TagEmiIngredient;
import dev.watdafawx.foxstweaks.Config;

/**
 * EMI Slim, merged in from the standalone mod of that name (0.1.1): cuts EMI 1.1.24's duplicated item data.
 * {@link TagShare} gives every TagEmiIngredient of a tag one shared stack list; {@link PatchInterner} makes
 * identical component patches on EMI item stacks one object. On TNP Limitless 8 that was -470 MB of live heap.
 *
 * <p>Both toggles are read once at the start of every EMI reload ({@link #latch}, from
 * {@code EmiSlimStackListMixin}), never in between: turning tag sharing off while shared lists exist would stop
 * owner tracking and let a recipe's change land on the wrong recipe. A config change applies on the next EMI
 * reload (F3+T or rejoining).
 *
 * <p>Only reached from EMI mixins, so it may import EMI types.
 */
public final class EmiSlim {
    static final Logger LOG = LogUtils.getLogger();

    /** Whether TagShare shares lists in the current EMI load. */
    static volatile boolean shareTags;
    /** Whether PatchInterner interns in the current EMI load. */
    static volatile boolean internPatches;

    private static Boolean shareMixinsApplied;

    /** Called at the start of every EMI reload, before anything is built. */
    public static void latch() {
        var share = Config.enabled(Config.EMI_SHARE_TAG_LISTS) && shareMixinsApplied();
        var intern = Config.enabled(Config.EMI_INTERN_PATCHES);
        if (share != shareTags || intern != internPatches) {
            LOG.info("[foxstweaks] EMI Slim: shareTagLists={}, internPatches={}", share, intern);
        }

        shareTags = share;
        internPatches = intern;
        TagShare.clear();
        PatchInterner.reset();
    }

    /**
     * Sharing is only safe with the owner tracking in place: if an EMI update broke some of these mixins but not
     * {@code EmiTags#getValues}, a recipe changing one of its stacks would change it for every recipe of the tag.
     * Mixin config failures are non-fatal here, so check rather than assume.
     */
    private static boolean shareMixinsApplied() {
        if (shareMixinsApplied == null) {
            shareMixinsApplied = SharedStack.class.isAssignableFrom(EmiStack.class)
                    && TagOwner.class.isAssignableFrom(TagEmiIngredient.class)
                    && ListOwner.class.isAssignableFrom(ListEmiIngredient.class);
            if (!shareMixinsApplied) {
                LOG.warn("[foxstweaks] EMI Slim: tag list sharing off, its EMI mixins did not all apply (EMI version changed?)");
            }
        }

        return shareMixinsApplied;
    }

    public static void logStats() {
        if (shareTags || internPatches) {
            LOG.info("[foxstweaks] EMI Slim: {}; {}", TagShare.stats(), PatchInterner.stats());
        }
    }

    private EmiSlim() {
    }
}
