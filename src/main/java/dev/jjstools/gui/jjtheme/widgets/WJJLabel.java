/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.WLabel;

public class WJJLabel extends WLabel implements JJWidget {
    /** Whether this is a title: drawn in the dim colour like the panel's titles, but at the same size as other text, as there. */
    private final boolean heading;

    public WJJLabel(String text, boolean title) {
        super(text, false);
        this.heading = title;
    }

    @Override
    protected void onCalculateSize() {
        // Measured as it is drawn, lowercase or not.
        width = theme.textWidth(theme().shown(text), text.length(), false);
        height = theme.textHeight(false);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (!text.isEmpty()) {
            // Monkeytype's settings pages: the words you read in the text colour, headings in the
            // dim sub colour. Labels Meteor itself marks as secondary keep their own colour.
            renderer.text(theme().shown(text), x, y, color != null ? color : (heading ? theme().titleTextColor.get() : theme().textColor.get()), false);
        }
    }
}
