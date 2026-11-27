package dev.jjstools.panel;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Smooth shapes for the Control Panel: round corners, circles, chevrons and the like.
 *
 * Minecraft only draws square boxes, so anything curved is drawn here as a small picture, made
 * at the exact number of screen pixels it shows at with softened edges, and kept for reuse. Each
 * picture is white, and tinted to whatever colour it is drawn in.
 */
public final class UiShapes {
    /** Whether a point, in a shape of this size, is inside it. Points are in pixels, 0 to size. */
    private interface Inside {
        boolean at(double x, double y, int w, int h);
    }

    public record Shape(Identifier id, int w, int h) {}

    private static final int MAX = 96;
    private static final Map<String, Shape> SHAPES = new LinkedHashMap<>(64, 0.75F, true);

    private UiShapes() {}

    /** A filled circle r pixels across its radius, its quarters used for round corners. */
    public static Shape circle(int r) {
        return make("circle" + r, r * 2, r * 2, (x, y, w, h) -> {
            double dx = x - w / 2.0, dy = y - h / 2.0;
            return dx * dx + dy * dy <= (w / 2.0) * (w / 2.0);
        });
    }

    /** Steps round a whole ring for arc. */
    public static final int ARC_STEPS = 360;

    /**
     * A ring, of the given thickness in pixels, drawn round from the top clockwise for the given
     * number of ARC_STEPS (all of them for the whole ring), with rounded ends. Kept apart from the
     * other shapes, since a filling ring goes through many of these and would push them out.
     */
    static Shape arc(int size, int thick, int steps) {
        return make(ARCS, 24, "arc" + size + "t" + thick + "s" + steps, size, size, (x, y, w, h) -> {
            double cx = w / 2.0, cy = h / 2.0;
            double dx = x - cx, dy = y - cy;
            double d = Math.sqrt(dx * dx + dy * dy);
            double outer = w / 2.0, inner = outer - thick, mid = outer - thick / 2.0;
            if (steps >= ARC_STEPS) return d <= outer && d >= inner;
            double end = Math.PI * 2 * steps / ARC_STEPS;
            if (d <= outer && d >= inner) {
                double angle = Math.atan2(dx, -dy);
                if (angle < 0) angle += Math.PI * 2;
                if (angle <= end) return true;
            }
            // Rounded ends: a dot the ring's thickness at the start and at the end.
            double r = thick / 2.0;
            double sx = cx, sy = cy - mid;
            double ex = cx + Math.sin(end) * mid, ey = cy - Math.cos(end) * mid;
            return (x - sx) * (x - sx) + (y - sy) * (y - sy) <= r * r || (x - ex) * (x - ex) + (y - ey) * (y - ey) <= r * r;
        });
    }

    private static final Map<String, Shape> ARCS = new LinkedHashMap<>(32, 0.75F, true);

    /** A thin chevron, pointing down or up, as on a drop-down. */
    static Shape chevron(int w, int h, boolean up) {
        return make("chevron" + w + "x" + h + (up ? "u" : "d"), w, h, (x, y, ww, hh) -> {
            double t = Math.max(1.2, hh * 0.32);
            double half = t / 2;
            double yy = up ? hh - y : y;
            // Two strokes meeting in the middle at the bottom.
            double ax = half, ay = half, bx = ww / 2.0, by = hh - half, cx = ww - half, cy = half;
            return segment(x, yy, ax, ay, bx, by) <= half || segment(x, yy, bx, by, cx, cy) <= half;
        });
    }

    /** An x, for the close button. */
    static Shape cross(int size) {
        return make("cross" + size, size, size, (x, y, w, h) -> {
            double half = Math.max(0.7, w * 0.11);
            double a = half, b = w - half;
            return segment(x, y, a, a, b, b) <= half || segment(x, y, b, a, a, b) <= half;
        });
    }

    /** Two triangles, pointing out of the corners or in towards the middle, for the maximise button. */
    static Shape triangles(int size, boolean in) {
        return make("tri" + size + (in ? "i" : "o"), size, size, (x, y, w, h) -> {
            double k = w * 0.62;
            if (!in) {
                // Right angles in the top left and bottom right corners.
                return x + y <= k || (w - x) + (h - y) <= k;
            }
            // Right angles meeting near the middle.
            double mx = w / 2.0, my = h / 2.0;
            boolean topLeft = x <= mx && y <= my && (mx - x) + (my - y) <= k;
            boolean bottomRight = x >= mx && y >= my && (x - mx) + (y - my) <= k;
            return topLeft || bottomRight;
        });
    }

    /** A push pin, leant over to the right, for pinning someone in the chat list. */
    static Shape pin(int size) {
        return make("pin" + size, size, size, (x, y, w, h) -> {
            // Worked out upright in a unit square, then turned 45 degrees about the middle.
            double cx = x / w - 0.5, cy = y / h - 0.5;
            double c = Math.cos(Math.PI / 4), sn = Math.sin(Math.PI / 4);
            double u = cx * c + cy * sn + 0.5, v = -cx * sn + cy * c + 0.5;
            boolean cap = u >= 0.32 && u <= 0.68 && v >= 0.06 && v <= 0.2;
            boolean body = u >= 0.38 && u <= 0.62 && v >= 0.2 && v <= 0.5;
            boolean base = u >= 0.2 && u <= 0.8 && v >= 0.5 && v <= 0.62;
            boolean needle = segment(u, v, 0.5, 0.62, 0.5, 0.96) <= 0.045;
            return cap || body || base || needle;
        });
    }

    /** How far a point is from a line between two others. */
    private static double segment(double px, double py, double ax, double ay, double bx, double by) {
        double vx = bx - ax, vy = by - ay;
        double len = vx * vx + vy * vy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((px - ax) * vx + (py - ay) * vy) / len));
        double dx = px - (ax + t * vx), dy = py - (ay + t * vy);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static Shape make(String key, int w, int h, Inside inside) {
        return make(SHAPES, MAX, key, w, h, inside);
    }

    private static Shape make(Map<String, Shape> cache, int max, String key, int w, int h, Inside inside) {
        Shape shape = cache.get(key);
        if (shape != null) return shape;
        w = Math.max(1, w);
        h = Math.max(1, h);

        // Each pixel looked at in 16 places, so its edge is as soft as how much of it is inside.
        NativeImage image = new NativeImage(w, h, true);
        for (int py = 0; py < h; py++) {
            for (int px = 0; px < w; px++) {
                int hits = 0;
                for (int sy = 0; sy < 4; sy++) {
                    for (int sx = 0; sx < 4; sx++) {
                        if (inside.at(px + (sx + 0.5) / 4.0, py + (sy + 0.5) / 4.0, w, h)) hits++;
                    }
                }
                int a = Math.round(hits * 255 / 16F);
                image.setColorArgb(px, py, (a << 24) | 0xFFFFFF);
            }
        }
        Identifier id = Identifier.of("jjs-tools", "panel_shape/" + key.toLowerCase(java.util.Locale.ROOT));
        int fw = w, fh = h;
        NativeImageBackedTexture texture = dev.jjstools.compat.Compat.texture(() -> "JJ's Tools panel shape " + key, image);
        texture.upload();
        MinecraftClient.getInstance().getTextureManager().registerTexture(id, texture);
        shape = new Shape(id, fw, fh);
        cache.put(key, shape);

        // Sizes change with the GUI and text scale; the ones not drawn for a while go.
        if (cache.size() > max) {
            String oldest = cache.keySet().iterator().next();
            Shape old = cache.remove(oldest);
            MinecraftClient.getInstance().getTextureManager().destroyTexture(old.id());
        }
        return shape;
    }
}
