package dev.jjstools.hud;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Whatever text you want, wherever you want it, in whatever colour.
 *
 * Plain text only, no starscript. Use Meteor's own Text element if you need values in it.
 */
public class CustomTextHud extends HudElement {
    public static final HudElementInfo<CustomTextHud> INFO = new HudElementInfo<>(
        JJsHud.GROUP, "custom-text", "Shows any text you type, in any colour.", CustomTextHud::new);

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> text = sgGeneral.add(new StringSetting.Builder()
        .name("text").description("What to show.")
        .defaultValue("JJ's Tools").build());

    private final Setting<SettingColor> colour = sgGeneral.add(new ColorSetting.Builder()
        .name("color").description("Text colour.")
        .defaultValue(new SettingColor(255, 255, 255, 255)).build());

    private final Setting<Boolean> shadow = sgGeneral.add(new BoolSetting.Builder()
        .name("shadow").description("Drop shadow behind the text.")
        .defaultValue(true).build());

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale").description("Text size.")
        .defaultValue(1.0).min(0.1).sliderRange(0.5, 4.0).build());

    public CustomTextHud() {
        super(INFO);
    }

    @Override
    public void tick(HudRenderer renderer) {
        updateSize(renderer);
    }

    @Override
    public void render(HudRenderer renderer) {
        updateSize(renderer);
        renderer.text(text.get(), x, y, colour.get(), shadow.get(), scale.get());
    }

    private void updateSize(HudRenderer renderer) {
        setSize(
            renderer.textWidth(text.get(), shadow.get(), scale.get()),
            renderer.textHeight(shadow.get(), scale.get())
        );
    }
}
