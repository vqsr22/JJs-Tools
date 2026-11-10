/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Adapted for JJ's theme: drawn as the Control Panel's on and off, two buttons side by side.
 */

package dev.jjstools.gui.jjtheme.widgets.pressable;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WCheckbox;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * A checkbox drawn as the Control Panel's "off" and "on": two equal buttons, the chosen one in
 * the main colour. Clicking a side picks it; under the mouse a side turns to the text colour,
 * and goes dim while held, as monkeytype's buttons do.
 */
public class WJJCheckbox extends WCheckbox implements JJWidget {
    private double mouseX;
    /** Each side's own press and pulse. */
    private final Object offKey = new Object(), onKey = new Object();

    public WJJCheckbox(boolean checked) {
        super(checked);
    }

    private double gap() {
        return Math.round(theme().unit() * 2);
    }

    private double side() {
        JJGuiTheme theme = theme();
        double pad = pad();
        return pad + Math.max(theme.textWidth(theme.shown("off")), theme.textWidth(theme.shown("on"))) + pad;
    }

    @Override
    protected void onCalculateSize() {
        double pad = pad();
        width = side() * 2 + gap();
        height = pad + theme.textHeight() + pad;
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        super.onMouseMoved(mouseX, mouseY, lastMouseX, lastMouseY);
        this.mouseX = mouseX;
    }

    /** Whether the mouse is over the "on" half rather than the "off" one. */
    private boolean overOn() {
        return mouseX >= x + side() + gap() / 2;
    }

    @Override
    protected void onPressed(int button) {
        // The side clicked is the one picked, rather than flipping whatever it was.
        checked = overOn();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double w = side();
        drawSide(renderer, offKey, x, w, "off", !checked, mouseOver && !overOn());
        drawSide(renderer, onKey, x + w + gap(), width - w - gap(), "on", checked, mouseOver && overOn());
    }

    private void drawSide(GuiRenderer renderer, Object key, double sx, double w, String label, boolean chosen, boolean hot) {
        JJGuiTheme theme = theme();
        Color bg = hot && pressed ? theme.textSecondaryColor.get()
            : hot ? theme.textColor.get()
            : chosen ? theme.checkboxColor.get()
            : theme.backgroundColor.get(false, false, true);
        // Smaller while held, and a short pulse when this side becomes the chosen one.
        double k = dev.jjstools.util.Motion.press(key, hot && pressed) * dev.jjstools.util.Motion.pulseOnTurn(key, chosen);
        roundScaled(renderer, sx, y, w, height, theme.radius(), bg, k);
        String shown = theme.shown(label);
        renderer.text(shown, sx + (w - theme.textWidth(shown)) / 2, y + pad(), theme.ink(hot || chosen), false);
    }
}
