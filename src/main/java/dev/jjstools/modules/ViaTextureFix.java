package dev.jjstools.modules;

import dev.jjstools.JJsTools;
import dev.jjstools.util.ViaItemResolver;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Repairs items that ViaFabricPlus translated down to an older protocol. Built for 2b2t, where you
 * run a newer client against an older protocol version.
 *
 * When the old protocol has no id for an item, ViaBackwards substitutes the nearest one it does
 * have and renames it with the version it came from: a pale oak sign arrives as
 * minecraft:birch_sign called "1.21.2 Pale Oak Sign". The original id is not stored anywhere on
 * the stack, only the name, so the name is what both halves of this work from.
 *
 * Names are tidied at display time and models are swapped at render time. The stack itself is
 * never altered, because the server still believes you hold a birch sign and the client has to
 * agree or you get ghost items.
 */
public class ViaTextureFix extends Module {
    /** "1.21.2 Pale Oak Sign" and friends. Two or three version numbers, then a space. */
    public static final Pattern VERSION_PREFIX = Pattern.compile("^\\d+\\.\\d+(\\.\\d+)?\\s+");

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> stripNames = sgGeneral.add(new BoolSetting.Builder()
        .name("strip-version-names")
        .description("Remove the version number ViaBackwards puts in front of translated item names.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> fixTextures = sgGeneral.add(new BoolSetting.Builder()
        .name("fix-textures")
        .description("Render translated items as what their name says they are, so a \"1.21.2 Pale Oak Sign\" draws as a pale oak sign. Item icons only; a block already placed in the world cannot be changed.")
        .defaultValue(true)
        .build()
    );

    public ViaTextureFix() {
        super(JJsTools.CATEGORY, "via-texture-fix", "Repairs items ViaFabricPlus had to translate down. On a 1.20.5 connection a pale oak sign arrives as a birch sign called \"1.21.2 Pale Oak Sign\"; this gives it back its name and item texture. For 2b2t.");
    }

    private static ViaTextureFix active() {
        ViaTextureFix module = Modules.get().get(ViaTextureFix.class);
        return module != null && module.isActive() ? module : null;
    }

    /** Called from ItemNameMixin. */
    public static boolean shouldStrip() {
        ViaTextureFix module = active();
        return module != null && module.stripNames.get();
    }

    /** The version prefix removed, or null if there was not one. */
    public static String stripped(String name) {
        if (name == null) return null;
        Matcher matcher = VERSION_PREFIX.matcher(name);
        return matcher.find() ? name.substring(matcher.end()) : null;
    }

    /**
     * Called from ItemModelSwapMixin for every item about to be drawn, so it has to stay cheap.
     * The early exits do the work: almost no stack has a custom name at all.
     */
    /**
     * Called for every item about to be drawn, several hundred times a frame, so it has to be
     * nearly free in the common case.
     *
     * The order of the checks matters: almost nothing has a custom name, so that one lookup throws
     * out the vast majority before any string work happens. The swapped stacks are then cached per
     * item, because building a fresh ItemStack for every item on every frame was allocating hard
     * enough to show up in the 1% lows.
     */
    public static ItemStack displayStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return stack;

        // Cheapest possible rejection first.
        Text custom = stack.get(DataComponentTypes.CUSTOM_NAME);
        if (custom == null) return stack;

        ViaTextureFix module = active();
        if (module == null || !module.fixTextures.get()) return stack;

        String name = stripped(custom.getString());
        if (name == null) return stack;

        Item real = ViaItemResolver.resolve(name, stack);
        if (real == null) return stack;

        /*
         * A fresh stack every time, on purpose.
         *
         * Handing the same cached instance to every render call broke the swap outright: the model
         * layer keeps state tied to the stack it is given, so sharing one across every draw left
         * it rendering the original item again. The allocation only happens for items that
         * actually got translated, which is a small fraction of what is on screen, and the early
         * exits above keep everything else free.
         */
        return new ItemStack(real, stack.getCount());
    }
}
