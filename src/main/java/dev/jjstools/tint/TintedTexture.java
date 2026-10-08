package dev.jjstools.tint;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Optional;

/** Builds a recoloured copy of the resource pack's shulker GUI texture. */
public final class TintedTexture {
    public static final Identifier ID = Identifier.of("jjs-tools", "gui/tinted_shulker_box");
    private static boolean registered;

    private TintedTexture() {}

    /** Returns true if the tinted texture is ready to draw. */
    public static boolean build(Identifier source, int rgb, double strength, int splitY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Optional<Resource> resource = mc.getResourceManager().getResource(source);
        if (resource.isEmpty()) return false;

        try (InputStream in = resource.get().getInputStream()) {
            NativeImage image = NativeImage.read(in);
            double refLum = panelLuminance(image, splitY);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    int argb = image.getColorArgb(x, y);
                    if ((argb >>> 24) == 0) continue;
                    image.setColorArgb(x, y, Colors.colorize(argb, rgb, refLum, strength));
                }
            }

            TextureManager tm = mc.getTextureManager();
            if (registered) tm.destroyTexture(ID);
            NativeImageBackedTexture texture = new NativeImageBackedTexture(() -> "shulkertint tinted shulker box", image);
            texture.upload();
            tm.registerTexture(ID, texture);
            registered = true;
            return true;
        } catch (Exception e) {
            System.err.println("[JJs Tools] Could not build tinted texture: " + e);
            return false;
        }
    }

    /**
     * Brightness of the pack's panel background. Sampled from the title strip (above the slots),
     * so the slot backgrounds can't be mistaken for the panel.
     */
    private static double panelLuminance(NativeImage image, int splitY) {
        double scale = image.getWidth() / 256.0;
        java.util.HashMap<Integer, Integer> counts = new java.util.HashMap<>();
        countColors(image, counts, (int) (7 * scale), (int) (169 * scale), (int) (4 * scale), (int) (16 * scale));
        if (counts.isEmpty()) {
            countColors(image, counts, 0, (int) Math.ceil(176 * scale), 0, (int) Math.ceil(Math.max(1, splitY) * scale));
        }
        int best = 0xFFC6C6C6, bestCount = -1;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return Colors.luminance(best);
    }

    private static void countColors(NativeImage image, java.util.Map<Integer, Integer> counts, int x0, int x1, int y0, int y1) {
        x1 = Math.min(x1, image.getWidth());
        y1 = Math.min(y1, image.getHeight());
        for (int y = Math.max(0, y0); y < y1; y++) {
            for (int x = Math.max(0, x0); x < x1; x++) {
                int argb = image.getColorArgb(x, y);
                if ((argb >>> 24) < 200) continue;
                counts.merge(argb | 0xFF000000, 1, Integer::sum);
            }
        }
    }
}
