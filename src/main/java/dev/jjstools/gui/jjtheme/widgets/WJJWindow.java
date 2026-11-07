/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Adapted for JJ's theme: drawn as the Control Panel's windows are, one rounded card with a thin
 * outline, the title in the dim text colour at the left of a header that is not coloured apart.
 */

package dev.jjstools.gui.jjtheme.widgets;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.Cell;
import meteordevelopment.meteorclient.gui.widgets.WLabel;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WContainer;
import meteordevelopment.meteorclient.gui.widgets.containers.WWindow;
import dev.jjstools.panel.Prefs;
import net.minecraft.client.gui.Click;

public class WJJWindow extends WWindow implements JJWidget {
    public WJJWindow(WWidget icon, String title) {
        super(icon, title);
    }

    @Override
    protected WHeader header(WWidget icon) {
        return new WJJHeader(icon);
    }

    // Resizing: the bottom edge of a module window drags to make its list taller or shorter, as
    // the Control Panel's columns do. Saved per window; double-clicking the edge puts it back.

    private boolean resizing;
    /** Whether any column is being resized, so what the mouse passes over meanwhile does not light up. */
    public static boolean resizingAny;
    private boolean overGrip;
    private double grabY, grabH;

    @Override
    public void init() {
        super.init();
        if (id != null) {
            Integer saved = Prefs.get().meteorWindowHeights.get(id);
            if (saved != null) view.maxHeight = Math.max(minListHeight(), saved);
        }
    }

    private double minListHeight() {
        return theme.textHeight() * 3;
    }

    private double shownHeight() {
        return Math.min(height, header.height + (height - header.height) * animProgress);
    }

    /** How far the grabbable strip reaches, in and below the bottom edge: generous, so it is easy to catch. */
    private double gripInside() {
        return Math.max(8, theme().unit() * 6);
    }

    private double gripOutside() {
        return Math.max(4, theme().unit() * 3);
    }

    private boolean onGrip(double mx, double my) {
        if (id == null || !expanded) return false;
        double bottom = y + shownHeight();
        return mx >= x && mx <= x + width && my >= bottom - gripInside() && my <= bottom + gripOutside();
    }

    // The mouse turns into an up-and-down arrow over the edge, so it is plain it can be dragged.
    private static long resizeCursor;
    private static WJJWindow cursorOwner;

    private void cursor(boolean resize) {
        long handle = net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle();
        if (resize && cursorOwner != this) {
            if (resizeCursor == 0) resizeCursor = org.lwjgl.glfw.GLFW.glfwCreateStandardCursor(org.lwjgl.glfw.GLFW.GLFW_VRESIZE_CURSOR);
            org.lwjgl.glfw.GLFW.glfwSetCursor(handle, resizeCursor);
            cursorOwner = this;
        } else if (!resize && cursorOwner == this) {
            org.lwjgl.glfw.GLFW.glfwSetCursor(handle, 0L);
            cursorOwner = null;
        }
    }

    /** Puts the normal mouse back, for when the GUI closes with it still over an edge. */
    public static void resetCursor() {
        if (cursorOwner == null) return;
        org.lwjgl.glfw.GLFW.glfwSetCursor(net.minecraft.client.MinecraftClient.getInstance().getWindow().getHandle(), 0L);
        cursorOwner = null;
    }

    /**
     * Module columns sit right below JJ's search bar, which sits under the top bar. Columns
     * nobody has moved go straight up to it, one gap below, keeping any second row Meteor makes
     * the same distance under the first; a column dragged somewhere stays there, only kept from
     * going up behind the bar.
     */
    @Override
    protected void onCalculateWidgetPositions() {
        // Lower in a stack: right under the window above, wherever that is, never where this one
        // was dragged to or pinned, so it always follows the one above.
        WWindow above = dev.jjstools.gui.jjtheme.ColumnStacks.above(this);
        if (above != null && id != null) {
            var config = theme.getWindowConfig(id);
            config.x = -1;
            config.y = -1;
            moved = false;
        }
        super.onCalculateWidgetPositions();
        if (above != null) {
            double wantX = above.x, wantY = above.y + above.height + dev.jjstools.gui.jjtheme.ColumnStacks.gap;
            if (wantX != x || wantY != y) move(wantX - x, wantY - y);
            return;
        }
        if (id == null) return;
        double top = WJJSearchBar.contentTop(this);
        if (top <= 0) return;
        var config = theme.getWindowConfig(id);
        boolean placedByMeteor = config.y == -1 && !moved;
        double want = placedByMeteor && parent != null ? top + (y - parent.y) : Math.max(y, top);
        if (want != y) move(0, want - y);
    }

    /**
     * The edge is looked at before anything inside the window gets the click, so a module
     * sitting at the bottom of a long list does not take it, whatever it is scrolled to.
     */
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        if (click.button() == 0 && onGrip(click.x(), click.y())) return gripClicked(click, doubled);
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(Click click) {
        if (resizing) return onMouseReleased(click);
        return super.mouseReleased(click);
    }

    private boolean gripClicked(Click click, boolean doubled) {
        if (doubled) {
            Prefs.get().meteorWindowHeights.remove(id);
            Prefs.save();
            view.maxHeight = meteordevelopment.meteorclient.utils.Utils.getWindowHeight() - theme.scale(128);
            invalidate();
            return true;
        }
        resizing = true;
        resizingAny = true;
        grabY = click.y();
        grabH = view.height;
        pinWindows();
        return true;
    }

    /**
     * Holds every window where it is while one is resized. Meteor lays out windows nobody has
     * moved from their sizes, so a window growing would push the others along; giving each its
     * place, as dragging it there would, keeps them still.
     */
    private void pinWindows() {
        if (!(parent instanceof WContainer screen)) return;
        for (Cell<?> cell : screen.cells) {
            if (cell.widget() instanceof WWindow w && w.id != null) {
                var config = theme.getWindowConfig(w.id);
                config.x = w.x;
                config.y = w.y;
            }
        }
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        overGrip = onGrip(mouseX, mouseY);
        cursor(overGrip || resizing);
        if (!resizing) return;

        // Dragged right up to the header, the window folds away as its chevron would fold it;
        // brought back down in the same drag, it opens again.
        boolean folding = mouseY <= y + header.height + minListHeight() / 2;
        if (folding != !expanded) {
            // Folding keeps the list at the small size it was dragged to while it closes, rather
            // than springing back open to full height on the way; the full height comes back on
            // letting go, out of sight.
            setExpanded(!folding);
            invalidate();
        }
        if (folding) return;

        double room = meteordevelopment.meteorclient.utils.Utils.getWindowHeight() - y - header.height - padding * 2;
        view.maxHeight = Math.max(minListHeight(), Math.min(room, grabH + (mouseY - grabY)));
        invalidate();
    }

    @Override
    public boolean onMouseReleased(Click click) {
        if (!resizing) return false;
        resizing = false;
        resizingAny = false;
        cursor(overGrip);
        // Folded away, the height it had is kept for when it is opened again.
        if (expanded) {
            Prefs.get().meteorWindowHeights.put(id, (int) Math.round(view.maxHeight));
            Prefs.save();
        } else {
            view.maxHeight = Math.max(minListHeight(), grabH);
            invalidate();
        }
        return true;
    }

    /**
     * Each window drawn as a batch of its own. Meteor collects shapes, textures and text and
     * draws each kind in turn, and a window whose list does not scroll has no clipping area of
     * its own, so it shared a batch with the next: a window behind then had its rounded corners
     * and text drawn on top of the one in front of it. Clipping each window to itself starts a
     * fresh batch, so every window is finished before the next is drawn over it.
     */
    @Override
    public boolean render(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!visible) return true;
        boolean column = isModuleColumn();
        // Starting the column's clip draws whatever the screen had waiting, such as the top bar's
        // words, so that happens first, at the normal scale; only then does the column's own
        // scale take over. The other way round, the top bar's text came out at the module list size.
        double shown = Math.max(1, shownHeight());
        renderer.scissorStart(x, y, width, shown);
        if (column) {
            JJGuiTheme.enterModuleList();
            lastShown = System.currentTimeMillis();
            COLUMNS.add(this);
        }
        try {
            boolean result = super.render(renderer, mouseX, mouseY, delta);
            // The batch is drawn here, so its text is sized at the module list scale too.
            renderer.scissorEnd();
            return result;
        } finally {
            if (column) JJGuiTheme.exitModuleList();
        }
    }

    // Module list scale: a module column is sized, laid out and drawn at its own scale.

    private static final java.util.Set<WJJWindow> COLUMNS = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private long lastShown;

    /** A category or favourites column on the modules screen, rather than any other window. */
    private boolean isModuleColumn() {
        return id != null && !"search".equals(id)
            && net.minecraft.client.MinecraftClient.getInstance().currentScreen instanceof meteordevelopment.meteorclient.gui.screens.ModulesScreen;
    }

    /** The module columns showing now, for auto fit to measure. */
    public static java.util.List<WWindow> moduleColumns() {
        long now = System.currentTimeMillis();
        java.util.List<WWindow> out = new java.util.ArrayList<>();
        for (WJJWindow w : COLUMNS) if (w.parent != null && now - w.lastShown < 500 && w.isModuleColumn()) out.add(w);
        return out;
    }

    @Override
    public void calculateSize() {
        boolean column = isModuleColumn();
        if (column) JJGuiTheme.enterModuleList();
        try {
            super.calculateSize();
        } finally {
            if (column) JJGuiTheme.exitModuleList();
        }
    }

    @Override
    public void calculateWidgetPositions() {
        boolean column = isModuleColumn();
        if (column) JJGuiTheme.enterModuleList();
        try {
            super.calculateWidgetPositions();
        } finally {
            if (column) JJGuiTheme.exitModuleList();
        }
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        // The whole window, header and all, as one card. Meteor keeps a folded window at its
        // full height and only stops drawing what is in it, so the card is as tall as what shows:
        // the header, and as much of the rest as is unfolded.
        double shown = shownHeight();
        renderPanel(renderer, x, y, width, shown);

        // A handle along the bottom of every module column, so it is plain the edge can be dragged:
        // faint at rest, the text colour under the mouse, the main colour while dragging.
        if (id != null && expanded && animProgress > 0.99) {
            double u = theme().unit();
            boolean hot = overGrip || resizing;
            double gw = Math.min(width * 0.5, u * (hot ? 22 : 16));
            double gh = Math.max(2, Math.round(u * (hot ? 1.6 : 1.1)));
            var c = resizing ? theme().accentColor.get() : hot ? theme().textColor.get() : theme().separatorEdges.get();
            round(renderer, x + (width - gw) / 2, y + shown - u * 2.2 - gh, gw, gh, gh / 2, c);
        }
    }

    private class WJJHeader extends WHeader {
        public WJJHeader(WWidget icon) {
            super(icon);
        }

        @Override
        public void init() {
            super.init();
            // The title at the left, as the panel's are, rather than in the middle.
            leftAlignLabels(this);
        }

        private void leftAlignLabels(WContainer container) {
            for (Cell<?> cell : container.cells) {
                WWidget w = cell.widget();
                if (w instanceof WLabel) alignLeft(cell);
                else if (w instanceof WContainer inner) leftAlignLabels(inner);
            }
        }

        /** Meteor's cells only go left by default and have no way back to it once centred, so the field is set directly. */
        private void alignLeft(Cell<?> cell) {
            try {
                java.lang.reflect.Field f = Cell.class.getDeclaredField("alignX");
                f.setAccessible(true);
                f.set(cell, meteordevelopment.meteorclient.gui.utils.AlignmentX.Left);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Stays centred.
            }
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            // Nothing of its own: the header is part of the card.
        }
    }
}
