/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.WHorizontalSeparator;

public class WJJHorizontalSeparator extends WHorizontalSeparator implements JJWidget {
    public WJJHorizontalSeparator(String text) {
        super(text);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (text == null) renderWithoutText(renderer);
        else renderWithText(renderer);
    }

    /** A thin line across, faint, as the panel's dividers. */
    private void renderWithoutText(GuiRenderer renderer) {
        JJGuiTheme theme = theme();
        double s = Math.max(1, Math.round(theme.unit()));
        renderer.quad(x, y + height / 2 - s / 2, width, s, theme.separatorCenter.get());
    }

    /** A heading in the dim colour at the left, as monkeytype's settings sections are, with no lines. */
    private void renderWithText(GuiRenderer renderer) {
        JJGuiTheme theme = theme();
        renderer.text(theme.shown(text), x, y, theme.separatorText.get(), false);
    }
}
