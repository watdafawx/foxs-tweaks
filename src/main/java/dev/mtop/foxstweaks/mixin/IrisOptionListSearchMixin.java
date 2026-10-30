package dev.mtop.foxstweaks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.mtop.foxstweaks.Config;
import dev.mtop.foxstweaks.client.compat.IrisSearchList;
import dev.mtop.foxstweaks.client.compat.IrisSettingsSearch;
import net.irisshaders.iris.gui.element.IrisContainerObjectSelectionList;
import net.irisshaders.iris.gui.NavigationController;
import net.irisshaders.iris.gui.element.ShaderPackOptionList;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.irisshaders.iris.shaderpack.option.menu.OptionMenuContainer;

/** While the settings search has text, the option list shows search results instead of the current menu. */
@Mixin(value = ShaderPackOptionList.class, remap = false)
public abstract class IrisOptionListSearchMixin extends IrisContainerObjectSelectionList<ShaderPackOptionList.BaseEntry> implements IrisSearchList {
    @Shadow @Final private ShaderPackScreen screen;
    @Shadow @Final private NavigationController navigation;
    @Shadow private OptionMenuContainer container;

    private IrisOptionListSearchMixin() {
        super(null, 0, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public void foxstweaks$addRow(ShaderPackOptionList.BaseEntry entry) {
        addEntry(entry);
    }

    @Inject(method = "rebuild", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/gui/element/widget/OptionMenuConstructor;constructAndApplyToScreen(Lnet/irisshaders/iris/shaderpack/option/menu/OptionMenuContainer;Lnet/irisshaders/iris/gui/screen/ShaderPackScreen;Lnet/irisshaders/iris/gui/element/ShaderPackOptionList;Lnet/irisshaders/iris/gui/NavigationController;)V"),
            cancellable = true, require = 0)
    private void foxstweaks$searchResults(CallbackInfo ci) {
        if (IrisSettingsSearch.active() && Config.enabled(Config.IRIS_SETTINGS_SEARCH)) {
            IrisSettingsSearch.fill((ShaderPackOptionList) (Object) this, container, screen, navigation);
            ci.cancel();
        }
    }
}
