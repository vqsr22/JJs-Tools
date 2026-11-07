/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * Adapted for JJ's theme: drawn as monkeytype's settings bar, one rounded strip in the button
 * shade holding the tabs as words, dim at rest, the text colour under the mouse and the main
 * colour for the one open.
 */

package dev.jjstools.gui.jjtheme.widgets;

import dev.jjstools.gui.jjtheme.JJGuiTheme;
import dev.jjstools.gui.jjtheme.JJWidget;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.tabs.Tab;
import meteordevelopment.meteorclient.gui.tabs.TabScreen;
import meteordevelopment.meteorclient.gui.tabs.Tabs;
import meteordevelopment.meteorclient.gui.widgets.WTopBar;
import meteordevelopment.meteorclient.utils.render.color.Color;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class WJJTopBar extends WTopBar implements JJWidget {
    @Override
    protected Color getButtonColor(boolean pressed, boolean hovered) {
        return theme().backgroundColor.get(pressed, hovered, true);
    }

    @Override
    protected Color getNameColor() {
        return theme().textSecondaryColor.get();
    }

    @Override
    public void init() {
        JJGuiTheme theme = theme();
        spacing = Math.round(theme.unit() * 2);
        for (Tab tab : Tabs.get()) add(new WJJTopBarButton(tab)).pad(Math.round(theme.unit() * 2));
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        // The strip: the button shade, rounded, no outline, as monkeytype's.
        round(renderer, x, y, width, height, theme().radius(), theme().backgroundColor.get(false, false, true));
    }

    protected class WJJTopBarButton extends WTopBarButton {
        private final Tab tab;

        public WJJTopBarButton(Tab tab) {
            super(tab);
            this.tab = tab;
        }

        @Override
        protected void onCalculateSize() {
            double pad = pad();
            width = pad + theme.textWidth(theme().shown(tab.name)) + pad;
            height = pad + theme.textHeight() + pad;
        }

        @Override
        protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
            JJGuiTheme theme = theme();
            double pad = pad();
            boolean open = mc.currentScreen instanceof TabScreen screen && screen.tab == tab;

            // Held down, a rounded patch in the window colour behind the word, so the click shows.
            if (pressed) round(renderer, x, y, width, height, theme.radius(), theme.panelColor.get());

            Color ink = pressed ? theme.textColor.get()
                : mouseOver ? theme.textColor.get()
                : open ? theme.accentColor.get()
                : theme.textSecondaryColor.get();
            renderer.text(theme.shown(tab.name), x + pad, y + pad, ink, false);
        }
    }
}
