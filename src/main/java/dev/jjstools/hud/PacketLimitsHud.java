package dev.jjstools.hud;

import dev.jjstools.util.PacketLimits;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * How many packets you are sending against the server's limits: global, inventory and
 * interaction, each as count / limit, going from green to red as it fills.
 */
public class PacketLimitsHud extends HudElement {
    public static final HudElementInfo<PacketLimitsHud> INFO = new HudElementInfo<>(
        JJsHud.GROUP, "packet-limits", "Shows how many global, inventory and interaction packets you are sending against the server's limits.", PacketLimitsHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow").description("Drop shadow behind the text.")
        .defaultValue(true).build());

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale").description("Text size.")
        .defaultValue(1.0).min(0.1).sliderRange(0.5, 4.0).build());

    private final Setting<SettingColor> low = sgGeneral.add(new ColorSetting.Builder()
        .name("low-colour").description("Colour while well under the limit.")
        .defaultValue(new SettingColor(120, 220, 120, 255)).build());

    private final Setting<SettingColor> high = sgGeneral.add(new ColorSetting.Builder()
        .name("high-colour").description("Colour at the limit.")
        .defaultValue(new SettingColor(240, 80, 80, 255)).build());

    public PacketLimitsHud() {
        super(INFO);
    }

    private String[] lines() {
        return new String[] {
            "global: " + PacketLimits.global() + "/" + PacketLimits.globalLimit(),
            "inv: " + PacketLimits.inventory() + "/" + PacketLimits.inventoryLimit(),
            "int: " + PacketLimits.interact() + "/" + PacketLimits.interactLimit() };
    }

    private double[] fills() {
        return new double[] {
            PacketLimits.global() / (double) PacketLimits.globalLimit(),
            PacketLimits.inventory() / (double) PacketLimits.inventoryLimit(),
            PacketLimits.interact() / (double) PacketLimits.interactLimit() };
    }

    @Override
    public void tick(HudRenderer renderer) {
        updateSize(renderer);
    }

    @Override
    public void render(HudRenderer renderer) {
        updateSize(renderer);
        String[] lines = lines();
        double[] fills = fills();
        double lineHeight = renderer.textHeight(shadow.get(), scale.get());
        for (int i = 0; i < lines.length; i++) {
            renderer.text(lines[i], x, y + i * lineHeight, blend(fills[i]), shadow.get(), scale.get());
        }
    }

    private Color blend(double fill) {
        double t = Math.max(0, Math.min(1, fill));
        Color a = low.get(), b = high.get();
        return new Color((int) (a.r + (b.r - a.r) * t), (int) (a.g + (b.g - a.g) * t), (int) (a.b + (b.b - a.b) * t), (int) (a.a + (b.a - a.a) * t));
    }

    private void updateSize(HudRenderer renderer) {
        double width = 0;
        for (String line : lines()) width = Math.max(width, renderer.textWidth(line, shadow.get(), scale.get()));
        setSize(width, renderer.textHeight(shadow.get(), scale.get()) * 3);
    }
}
