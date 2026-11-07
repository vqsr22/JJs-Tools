package dev.jjstools.gui;

import dev.jjstools.panel.PanelFonts;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.Ui;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;

/**
 * The Control Panel's fonts as tiles, like the panel's font page. Picking one sets it for the
 * Control Panel and for Meteor's GUI at once, downloading it first if it is not here yet.
 */
public class FontsTab extends Tab {
    public FontsTab() {
        super("Fonts");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new FontsScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof FontsScreen;
    }

    private static class FontsScreen extends WindowTabScreen {
        private final List<WSwatch> swatches = new ArrayList<>();
        private WLabel status;

        FontsScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            // Where the font is used, beside the font itself.
            WHorizontalList options = add(theme.horizontalList()).expandX().widget();
            toggle(options, "shadow", shadowOn(), MeteorFonts::setShadow);
            toggle(options, "hud", MeteorFonts.hudFont(), MeteorFonts::setHudFont);
            toggle(options, "tab list", MeteorFonts.tabFont(), MeteorFonts::setTabFont);

            status = add(theme.label(" ")).expandX().widget();

            // The tiles in the panel's own colours, as on its font page.
            int[] p = Ui.palette(Ui.currentTheme());
            WTable grid = add(theme.table()).expandX().widget();
            // The same gap between tiles as between the tiles and the window's edge, as monkeytype's grid.
            double gap = WSwatch.gap(theme);
            grid.horizontalSpacing = grid.verticalSpacing = gap;
            for (var cell : window.cells) if (cell.widget() == window.view) cell.pad(gap);
            String current = Prefs.get().font == null ? PanelFonts.MINECRAFT : Prefs.get().font;
            int col = 0;
            swatches.clear();
            for (String id : PanelFonts.choices()) {
                WSwatch s = new WSwatch(id, p[2], p[4], p[3], p[5], false, 160);
                s.fontId = id;
                grid.add(s);
                s.picked = id.equals(current);
                swatches.add(s);
                s.action = () -> {
                    MeteorFonts.pick(id);
                    for (WSwatch o : swatches) o.picked = o == s;
                };
                if (++col == 5) {
                    grid.row();
                    col = 0;
                }
            }
        }

        private void toggle(WHorizontalList row, String label, boolean on, java.util.function.Consumer<Boolean> set) {
            row.add(theme.label(label));
            WCheckbox box = row.add(theme.checkbox(on)).widget();
            box.action = () -> set.accept(box.checked);
        }

        private boolean shadowOn() {
            return theme instanceof dev.jjstools.gui.jjtheme.JJGuiTheme jj && jj.textShadow.get();
        }

        @Override
        public void removed() {
            super.removed();
            // The previews' letters are textures of their own; they go when the page closes, as on the panel.
            dev.jjstools.panel.UiFont.dropPreviews();
        }

        @Override
        public void tick() {
            super.tick();
            // Also here, so a download finishing on the title screen is still picked up.
            MeteorFonts.tick();
            String words = MeteorFonts.status();
            if (status != null && !words.equals(status.get())) status.set(words.isEmpty() ? " " : words);
        }
    }
}
