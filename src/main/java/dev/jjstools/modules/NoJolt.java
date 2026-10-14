package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Holds the health and hunger icons still.
 *
 * Vanilla shakes them to get your attention: the food icons jiggle once your saturation is gone,
 * and the hearts jiggle once your health and absorption together are down to two hearts. Both are
 * movement in the corner of your eye at exactly the moment you want to be reading the numbers,
 * and on a server where you are watching your totems and your health closely it is noise rather
 * than a warning.
 *
 * Nothing about the game changes, only whether the icons sit still.
 */
public class NoJolt extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> hunger = sgGeneral.add(new BoolSetting.Builder()
        .name("hunger-jolt")
        .description("Hold the food icons still when your saturation has run out.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hearts = sgGeneral.add(new BoolSetting.Builder()
        .name("heart-jolt")
        .description("Hold the hearts still when your health and absorption together are low.")
        .defaultValue(true)
        .build()
    );

    public NoJolt() {
        super(JJsTools.CATEGORY, "no-jolt", "Stops the hunger and health icons shaking at you.");
    }

    // Read by InGameHudJoltMixin

    private static NoJolt active() {
        Modules modules = Modules.get();
        if (modules == null) return null;

        NoJolt module = modules.get(NoJolt.class);
        return module != null && module.isActive() ? module : null;
    }

    public static boolean steadyHunger() {
        NoJolt module = active();
        return module != null && module.hunger.get();
    }

    public static boolean steadyHearts() {
        NoJolt module = active();
        return module != null && module.hearts.get();
    }
}
