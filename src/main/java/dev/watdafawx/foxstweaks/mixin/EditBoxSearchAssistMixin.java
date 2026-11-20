package dev.watdafawx.foxstweaks.mixin;

import java.util.function.Consumer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.watdafawx.foxstweaks.Config;
import dev.watdafawx.foxstweaks.client.search.SearchAssist;
import dev.watdafawx.foxstweaks.client.search.SearchVocab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/**
 * Every search box: grey autocomplete (Tab takes it), a "Did you mean" line for misspelled words (Tab takes
 * that too) and, optionally, the corrected text handed to the box's listener so the misspelling still finds
 * things. What counts as a search box is {@link SearchAssist#isSearchBox}. Client section.
 */
@Mixin(EditBox.class)
public abstract class EditBoxSearchAssistMixin extends AbstractWidget {
    @Shadow private String suggestion;
    @Shadow private Component hint;
    @Shadow private Consumer<String> responder;

    @Shadow public abstract String getValue();
    @Shadow public abstract void setValue(String value);
    @Shadow public abstract void setSuggestion(String suggestion);
    @Shadow public abstract void moveCursorToEnd(boolean select);
    @Shadow public abstract boolean canConsumeInput();

    /** The suggestion we put in the box, so we never overwrite or take one a mod set itself. */
    @Unique private String foxstweaks$ours;
    /** The whole query with its misspelled words fixed, or null. */
    @Unique private String foxstweaks$didYouMean;

    protected EditBoxSearchAssistMixin() {
        super(0, 0, 0, 0, Component.empty());
    }

    @Unique
    private boolean foxstweaks$search() {
        return SearchAssist.isSearchBox((EditBox) (Object) this, hint);
    }

    /** What the listener gets: the corrected query when autocorrect is on and something was misspelled. */
    @ModifyArg(method = "onValueChange", index = 0, require = 0, at = @At(value = "INVOKE",
            target = "Ljava/util/function/Consumer;accept(Ljava/lang/Object;)V"))
    private Object foxstweaks$effectiveQuery(Object text) {
        if (text instanceof String typed && Config.enabled(Config.SEARCH_ASSIST_AUTOCORRECT) && foxstweaks$search()) {
            String fixed = SearchVocab.correct(typed);
            if (fixed != null) {
                return fixed;
            }
        }
        return text;
    }

    @Inject(method = "onValueChange", at = @At("TAIL"), require = 0)
    private void foxstweaks$refresh(String newText, CallbackInfo ci) {
        boolean search = foxstweaks$search();
        String completion = search && !newText.isEmpty() ? SearchVocab.complete(newText) : null;
        if (suggestion == null || suggestion.isEmpty() || suggestion.equals(foxstweaks$ours)) {
            setSuggestion(completion);
            foxstweaks$ours = completion;
        }
        foxstweaks$didYouMean = search && !newText.isEmpty() ? SearchVocab.correct(newText) : null;
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxstweaks$tab(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        if (key != 258 || (modifiers & 1) != 0 || !canConsumeInput()) {
            return;
        }
        boolean hasCompletion = foxstweaks$ours != null && foxstweaks$ours.equals(suggestion);
        if ((!hasCompletion && foxstweaks$didYouMean == null) || !foxstweaks$search()) {
            return;
        }
        setValue(hasCompletion ? getValue() + foxstweaks$ours : foxstweaks$didYouMean);
        moveCursorToEnd(false);
        cir.setReturnValue(true);
    }

    @Inject(method = "renderWidget", at = @At("TAIL"), require = 0)
    private void foxstweaks$drawDidYouMean(GuiGraphics g, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (foxstweaks$didYouMean == null || !isFocused()) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.drawString(Minecraft.getInstance().font,
                Component.translatable("foxstweaks.search_assist.did_you_mean", foxstweaks$didYouMean),
                getX(), getY() + getHeight() + 3, 0xFFE0C060);
        g.pose().popPose();
    }
}
