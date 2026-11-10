package dev.jjstools.gui;

import dev.jjstools.gui.jjtheme.JJDraw;
import meteordevelopment.meteorclient.gui.renderer.GuiRenderer;
import meteordevelopment.meteorclient.gui.widgets.pressable.WPressable;
import meteordevelopment.meteorclient.utils.render.color.Color;

/**
 * A box to click in the themes and fonts tabs, like the Control Panel's: a rounded tile in
 * colours of its own, the name inside, and three dots in the theme's colours on theme tiles.
 * Picked tiles get an outline in their accent colour; hovering swaps the background and text,
 * as monkeytype does.
 */
public class WSwatch extends WPressable {
    private final String label;
    private final Color background = new Color();
    private final Color text = new Color();
    private final Color accent = new Color();
    private final Color dim = new Color();
    private final boolean dots;
    private final double minWidth;
    public boolean picked;
    /** On the fonts tab: the font whose name this is, drawn in that font. Null on theme tiles. */
    public String fontId;

    public WSwatch(String label, int background, int text, int accent, int dim, boolean dots, double minWidth) {
        this.label = label;
        set(this.background, background);
        set(this.text, text);
        set(this.accent, accent);
        set(this.dim, dim);
        this.dots = dots;
        this.minWidth = minWidth;
    }

    private static void set(Color c, int argb) {
        c.set((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, 255);
    }

    /** The font to draw the name in, or null for Meteor's own (the "meteor" tile, and fonts still downloading). */
    private dev.jjstools.panel.UiFont.Face face() {
        if (fontId == null || fontId.equals(dev.jjstools.panel.PanelFonts.METEOR)) return null;
        return dev.jjstools.panel.UiFont.preview(fontId);
    }

    private boolean minecraftFont() {
        return dev.jjstools.panel.PanelFonts.MINECRAFT.equals(fontId);
    }

    /** The Control Panel's font sizes are in Minecraft's nine-high text; this is that at Meteor's text height. */
    private float fontScale() {
        return (float) (theme.textHeight() / 9.0);
    }

    private double labelWidth() {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (minecraftFont()) return mc.textRenderer.getWidth(label) * fontScale();
        var f = face();
        if (f != null) return f.width(mc.textRenderer, label) * fontScale();
        return theme.textWidth(label);
    }

    /**
     * Draws the name in its own font after Meteor has drawn its own text, as the Control Panel's
     * font page does. False when there is no other font to draw it in.
     */
    private boolean drawPreview(GuiRenderer renderer, double tx, double ty, Color ink) {
        if (fontId == null) return false;
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        boolean vanilla = minecraftFont();
        var f = vanilla ? null : face();
        if (!vanilla && f == null) return false;
        int argb = (ink.a << 24) | (ink.r << 16) | (ink.g << 8) | ink.b;
        float k = fontScale();
        int px = (int) Math.round(tx), py = (int) Math.round(ty);
        renderer.post(() -> {
            var ctx = ((dev.jjstools.mixin.GuiRendererAccessor) renderer).jjstools$getDrawContext();
            if (ctx == null) return;
            if (vanilla) {
                dev.jjstools.compat.Gui.push(ctx);
                dev.jjstools.compat.Gui.translate(ctx, px, py);
                dev.jjstools.compat.Gui.scale(ctx, k, k);
                ctx.drawText(mc.textRenderer, label, 0, 0, argb, false);
                dev.jjstools.compat.Gui.pop(ctx);
            } else {
                f.draw(ctx, mc.textRenderer, label, px, py, k, argb);
            }
        });
        return true;
    }

    /** Plenty of room round the name, as monkeytype's theme tiles have. */
    private double tilePad() {
        return Math.round(theme.textHeight() * 0.625);
    }

    /** The gap between tiles, and round the grid's edge: monkeytype's half a line. */
    public static double gap(meteordevelopment.meteorclient.gui.GuiTheme theme) {
        return Math.round(theme.textHeight() * 0.5);
    }

    @Override
    protected void onCalculateSize() {
        double pad = tilePad();
        double dotsW = dots ? theme.scale(6) * 3 + theme.scale(3) * 2 + pad : 0;
        // Monkeytype's tiles: two and a quarter lines tall and a little over eight times as wide
        // as tall, every tile the same, with room for the palette on both sides of the name.
        height = Math.round(theme.textHeight() * 2.25);
        width = Math.max(Math.round(height * 8.33), pad + dotsW + labelWidth() + dotsW + pad);
    }

    @Override
    protected void onRender(GuiRenderer renderer, double mouseX, double mouseY, double delta) {
        double r = theme.scale(4);
        if (picked) {
            // On whole pixels first, then pulled in by the same whole number on every side, so
            // the ring is even all round; drawn from fractions, each side rounded its own way.
            int x0 = (int) Math.round(x), y0 = (int) Math.round(y);
            int x1 = (int) Math.round(x + width), y1 = (int) Math.round(y + height);
            int s = (int) Math.max(2, Math.round(theme.scale(3)));
            JJDraw.round(renderer, x0, y0, x1 - x0, y1 - y0, r, accent);
            JJDraw.round(renderer, x0 + s, y0 + s, x1 - x0 - s * 2, y1 - y0 - s * 2, Math.max(0, r - s), mouseOver ? text : background);
        } else {
            JJDraw.round(renderer, x, y, width, height, r, mouseOver ? text : background);
        }
        double pad = tilePad();
        Color ink = mouseOver ? background : text;
        // The name in the middle, as monkeytype's are.
        double tx = x + (width - labelWidth()) / 2;
        double ty = y + (height - theme.textHeight()) / 2;
        if (!drawPreview(renderer, tx, ty, ink)) renderer.text(label, tx, ty, ink, false);
        // The palette only on the one picked, as monkeytype shows it.
        if (dots && picked) {
            double d = theme.scale(6);
            double gap = theme.scale(3);
            double dx = x + width - pad - d * 3 - gap * 2;
            double dy = y + (height - d) / 2;
            renderer.quad(dx, dy, d, d, GuiRenderer.CIRCLE, accent);
            renderer.quad(dx + d + gap, dy, d, d, GuiRenderer.CIRCLE, dim);
            renderer.quad(dx + (d + gap) * 2, dy, d, d, GuiRenderer.CIRCLE, mouseOver ? background : text);
        }
    }
}
