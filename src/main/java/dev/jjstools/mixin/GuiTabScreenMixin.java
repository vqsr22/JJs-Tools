package dev.jjstools.mixin;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import dev.jjstools.gui.jjtheme.ColumnStacks;
import meteordevelopment.meteorclient.gui.widgets.containers.WSection;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Column stacking and an "auto fit" button on the GUI tab's top row under JJ's theme, beside reset layout and reset
 * colors: it sets the GUI scale so every module column fits across the screen.
 */
@Mixin(targets = "meteordevelopment.meteorclient.gui.tabs.builtin.GuiTab$GuiScreen", remap = false)
public abstract class GuiTabScreenMixin extends WindowTabScreen {
    private GuiTabScreenMixin(GuiTheme theme, Tab tab) {
        super(theme, tab);
    }

    @Inject(method = "initWidgets", at = @At("TAIL"), remap = false)
    private void jjsTools$autoFit(CallbackInfo ci) {
        if (!(theme instanceof JJGuiTheme jj) || window.view.cells.isEmpty()) return;
        if (!(window.view.cells.getFirst().widget() instanceof WHorizontalList row)) return;
        WButton fit = row.add(theme.button("auto fit")).widget();
        fit.tooltip = "Sets the scale so every module column fits across the screen.";
        fit.action = () -> {
            jj.autoFit();
            // Opened again so the scale slider shows the new value.
            mc.setScreen(null);
            tab.openScreen(GuiThemes.get());
        };
        stacking(jj);
    }

    /**
     * Column stacking, right under auto fit: one row for every module column, each with a choice
     * of keeping its own place in the row or sitting below any other column. Stacks go as deep as
     * you like, and addon categories show up here as soon as they exist. Columns that would make
     * a loop are left out of each list.
     */
    private void stacking(JJGuiTheme jj) {
        List<String> ids = new ArrayList<>();
        for (Category category : Modules.loopCategories()) {
            if (!Modules.get().getGroup(category).isEmpty()) ids.add(category.name);
        }
        if (Modules.get().getAll().stream().anyMatch(m -> m.favorite)) ids.add("favorites");
        if (ids.size() < 2) return;

        WSection section = add(theme.section("column stacking (for autofit button)")).expandX().widget();
        WTable table = section.add(theme.table()).expandX().widget();
        for (String id : ids) {
            List<String> options = new ArrayList<>();
            options.add(OWN);
            for (String other : ids) {
                if (!other.equals(id) && !ColumnStacks.wouldLoop(id, other)) options.add(BELOW + other.toLowerCase(Locale.ROOT));
            }
            String under = ColumnStacks.below(id);
            String now = under != null && ids.contains(under) ? BELOW + under.toLowerCase(Locale.ROOT) : OWN;
            if (!options.contains(now)) now = OWN;

            table.add(theme.label(id.toLowerCase(Locale.ROOT)));
            WDropdown<String> choice = table.add(theme.dropdown(options.toArray(new String[0]), now)).expandX().widget();
            choice.action = () -> {
                String picked = choice.get();
                String target = null;
                if (!picked.equals(OWN)) {
                    for (String other : ids) if ((BELOW + other.toLowerCase(Locale.ROOT)).equals(picked)) target = other;
                }
                ColumnStacks.set(id, target);
                // Opened again so every list leaves out the choices that would now make a loop.
                mc.setScreen(null);
                tab.openScreen(GuiThemes.get());
            };
            table.row();
        }

        // Straight under the top row, beside auto fit.
        var cells = window.view.cells;
        cells.add(1, cells.removeLast());
    }

    private static final String OWN = "own column";
    private static final String BELOW = "below ";
}
