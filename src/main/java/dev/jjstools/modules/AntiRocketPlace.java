package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.item.Items;

/**
 * Stops a firework rocket being fired at a block when you meant to boost yourself.
 *
 * Right clicking while looking at a block launches the rocket at that block instead of boosting
 * you, which is exactly the wrong outcome when you are gliding low over terrain or climbing out of
 * a hole you have been digging. With this on, the block is ignored and the rocket always boosts.
 *
 * Only while gliding, so rockets still work normally on the ground.
 */
public class AntiRocketPlace extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> onlyWhileGliding = sgGeneral.add(new BoolSetting.Builder()
        .name("only-while-gliding")
        .description("Only ignore the block while your elytra is open. Off means rockets never fire at blocks at all.")
        .defaultValue(true)
        .build()
    );

    public AntiRocketPlace() {
        super(JJsTools.CATEGORY, "anti-rocket-place", "Makes rockets boost you instead of firing at whatever block you are looking at.");
    }

    /** Called from InteractBlockMixin. */
    public static boolean shouldIgnoreBlock() {
        AntiRocketPlace module = Modules.get().get(AntiRocketPlace.class);
        if (module == null || !module.isActive()) return false;
        if (module.mc.player == null) return false;

        boolean holdingRocket = module.mc.player.getMainHandStack().isOf(Items.FIREWORK_ROCKET)
            || module.mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET);
        if (!holdingRocket) return false;

        return !module.onlyWhileGliding.get() || module.mc.player.isGliding();
    }
}
