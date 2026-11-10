package dev.jjstools.panel;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.renderer.text.FontFace;
import meteordevelopment.meteorclient.systems.config.Config;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import org.joml.Matrix3x2f;
import org.lwjgl.BufferUtils;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.stb.STBTruetype;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * The Control Panel's own fonts: the one it is drawn in, picked on the font page, and the
 * previews on that page, each font's name drawn in that font.
 *
 * Meteor draws its own text straight onto the screen, which would end up under everything the
 * panel draws, so fonts are read here and their letters baked into ordinary textures at the
 * exact pixel size they show at. They are then drawn like any other picture, in order with the
 * rest of the panel and cut off by the same clipping, and stay sharp at any scale.
 */
public final class UiFont {
    /** How tall the letters are, in the same units as Minecraft's 9 pixel high text. */
    private static final float EM = 10.0F;
    /** Where the line sits, the same as Minecraft's text, so the two line up. */
    private static final float BASELINE = 7.0F;

    /** The characters baked for the panel's font, as first codepoint and how many. */
    private static final int[][] FULL = {
        { 32, 95 },     // Basic Latin
        { 160, 96 },    // Latin-1
        { 256, 128 },   // Latin Extended-A
        { 0x2010, 32 }, // dashes, quotes, bullet, dots
    };
    /** Previews only show a font's name, so only plain letters are baked for them. */
    private static final int[][] PREVIEW = { { 32, 95 } };

    private static final class Atlas {
        final Identifier id;
        final int size;
        final Map<Integer, STBTTPackedchar> chars = new HashMap<>();
        /** Kept so the memory the characters above point into stays alive. */
        STBTTPackedchar.Buffer[] packed;

        Atlas(Identifier id, int size) {
            this.id = id;
            this.size = size;
        }
    }

    /** One loaded font, with its letters baked at whatever sizes it has been drawn at. */
    public static final class Face {
        private final String key;
        private final int[][] ranges;
        private final int maxAtlases;
        private ByteBuffer data;
        private STBTTFontinfo info;
        private float unitScale;
        private final Map<Integer, Float> advance = new HashMap<>();
        private final Map<Integer, Boolean> present = new HashMap<>();
        private final Map<Integer, Atlas> atlases = new HashMap<>();
        private final Deque<Integer> atlasOrder = new ArrayDeque<>();

        private Face(String key, ByteBuffer bytes, int[][] ranges, int maxAtlases) {
            this.key = key;
            this.ranges = ranges;
            this.maxAtlases = maxAtlases;
            try {
                if (bytes == null) throw new IllegalStateException("no font file");
                data = bytes;
                STBTTFontinfo fi = STBTTFontinfo.create();
                if (!STBTruetype.stbtt_InitFont(fi, data)) throw new IllegalStateException("not a font");
                int ascent, descent;
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer a = stack.mallocInt(1), d = stack.mallocInt(1), g = stack.mallocInt(1);
                    STBTruetype.stbtt_GetFontVMetrics(fi, a, d, g);
                    ascent = a.get(0);
                    descent = d.get(0);
                }
                info = fi;
                unitScale = EM / Math.max(1, ascent - descent);
            } catch (Throwable t) {
                info = null;
                JJsTools.LOG.warn("Couldn't load the Control Panel font " + key, t);
            }
        }

        boolean ok() {
            return info != null;
        }

        void destroy() {
            MinecraftClient mc = MinecraftClient.getInstance();
            for (Atlas atlas : atlases.values()) if (atlas != null) mc.getTextureManager().destroyTexture(atlas.id);
            atlases.clear();
            atlasOrder.clear();
        }

        /** Whether the font has this character; ones it lacks are drawn in Minecraft's font. */
        private boolean has(int cp) {
            if (cp == ' ') return true;
            return present.computeIfAbsent(cp, c -> {
                boolean baked = false;
                for (int[] r : ranges) if (c >= r[0] && c < r[0] + r[1]) baked = true;
                return baked && STBTruetype.stbtt_FindGlyphIndex(info, c) != 0;
            });
        }

        /** How far a character moves the pen, in panel units at scale 1. */
        private float advance(int cp) {
            return advance.computeIfAbsent(cp, c -> {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    IntBuffer adv = stack.mallocInt(1), lsb = stack.mallocInt(1);
                    STBTruetype.stbtt_GetCodepointHMetrics(info, c, adv, lsb);
                    return adv.get(0) * unitScale;
                }
            });
        }

        /** The width of some text at scale 1, in the same units as Minecraft's TextRenderer. */
        public float width(TextRenderer tr, String s) {
            float w = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                w += has(c) ? advance(c) : tr.getWidth(String.valueOf(c));
            }
            return w;
        }

        /**
         * Draws text with its top left at x, y in the context's current units, scaled by scale.
         * Gives how many pieces it drew, a letter each, for the panel to keep count of.
         */
        public int draw(DrawContext ctx, TextRenderer tr, String s, int x, int y, float scale, int colour) {
            MinecraftClient mc = MinecraftClient.getInstance();
            var matrices = ctx.getMatrices();
            matrices.pushMatrix();
            matrices.translate(x, y);

            // How many screen pixels one panel unit of text is, so the letters are baked for it exactly.
            Matrix3x2f m = matrices;
            float window = mc.getWindow().getScaleFactor();
            float perUnit = Math.abs(m.m00) * window * scale;
            int px = Math.round(EM * perUnit);
            if (px < 5 || px > 160) {
                matrices.popMatrix();
                fallback(ctx, tr, s, x, y, scale, colour);
                return 1;
            }

            // Moves onto whole screen pixels, then works in screen pixels.
            float ox = m.m20 * window, oy = m.m21 * window;
            float toUnits = 1.0F / (Math.abs(m.m00) * window);
            matrices.translate((Math.round(ox) - ox) * toUnits, (Math.round(oy) - oy) * toUnits);
            matrices.scale(toUnits, toUnits);

            Atlas atlas = atlas(px);
            int baseline = Math.round(BASELINE * perUnit);
            float pen = 0;
            int drawn = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (!has(c)) {
                    // A character the font has not got, such as an arrow, in Minecraft's font.
                    matrices.pushMatrix();
                    matrices.translate(Math.round(pen), 0);
                    matrices.scale(perUnit, perUnit);
                    ctx.drawText(tr, String.valueOf(c), 0, 0, colour, false);
                    drawn++;
                    matrices.popMatrix();
                    pen += tr.getWidth(String.valueOf(c)) * perUnit;
                    continue;
                }
                STBTTPackedchar g = atlas == null ? null : atlas.chars.get((int) c);
                if (g != null && c != ' ') {
                    int w = g.x1() - g.x0(), h = g.y1() - g.y0();
                    if (w > 0 && h > 0) {
                        int gx = Math.round(pen + g.xoff());
                        int gy = baseline + Math.round(g.yoff());
                        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, atlas.id, gx, gy, g.x0(), g.y0(), w, h, w, h, atlas.size, atlas.size, colour);
                        drawn++;
                    }
                }
                pen += advance(c) * perUnit;
            }
            matrices.popMatrix();
            return drawn;
        }

        /** The letters baked at a pixel height, made the first time that size is needed. */
        private Atlas atlas(int px) {
            Atlas atlas = atlases.get(px);
            if (atlas != null) return atlas;
            if (atlases.containsKey(px)) return null;

            // Enough room for every baked character, doubled until they all fit.
            int count = 0;
            for (int[] r : ranges) count += r[1];
            int size = 64;
            while ((long) size * size < (long) (px + 4) * (px + 4) * count * 0.7) size *= 2;

            while (size <= 4096) {
                ByteBuffer bitmap = BufferUtils.createByteBuffer(size * size);
                STBTTPackedchar.Buffer[] packed = new STBTTPackedchar.Buffer[ranges.length];
                STBTTPackContext pack = STBTTPackContext.create();
                STBTruetype.stbtt_PackBegin(pack, bitmap, size, size, 0, 1);
                STBTTPackRange.Buffer rs = STBTTPackRange.create(ranges.length);
                for (int i = 0; i < ranges.length; i++) {
                    packed[i] = STBTTPackedchar.create(ranges[i][1]);
                    rs.put(STBTTPackRange.create().set(px, ranges[i][0], null, ranges[i][1], packed[i], (byte) 1, (byte) 1));
                }
                rs.flip();
                boolean ok = STBTruetype.stbtt_PackFontRanges(pack, data, 0, rs);
                STBTruetype.stbtt_PackEnd(pack);
                if (!ok) {
                    size *= 2;
                    continue;
                }

                NativeImage image = new NativeImage(size, size, true);
                for (int yy = 0; yy < size; yy++) {
                    for (int xx = 0; xx < size; xx++) {
                        int a = bitmap.get(yy * size + xx) & 0xFF;
                        image.setColorArgb(xx, yy, (a << 24) | 0xFFFFFF);
                    }
                }
                int n = ++textures;
                Identifier id = Identifier.of("jjs-tools", "panel_font/" + n);
                NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "JJ's Tools panel font " + key + " " + px, image);
                texture.upload();
                MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);

                atlas = new Atlas(id, size);
                atlas.packed = packed;
                for (int i = 0; i < ranges.length; i++) {
                    for (int j = 0; j < ranges[i][1]; j++) atlas.chars.put(ranges[i][0] + j, packed[i].get(j));
                }
                atlases.put(px, atlas);
                atlasOrder.addLast(px);
                // Only a few sizes are kept; the oldest goes when the text or GUI scale changes a lot.
                while (atlasOrder.size() > maxAtlases) {
                    Atlas old = atlases.remove(atlasOrder.removeFirst());
                    if (old != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(old.id);
                }
                return atlas;
            }
            atlases.put(px, null);
            return null;
        }
    }

    private static int textures;
    /** The font the panel is drawn in, or null for Minecraft's. */
    private static Face current;
    /** Which font that is: "meteor:" and the font, or "font:" and one of PanelFonts'. */
    private static String loadedKey;
    /** The font page's previews, by font. */
    private static final Map<String, Face> previews = new HashMap<>();
    /** Goes up each time a different font is loaded, so remembered widths can be thrown away. */
    public static int generation;

    private UiFont() {}

    /** What a font choice is loaded as: its key, and how to read its file (null while downloading). */
    private record Source(String key, java.util.function.Supplier<ByteBuffer> bytes) {}

    private static Source source(String choice) {
        if (choice == null || choice.equals(PanelFonts.MINECRAFT)) return null;
        if (choice.equals(PanelFonts.METEOR)) {
            FontFace chosen;
            try {
                chosen = Config.get().font.get();
            } catch (Throwable t) {
                return null;
            }
            if (chosen == null) return null;
            return new Source("meteor:" + System.identityHashCode(chosen), () -> {
                try {
                    return dev.jjstools.compat.Compat.fontBytes(chosen);
                } catch (Throwable t) {
                    return null;
                }
            });
        }
        return new Source("font:" + choice, () -> PanelFonts.data(choice));
    }

    /**
     * Whether the panel draws in a font of its own this frame, rather than Minecraft's, loading
     * it if the choice changed: one of PanelFonts', or Meteor's custom font.
     */
    public static boolean ready() {
        Source src = source(Prefs.get().font);
        if (src == null) return false;
        if (!src.key().equals(loadedKey)) {
            // Still downloading: Minecraft's font until it is here.
            ByteBuffer bytes = src.bytes().get();
            if (bytes == null) return false;
            if (current != null) current.destroy();
            current = new Face(src.key(), bytes, FULL, 6);
            loadedKey = src.key();
            generation++;
        }
        return current != null && current.ok();
    }

    /**
     * A font for the font page to draw its own name in, loaded the first time it is asked for,
     * or null for Minecraft's font and while it is still downloading.
     */
    public static Face preview(String choice) {
        Source src = source(choice);
        if (src == null) return null;
        Face face = previews.get(src.key());
        if (face == null) {
            ByteBuffer bytes = src.bytes().get();
            if (bytes == null) return null;
            face = new Face(src.key(), bytes, PREVIEW, 1);
            previews.put(src.key(), face);
        }
        return face.ok() ? face : null;
    }

    /** Lets the previews go when the font page closes, freeing their textures. */
    public static void dropPreviews() {
        for (Face f : previews.values()) f.destroy();
        previews.clear();
    }

    /** Whether this text can be drawn in the custom font; text with colour codes stays in Minecraft's. */
    public static boolean handles(String s) {
        return s.indexOf('§') < 0;
    }

    /** The width of some text at scale 1 in the panel's font. */
    public static float width(TextRenderer tr, String s) {
        return current == null ? tr.getWidth(s) : current.width(tr, s);
    }

    /** Draws text in the panel's font. Gives how many pieces it drew. */
    public static int draw(DrawContext ctx, TextRenderer tr, String s, int x, int y, float scale, int colour) {
        if (current == null) {
            fallback(ctx, tr, s, x, y, scale, colour);
            return 1;
        }
        return current.draw(ctx, tr, s, x, y, scale, colour);
    }

    private static void fallback(DrawContext ctx, TextRenderer tr, String s, int x, int y, float scale, int colour) {
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawText(tr, s, 0, 0, colour, false);
        ctx.getMatrices().popMatrix();
    }
}
