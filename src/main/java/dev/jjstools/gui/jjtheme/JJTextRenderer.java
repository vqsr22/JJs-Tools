package dev.jjstools.gui.jjtheme;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.renderer.Fonts;
import meteordevelopment.meteorclient.renderer.MeshBuilder;
import meteordevelopment.meteorclient.renderer.MeshRenderer;
import meteordevelopment.meteorclient.renderer.MeteorRenderPipelines;
import meteordevelopment.meteorclient.renderer.text.CustomTextRenderer;
import meteordevelopment.meteorclient.renderer.text.Font;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.client.MinecraftClient;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sharp text for JJ's theme, at any GUI scale.
 *
 * Meteor bakes its font at five sizes and squashes the nearest one to the size it needs, so at
 * a scale such as 0.75 a 27 pixel font is shrunk to about 13 pixels and the letters smear. This
 * draws the same font but bakes it at exactly the size each scale needs, so every letter lands
 * one texture pixel to one screen pixel, and starts each line on a whole pixel. It measures text
 * the same way Meteor's does, so layouts do not change.
 *
 * Written along the lines of Meteor's CustomTextRenderer (GPL-3.0), whose metrics it keeps.
 */
public final class JJTextRenderer implements TextRenderer {
    private static JJTextRenderer instance;

    private final MeshBuilder mesh = new MeshBuilder(MeteorRenderPipelines.UI_TEXT);
    private final FontFace face;
    private final ByteBuffer buffer;
    /** The size Meteor measures at, so widths and heights match its own renderer. */
    private final Font measure;
    /** Fonts baked at exact sizes, the most recently used last; only a few are kept. */
    private final Map<Integer, Font> sized = new LinkedHashMap<>(8, 0.75F, true);

    private Font font;
    private boolean building;
    private boolean scaleOnly;
    private double scale = 1;

    private JJTextRenderer(FontFace face) throws Exception {
        this.face = face;
        this.buffer = dev.jjstools.compat.Compat.fontBytes(face);
        this.measure = new Font(buffer, 27);
    }

    /** The sharp renderer for Meteor's current font, or Meteor's own when its custom font is off. */
    public static TextRenderer get() {
        TextRenderer meteor = TextRenderer.get();
        if (!(meteor instanceof CustomTextRenderer custom)) return meteor;
        JJTextRenderer r = instance;
        if (r == null || r.face != custom.fontFace) {
            try {
                if (r != null) r.destroy();
                instance = r = new JJTextRenderer(custom.fontFace);
            } catch (Exception e) {
                JJsTools.LOG.warn("JJ's theme could not load its sharp text, using Meteor's", e);
                return meteor;
            }
        }
        return r;
    }

    /**
     * A font baked so that it is drawn at its own size: Meteor's text is 18 pixels high at scale 1.
     *
     * Baking one is slow (a 2048 pixel texture), so sizes are kept rather than thrown away: if the
     * GUI ever asked for more sizes than were kept, it would bake fonts every frame. Sizes in
     * between share the nearest even size once text is large, where a pixel either way cannot be seen.
     */
    private Font fontFor(double scale) {
        int px = (int) Math.max(6, Math.min(160, Math.round(18 * scale)));
        if (px > 40) px &= ~1;
        Font f = sized.get(px);
        if (f == null) {
            f = new Font(buffer, px);
            sized.put(px, f);
            if (sized.size() > 12) {
                Integer oldest = sized.keySet().iterator().next();
                Font old = sized.get(oldest);
                if (old != font && old != f) {
                    sized.remove(oldest);
                    old.texture.close();
                }
            }
        }
        return f;
    }

    @Override
    public void setAlpha(double a) {
        mesh.alpha = a;
    }

    @Override
    public void begin(double scale, boolean scaleOnly, boolean big) {
        if (building) throw new RuntimeException("JJTextRenderer.begin() called twice");
        if (!scaleOnly) mesh.begin();
        // Titles are drawn a quarter bigger, as in Meteor.
        double want = big ? scale * 1.25 : scale;
        font = fontFor(want);
        building = true;
        this.scaleOnly = scaleOnly;
        // What it takes to draw this font at the size Meteor would have drawn it.
        this.scale = want * 18.0 / font.getHeight();
    }

    @Override
    public double getWidth(String text, int length, boolean shadow) {
        if (text.isEmpty()) return 0;
        if (building) return (font.getWidth(text, length) + (shadow ? 1 : 0)) * scale;
        return (measure.getWidth(text, length) + (shadow ? 1 : 0)) / 1.5;
    }

    @Override
    public double getHeight(boolean shadow) {
        if (building) return (font.getHeight() + 1 + (shadow ? 1 : 0)) * scale;
        return (measure.getHeight() + 1 + (shadow ? 1 : 0)) / 1.5;
    }

    @Override
    public double render(String text, double x, double y, Color color, boolean shadow) {
        boolean wasBuilding = building;
        if (!wasBuilding) begin();
        shadow = shadow || JJGuiTheme.shadowText();
        // Each line starts on a whole pixel, so the letters are not blurred across two.
        double rx = Math.round(x), ry = Math.round(y);
        double width;
        if (shadow) {
            Color sc = CustomTextRenderer.SHADOW_COLOR;
            int pre = sc.a;
            sc.a = (int) (color.a / 255.0 * pre);
            double off = Math.max(1, Math.round(scale));
            font.render(mesh, text, rx + off, ry + off, sc, scale);
            sc.a = pre;
        }
        width = font.render(mesh, text, rx, ry, color, scale);
        if (!wasBuilding) end();
        return width;
    }

    @Override
    public boolean isBuilding() {
        return building;
    }

    @Override
    public void end() {
        if (!building) throw new RuntimeException("JJTextRenderer.end() called without calling begin()");
        if (!scaleOnly) {
            mesh.end();
            MeshRenderer.begin()
                .attachments(MinecraftClient.getInstance().getFramebuffer())
                .pipeline(MeteorRenderPipelines.UI_TEXT)
                .mesh(mesh)
                //#if MC >= 1.21.9
                .sampler("u_Texture", font.texture.getGlTextureView(), font.texture.getSampler())
                //#elif MC >= 1.21.6
                //$$ .sampler("u_Texture", font.texture.getGlTextureView())
                //#else
                //$$ .setupCallback(pass -> pass.bindSampler("u_Texture", font.texture.getGlTexture()))
                //#endif
                .end();
        }
        building = false;
        scale = 1;
    }

    private void destroy() {
        for (Font f : sized.values()) f.texture.close();
        measure.texture.close();
        sized.clear();
    }
}
