package dev.jjstools.util;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits a line of text to a width without distorting any of its pixels.
 *
 * A font glyph is a grid of square pixels. Drawn at some fraction of full size, each of those
 * pixels covers a run of screen pixels, and the length of that run is whatever the maths lands
 * on: at three quarters, one font pixel covers two screen pixels and the next covers three. That
 * pattern runs the same way down as it does across, so a pixel one along and level with the start
 * comes out three wide and two tall. The only sizes free of it are the ones where a font pixel
 * covers a whole number of screen pixels, and those are a whole number over the GUI scale: at a
 * GUI scale of 3, two thirds or full size and nothing in between.
 *
 * That leaves a line that is a little too long with nowhere to go, since the next size down is a
 * long way down. So the gap is taken out of the spaces instead, which are the one part of a line
 * that can give without anything being redrawn. A title nine pixels too wide with three spaces in
 * it does not need shrinking at all: take three pixels out of each space and it fits at full size,
 * every letter untouched.
 *
 * Sizes are tried from full size downward, and the first one the spaces can absorb is the one
 * used, so the text stays as large as it possibly can. The letters themselves are never moved
 * relative to each other, never restyled and never rescaled on one axis: at a whole-number size
 * with every glyph starting on a whole pixel, drawing them one at a time is pixel for pixel what
 * vanilla would have drawn.
 */
public final class FittedText {
    /** How thin a space may get, in font pixels. Letters carry their own trailing pixel too. */
    private static final int MIN_SPACE = 1;

    /** How much air a space may gain, in font pixels, when there is width going spare. */
    private static final int MAX_SPACE_GAIN = 4;

    private FittedText() {}

    /**
     * Draws the text to fit, or returns false if it cannot be done this way.
     *
     * False means the caller should fall back: at a GUI scale of 1 there is no size below full
     * size on the pixel grid, and a line with no spaces in it has nothing to give.
     *
     * @param flexSpaces whether the spaces may change width to close the gap. Off is a plain
     *                   step down to the largest size that fits on its own, which leaves the
     *                   writing completely untouched but can stop short of the width.
     */
    public static boolean draw(DrawContext context, TextRenderer textRenderer, Text text,
                               int x, int y, int limit, int colour, boolean shadow,
                               boolean flexSpaces) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.getWindow() == null || limit <= 0) return false;

        int gui = dev.jjstools.compat.Gui.scaleFactor();
        int budget = limit * gui;

        /*
         * One drawable per character, each keeping its own styling.
         *
         * Taken from the ordered text rather than from the string so colour, bold and the font a
         * character asks for all survive being drawn separately, and so a character built from a
         * surrogate pair stays one character.
         */
        List<OrderedText> glyphs = new ArrayList<>();
        List<Boolean> spaces = new ArrayList<>();
        text.asOrderedText().accept((index, style, codePoint) -> {
            glyphs.add(OrderedText.styled(codePoint, style));
            spaces.add(codePoint == ' ');
            return true;
        });
        if (glyphs.isEmpty()) return false;

        int count = glyphs.size();
        int[] widths = new int[count];
        int natural = 0;
        int spaceCount = 0;
        int spaceWidth = 0;

        for (int i = 0; i < count; i++) {
            widths[i] = textRenderer.getWidth(glyphs.get(i));
            natural += widths[i];
            if (spaces.get(i)) {
                spaceCount++;
                spaceWidth += widths[i];
            }
        }
        if (natural <= 0) return false;

        // Biggest first, so the text is only ever made smaller when it has to be.
        for (int step = gui; step >= 1; step--) {
            int base = natural * step;
            int over = base - budget;

            if (over <= 0) {
                // It fits at this size. Spaces may take up what is left, within reason.
                int give = 0;
                if (flexSpaces && spaceCount > 0) {
                    give = Math.min(MAX_SPACE_GAIN * step, -over / spaceCount);
                }
                render(context, textRenderer, glyphs, spaces, widths, x, y, colour, shadow,
                    step, gui, give);
                return true;
            }

            if (!flexSpaces || spaceCount == 0) continue;

            // What the spaces can give up at this size, in screen pixels.
            int room = (spaceWidth - spaceCount * MIN_SPACE) * step;
            if (over > room) continue;

            // Share the shortfall out, the extra pixel going to the first few.
            int each = over / spaceCount;
            int extra = over % spaceCount;
            render(context, textRenderer, glyphs, spaces, widths, x, y, colour, shadow,
                step, gui, -each, extra);
            return true;
        }

        return false;
    }

    private static void render(DrawContext context, TextRenderer textRenderer, List<OrderedText> glyphs,
                               List<Boolean> spaces, int[] widths, int x, int y, int colour,
                               boolean shadow, int step, int gui, int spaceDelta) {
        render(context, textRenderer, glyphs, spaces, widths, x, y, colour, shadow, step, gui, spaceDelta, 0);
    }

    /**
     * Lays the line out in whole screen pixels and draws it.
     *
     * The pen is counted in screen pixels rather than in anything finer, so every character
     * starts exactly on a pixel. A character that starts between two pixels is as distorted as
     * one scaled badly, and the whole point here is that neither happens.
     */
    private static void render(DrawContext context, TextRenderer textRenderer, List<OrderedText> glyphs,
                               List<Boolean> spaces, int[] widths, int x, int y, int colour,
                               boolean shadow, int step, int gui, int spaceDelta, int extraSpaces) {
        float scale = step / (float) gui;

        // Half the height the line loses, rounded to a screen pixel so it stays on the grid.
        float drop = Math.round(textRenderer.fontHeight * (1.0F - scale) * 0.5F * gui) / (float) gui;

        int pen = 0;
        int seenSpaces = 0;

        for (int i = 0; i < glyphs.size(); i++) {
            boolean space = spaces.get(i);
            int advance = widths[i] * step;

            if (space) {
                advance += spaceDelta;
                // The odd pixel that would not divide evenly, given to the first few spaces.
                if (seenSpaces < extraSpaces) advance -= 1;
                seenSpaces++;

                advance = Math.max(advance, MIN_SPACE * step);
                pen += advance;
                continue;
            }

            dev.jjstools.compat.Gui.push(context);
            dev.jjstools.compat.Gui.translate(context, x + pen / (float) gui, y + drop);
            dev.jjstools.compat.Gui.scale(context, scale, scale);
            context.drawText(textRenderer, glyphs.get(i), 0, 0, colour, shadow);
            dev.jjstools.compat.Gui.pop(context);

            pen += advance;
        }
    }
}
