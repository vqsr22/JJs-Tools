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
        double bx = x + mx, by = y + my, bw = width - mx * 2, bh = height - my * 2;
        // Held down: the dim colour, as monkeytype's buttons, so a click shows.
        if (pressed) round(renderer, bx, by, bw, bh, theme.radius(), theme.textSecondaryColor.get());
        else if (mouseOver) round(renderer, bx, by, bw, bh, theme.radius(), theme.textColor.get());
        else if (on) round(renderer, bx, by, bw, bh, theme.radius(), theme.moduleBackground.get());

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

        renderer.text(shown, x, by + pad, theme.ink(pressed || mouseOver || on), false);
    }

    private double marginX() {
        return Math.round(theme().unit() * 3);
    }

    private double marginY() {
        return Math.round(theme().unit() * 1);
    }
}
