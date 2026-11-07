package dev.jjstools.gui;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.panel.Prefs;
import dev.jjstools.panel.Ui;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.GuiThemes;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.WindowTabScreen;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import net.minecraft.client.gui.screen.Screen;

import java.util.ArrayList;
import java.util.List;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Every Control Panel theme as a tile in its own colours, like the panel's themes page. Picking
 * one changes the Control Panel and, through JJ's theme, Meteor's GUI with it.
 */
public class ThemesTab extends Tab {
    public ThemesTab() {
        super("Themes");
    }

    @Override
    public TabScreen createScreen(GuiTheme theme) {
        return new ThemesScreen(theme, this);
    }

    @Override
    public boolean isScreen(Screen screen) {
        return screen instanceof ThemesScreen;
    }

    private static class ThemesScreen extends WindowTabScreen {
        private final List<WSwatch> swatches = new ArrayList<>();

        ThemesScreen(GuiTheme theme, Tab tab) {
            super(theme, tab);
        }

        @Override
        public void initWidgets() {
            if (!(GuiThemes.get() instanceof JJGuiTheme)) {
                add(theme.label("Picking a theme here also switches Meteor's GUI to JJ's theme, so it follows."));
            }

            WTable grid = add(theme.table()).expandX().widget();
            // The same gap between tiles as between the tiles and the window's edge, as monkeytype's grid.
            double gap = WSwatch.gap(theme);
            grid.horizontalSpacing = grid.verticalSpacing = gap;
            for (var cell : window.cells) if (cell.widget() == window.view) cell.pad(gap);
            String current = Ui.currentTheme().name();
            int col = 0;
            swatches.clear();
            for (Ui.Theme t : Ui.Theme.ordered()) {
                int[] p = Ui.palette(t);
                // Each name in its theme's main colour on its background, as monkeytype's tiles.
                int name = t.nameColour() != 0 ? t.nameColour() : p[3];
                WSwatch s = grid.add(new WSwatch(t.title, p[0], name, p[3], p[5], true, 160)).widget();
                s.picked = t.name().equals(current);
                swatches.add(s);
                s.action = () -> pick(t, s);
                if (++col == 5) {
                    grid.row();
                    col = 0;
                }
            }
        }

        private void pick(Ui.Theme t, WSwatch picked) {
            Prefs.Data prefs = Prefs.get();
            prefs.theme = t.name();
            prefs.dark = t != Ui.Theme.Light;
            Prefs.save();
            for (WSwatch s : swatches) s.picked = s == picked;

            if (!(GuiThemes.get() instanceof JJGuiTheme)) {
                // Meteor's GUI only follows the panel through JJ's theme.
                GuiThemes.select(JJGuiTheme.NAME);
                mc.setScreen(null);
                tab.openScreen(GuiThemes.get());
            }
            JJGuiTheme.tick();
        }
    }
}
