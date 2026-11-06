package dev.watdafawx.foxstweaks.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.emi.emi.api.stack.Comparison;
import dev.emi.emi.api.stack.EmiStack;
import dev.watdafawx.foxstweaks.emi.SharedStack;
import dev.watdafawx.foxstweaks.emi.SharedTagList;
import dev.watdafawx.foxstweaks.emi.TagShare;

/**
 * EMI Slim: before a stack that lives in a shared tag list is changed, split it off from the shared list
 * ({@link TagShare#beforeChange}). The change itself always runs as normal.
 */
@Mixin(value = EmiStack.class, remap = false)
public abstract class EmiSlimStackMixin implements SharedStack {
    @Shadow
    private EmiStack remainder;
    @Shadow
    protected Comparison comparison;
    @Shadow
    protected long amount;
    @Shadow
    protected float chance;

    @Unique
    private SharedTagList foxstweaks$home;

    @Override
    public SharedTagList foxstweaks$getHome() {
        return foxstweaks$home;
    }

    @Override
    public void foxstweaks$setHome(SharedTagList home) {
        foxstweaks$home = home;
    }

    @Inject(method = "setRemainder(Ldev/emi/emi/api/stack/EmiStack;)Ldev/emi/emi/api/stack/EmiStack;", at = @At("HEAD"))
    private void foxstweaks$setRemainder(EmiStack stack, CallbackInfoReturnable<EmiStack> cir) {
        if (foxstweaks$home != null && stack != remainder && !(stack.isEmpty() && remainder.isEmpty())) {
            TagShare.beforeChange((EmiStack) (Object) this);
        }
    }

    @Inject(method = "setAmount(J)Ldev/emi/emi/api/stack/EmiStack;", at = @At("HEAD"))
    private void foxstweaks$setAmount(long value, CallbackInfoReturnable<EmiStack> cir) {
        if (foxstweaks$home != null && value != amount) {
            TagShare.beforeChange((EmiStack) (Object) this);
        }
    }

    @Inject(method = "setChance(F)Ldev/emi/emi/api/stack/EmiStack;", at = @At("HEAD"))
    private void foxstweaks$setChance(float value, CallbackInfoReturnable<EmiStack> cir) {
        if (foxstweaks$home != null && value != chance) {
            TagShare.beforeChange((EmiStack) (Object) this);
        }
    }

    @Inject(method = "comparison(Ldev/emi/emi/api/stack/Comparison;)Ldev/emi/emi/api/stack/EmiStack;", at = @At("HEAD"))
    private void foxstweaks$comparison(Comparison value, CallbackInfoReturnable<EmiStack> cir) {
        if (foxstweaks$home != null && value != comparison) {
            TagShare.beforeChange((EmiStack) (Object) this);
        }
    }

    @Inject(method = "comparison(Ljava/util/function/Function;)Ldev/emi/emi/api/stack/EmiStack;", at = @At("HEAD"))
    private void foxstweaks$comparisonFn(Function<Comparison, Comparison> fn, CallbackInfoReturnable<EmiStack> cir) {
        if (foxstweaks$home != null) {
            TagShare.beforeChange((EmiStack) (Object) this);
        }
    }
}
