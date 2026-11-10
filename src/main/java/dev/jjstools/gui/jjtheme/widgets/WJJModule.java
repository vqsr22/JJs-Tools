/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.utils.AlignmentX;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.systems.modules.Module;

import static meteordevelopment.meteorclient.MeteorClient.mc;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

public class WJJModule extends WPressable implements JJWidget {
    private final Module module;
    private final String title;

    private double titleWidth;

    private double animationProgress1;

    private double animationProgress2;

    public WJJModule(Module module, String title) {
        this.module = module;
        this.title = title;
        this.tooltip = module.description;

        if (module.isActive()) {
            animationProgress1 = 1;
            animationProgress2 = 1;
        } else {
            animationProgress1 = 0;
            animationProgress2 = 0;
        }
    }

    @Override
    public double pad() {
        return theme.scale(4);
    }

    @Override
    protected void onCalculateSize() {
        double pad = pad();

        titleWidth = theme.textWidth(theme().shown(title));
        // Equal columns: every button as wide as the longest name, the name centred in it.
        double room = theme().equalColumns.get() ? Math.max(titleWidth, theme().widestModuleName()) : titleWidth;

        // A little room around each row, so the highlights stand apart from each other and the
        // window's edges, as the panel's lists do.
        width = marginX() + pad + room + pad + marginX();
        height = marginY() + pad + theme.textHeight() + pad + marginY();
    }

    @Override
    protected void onPressed(int button) {
        if (button == GLFW_MOUSE_BUTTON_LEFT) module.toggle();
        else if (button == GLFW_MOUSE_BUTTON_RIGHT) mc.setScreen(theme.moduleScreen(module));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        JJGuiTheme theme = theme();
        double pad = pad();

        // As the panel's buttons: nothing behind it at rest, the main colour when the module is
        // on, and the text colour under the mouse, each with the panel colour written on it.
        // Instant, as monkeytype's are, rather than sliding in.
        boolean on = module.isActive();
        // While a column is being resized the mouse is dragging an edge, not pointing at modules.
        boolean mouseOver = this.mouseOver && !WJJWindow.resizingAny;
        boolean pressed = this.pressed && !WJJWindow.resizingAny;
        double mx = marginX(), my = marginY();
        // Coming in: each row a moment after the one above it, rising a little and fading in.
        double in = entrance();
        double rise = (1 - in) * theme.scale(6);
        double bx = x + mx, by = y + my + rise, bw = width - mx * 2, bh = height - my * 2;
        // Smaller while held, and a short pulse when the module turns on.
        double k = dev.jjstools.util.Motion.press(this, pressed) * dev.jjstools.util.Motion.pulseOnTurn(this, on);
        // Held down: the dim colour, as monkeytype's buttons, so a click shows.
        if (pressed) roundScaled(renderer, bx, by, bw, bh, theme.radius(), faded(theme.textSecondaryColor.get(), in), k);
        else if (mouseOver) roundScaled(renderer, bx, by, bw, bh, theme.radius(), faded(theme.textColor.get(), in), k);
        else if (on) roundScaled(renderer, bx, by, bw, bh, theme.radius(), faded(theme.moduleBackground.get(), in), k);

        String shown = theme.shown(title);
        double w = titleWidth;
        double x = bx + pad;
        double room = bw - pad * 2;

        if (theme.moduleAlignment.get() == AlignmentX.Center) {
            x += room / 2 - w / 2;
        }
        else if (theme.moduleAlignment.get() == AlignmentX.Right) {
            x += room - w;
        }

        renderer.text(shown, x, by + pad, faded(theme.ink(pressed || mouseOver || on), in), false);
    }

    private int row = -1;

    /**
     * How far in this row is, 0 to 1, after the screen opens: its column a moment after the one
     * to its left, and each row in it a moment after the one above, so the list settles in
     * order. Only the first dozen rows wait; the rest come in with the twelfth.
     */
    private double entrance() {
        if (row < 0 && parent instanceof meteordevelopment.meteorclient.gui.widgets.containers.WContainer c) {
            row = 0;
            for (var cell : c.cells) {
                if (cell.widget() == this) break;
                row++;
            }
        }
        double opened = dev.jjstools.util.Motion.openedAt(mc.currentScreen);
        if (dev.jjstools.util.Motion.now() - opened > 1500) return 1;
        double delay = WJJWindow.columnIndex(this) * dev.jjstools.util.Motion.STAGGER_MS
            + Math.min(Math.max(row, 0), 12) * dev.jjstools.util.Motion.STAGGER_MS;
        return dev.jjstools.util.Motion.enter(opened, delay);
    }

    /** A colour with its see-through-ness scaled, for fading in. */
    private static meteordevelopment.meteorclient.utils.render.color.Color faded(meteordevelopment.meteorclient.utils.render.color.Color c, double k) {
        if (k >= 1) return c;
        meteordevelopment.meteorclient.utils.render.color.Color copy = new meteordevelopment.meteorclient.utils.render.color.Color(c);
        copy.a = (int) Math.round(c.a * k);
        return copy;
    }

    private double marginX() {
        return Math.round(theme().unit() * 3);
    }

    private double marginY() {
        return Math.round(theme().unit() * 1);
    }
}
