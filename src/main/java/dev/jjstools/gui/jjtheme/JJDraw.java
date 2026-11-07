package dev.jjstools.gui.jjtheme;

import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.renderer.packer.GuiTexture;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.util.Identifier;

/**
 * Rounded rectangles for Meteor's GUI renderer, drawn the way the Control Panel draws its own.
 *
 * Each corner is a smooth, anti-aliased quarter circle from a small texture, turned to suit
 * the corner, so the curve is as soft as the Control Panel's rather than stepped. The straight
 * parts are plain quads, and no two pieces overlap, so a see-through colour stays even.
 */
public final class JJDraw {
    /** The biggest corner baked at its own size; bigger ones are scaled from the large image. */
    private static final int MAX_BAKED = 40;

    /** A quarter circle, its curve at the top left, at every size from 1 to MAX_BAKED pixels, index by size. */
    static final GuiTexture[] CORNERS = new GuiTexture[MAX_BAKED + 1];
    /** The large one, for corners bigger than any baked. */
    static GuiTexture CORNER;
    /** A magnifying glass, for the search bar. */
    public static GuiTexture SEARCH;

    private JJDraw() {}

    /**
     * Called while the game starts, before Meteor packs its GUI textures. Each corner size gets
     * its own image, so a corner is drawn pixel for pixel rather than squashed from a larger one,
     * which is what made small corners lumpy.
     */
    static void registerTextures() {
        if (CORNER != null) return;
        CORNER = GuiRenderer.addTexture(Identifier.of("jjs-tools", "textures/gui/corner.png"));
        SEARCH = GuiRenderer.addTexture(Identifier.of("jjs-tools", "textures/gui/search.png"));
        for (int i = 1; i <= MAX_BAKED; i++) {
            CORNERS[i] = GuiRenderer.addTexture(Identifier.of("jjs-tools", "textures/gui/corner_" + i + ".png"));
        }
    }

    public static void round(GuiRenderer renderer, double x, double y, double w, double h, double radius, Color color) {
        round(renderer, x, y, w, h, radius, color, true, true);
    }

    /**
     * A rectangle with its top corners, bottom corners or both rounded.
     *
     * Everything is put on whole pixels first. The pieces meet edge to edge, and on fractions of
     * a pixel the edges they share were each drawn half see-through, which showed as thin lines
     * across slider thumbs and between buttons.
     */
    public static void round(GuiRenderer renderer, double x, double y, double w, double h, double radius, Color color, boolean top, boolean bottom) {
        // Nothing to see: nothing drawn, which spares the outlines buttons do not show.
        if (color.a == 0) return;
        int x0 = (int) Math.round(x), y0 = (int) Math.round(y);
        int x1 = (int) Math.round(x + w), y1 = (int) Math.round(y + h);
        int iw = x1 - x0, ih = y1 - y0;
        if (iw <= 0 || ih <= 0) return;
        int r = (int) Math.min(Math.round(radius), Math.min(iw, ih) / 2);
        if (r < 1 || (!top && !bottom)) {
            renderer.quad(x0, y0, iw, ih, color);
            return;
        }
        int tr = top ? r : 0, br = bottom ? r : 0;
        if (ih - tr - br > 0) renderer.quad(x0, y0 + tr, iw, ih - tr - br, color);
        if (top) {
            if (iw - r * 2 > 0) renderer.quad(x0 + r, y0, iw - r * 2, r, color);
            corner(renderer, x0, y0, r, 0, color);
            corner(renderer, x1 - r, y0, r, 90, color);
        }
        if (bottom) {
            if (iw - r * 2 > 0) renderer.quad(x0 + r, y1 - r, iw - r * 2, r, color);
            corner(renderer, x1 - r, y1 - r, r, 180, color);
            corner(renderer, x0, y1 - r, r, 270, color);
        }
    }

    /** One corner's square, the curve turned to face its corner: 0 top left, 90 top right, 180 bottom right, 270 bottom left. */
    private static void corner(GuiRenderer renderer, int x, int y, int r, double turn, Color color) {
        GuiTexture tex = r <= MAX_BAKED && CORNERS[r] != null ? CORNERS[r] : CORNER;
        if (tex != null) {
            renderer.rotatedQuad(x, y, r, r, turn, tex, color);
            return;
        }
        // Without the textures, a fan of thin triangles around the corner's centre.
        double cx = x + (turn == 0 || turn == 270 ? r : 0);
        double cy = y + (turn == 0 || turn == 90 ? r : 0);
        double start = 180 + turn;
        int n = Math.max(3, Math.min(10, r));
        for (int i = 0; i < n; i++) {
            double a1 = Math.toRadians(start + 90.0 * i / n);
            double a2 = Math.toRadians(start + 90.0 * (i + 1) / n);
            renderer.triangle(cx, cy, cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, cx + Math.cos(a2) * r, cy + Math.sin(a2) * r, color);
        }
    }
}
