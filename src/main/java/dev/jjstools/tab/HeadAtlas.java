package dev.jjstools.tab;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Packs every player's face (with the hat layer already blended on) into one 512x512 texture.
 *
 * Normally each head in the tab list uses that player's own skin texture, so 500 players means
 * 500 separate draws. With every face in one texture, all heads use the same texture and the GUI
 * renderer can draw them together.
 *
 * Faces are copied on the CPU, once per skin, from the skin image Minecraft keeps in memory
 * (downloaded skins) or from the resource pack (default skins). Anything that cannot be read is
 * reported back as "not handled" and drawn the normal way, so a head is never lost.
 */
public final class HeadAtlas {
    private HeadAtlas() {
    }

    private static final Identifier ID = Identifier.of("jjs-tools", "tab_head_atlas");
    private static final int SIZE = 512;
    private static final int CELL = 8;
    private static final int PER_ROW = SIZE / CELL;
    private static final int MAX_CELLS = PER_ROW * PER_ROW; // 4096
    private static final long RETRY_MS = 2000;

    private static NativeImage image;
    private static NativeImageBackedTexture texture;
    private static final Map<Identifier, Integer> withHat = new HashMap<>();
    private static final Map<Identifier, Integer> noHat = new HashMap<>();
    private static final Map<Identifier, Long> failedUntil = new HashMap<>();
    private static int next;
    private static boolean dirty;

    /** Draws a face from the atlas. Returns false if it could not, so the caller draws it normally. */
    public static boolean draw(DrawContext ctx, Identifier skin, int x, int y, int size, boolean hat, int color) {
        int cell = cellFor(skin, hat);
        if (cell < 0) return false;

        int u = (cell % PER_ROW) * CELL;
        int v = (cell / PER_ROW) * CELL;
        ctx.drawTexture(RenderPipelines.GUI_TEXTURED, ID, x, y, u, v, size, size, CELL, CELL, SIZE, SIZE, color);
        return true;
    }

    /**
     * Sends new faces to the GPU. Called once after the tab list is drawn: GUI draws are only
     * queued at that point and run later in the frame, so one upload covers every new face.
     */
    public static void flush() {
        if (dirty && texture != null) {
            texture.upload();
            dirty = false;
        }
    }

    public static void clear() {
        withHat.clear();
        noHat.clear();
        failedUntil.clear();
        next = 0;
    }

    private static int cellFor(Identifier skin, boolean hat) {
        Map<Identifier, Integer> map = hat ? withHat : noHat;
        Integer cell = map.get(skin);
        if (cell != null) return cell;

        Long retry = failedUntil.get(skin);
        if (retry != null && System.currentTimeMillis() < retry) return -1;

        if (!ensureTexture()) return -1;

        if (next >= MAX_CELLS) clear(); // full: start over, faces get re-copied as they are drawn

        int newCell = next;
        if (!copyFace(skin, hat, newCell)) {
            failedUntil.put(skin, System.currentTimeMillis() + RETRY_MS);
            return -1;
        }

        next++;
        map.put(skin, newCell);
        failedUntil.remove(skin);
        dirty = true;
        return newCell;
    }

    private static boolean ensureTexture() {
        if (texture != null) return true;
        try {
            image = new NativeImage(SIZE, SIZE, true);
            texture = new NativeImageBackedTexture(() -> "JJ's Tools tab head atlas", image);
            MinecraftClient.getInstance().getTextureManager().registerTexture(ID, texture);
            return true;
        } catch (Throwable t) {
            texture = null;
            image = null;
            return false;
        }
    }

    private static boolean copyFace(Identifier skin, boolean hat, int cell) {
        MinecraftClient mc = MinecraftClient.getInstance();
        NativeImage src = null;
        boolean ownsSrc = false;

        try {
            AbstractTexture tex = mc.getTextureManager().getTexture(skin);
            if (tex instanceof NativeImageBackedTexture backed) src = backed.getImage();

            if (src == null) {
                Optional<Resource> resource = mc.getResourceManager().getResource(skin);
                if (resource.isPresent()) {
                    try (InputStream in = resource.get().getInputStream()) {
                        src = NativeImage.read(in);
                        ownsSrc = true;
                    }
                }
            }

            if (src == null || src.getWidth() < 48 || src.getHeight() < 16) return false;

            int cx = (cell % PER_ROW) * CELL;
            int cy = (cell / PER_ROW) * CELL;
            for (int py = 0; py < 8; py++) {
                for (int px = 0; px < 8; px++) {
                    int face = src.getColorArgb(8 + px, 8 + py) | 0xFF000000;
                    if (hat) face = blend(face, src.getColorArgb(40 + px, 8 + py));
                    image.setColorArgb(cx + px, cy + py, face);
                }
            }
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (ownsSrc && src != null) src.close();
        }
    }

    /** Draws the hat pixel over the face pixel, the same as the hat quad would with blending. */
    private static int blend(int face, int hat) {
        int a = (hat >>> 24) & 0xFF;
        if (a == 0) return face;
        if (a == 255) return hat;

        int r = (((hat >> 16) & 0xFF) * a + ((face >> 16) & 0xFF) * (255 - a)) / 255;
        int g = (((hat >> 8) & 0xFF) * a + ((face >> 8) & 0xFF) * (255 - a)) / 255;
        int b = ((hat & 0xFF) * a + (face & 0xFF) * (255 - a)) / 255;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
