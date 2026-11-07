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
        JJGuiTheme theme = theme();
        double r = theme.radius();

        // An outline only when it is meant to show; the panel's buttons have none.
        if (outlineColor.a > 0) {
            double s = Math.max(1, Math.round(theme.unit()));
            round(renderer, widget.x, widget.y, widget.width, widget.height, r, outlineColor);
            round(renderer, widget.x + s, widget.y + s, widget.width - s * 2, widget.height - s * 2, Math.max(0, r - s), backgroundColor);
        } else {
            round(renderer, widget.x, widget.y, widget.width, widget.height, r, backgroundColor);
        }
    }

    /** A button's background: the button shade, swapping to the text colour under the mouse, instantly, as the panel's. */
    default void renderBackground(GuiRenderer renderer, WWidget widget, boolean pressed, boolean mouseOver) {
        JJGuiTheme theme = theme();
        renderBackground(renderer, widget, theme.outlineColor.get(pressed, mouseOver, true), theme.backgroundColor.get(pressed, mouseOver, true));
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
