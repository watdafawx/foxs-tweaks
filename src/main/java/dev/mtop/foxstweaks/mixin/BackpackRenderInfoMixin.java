package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import dev.mtop.foxstweaks.Config;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Sophisticated Backpacks builds a fresh {@code BackpackRenderInfo} from a backpack stack every time the stack is
 * drawn: the item model override, the item renderer and the worn-backpack layer each call
 * {@code fromPhysicalStack}, which deep-copies the render tag and re-parses it, decoding every upgrade item through
 * the {@code ItemStack} codec. On a hotbar with backpacks that ran several times per frame.
 *
 * <p>All three callers only read the result, so it is cached per render-info component. Data components are
 * immutable and replaced whenever the backpack's render data changes, so the component instance is a correct key,
 * and identity ({@code weakKeys}) avoids deep-hashing the tag. The cache is bounded because each value holds its
 * stack, which keeps the weak key reachable. A stack without the component (legacy data, migrated onto the stack
 * while deserializing) takes the original path.
 */
@Mixin(targets = "net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.BackpackRenderInfo", remap = false)
public abstract class BackpackRenderInfoMixin {
    @Unique private static final ResourceLocation foxstweaks$RENDER_INFO_ID =
            ResourceLocation.fromNamespaceAndPath("sophisticatedcore", "render_info_tag");
    @Unique private static final Cache<Object, Object> foxstweaks$cache =
            CacheBuilder.newBuilder().weakKeys().maximumSize(256).build();
    @Unique private static DataComponentType<?> foxstweaks$renderInfoType;

    @Unique
    private static Object foxstweaks$key(ItemStack stack) {
        if (!Config.BACKPACK_RENDER_INFO_CACHE.get()) {
            return null;
        }
        if (foxstweaks$renderInfoType == null) {
            foxstweaks$renderInfoType = BuiltInRegistries.DATA_COMPONENT_TYPE.get(foxstweaks$RENDER_INFO_ID);
            if (foxstweaks$renderInfoType == null) {
                return null;
            }
        }
        return stack.get(foxstweaks$renderInfoType);
    }

    @Inject(method = "fromPhysicalStack", at = @At("HEAD"), cancellable = true)
    private static void foxstweaks$cached(ItemStack backpack, CallbackInfoReturnable<Object> cir) {
        Object key = foxstweaks$key(backpack);
        if (key != null) {
            Object cached = foxstweaks$cache.getIfPresent(key);
            if (cached != null) {
                cir.setReturnValue(cached);
            }
        }
    }

    @Inject(method = "fromPhysicalStack", at = @At("RETURN"))
    private static void foxstweaks$store(ItemStack backpack, CallbackInfoReturnable<Object> cir) {
        Object key = foxstweaks$key(backpack);
        if (key != null) {
            foxstweaks$cache.put(key, cir.getReturnValue());
        }
    }
}
