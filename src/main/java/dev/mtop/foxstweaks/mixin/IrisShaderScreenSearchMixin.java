package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.client.compat.IrisSettingsSearch;
import net.irisshaders.iris.gui.element.ShaderPackOptionList;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Adds a search box under Iris' shader settings list (only while the settings, not the pack list, are open).
 * Typing rebuilds the list as search results; Esc clears the search before it does anything else.
 */
@Mixin(value = ShaderPackScreen.class, remap = false)
public abstract class IrisShaderScreenSearchMixin extends Screen {
    @Shadow private ShaderPackOptionList shaderOptionList;
    @Shadow private boolean optionMenuOpen;
    @Shadow private boolean guiHidden;

    @Unique private EditBox foxstweaks$search;

    protected IrisShaderScreenSearchMixin() {
        super(Component.empty());
    }

    @Inject(method = "<init>", at = @At("TAIL"), require = 0)
    private void foxstweaks$resetSearch(CallbackInfo ci) {
        IrisSettingsSearch.query = "";
    }

    /** Make room for the search box: the settings list starts 20px lower (top and height arguments). */
    @ModifyArg(method = "init", index = 6, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gui/element/ShaderPackOptionList;<init>(Lnet/irisshaders/iris/gui/screen/ShaderPackScreen;Lnet/irisshaders/iris/gui/NavigationController;Lnet/irisshaders/iris/shaderpack/ShaderPack;Lnet/minecraft/client/Minecraft;IIIIII)V"))
    private int foxstweaks$listTop(int top) {
        return Config.enabled(Config.IRIS_SETTINGS_SEARCH) ? top + SEARCH_ROOM : top;
    }

    @ModifyArg(method = "init", index = 7, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gui/element/ShaderPackOptionList;<init>(Lnet/irisshaders/iris/gui/screen/ShaderPackScreen;Lnet/irisshaders/iris/gui/NavigationController;Lnet/irisshaders/iris/shaderpack/ShaderPack;Lnet/minecraft/client/Minecraft;IIIIII)V"))
    private int foxstweaks$listHeight(int height) {
        return Config.enabled(Config.IRIS_SETTINGS_SEARCH) ? height - SEARCH_ROOM : height;
    }

    @Unique private static final int SEARCH_ROOM = 20;

    @Inject(method = "init", at = @At("TAIL"), require = 0)
    private void foxstweaks$addSearchBox(CallbackInfo ci) {
        foxstweaks$search = null;
        if (!Config.enabled(Config.IRIS_SETTINGS_SEARCH)) {
            return;
        }
        if (!optionMenuOpen || shaderOptionList == null || guiHidden) {
            // Back on the pack list: drop the search so the settings open on their normal menu next time.
            IrisSettingsSearch.query = "";
            return;
        }
        EditBox box = new EditBox(font, width / 2 - 100, 33, 200, 16, Component.literal("Search shader settings"));
        box.setMaxLength(64);
        box.setHint(Component.literal("Search settings (e.g. colored light)").withStyle(ChatFormatting.DARK_GRAY));
        box.setValue(IrisSettingsSearch.query);
        box.setResponder(text -> {
            if (!text.equals(IrisSettingsSearch.query)) {
                IrisSettingsSearch.query = text;
                if (shaderOptionList != null) {
                    IrisSettingsSearch.completion = null;
                    shaderOptionList.rebuild();
                }
                foxstweaks$showCompletion();
            }
        });
        foxstweaks$search = addRenderableWidget(box);
        foxstweaks$showCompletion();
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private void foxstweaks$escClearsSearch(int key, int scancode, int modifiers, CallbackInfoReturnable<Boolean> cir) {
        EditBox box = foxstweaks$search;
        if (box == null) {
            return;
        }
        if (key == 256 && IrisSettingsSearch.active()) {
            box.setValue("");
            cir.setReturnValue(true);
        } else if (box.isFocused() && key == 258 && IrisSettingsSearch.active()) {
            // Tab in the search box: take the grey suggestion instead of switching to the pack list.
            String full = IrisSettingsSearch.completion;
            if (full != null) {
                box.setValue(full);
                box.moveCursorToEnd(false);
            }
            cir.setReturnValue(true);
        } else if (box.isFocused() && key != 256 && key != 258) {
            // Typing in the box: don't let the screen treat letters as shortcuts.
            cir.setReturnValue(box.keyPressed(key, scancode, modifiers) || box.canConsumeInput());
        }
    }

    @Unique
    private void foxstweaks$showCompletion() {
        EditBox box = foxstweaks$search;
        if (box == null) {
            return;
        }
        String typed = box.getValue();
        String full = IrisSettingsSearch.completion;
        box.setSuggestion(typed.isBlank() || full == null || full.length() <= typed.length()
                || !full.toLowerCase(java.util.Locale.ROOT).startsWith(typed.toLowerCase(java.util.Locale.ROOT))
                ? null : full.substring(typed.length()));
    }
}
