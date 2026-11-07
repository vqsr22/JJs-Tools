/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.WQuad;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class WJJQuad extends WQuad {
    public WJJQuad(Color color) {
        super(color);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        // Rounded a little, as the panel's swatches are.
        double r = theme instanceof dev.jjstools.gui.jjtheme.JJGuiTheme t ? t.radius() * 0.6 : 0;
        dev.jjstools.gui.jjtheme.JJDraw.round(renderer, x, y, width, height, r, color);
    }
}
