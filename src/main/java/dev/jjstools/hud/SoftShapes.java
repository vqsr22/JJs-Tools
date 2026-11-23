package dev.jjstools.hud;

import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.utils.render.color.Color;

import java.util.ArrayList;
import java.util.List;

/**
 * Smooth shapes for HUD elements, drawn into Meteor's 2D mesh alongside its own quads.
 *
 * Meteor draws plain triangles with hard edges, so anything slanted or round came out jagged.
 * Each shape here gets a one-pixel rim that fades from its colour to clear, which the eye reads
 * as a smooth edge, the same trick as anti-aliasing. Lines are drawn as one continuous strip
 * with mitred joins, so nothing overlaps (and doubles up) where the segments meet.
 */
public final class SoftShapes {
    private SoftShapes() {}

    private static final double FEATHER = 1.0;

    private static Color clear(Color c) {
        Color t = new Color(c);
        t.a = 0;
        return t;
    }

    private static int vertex(double x, double y, Color c) {
        return Renderer2D.COLOR.triangles.vec2(x, y).color(c).next();
    }

    private static void tri(int a, int b, int c) {
        Renderer2D.COLOR.triangles.triangle(a, b, c);
    }

    /** A rounded rectangle with soft edges. */
    public static void roundRect(double x, double y, double w, double h, double r, Color c) {
        if (w <= 0 || h <= 0) return;
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2));
        List<double[]> pts = new ArrayList<>();
        int n = Math.max(2, (int) Math.ceil(r * 1.2));
        corner(pts, x + w - r, y + r, r, -Math.PI / 2, n);
        corner(pts, x + w - r, y + h - r, r, 0, n);
        corner(pts, x + r, y + h - r, r, Math.PI / 2, n);
        corner(pts, x + r, y + r, r, Math.PI, n);
        polygon(pts, x + w / 2, y + h / 2, c);
    }

    /** A soft-edged filled circle. */
    public static void circle(double cx, double cy, double r, Color c) {
        List<double[]> pts = new ArrayList<>();
        int n = Math.max(10, (int) Math.ceil(r * 4));
        for (int i = 0; i < n; i++) {
            double a = Math.PI * 2 * i / n;
            pts.add(new double[] { cx + Math.cos(a) * r, cy + Math.sin(a) * r });
        }
        polygon(pts, cx, cy, c);
    }

    private static void corner(List<double[]> pts, double cx, double cy, double r, double start, int n) {
        if (r <= 0) {
            pts.add(new double[] { cx, cy });
            return;
        }
        for (int i = 0; i <= n; i++) {
            double a = start + Math.PI / 2 * i / n;
            pts.add(new double[] { cx + Math.cos(a) * r, cy + Math.sin(a) * r });
        }
    }

    /** A convex outline, filled from its middle, with a rim fading out around it. */
    private static void polygon(List<double[]> pts, double cx, double cy, Color c) {
        int count = pts.size();
        if (count < 3) return;
        Color edge = clear(c);
        int centre = vertex(cx, cy, c);
        int[] inner = new int[count], outer = new int[count];
        for (int i = 0; i < count; i++) {
            double[] p = pts.get(i);
            double dx = p[0] - cx, dy = p[1] - cy;
            double len = Math.sqrt(dx * dx + dy * dy);
            double ox = len > 1e-6 ? dx / len * FEATHER : 0, oy = len > 1e-6 ? dy / len * FEATHER : 0;
            // The rim straddles the outline, so the shape keeps its size.
            inner[i] = vertex(p[0] - ox * 0.5, p[1] - oy * 0.5, c);
            outer[i] = vertex(p[0] + ox * 0.5, p[1] + oy * 0.5, edge);
        }
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            tri(centre, inner[i], inner[j]);
            tri(inner[i], outer[i], outer[j]);
            tri(inner[i], outer[j], inner[j]);
        }
    }

    /**
     * A smooth line through the points, of the given thickness, with soft edges and mitred joins.
     * Points are x, y pairs.
     */
    public static void polyline(double[] xs, double[] ys, int n, double thickness, Color c) {
        if (n < 2) return;
        Color edge = clear(c);
        double half = Math.max(0.35, thickness / 2);
        int prevLo = -1, prevLi = -1, prevRi = -1, prevRo = -1;
        for (int i = 0; i < n; i++) {
            // The normal at this point: the average of the segments either side, for a mitre.
            double nx, ny;
            double[] a = normal(xs, ys, Math.max(0, i - 1), Math.max(1, i));
            double[] b = normal(xs, ys, Math.min(n - 2, i), Math.min(n - 1, i + 1));
            nx = a[0] + b[0];
            ny = a[1] + b[1];
            double len = Math.sqrt(nx * nx + ny * ny);
            if (len < 1e-6) {
                nx = a[0];
                ny = a[1];
            } else {
                nx /= len;
                ny /= len;
            }
            // Longer at sharp corners so the line keeps its width, but never past twice.
            double dot = nx * a[0] + ny * a[1];
            double miter = Math.min(2.0, 1 / Math.max(0.5, dot));
            double in = half * miter, out = (half + FEATHER) * miter;
            int lo = vertex(xs[i] + nx * out, ys[i] + ny * out, edge);
            int li = vertex(xs[i] + nx * in, ys[i] + ny * in, c);
            int ri = vertex(xs[i] - nx * in, ys[i] - ny * in, c);
            int ro = vertex(xs[i] - nx * out, ys[i] - ny * out, edge);
            if (prevLo >= 0) {
                tri(prevLo, lo, li);
                tri(prevLo, li, prevLi);
                tri(prevLi, li, ri);
                tri(prevLi, ri, prevRi);
                tri(prevRi, ri, ro);
                tri(prevRi, ro, prevRo);
            }
            prevLo = lo;
            prevLi = li;
            prevRi = ri;
            prevRo = ro;
        }
    }

    private static double[] normal(double[] xs, double[] ys, int i, int j) {
        double dx = xs[j] - xs[i], dy = ys[j] - ys[i];
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return new double[] { 0, -1 };
        return new double[] { -dy / len, dx / len };
    }

    /** The area under a line down to bottom, fading from top colour at the line to bottom colour. */
    public static void areaUnder(double[] xs, double[] ys, int n, double bottom, Color top, Color low) {
        if (n < 2) return;
        int prevT = vertex(xs[0], ys[0], top), prevB = vertex(xs[0], bottom, low);
        for (int i = 1; i < n; i++) {
            int t = vertex(xs[i], ys[i], top), b = vertex(xs[i], bottom, low);
            tri(prevT, t, b);
            tri(prevT, b, prevB);
            prevT = t;
            prevB = b;
        }
    }
}
