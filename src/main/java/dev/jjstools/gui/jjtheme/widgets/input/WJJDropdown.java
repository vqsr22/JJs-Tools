/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets.input;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.input.WDropdown;
import meteordevelopment.meteorclient.utils.render.color.Color;

public class WJJDropdown<T> extends WDropdown<T> implements JJWidget {
    public WJJDropdown(T[] values, T value) {
        super(values, value);
    }

    private boolean wasExpanded;
    private double changedAt = -1e9;

    /**
     * The list opens downwards out of the dropdown over 200 milliseconds, easing out, and folds
     * back into it a fifth quicker, easing in. Meteor shows the list as far down as this progress
     * goes, so the list is revealed from the dropdown itself rather than appearing all at once.
     */
    @Override
    public boolean render(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (expanded != wasExpanded) {
            wasExpanded = expanded;
            changedAt = dev.jjstools.util.Motion.now();
        }
        double progress = expanded ? dev.jjstools.util.Motion.enter(changedAt, 0) : dev.jjstools.util.Motion.exit(changedAt);
        // Meteor moves the progress on by its own step before drawing; starting a step back
        // leaves it where this put it, so the list is still drawn while it closes.
        animProgress = Math.max(0, Math.min(1, progress - (expanded ? 1 : -1) * delta * 14));
        boolean result = super.render(renderer, mouseX, mouseY, delta);
        animProgress = progress;
        return result;
    }

    @Override
    protected WDropdownRoot createRootWidget() {
        return new WRoot();
    }

    @Override
    protected WDropdownValue createValueWidget() {
        return new WValue();
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        JJGuiTheme theme = theme();
        double pad = pad();
        double s = theme.textHeight();

        renderBackground(renderer, this, pressed, mouseOver);
        boolean hot = pressed || mouseOver;

        String text = theme.shown(get().toString());
        double w = theme.textWidth(text);
        renderer.text(text, x + pad + maxValueWidth / 2 - w / 2, y + pad, theme.ink(hot), false);

        renderer.rotatedQuad(x + pad + maxValueWidth + pad, y + pad, s, s, 0, GuiRenderer.TRIANGLE, theme.ink(hot));
    }

    private static class WRoot extends WDropdownRoot implements JJWidget {
        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            JJGuiTheme theme = theme();
            // The open list as the panel's wells: a rounded area a shade off the window.
            round(renderer, x, y, width, height, theme.radius(), theme.wellColor.get());
        }
    }

    private class WValue extends WDropdownValue implements JJWidget {
        @Override
        protected void onCalculateSize() {
            double pad = pad();

            width = pad + theme.textWidth(value.toString()) + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            JJGuiTheme theme = theme();

            // Each choice as the panel's: plain in the list, the main colour when it is the one
            // picked, the text colour under the mouse.
            boolean picked = value == WJJDropdown.this.get();
            boolean hot = pressed || mouseOver;
            if (pressed) round(renderer, x, y, width, height, theme.radius(), theme.textSecondaryColor.get());
            else if (mouseOver) round(renderer, x, y, width, height, theme.radius(), theme.textColor.get());
            else if (picked) round(renderer, x, y, width, height, theme.radius(), theme.accentColor.get());

            String text = theme.shown(value.toString());
            renderer.text(text, x + width / 2 - theme.textWidth(text) / 2, y + pad(), theme.ink(hot || picked), false);
        }
    }
}
