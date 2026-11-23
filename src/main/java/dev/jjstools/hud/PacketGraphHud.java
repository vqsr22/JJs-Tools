package dev.jjstools.hud;

import dev.jjstools.util.PacketLimits;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * A live graph of how close you are to the server's packet limits: global, inventory and
 * interaction, each as a share of its limit, the top of the graph being the limit itself.
 *
 * Drawn the way JJ's theme draws things: a rounded panel in the panel colour, a small dim title
 * and a legend of the three figures above a rounded well that holds the graph, the lines in the
 * accent colour and two colours beside it. Read twenty times a second and scrolled smoothly
 * between readings, each line eased toward its newest value, with a soft fill under it and a
 * faint line at three quarters of the limit. Turn follow-jj-theme off to pick every colour.
 */
public class PacketGraphHud extends HudElement {
    public static final HudElementInfo<PacketGraphHud> INFO = new HudElementInfo<>(
        JJsHud.GROUP, "packet-graph", "A smooth live graph of your global, inventory and interaction packets against the server's limits, in JJ's theme.", PacketGraphHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgLines = settings.createGroup("Lines");
    private final SettingGroup sgText = settings.createGroup("Text");
    private final SettingGroup sgColours = settings.createGroup("Colours");

    // Layout

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("width").description("Width of the whole panel.")
        .defaultValue(170).min(60).sliderRange(80, 400).build());

    private final Setting<Integer> graphHeight = sgGeneral.add(new IntSetting.Builder()
        .name("graph-height").description("Height of the graph itself.")
        .defaultValue(44).min(12).sliderRange(16, 200).build());

    private final Setting<Integer> history = sgGeneral.add(new IntSetting.Builder()
        .name("history").description("Seconds of history shown across the graph.")
        .defaultValue(10).range(2, 60).sliderRange(2, 30).build());

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing").description("How much the lines are eased between readings. 0 shows every reading as it is.")
        .defaultValue(0.55).range(0, 0.95).sliderRange(0, 0.9).build());

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("corner-radius").description("Roundness of the panel's and the graph's corners.")
        .defaultValue(4).range(0, 12).sliderRange(0, 10).build());

    private final Setting<Boolean> panel = sgGeneral.add(new BoolSetting.Builder()
        .name("panel").description("Draw the panel behind it. Off leaves just the graph and its labels.")
        .defaultValue(true).build());

    // Lines

    private final Setting<Boolean> showGlobal = sgLines.add(new BoolSetting.Builder()
        .name("global").description("Show the line for all packets.").defaultValue(true).build());

    private final Setting<Boolean> showInventory = sgLines.add(new BoolSetting.Builder()
        .name("inventory").description("Show the line for inventory packets.").defaultValue(true).build());

    private final Setting<Boolean> showInteract = sgLines.add(new BoolSetting.Builder()
        .name("interact").description("Show the line for block interactions.").defaultValue(true).build());

    private final Setting<Double> thickness = sgLines.add(new DoubleSetting.Builder()
        .name("thickness").description("Thickness of the lines.")
        .defaultValue(1.5).range(0.5, 6).sliderRange(0.5, 4).build());

    private final Setting<Integer> fillOpacity = sgLines.add(new IntSetting.Builder()
        .name("fill-opacity").description("How strong the fill under each line is. 0 for none.")
        .defaultValue(55).range(0, 255).sliderRange(0, 160).build());

    private final Setting<Boolean> guide = sgLines.add(new BoolSetting.Builder()
        .name("three-quarter-line").description("A faint line at three quarters of the limit.").defaultValue(true).build());

    // Text

    private final Setting<Boolean> title = sgText.add(new BoolSetting.Builder()
        .name("title").description("Write \"packets\" over the graph.").defaultValue(true).build());

    private final Setting<Boolean> legend = sgText.add(new BoolSetting.Builder()
        .name("legend").description("The current count against each limit, beside a dot in the line's colour.").defaultValue(true).build());

    private final Setting<Double> textScale = sgText.add(new DoubleSetting.Builder()
        .name("text-size").description("Size of the title and legend.")
        .defaultValue(0.8).range(0.3, 3).sliderRange(0.4, 2).build());

    private final Setting<Boolean> shadow = sgText.add(new BoolSetting.Builder()
        .name("text-shadow").description("Drop shadow behind the text.").defaultValue(false).build());

    // Colours

    private final Setting<Boolean> followTheme = sgColours.add(new BoolSetting.Builder()
        .name("follow-jj-theme").description("Take the colours from the theme picked in the Control Panel, and change with it. Off uses the colours below.")
        .defaultValue(true).build());

    private final Setting<Integer> panelOpacity = sgColours.add(new IntSetting.Builder()
        .name("panel-opacity").description("How see-through the panel is when following JJ's theme.")
        .defaultValue(215).range(0, 255).sliderRange(0, 255).visible(followTheme::get).build());

    private final Setting<SettingColor> panelColour = sgColours.add(new ColorSetting.Builder()
        .name("panel-colour").description("Colour of the panel.")
        .defaultValue(new SettingColor(28, 29, 33, 215)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> wellColour = sgColours.add(new ColorSetting.Builder()
        .name("graph-colour").description("Colour behind the graph.")
        .defaultValue(new SettingColor(20, 21, 24, 230)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> textColour = sgColours.add(new ColorSetting.Builder()
        .name("text-colour").description("Colour of the legend's figures.")
        .defaultValue(new SettingColor(225, 225, 225)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> dimColour = sgColours.add(new ColorSetting.Builder()
        .name("dim-text-colour").description("Colour of the title and the limits.")
        .defaultValue(new SettingColor(130, 132, 138)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> globalColour = sgColours.add(new ColorSetting.Builder()
        .name("global-colour").description("Colour of the global line.")
        .defaultValue(new SettingColor(90, 200, 255)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> inventoryColour = sgColours.add(new ColorSetting.Builder()
        .name("inventory-colour").description("Colour of the inventory line.")
        .defaultValue(new SettingColor(255, 170, 60)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> interactColour = sgColours.add(new ColorSetting.Builder()
        .name("interact-colour").description("Colour of the interaction line.")
        .defaultValue(new SettingColor(220, 110, 255)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> warnColour = sgColours.add(new ColorSetting.Builder()
        .name("near-limit-colour").description("Colour a figure turns as it nears its limit.")
        .defaultValue(new SettingColor(240, 85, 85)).build());

    private static final long STEP_MS = 50;
    private static final int MAX_SAMPLES = 60 * 1000 / (int) STEP_MS + 2;
    private static final String[] NAMES = { "global", "inv", "int" };

    /** Readings as shares of each limit, in a ring. */
    private final double[][] samples = new double[3][MAX_SAMPLES];
    private int head, count;
    private long lastSample;
    private final double[] eased = new double[3];

    private int[] palette;
    private long paletteAt;

    public PacketGraphHud() {
        super(INFO);
    }

    // Colours

    /** panel, well, accent, text, dim text, and the three line colours. */
    private Color[] colours() {
        if (!followTheme.get()) {
            return new Color[] { panelColour.get(), wellColour.get(), globalColour.get(), textColour.get(), dimColour.get(),
                globalColour.get(), inventoryColour.get(), interactColour.get() };
        }
        long now = System.currentTimeMillis();
        if (palette == null || now - paletteAt > 500) {
            try {
                palette = dev.jjstools.panel.Ui.palette(dev.jjstools.panel.Ui.currentTheme());
            } catch (Throwable t) {
                palette = new int[] { 0xFF1C1D21, 0xFF141518, 0xFF26272C, 0xFF913DE2, 0xFFE1E1E1, 0xFF82848A };
            }
            paletteAt = now;
        }
        Color panelC = argb(palette[0]);
        panelC.a = panelOpacity.get();
        Color well = argb(palette[1]);
        well.a = Math.min(255, panelOpacity.get() + 20);
        Color accent = argb(palette[3]);
        return new Color[] { panelC, well, accent, argb(palette[4]), argb(palette[5]),
            accent, hueShift(accent, 0.33f), hueShift(accent, 0.66f) };
    }

    private static Color argb(int c) {
        return new Color((c >> 16) & 0xFF, (c >> 8) & 0xFF, c & 0xFF, (c >>> 24) & 0xFF);
    }

    /** The same colour turned round the colour wheel, so the three lines sit together. */
    private static Color hueShift(Color c, float by) {
        float[] hsb = java.awt.Color.RGBtoHSB(c.r, c.g, c.b, null);
        // A grey accent has no hue to turn: give the others a little colour of their own.
        float sat = hsb[1] < 0.2f ? 0.55f : hsb[1];
        float bri = Math.max(hsb[2], 0.7f);
        int rgb = java.awt.Color.HSBtoRGB((hsb[0] + by) % 1f, sat, bri);
        return new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, c.a);
    }

    private static Color alpha(Color c, int a) {
        Color copy = new Color(c);
        copy.a = Math.max(0, Math.min(255, a * c.a / 255));
        return copy;
    }

    // Readings

    private double[] reading() {
        return new double[] {
            PacketLimits.global() / (double) PacketLimits.globalLimit(),
            PacketLimits.inventory() / (double) PacketLimits.inventoryLimit(),
            PacketLimits.interact() / (double) PacketLimits.interactLimit() };
    }

    private void sample() {
        long now = System.currentTimeMillis();
        if (lastSample == 0 || now - lastSample > 2000) {
            // First reading, or back after a long gap: start from here rather than filling it in.
            lastSample = now - STEP_MS;
            count = 0;
        }
        double keep = smoothing.get();
        while (now - lastSample >= STEP_MS) {
            lastSample += STEP_MS;
            double[] r = reading();
            head = (head + 1) % MAX_SAMPLES;
            for (int i = 0; i < 3; i++) {
                eased[i] = count == 0 ? r[i] : eased[i] * keep + r[i] * (1 - keep);
                samples[i][head] = eased[i];
            }
            if (count < MAX_SAMPLES) count++;
        }
    }

    // Layout

    private double pad() {
        return 4;
    }

    private double headerHeight(HudRenderer renderer) {
        if (!title.get() && !legend.get()) return 0;
        return renderer.textHeight(shadow.get(), textScale.get()) + 3;
    }

    private void updateSize(HudRenderer renderer) {
        double p = panel.get() ? pad() : 0;
        setSize(width.get(), p + headerHeight(renderer) + graphHeight.get() + p);
    }

    @Override
    public void tick(HudRenderer renderer) {
        updateSize(renderer);
    }

    @Override
    public void render(HudRenderer renderer) {
        updateSize(renderer);
        sample();
        Color[] c = colours();
        double p = panel.get() ? pad() : 0;
        double r = radius.get();

        if (panel.get()) round(renderer, x, y, getWidth(), getHeight(), r, c[0]);

        double header = headerHeight(renderer);
        if (header > 0) drawHeader(renderer, x + p, y + p, getWidth() - p * 2, c);

        double gx = x + p, gy = y + p + header, gw = getWidth() - p * 2, gh = graphHeight.get();
        round(renderer, gx, gy, gw, gh, Math.max(0, r - (panel.get() ? 1 : 0)), c[1]);

        // Inside the well, kept clear of its rounded corners.
        double inset = Math.max(1, Math.min(r * 0.5, 3));
        double px = gx + inset, py = gy + inset, pw = gw - inset * 2, ph = gh - inset * 2;
        if (guide.get()) {
            double gyLine = py + ph * 0.25;
            renderer.quad(px, gyLine, pw, 1, alpha(c[4], 60));
        }

        int shown = Math.min(count, (int) (history.get() * 1000 / STEP_MS) + 1);
        if (shown >= 3) {
            double perStep = pw / (shown - 2);
            double into = Math.min(1, (System.currentTimeMillis() - lastSample) / (double) STEP_MS);
            boolean[] on = { showGlobal.get(), showInventory.get(), showInteract.get() };
            // Back to front: interaction, inventory, global.
            for (int s = 2; s >= 0; s--) {
                if (on[s]) drawSeries(renderer, samples[s], shown, perStep, into, px, py, pw, ph, c[5 + s]);
            }
        }
    }

    private void drawHeader(HudRenderer renderer, double hx, double hy, double hw, Color[] c) {
        double scale = textScale.get();
        boolean sh = shadow.get();
        double lx = hx + 1;
        if (title.get()) {
            renderer.text("packets", lx, hy, c[4], sh, scale);
            lx += renderer.textWidth("packets", sh, scale) + 6;
        }
        if (!legend.get()) return;

        int[] now = { PacketLimits.global(), PacketLimits.inventory(), PacketLimits.interact() };
        int[] limits = { PacketLimits.globalLimit(), PacketLimits.inventoryLimit(), PacketLimits.interactLimit() };
        boolean[] on = { showGlobal.get(), showInventory.get(), showInteract.get() };
        double th = renderer.textHeight(sh, scale);
        double dot = Math.max(3, th * 0.45);

        // Right-aligned, as the panel's figures are: each a dot, the count, and /limit dimmed.
        double rx = hx + hw - 1;
        for (int i = 2; i >= 0; i--) {
            if (!on[i]) continue;
            String count = String.valueOf(now[i]);
            String limit = "/" + limits[i];
            double share = now[i] / (double) limits[i];
            Color figure = share >= 0.9 ? warnColour.get() : c[3];
            double wl = renderer.textWidth(limit, sh, scale), wc = renderer.textWidth(count, sh, scale);
            double start = rx - wl - wc - dot - 3;
            if (start < lx) break;
            round(renderer, start, hy + (th - dot) / 2 - 0.5, dot, dot, dot / 2, c[5 + i]);
            renderer.text(count, start + dot + 3, hy, figure, sh, scale);
            renderer.text(limit, start + dot + 3 + wc, hy, c[4], sh, scale);
            rx = start - 7;
        }
    }

    private void drawSeries(HudRenderer renderer, double[] series, int shown, double perStep, double into,
                            double px, double py, double pw, double ph, Color colour) {
        double thick = thickness.get();
        int fillA = fillOpacity.get();
        Color fillTop = alpha(colour, fillA), fillBottom = alpha(colour, 0);

        double prevX = 0, prevY = 0;
        boolean first = true;
        for (int k = 0; k < shown; k++) {
            int idx = Math.floorMod(head - (shown - 1 - k), MAX_SAMPLES);
            double sx = px + (k - into) * perStep;
            double v = Math.max(0, Math.min(1, series[idx]));
            double sy = py + ph - v * ph;
            if (!first) {
                // Clipped to the graph's sides.
                double x1 = Math.max(px, prevX), x2 = Math.min(px + pw, sx);
                if (x2 > x1) {
                    double t1 = (x1 - prevX) / (sx - prevX), t2 = (x2 - prevX) / (sx - prevX);
                    double y1 = prevY + (sy - prevY) * t1, y2 = prevY + (sy - prevY) * t2;
                    if (fillA > 0) {
                        // The area under the segment: a slope on top, straight down to the bottom.
                        double bottom = py + ph;
                        double low = Math.max(y1, y2);
                        renderer.triangle(x1, y1, x2, y2, y1 > y2 ? x1 : x2, low, fillTop);
                        renderer.quad(x1, low, x2 - x1, bottom - low, fillTop, fillTop, fillBottom, fillBottom);
                    }
                    thickLine(renderer, x1, y1, x2, y2, thick, colour);
                }
            }
            prevX = sx;
            prevY = sy;
            first = false;
        }
    }

    // Shapes

    /** A line of any thickness, as two triangles, with a round joint so segments meet cleanly. */
    private static void thickLine(HudRenderer renderer, double x1, double y1, double x2, double y2, double t, Color c) {
        if (t <= 1.01) {
            renderer.line(x1, y1, x2, y2, c);
            return;
        }
        double dx = x2 - x1, dy = y2 - y1;
        double len = Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-6) return;
        double nx = -dy / len * t / 2, ny = dx / len * t / 2;
        renderer.triangle(x1 + nx, y1 + ny, x2 + nx, y2 + ny, x2 - nx, y2 - ny, c);
        renderer.triangle(x1 + nx, y1 + ny, x2 - nx, y2 - ny, x1 - nx, y1 - ny, c);
        disc(renderer, x2, y2, t / 2, c);
    }

    private static void disc(HudRenderer renderer, double cx, double cy, double r, Color c) {
        int n = 8;
        for (int i = 0; i < n; i++) {
            double a1 = Math.PI * 2 * i / n, a2 = Math.PI * 2 * (i + 1) / n;
            renderer.triangle(cx, cy, cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, cx + Math.cos(a2) * r, cy + Math.sin(a2) * r, c);
        }
    }

    /** A rounded rectangle, as the panel's. */
    private static void round(HudRenderer renderer, double x, double y, double w, double h, double r, Color c) {
        r = Math.min(r, Math.min(w, h) / 2);
        if (r < 0.5) {
            renderer.quad(x, y, w, h, c);
            return;
        }
        renderer.quad(x + r, y, w - r * 2, h, c);
        renderer.quad(x, y + r, r, h - r * 2, c);
        renderer.quad(x + w - r, y + r, r, h - r * 2, c);
        corner(renderer, x + r, y + r, r, Math.PI, c);
        corner(renderer, x + w - r, y + r, r, Math.PI * 1.5, c);
        corner(renderer, x + w - r, y + h - r, r, 0, c);
        corner(renderer, x + r, y + h - r, r, Math.PI * 0.5, c);
    }

    private static void corner(HudRenderer renderer, double cx, double cy, double r, double start, Color c) {
        int n = Math.max(3, (int) Math.ceil(r * 1.5));
        for (int i = 0; i < n; i++) {
            double a1 = start + Math.PI / 2 * i / n, a2 = start + Math.PI / 2 * (i + 1) / n;
            renderer.triangle(cx, cy, cx + Math.cos(a1) * r, cy + Math.sin(a1) * r, cx + Math.cos(a2) * r, cy + Math.sin(a2) * r, c);
        }
    }
}
