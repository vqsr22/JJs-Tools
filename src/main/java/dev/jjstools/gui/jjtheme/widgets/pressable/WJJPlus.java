/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets.pressable;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPlus;

public class WJJPlus extends WPlus implements JJWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        JJGuiTheme theme = theme();
        double pad = pad();
        double s = Math.max(1, Math.round(theme.unit()));

        renderBackground(renderer, this, pressed, mouseOver);
        var c = (pressed || mouseOver) ? theme.ink(true) : theme.plusColor.get();
        renderer.quad(x + pad, y + height / 2 - s / 2, width - pad * 2, s, c);
        renderer.quad(x + width / 2 - s / 2, y + pad, s, height - pad * 2, c);
    }
}
