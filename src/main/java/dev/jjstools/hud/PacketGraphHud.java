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
 * Read twenty times a second and scrolled smoothly between readings, each line eased toward
 * its newest value, with a soft fill under it and a faint line at the limit and at three
 * quarters of it. The current figures are written along the top.
 */
public class PacketGraphHud extends HudElement {
    public static final HudElementInfo<PacketGraphHud> INFO = new HudElementInfo<>(
        JJsHud.GROUP, "packet-graph", "A smooth live graph of your global, inventory and interaction packets against the server's limits.", PacketGraphHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColours = settings.createGroup("Colours");

    private final Setting<Integer> width = sgGeneral.add(new IntSetting.Builder()
        .name("width").description("Width of the graph.")
        .defaultValue(160).min(40).sliderRange(60, 400).build());

    private final Setting<Integer> height = sgGeneral.add(new IntSetting.Builder()
        .name("height").description("Height of the graph.")
        .defaultValue(48).min(16).sliderRange(20, 200).build());

    private final Setting<Integer> history = sgGeneral.add(new IntSetting.Builder()
        .name("history").description("Seconds of history shown across the graph.")
        .defaultValue(10).range(2, 60).sliderRange(2, 30).build());

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing").description("How much the lines are eased between readings. 0 shows every reading as it is.")
        .defaultValue(0.5).range(0, 0.95).sliderRange(0, 0.9).build());

    private final Setting<Boolean> showGlobal = sgGeneral.add(new BoolSetting.Builder()
        .name("global").description("Show the line for all packets.").defaultValue(true).build());

    private final Setting<Boolean> showInventory = sgGeneral.add(new BoolSetting.Builder()
        .name("inventory").description("Show the line for inventory packets.").defaultValue(true).build());

    private final Setting<Boolean> showInteract = sgGeneral.add(new BoolSetting.Builder()
        .name("interact").description("Show the line for block interactions.").defaultValue(true).build());

    private final Setting<Boolean> fill = sgGeneral.add(new BoolSetting.Builder()
        .name("fill").description("A soft fill under each line.").defaultValue(true).build());

    private final Setting<Boolean> labels = sgGeneral.add(new BoolSetting.Builder()
        .name("labels").description("Write the current count against each limit along the top.").defaultValue(true).build());

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("text-shadow").description("Drop shadow behind the labels.").defaultValue(true).build());

    private final Setting<SettingColor> background = sgColours.add(new ColorSetting.Builder()
        .name("background").description("Colour behind the graph.")
        .defaultValue(new SettingColor(14, 14, 18, 140)).build());

    private final Setting<SettingColor> guides = sgColours.add(new ColorSetting.Builder()
        .name("guides").description("Colour of the lines at the limit and at three quarters of it.")
        .defaultValue(new SettingColor(255, 255, 255, 40)).build());

    private final Setting<SettingColor> globalColour = sgColours.add(new ColorSetting.Builder()
        .name("global-colour").description("Colour of the global line.")
        .defaultValue(new SettingColor(90, 200, 255, 255)).build());

    private final Setting<SettingColor> inventoryColour = sgColours.add(new ColorSetting.Builder()
        .name("inventory-colour").description("Colour of the inventory line.")
        .defaultValue(new SettingColor(255, 170, 60, 255)).build());

    private final Setting<SettingColor> interactColour = sgColours.add(new ColorSetting.Builder()
        .name("interact-colour").description("Colour of the interaction line.")
        .defaultValue(new SettingColor(220, 110, 255, 255)).build());

    private final Setting<SettingColor> warnColour = sgColours.add(new ColorSetting.Builder()
        .name("near-limit-colour").description("Colour a label turns as its line nears the limit.")
        .defaultValue(new SettingColor(255, 80, 80, 255)).build());

    private static final long STEP_MS = 50;
    private static final int MAX_SAMPLES = 60 * 1000 / (int) STEP_MS + 2;

    /** Readings as shares of each limit, oldest first, in a ring. */
    private final double[][] samples = new double[3][MAX_SAMPLES];
    private int head, count;
    private long lastSample;
    private final double[] eased = new double[3];

    public PacketGraphHud() {
        super(INFO);
    }

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

    @Override
    public void tick(HudRenderer renderer) {
        setSize(width.get(), height.get());
    }

    @Override
    public void render(HudRenderer renderer) {
        setSize(width.get(), height.get());
        sample();

        double w = width.get(), h = height.get();
        renderer.quad(x, y, w, h, background.get());

        // Guides: the limit along the top, three quarters below it.
        double top = y + 1;
        renderer.line(x, top, x + w, top, guides.get());
        Color faint = new Color(guides.get());
        faint.a = faint.a / 2;
        renderer.line(x, y + h - h * 0.75, x + w, y + h - h * 0.75, faint);

        int shown = Math.min(count, (int) (history.get() * 1000 / STEP_MS) + 1);
        if (shown >= 2) {
            // Scrolled by how far into the next reading we are, so it glides rather than steps.
            double perStep = w / (shown - 2);
            double into = Math.min(1, (System.currentTimeMillis() - lastSample) / (double) STEP_MS);
            boolean[] on = { showGlobal.get(), showInventory.get(), showInteract.get() };
            Color[] colours = { globalColour.get(), inventoryColour.get(), interactColour.get() };
            // Drawn back to front: interaction, inventory, global.
            for (int s = 2; s >= 0; s--) {
                if (!on[s]) continue;
                drawSeries(renderer, samples[s], shown, perStep, into, w, h, colours[s]);
            }
        }

        if (labels.get()) drawLabels(renderer, w);
    }

    private void drawSeries(HudRenderer renderer, double[] series, int shown, double perStep, double into, double w, double h, Color colour) {
        Color fillTop = new Color(colour);
        fillTop.a = fillTop.a * 70 / 255;
        Color fillBottom = new Color(colour);
        fillBottom.a = 0;

        double prevX = 0, prevY = 0;
        boolean first = true;
        for (int k = 0; k < shown; k++) {
            int idx = Math.floorMod(head - (shown - 1 - k), MAX_SAMPLES);
            double px = x + (k - into) * perStep;
            double v = Math.max(0, Math.min(1, series[idx]));
            double py = y + h - v * (h - 2) - 1;
            if (!first) {
                // Clipped to the graph's sides.
                double x1 = Math.max(x, prevX), x2 = Math.min(x + w, px);
                if (x2 > x1) {
                    double t1 = (x1 - prevX) / (px - prevX), t2 = (x2 - prevX) / (px - prevX);
                    double y1 = prevY + (py - prevY) * t1, y2 = prevY + (py - prevY) * t2;
                    if (fill.get()) {
                        double ty = Math.min(y1, y2);
                        renderer.quad(x1, ty, x2 - x1, y + h - ty, fillTop, fillTop, fillBottom, fillBottom);
                    }
                    renderer.line(x1, y1, x2, y2, colour);
                }
            }
            prevX = px;
            prevY = py;
            first = false;
        }
    }

    private void drawLabels(HudRenderer renderer, double w) {
        String[] names = { "G ", "I ", "B " };
        int[] now = { PacketLimits.global(), PacketLimits.inventory(), PacketLimits.interact() };
        int[] limits = { PacketLimits.globalLimit(), PacketLimits.inventoryLimit(), PacketLimits.interactLimit() };
        boolean[] on = { showGlobal.get(), showInventory.get(), showInteract.get() };
        Color[] colours = { globalColour.get(), inventoryColour.get(), interactColour.get() };
        double scale = 0.75;
        double lx = x + 3;
        double ly = y + 3;
        for (int i = 0; i < 3; i++) {
            if (!on[i]) continue;
            String text = names[i] + now[i] + "/" + limits[i];
            double share = now[i] / (double) limits[i];
            Color c = share >= 0.9 ? warnColour.get() : colours[i];
            renderer.text(text, lx, ly, c, shadow.get(), scale);
            lx += renderer.textWidth(text, shadow.get(), scale) + 6;
            if (lx > x + w - 10) break;
        }
    }
}
