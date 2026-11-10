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
    // Meteor keeps these for Ctrl+F from its 1.21.9 builds on; before that there is no Ctrl+F.
    //#if MC >= 1.21.9
    @Shadow(remap = false)
    private WWindow searchWindow;

    @Shadow(remap = false)
    private WTextBox searchTextBox;
    //#endif

    private ModulesScreenMixin(GuiTheme theme, Tab tab) {
        super(theme, tab);
    }

    @Inject(method = "createSearch", at = @At("HEAD"), cancellable = true, remap = false)
    private void jjsTools$searchBar(WContainer c, CallbackInfoReturnable<WWindow> cir) {
        if (!(theme instanceof JJGuiTheme)) return;

        WJJSearchBar bar = new WJJSearchBar();
        addDirect(bar);
        // Not typing into it until it is clicked (or Ctrl+F), so no caret is waiting in it on open.
        //#if MC >= 1.21.9
        searchTextBox = bar.box;
        //#endif

        // Meteor keeps the search window in its list of windows and unfolds it on Ctrl+F; a
        // window that is never shown stands in for it, so nothing of Meteor's has to change.
        WWindow standIn = theme.window("Search");
        //#if MC >= 1.21.9
        searchWindow = standIn;
        //#endif
        cir.setReturnValue(standIn);
    }
}
