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
 * interaction, each as a share of its limit (zoomed to the highest line with auto-scale).
 *
 * Drawn the way JJ's theme draws things: a rounded panel in the panel colour, a legend of the
 * three figures (global, inv, int) above a rounded well that holds the graph, the lines in the
 * accent colour and two colours beside it, or colours of your own. Read twenty times a second and scrolled smoothly
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
        .name("background").description("Draw the background: the panel and the darker area behind the lines. Off leaves just the lines and their labels.")
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

    private final Setting<Boolean> autoScale = sgLines.add(new BoolSetting.Builder()
        .name("auto-scale").description("Zoom the graph to the highest line shown, so small counts (a few inventory clicks, or everything with global off) still rise clearly. Off, the top is always the limit.")
        .defaultValue(true).build());

    private final Setting<Boolean> guide = sgLines.add(new BoolSetting.Builder()
        .name("three-quarter-line").description("A faint line at three quarters of the limit.").defaultValue(true).build());

    // Text

    private final Setting<Boolean> legend = sgText.add(new BoolSetting.Builder()
        .name("legend").description("Each line's name, its current count and its limit, in the line's colour: global, inv, int.").defaultValue(true).build());

    private final Setting<Double> textScale = sgText.add(new DoubleSetting.Builder()
        .name("text-size").description("Size of the legend.")
        .defaultValue(0.8).range(0.3, 3).sliderRange(0.4, 2).build());

    private final Setting<Boolean> shadow = sgText.add(new BoolSetting.Builder()
        .name("text-shadow").description("Drop shadow behind the text.").defaultValue(false).build());

    // Colours

    private final Setting<Boolean> followTheme = sgColours.add(new BoolSetting.Builder()
        .name("follow-jj-theme").description("Take the colours from the theme picked in the Control Panel, and change with it. Off uses the colours below.")
        .defaultValue(true).build());

    private final Setting<Boolean> customBackground = sgColours.add(new BoolSetting.Builder()
        .name("custom-background-colour").description("Your own background colours below, while the rest follows JJ's theme.")
        .defaultValue(false).visible(() -> followTheme.get() && panel.get()).build());

    private final Setting<Boolean> customLines = sgColours.add(new BoolSetting.Builder()
        .name("custom-line-colours").description("Your own colours for the global, inventory and interact lines and their figures, while the rest follows JJ's theme.")
        .defaultValue(false).visible(followTheme::get).build());

    private final Setting<Integer> panelOpacity = sgColours.add(new IntSetting.Builder()
        .name("panel-opacity").description("How see-through the background is when following JJ's theme.")
        .defaultValue(215).range(0, 255).sliderRange(0, 255).visible(() -> followTheme.get() && !customBackground.get() && panel.get()).build());

    private final Setting<SettingColor> panelColour = sgColours.add(new ColorSetting.Builder()
        .name("background-colour").description("Colour of the background panel.")
        .defaultValue(new SettingColor(28, 29, 33, 215)).visible(() -> panel.get() && (!followTheme.get() || customBackground.get())).build());

    private final Setting<SettingColor> wellColour = sgColours.add(new ColorSetting.Builder()
        .name("graph-colour").description("Colour of the darker area behind the lines.")
        .defaultValue(new SettingColor(20, 21, 24, 230)).visible(() -> panel.get() && (!followTheme.get() || customBackground.get())).build());

    private final Setting<SettingColor> textColour = sgColours.add(new ColorSetting.Builder()
        .name("text-colour").description("Colour of the legend's figures.")
        .defaultValue(new SettingColor(225, 225, 225)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> dimColour = sgColours.add(new ColorSetting.Builder()
        .name("dim-text-colour").description("Colour of the limits in the legend.")
        .defaultValue(new SettingColor(130, 132, 138)).visible(() -> !followTheme.get()).build());

    private final Setting<SettingColor> globalColour = sgColours.add(new ColorSetting.Builder()
        .name("global-colour").description("Colour of the global line and its figure.")
        .defaultValue(new SettingColor(90, 200, 255)).visible(this::lineColoursShown).build());

    private final Setting<SettingColor> inventoryColour = sgColours.add(new ColorSetting.Builder()
        .name("inventory-colour").description("Colour of the inventory line and its figure.")
        .defaultValue(new SettingColor(255, 170, 60)).visible(this::lineColoursShown).build());

    private final Setting<SettingColor> interactColour = sgColours.add(new ColorSetting.Builder()
        .name("interact-colour").description("Colour of the interaction line and its figure.")
        .defaultValue(new SettingColor(220, 110, 255)).visible(this::lineColoursShown).build());

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
    /** The share of the limit at the top of the graph, eased as the lines rise and fall. */
    private double scaleTop = 1;
    private long scaleAt;

    private int[] palette;
    private long paletteAt;

    public PacketGraphHud() {
        super(INFO);
    }

    // Colours

    private boolean lineColoursShown() {
        return !followTheme.get() || customLines.get();
    }

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
        if (customBackground.get()) {
            panelC = panelColour.get();
            well = wellColour.get();
        }
        Color accent = argb(palette[3]);
        if (customLines.get()) {
            return new Color[] { panelC, well, accent, argb(palette[4]), argb(palette[5]),
                globalColour.get(), inventoryColour.get(), interactColour.get() };
        }
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
        if (!legend.get()) return 0;
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

    /** How far the graph is moved down to keep clear of the tab list, eased in and out. */
    private double tabShift;
    private long shiftAt;

    /** How far down the graph has to go to sit below the tab list while it is open, in HUD pixels. */
    private double tabClearance() {
        if (System.nanoTime() - dev.jjstools.tab.LiteTab.drawnAt > 100_000_000L) return 0;
        double scale = net.minecraft.client.MinecraftClient.getInstance().getWindow().getScaleFactor();
        double left = dev.jjstools.tab.LiteTab.boundsLeft * scale, right = dev.jjstools.tab.LiteTab.boundsRight * scale;
        double bottom = dev.jjstools.tab.LiteTab.boundsBottom * scale;
        if (x + getWidth() <= left || x >= right || y >= bottom) return 0;
        return bottom + 4 * scale - y;
    }

    @Override
    public void render(HudRenderer renderer) {
        long now = System.nanoTime();
        double dt = shiftAt == 0 ? 1 : Math.min(0.1, (now - shiftAt) / 1e9);
        shiftAt = now;
        double want = isInEditor() ? 0 : tabClearance();
        tabShift += (want - tabShift) * (1 - Math.exp(-dt * 18));
        if (Math.abs(want - tabShift) < 0.5) tabShift = want;
        int shift = (int) Math.round(tabShift);
        y += shift;
        try {
            draw(renderer);
        } finally {
            y -= shift;
        }
    }

    private void draw(HudRenderer renderer) {
        updateSize(renderer);
        sample();
        Color[] c = colours();
        double p = panel.get() ? pad() : 0;
        double r = radius.get();

        if (panel.get()) SoftShapes.roundRect(x, y, getWidth(), getHeight(), r, c[0]);

        double header = headerHeight(renderer);
        if (header > 0) drawHeader(renderer, x + p, y + p, getWidth() - p * 2, c);

        double gx = x + p, gy = y + p + header, gw = getWidth() - p * 2, gh = graphHeight.get();
        if (panel.get()) SoftShapes.roundRect(gx, gy, gw, gh, Math.max(0, r - 1), c[1]);

        // Inside the well, kept clear of its rounded corners.
        double inset = Math.max(1, Math.min(r * 0.5, 3));
        double px = gx + inset, py = gy + inset, pw = gw - inset * 2, ph = gh - inset * 2;
        int shown = Math.min(count, (int) (history.get() * 1000 / STEP_MS) + 1);
        boolean[] lines = { showGlobal.get(), showInventory.get(), showInteract.get() };
        updateScale(shown, lines);

        if (guide.get() && scaleTop >= 0.75) {
            double gyLine = py + ph - ph * 0.75 / scaleTop;
            renderer.quad(px, gyLine, pw, 1, alpha(c[4], 60));
        }

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

    /**
     * With auto-scale, the top of the graph follows the highest line shown over its history,
     * with some room above, between a tenth of the limit and the limit itself.
     */
    private void updateScale(int shown, boolean[] lines) {
        long now = System.nanoTime();
        double dt = scaleAt == 0 ? 1 : Math.min(0.1, (now - scaleAt) / 1e9);
        scaleAt = now;
        double want = 1;
        if (autoScale.get()) {
            double peak = 0;
            for (int s = 0; s < 3; s++) {
                if (!lines[s]) continue;
                for (int k = 0; k < shown; k++) peak = Math.max(peak, samples[s][Math.floorMod(head - k, MAX_SAMPLES)]);
            }
            want = Math.max(0.1, Math.min(1, peak * 1.25));
        }
        // Rises quickly so a spike is never cut off, settles back slowly.
        double rate = want > scaleTop ? 14 : 2.5;
        scaleTop += (want - scaleTop) * (1 - Math.exp(-dt * rate));
        scaleTop = Math.max(0.1, Math.min(1, scaleTop));
    }

    private void drawHeader(HudRenderer renderer, double hx, double hy, double hw, Color[] c) {
        if (!legend.get()) return;
        double scale = textScale.get();
        boolean sh = shadow.get();

        int[] now = { PacketLimits.global(), PacketLimits.inventory(), PacketLimits.interact() };
        int[] limits = { PacketLimits.globalLimit(), PacketLimits.inventoryLimit(), PacketLimits.interactLimit() };
        boolean[] on = { showGlobal.get(), showInventory.get(), showInteract.get() };

        // Left to right: "global 12/1249  inv 3/79  int 0/9", each name in its line's colour,
        // the count too (red near the limit), and the limit dimmed.
        double lx = hx + 1, right = hx + hw - 1;
        for (int i = 0; i < 3; i++) {
            if (!on[i]) continue;
            String name = NAMES[i] + " ";
            String count = String.valueOf(now[i]);
            String limit = "/" + limits[i];
            double wn = renderer.textWidth(name, sh, scale), wc = renderer.textWidth(count, sh, scale), wl = renderer.textWidth(limit, sh, scale);
            if (lx + wn + wc + wl > right) break;
            Color figure = now[i] / (double) limits[i] >= 0.9 ? warnColour.get() : c[5 + i];
            renderer.text(name, lx, hy, c[5 + i], sh, scale);
            renderer.text(count, lx + wn, hy, figure, sh, scale);
            renderer.text(limit, lx + wn + wc, hy, c[4], sh, scale);
            lx += wn + wc + wl + 8;
        }
    }

    private void drawSeries(HudRenderer renderer, double[] series, int shown, double perStep, double into,
                            double px, double py, double pw, double ph, Color colour) {
        double[] xs = new double[shown + 2], ys = new double[shown + 2];
        int n = 0;
        double prevX = 0, prevY = 0;
        for (int k = 0; k < shown; k++) {
            int idx = Math.floorMod(head - (shown - 1 - k), MAX_SAMPLES);
            double sx = px + (k - into) * perStep;
            double v = Math.max(0, Math.min(1, series[idx] / scaleTop));
            double sy = py + ph - v * ph;
            if (k > 0) {
                // Clipped to the graph's sides: a point where the line crosses each edge.
                if (prevX < px && sx >= px) {
                    xs[n] = px;
                    ys[n++] = prevY + (sy - prevY) * (px - prevX) / (sx - prevX);
                }
                if (sx >= px && sx <= px + pw) {
                    xs[n] = sx;
                    ys[n++] = sy;
                } else if (prevX <= px + pw && sx > px + pw) {
                    xs[n] = px + pw;
                    ys[n++] = prevY + (sy - prevY) * (px + pw - prevX) / (sx - prevX);
                }
            } else if (sx >= px && sx <= px + pw) {
                xs[n] = sx;
                ys[n++] = sy;
            }
            prevX = sx;
            prevY = sy;
        }
        if (n < 2) return;

        int fillA = fillOpacity.get();
        if (fillA > 0) SoftShapes.areaUnder(xs, ys, n, py + ph, alpha(colour, fillA), alpha(colour, 0));
        SoftShapes.polyline(xs, ys, n, thickness.get(), colour);
    }

}
