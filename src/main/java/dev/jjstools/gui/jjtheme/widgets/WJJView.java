/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package dev.jjstools.gui.jjtheme.widgets;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WView;

public class WJJView extends WView implements JJWidget {
    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        if (canScroll && hasScrollBar) {
            // A thin bar at the right edge, as the panel's scroll bars.
            double u = theme().unit();
            double bw = Math.min(handleWidth(), u * 3);
            // Monkeytype's corners on its scroll bar are small, two pixels at its sizes.
            //#if MC >= 1.21.9
            round(renderer, handleX() + handleWidth() - bw, handleY() + u, bw, handleHeight() - u * 2, u, theme().scrollbarColor.get(focused, handleMouseOver));
            //#else
            //$$ round(renderer, handleX() + handleWidth() - bw, handleY() + u, bw, handleHeight() - u * 2, u, theme().scrollbarColor.get(handlePressed, handleMouseOver));
            //#endif
        }
    }
}
