package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.tint.Colors;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.util.DyeColor;

import java.util.EnumMap;
import java.util.Map;

/**
 * Colours the shulker box GUI and its title to match the box.
 *
 * Was a separate mod; the logic is unchanged but the settings now live in Meteor, so Cloth Config
 * and Mod Menu are no longer needed.
 *
 * It does not replace your pack's texture. It reads whatever shulker_box.png the pack provides and
 * recolours it pixel by pixel, using the same maths ShulkerBoxTooltip uses, so the two agree on
 * what colour a box is.
 */
public class ShulkerTint extends Module {
    public enum TitleMode { Vanilla, MatchBox, InverseBrightness, Custom }
    public enum BlendMode { Colorize, Multiply }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTitle = settings.createGroup("Title");
    private final SettingGroup sgColours = settings.createGroup("Colours");

    // General

    private final Setting<Boolean> tintBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("tint-background")
        .description("Recolour the GUI panel itself, not just the title.")
        .defaultValue(true)
        .build()
    );

    private final Setting<BlendMode> blendMode = sgGeneral.add(new EnumSetting.Builder<BlendMode>()
        .name("blend-mode")
        .description("Colorize rebuilds the texture and keeps its shading. Multiply is cheaper but goes muddy on dark packs.")
        .defaultValue(BlendMode.Colorize)
        .visible(tintBackground::get)
        .build()
    );

    private final Setting<Integer> tintStrength = sgGeneral.add(new IntSetting.Builder()
        .name("tint-strength")
        .description("How far toward the box colour the panel goes.")
        .defaultValue(100).min(0).max(100).sliderRange(0, 100)
        .visible(tintBackground::get)
        .build()
    );

    private final Setting<Integer> tintHeight = sgGeneral.add(new IntSetting.Builder()
        .name("tint-height")
        .description("Pixels down the GUI that get tinted. 78 is the container half including the divider. Raise it toward 166 and the player inventory is tinted too.")
        .defaultValue(78).min(0).max(166).sliderRange(0, 166)
        .visible(tintBackground::get)
        .build()
    );

    // Title

    private final Setting<TitleMode> titleMode = sgTitle.add(new EnumSetting.Builder<TitleMode>()
        .name("title-mode")
        .description("Inverse Brightness keeps the box's hue but flips its lightness, so a dark box gets a light title.")
        .defaultValue(TitleMode.MatchBox)
        .build()
    );

    private final Setting<Integer> titleBrightness = sgTitle.add(new IntSetting.Builder()
        .name("title-brightness")
        .description("Lighter or darker than the box colour.")
        .defaultValue(60).min(-100).max(100).sliderRange(-100, 100)
        .visible(() -> titleMode.get() == TitleMode.MatchBox)
        .build()
    );

    private final Setting<Integer> inverseStrength = sgTitle.add(new IntSetting.Builder()
        .name("inverse-strength")
        .description("How far the title's lightness is flipped away from the box's.")
        .defaultValue(60).min(0).max(100).sliderRange(0, 100)
        .visible(() -> titleMode.get() == TitleMode.InverseBrightness)
        .build()
    );

    private final Setting<SettingColor> customTitleColour = sgTitle.add(new ColorSetting.Builder()
        .name("custom-title-color")
        .defaultValue(new SettingColor(255, 255, 255))
        .visible(() -> titleMode.get() == TitleMode.Custom)
        .build()
    );

    private final Setting<Boolean> overrideNameColours = sgTitle.add(new BoolSetting.Builder()
        .name("override-name-colors")
        .description("Ignore colours baked into a box's custom name, so the chosen title colour always shows.")
        .defaultValue(true)
        .build()
    );

    // Colours

    private final Setting<SettingColor> undyedColour = sgColours.add(new ColorSetting.Builder()
        .name("undyed")
        .description("An undyed box has no dye colour to read, so this is used instead.")
        .defaultValue(rgb(Colors.DEFAULT_UNDYED))
        .build()
    );

    /**
     * One colour setting per dye.
     *
     * Built from DyeColor rather than written out sixteen times, so a new dye colour would appear
     * on its own and the defaults stay tied to the shared colour table.
     */
    private final Map<DyeColor, Setting<SettingColor>> dyeColours = new EnumMap<>(DyeColor.class);

    public ShulkerTint() {
        super(JJsTools.CATEGORY, "shulker-tint", "Colours the shulker box GUI and title to match the box.");

        for (DyeColor dye : DyeColor.values()) {
            dyeColours.put(dye, sgColours.add(new ColorSetting.Builder()
                // DyeColor has no getName() here; the mod's own helper is what the old settings page used.
                .name(Colors.prettyName(dye).toLowerCase(java.util.Locale.ROOT).replace(' ', '-'))
                .defaultValue(rgb(Colors.defaultDyeRgb(dye)))
                .build()
            ));
        }
    }

    private static SettingColor rgb(int rgb) {
        return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
    }

    private static int packed(SettingColor colour) {
        return (colour.r << 16) | (colour.g << 8) | colour.b;
    }

    // Read by ShulkerBoxScreenMixin

    private static ShulkerTint active() {
        ShulkerTint module = Modules.get().get(ShulkerTint.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean on() {
        return active() != null;
    }

    public static boolean backgroundTinted() {
        ShulkerTint m = active();
        return m != null && m.tintBackground.get();
    }

    public static boolean colorizing() {
        ShulkerTint m = active();
        return m != null && m.blendMode.get() == BlendMode.Colorize;
    }

    public static int strength() {
        ShulkerTint m = active();
        return m == null ? 100 : m.tintStrength.get();
    }

    public static int splitY() {
        ShulkerTint m = active();
        return m == null ? 78 : m.tintHeight.get();
    }

    public static TitleMode titleMode() {
        ShulkerTint m = active();
        return m == null ? TitleMode.Vanilla : m.titleMode.get();
    }

    public static int titleBrightness() {
        ShulkerTint m = active();
        return m == null ? 60 : m.titleBrightness.get();
    }

    public static int inverseStrength() {
        ShulkerTint m = active();
        return m == null ? 60 : m.inverseStrength.get();
    }

    public static int customTitleColour() {
        ShulkerTint m = active();
        return m == null ? 0xFFFFFF : packed(m.customTitleColour.get());
    }

    public static boolean overrideNameColours() {
        ShulkerTint m = active();
        return m != null && m.overrideNameColours.get();
    }

    public static int colourFor(DyeColor dye) {
        ShulkerTint m = active();
        if (m == null) return Colors.defaultDyeRgb(dye);

        Setting<SettingColor> setting = m.dyeColours.get(dye);
        return setting == null ? Colors.defaultDyeRgb(dye) : packed(setting.get());
    }

    public static int undyedColour() {
        ShulkerTint m = active();
        return m == null ? Colors.DEFAULT_UNDYED : packed(m.undyedColour.get());
    }
}
