package dev.jjstools.mixin;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.widgets.WJJSearchBar;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.screens.ModulesScreen;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Under JJ's theme, Meteor's search window becomes a bar right under the top bar and just as
 * wide, like monkeytype's settings search. Ctrl+F still goes to it. Other themes keep Meteor's own.
 */
@Mixin(value = ModulesScreen.class, remap = false)
public abstract class ModulesScreenMixin extends TabScreen {
    @Shadow(remap = false)
    private WWindow searchWindow;

    @Shadow(remap = false)
    private WTextBox searchTextBox;

    private ModulesScreenMixin(GuiTheme theme, Tab tab) {
        super(theme, tab);
    }

    @Inject(method = "createSearch", at = @At("HEAD"), cancellable = true, remap = false)
    private void jjsTools$searchBar(WContainer c, CallbackInfoReturnable<WWindow> cir) {
        if (!(theme instanceof JJGuiTheme)) return;

        WJJSearchBar bar = new WJJSearchBar();
        addDirect(bar);
        bar.box.setFocused(true);
        searchTextBox = bar.box;

        // Meteor keeps the search window in its list of windows and unfolds it on Ctrl+F; a
        // window that is never shown stands in for it, so nothing of Meteor's has to change.
        WWindow standIn = theme.window("Search");
        searchWindow = standIn;
        cir.setReturnValue(standIn);
    }
}
