package dev.jjstools.gui.jjtheme.widgets;

import dev.jjstools.gui.jjtheme.JJDraw;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WHorizontalList;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.input.WTextBox;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.util.Pair;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JJ's theme's module search: a bar right under the top bar and just as wide, like monkeytype's
 * "search settings", with the matches laid out in a grid beneath it while there is text in it.
 * It takes the place of Meteor's search window.
 */
public class WJJSearchBar extends WVerticalList implements JJWidget {
    private static final int COLUMNS = 5;

    public WTextBox box;
    private WHorizontalList row;
    private WTable results;

    @Override
    public void init() {
        JJGuiTheme theme = theme();
        spacing = gap();

        row = add(theme.horizontalList()).expandX().widget();
        row.spacing = 0;
        row.add(new WIcon()).centerY();
        box = row.add(theme.textBox("", "search modules")).expandX().widget();
        box.action = this::refresh;

        results = add(theme.table()).expandX().widget();
        results.horizontalSpacing = results.verticalSpacing = 0;
    }

    private double gap() {
        return theme.scale(4);
    }

    /** The matches for what is typed: modules by name first, then modules with a setting by that name. */
    private void refresh() {
        results.clear();
        String text = box.get();
        if (text.isBlank()) return;

        // The ten best: modules by name first, then modules with a setting by that name.
        int limit = 10;
        Map<Module, String> found = new LinkedHashMap<>();
        //#if MC >= 1.21.9
        List<Pair<Module, String>> titles = Modules.get().searchTitles(text);
        for (Pair<Module, String> p : titles) {
            if (found.size() >= limit) break;
            found.putIfAbsent(p.getLeft(), p.getRight());
        }
        //#else
        //$$ for (Module m : Modules.get().searchTitles(text)) {
        //$$     if (found.size() >= limit) break;
        //$$     found.putIfAbsent(m, m.title);
        //$$ }
        //#endif
        for (Module m : Modules.get().searchSettingTitles(text)) {
            if (found.size() >= limit) break;
            found.putIfAbsent(m, m.title);
        }

        int col = 0;
        for (Map.Entry<Module, String> e : found.entrySet()) {
            results.add(((dev.jjstools.gui.jjtheme.JJGuiTheme) theme).module(e.getKey(), e.getValue())).expandX();
            if (++col == COLUMNS) {
                results.row();
                col = 0;
            }
        }
    }

    /** The top bar this sits under, from the screen it is on. */
    private WWidget topBar() {
        if (!(parent instanceof WContainer root)) return null;
        for (Cell<?> cell : root.cells) if (cell.widget() instanceof WTopBar bar) return bar;
        return null;
    }

    @Override
    protected void onCalculateSize() {
        super.onCalculateSize();
        // As wide as the top bar.
        WWidget bar = topBar();
        if (bar != null) width = Math.max(bar.width, theme.scale(200));
    }

    @Override
    protected void onCalculateWidgetPositions() {
        // Right below the top bar, lined up with it.
        WWidget bar = topBar();
        if (bar != null) {
            x = bar.x;
            y = bar.y + bar.height + gap();
        }
        super.onCalculateWidgetPositions();
    }

    /** Where the module columns should start: below this bar, leaving the same gap again. */
    public static double contentTop(WWidget anyChildOfScreen) {
        return contentTop(anyChildOfScreen, true);
    }

    /** How much further down the columns are pushed right now by search matches showing. */
    public static double resultsExtra(WWidget anyChildOfScreen) {
        double with = contentTop(anyChildOfScreen, true), without = contentTop(anyChildOfScreen, false);
        return with < 0 || without < 0 ? 0 : with - without;
    }

    private static double contentTop(WWidget anyChildOfScreen, boolean withResults) {
        WWidget w = anyChildOfScreen;
        while (w != null && w.parent != null) w = w.parent;
        if (!(w instanceof WContainer root)) return -1;
        WWidget bar = null;
        WJJSearchBar search = null;
        for (Cell<?> cell : root.cells) {
            if (cell.widget() instanceof WTopBar b) bar = b;
            if (cell.widget() instanceof WJJSearchBar s) search = s;
        }
        if (bar == null || search == null) return -1;
        // From the bar's own measurements, as it is laid out at the normal scale while the
        // columns asking this are laid out at the module list scale.
        double gap = search.spacing;
        double rowH = search.row != null ? search.row.height : 0;
        double top = bar.y + bar.height + gap + rowH + gap;
        // While there are matches the columns make room for them, so nothing lies over anything
        // and every match can be clicked.
        if (withResults && search.results != null && !search.results.cells.isEmpty()) top += search.results.height + gap;
        return top;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        JJGuiTheme theme = theme();
        // The bar: one rounded strip in the button shade, the text box blending into it.
        round(renderer, x, y, width, row.height, theme.radius(), theme.backgroundColor.get(false, false, true));
        // The matches, on a card of their own beneath it.
        if (!results.cells.isEmpty()) renderPanel(renderer, results.x, results.y, results.width, results.height);
    }

    /** The magnifying glass at the start of the bar, in the dim colour. */
    private class WIcon extends WWidget {
        @Override
        protected void onCalculateSize() {
            double pad = theme.pad();
            width = pad + theme.textHeight() + pad * 0.5;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            if (JJDraw.SEARCH == null) return;
            double s = Math.round(theme.textHeight() * 0.85);
            double pad = theme.pad();
            renderer.quad(Math.round(x + pad), Math.round(y + (height - s) / 2), s, s, JJDraw.SEARCH, ((JJGuiTheme) theme).textSecondaryColor.get());
        }
    }
}
