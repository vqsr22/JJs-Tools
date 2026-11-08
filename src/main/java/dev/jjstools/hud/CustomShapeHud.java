package dev.jjstools.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

import java.util.ArrayList;
import java.util.List;

/**
 * A shape on the HUD: a rectangle, an ellipse, a triangle or a line, filled, outlined or both.
 *
 * Moved and placed in the HUD editor like any other element. Every shape is built out of the
 * HUD renderer's triangles, so curves are smooth at any size and outlines are as thick as asked
 * without gaps at the corners: an outline is the band between the shape's edge and a copy of it
 * pulled in by the thickness.
 */
public class CustomShapeHud extends HudElement {
    public static final HudElementInfo<CustomShapeHud> INFO = new HudElementInfo<>(
        JJsHud.GROUP, "custom-shape", "Draws a rectangle, ellipse, triangle or line, filled and outlined in any colour.", CustomShapeHud::new);

    public enum Shape { Rectangle, Ellipse, Triangle, Line }
    public enum Pointing { Up, Down, Left, Right }

    private final SettingGroup sgShape = settings.getDefaultGroup();
    private final SettingGroup sgFill = settings.createGroup("Fill");
    private final SettingGroup sgOutline = settings.createGroup("Outline");

    // Shape

    private final Setting<Shape> shape = sgShape.add(new EnumSetting.Builder<Shape>()
        .name("shape").description("What to draw.")
        .defaultValue(Shape.Rectangle).build());

    private final Setting<Integer> width = sgShape.add(new IntSetting.Builder()
        .name("width").description("How wide the shape is, in pixels. For a line, how long it is.")
        .defaultValue(40).min(1).max(2000).sliderRange(1, 400).build());

    private final Setting<Integer> height = sgShape.add(new IntSetting.Builder()
        .name("height").description("How tall the shape is, in pixels.")
        .defaultValue(40).min(1).max(2000).sliderRange(1, 400)
        .visible(() -> shape.get() != Shape.Line).build());

    private final Setting<Integer> cornerRadius = sgShape.add(new IntSetting.Builder()
        .name("corner-radius").description("How rounded the corners are, in pixels. 0 is square.")
        .defaultValue(0).min(0).max(1000).sliderRange(0, 50)
        .visible(() -> shape.get() == Shape.Rectangle).build());

    private final Setting<Pointing> pointing = sgShape.add(new EnumSetting.Builder<Pointing>()
        .name("pointing").description("Which way the triangle's tip faces.")
        .defaultValue(Pointing.Up)
        .visible(() -> shape.get() == Shape.Triangle).build());

    private final Setting<Double> angle = sgShape.add(new DoubleSetting.Builder()
        .name("angle").description("Which way the line runs, in degrees. 0 is flat, 90 is straight up and down.")
        .defaultValue(0.0).min(0.0).max(360.0).sliderRange(0.0, 360.0)
        .visible(() -> shape.get() == Shape.Line).build());

    private final Setting<Integer> lineThickness = sgShape.add(new IntSetting.Builder()
        .name("line-thickness").description("How thick the line is, in pixels.")
        .defaultValue(2).min(1).max(100).sliderRange(1, 20)
        .visible(() -> shape.get() == Shape.Line).build());

    private final Setting<SettingColor> lineColour = sgShape.add(new ColorSetting.Builder()
        .name("line-colour").description("Colour of the line.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> shape.get() == Shape.Line).build());

    // Fill

    private final Setting<Boolean> fill = sgFill.add(new BoolSetting.Builder()
        .name("fill").description("Fill the shape in.")
        .defaultValue(true)
        .visible(() -> shape.get() != Shape.Line).build());

    private final Setting<SettingColor> fillColour = sgFill.add(new ColorSetting.Builder()
        .name("fill-colour").description("Colour inside the shape.")
        .defaultValue(new SettingColor(120, 60, 200, 120))
        .visible(() -> shape.get() != Shape.Line && fill.get()).build());

    // Outline

    private final Setting<Boolean> outline = sgOutline.add(new BoolSetting.Builder()
        .name("outline").description("Draw a border around the shape.")
        .defaultValue(true)
        .visible(() -> shape.get() != Shape.Line).build());

    private final Setting<Integer> outlineThickness = sgOutline.add(new IntSetting.Builder()
        .name("outline-thickness").description("How thick the border is, in pixels. It is drawn inside the shape's edge.")
        .defaultValue(1).min(1).max(100).sliderRange(1, 20)
        .visible(() -> shape.get() != Shape.Line && outline.get()).build());

    private final Setting<SettingColor> outlineColour = sgOutline.add(new ColorSetting.Builder()
        .name("outline-colour").description("Colour of the border.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> shape.get() != Shape.Line && outline.get()).build());

    /** Points along a curved edge, per quarter turn. Enough to look round at any HUD size. */
    private static final int ARC_STEPS = 12;

    public CustomShapeHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        updateSize();
    }

    @Override
    public void render(HudRenderer renderer) {
        updateSize();

        if (shape.get() == Shape.Line) {
            drawLine(renderer);
            return;
        }

        double w = width.get();
        double h = height.get();
        double t = Math.min(outlineThickness.get(), Math.min(w, h) / 2.0);

        List<double[]> outer = path(x, y, w, h, 0.0);
        if (fill.get()) fan(renderer, outer, fillColour.get());
        if (outline.get()) band(renderer, outer, path(x, y, w, h, t), outlineColour.get());
    }

    private void updateSize() {
        if (shape.get() == Shape.Line) {
            double radians = Math.toRadians(angle.get());
            double t = lineThickness.get();
            setSize(Math.abs(Math.cos(radians)) * width.get() + t, Math.abs(Math.sin(radians)) * width.get() + t);
        }
        else {
            setSize(width.get(), height.get());
        }
    }

    // Outlines of the shapes

    /**
     * The edge of the shape, as points going round it, pulled in from the box by inset.
     *
     * Every shape gives the same number of points whatever the inset, so the outline can be
     * drawn as the band between two copies of it, one point of each at a time.
     */
    private List<double[]> path(double bx, double by, double bw, double bh, double inset) {
        return switch (shape.get()) {
            case Rectangle -> roundedRect(bx + inset, by + inset, bw - 2 * inset, bh - 2 * inset,
                Math.max(0.0, Math.min(cornerRadius.get(), Math.min(bw, bh) / 2.0) - inset));
            case Ellipse -> ellipse(bx + bw / 2.0, by + bh / 2.0, bw / 2.0 - inset, bh / 2.0 - inset);
            case Triangle -> triangle(bx, by, bw, bh, inset);
            case Line -> new ArrayList<>();
        };
    }

    private static List<double[]> roundedRect(double rx, double ry, double rw, double rh, double r) {
        List<double[]> points = new ArrayList<>();
        // Corner centres, going round clockwise from the top left, each with the angle its arc starts at.
        double[][] corners = {
            { rx + r, ry + r, 180 },
            { rx + rw - r, ry + r, 270 },
            { rx + rw - r, ry + rh - r, 0 },
            { rx + r, ry + rh - r, 90 }
        };
        for (double[] c : corners) {
            for (int i = 0; i <= ARC_STEPS; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / ARC_STEPS);
                points.add(new double[] { c[0] + r * Math.cos(a), c[1] + r * Math.sin(a) });
            }
        }
        return points;
    }

    private static List<double[]> ellipse(double cx, double cy, double rx, double ry) {
        List<double[]> points = new ArrayList<>();
        int steps = ARC_STEPS * 4;
        for (int i = 0; i < steps; i++) {
            double a = 2.0 * Math.PI * i / steps;
            points.add(new double[] { cx + Math.max(0, rx) * Math.cos(a), cy + Math.max(0, ry) * Math.sin(a) });
        }
        return points;
    }

    /**
     * The triangle filling the box with its tip on the chosen side, pulled in by inset.
     *
     * Pulling in evenly from all three sides is shrinking it towards the point equally far from
     * all three, the centre of the circle that fits inside it, so that is what is done.
     */
    private List<double[]> triangle(double bx, double by, double bw, double bh, double inset) {
        double[][] v = switch (pointing.get()) {
            case Up -> new double[][] { { bx + bw / 2, by }, { bx + bw, by + bh }, { bx, by + bh } };
            case Down -> new double[][] { { bx, by }, { bx + bw, by }, { bx + bw / 2, by + bh } };
            case Left -> new double[][] { { bx, by + bh / 2 }, { bx + bw, by }, { bx + bw, by + bh } };
            case Right -> new double[][] { { bx, by }, { bx + bw, by + bh / 2 }, { bx, by + bh } };
        };

        List<double[]> points = new ArrayList<>();
        if (inset <= 0) {
            for (double[] p : v) points.add(p);
            return points;
        }

        double a = dist(v[1], v[2]), b = dist(v[0], v[2]), c = dist(v[0], v[1]);
        double perimeter = a + b + c;
        double ix = (a * v[0][0] + b * v[1][0] + c * v[2][0]) / perimeter;
        double iy = (a * v[0][1] + b * v[1][1] + c * v[2][1]) / perimeter;

        double s = perimeter / 2;
        double area = Math.sqrt(Math.max(0, s * (s - a) * (s - b) * (s - c)));
        double inradius = area / s;
        double k = inradius <= 0 ? 0 : Math.max(0, (inradius - inset) / inradius);

        for (double[] p : v) points.add(new double[] { ix + (p[0] - ix) * k, iy + (p[1] - iy) * k });
        return points;
    }

    private static double dist(double[] p, double[] q) {
        return Math.hypot(p[0] - q[0], p[1] - q[1]);
    }

    // Drawing

    /**
     * One triangle, its corners put in the order Meteor's HUD draws.
     *
     * Meteor's HUD leaves out triangles whose corners run the other way round, as it does the
     * backs of things in 3D, and the shapes' points went round the wrong way, so nothing showed
     * at all. Each triangle is now turned to face the right way, whichever way it was given.
     */
    private static void tri(HudRenderer renderer, double x1, double y1, double x2, double y2, double x3, double y3, Color colour) {
        double cross = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
        if (cross > 0) renderer.triangle(x1, y1, x3, y3, x2, y2, colour);
        else renderer.triangle(x1, y1, x2, y2, x3, y3, colour);
    }

    /** Fills a shape that bulges outwards everywhere, as triangles from its middle. */
    private static void fan(HudRenderer renderer, List<double[]> points, Color colour) {
        if (points.size() < 3) return;

        double cx = 0, cy = 0;
        for (double[] p : points) {
            cx += p[0];
            cy += p[1];
        }
        cx /= points.size();
        cy /= points.size();

        for (int i = 0; i < points.size(); i++) {
            double[] p = points.get(i);
            double[] q = points.get((i + 1) % points.size());
            tri(renderer, cx, cy, p[0], p[1], q[0], q[1], colour);
        }
    }

    /** Fills the band between an outer edge and the same edge pulled in, two triangles a step. */
    private static void band(HudRenderer renderer, List<double[]> outer, List<double[]> inner, Color colour) {
        int n = Math.min(outer.size(), inner.size());
        for (int i = 0; i < n; i++) {
            double[] o1 = outer.get(i), o2 = outer.get((i + 1) % n);
            double[] i1 = inner.get(i), i2 = inner.get((i + 1) % n);
            tri(renderer, o1[0], o1[1], o2[0], o2[1], i2[0], i2[1], colour);
            tri(renderer, o1[0], o1[1], i2[0], i2[1], i1[0], i1[1], colour);
        }
    }

    /** A line through the middle of the element at the chosen angle, as one thin rectangle. */
    private void drawLine(HudRenderer renderer) {
        double radians = Math.toRadians(angle.get());
        double half = width.get() / 2.0;
        double cx = x + getWidth() / 2.0;
        double cy = y + getHeight() / 2.0;

        // Screen y runs downwards, so up on the screen is minus.
        double dx = Math.cos(radians) * half;
        double dy = -Math.sin(radians) * half;

        double t = lineThickness.get() / 2.0;
        double nx = -dy / half * t;
        double ny = dx / half * t;

        double ax = cx - dx, ay = cy - dy;
        double bx = cx + dx, by = cy + dy;

        Color colour = lineColour.get();
        tri(renderer, ax + nx, ay + ny, bx + nx, by + ny, bx - nx, by - ny, colour);
        tri(renderer, ax + nx, ay + ny, bx - nx, by - ny, ax - nx, ay - ny, colour);
    }
}
