package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;

/**
 * Fixes enchantment tooltips scrambled by ViaFabricPlus: curses red, everything else grey,
 * and enchantments listed in vanilla order. Works on every enchanted item and enchanted books.
 * The actual work is done in EnchantmentNameMixin and ItemEnchantmentsComponentMixin.
 */
public class ViaEnchantFix extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> fixColours = sgGeneral.add(new BoolSetting.Builder()
        .name("fix-colours")
        .description("Makes curses red and every other vanilla enchantment grey.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fixOrder = sgGeneral.add(new BoolSetting.Builder()
        .name("fix-order")
        .description("Lists enchantments in vanilla order (curses first, Unbreaking and Mending last).")
        .defaultValue(true)
        .build()
    );

    public ViaEnchantFix() {
        super(JJsTools.CATEGORY, "via-enchant-fix", "Fixes enchantment tooltip colours and order broken by ViaFabricPlus. begone evil mending!");
    }

    private static ViaEnchantFix instance() {
        Modules modules = Modules.get();
        return modules == null ? null : modules.get(ViaEnchantFix.class);
    }

    public static boolean shouldFixColours() {
        ViaEnchantFix m = instance();
        return m != null && m.isActive() && m.fixColours.get();
    }

    private final Setting<Boolean> noItalicRenamed = sgGeneral.add(new BoolSetting.Builder()
        .name("no-italic-renamed")
        .description("Don't italicise the name of renamed items in tooltips. Applies to every item.")
        .defaultValue(true)
        .build()
    );

    public static boolean shouldUnitaliciseNames() {
        ViaEnchantFix m = instance();
        return m != null && m.isActive() && m.noItalicRenamed.get();
    }

    public static boolean shouldFixOrder() {
        ViaEnchantFix m = instance();
        return m != null && m.isActive() && m.fixOrder.get();
    }
}
