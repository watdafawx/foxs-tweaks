package dev.watdafawx.foxstweaks.mixin;

import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lets {@link SkewerRecipesMixin} read Kaleidoscope Grilling's display map without a compile dependency. */
@Mixin(targets = "cn.breezeth.kaleidoscope_grilling.data.GrillingDataManager", remap = false)
public interface GrillingDataManagerInvoker {
    @Invoker("skewersForDisplay")
    static Map<String, ?> foxstweaks$skewersForDisplay() {
        throw new AssertionError();
    }
}
