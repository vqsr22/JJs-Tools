/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.utils.BaseWidget;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.utils.render.color.Color;

public interface JJWidget extends BaseWidget {
    default JJGuiTheme theme() {
        return (JJGuiTheme) getTheme();
    }

    /*
     * JJ's theme: boxes are drawn with rounded corners, like the Control Panel's, instead of
     * Meteor's square ones. A thin rounded outline sits under a rounded fill.
     */
    default void renderBackground(GuiRenderer renderer, WWidget widget, Color outlineColor, Color backgroundColor) {
        renderBox(renderer, widget.x, widget.y, widget.width, widget.height, outlineColor, backgroundColor);
    }

    /** A rounded box, with a thin outline under it when the outline colour shows. */
    default void renderBox(GuiRenderer renderer, double x, double y, double w, double h, Color outlineColor, Color backgroundColor) {
        JJGuiTheme theme = theme();
        double r = theme.radius();

        // An outline only when it is meant to show; the panel's buttons have none.
        if (outlineColor.a > 0) {
            double s = Math.max(1, Math.round(theme.unit()));
            round(renderer, x, y, w, h, r, outlineColor);
            round(renderer, x + s, y + s, w - s * 2, h - s * 2, Math.max(0, r - s), backgroundColor);
        } else {
            round(renderer, x, y, w, h, r, backgroundColor);
        }
    }

    /**
     * A button's background: the button shade, swapping to the text colour under the mouse,
     * instantly, as the panel's. Held down it sits a touch smaller at once, and eases back to
     * full size when let go.
     */
    default void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        JJGuiTheme theme = theme();
        double k = dev.jjstools.util.Motion.press(widget, pressed);
        double w = widget.width * k, h = widget.height * k;
        renderBox(renderer, widget.x + (widget.width - w) / 2, widget.y + (widget.height - h) / 2, w, h,
            theme.outlineColor.get(pressed, mouseOver, true), theme.backgroundColor.get(pressed, mouseOver, true));
    }

    /** A rounded rectangle drawn at a size about its middle, for presses and pulses. */
    default void roundScaled(GuiRenderer renderer, double x, double y, double w, double h, double radius, Color color, double k) {
        double sw = w * k, sh = h * k;
        round(renderer, x + (w - sw) / 2, y + (h - sh) / 2, sw, sh, radius * k, color);
    }

    /** A card like the panel's windows: a thin outline a step towards the dim text, then the panel colour. */
    default void renderPanel(GuiRenderer renderer, double x, double y, double w, double h) {
        JJGuiTheme theme = theme();
        double r = theme.windowRadius();
        double s = Math.max(1, Math.round(theme.unit()));
        round(renderer, x, y, w, h, r, theme.windowOutlineColor.get());
        round(renderer, x + s, y + s, w - s * 2, h - s * 2, Math.max(0, r - s), theme.panelColor.get());
    }

    /** A rectangle with all four corners rounded. */
    default void round(GuiRenderer renderer, double x, double y, double w, double h, double radius, Color color) {
        JJDraw.round(renderer, x, y, w, h, radius, color, true, true);
    }

    /** A rectangle with its top corners, bottom corners or both rounded. */
    default void round(GuiRenderer renderer, double x, double y, double w, double h, double radius, Color color, boolean top, boolean bottom) {
        JJDraw.round(renderer, x, y, w, h, radius, color, top, bottom);
    }
}
