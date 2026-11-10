/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets.input;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.input.WSlider;

public class WJJSlider extends WSlider implements JJWidget {
    public WJJSlider(double value, double min, double max) {
        super(value, min, max);
    }

    /**
     * Monkeytype's slider: a pill-shaped thumb a quarter of the track wide. Meteor works out the
     * value from the handle's size, so making the handle that wide keeps clicks and drags lined up
     * with where it is drawn.
     */
    @Override
    protected double handleSize() {
        double h = theme.textHeight();
        return width > h * 4 ? width * 0.25 : h;
    }

    @Override
    protected void onCalculateSize() {
        double h = theme.textHeight();
        width = h * 4;
        // A little taller than the text, as monkeytype's 1.25em.
        height = Math.round(h * 1.25);
    }

    @Override
    public void onMouseMoved(double mouseX, double mouseY, double lastMouseX, double lastMouseY) {
        super.onMouseMoved(mouseX, mouseY, lastMouseX, lastMouseY);
        // Over the thumb is over the whole pill, not a square in its middle.
        double tx = x + valueWidth();
        handleMouseOver = mouseX >= tx && mouseX <= tx + handleSize() && mouseY >= y && mouseY <= y + height;
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        JJGuiTheme theme = theme();
        double valueWidth = valueWidth();

        // The track: a pill a little slimmer than the thumb, in the button shade. Its round ends
        // share their centres with the thumb's at either end, so with the thumb all the way over
        // the track stays hidden inside it rather than peeking out at the thumb's narrow tip.
        double th = Math.round(height * 0.8);
        double inset = (height - th) / 2;
        round(renderer, x + inset, y + inset, width - inset * 2, th, th / 2, theme.sliderRight.get());

        // The thumb: a pill in the main colour; the text colour under the mouse, dim while dragged.
        // Meteor draws all plain quads before all textures, so the track's rounded ends (textures)
        // landed on top of the thumb's body (plain quads) as a dark line whenever the thumb sat over
        // an end. Starting a clip draws everything so far first, so the thumb now goes on top.
        // While dragged it follows the mouse exactly, and stretches up to 12% along the way it is
        // moving, by how fast, settling back to round when it stops.
        double knob = handleSize();
        double now = dev.jjstools.util.Motion.now();
        double ms = lastFrame == 0 ? 16 : Math.max(1, now - lastFrame);
        double speed = Math.abs(valueWidth - lastValueWidth) / ms;   // pixels per millisecond
        lastFrame = now;
        lastValueWidth = valueWidth;
        double target = dragging && !dev.jjstools.util.Motion.off() ? Math.min(1, speed / Math.max(0.05, knob * 0.02)) : 0;
        stretch = dev.jjstools.util.Motion.off() ? 0 : stretch + (target - stretch) * (1 - Math.exp(-ms / dev.jjstools.util.Motion.ms(60)));
        double w = knob * (1 + 0.12 * stretch);
        double kx = Math.max(x, Math.min(x + width - w, x + valueWidth + knob / 2 - w / 2));
        renderer.scissorStart(x, y, width, height);
        round(renderer, kx, y, w, height, height / 2, theme.sliderHandle.get(dragging, handleMouseOver));
        renderer.scissorEnd();
    }

    private double lastValueWidth, lastFrame, stretch;
}
